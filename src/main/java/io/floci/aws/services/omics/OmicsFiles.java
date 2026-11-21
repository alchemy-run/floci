package io.floci.aws.services.omics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/**
 * What HealthOmics reads from genomics files: a FASTA reference's samtools index ({@code .fai}), and the reads and bases
 * of a FASTQ (plain or gzipped) or BAM read set, walked record by record. CRAM needs its reference to decode, so it isn't
 * counted.
 */
final class OmicsFiles {

    /** The size of the parts references and read sets are read in, as GetReference and GetReadSet serve them. */
    static final long PART_SIZE = 100L * 1024 * 1024;

    /** A read set's sequence counts and whether its reads are aligned. */
    record Counts(long reads, long bases, String alignment) {
    }

    private OmicsFiles() {
    }

    static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean gzipped(byte[] data) {
        return data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    /** Gunzips every member of a (BGZF) gzip file. */
    static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** The samtools faidx index of a FASTA file, failing when it isn't FASTA or its line lengths vary. */
    static String faidx(byte[] fasta) {
        String text = new String(fasta, StandardCharsets.US_ASCII);
        if (!text.startsWith(">")) {
            throw new IllegalArgumentException("The file isn't FASTA: it doesn't start with a > header line.");
        }
        StringBuilder index = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            int headerEnd = text.indexOf('\n', at);
            if (headerEnd < 0) {
                headerEnd = text.length();
            }
            String name = text.substring(at + 1, headerEnd).trim().split("\\s+")[0];
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A FASTA sequence has no name.");
            }
            long offset = Math.min(headerEnd + 1, text.length());
            int cursor = (int) offset;
            long length = 0;
            int lineBases = -1;
            int lineWidth = -1;
            boolean shortLine = false;
            while (cursor < text.length() && text.charAt(cursor) != '>') {
                int end = text.indexOf('\n', cursor);
                int next = end < 0 ? text.length() : end + 1;
                String line = text.substring(cursor, end < 0 ? text.length() : end);
                int width = next - cursor;
                String bases = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!bases.isEmpty()) {
                    if (shortLine) {
                        throw new IllegalArgumentException("Sequence " + name + " has lines of different lengths.");
                    }
                    if (lineBases < 0) {
                        lineBases = bases.length();
                        lineWidth = width;
                    } else if (bases.length() > lineBases) {
                        throw new IllegalArgumentException("Sequence " + name + " has lines of different lengths.");
                    } else if (bases.length() < lineBases) {
                        shortLine = true;
                    }
                    length += bases.length();
                }
                cursor = next;
            }
            index.append(name).append('\t').append(length).append('\t').append(offset).append('\t')
                    .append(Math.max(lineBases, 0)).append('\t').append(Math.max(lineWidth, 0)).append('\n');
            at = cursor;
        }
        return index.toString();
    }

    /** Counts a read set's reads and bases. */
    static Counts count(String type, byte[] data) throws IOException {
        return switch (type) {
            case "FASTQ" -> fastq(gzipped(data) ? gunzip(data) : data);
            case "BAM", "UBAM" -> bam(gunzip(data), type);
            default -> new Counts(0, 0, "ALIGNED");
        };
    }

    private static Counts fastq(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII);
        String[] lines = text.split("\\r?\\n");
        long reads = 0;
        long bases = 0;
        int i = 0;
        while (i < lines.length) {
            if (lines[i].isEmpty()) {
                i++;
                continue;
            }
            if (!lines[i].startsWith("@") || i + 3 >= lines.length || !lines[i + 2].startsWith("+")) {
                throw new IllegalArgumentException("The file isn't FASTQ: record " + (reads + 1) + " is malformed.");
            }
            if (lines[i + 1].length() != lines[i + 3].length()) {
                throw new IllegalArgumentException("FASTQ record " + (reads + 1) + "'s quality and sequence lengths "
                        + "differ.");
            }
            reads++;
            bases += lines[i + 1].length();
            i += 4;
        }
        return new Counts(reads, bases, "UNALIGNED");
    }

    private static Counts bam(byte[] data, String type) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 4 || data[0] != 'B' || data[1] != 'A' || data[2] != 'M' || data[3] != 1) {
            throw new IllegalArgumentException("The file isn't BAM: its magic number is wrong.");
        }
        buffer.position(4);
        int textLength = buffer.getInt();
        buffer.position(buffer.position() + textLength);
        int references = buffer.getInt();
        for (int i = 0; i < references; i++) {
            int nameLength = buffer.getInt();
            buffer.position(buffer.position() + nameLength + 4);
        }
        long reads = 0;
        long bases = 0;
        boolean aligned = false;
        while (buffer.remaining() >= 4) {
            int blockSize = buffer.getInt();
            int start = buffer.position();
            if (blockSize < 32 || start + blockSize > data.length) {
                throw new IllegalArgumentException("BAM record " + (reads + 1) + " is truncated.");
            }
            int referenceId = buffer.getInt(start);
            int sequenceLength = buffer.getInt(start + 16);
            aligned |= referenceId >= 0;
            reads++;
            bases += sequenceLength;
            buffer.position(start + blockSize);
        }
        return new Counts(reads, bases, "UBAM".equals(type) || !aligned ? "UNALIGNED" : "ALIGNED");
    }

    /** A part of a file: {@code partNumber} counts from 1. */
    static byte[] part(byte[] data, int partNumber) {
        long start = (partNumber - 1L) * PART_SIZE;
        if (partNumber < 1 || (start >= data.length && !(data.length == 0 && partNumber == 1))) {
            throw OmicsStore.range("Part " + partNumber + " is past the end of the file.");
        }
        int end = (int) Math.min(data.length, start + PART_SIZE);
        byte[] slice = new byte[end - (int) start];
        System.arraycopy(data, (int) start, slice, 0, slice.length);
        return slice;
    }

    static long parts(long length) {
        return Math.max(1, (length + PART_SIZE - 1) / PART_SIZE);
    }
}
