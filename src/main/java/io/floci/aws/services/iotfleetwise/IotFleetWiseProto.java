package io.floci.aws.services.iotfleetwise;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The protobuf wire format of the messages IoT FleetWise exchanges with the Edge Agent ({@code collection_schemes},
 * {@code decoder_manifest}, {@code checkin} and {@code vehicle_data} in aws-iot-fleetwise-edge), written and read
 * field by field.
 */
final class IotFleetWiseProto {

    private IotFleetWiseProto() {
    }

    /** Builds one message. Zero, false and empty values are left out, as proto3 does. */
    static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CodedOutputStream out = CodedOutputStream.newInstance(bytes);

        Writer string(int field, String value) {
            if (value != null && !value.isEmpty()) {
                run(() -> out.writeString(field, value));
            }
            return this;
        }

        Writer uint(int field, long value) {
            if (value != 0) {
                run(() -> out.writeUInt64(field, value));
            }
            return this;
        }

        Writer sint(int field, long value) {
            if (value != 0) {
                run(() -> out.writeSInt64(field, value));
            }
            return this;
        }

        Writer bool(int field, boolean value) {
            if (value) {
                run(() -> out.writeBool(field, true));
            }
            return this;
        }

        Writer dbl(int field, double value) {
            if (value != 0) {
                run(() -> out.writeDouble(field, value));
            }
            return this;
        }

        /** A double in a oneof, written even when zero so the case is set. */
        Writer dblAlways(int field, double value) {
            run(() -> out.writeDouble(field, value));
            return this;
        }

        /** A uint32 in a oneof, written even when zero. */
        Writer uintAlways(int field, long value) {
            run(() -> out.writeUInt32(field, (int) value));
            return this;
        }

        /** A bool in a oneof, written even when false. */
        Writer boolAlways(int field, boolean value) {
            run(() -> out.writeBool(field, value));
            return this;
        }

        Writer message(int field, Writer message) {
            if (message != null) {
                byte[] body = message.bytes();
                run(() -> out.writeByteArray(field, body));
            }
            return this;
        }

        byte[] bytes() {
            run(out::flush);
            return bytes.toByteArray();
        }

        private static void run(IoAction action) {
            try {
                action.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** One decoded field: its number, wire type, and value (a Long, Double, or byte[] for length-delimited). */
    record Field(int number, int wireType, Object value) {
        long asLong() {
            return value instanceof Long l ? l : 0;
        }

        double asDouble() {
            return value instanceof Double d ? d : 0;
        }

        byte[] asBytes() {
            return value instanceof byte[] b ? b : new byte[0];
        }

        String asString() {
            return new String(asBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every field of a message in order; repeated fields appear once per value. */
    static List<Field> read(byte[] message) {
        List<Field> fields = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(message);
        try {
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                int number = WireFormat.getTagFieldNumber(tag);
                int type = WireFormat.getTagWireType(tag);
                Object value = switch (type) {
                    case WireFormat.WIRETYPE_VARINT -> in.readRawVarint64();
                    case WireFormat.WIRETYPE_FIXED64 -> Double.longBitsToDouble(in.readRawLittleEndian64());
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED -> in.readByteArray();
                    case WireFormat.WIRETYPE_FIXED32 -> (long) in.readRawLittleEndian32();
                    default -> throw new IllegalArgumentException("Unsupported wire type " + type);
                };
                fields.add(new Field(number, type, value));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The message isn't valid protobuf: " + e.getMessage(), e);
        }
        return fields;
    }

    /** The fields of a message by number, the last value winning; use {@link #read} for repeated fields. */
    static Map<Integer, Field> fields(byte[] message) {
        Map<Integer, Field> fields = new LinkedHashMap<>();
        read(message).forEach(field -> fields.put(field.number(), field));
        return fields;
    }

    static long zigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
