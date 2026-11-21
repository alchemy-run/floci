package io.floci.aws.services.glacier;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Glacier's SHA-256 tree hash: the SHA-256 of each 1 MiB chunk, then of each pair of hashes, level by level, until
 * one is left (a hash without a pair is carried up as it is). A part or range aligned on a power-of-two number of
 * mebibytes has a tree hash that is a node of its archive's tree, so the hashes of parts combine into the archive's.
 */
final class GlacierTreeHash {

    static final int MEBIBYTE = 1024 * 1024;

    private GlacierTreeHash() {
    }

    /** The tree hash of {@code length} bytes of {@code data} from {@code offset}, in lowercase hex. */
    static String of(byte[] data, long offset, long length) {
        List<byte[]> level = new ArrayList<>();
        if (length == 0) {
            level.add(sha256(data, 0, 0));
        }
        for (long start = offset; start < offset + length; start += MEBIBYTE) {
            int size = (int) Math.min(MEBIBYTE, offset + length - start);
            level.add(sha256(data, (int) start, size));
        }
        return HexFormat.of().formatHex(combine(level));
    }

    static String of(byte[] data) {
        return of(data, 0, data.length);
    }

    /** Combines the tree hashes of consecutive, equally sized, power-of-two pieces into the hash of the whole. */
    static String combineHex(List<String> hashes) {
        List<byte[]> level = new ArrayList<>();
        hashes.forEach(hash -> level.add(HexFormat.of().parseHex(hash)));
        return HexFormat.of().formatHex(combine(level));
    }

    private static byte[] combine(List<byte[]> hashes) {
        List<byte[]> level = hashes;
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 < level.size()) {
                    byte[] pair = new byte[level.get(i).length + level.get(i + 1).length];
                    System.arraycopy(level.get(i), 0, pair, 0, level.get(i).length);
                    System.arraycopy(level.get(i + 1), 0, pair, level.get(i).length, level.get(i + 1).length);
                    next.add(sha256(pair, 0, pair.length));
                } else {
                    next.add(level.get(i));
                }
            }
            level = next;
        }
        return level.get(0);
    }

    /** Whether a byte range is tree-hash aligned: its hash is a node of the archive's tree. */
    static boolean aligned(long start, long end, long archiveSize) {
        long length = end - start + 1;
        if (start % MEBIBYTE != 0) {
            return false;
        }
        if (end + 1 == archiveSize) {
            long chunks = (length + MEBIBYTE - 1) / MEBIBYTE;
            long span = Long.highestOneBit(Math.max(1, chunks));
            span = span < chunks ? span * 2 : span;
            return (start / MEBIBYTE) % span == 0;
        }
        if (length % MEBIBYTE != 0) {
            return false;
        }
        long chunks = length / MEBIBYTE;
        return Long.bitCount(chunks) == 1 && (start / MEBIBYTE) % chunks == 0;
    }

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data, 0, data.length));
    }

    private static byte[] sha256(byte[] data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
