package io.floci.aws.services.b2bi;

import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B2BI mapping templates: JSONata over JSON and XSLT over XML, the JSON text B2BI writes (two-space indented,
 * {@code "key": value}), and {@code GenerateMapping}, which Floci answers without a model: each value of the
 * sample output is looked up in the sample input, and the template selects it from where it was found.
 */
final class B2biMappings {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MILLIS = 5000;
    private static final int MAX_DEPTH = 500;

    private B2biMappings() {
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    /** Fails with {@code ValidationException} when a template doesn't compile. */
    static void checkTemplate(String language, String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        if ("JSONATA".equals(language)) {
            try {
                Jsonata.jsonata(template);
            } catch (RuntimeException e) {
                throw invalid("The JSONata mapping template doesn't compile: " + e.getMessage());
            }
        } else {
            xslt(template);
        }
    }

    /** Applies a mapping template: JSONata to a JSON document, XSLT to an XML one. Returns the mapped text. */
    static String apply(String language, String template, String input, String fileFormat) {
        if ("XSLT".equals(language)) {
            return transform(template, input);
        }
        JsonNode document = "XML".equals(fileFormat) ? xmlToJson(input) : parseJson(input);
        return pretty(jsonata(template, document));
    }

    static JsonNode jsonata(String template, JsonNode document) {
        try {
            Jsonata expression = Jsonata.jsonata(template);
            Jsonata.Frame frame = expression.createFrame();
            frame.setRuntimeBounds(TIMEOUT_MILLIS, MAX_DEPTH);
            Object result = expression.evaluate(MAPPER.convertValue(document, Object.class), frame);
            return result == null ? MAPPER.nullNode() : MAPPER.valueToTree(result);
        } catch (RuntimeException e) {
            throw invalid("The JSONata mapping template failed: " + e.getMessage());
        }
    }

    static JsonNode parseJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw invalid("The input isn't valid JSON: " + e.getMessage());
        }
    }

    private static Transformer xslt(String template) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            return factory.newTransformer(new StreamSource(new StringReader(template)));
        } catch (Exception e) {
            throw invalid("The XSLT mapping template doesn't compile: " + e.getMessage());
        }
    }

    static String transform(String template, String xml) {
        B2biX12.parseXml(xml);
        try {
            StringWriter out = new StringWriter();
            xslt(template).transform(new StreamSource(new StringReader(xml)), new StreamResult(out));
            return out.toString();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("The XSLT mapping template failed: " + e.getMessage());
        }
    }

    /** A JSON value as B2BI writes it: two-space indented, {@code "key": value}, one element per line. */
    static String pretty(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(value, out, "");
        return out.toString();
    }

    private static void write(JsonNode value, StringBuilder out, String indent) {
        if (value.isObject()) {
            if (value.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.append(indent).append("  ").append(MAPPER.valueToTree(field.getKey()).toString()).append(": ");
                write(field.getValue(), out, indent + "  ");
                out.append(fields.hasNext() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (value.isArray()) {
            if (value.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < value.size(); i++) {
                out.append(indent).append("  ");
                write(value.get(i), out, indent + "  ");
                out.append(i + 1 < value.size() ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else {
            out.append(value.toString());
        }
    }

    /** An XML document as JSON: attributes as {@code @name}, text as {@code #text}, repeated elements as arrays. */
    static JsonNode xmlToJson(String xml) {
        Document document = B2biX12.parseXml(xml);
        ObjectNode root = MAPPER.createObjectNode();
        root.set(document.getDocumentElement().getTagName(), elementJson(document.getDocumentElement()));
        return root;
    }

    private static JsonNode elementJson(Element element) {
        ObjectNode out = MAPPER.createObjectNode();
        for (int a = 0; a < element.getAttributes().getLength(); a++) {
            Node attribute = element.getAttributes().item(a);
            out.put("@" + attribute.getNodeName(), attribute.getNodeValue());
        }
        Map<String, List<JsonNode>> children = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int n = 0; n < nodes.getLength(); n++) {
            Node node = nodes.item(n);
            if (node instanceof Element child) {
                children.computeIfAbsent(child.getTagName(), k -> new ArrayList<>()).add(elementJson(child));
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        if (children.isEmpty() && out.isEmpty()) {
            return MAPPER.getNodeFactory().textNode(text.toString().trim());
        }
        children.forEach((name, values) -> {
            if (values.size() == 1) {
                out.set(name, values.get(0));
            } else {
                ArrayNode array = out.putArray(name);
                values.forEach(array::add);
            }
        });
        if (!text.toString().isBlank()) {
            out.put("#text", text.toString().trim());
        }
        return out;
    }

    // ── GenerateMapping ────────────────────────────────────────────────────

    record Generated(String template, double accuracy) {
    }

    /**
     * A mapping template from a sample input and the output it should produce. Every leaf of the output is
     * looked for in the input: one found is selected by its path (inside an array the output repeats, relative to
     * the array's element), one not found is written as the literal value. Accuracy is the share of output leaves
     * the template reproduces when it is applied to the input.
     */
    static Generated generate(String inputContent, String outputContent, String mappingType) {
        if ("XSLT".equals(mappingType)) {
            return generateXslt(inputContent, outputContent);
        }
        JsonNode input = looksLikeXml(inputContent) ? xmlToJson(inputContent) : parseJson(inputContent);
        JsonNode output = parseJson(outputContent);
        String template = expression(output, input, List.of(), 0);
        JsonNode produced;
        try {
            produced = jsonata(template, input);
        } catch (AwsException e) {
            produced = MAPPER.nullNode();
        }
        int[] counts = new int[2];
        compare(output, produced, counts);
        return new Generated(template, counts[1] == 0 ? 1.0 : (double) counts[0] / counts[1]);
    }

    private static boolean looksLikeXml(String content) {
        return content.stripLeading().startsWith("<");
    }

    private static String expression(JsonNode output, JsonNode scope, List<String> scopePath, int depth) {
        String indent = "  ".repeat(depth);
        if (output.isObject()) {
            if (output.isEmpty()) {
                return "{}";
            }
            StringBuilder out = new StringBuilder("{\n");
            Iterator<Map.Entry<String, JsonNode>> fields = output.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.append(indent).append("  ").append(MAPPER.valueToTree(field.getKey()).toString()).append(": ")
                        .append(expression(field.getValue(), scope, scopePath, depth + 1))
                        .append(fields.hasNext() ? ",\n" : "\n");
            }
            return out.append(indent).append('}').toString();
        }
        if (output.isArray()) {
            if (output.isEmpty()) {
                return "[]";
            }
            JsonNode sample = output.get(0);
            List<String> arrayPath = sample.isObject() ? arrayMatching(scope, sample, new ArrayList<>()) : null;
            if (arrayPath != null) {
                JsonNode elements = at(scope, arrayPath);
                String body = expression(sample, elements.get(0), List.of(), depth + 1);
                return "[" + path(arrayPath) + ".(" + body + ")]";
            }
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < output.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(expression(output.get(i), scope, scopePath, depth + 1));
            }
            return out.append(']').toString();
        }
        List<String> found = findValue(scope, output, new ArrayList<>());
        if (found != null) {
            String path = path(found);
            return output.isNumber() && !at(scope, found).isNumber() ? "$number(" + path + ")"
                    : output.isTextual() && !at(scope, found).isTextual() ? "$string(" + path + ")" : path;
        }
        return output.toString();
    }

    /** The path of an array in {@code node} whose first element has a value of every leaf of {@code sample}. */
    private static List<String> arrayMatching(JsonNode node, JsonNode sample, List<String> at) {
        if (node.isArray() && !node.isEmpty() && node.get(0).isObject()) {
            List<JsonNode> leaves = new ArrayList<>();
            leaves(sample, leaves);
            boolean all = !leaves.isEmpty() && leaves.stream().anyMatch(l -> findValue(node.get(0), l,
                    new ArrayList<>()) != null);
            if (all) {
                return at;
            }
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<String> next = new ArrayList<>(at);
                next.add(field.getKey());
                List<String> found = arrayMatching(field.getValue(), sample, next);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void leaves(JsonNode node, List<JsonNode> out) {
        if (node.isContainerNode()) {
            node.forEach(child -> leaves(child, out));
        } else {
            out.add(node);
        }
    }

    /** The path to the first leaf of {@code node} equal to {@code value} (as text), through objects only. */
    private static List<String> findValue(JsonNode node, JsonNode value, List<String> at) {
        if (node.isValueNode()) {
            return node.asText().equals(value.asText()) && !value.asText().isEmpty() ? at : null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<String> next = new ArrayList<>(at);
                next.add(field.getKey());
                List<String> found = findValue(field.getValue(), value, next);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node.isArray() && !node.isEmpty()) {
            return findValue(node.get(0), value, at);
        }
        return null;
    }

    private static JsonNode at(JsonNode node, List<String> path) {
        JsonNode current = node;
        for (String step : path) {
            current = current.isArray() ? current.path(0).path(step) : current.path(step);
        }
        return current;
    }

    private static String path(List<String> steps) {
        if (steps.isEmpty()) {
            return "$";
        }
        StringBuilder out = new StringBuilder();
        for (String step : steps) {
            out.append(out.isEmpty() ? "" : ".").append(step.matches("[A-Za-z_][A-Za-z0-9_]*") ? step
                    : "`" + step.replace("`", "") + "`");
        }
        return out.toString();
    }

    private static void compare(JsonNode expected, JsonNode actual, int[] counts) {
        if (expected.isObject()) {
            expected.fields().forEachRemaining(f -> compare(f.getValue(), actual == null ? null : actual.get(f.getKey()),
                    counts));
        } else if (expected.isArray()) {
            for (int i = 0; i < expected.size(); i++) {
                compare(expected.get(i), actual != null && actual.isArray() ? actual.get(i) : null, counts);
            }
        } else {
            counts[1]++;
            if (actual != null && actual.isValueNode() && actual.asText().equals(expected.asText())) {
                counts[0]++;
            }
        }
    }

    /** An XSLT template that writes the sample output, each text found in the input selected from it. */
    private static Generated generateXslt(String inputContent, String outputContent) {
        Document input = B2biX12.parseXml(inputContent);
        Document output = B2biX12.parseXml(outputContent);
        Map<String, String> paths = new LinkedHashMap<>();
        textPaths(input.getDocumentElement(), "/" + input.getDocumentElement().getTagName(), paths);
        int[] counts = new int[2];
        StringBuilder template = new StringBuilder("<xsl:stylesheet version=\"1.0\" "
                + "xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n  <xsl:output method=\"xml\" indent=\"yes\"/>\n"
                + "  <xsl:template match=\"/\">\n");
        xsltBody(output.getDocumentElement(), paths, template, "    ", counts);
        template.append("  </xsl:template>\n</xsl:stylesheet>\n");
        return new Generated(template.toString(), counts[1] == 0 ? 1.0 : (double) counts[0] / counts[1]);
    }

    private static void textPaths(Element element, String path, Map<String, String> out) {
        for (int a = 0; a < element.getAttributes().getLength(); a++) {
            Node attribute = element.getAttributes().item(a);
            out.putIfAbsent(attribute.getNodeValue(), path + "/@" + attribute.getNodeName());
        }
        NodeList nodes = element.getChildNodes();
        boolean leaf = true;
        for (int n = 0; n < nodes.getLength(); n++) {
            if (nodes.item(n) instanceof Element child) {
                leaf = false;
                textPaths(child, path + "/" + child.getTagName(), out);
            }
        }
        if (leaf && !element.getTextContent().isBlank()) {
            out.putIfAbsent(element.getTextContent().trim(), path);
        }
    }

    private static void xsltBody(Element element, Map<String, String> paths, StringBuilder out, String indent,
                                 int[] counts) {
        out.append(indent).append('<').append(element.getTagName());
        for (int a = 0; a < element.getAttributes().getLength(); a++) {
            Node attribute = element.getAttributes().item(a);
            counts[1]++;
            String source = paths.get(attribute.getNodeValue());
            if (source != null) {
                counts[0]++;
                out.append(' ').append(attribute.getNodeName()).append("=\"{").append(source).append("}\"");
            } else {
                out.append(' ').append(attribute.getNodeName()).append("=\"")
                        .append(B2biX12.escape(attribute.getNodeValue())).append('"');
            }
        }
        out.append('>');
        NodeList nodes = element.getChildNodes();
        boolean leaf = true;
        for (int n = 0; n < nodes.getLength(); n++) {
            if (nodes.item(n) instanceof Element) {
                leaf = false;
                break;
            }
        }
        if (leaf) {
            String text = element.getTextContent().trim();
            if (!text.isEmpty()) {
                counts[1]++;
                String source = paths.get(text);
                if (source != null) {
                    counts[0]++;
                    out.append("<xsl:value-of select=\"").append(source).append("\"/>");
                } else {
                    out.append(B2biX12.escape(text));
                }
            }
        } else {
            out.append('\n');
            for (int n = 0; n < nodes.getLength(); n++) {
                if (nodes.item(n) instanceof Element child) {
                    xsltBody(child, paths, out, indent + "  ", counts);
                }
            }
            out.append(indent);
        }
        out.append("</").append(element.getTagName()).append(">\n");
    }
}
