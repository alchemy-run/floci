package io.github.hectorvent.floci.services.amp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus remote-write protobuf + raw snappy (block format).
 *
 * <pre>
 * message WriteRequest { repeated TimeSeries timeseries = 1; }
 * message TimeSeries   { repeated Label labels = 1; repeated Sample samples = 2; }
 * message Label        { string name = 1; string value = 2; }
 * message Sample       { double value = 1; int64 timestamp = 2; }
 * </pre>
 */
public final class AmpRemoteWriteCodec {

    private AmpRemoteWriteCodec() {
    }

    public record Sample(double value, long timestampMs) {
    }

    public record Series(Map<String, String> labels, List<Sample> samples) {
    }

    public static byte[] snappyCompress(byte[] input) {
        byte[] header = encodeVarint(input.length);
        int chunkSize = 65536;
        int chunkCount = input.length == 0 ? 1 : (input.length + chunkSize - 1) / chunkSize;
        int total = header.length;
        if (input.length == 0) {
            total += 1;
        } else {
            for (int i = 0; i < chunkCount; i++) {
                int len = Math.min(chunkSize, input.length - i * chunkSize);
                total += (len <= 60 ? 1 : 3) + len;
            }
        }
        byte[] out = new byte[total];
        System.arraycopy(header, 0, out, 0, header.length);
        int offset = header.length;
        if (input.length == 0) {
            out[offset] = 0x00;
            return out;
        }
        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int len = Math.min(chunkSize, input.length - start);
            if (len <= 60) {
                out[offset++] = (byte) ((len - 1) << 2);
            } else {
                out[offset++] = (byte) (61 << 2);
                out[offset++] = (byte) ((len - 1) & 0xff);
                out[offset++] = (byte) (((len - 1) >> 8) & 0xff);
            }
            System.arraycopy(input, start, out, offset, len);
            offset += len;
        }
        return out;
    }

    public static byte[] snappyDecompress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        int[] pos = {0};
        long uncompressed = readVarint(input, pos);
        if (uncompressed < 0 || uncompressed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid snappy uncompressed length");
        }
        byte[] out = new byte[(int) uncompressed];
        int written = 0;
        while (pos[0] < input.length && written < out.length) {
            int tag = input[pos[0]++] & 0xff;
            int type = tag & 0x03;
            if (type == 0) {
                int lenMinus1 = tag >> 2;
                if (lenMinus1 >= 60) {
                    int extra = lenMinus1 - 59;
                    lenMinus1 = 0;
                    for (int i = 0; i < extra; i++) {
                        if (pos[0] >= input.length) {
                            throw new IllegalArgumentException("Truncated snappy literal length");
                        }
                        lenMinus1 |= (input[pos[0]++] & 0xff) << (8 * i);
                    }
                }
                int len = lenMinus1 + 1;
                if (pos[0] + len > input.length || written + len > out.length) {
                    throw new IllegalArgumentException("Truncated snappy literal");
                }
                System.arraycopy(input, pos[0], out, written, len);
                pos[0] += len;
                written += len;
            } else {
                int len;
                int copyOffset;
                if (type == 1) {
                    len = ((tag >> 2) & 0x07) + 4;
                    if (pos[0] >= input.length) {
                        throw new IllegalArgumentException("Truncated snappy copy");
                    }
                    copyOffset = ((tag >> 5) << 8) | (input[pos[0]++] & 0xff);
                } else if (type == 2) {
                    len = (tag >> 2) + 1;
                    if (pos[0] + 2 > input.length) {
                        throw new IllegalArgumentException("Truncated snappy copy");
                    }
                    copyOffset = (input[pos[0]] & 0xff) | ((input[pos[0] + 1] & 0xff) << 8);
                    pos[0] += 2;
                } else {
                    len = (tag >> 2) + 1;
                    if (pos[0] + 4 > input.length) {
                        throw new IllegalArgumentException("Truncated snappy copy");
                    }
                    copyOffset = (input[pos[0]] & 0xff)
                            | ((input[pos[0] + 1] & 0xff) << 8)
                            | ((input[pos[0] + 2] & 0xff) << 16)
                            | ((input[pos[0] + 3] & 0xff) << 24);
                    pos[0] += 4;
                }
                if (copyOffset <= 0 || copyOffset > written) {
                    throw new IllegalArgumentException("Invalid snappy copy offset");
                }
                for (int i = 0; i < len; i++) {
                    out[written] = out[written - copyOffset];
                    written++;
                }
            }
        }
        return out;
    }

    public static byte[] encodeWriteRequest(List<Series> timeseries) {
        ByteAccumulator out = new ByteAccumulator();
        for (Series series : timeseries) {
            writeLengthDelimited(out, 1, encodeTimeSeries(series));
        }
        return out.toByteArray();
    }

    public static List<Series> decodeWriteRequest(byte[] protobuf) {
        List<Series> series = new ArrayList<>();
        ProtoReader reader = new ProtoReader(protobuf, 0, protobuf.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            if (field == 1 && wire == 2) {
                byte[] nested = reader.readBytes();
                series.add(decodeTimeSeries(nested));
            } else {
                reader.skip(wire);
            }
        }
        return series;
    }

    private static byte[] encodeTimeSeries(Series series) {
        ByteAccumulator out = new ByteAccumulator();
        List<String> names = new ArrayList<>(series.labels().keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            writeLengthDelimited(out, 1, encodeLabel(name, series.labels().get(name)));
        }
        for (Sample sample : series.samples()) {
            writeLengthDelimited(out, 2, encodeSample(sample));
        }
        return out.toByteArray();
    }

    private static Series decodeTimeSeries(byte[] payload) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<Sample> samples = new ArrayList<>();
        ProtoReader reader = new ProtoReader(payload, 0, payload.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            if (field == 1 && wire == 2) {
                String[] label = decodeLabel(reader.readBytes());
                labels.put(label[0], label[1]);
            } else if (field == 2 && wire == 2) {
                samples.add(decodeSample(reader.readBytes()));
            } else {
                reader.skip(wire);
            }
        }
        return new Series(Map.copyOf(labels), List.copyOf(samples));
    }

    private static byte[] encodeLabel(String name, String value) {
        ByteAccumulator out = new ByteAccumulator();
        writeLengthDelimited(out, 1, name.getBytes(StandardCharsets.UTF_8));
        writeLengthDelimited(out, 2, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String[] decodeLabel(byte[] payload) {
        String name = "";
        String value = "";
        ProtoReader reader = new ProtoReader(payload, 0, payload.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            if (field == 1 && wire == 2) {
                name = new String(reader.readBytes(), StandardCharsets.UTF_8);
            } else if (field == 2 && wire == 2) {
                value = new String(reader.readBytes(), StandardCharsets.UTF_8);
            } else {
                reader.skip(wire);
            }
        }
        return new String[] {name, value};
    }

    private static byte[] encodeSample(Sample sample) {
        ByteAccumulator out = new ByteAccumulator();
        out.write(0x09);
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(sample.value());
        out.write(buf.array());
        out.write(0x10);
        out.write(encodeVarint(sample.timestampMs()));
        return out.toByteArray();
    }

    private static Sample decodeSample(byte[] payload) {
        double value = 0;
        long timestamp = 0;
        ProtoReader reader = new ProtoReader(payload, 0, payload.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint32();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            if (field == 1 && wire == 1) {
                value = reader.readDouble();
            } else if (field == 2 && wire == 0) {
                timestamp = reader.readVarint64();
            } else {
                reader.skip(wire);
            }
        }
        return new Sample(value, timestamp);
    }

    private static void writeLengthDelimited(ByteAccumulator out, int fieldNumber, byte[] payload) {
        out.write((fieldNumber << 3) | 2);
        out.write(encodeVarint(payload.length));
        out.write(payload);
    }

    private static byte[] encodeVarint(long value) {
        long v = value;
        ByteAccumulator out = new ByteAccumulator();
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static long readVarint(byte[] input, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < input.length) {
            int b = input[pos[0]++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("Varint too long");
            }
        }
        throw new IllegalArgumentException("Truncated varint");
    }

    private static final class ProtoReader {
        private final byte[] buf;
        private int pos;
        private final int end;

        private ProtoReader(byte[] buf, int pos, int end) {
            this.buf = buf;
            this.pos = pos;
            this.end = end;
        }

        private boolean hasRemaining() {
            return pos < end;
        }

        private int readVarint32() {
            return (int) readVarint64();
        }

        private long readVarint64() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int b = buf[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("Varint too long");
                }
            }
            throw new IllegalArgumentException("Truncated varint");
        }

        private byte[] readBytes() {
            int len = readVarint32();
            if (len < 0 || pos + len > end) {
                throw new IllegalArgumentException("Truncated protobuf bytes");
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        private double readDouble() {
            if (pos + 8 > end) {
                throw new IllegalArgumentException("Truncated protobuf double");
            }
            ByteBuffer buf = ByteBuffer.wrap(this.buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN);
            pos += 8;
            return buf.getDouble();
        }

        private void skip(int wireType) {
            switch (wireType) {
                case 0 -> readVarint64();
                case 1 -> {
                    if (pos + 8 > end) {
                        throw new IllegalArgumentException("Truncated protobuf fixed64");
                    }
                    pos += 8;
                }
                case 2 -> readBytes();
                case 5 -> {
                    if (pos + 4 > end) {
                        throw new IllegalArgumentException("Truncated protobuf fixed32");
                    }
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("Unknown protobuf wire type " + wireType);
            }
        }
    }

    private static final class ByteAccumulator {
        private byte[] buf = new byte[64];
        private int size;

        private void write(int b) {
            ensure(1);
            buf[size++] = (byte) b;
        }

        private void write(byte[] src) {
            ensure(src.length);
            System.arraycopy(src, 0, buf, size, src.length);
            size += src.length;
        }

        private void ensure(int extra) {
            if (size + extra <= buf.length) {
                return;
            }
            int n = buf.length;
            while (size + extra > n) {
                n *= 2;
            }
            byte[] next = new byte[n];
            System.arraycopy(buf, 0, next, 0, size);
            buf = next;
        }

        private byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }
}
