package io.github.hectorvent.floci.services.b2bi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal JSONata evaluator covering object/array constructors, literals, and
 * dotted path lookups against the input document. Enough to emulate B2BI
 * {@code TestMapping} / transformer mapping templates used by Alchemy.
 */
final class B2biJsonata {

    private B2biJsonata() {
    }

    static JsonNode evaluate(String template, JsonNode input, ObjectMapper mapper) {
        if (template == null || template.isBlank()) {
            return input == null ? NullNode.getInstance() : input;
        }
        Parser parser = new Parser(template, input == null ? mapper.nullNode() : input, mapper);
        JsonNode result = parser.parseValue();
        parser.skipWs();
        if (!parser.done()) {
            throw new IllegalArgumentException("Unexpected trailing characters in mapping template");
        }
        return result;
    }

    private static final class Parser {
        private final String src;
        private final JsonNode input;
        private final ObjectMapper mapper;
        private int i;

        Parser(String src, JsonNode input, ObjectMapper mapper) {
            this.src = src;
            this.input = input;
            this.mapper = mapper;
        }

        boolean done() {
            return i >= src.length();
        }

        void skipWs() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
        }

        JsonNode parseValue() {
            skipWs();
            if (done()) {
                throw new IllegalArgumentException("Unexpected end of mapping template");
            }
            char c = src.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return mapper.getNodeFactory().textNode(parseString());
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            if (src.startsWith("true", i) && boundary(i + 4)) {
                i += 4;
                return mapper.getNodeFactory().booleanNode(true);
            }
            if (src.startsWith("false", i) && boundary(i + 5)) {
                i += 5;
                return mapper.getNodeFactory().booleanNode(false);
            }
            if (src.startsWith("null", i) && boundary(i + 4)) {
                i += 4;
                return NullNode.getInstance();
            }
            return lookup(parsePath());
        }

        private boolean boundary(int at) {
            return at >= src.length() || !isIdentPart(src.charAt(at));
        }

        private ObjectNode parseObject() {
            i++; // {
            ObjectNode object = mapper.createObjectNode();
            skipWs();
            if (peek('}')) {
                i++;
                return object;
            }
            while (true) {
                skipWs();
                String key = parseKey();
                skipWs();
                expect(':');
                JsonNode value = parseValue();
                object.set(key, value);
                skipWs();
                if (peek('}')) {
                    i++;
                    return object;
                }
                expect(',');
            }
        }

        private ArrayNode parseArray() {
            i++; // [
            ArrayNode array = mapper.createArrayNode();
            skipWs();
            if (peek(']')) {
                i++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWs();
                if (peek(']')) {
                    i++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseKey() {
            skipWs();
            if (done()) {
                throw new IllegalArgumentException("Expected object key");
            }
            if (src.charAt(i) == '"') {
                return parseString();
            }
            return parseIdentifier();
        }

        private String parsePath() {
            String first = parseIdentifier();
            StringBuilder path = new StringBuilder(first);
            skipWs();
            while (peek('.')) {
                i++;
                skipWs();
                path.append('.').append(parseIdentifier());
                skipWs();
            }
            return path.toString();
        }

        private String parseIdentifier() {
            skipWs();
            if (done()) {
                throw new IllegalArgumentException("Expected identifier");
            }
            char c = src.charAt(i);
            if (c != '$' && !Character.isLetter(c) && c != '_') {
                throw new IllegalArgumentException("Expected identifier at index " + i);
            }
            int start = i;
            i++;
            while (i < src.length() && isIdentPart(src.charAt(i))) {
                i++;
            }
            return src.substring(start, i);
        }

        private static boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < src.length()) {
                char c = src.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (done()) {
                        throw new IllegalArgumentException("Unterminated string escape");
                    }
                    char e = src.charAt(i++);
                    out.append(switch (e) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private JsonNode parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
            if (peek('.')) {
                i++;
                while (i < src.length() && Character.isDigit(src.charAt(i))) {
                    i++;
                }
            }
            String raw = src.substring(start, i);
            if (raw.contains(".")) {
                return mapper.getNodeFactory().numberNode(Double.parseDouble(raw));
            }
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return mapper.getNodeFactory().numberNode((int) value);
            }
            return mapper.getNodeFactory().numberNode(value);
        }

        private JsonNode lookup(String path) {
            if ("$".equals(path)) {
                return input;
            }
            JsonNode current = input;
            for (String part : path.split("\\.")) {
                if (current == null || current.isMissingNode() || current.isNull()) {
                    return NullNode.getInstance();
                }
                if (current.isObject()) {
                    current = current.get(part);
                } else if (current.isArray() && part.matches("\\d+")) {
                    current = current.get(Integer.parseInt(part));
                } else {
                    return NullNode.getInstance();
                }
            }
            return current == null ? NullNode.getInstance() : current;
        }

        private boolean peek(char c) {
            return i < src.length() && src.charAt(i) == c;
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) {
                throw new IllegalArgumentException("Expected '" + c + "' at index " + i);
            }
            i++;
        }
    }
}
