package io.floci.aws.services.iotfleetwise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a COVESA Vehicle Signal Specification tree in its JSON export ({@code {"Vehicle": {"type": "branch",
 * "children": {...}}}}) into signal catalog nodes, as {@code ImportSignalCatalog} does.
 */
final class IotFleetWiseVss {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("int8", "INT8"), Map.entry("uint8", "UINT8"), Map.entry("int16", "INT16"),
            Map.entry("uint16", "UINT16"), Map.entry("int32", "INT32"), Map.entry("uint32", "UINT32"),
            Map.entry("int64", "INT64"), Map.entry("uint64", "UINT64"), Map.entry("boolean", "BOOLEAN"),
            Map.entry("float", "FLOAT"), Map.entry("double", "DOUBLE"), Map.entry("string", "STRING"));

    private IotFleetWiseVss() {
    }

    static List<ObjectNode> parse(ObjectMapper mapper, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw IotFleetWiseStore.field("vss.vssJson", "The VSS JSON can't be parsed: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw IotFleetWiseStore.field("vss.vssJson", "The VSS JSON must be an object of root branches.");
        }
        List<ObjectNode> nodes = new ArrayList<>();
        walk(mapper, root, "", nodes);
        return nodes;
    }

    private static void walk(ObjectMapper mapper, JsonNode children, String prefix, List<ObjectNode> nodes) {
        Iterator<Map.Entry<String, JsonNode>> entries = children.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode spec = entry.getValue();
            String type = spec.path("type").asText("").toLowerCase(Locale.ROOT);
            if (!IotFleetWiseModels.NODE_TYPES.contains(type)) {
                throw IotFleetWiseStore.field("vss.vssJson", "The VSS node " + path + " has the unsupported type "
                        + spec.path("type").asText() + ".");
            }
            ObjectNode node = mapper.createObjectNode();
            ObjectNode body = node.putObject(type);
            body.put("fullyQualifiedName", path);
            copyText(spec, body, "description", "description");
            copyText(spec, body, "unit", "unit");
            copyText(spec, body, "comment", "comment");
            copyText(spec, body, "deprecation", "deprecationMessage");
            if (spec.has("min")) {
                body.put("min", spec.path("min").asDouble());
            }
            if (spec.has("max")) {
                body.put("max", spec.path("max").asDouble());
            }
            if (spec.has("allowed")) {
                body.putArray("allowedValues").addAll(textValues(mapper, spec.path("allowed")));
            }
            if (spec.has("default") && "attribute".equals(type)) {
                body.put("defaultValue", spec.path("default").asText());
            }
            if (spec.has("datatype")) {
                body.put("dataType", dataType(path, spec.path("datatype").asText()));
            }
            nodes.add(node);
            if (spec.has("children")) {
                walk(mapper, spec.path("children"), path, nodes);
            }
        }
    }

    private static String dataType(String path, String vss) {
        String lower = vss.toLowerCase(Locale.ROOT);
        boolean array = lower.endsWith("[]");
        String base = array ? lower.substring(0, lower.length() - 2) : lower;
        String type = TYPES.get(base);
        if (type == null) {
            throw IotFleetWiseStore.field("vss.vssJson", "The VSS node " + path + " has the unsupported datatype "
                    + vss + ".");
        }
        return array ? type + "_ARRAY" : type;
    }

    private static void copyText(JsonNode from, ObjectNode to, String source, String target) {
        if (from.hasNonNull(source)) {
            to.put(target, from.path(source).asText());
        }
    }

    private static List<JsonNode> textValues(ObjectMapper mapper, JsonNode values) {
        List<JsonNode> texts = new ArrayList<>();
        values.forEach(value -> texts.add(mapper.getNodeFactory().textNode(value.asText())));
        return texts;
    }
}
