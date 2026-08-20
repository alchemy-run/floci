package io.github.hectorvent.floci.services.appsync.graphql;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the APPSYNC_JS subset used by resolver/function handlers:
 * {@code export function request(ctx) { return ... }} /
 * {@code export function response(ctx) { return ... }}.
 */
@ApplicationScoped
public class AppSyncJsEngine {

    private static final Pattern FUNCTION = Pattern.compile(
            "(?:export\\s+)?function\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{",
            Pattern.MULTILINE);

    public Object evaluate(String code, String functionName, Map<String, Object> context) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String name = functionName == null || functionName.isBlank() ? "request" : functionName;
        String body = extractFunctionBody(code, name);
        if (body == null) {
            throw new IllegalArgumentException("Function not found: " + name);
        }
        String expr = extractReturnExpression(body);
        return new Interpreter(wrapContext(context)).eval(expr);
    }

    static Map<String, Object> wrapContext(Map<String, Object> context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (context != null) {
            ctx.putAll(context);
        }
        Object arguments = ctx.get("arguments");
        if (arguments == null) {
            arguments = ctx.get("args");
        }
        if (arguments == null) {
            arguments = Map.of();
        }
        ctx.put("arguments", arguments);
        ctx.put("args", arguments);
        ctx.putIfAbsent("source", Map.of());
        ctx.putIfAbsent("stash", new LinkedHashMap<>());
        ctx.putIfAbsent("info", Map.of());
        ctx.putIfAbsent("identity", Map.of());
        ctx.putIfAbsent("request", Map.of("headers", Map.of()));
        ctx.putIfAbsent("env", Map.of());
        return ctx;
    }

    static String extractFunctionBody(String code, String functionName) {
        Matcher matcher = FUNCTION.matcher(code);
        while (matcher.find()) {
            if (!functionName.equals(matcher.group(1))) {
                continue;
            }
            int start = matcher.end();
            int depth = 1;
            for (int i = start; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return code.substring(start, i);
                    }
                } else if (c == '"' || c == '\'' || c == '`') {
                    i = skipString(code, i);
                }
            }
        }
        return null;
    }

    static String extractReturnExpression(String body) {
        String trimmed = body.strip();
        if (trimmed.startsWith("return")) {
            String rest = trimmed.substring("return".length()).strip();
            if (rest.endsWith(";")) {
                rest = rest.substring(0, rest.length() - 1).strip();
            }
            return rest;
        }
        throw new IllegalArgumentException("APPSYNC_JS function must return a value");
    }

    private static int skipString(String code, int start) {
        char quote = code.charAt(start);
        for (int i = start + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i;
            }
        }
        return code.length() - 1;
    }

    static final class Interpreter {
        private final Map<String, Object> ctx;
        private final String expr;
        private int pos;

        Interpreter(Map<String, Object> ctx) {
            this.ctx = ctx;
            this.expr = "";
            this.pos = 0;
        }

        Object eval(String expression) {
            Interpreter nested = new Interpreter(ctx, expression);
            Object value = nested.parseExpression();
            nested.skipWs();
            if (nested.pos < nested.expr.length()) {
                throw new IllegalArgumentException("Unexpected token at " + nested.pos + " in: " + expression);
            }
            return value;
        }

        private Interpreter(Map<String, Object> ctx, String expr) {
            this.ctx = ctx;
            this.expr = expr;
            this.pos = 0;
        }

        private Object parseExpression() {
            return parseAdd();
        }

        private Object parseAdd() {
            Object left = parseMul();
            while (true) {
                skipWs();
                if (match('+')) {
                    Object right = parseMul();
                    left = add(left, right);
                } else if (match('-')) {
                    Object right = parseMul();
                    left = subtract(left, right);
                } else {
                    return left;
                }
            }
        }

        private Object parseMul() {
            Object left = parseUnary();
            while (true) {
                skipWs();
                if (match('*')) {
                    Object right = parseUnary();
                    left = multiply(left, right);
                } else if (match('/')) {
                    Object right = parseUnary();
                    left = divide(left, right);
                } else {
                    return left;
                }
            }
        }

        private Object parseUnary() {
            skipWs();
            if (match('-')) {
                return subtract(0, parseUnary());
            }
            if (match('+')) {
                return parseUnary();
            }
            return parseMember();
        }

        private Object parseMember() {
            Object value = parsePrimary();
            while (true) {
                skipWs();
                if (match('.')) {
                    String name = parseIdent();
                    value = property(value, name);
                } else if (peek() == '[') {
                    pos++;
                    Object key = parseExpression();
                    skipWs();
                    expect(']');
                    value = property(value, String.valueOf(key));
                } else {
                    return value;
                }
            }
        }

        private Object parsePrimary() {
            skipWs();
            if (pos >= expr.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            char c = expr.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '(') {
                pos++;
                Object value = parseExpression();
                skipWs();
                expect(')');
                return value;
            }
            if (c == '"' || c == '\'') {
                return parseString();
            }
            if (c == '`') {
                return parseTemplate();
            }
            if (Character.isDigit(c)) {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                String ident = parseIdent();
                return switch (ident) {
                    case "null", "undefined" -> null;
                    case "true" -> true;
                    case "false" -> false;
                    case "ctx", "context" -> ctx;
                    default -> ctx.get(ident);
                };
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "' at " + pos);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWs();
            if (match('}')) {
                return object;
            }
            while (true) {
                skipWs();
                String key;
                if (peek() == '"' || peek() == '\'') {
                    key = parseString();
                } else {
                    key = parseIdent();
                }
                skipWs();
                expect(':');
                object.put(key, parseExpression());
                skipWs();
                if (match(',')) {
                    skipWs();
                    if (peek() == '}') {
                        pos++;
                        return object;
                    }
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (match(']')) {
                return list;
            }
            while (true) {
                list.add(parseExpression());
                skipWs();
                if (match(',')) {
                    skipWs();
                    if (peek() == ']') {
                        pos++;
                        return list;
                    }
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String parseString() {
            char quote = expr.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (pos < expr.length()) {
                char c = expr.charAt(pos++);
                if (c == '\\' && pos < expr.length()) {
                    sb.append(expr.charAt(pos++));
                    continue;
                }
                if (c == quote) {
                    return sb.toString();
                }
                sb.append(c);
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private String parseTemplate() {
            expect('`');
            StringBuilder sb = new StringBuilder();
            while (pos < expr.length()) {
                char c = expr.charAt(pos++);
                if (c == '`') {
                    return sb.toString();
                }
                if (c == '$' && peek() == '{') {
                    pos++;
                    Object value = parseExpression();
                    skipWs();
                    expect('}');
                    sb.append(value == null ? "" : String.valueOf(value));
                    continue;
                }
                sb.append(c);
            }
            throw new IllegalArgumentException("Unterminated template");
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            String raw = expr.substring(start, pos);
            if (raw.contains(".")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private String parseIdent() {
            skipWs();
            int start = pos;
            if (pos >= expr.length() || !(Character.isLetter(expr.charAt(pos)) || expr.charAt(pos) == '_')) {
                throw new IllegalArgumentException("Expected identifier at " + pos);
            }
            pos++;
            while (pos < expr.length()) {
                char c = expr.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    pos++;
                } else {
                    break;
                }
            }
            return expr.substring(start, pos);
        }

        private Object property(Object target, String name) {
            if (target == null) {
                return null;
            }
            if (target instanceof Map<?, ?> map) {
                return map.get(name);
            }
            return null;
        }

        private Object add(Object left, Object right) {
            if (left instanceof String || right instanceof String) {
                return String.valueOf(left == null ? "" : left) + (right == null ? "" : right);
            }
            return toDouble(left) + toDouble(right);
        }

        private Object subtract(Object left, Object right) {
            return toDouble(left) - toDouble(right);
        }

        private Object multiply(Object left, Object right) {
            return toDouble(left) * toDouble(right);
        }

        private Object divide(Object left, Object right) {
            return toDouble(left) / toDouble(right);
        }

        private double toDouble(Object value) {
            if (value == null) {
                return 0;
            }
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            return Double.parseDouble(String.valueOf(value));
        }

        private void skipWs() {
            while (pos < expr.length() && Character.isWhitespace(expr.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(char expected) {
            skipWs();
            if (pos < expr.length() && expr.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            skipWs();
            if (pos >= expr.length() || expr.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
            }
            pos++;
        }

        private char peek() {
            skipWs();
            return pos < expr.length() ? expr.charAt(pos) : '\0';
        }
    }
}
