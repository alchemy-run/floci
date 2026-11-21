package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * How Floci stores CQL values in rows, and how CDC records show them.
 *
 * <p>Stored values are JSON: text types, {@code decimal}, {@code varint}, {@code uuid}, {@code timeuuid},
 * {@code inet}, {@code duration} and {@code date} ({@code yyyy-mm-dd}) as strings; the integer types, {@code float},
 * {@code double}, {@code timestamp} (epoch milliseconds) and {@code time} (nanoseconds of the day) as numbers;
 * {@code boolean} as a boolean; {@code blob} as base64; lists, sets and tuples as arrays; maps as arrays of
 * {@code [key, value]} pairs; and user-defined types as objects.
 */
final class CqlValues {

    private CqlValues() {
    }

    /** A stored value as a CDC {@code KeyspacesCellValue}. */
    static ObjectNode cell(ObjectMapper mapper, CqlType type, JsonNode value,
                           Function<String, Map<String, CqlType>> udts) {
        ObjectNode out = mapper.createObjectNode();
        if (type == null || value == null || value.isNull()) {
            return out;
        }
        switch (type.name()) {
            case "boolean" -> out.put("boolT", value.asBoolean());
            case "blob" -> out.put("blobT", value.asText());
            case "timestamp" -> out.put("timestampT", Instant.ofEpochMilli(value.asLong()).toString());
            case "list", "set" -> {
                ArrayNode items = out.putArray(type.name() + "T");
                for (JsonNode item : value) {
                    items.addObject().set("value", cell(mapper, type.args().get(0), item, udts));
                }
            }
            case "map" -> {
                ArrayNode items = out.putArray("mapT");
                for (JsonNode pair : value) {
                    ObjectNode entry = items.addObject();
                    entry.set("key", cell(mapper, type.args().get(0), pair.get(0), udts));
                    entry.set("value", cell(mapper, type.args().get(1), pair.get(1), udts));
                }
            }
            case "tuple" -> {
                ArrayNode items = out.putArray("tupleT");
                for (int i = 0; i < type.args().size(); i++) {
                    items.addObject().set("value", cell(mapper, type.args().get(i), value.get(i), udts));
                }
            }
            default -> {
                if (type.isUdt()) {
                    Map<String, CqlType> fields = udts.apply(type.name());
                    ObjectNode udt = out.putObject("udtT");
                    value.fields().forEachRemaining(e -> udt.putObject(e.getKey()).set("value", cell(mapper,
                            fields.get(e.getKey()), e.getValue(), udts)));
                } else {
                    out.put(("text".equals(type.name()) ? "text" : type.name()) + "T", value.asText());
                }
            }
        }
        return out;
    }
}
