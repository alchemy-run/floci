package io.floci.aws.services.frauddetector;

import io.floci.aws.core.common.AwsException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Amazon Fraud Detector's rule language (DETECTORPL): variables ({@code $name}), lists ({@code @name}), string and
 * number literals, {@code null}, {@code true}/{@code false}, list literals ({@code [1, 2]}), the comparison operators
 * {@code == != > >= < <=}, {@code in} and {@code not in}, {@code and}, {@code or} and {@code !}, arithmetic
 * {@code + - * / %}, parentheses, {@code #} comments, and the functions {@code regex_match}, {@code lowercase},
 * {@code uppercase}, {@code getcurrentdatetime}, {@code isbefore}, {@code isafter} and {@code getepochmilliseconds}.
 * An {@code and} stops at its first false operand and an {@code or} at its first true one.
 */
final class FraudDetectorRules {

    /** What an expression reads while it is evaluated. */
    interface Context {
        Object variable(String name);

        Set<String> list(String name);
    }

    /** A parsed expression and the variables and lists it names. */
    record Expression(Node root, Set<String> variables, Set<String> lists) {

        boolean matches(Context context) {
            return truthy(root.eval(context));
        }

        /** The expression with each variable replaced by its value, as prediction metadata shows it. */
        String withValues(String source, Context context) {
            String out = source;
            List<String> longestFirst = new ArrayList<>(variables);
            longestFirst.sort(Comparator.comparingInt(String::length).reversed());
            for (String variable : longestFirst) {
                Object value = context.variable(variable);
                out = out.replace("$" + variable, value instanceof String s ? "\"" + s + "\"" : String.valueOf(value));
            }
            return out;
        }
    }

    interface Node {
        Object eval(Context context);
    }

    private final List<String> tokens;
    private final Set<String> variables = new LinkedHashSet<>();
    private final Set<String> lists = new LinkedHashSet<>();
    private int position;

    private FraudDetectorRules(List<String> tokens) {
        this.tokens = tokens;
    }

    /** Parses an expression; a syntax error is a validation error, as CreateRule reports it. */
    static Expression parse(String source) {
        FraudDetectorRules parser = new FraudDetectorRules(tokenize(source));
        if (parser.tokens.isEmpty()) {
            throw invalid("The rule expression is empty.");
        }
        Node root = parser.or();
        if (parser.position != parser.tokens.size()) {
            throw invalid("Unexpected '" + parser.tokens.get(parser.position) + "' in the rule expression.");
        }
        return new Expression(root, parser.variables, parser.lists);
    }

    // ---------- tokens ----------

    private static List<String> tokenize(String source) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '"') {
                StringBuilder value = new StringBuilder("\"");
                i++;
                while (i < source.length() && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        i++;
                    }
                    value.append(source.charAt(i++));
                }
                if (i >= source.length()) {
                    throw invalid("Unterminated string in the rule expression.");
                }
                i++;
                out.add(value.toString());
            } else if ("()[],+-*/%".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else if ("=!<>".indexOf(c) >= 0) {
                if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                    out.add(source.substring(i, i + 2));
                    i += 2;
                } else if (c == '=') {
                    throw invalid("Use == to compare in the rule expression.");
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            } else if (c == '$' || c == '@' || Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                int start = i;
                i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_' || source.charAt(i) == '.')) {
                    i++;
                }
                out.add(source.substring(start, i));
            } else {
                throw invalid("Unexpected character '" + c + "' in the rule expression.");
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
            throw invalid("Expected '" + token + "' in the rule expression.");
        }
    }

    private String next() {
        if (position >= tokens.size()) {
            throw invalid("The rule expression ends early.");
        }
        return tokens.get(position++);
    }

    // ---------- grammar ----------

    private Node or() {
        Node left = and();
        while (accept("or")) {
            Node l = left;
            Node r = and();
            left = c -> truthy(l.eval(c)) || truthy(r.eval(c));
        }
        return left;
    }

    private Node and() {
        Node left = not();
        while (accept("and")) {
            Node l = left;
            Node r = not();
            left = c -> truthy(l.eval(c)) && truthy(r.eval(c));
        }
        return left;
    }

    private Node not() {
        if (accept("!")) {
            Node inner = not();
            return c -> !truthy(inner.eval(c));
        }
        return comparison();
    }

    private Node comparison() {
        Node left = additive();
        String op = peek();
        if (op == null) {
            return left;
        }
        if (List.of("==", "!=", ">", ">=", "<", "<=").contains(op)) {
            position++;
            Node right = additive();
            return c -> compare(op, left.eval(c), right.eval(c));
        }
        if ("in".equalsIgnoreCase(op)) {
            position++;
            Node set = collection();
            return c -> contains(set.eval(c), left.eval(c));
        }
        if ("not".equalsIgnoreCase(op) && "in".equalsIgnoreCase(position + 1 < tokens.size()
                ? tokens.get(position + 1) : null)) {
            position += 2;
            Node set = collection();
            return c -> !contains(set.eval(c), left.eval(c));
        }
        return left;
    }

    /** The right side of {@code in}: a list literal or a list. */
    private Node collection() {
        String token = peek();
        if (token != null && token.startsWith("@")) {
            position++;
            String name = token.substring(1);
            lists.add(name);
            return c -> c.list(name);
        }
        expect("[");
        List<Node> items = new ArrayList<>();
        if (!accept("]")) {
            do {
                items.add(additive());
            } while (accept(","));
            expect("]");
        }
        return c -> {
            List<Object> values = new ArrayList<>();
            items.forEach(item -> values.add(item.eval(c)));
            return values;
        };
    }

    private Node additive() {
        Node left = multiplicative();
        while ("+".equals(peek()) || "-".equals(peek())) {
            String op = next();
            Node l = left;
            Node r = multiplicative();
            left = c -> arithmetic(op, l.eval(c), r.eval(c));
        }
        return left;
    }

    private Node multiplicative() {
        Node left = unary();
        while ("*".equals(peek()) || "/".equals(peek()) || "%".equals(peek())) {
            String op = next();
            Node l = left;
            Node r = unary();
            left = c -> arithmetic(op, l.eval(c), r.eval(c));
        }
        return left;
    }

    private Node unary() {
        if (accept("-")) {
            Node inner = unary();
            return c -> {
                Object value = inner.eval(c);
                return value instanceof Number n ? -n.doubleValue() : null;
            };
        }
        return primary();
    }

    private Node primary() {
        String token = next();
        if ("(".equals(token)) {
            Node inner = or();
            expect(")");
            return inner;
        }
        if (token.startsWith("\"")) {
            String value = token.substring(1);
            return c -> value;
        }
        if (token.startsWith("$")) {
            String name = token.substring(1);
            if (name.isEmpty()) {
                throw invalid("A variable needs a name after $.");
            }
            variables.add(name);
            return c -> c.variable(name);
        }
        if (token.startsWith("@")) {
            throw invalid("A list (" + token + ") can only follow in or not in.");
        }
        String lower = token.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "null" -> {
                return c -> null;
            }
            case "true", "false" -> {
                boolean value = Boolean.parseBoolean(lower);
                return c -> value;
            }
            default -> {
            }
        }
        if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '.') {
            double value;
            try {
                value = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw invalid("Invalid number " + token + " in the rule expression.");
            }
            return c -> value;
        }
        if ("(".equals(peek())) {
            return function(lower);
        }
        throw invalid("Unknown name '" + token + "' in the rule expression; variables start with $.");
    }

    private Node function(String name) {
        expect("(");
        List<Node> args = new ArrayList<>();
        if (!accept(")")) {
            do {
                args.add(or());
            } while (accept(","));
            expect(")");
        }
        int arity = switch (name) {
            case "getcurrentdatetime" -> 0;
            case "lowercase", "uppercase", "getepochmilliseconds" -> 1;
            case "regex_match", "isbefore", "isafter" -> 2;
            default -> throw invalid("Unknown function " + name + " in the rule expression.");
        };
        if (args.size() != arity) {
            throw invalid(name + " takes " + arity + " argument" + (arity == 1 ? "" : "s") + ".");
        }
        Pattern constant = null;
        if ("regex_match".equals(name)) {
            Object pattern = args.get(0).eval(new Context() {
                @Override
                public Object variable(String variable) {
                    return null;
                }

                @Override
                public Set<String> list(String list) {
                    return Set.of();
                }
            });
            if (pattern instanceof String text) {
                try {
                    constant = Pattern.compile(text);
                } catch (PatternSyntaxException e) {
                    throw invalid("Invalid regular expression " + text + ": " + e.getDescription());
                }
            }
        }
        Pattern compiled = constant;
        return switch (name) {
            case "getcurrentdatetime" -> c -> Instant.now().toString();
            case "lowercase" -> c -> mapString(args.get(0).eval(c), s -> s.toLowerCase(Locale.ROOT));
            case "uppercase" -> c -> mapString(args.get(0).eval(c), s -> s.toUpperCase(Locale.ROOT));
            case "getepochmilliseconds" -> c -> {
                Instant at = instant(args.get(0).eval(c));
                return at == null ? null : (double) at.toEpochMilli();
            };
            case "isbefore", "isafter" -> c -> {
                Instant a = instant(args.get(0).eval(c));
                Instant b = instant(args.get(1).eval(c));
                if (a == null || b == null) {
                    return null;
                }
                return "isbefore".equals(name) ? a.isBefore(b) : a.isAfter(b);
            };
            default -> c -> {
                Object value = args.get(1).eval(c);
                if (value == null) {
                    return false;
                }
                Pattern pattern = compiled != null ? compiled : Pattern.compile(String.valueOf(args.get(0).eval(c)));
                return pattern.matcher(stringOf(value)).matches();
            };
        };
    }

    // ---------- semantics ----------

    static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return false;
    }

    private static Object mapString(Object value, Function<String, String> f) {
        return value == null ? null : f.apply(stringOf(value));
    }

    private static String stringOf(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (value instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static Object arithmetic(String op, Object left, Object right) {
        Double a = number(left);
        Double b = number(right);
        if (a == null || b == null) {
            return null;
        }
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;
            default -> b == 0 ? null : a % b;
        };
    }

    /** Equality across types: numbers compare numerically, booleans and strings by value; null only equals null. */
    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        Double x = number(a);
        Double y = number(b);
        if ((a instanceof Number || b instanceof Number) && x != null && y != null) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b) && (a instanceof Boolean || "true".equalsIgnoreCase(stringOf(a))
                    || "false".equalsIgnoreCase(stringOf(a))) && (b instanceof Boolean
                    || "true".equalsIgnoreCase(stringOf(b)) || "false".equalsIgnoreCase(stringOf(b)));
        }
        return Objects.equals(stringOf(a), stringOf(b));
    }

    private static boolean compare(String op, Object left, Object right) {
        switch (op) {
            case "==" -> {
                return equal(left, right);
            }
            case "!=" -> {
                return !equal(left, right);
            }
            default -> {
            }
        }
        if (left == null || right == null) {
            return false;
        }
        Double a = number(left);
        Double b = number(right);
        int order;
        if (a != null && b != null) {
            order = Double.compare(a, b);
        } else {
            Instant x = instant(left);
            Instant y = instant(right);
            order = x != null && y != null ? x.compareTo(y) : stringOf(left).compareTo(stringOf(right));
        }
        return switch (op) {
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            case "<" -> order < 0;
            default -> order <= 0;
        };
    }

    private static boolean contains(Object collection, Object value) {
        if (collection instanceof Set<?> set) {
            return value != null && set.contains(stringOf(value));
        }
        if (collection instanceof List<?> items) {
            for (Object item : items) {
                if (equal(item, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
