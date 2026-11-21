package io.floci.aws.services.observabilityadmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The filter strings telemetry and centralization rules select with: comparisons of an attribute with a quoted value,
 * joined by {@code AND}, {@code OR} and {@code NOT} with parentheses. Comparisons are {@code =}, {@code !=},
 * {@code IN (...)}, {@code NOT IN (...)}, {@code LIKE} and {@code NOT LIKE} ({@code %} matches any run, {@code _} one
 * character). Attributes are names like {@code AccountId}, {@code OrganizationId}, {@code OrganizationUnitId},
 * {@code LogGroupName} or {@code tag:Environment}; one the resource doesn't have compares as absent.
 */
final class ObservabilityAdminFilter {

    private final String text;
    private final List<String> tokens;
    private int at;

    private ObservabilityAdminFilter(String text) {
        this.text = text;
        this.tokens = tokenize(text);
    }

    /** Whether a filter is well formed, or the problem with it. */
    static String problem(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        try {
            ObservabilityAdminFilter parser = new ObservabilityAdminFilter(filter);
            parser.expression(Map.of());
            if (parser.at != parser.tokens.size()) {
                return "Unexpected " + parser.tokens.get(parser.at) + " in the filter " + filter + ".";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Whether a resource with these attributes matches the filter; a blank filter matches everything. */
    static boolean matches(String filter, Map<String, List<String>> attributes) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        ObservabilityAdminFilter parser = new ObservabilityAdminFilter(filter);
        return parser.expression(attributes);
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated string in the filter " + text + ".");
                }
                tokens.add(text.substring(i, end + 1));
                i = end + 1;
            } else if (c == '(' || c == ')' || c == ',') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '!' && i + 1 < text.length() && text.charAt(i + 1) == '=') {
                tokens.add("!=");
                i += 2;
            } else if (c == '<' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                tokens.add("!=");
                i += 2;
            } else if (c == '=') {
                tokens.add("=");
                i++;
            } else {
                int start = i;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && "()',=!<\"".indexOf(
                        text.charAt(i)) < 0) {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("Unexpected " + c + " in the filter " + text + ".");
                }
                tokens.add(text.substring(start, i));
            }
        }
        return tokens;
    }

    private String peek() {
        return at < tokens.size() ? tokens.get(at) : null;
    }

    private boolean keyword(String word) {
        String next = peek();
        if (next != null && next.equalsIgnoreCase(word)) {
            at++;
            return true;
        }
        return false;
    }

    private String next(String what) {
        if (at >= tokens.size()) {
            throw new IllegalArgumentException("The filter " + text + " ends where " + what + " was expected.");
        }
        return tokens.get(at++);
    }

    private boolean expression(Map<String, List<String>> attributes) {
        boolean value = term(attributes);
        while (keyword("OR")) {
            value |= term(attributes);
        }
        return value;
    }

    private boolean term(Map<String, List<String>> attributes) {
        boolean value = factor(attributes);
        while (keyword("AND")) {
            value &= factor(attributes);
        }
        return value;
    }

    private boolean factor(Map<String, List<String>> attributes) {
        if (keyword("NOT")) {
            return !factor(attributes);
        }
        if ("(".equals(peek())) {
            at++;
            boolean value = expression(attributes);
            if (!")".equals(next("a closing parenthesis"))) {
                throw new IllegalArgumentException("Missing ) in the filter " + text + ".");
            }
            return value;
        }
        String name = next("an attribute");
        if (quoted(name) || "(".equals(name) || ")".equals(name)) {
            throw new IllegalArgumentException("Expected an attribute, not " + name + ", in the filter " + text + ".");
        }
        List<String> values = attribute(attributes, name);
        boolean negated = keyword("NOT");
        String op = next("a comparison").toUpperCase(Locale.ROOT);
        boolean result;
        switch (op) {
            case "=", "!=" -> {
                if (negated) {
                    throw new IllegalArgumentException("NOT can't precede " + op + " in the filter " + text + ".");
                }
                String literal = literal(next("a value"));
                boolean equal = values.stream().anyMatch(literal::equals);
                result = op.equals("=") == equal;
            }
            case "IN" -> {
                if (!"(".equals(next("a list"))) {
                    throw new IllegalArgumentException("IN takes a parenthesized list in the filter " + text + ".");
                }
                List<String> list = new ArrayList<>();
                do {
                    list.add(literal(next("a value")));
                } while (",".equals(peek()) && at++ >= 0);
                if (!")".equals(next("a closing parenthesis"))) {
                    throw new IllegalArgumentException("Missing ) in the filter " + text + ".");
                }
                result = negated != values.stream().anyMatch(list::contains);
            }
            case "LIKE" -> {
                Pattern pattern = like(literal(next("a pattern")));
                result = negated != values.stream().anyMatch(value -> pattern.matcher(value).matches());
            }
            default -> throw new IllegalArgumentException("Unknown comparison " + op + " in the filter " + text
                    + ".");
        }
        return result;
    }

    private static List<String> attribute(Map<String, List<String>> attributes, String name) {
        for (Map.Entry<String, List<String>> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) || (name.startsWith("tag:") && entry.getKey().equals(name))) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private static boolean quoted(String token) {
        return token.length() >= 2 && (token.startsWith("'") || token.startsWith("\""));
    }

    private String literal(String token) {
        if (!quoted(token)) {
            throw new IllegalArgumentException("Expected a quoted value, not " + token + ", in the filter " + text
                    + ".");
        }
        return token.substring(1, token.length() - 1);
    }

    private static Pattern like(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
