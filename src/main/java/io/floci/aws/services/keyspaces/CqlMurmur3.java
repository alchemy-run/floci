package io.floci.aws.services.keyspaces;

import java.nio.ByteBuffer;

/**
 * Cassandra's Murmur3 partitioner token: the first 64 bits of MurmurHash3 x64 128 over a partition key's
 * serialized form (a single component as is; a composite as {@code [short length][bytes][0]} per component), with
 * {@code Long.MIN_VALUE} mapped to {@code Long.MAX_VALUE}.
 */
final class CqlMurmur3 {

    private CqlMurmur3() {
    }

    static long token(byte[][] components) {
        byte[] key;
        if (components.length == 1) {
            key = components[0] == null ? new byte[0] : components[0];
        } else {
            int size = 0;
            for (byte[] c : components) {
                size += 3 + (c == null ? 0 : c.length);
            }
            ByteBuffer b = ByteBuffer.allocate(size);
            for (byte[] c : components) {
                byte[] v = c == null ? new byte[0] : c;
                b.putShort((short) v.length).put(v).put((byte) 0);
            }
            key = b.array();
        }
        long h = hash(key);
        return h == Long.MIN_VALUE ? Long.MAX_VALUE : h;
    }

    private static long getBlock(byte[] key, int offset, int index) {
        int i = offset + (index << 3);
        return ((long) key[i] & 0xff) | (((long) key[i + 1] & 0xff) << 8) | (((long) key[i + 2] & 0xff) << 16)
                | (((long) key[i + 3] & 0xff) << 24) | (((long) key[i + 4] & 0xff) << 32)
                | (((long) key[i + 5] & 0xff) << 40) | (((long) key[i + 6] & 0xff) << 48)
                | (((long) key[i + 7] & 0xff) << 56);
    }

    private static long fmix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** The first half of MurmurHash3 x64 128 with seed 0, with Cassandra's sign-extended tail bytes. */
    static long hash(byte[] key) {
        int length = key.length;
        int nblocks = length >> 4;
        long h1 = 0;
        long h2 = 0;
        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;
        for (int i = 0; i < nblocks; i++) {
            long k1 = getBlock(key, 0, i * 2);
            long k2 = getBlock(key, 0, i * 2 + 1);
            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;
            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }
        int offset = nblocks * 16;
        long k1 = 0;
        long k2 = 0;
        switch (length & 15) {
            case 15:
                k2 ^= ((long) key[offset + 14]) << 48;
                // fall through
            case 14:
                k2 ^= ((long) key[offset + 13]) << 40;
                // fall through
            case 13:
                k2 ^= ((long) key[offset + 12]) << 32;
                // fall through
            case 12:
                k2 ^= ((long) key[offset + 11]) << 24;
                // fall through
            case 11:
                k2 ^= ((long) key[offset + 10]) << 16;
                // fall through
            case 10:
                k2 ^= ((long) key[offset + 9]) << 8;
                // fall through
            case 9:
                k2 ^= key[offset + 8];
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
                // fall through
            case 8:
                k1 ^= ((long) key[offset + 7]) << 56;
                // fall through
            case 7:
                k1 ^= ((long) key[offset + 6]) << 48;
                // fall through
            case 6:
                k1 ^= ((long) key[offset + 5]) << 40;
                // fall through
            case 5:
                k1 ^= ((long) key[offset + 4]) << 32;
                // fall through
            case 4:
                k1 ^= ((long) key[offset + 3]) << 24;
                // fall through
            case 3:
                k1 ^= ((long) key[offset + 2]) << 16;
                // fall through
            case 2:
                k1 ^= ((long) key[offset + 1]) << 8;
                // fall through
            case 1:
                k1 ^= key[offset];
                k1 *= c1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix(h1);
        h2 = fmix(h2);
        h1 += h2;
        return h1;
    }
}
