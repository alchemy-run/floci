package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes a REST-XML service's wire format from its vendored botocore model
 * ({@code aws/<service>-rest-xml.json}, written by {@code tools/aws/regen_rest_xml_shapes.py}), so
 * a service can work with requests and responses as JSON trees in the model's member names.
 * <p>
 * Decoding takes a request's URI, query and header parameters and its XML body to one object.
 * Encoding writes an output object as the XML body under its shape's root element, and returns the
 * members the model binds to response headers separately. Lists follow the model's member names and
 * flattening; timestamps stay ISO-8601 strings; blobs stay base64 text.
 */
public final class RestXmlCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final JsonNode model;
    private final String namespace;

    private RestXmlCodec(JsonNode model, String namespace) {
        this.model = model;
        this.namespace = namespace;
    }

    public static RestXmlCodec load(String resource, String namespace) {
        return new RestXmlCodec(VendoredResources.read(resource, "REST-XML model " + resource,
                in -> new ObjectMapper().readTree(in)), namespace);
    }

    /** The model's operations, each with its HTTP {@code method} and {@code uri} template. */
    public JsonNode operations() {
        return model.path("operations");
    }

    private JsonNode shape(String name) {
        return model.path("shapes").path(name);
    }

    private JsonNode operation(String name) {
        JsonNode operation = model.path("operations").path(name);
        if (operation.isMissingNode()) {
            throw new IllegalArgumentException("Unknown operation " + name);
        }
        return operation;
    }

    // ── Decoding ───────────────────────────────────────────────────────────

    /**
     * The operation's input as one object: members bound to the URI, query or headers taken from
     * the given maps (by their wire names), the rest read from the XML body.
     */
    public ObjectNode decode(String operation, String body, Map<String, String> uri,
                             Map<String, List<String>> query, Map<String, List<String>> headers) {
        JsonNode op = operation(operation);
        ObjectNode result = NODES.objectNode();
        if (!op.has("input")) {
            return result;
        }
        JsonNode input = shape(op.path("input").asText());
        String payload = input.path("payload").asText(null);
        Element root = null;
        if (body != null && !body.isBlank()) {
            try {
                Document document = XmlParser.parseDocument(body);
                root = document.getDocumentElement();
            } catch (Exception e) {
                throw new AwsException("MalformedXML", "The XML you provided was not well-formed or did not "
                        + "validate against our published schema.", 400);
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = input.path("members").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String member = entry.getKey();
            JsonNode spec = entry.getValue();
            String location = spec.path("location").asText("");
            String wire = spec.path("locationName").asText(member);
            JsonNode memberShape = shape(spec.path("shape").asText());
            switch (location) {
                case "uri" -> {
                    String value = uri.get(wire);
                    if (value != null) {
                        result.set(member, scalar(memberShape, value));
                    }
                }
                case "querystring" -> {
                    List<String> values = query.get(wire);
                    if (values != null && !values.isEmpty()) {
                        if ("list".equals(memberShape.path("type").asText())) {
                            ArrayNode list = result.putArray(member);
                            JsonNode itemShape = shape(memberShape.path("member").path("shape").asText());
                            values.forEach(v -> list.add(scalar(itemShape, v)));
                        } else {
                            result.set(member, scalar(memberShape, values.getFirst()));
                        }
                    }
                }
                case "header" -> {
                    String value = header(headers, wire);
                    if (value != null) {
                        result.set(member, scalar(memberShape, value));
                    }
                }
                case "headers" -> {
                    ObjectNode map = NODES.objectNode();
                    String prefix = wire.toLowerCase();
                    headers.forEach((name, values) -> {
                        if (name.toLowerCase().startsWith(prefix) && !values.isEmpty()) {
                            map.put(name.substring(prefix.length()), values.getFirst());
                        }
                    });
                    if (!map.isEmpty()) {
                        result.set(member, map);
                    }
                }
                default -> {
                    if (root == null) {
                        continue;
                    }
                    if (member.equals(payload)) {
                        result.set(member, readValue(memberShape, root));
                    } else if (payload == null) {
                        Element child = child(root, wire);
                        if (child != null || isFlattened(memberShape)) {
                            JsonNode value = isFlattened(memberShape) ? readFlattened(memberShape, root, wire)
                                    : readValue(memberShape, child);
                            if (value != null) {
                                result.set(member, value);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    private boolean isFlattened(JsonNode shape) {
        return shape.path("flattened").asBoolean(false);
    }

    private JsonNode readValue(JsonNode shape, Element element) {
        return switch (shape.path("type").asText()) {
            case "structure" -> readStructure(shape, element);
            case "list" -> {
                ArrayNode list = NODES.arrayNode();
                String itemName = shape.path("member").path("locationName").asText("member");
                JsonNode itemShape = shape(shape.path("member").path("shape").asText());
                for (Element item : children(element, itemName)) {
                    list.add(readValue(itemShape, item));
                }
                yield list;
            }
            case "map" -> readMap(shape, children(element, "entry"));
            default -> scalar(shape, element.getTextContent());
        };
    }

    private JsonNode readFlattened(JsonNode shape, Element parent, String name) {
        List<Element> items = children(parent, name);
        if (items.isEmpty()) {
            return null;
        }
        if ("map".equals(shape.path("type").asText())) {
            return readMap(shape, items);
        }
        ArrayNode list = NODES.arrayNode();
        JsonNode itemShape = shape(shape.path("member").path("shape").asText());
        items.forEach(item -> list.add(readValue(itemShape, item)));
        return list;
    }

    private ObjectNode readMap(JsonNode shape, List<Element> entries) {
        ObjectNode map = NODES.objectNode();
        String keyName = shape.path("key").path("locationName").asText("key");
        String valueName = shape.path("value").path("locationName").asText("value");
        JsonNode valueShape = shape(shape.path("value").path("shape").asText());
        for (Element entry : entries) {
            Element key = child(entry, keyName);
            Element value = child(entry, valueName);
            if (key != null && value != null) {
                map.set(key.getTextContent(), readValue(valueShape, value));
            }
        }
        return map;
    }

    private ObjectNode readStructure(JsonNode shape, Element element) {
        ObjectNode object = NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = shape.path("members").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode spec = entry.getValue();
            String wire = spec.path("locationName").asText(entry.getKey());
            JsonNode memberShape = shape(spec.path("shape").asText());
            if (spec.path("xmlAttribute").asBoolean(false)) {
                String attribute = element.getAttribute(wire.contains(":") ? wire.substring(wire.indexOf(':') + 1)
                        : wire);
                if (!attribute.isEmpty()) {
                    object.set(entry.getKey(), scalar(memberShape, attribute));
                }
                continue;
            }
            if (isFlattened(memberShape)) {
                JsonNode value = readFlattened(memberShape, element, wire);
                if (value != null) {
                    object.set(entry.getKey(), value);
                }
                continue;
            }
            Element child = child(element, wire);
            if (child != null) {
                object.set(entry.getKey(), readValue(memberShape, child));
            }
        }
        return object;
    }

    private static JsonNode scalar(JsonNode shape, String text) {
        String value = text == null ? "" : text.trim();
        return switch (shape.path("type").asText()) {
            case "integer", "long" -> {
                try {
                    yield NODES.numberNode(Long.parseLong(value));
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidRequest", "Expected an integer, got " + value + ".", 400);
                }
            }
            case "double", "float" -> {
                try {
                    yield NODES.numberNode(Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidRequest", "Expected a number, got " + value + ".", 400);
                }
            }
            case "boolean" -> NODES.booleanNode(Boolean.parseBoolean(value));
            default -> NODES.textNode(text == null ? "" : text);
        };
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    // ── Encoding ───────────────────────────────────────────────────────────

    /** An encoded response: its XML body (null when the output has none) and header members. */
    public record Encoded(String body, Map<String, String> headers) {}

    public Encoded encode(String operation, JsonNode output) {
        JsonNode op = operation(operation);
        Map<String, String> headers = new LinkedHashMap<>();
        if (!op.has("output")) {
            return new Encoded(null, headers);
        }
        String outputName = op.path("output").asText();
        JsonNode shape = shape(outputName);
        String payload = shape.path("payload").asText(null);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        boolean wroteBody = false;
        if (payload != null) {
            JsonNode spec = shape.path("members").path(payload);
            JsonNode value = output.path(payload);
            JsonNode payloadShape = shape(spec.path("shape").asText());
            if ("structure".equals(payloadShape.path("type").asText()) && value.isObject()) {
                String root = spec.path("locationName").asText(payload);
                writeElement(xml, root, payloadShape, value, true);
                wroteBody = true;
            } else if (!value.isMissingNode() && !value.isNull()) {
                return new Encoded(value.asText(), headers);
            }
        } else {
            StringBuilder inner = new StringBuilder();
            for (Iterator<Map.Entry<String, JsonNode>> it = shape.path("members").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode spec = entry.getValue();
                JsonNode value = output.get(entry.getKey());
                if (value == null || value.isNull()) {
                    continue;
                }
                if ("header".equals(spec.path("location").asText())) {
                    headers.put(spec.path("locationName").asText(entry.getKey()), value.asText());
                    continue;
                }
                writeMember(inner, entry.getKey(), spec, value);
            }
            xml.append('<').append(outputName).append(" xmlns=\"").append(namespace).append("\">").append(inner)
                    .append("</").append(outputName).append('>');
            wroteBody = true;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = shape.path("members").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if ("header".equals(entry.getValue().path("location").asText()) && output.hasNonNull(entry.getKey())) {
                headers.put(entry.getValue().path("locationName").asText(entry.getKey()),
                        output.get(entry.getKey()).asText());
            }
        }
        return new Encoded(wroteBody ? xml.toString() : null, headers);
    }

    private void writeMember(StringBuilder xml, String member, JsonNode spec, JsonNode value) {
        String wire = spec.path("locationName").asText(member);
        JsonNode memberShape = shape(spec.path("shape").asText());
        if (isFlattened(memberShape) && value.isArray()) {
            JsonNode itemShape = shape(memberShape.path("member").path("shape").asText());
            for (JsonNode item : value) {
                writeElement(xml, wire, itemShape, item, false);
            }
            return;
        }
        writeElement(xml, wire, memberShape, value, false);
    }

    private void writeElement(StringBuilder xml, String name, JsonNode shape, JsonNode value, boolean root) {
        xml.append('<').append(name);
        if (root) {
            xml.append(" xmlns=\"").append(namespace).append('"');
        }
        xml.append('>');
        switch (shape.path("type").asText()) {
            case "structure" -> {
                for (Iterator<Map.Entry<String, JsonNode>> it = shape.path("members").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode child = value.get(entry.getKey());
                    if (child != null && !child.isNull()) {
                        writeMember(xml, entry.getKey(), entry.getValue(), child);
                    }
                }
            }
            case "list" -> {
                String itemName = shape.path("member").path("locationName").asText("member");
                JsonNode itemShape = shape(shape.path("member").path("shape").asText());
                for (JsonNode item : value) {
                    writeElement(xml, itemName, itemShape, item, false);
                }
            }
            case "map" -> {
                String keyName = shape.path("key").path("locationName").asText("key");
                String valueName = shape.path("value").path("locationName").asText("value");
                JsonNode valueShape = shape(shape.path("value").path("shape").asText());
                for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    xml.append("<entry><").append(keyName).append('>').append(escape(entry.getKey())).append("</")
                            .append(keyName).append('>');
                    writeElement(xml, valueName, valueShape, entry.getValue(), false);
                    xml.append("</entry>");
                }
            }
            default -> xml.append(escape(value.asText()));
        }
        xml.append("</").append(name).append('>');
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
