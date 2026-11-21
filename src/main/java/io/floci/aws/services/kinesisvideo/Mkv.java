package io.floci.aws.services.kinesisvideo;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Matroska (EBML) a Kinesis Video producer sends and a consumer reads: a stream of one segment whose clusters are
 * fragments. A {@link Reader} splits a producer's stream into its header (the EBML header, segment info and tracks)
 * and its clusters; {@link #fragment} writes one fragment as a consumer receives it: a whole Matroska document with
 * the stream's header, a cluster, and the {@code AWS_KINESISVIDEO_*} tags naming the fragment.
 */
final class Mkv {

    static final int EBML = 0x1A45DFA3;
    static final int SEGMENT = 0x18538067;
    static final int SEEK_HEAD = 0x114D9B74;
    static final int INFO = 0x1549A966;
    static final int TIMECODE_SCALE = 0x2AD7B1;
    static final int TRACKS = 0x1654AE6B;
    static final int TRACK_ENTRY = 0xAE;
    static final int TRACK_NUMBER = 0xD7;
    static final int CODEC_ID = 0x86;
    static final int CODEC_PRIVATE = 0x63A2;
    static final int CLUSTER = 0x1F43B675;
    static final int TIMECODE = 0xE7;
    static final int SIMPLE_BLOCK = 0xA3;
    static final int BLOCK_GROUP = 0xA0;
    static final int BLOCK = 0xA1;
    static final int CUES = 0x1C53BB6B;
    static final int TAGS = 0x1254C367;
    static final int TAG = 0x7373;
    static final int SIMPLE_TAG = 0x67C8;
    static final int TAG_NAME = 0x45A3;
    static final int TAG_STRING = 0x4487;
    static final int VOID = 0xEC;
    static final long UNKNOWN = -1;

    private Mkv() {
    }

    /** An element's ID and the size of its data, or {@link #UNKNOWN}. */
    record Header(int id, long size, int length) {
    }

    /** One cluster: its timecode (in the segment's timecode scale), its bytes, and the frames' last timecode. */
    record Cluster(long timecode, byte[] bytes, long lastBlockTimecode, boolean keyFrame) {
    }

    /** The parts of a producer's stream before its clusters, and the tracks' codecs. */
    record StreamHeader(byte[] ebml, byte[] info, byte[] tracks, long timecodeScale, List<String> codecs,
                        boolean codecPrivate) {
    }

    // ---------------------------------------------------------------- reading

    /** Reads a producer's Matroska stream element by element. */
    static final class Reader {
        private final InputStream in;
        private StreamHeader header;
        private byte[] ebml = new byte[0];
        private byte[] info = new byte[0];
        private byte[] tracks = new byte[0];
        private long scale = 1_000_000;
        private long remaining = UNKNOWN;

        Reader(InputStream in) {
            this.in = in;
        }

        StreamHeader header() {
            return header;
        }

        /** The next cluster, or null at the end of the stream. A new segment replaces the header. */
        Cluster next() throws IOException {
            while (true) {
                Header h = readHeader(in);
                if (h == null) {
                    return null;
                }
                switch (h.id()) {
                    case EBML -> ebml = concat(encodeHeader(h), readFully(h.size()));
                    case SEGMENT -> {
                        remaining = h.size();
                        header = null;
                    }
                    case INFO -> {
                        byte[] body = readFully(h.size());
                        info = concat(encodeHeader(h), body);
                        Long value = uint(body, TIMECODE_SCALE);
                        scale = value == null ? 1_000_000 : value;
                    }
                    case TRACKS -> {
                        byte[] body = readFully(h.size());
                        tracks = concat(encodeHeader(h), body);
                        header = new StreamHeader(ebml, info, tracks, scale, codecs(body), hasCodecPrivate(body));
                    }
                    case CLUSTER -> {
                        if (header == null) {
                            throw new IOException("The stream has a cluster before its tracks.");
                        }
                        byte[] body = h.size() == UNKNOWN ? readUnknownCluster() : readFully(h.size());
                        return cluster(body);
                    }
                    default -> skip(h.size());
                }
            }
        }

        private byte[] readUnknownCluster() throws IOException {
            throw new IOException("Clusters of unknown size aren't supported; give each cluster its size.");
        }

        private byte[] readFully(long size) throws IOException {
            if (size < 0 || size > 512L * 1024 * 1024) {
                throw new IOException("An element of " + size + " bytes is too large.");
            }
            byte[] data = in.readNBytes((int) size);
            if (data.length != size) {
                throw new EOFException("The stream ended inside an element.");
            }
            return data;
        }

        private void skip(long size) throws IOException {
            if (size == UNKNOWN) {
                return;
            }
            long skipped = in.skip(size);
            while (skipped < size) {
                if (in.read() < 0) {
                    throw new EOFException("The stream ended inside an element.");
                }
                skipped++;
            }
        }
    }

    private static Cluster cluster(byte[] body) throws IOException {
        long timecode = 0;
        long last = 0;
        boolean key = false;
        boolean first = true;
        int at = 0;
        while (at < body.length) {
            Header h = readHeader(body, at);
            int start = at + h.length();
            if (h.id() == TIMECODE) {
                timecode = readUint(body, start, (int) h.size());
            } else if (h.id() == SIMPLE_BLOCK || h.id() == BLOCK_GROUP) {
                int block = start;
                int blockSize = (int) h.size();
                if (h.id() == BLOCK_GROUP) {
                    int[] inner = findChild(body, start, (int) h.size(), BLOCK);
                    if (inner == null) {
                        at = start + (int) h.size();
                        continue;
                    }
                    block = inner[0];
                    blockSize = inner[1];
                }
                int[] trackLength = new int[1];
                vint(body, block, trackLength);
                int offset = block + trackLength[0];
                short relative = (short) (((body[offset] & 0xff) << 8) | (body[offset + 1] & 0xff));
                last = Math.max(last, timecode + relative);
                if (first) {
                    key = h.id() == SIMPLE_BLOCK ? (body[offset + 2] & 0x80) != 0 : true;
                    first = false;
                }
                if (blockSize < 0) {
                    throw new IOException("Invalid block.");
                }
            }
            at = start + (int) h.size();
        }
        return new Cluster(timecode, concat(encodeHeader(new Header(CLUSTER, body.length, 0)), body), last, key);
    }

    /** Where a child element's data starts and how long it is, or null. */
    private static int[] findChild(byte[] data, int start, int size, int id) {
        int at = start;
        while (at < start + size) {
            Header h = readHeader(data, at);
            if (h.id() == id) {
                return new int[] {at + h.length(), (int) h.size()};
            }
            at += h.length() + (int) h.size();
        }
        return null;
    }

    private static List<String> codecs(byte[] tracks) {
        List<String> codecs = new ArrayList<>();
        int at = 0;
        while (at < tracks.length) {
            Header h = readHeader(tracks, at);
            int start = at + h.length();
            if (h.id() == TRACK_ENTRY) {
                int inner = start;
                while (inner < start + h.size()) {
                    Header c = readHeader(tracks, inner);
                    if (c.id() == CODEC_ID) {
                        codecs.add(new String(tracks, inner + c.length(), (int) c.size(), StandardCharsets.US_ASCII)
                                .trim());
                    }
                    inner += c.length() + (int) c.size();
                }
            }
            at = start + (int) h.size();
        }
        return codecs;
    }

    private static boolean hasCodecPrivate(byte[] tracks) {
        int at = 0;
        while (at < tracks.length) {
            Header h = readHeader(tracks, at);
            int start = at + h.length();
            if (h.id() == TRACK_ENTRY) {
                int inner = start;
                while (inner < start + h.size()) {
                    Header c = readHeader(tracks, inner);
                    if (c.id() == CODEC_PRIVATE && c.size() > 0) {
                        return true;
                    }
                    inner += c.length() + (int) c.size();
                }
            }
            at = start + (int) h.size();
        }
        return false;
    }

    /** An unsigned integer child of an element body, or null. */
    private static Long uint(byte[] body, int id) {
        int at = 0;
        while (at < body.length) {
            Header h = readHeader(body, at);
            if (h.id() == id) {
                return readUint(body, at + h.length(), (int) h.size());
            }
            at += h.length() + (int) Math.max(0, h.size());
        }
        return null;
    }

    static long readUint(byte[] data, int at, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = v << 8 | (data[at + i] & 0xff);
        }
        return v;
    }

    /** Reads an element header from a stream; null at a clean end. */
    static Header readHeader(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        int idLength = Integer.numberOfLeadingZeros(first) - 23;
        if (idLength < 1 || idLength > 4) {
            throw new IOException("Invalid element ID.");
        }
        int id = first;
        for (int i = 1; i < idLength; i++) {
            id = id << 8 | readByte(in);
        }
        int sizeFirst = readByte(in);
        int sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 23;
        if (sizeLength < 1 || sizeLength > 8) {
            throw new IOException("Invalid element size.");
        }
        long size = sizeFirst & (0xff >> sizeLength);
        boolean allOnes = size == (0xff >> sizeLength);
        for (int i = 1; i < sizeLength; i++) {
            int b = readByte(in);
            allOnes &= b == 0xff;
            size = size << 8 | b;
        }
        return new Header(id, allOnes ? UNKNOWN : size, idLength + sizeLength);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("The stream ended inside an element header.");
        }
        return b;
    }

    static Header readHeader(byte[] data, int at) {
        int first = data[at] & 0xff;
        int idLength = Integer.numberOfLeadingZeros(first) - 23;
        int id = first;
        for (int i = 1; i < idLength; i++) {
            id = id << 8 | (data[at + i] & 0xff);
        }
        int[] sizeLength = new int[1];
        long size = vint(data, at + idLength, sizeLength);
        return new Header(id, size, idLength + sizeLength[0]);
    }

    /** A variable-length size; {@link #UNKNOWN} when all its value bits are set. */
    static long vint(byte[] data, int at, int[] length) {
        int first = data[at] & 0xff;
        int n = Integer.numberOfLeadingZeros(first) - 23;
        long v = first & (0xff >> n);
        boolean allOnes = v == (0xff >> n);
        for (int i = 1; i < n; i++) {
            int b = data[at + i] & 0xff;
            allOnes &= b == 0xff;
            v = v << 8 | b;
        }
        length[0] = n;
        return allOnes ? UNKNOWN : v;
    }

    // ---------------------------------------------------------------- writing

    static byte[] encodeId(int id) {
        if ((id & 0xff000000) != 0) {
            return new byte[] {(byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
        }
        if ((id & 0xff0000) != 0) {
            return new byte[] {(byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
        }
        if ((id & 0xff00) != 0) {
            return new byte[] {(byte) (id >>> 8), (byte) id};
        }
        return new byte[] {(byte) id};
    }

    static byte[] encodeSize(long size) {
        if (size == UNKNOWN) {
            return new byte[] {0x01, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff};
        }
        int n = 1;
        while (n < 8 && size >= (1L << (7 * n)) - 1) {
            n++;
        }
        byte[] out = new byte[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = (byte) size;
            size >>>= 8;
        }
        out[0] |= (byte) (0x80 >> (n - 1));
        return out;
    }

    static byte[] encodeHeader(Header h) {
        return concat(encodeId(h.id()), encodeSize(h.size()));
    }

    static byte[] element(int id, byte[] body) {
        return concat(encodeId(id), encodeSize(body.length), body);
    }

    static byte[] string(int id, String value) {
        return element(id, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** A {@code Tags} element with one tag holding the given simple tags. */
    static byte[] tags(Map<String, String> tags) {
        ByteArrayOutputStream simple = new ByteArrayOutputStream();
        tags.forEach((name, value) -> simple.writeBytes(element(SIMPLE_TAG, concat(string(TAG_NAME, name),
                string(TAG_STRING, value)))));
        return element(TAGS, element(TAG, simple.toByteArray()));
    }

    /**
     * One fragment as {@code GetMedia} sends it: the EBML header, a segment of unknown size with the stream's info and
     * tracks, the fragment's tags, and its cluster.
     */
    static byte[] fragment(byte[] ebml, byte[] info, byte[] tracks, Map<String, String> tags, byte[] cluster) {
        return concat(ebml, encodeId(SEGMENT), encodeSize(UNKNOWN), info, tracks, tags(tags), cluster);
    }

    /** The tags that close a GetMedia response: where to continue. */
    static byte[] continuation(byte[] ebml, byte[] info, byte[] tracks, String token) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("AWS_KINESISVIDEO_CONTINUATION_TOKEN", token);
        return concat(ebml, encodeId(SEGMENT), encodeSize(UNKNOWN), info, tracks, tags(tags));
    }
}
