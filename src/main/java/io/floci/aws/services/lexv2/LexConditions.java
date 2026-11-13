package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates the expressions of conditional branches: {@code {Slot}}, {@code [sessionAttribute]} and
 * {@code ((requestAttribute))} references, quoted strings and numbers compared with {@code = != < > <= >=}, combined
 * with {@code AND}, {@code OR}, {@code NOT} and parentheses, and the functions {@code fn.COUNT}, {@code fn.IS_SET} and
 * {@code fn.IS_EMPTY}. An expression that doesn't parse is false.
 */
final class LexConditions {

    private final List<String> tokens;
    private final JsonNode slots;
    private final JsonNode session;
    private final JsonNode request;
    private int position;

    private LexConditions(List<String> tokens, JsonNode slots, JsonNode session, JsonNode request) {
        this.tokens = tokens;
        this.slots = slots;
        this.session = session;
        this.request = request;
    }

    static boolean evaluate(String expression, JsonNode slots, JsonNode session, JsonNode request) {
        try {
            LexConditions parser = new LexConditions(tokenize(expression), slots, session, request);
            boolean result = parser.or();
            return parser.position == parser.tokens.size() && result;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = expression.indexOf(c, i + 1);
                tokens.add(expression.substring(i, end + 1));
                i = end + 1;
            } else if (c == '{') {
                int end = expression.indexOf('}', i);
                tokens.add(expression.substring(i, end + 1));
                i = end + 1;
            } else if (expression.startsWith("((", i)) {
                int end = expression.indexOf("))", i);
                tokens.add(expression.substring(i, end + 2));
                i = end + 2;
            } else if (c == '[') {
                int end = expression.indexOf(']', i);
                tokens.add(expression.substring(i, end + 1));
                i = end + 1;
            } else if ("()=,".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '!' || c == '<' || c == '>') {
                boolean two = i + 1 < expression.length() && expression.charAt(i + 1) == '=';
                tokens.add(expression.substring(i, i + (two ? 2 : 1)));
                i += two ? 2 : 1;
            } else {
                int start = i;
                while (i < expression.length() && !Character.isWhitespace(expression.charAt(i))
                        && "(){}[]=!<>,\"'".indexOf(expression.charAt(i)) < 0) {
                    i++;
                }
                tokens.add(expression.substring(start, i));
            }
        }
        return tokens;
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }

    private boolean accept(String token) {
        if (token.equalsIgnoreCase(peek() == null ? "" : peek())) {
            position++;
            return true;
        }
        return false;
    }

    private boolean or() {
        boolean value = and();
        while (accept("OR")) {
            value |= and();
        }
        return value;
    }

    private boolean and() {
        boolean value = not();
        while (accept("AND")) {
            value &= not();
        }
        return value;
    }

    private boolean not() {
        if (accept("NOT")) {
            return !not();
        }
        return comparison();
    }

    private boolean comparison() {
        if ("(".equals(peek())) {
            int saved = position;
            position++;
            boolean value = or();
            if (accept(")")) {
                return value;
            }
            position = saved;
        }
        Object left = operand();
        String op = peek();
        if (op != null && List.of("=", "!=", "<", ">", "<=", ">=").contains(op)) {
            position++;
            Object right = operand();
            return compare(left, op, right);
        }
        if (left instanceof Boolean b) {
            return b;
        }
        return left != null && !"".equals(left);
    }

    private Object operand() {
        String token = tokens.get(position++);
        if (token.startsWith("\"") || token.startsWith("'")) {
            return token.substring(1, token.length() - 1);
        }
        if (token.startsWith("{")) {
            return slot(token.substring(1, token.length() - 1));
        }
        if (token.startsWith("((")) {
            return text(request, token.substring(2, token.length() - 2));
        }
        if (token.startsWith("[")) {
            return text(session, token.substring(1, token.length() - 1));
        }
        if (token.toLowerCase(Locale.ROOT).startsWith("fn.")) {
            String name = token.substring(3).toUpperCase(Locale.ROOT);
            if (!accept("(")) {
                throw new IllegalArgumentException("expected (");
            }
            String argument = tokens.get(position++);
            if (!accept(")")) {
                throw new IllegalArgumentException("expected )");
            }
            JsonNode slot = argument.startsWith("{") ? slots.path(argument.substring(1, argument.length() - 1))
                    : null;
            Object value = argument.startsWith("{") ? slot(argument.substring(1, argument.length() - 1))
                    : argument.startsWith("[") ? text(session, argument.substring(1, argument.length() - 1)) : null;
            return switch (name) {
                case "COUNT" -> slot != null && slot.path("values").isArray() && slot.path("values").size() > 0
                        ? (double) slot.path("values").size() : value == null ? 0.0 : 1.0;
                case "IS_SET" -> value != null;
                case "IS_EMPTY" -> value == null || "".equals(value);
                default -> throw new IllegalArgumentException("unknown function " + name);
            };
        }
        if ("true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token)) {
            return Boolean.parseBoolean(token.toLowerCase(Locale.ROOT));
        }
        return token;
    }

    private String slot(String name) {
        String[] parts = name.split("\\.", 2);
        JsonNode slot = slots.path(parts[0]);
        if (parts.length == 2 && slot.path("subSlots").isObject()) {
            slot = slot.path("subSlots").path(parts[1]);
        }
        JsonNode value = slot.path("value").path("interpretedValue");
        return value.isTextual() ? value.asText() : null;
    }

    private static String text(JsonNode attributes, String name) {
        JsonNode value = attributes == null ? null : attributes.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean compare(Object left, String op, Object right) {
        if (left == null || right == null) {
            return "!=".equals(op) ? left != right : "=".equals(op) && left == right;
        }
        Double a = number(left);
        Double b = number(right);
        int c;
        if (a != null && b != null) {
            c = Double.compare(a, b);
        } else {
            c = String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
        }
        return switch (op) {
            case "=" -> c == 0;
            case "!=" -> c != 0;
            case "<" -> c < 0;
            case ">" -> c > 0;
            case "<=" -> c <= 0;
            default -> c >= 0;
        };
    }

    private static Double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
