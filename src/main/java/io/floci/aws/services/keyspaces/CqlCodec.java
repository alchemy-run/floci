package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The CQL native protocol's encodings: frame primitives ({@code [short]}, {@code [int]}, {@code [string]},
 * {@code [long string]}, {@code [bytes]}, {@code [string map]}), type options, and the serialization of each CQL type,
 * converted to and from the JSON Floci stores values as (see {@link CqlValues}).
 */
final class CqlCodec {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final long DATE_EPOCH = 1L << 31;

    private CqlCodec() {
    }

    /** A growable buffer of protocol bytes. */
    static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Out b(int v) {
            bytes.write(v);
            return this;
        }

        Out s(int v) {
            bytes.write(v >>> 8);
            bytes.write(v);
            return this;
        }

        Out i(int v) {
            bytes.write(v >>> 24);
            bytes.write(v >>> 16);
            bytes.write(v >>> 8);
            bytes.write(v);
            return this;
        }

        Out l(long v) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                bytes.write((int) (v >>> shift));
            }
            return this;
        }

        Out raw(byte[] data) {
            bytes.writeBytes(data);
            return this;
        }

        Out string(String v) {
            byte[] data = v.getBytes(StandardCharsets.UTF_8);
            return s(data.length).raw(data);
        }

        Out longString(String v) {
            byte[] data = v.getBytes(StandardCharsets.UTF_8);
            return i(data.length).raw(data);
        }

        Out bytes(byte[] data) {
            return data == null ? i(-1) : i(data.length).raw(data);
        }

        Out shortBytes(byte[] data) {
            return s(data.length).raw(data);
        }

        Out stringList(List<String> values) {
            s(values.size());
            values.forEach(this::string);
            return this;
        }

        Out stringMultimap(Map<String, List<String>> map) {
            s(map.size());
            map.forEach((k, v) -> {
                string(k);
                stringList(v);
            });
            return this;
        }

        byte[] toBytes() {
            return bytes.toByteArray();
        }
    }

    /** A reader over protocol bytes. */
    static final class In {
        final ByteBuffer buffer;

        In(byte[] data) {
            buffer = ByteBuffer.wrap(data);
        }

        int b() {
            return buffer.get() & 0xff;
        }

        int s() {
            return buffer.getShort() & 0xffff;
        }

        int i() {
            return buffer.getInt();
        }

        long l() {
            return buffer.getLong();
        }

        boolean more() {
            return buffer.hasRemaining();
        }

        String string() {
            byte[] data = new byte[s()];
            buffer.get(data);
            return new String(data, StandardCharsets.UTF_8);
        }

        String longString() {
            byte[] data = new byte[i()];
            buffer.get(data);
            return new String(data, StandardCharsets.UTF_8);
        }

        /** {@code [bytes]}; null for a null value, and {@link #UNSET} for an unset one. */
        byte[] bytes() {
            int n = i();
            if (n == -1) {
                return null;
            }
            if (n == -2) {
                return UNSET;
            }
            byte[] data = new byte[n];
            buffer.get(data);
            return data;
        }

        byte[] shortBytes() {
            byte[] data = new byte[s()];
            buffer.get(data);
            return data;
        }

        Map<String, String> stringMap() {
            int n = s();
            Map<String, String> map = new LinkedHashMap<>();
            for (int k = 0; k < n; k++) {
                map.put(string(), string());
            }
            return map;
        }
    }

    /** The marker of an unset bound value, which leaves a column as it is. */
    static final byte[] UNSET = new byte[0];

    // ---------------------------------------------------------------- type options

    static void typeOption(Out out, CqlType type, Function<String, List<Map.Entry<String, CqlType>>> udts,
                           String keyspace) {
        switch (type.name()) {
            case "ascii" -> out.s(0x0001);
            case "bigint" -> out.s(0x0002);
            case "blob" -> out.s(0x0003);
            case "boolean" -> out.s(0x0004);
            case "counter" -> out.s(0x0005);
            case "decimal" -> out.s(0x0006);
            case "double" -> out.s(0x0007);
            case "float" -> out.s(0x0008);
            case "int" -> out.s(0x0009);
            case "timestamp" -> out.s(0x000B);
            case "uuid" -> out.s(0x000C);
            case "text" -> out.s(0x000D);
            case "varint" -> out.s(0x000E);
            case "timeuuid" -> out.s(0x000F);
            case "inet" -> out.s(0x0010);
            case "date" -> out.s(0x0011);
            case "time" -> out.s(0x0012);
            case "smallint" -> out.s(0x0013);
            case "tinyint" -> out.s(0x0014);
            case "duration" -> out.s(0x0000).string("org.apache.cassandra.db.marshal.DurationType");
            case "list" -> {
                out.s(0x0020);
                typeOption(out, type.args().get(0), udts, keyspace);
            }
            case "set" -> {
                out.s(0x0022);
                typeOption(out, type.args().get(0), udts, keyspace);
            }
            case "map" -> {
                out.s(0x0021);
                typeOption(out, type.args().get(0), udts, keyspace);
                typeOption(out, type.args().get(1), udts, keyspace);
            }
            case "tuple" -> {
                out.s(0x0031).s(type.args().size());
                type.args().forEach(a -> typeOption(out, a, udts, keyspace));
            }
            default -> {
                List<Map.Entry<String, CqlType>> fields = udts.apply(type.name());
                out.s(0x0030).string(keyspace).string(type.name()).s(fields.size());
                for (Map.Entry<String, CqlType> field : fields) {
                    out.string(field.getKey());
                    typeOption(out, field.getValue(), udts, keyspace);
                }
            }
        }
    }

    // ---------------------------------------------------------------- values

    /** Serializes a stored value; null stays null. */
    static byte[] encode(CqlType type, JsonNode value, Function<String, List<Map.Entry<String, CqlType>>> udts) {
        if (value == null || value.isNull()) {
            return null;
        }
        switch (type.name()) {
            case "ascii", "text" -> {
                return value.asText().getBytes(StandardCharsets.UTF_8);
            }
            case "bigint", "counter", "timestamp", "time" -> {
                return ByteBuffer.allocate(8).putLong(value.asLong()).array();
            }
            case "int" -> {
                return ByteBuffer.allocate(4).putInt(value.asInt()).array();
            }
            case "smallint" -> {
                return ByteBuffer.allocate(2).putShort((short) value.asInt()).array();
            }
            case "tinyint" -> {
                return new byte[] {(byte) value.asInt()};
            }
            case "boolean" -> {
                return new byte[] {(byte) (value.asBoolean() ? 1 : 0)};
            }
            case "float" -> {
                return ByteBuffer.allocate(4).putFloat((float) value.asDouble()).array();
            }
            case "double" -> {
                return ByteBuffer.allocate(8).putDouble(value.asDouble()).array();
            }
            case "blob" -> {
                return Base64.getDecoder().decode(value.asText());
            }
            case "uuid", "timeuuid" -> {
                UUID uuid = UUID.fromString(value.asText());
                return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits()).array();
            }
            case "varint" -> {
                return new BigInteger(value.asText()).toByteArray();
            }
            case "decimal" -> {
                BigDecimal d = new BigDecimal(value.asText());
                byte[] unscaled = d.unscaledValue().toByteArray();
                return ByteBuffer.allocate(4 + unscaled.length).putInt(d.scale()).put(unscaled).array();
            }
            case "inet" -> {
                try {
                    return InetAddress.getByName(value.asText()).getAddress();
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("Invalid inet " + value.asText());
                }
            }
            case "date" -> {
                long days = LocalDate.parse(value.asText()).toEpochDay();
                return ByteBuffer.allocate(4).putInt((int) (days + DATE_EPOCH)).array();
            }
            case "duration" -> {
                long[] parts = CqlDuration.parse(value.asText());
                Out out = new Out();
                for (long part : parts) {
                    vint(out, part);
                }
                return out.toBytes();
            }
            case "list", "set" -> {
                Out out = new Out().i(value.size());
                for (JsonNode item : value) {
                    out.bytes(encode(type.args().get(0), item, udts));
                }
                return out.toBytes();
            }
            case "map" -> {
                Out out = new Out().i(value.size());
                for (JsonNode pair : value) {
                    out.bytes(encode(type.args().get(0), pair.get(0), udts));
                    out.bytes(encode(type.args().get(1), pair.get(1), udts));
                }
                return out.toBytes();
            }
            case "tuple" -> {
                Out out = new Out();
                for (int k = 0; k < type.args().size(); k++) {
                    out.bytes(encode(type.args().get(k), value.get(k), udts));
                }
                return out.toBytes();
            }
            default -> {
                Out out = new Out();
                for (Map.Entry<String, CqlType> field : udts.apply(type.name())) {
                    out.bytes(encode(field.getValue(), value.get(field.getKey()), udts));
                }
                return out.toBytes();
            }
        }
    }

    /** Deserializes a bound value into the stored form; null stays null. */
    static JsonNode decode(CqlType type, byte[] data, Function<String, List<Map.Entry<String, CqlType>>> udts) {
        if (data == null) {
            return NODES.nullNode();
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        switch (type.name()) {
            case "ascii", "text" -> {
                return NODES.textNode(new String(data, StandardCharsets.UTF_8));
            }
            case "bigint", "counter", "timestamp", "time" -> {
                return NODES.numberNode(need(b, 8).getLong());
            }
            case "int" -> {
                return NODES.numberNode(need(b, 4).getInt());
            }
            case "smallint" -> {
                return NODES.numberNode((int) need(b, 2).getShort());
            }
            case "tinyint" -> {
                return NODES.numberNode((int) need(b, 1).get());
            }
            case "boolean" -> {
                return NODES.booleanNode(need(b, 1).get() != 0);
            }
            case "float" -> {
                return NODES.numberNode((double) need(b, 4).getFloat());
            }
            case "double" -> {
                return NODES.numberNode(need(b, 8).getDouble());
            }
            case "blob" -> {
                return NODES.textNode(Base64.getEncoder().encodeToString(data));
            }
            case "uuid", "timeuuid" -> {
                need(b, 16);
                return NODES.textNode(new UUID(b.getLong(), b.getLong()).toString());
            }
            case "varint" -> {
                return NODES.textNode(data.length == 0 ? "0" : new BigInteger(data).toString());
            }
            case "decimal" -> {
                if (b.remaining() < 4) {
                    throw new IllegalArgumentException("Invalid decimal value.");
                }
                int scale = b.getInt();
                byte[] unscaled = new byte[b.remaining()];
                b.get(unscaled);
                return NODES.textNode(new BigDecimal(new BigInteger(unscaled), scale).toPlainString());
            }
            case "inet" -> {
                try {
                    return NODES.textNode(InetAddress.getByAddress(data).getHostAddress());
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("Invalid inet value.");
                }
            }
            case "date" -> {
                long days = (need(b, 4).getInt() & 0xffffffffL) - DATE_EPOCH;
                return NODES.textNode(LocalDate.ofEpochDay(days).toString());
            }
            case "duration" -> {
                In in = new In(data);
                long months = readVint(in);
                long days = readVint(in);
                long nanos = readVint(in);
                return NODES.textNode(CqlDuration.format(months, days, nanos));
            }
            case "list", "set" -> {
                In in = new In(data);
                ArrayNode out = NODES.arrayNode();
                int n = in.i();
                for (int k = 0; k < n; k++) {
                    out.add(decode(type.args().get(0), in.bytes(), udts));
                }
                return out;
            }
            case "map" -> {
                In in = new In(data);
                ArrayNode out = NODES.arrayNode();
                int n = in.i();
                for (int k = 0; k < n; k++) {
                    ArrayNode pair = out.addArray();
                    pair.add(decode(type.args().get(0), in.bytes(), udts));
                    pair.add(decode(type.args().get(1), in.bytes(), udts));
                }
                return out;
            }
            case "tuple" -> {
                In in = new In(data);
                ArrayNode out = NODES.arrayNode();
                for (CqlType arg : type.args()) {
                    out.add(in.more() ? decode(arg, in.bytes(), udts) : NODES.nullNode());
                }
                return out;
            }
            default -> {
                In in = new In(data);
                ObjectNode out = NODES.objectNode();
                for (Map.Entry<String, CqlType> field : udts.apply(type.name())) {
                    out.set(field.getKey(), in.more() ? decode(field.getValue(), in.bytes(), udts)
                            : NODES.nullNode());
                }
                return out;
            }
        }
    }

    private static ByteBuffer need(ByteBuffer b, int n) {
        if (b.remaining() != n) {
            throw new IllegalArgumentException("Expected " + n + " bytes, got " + b.remaining() + ".");
        }
        return b;
    }

    /** Writes a signed vint (zigzag, then unsigned vint), as durations are serialized. */
    static void vint(Out out, long value) {
        long v = (value << 1) ^ (value >> 63);
        int size = 1;
        while (size < 9 && (v >>> (7 * size)) != 0) {
            size++;
        }
        if (size == 9) {
            out.b(0xff);
            out.l(v);
            return;
        }
        int extra = size - 1;
        int first = (int) (v >>> (8 * extra)) | (~(0xff >> extra) & 0xff);
        out.b(first & 0xff);
        for (int k = extra - 1; k >= 0; k--) {
            out.b((int) (v >>> (8 * k)) & 0xff);
        }
    }

    static long readVint(In in) {
        int first = in.b();
        int extra = 0;
        for (int mask = 0x80; mask != 0 && (first & mask) != 0; mask >>= 1) {
            extra++;
        }
        long v = first & (0xff >> extra);
        for (int k = 0; k < extra; k++) {
            v = v << 8 | in.b();
        }
        return (v >>> 1) ^ -(v & 1);
    }

    static ObjectMapper mapper() {
        return KeyspacesMapper.MAPPER;
    }

    /** A shared mapper. */
    static final class KeyspacesMapper {
        static final ObjectMapper MAPPER = new ObjectMapper();

        private KeyspacesMapper() {
        }
    }
}
