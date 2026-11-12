package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A CloudWatch pipelines {@code when} expression: fields by dot path ({@code user.role}), string, number, boolean and
 * null literals, sets ({@code {"prod", "staging"}}), relational, equality, arithmetic, set membership ({@code in},
 * {@code not in}) and regex ({@code =~}, {@code !~}) operators, {@code and}, {@code or} and {@code not}, and the
 * functions {@code length}, {@code contains} and {@code startsWith}. At most 256 characters.
 */
final class PipelineExpression {

    static final int MAX_LENGTH = 256;

    private final Function<ObjectNode, Object> compiled;

    private PipelineExpression(Function<ObjectNode, Object> compiled) {
        this.compiled = compiled;
    }

    /** Compiles an expression, failing with what's wrong with it. */
    static PipelineExpression compile(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Expressions are at most " + MAX_LENGTH + " characters.");
        }
        Parser parser = new Parser(text);
        Function<ObjectNode, Object> expression = parser.or();
        if (parser.at < parser.tokens.size()) {
            throw new IllegalArgumentException("Unexpected " + parser.tokens.get(parser.at) + " in " + text + ".");
        }
        return new PipelineExpression(expression);
    }

    boolean test(ObjectNode event) {
        return truthy(compiled.apply(event));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && !(value instanceof String text && text.isEmpty());
    }

    /** A field's value by dot path, as a Java value. */
    static Object field(ObjectNode event, String path) {
        JsonNode node = event;
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        return value(node);
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(value(item)));
            return list;
        }
        return node.toString();
    }

    private static Double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean equal(Object a, Object b) {
        Double x = number(a);
        Double y = number(b);
        if (a instanceof Double || b instanceof Double) {
            return x != null && y != null && x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    private static final class Parser {
        private final String text;
        private final List<String> tokens = new ArrayList<>();
        private int at;

        Parser(String text) {
            this.text = text;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '\'' || c == '"') {
                    StringBuilder literal = new StringBuilder().append(c);
                    int j = i + 1;
                    while (j < text.length() && text.charAt(j) != c) {
                        if (text.charAt(j) == '\\' && j + 1 < text.length()) {
                            j++;
                        }
                        literal.append(text.charAt(j));
                        j++;
                    }
                    if (j >= text.length()) {
                        throw new IllegalArgumentException("Unterminated string in " + text + ".");
                    }
                    tokens.add(literal.append(c).toString());
                    i = j + 1;
                } else if ("=!<>".indexOf(c) >= 0 && i + 1 < text.length()
                        && (text.charAt(i + 1) == '=' || text.charAt(i + 1) == '~')) {
                    tokens.add(text.substring(i, i + 2));
                    i += 2;
                } else if ("<>+-*/(){},".indexOf(c) >= 0) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '.') {
                    int j = i;
                    while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j))
                            || "_@.".indexOf(text.charAt(j)) >= 0)) {
                        j++;
                    }
                    tokens.add(text.substring(i, j));
                    i = j;
                } else {
                    throw new IllegalArgumentException("Unexpected " + c + " in " + text + ".");
                }
            }
        }

        private String peek() {
            return at < tokens.size() ? tokens.get(at) : null;
        }

        private boolean accept(String token) {
            if (token.equals(peek())) {
                at++;
                return true;
            }
            return false;
        }

        private String next() {
            if (at >= tokens.size()) {
                throw new IllegalArgumentException("The expression " + text + " ends early.");
            }
            return tokens.get(at++);
        }

        private void expect(String token) {
            if (!accept(token)) {
                throw new IllegalArgumentException("Expected " + token + " in " + text + ".");
            }
        }

        Function<ObjectNode, Object> or() {
            Function<ObjectNode, Object> left = and();
            while (accept("or")) {
                Function<ObjectNode, Object> l = left;
                Function<ObjectNode, Object> r = and();
                left = event -> truthy(l.apply(event)) || truthy(r.apply(event));
            }
            return left;
        }

        Function<ObjectNode, Object> and() {
            Function<ObjectNode, Object> left = not();
            while (accept("and")) {
                Function<ObjectNode, Object> l = left;
                Function<ObjectNode, Object> r = not();
                left = event -> truthy(l.apply(event)) && truthy(r.apply(event));
            }
            return left;
        }

        Function<ObjectNode, Object> not() {
            if (accept("not")) {
                Function<ObjectNode, Object> inner = not();
                return event -> !truthy(inner.apply(event));
            }
            return comparison();
        }

        Function<ObjectNode, Object> comparison() {
            Function<ObjectNode, Object> left = additive();
            String op = peek();
            if (op == null) {
                return left;
            }
            if ("not".equals(op) && at + 1 < tokens.size() && "in".equals(tokens.get(at + 1))) {
                at += 2;
                Function<ObjectNode, Object> set = additive();
                return event -> !member(left.apply(event), set.apply(event));
            }
            switch (op) {
                case "==", "!=", "<", "<=", ">", ">=", "=~", "!~", "in" -> at++;
                default -> {
                    return left;
                }
            }
            Function<ObjectNode, Object> right = additive();
            return switch (op) {
                case "==" -> event -> equal(left.apply(event), right.apply(event));
                case "!=" -> event -> !equal(left.apply(event), right.apply(event));
                case "in" -> event -> member(left.apply(event), right.apply(event));
                case "=~", "!~" -> {
                    boolean positive = op.equals("=~");
                    yield event -> {
                        Object value = left.apply(event);
                        Object pattern = right.apply(event);
                        if (value == null || pattern == null) {
                            return !positive;
                        }
                        try {
                            return positive == Pattern.compile(pattern.toString()).matcher(value.toString()).find();
                        } catch (PatternSyntaxException e) {
                            return false;
                        }
                    };
                }
                default -> event -> {
                    Double a = number(left.apply(event));
                    Double b = number(right.apply(event));
                    if (a == null || b == null) {
                        return false;
                    }
                    return switch (op) {
                        case "<" -> a < b;
                        case "<=" -> a <= b;
                        case ">" -> a > b;
                        default -> a >= b;
                    };
                };
            };
        }

        private static boolean member(Object value, Object set) {
            if (set instanceof List<?> list) {
                return list.stream().anyMatch(item -> equal(value, item));
            }
            return false;
        }

        Function<ObjectNode, Object> additive() {
            Function<ObjectNode, Object> left = multiplicative();
            while ("+".equals(peek()) || "-".equals(peek())) {
                String op = next();
                Function<ObjectNode, Object> l = left;
                Function<ObjectNode, Object> r = multiplicative();
                left = event -> arithmetic(op, l.apply(event), r.apply(event));
            }
            return left;
        }

        Function<ObjectNode, Object> multiplicative() {
            Function<ObjectNode, Object> left = primary();
            while ("*".equals(peek()) || "/".equals(peek())) {
                String op = next();
                Function<ObjectNode, Object> l = left;
                Function<ObjectNode, Object> r = primary();
                left = event -> arithmetic(op, l.apply(event), r.apply(event));
            }
            return left;
        }

        private static Object arithmetic(String op, Object a, Object b) {
            Double x = number(a);
            Double y = number(b);
            if (x == null || y == null) {
                return op.equals("+") && a instanceof String && b instanceof String ? a + (String) b : null;
            }
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                default -> y == 0 ? null : x / y;
            };
        }

        Function<ObjectNode, Object> primary() {
            String token = next();
            if (token.equals("(")) {
                Function<ObjectNode, Object> inner = or();
                expect(")");
                return inner;
            }
            if (token.equals("-")) {
                Function<ObjectNode, Object> inner = primary();
                return event -> {
                    Double value = number(inner.apply(event));
                    return value == null ? null : -value;
                };
            }
            if (token.equals("{")) {
                List<Function<ObjectNode, Object>> items = new ArrayList<>();
                if (!accept("}")) {
                    do {
                        items.add(additive());
                    } while (accept(","));
                    expect("}");
                }
                return event -> {
                    List<Object> values = new ArrayList<>();
                    items.forEach(item -> values.add(item.apply(event)));
                    return values;
                };
            }
            if (token.startsWith("'") || token.startsWith("\"")) {
                String literal = token.substring(1, token.length() - 1);
                return event -> literal;
            }
            if (token.equals("true") || token.equals("false")) {
                boolean literal = Boolean.parseBoolean(token);
                return event -> literal;
            }
            if (token.equals("null")) {
                return event -> null;
            }
            if (Character.isDigit(token.charAt(0))) {
                try {
                    double literal = Double.parseDouble(token);
                    return event -> literal;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad number " + token + " in " + text + ".");
                }
            }
            if ("(".equals(peek())) {
                at++;
                List<Function<ObjectNode, Object>> args = new ArrayList<>();
                if (!accept(")")) {
                    do {
                        args.add(or());
                    } while (accept(","));
                    expect(")");
                }
                return function(token, args);
            }
            if (List.of("and", "or", "not", "in").contains(token)) {
                throw new IllegalArgumentException("Unexpected " + token + " in " + text + ".");
            }
            return event -> field(event, token);
        }

        private Function<ObjectNode, Object> function(String name, List<Function<ObjectNode, Object>> args) {
            switch (name) {
                case "length" -> {
                    arity(name, args, 1);
                    return event -> {
                        Object value = args.get(0).apply(event);
                        if (value instanceof String text) {
                            return (double) text.length();
                        }
                        return value instanceof List<?> list ? (double) list.size() : null;
                    };
                }
                case "contains" -> {
                    arity(name, args, 2);
                    return event -> {
                        Object value = args.get(0).apply(event);
                        Object search = args.get(1).apply(event);
                        if (value instanceof String text && search != null) {
                            return text.contains(search.toString());
                        }
                        return value instanceof List<?> list && list.stream().anyMatch(item -> equal(item, search));
                    };
                }
                case "startsWith" -> {
                    arity(name, args, 2);
                    return event -> {
                        Object value = args.get(0).apply(event);
                        Object prefix = args.get(1).apply(event);
                        return value instanceof String text && prefix != null && text.startsWith(prefix.toString());
                    };
                }
                default -> throw new IllegalArgumentException("Unknown function " + name + " in " + text + ".");
            }
        }

        private void arity(String name, List<?> args, int count) {
            if (args.size() != count) {
                throw new IllegalArgumentException(name + " takes " + count + " arguments in " + text + ".");
            }
        }
    }
}
