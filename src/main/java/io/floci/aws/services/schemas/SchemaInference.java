package io.floci.aws.services.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers schemas from EventBridge events the way schema discovery does: the envelope becomes an {@code AWSEvent}
 * object whose {@code detail} refers to a component named after the detail type, every nested object becomes its
 * own component, and samples are merged, with a property required only when every sample has it. Also converts an
 * OpenAPI 3 schema to JSON Schema Draft 4 for {@code ExportSchema}.
 */
final class SchemaInference {

    static final String OPEN_API = "OpenApi3";
    static final String JSON_SCHEMA = "JSONSchemaDraft4";

    private final ObjectMapper mapper;

    SchemaInference(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The events, parsed and checked to be EventBridge events of one source and detail type. */
    List<JsonNode> events(List<String> raw) {
        if (raw.isEmpty()) {
            throw new AwsException("BadRequestException", "Events must contain at least one event.", 400);
        }
        List<JsonNode> out = new ArrayList<>();
        for (String text : raw) {
            JsonNode event;
            try {
                event = mapper.readTree(text);
            } catch (Exception e) {
                throw new AwsException("BadRequestException", "An event isn't valid JSON.", 400);
            }
            if (!event.isObject() || !event.hasNonNull("source") || !event.hasNonNull("detail-type")
                    || !event.has("detail")) {
                throw new AwsException("BadRequestException", "Each event must be an EventBridge event with "
                        + "source, detail-type and detail.", 400);
            }
            if (!out.isEmpty() && (!out.get(0).path("source").equals(event.path("source"))
                    || !out.get(0).path("detail-type").equals(event.path("detail-type")))) {
                throw new AwsException("BadRequestException", "The events must share one source and detail-type.",
                        400);
            }
            out.add(event);
        }
        return out;
    }

    /** The name schema discovery gives an event's schema: {@code source@DetailType}. */
    static String schemaName(JsonNode event) {
        return event.path("source").asText() + "@" + pascal(event.path("detail-type").asText());
    }

    /** A schema of the given type inferred from the sample events. */
    ObjectNode infer(List<JsonNode> events, String type) {
        String detailName = pascal(events.get(0).path("detail-type").asText());
        Map<String, ObjectNode> components = new LinkedHashMap<>();
        ObjectNode root = objectSchema(events, components, "AWSEvent", detailName);
        root.put("x-amazon-events-detail-type", events.get(0).path("detail-type").asText())
                .put("x-amazon-events-source", events.get(0).path("source").asText());
        components.remove("AWSEvent");
        Map<String, ObjectNode> ordered = new LinkedHashMap<>();
        ordered.put("AWSEvent", root);
        ordered.putAll(components);
        return JSON_SCHEMA.equals(type) ? draft4(ordered, detailName) : openApi(ordered, detailName);
    }

    private ObjectNode openApi(Map<String, ObjectNode> components, String title) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("openapi", "3.0.0");
        doc.putObject("info").put("version", "1.0.0").put("title", title);
        doc.putObject("paths");
        ObjectNode schemas = doc.putObject("components").putObject("schemas");
        components.forEach((name, schema) -> schemas.set(name, refs(schema, "#/components/schemas/")));
        return doc;
    }

    private ObjectNode draft4(Map<String, ObjectNode> components, String title) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("$schema", "http://json-schema.org/draft-04/schema#");
        doc.put("title", title);
        ObjectNode definitions = doc.putObject("definitions");
        components.forEach((name, schema) -> {
            if (!"AWSEvent".equals(name)) {
                definitions.set(name, refs(schema, "#/definitions/"));
            }
        });
        doc.setAll(refs(components.get("AWSEvent"), "#/definitions/"));
        return doc;
    }

    /** Rewrites the placeholder refs ({@code {"$ref": "Name"}}) to point under {@code prefix}. */
    private ObjectNode refs(ObjectNode schema, String prefix) {
        ObjectNode copy = schema.deepCopy();
        rewrite(copy, prefix);
        return copy;
    }

    private static void rewrite(JsonNode node, String prefix) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.has("$ref") && !object.get("$ref").asText().startsWith("#")) {
                object.put("$ref", prefix + object.get("$ref").asText());
            }
            object.forEach(child -> rewrite(child, prefix));
        } else if (node.isArray()) {
            node.forEach(child -> rewrite(child, prefix));
        }
    }

    /** The schema of objects sampled as {@code samples}; nested objects are registered as components. */
    private ObjectNode objectSchema(List<JsonNode> samples, Map<String, ObjectNode> components, String name,
                                    String detailName) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode sample : samples) {
            sample.fieldNames().forEachRemaining(keys::add);
        }
        List<String> required = new ArrayList<>();
        ObjectNode properties = mapper.createObjectNode();
        for (String key : keys.stream().sorted().toList()) {
            List<JsonNode> values = new ArrayList<>();
            boolean everywhere = true;
            for (JsonNode sample : samples) {
                if (sample.has(key) && !sample.get(key).isNull()) {
                    values.add(sample.get(key));
                } else {
                    everywhere = false;
                }
            }
            if (values.isEmpty()) {
                properties.putObject(key);
                continue;
            }
            String childName = "AWSEvent".equals(name) && "detail".equals(key) ? detailName : pascal(key);
            properties.set(key, valueSchema(values, components, uniqueName(components, childName), detailName));
            if (everywhere) {
                required.add(key);
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", mapper.valueToTree(required));
        }
        schema.set("properties", properties);
        components.put(name, schema);
        return schema;
    }

    private JsonNode valueSchema(List<JsonNode> values, Map<String, ObjectNode> components, String name,
                                 String detailName) {
        JsonNode first = values.get(0);
        if (first.isObject()) {
            List<JsonNode> objects = values.stream().filter(JsonNode::isObject).toList();
            objectSchema(objects, components, name, detailName);
            return mapper.createObjectNode().put("$ref", name);
        }
        if (first.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            values.stream().filter(JsonNode::isArray).forEach(array -> array.forEach(items::add));
            ObjectNode schema = mapper.createObjectNode().put("type", "array");
            schema.set("items", items.isEmpty() ? mapper.createObjectNode()
                    : valueSchema(items, components, singular(name), detailName));
            return schema;
        }
        if (first.isBoolean()) {
            return mapper.createObjectNode().put("type", "boolean");
        }
        if (first.isNumber()) {
            return mapper.createObjectNode().put("type", "number");
        }
        ObjectNode schema = mapper.createObjectNode().put("type", "string");
        if (values.stream().allMatch(value -> isDateTime(value.asText()))) {
            schema.put("format", "date-time");
        }
        return schema;
    }

    private static String uniqueName(Map<String, ObjectNode> components, String name) {
        String candidate = name;
        for (int i = 1; components.containsKey(candidate); i++) {
            candidate = name + "_" + i;
        }
        return candidate;
    }

    private static boolean isDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String singular(String name) {
        return name.endsWith("s") && name.length() > 1 ? name.substring(0, name.length() - 1) + "Item" : name
                + "Item";
    }

    /** {@code EC2 Instance State-change Notification} becomes {@code EC2InstanceStatechangeNotification}. */
    static String pascal(String text) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = c == ' ' || c == '_';
            }
        }
        return out.isEmpty() ? "Object" : out.toString();
    }

    /** An OpenAPI 3 schema as JSON Schema Draft 4: its root component, with the others as definitions. */
    ObjectNode exportDraft4(JsonNode openApi) {
        JsonNode schemas = openApi.path("components").path("schemas");
        if (!schemas.isObject() || schemas.isEmpty()) {
            throw new AwsException("BadRequestException", "The schema has no components to export.", 400);
        }
        String rootName = schemas.has("AWSEvent") ? "AWSEvent" : schemas.fieldNames().next();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("$schema", "http://json-schema.org/draft-04/schema#");
        doc.put("title", openApi.path("info").path("title").asText(rootName));
        ObjectNode definitions = doc.putObject("definitions");
        Iterator<Map.Entry<String, JsonNode>> fields = schemas.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getKey().equals(rootName)) {
                definitions.set(entry.getKey(), exportRefs(entry.getValue().deepCopy()));
            }
        }
        doc.setAll((ObjectNode) exportRefs(schemas.get(rootName).deepCopy()));
        return doc;
    }

    private static JsonNode exportRefs(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.has("$ref")) {
                object.put("$ref", object.get("$ref").asText().replace("#/components/schemas/", "#/definitions/"));
            }
            object.forEach(SchemaInference::exportRefs);
        } else if (node instanceof ArrayNode array) {
            array.forEach(SchemaInference::exportRefs);
        }
        return node;
    }
}
