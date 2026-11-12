package io.floci.aws.services.xray;

import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * X-Ray filter expressions, as trace summaries, groups and entity selectors use them:
 *
 * <ul>
 *   <li>boolean keywords {@code ok}, {@code error}, {@code fault}, {@code throttle}, {@code partial}</li>
 *   <li>number keywords {@code responsetime}, {@code duration}, {@code http.status}</li>
 *   <li>string keywords {@code http.url}, {@code http.method}, {@code http.useragent}, {@code http.clientip},
 *       {@code user}, {@code name}</li>
 *   <li>{@code annotation.key} and {@code annotation[key]}</li>
 *   <li>{@code service("name")}, {@code service(id(name: "name", type: "type"))}, {@code service()} and
 *       {@code edge("source", "destination")}, each optionally followed by {@code { filter }} scoped to it</li>
 *   <li>{@code group.name} and {@code group.arn}, which apply the named group's filter</li>
 *   <li>comparisons {@code = != < <= > >=} and {@code CONTAINS}, {@code BEGINSWITH}, {@code ENDSWITH}; {@code AND}
 *       ({@code &&}), {@code OR} ({@code ||}), {@code NOT} ({@code !}) and parentheses</li>
 * </ul>
 */
final class XRayFilter {

    /** Resolves {@code group.name} and {@code group.arn} to that group's filter. */
    interface Groups {
        Predicate<XRayTraces.Scope> filterOf(String field, String value);
    }

    private final List<String> tokens;
    private final Groups groups;
    private int position;

    private XRayFilter(List<String> tokens, Groups groups) {
        this.tokens = tokens;
        this.groups = groups;
    }

    /** Compiles an expression; a blank one matches everything. */
    static Predicate<XRayTraces.Scope> compile(String expression, Groups groups) {
        if (expression == null || expression.isBlank()) {
            return scope -> true;
        }
        XRayFilter parser = new XRayFilter(tokenize(expression), groups);
        Predicate<XRayTraces.Scope> predicate = parser.or();
        if (parser.position != parser.tokens.size()) {
            throw invalid("Unexpected '" + parser.tokens.get(parser.position) + "' in the filter expression.");
        }
        return predicate;
    }

    // ---------- tokens ----------

    private static List<String> tokenize(String expression) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                StringBuilder value = new StringBuilder("\"");
                i++;
                while (i < expression.length() && expression.charAt(i) != '"') {
                    if (expression.charAt(i) == '\\' && i + 1 < expression.length()) {
                        i++;
                    }
                    value.append(expression.charAt(i++));
                }
                if (i >= expression.length()) {
                    throw invalid("Unterminated string in the filter expression.");
                }
                i++;
                out.add(value.toString());
            } else if ("(){}[],:".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else if ("=!<>&|".indexOf(c) >= 0) {
                String two = i + 1 < expression.length() ? expression.substring(i, i + 2) : "";
                if (List.of("!=", "<=", ">=", "&&", "||").contains(two)) {
                    out.add(two);
                    i += 2;
                } else if (c == '&' || c == '|') {
                    throw invalid("Unexpected '" + c + "' in the filter expression.");
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            } else {
                int start = i;
                while (i < expression.length() && !Character.isWhitespace(expression.charAt(i))
                        && "(){}[],:=!<>&|\"".indexOf(expression.charAt(i)) < 0) {
                    i++;
                }
                out.add(expression.substring(start, i));
            }
        }
        return out;
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : null;
    }

    private boolean accept(String token) {
        if (token.equalsIgnoreCase(String.valueOf(peek()))) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!accept(token)) {
            throw invalid("Expected '" + token + "' in the filter expression.");
        }
    }

    private String next() {
        if (position >= tokens.size()) {
            throw invalid("The filter expression ends early.");
        }
        return tokens.get(position++);
    }

    // ---------- grammar ----------

    private Predicate<XRayTraces.Scope> or() {
        Predicate<XRayTraces.Scope> left = and();
        while (accept("OR") || accept("||")) {
            left = left.or(and());
        }
        return left;
    }

    private Predicate<XRayTraces.Scope> and() {
        Predicate<XRayTraces.Scope> left = unary();
        while (accept("AND") || accept("&&")) {
            left = left.and(unary());
        }
        return left;
    }

    private Predicate<XRayTraces.Scope> unary() {
        if (accept("NOT") || accept("!")) {
            return unary().negate();
        }
        if (accept("(")) {
            Predicate<XRayTraces.Scope> inner = or();
            expect(")");
            return inner;
        }
        return term();
    }

    private Predicate<XRayTraces.Scope> term() {
        String keyword = next();
        String lower = keyword.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "ok", "error", "fault", "throttle", "partial" -> {
                Predicate<XRayTraces.Scope> flag = scope -> scope.flag(lower);
                return comparison().map(c -> c.bool(flag)).orElse(flag);
            }
            case "service" -> {
                return scoped(serviceSelector());
            }
            case "edge" -> {
                expect("(");
                String source = string(next());
                expect(",");
                String destination = string(next());
                expect(")");
                return scoped(scope -> scope.edge(source, destination));
            }
            case "id" -> {
                XRayTraces.ServiceKey key = idArguments();
                return scope -> scope.service(key) != null;
            }
            case "group.name", "group.arn" -> {
                expect("=");
                String value = string(next());
                return groups.filterOf(lower, value);
            }
            default -> {
                if (lower.startsWith("annotation")) {
                    String key = annotationKey(keyword);
                    Comparison comparison = comparison().orElseThrow(() ->
                            invalid("An annotation needs a comparison."));
                    return scope -> scope.annotations(key).stream().anyMatch(comparison::test);
                }
                if (!XRayTraces.Scope.KEYWORDS.contains(lower)) {
                    throw invalid("Unknown keyword '" + keyword + "' in the filter expression.");
                }
                Comparison comparison = comparison().orElseThrow(() ->
                        invalid("The keyword " + keyword + " needs a comparison."));
                return scope -> scope.values(lower).stream().anyMatch(comparison::test);
            }
        }
    }

    /** {@code ("name")}, {@code (id(...))} or {@code ()}: the scope of the services it selects. */
    private Function<XRayTraces.Scope, XRayTraces.Scope> serviceSelector() {
        expect("(");
        if (accept(")")) {
            return XRayTraces.Scope::anyService;
        }
        XRayTraces.ServiceKey key;
        if (accept("id")) {
            key = idArguments();
        } else {
            key = new XRayTraces.ServiceKey(string(next()), null);
        }
        expect(")");
        return scope -> scope.service(key);
    }

    private XRayTraces.ServiceKey idArguments() {
        expect("(");
        String name = null;
        String type = null;
        while (!accept(")")) {
            String field = next().toLowerCase(Locale.ROOT);
            expect(":");
            String value = string(next());
            if ("name".equals(field)) {
                name = value;
            } else if ("type".equals(field)) {
                type = value;
            } else if (!"account.id".equals(field)) {
                throw invalid("id() takes name, type and account.id.");
            }
            accept(",");
        }
        return new XRayTraces.ServiceKey(name, type);
    }

    /** A selector, optionally followed by {@code { filter }} evaluated within what it selects. */
    private Predicate<XRayTraces.Scope> scoped(Function<XRayTraces.Scope, XRayTraces.Scope> select) {
        if (accept("{")) {
            Predicate<XRayTraces.Scope> inner = or();
            expect("}");
            return scope -> {
                XRayTraces.Scope selected = select.apply(scope);
                return selected != null && inner.test(selected);
            };
        }
        return scope -> select.apply(scope) != null;
    }

    private String annotationKey(String keyword) {
        if (keyword.contains(".")) {
            return keyword.substring(keyword.indexOf('.') + 1);
        }
        expect("[");
        String key = next();
        expect("]");
        return key.startsWith("\"") ? key.substring(1) : key;
    }

    private static String string(String token) {
        if (!token.startsWith("\"")) {
            throw invalid("Expected a quoted string in the filter expression, not " + token + ".");
        }
        return token.substring(1);
    }

    // ---------- comparisons ----------

    private record Comparison(String operator, Object value) {

        boolean test(Object actual) {
            if (actual == null) {
                return false;
            }
            if (value instanceof Double number) {
                double left;
                if (actual instanceof Number n) {
                    left = n.doubleValue();
                } else {
                    try {
                        left = Double.parseDouble(actual.toString());
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
                return switch (operator) {
                    case "=" -> left == number;
                    case "!=" -> left != number;
                    case "<" -> left < number;
                    case "<=" -> left <= number;
                    case ">" -> left > number;
                    case ">=" -> left >= number;
                    default -> false;
                };
            }
            if (value instanceof Boolean bool) {
                boolean equal = bool.equals(actual instanceof Boolean b ? b : Boolean.parseBoolean(actual.toString()));
                return "!=".equals(operator) != equal;
            }
            String left = actual.toString();
            String right = value.toString();
            return switch (operator) {
                case "=" -> left.equals(right);
                case "!=" -> !left.equals(right);
                case "contains" -> left.contains(right);
                case "beginswith" -> left.startsWith(right);
                case "endswith" -> left.endsWith(right);
                case "<" -> left.compareTo(right) < 0;
                case ">" -> left.compareTo(right) > 0;
                case "<=" -> left.compareTo(right) <= 0;
                case ">=" -> left.compareTo(right) >= 0;
                default -> false;
            };
        }

        Predicate<XRayTraces.Scope> bool(Predicate<XRayTraces.Scope> flag) {
            boolean wanted = Boolean.TRUE.equals(value);
            return "!=".equals(operator) ? scope -> flag.test(scope) != wanted : scope -> flag.test(scope) == wanted;
        }
    }

    private Optional<Comparison> comparison() {
        String operator = peek();
        if (operator == null) {
            return Optional.empty();
        }
        String lower = operator.toLowerCase(Locale.ROOT);
        if (!List.of("=", "!=", "<", "<=", ">", ">=", "contains", "beginswith", "endswith").contains(lower)) {
            return Optional.empty();
        }
        position++;
        String raw = next();
        Object value;
        if (raw.startsWith("\"")) {
            value = raw.substring(1);
        } else if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            value = Boolean.parseBoolean(raw);
        } else {
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw invalid("Invalid value " + raw + " in the filter expression.");
            }
        }
        return Optional.of(new Comparison(lower, value));
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", "Invalid filter expression: " + message, 400);
    }
}
