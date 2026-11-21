package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IoT SiteWise's formula expression language for transforms and metrics: numbers, strings, the constants {@code pi},
 * {@code e}, {@code true}, {@code false} and {@code none}; {@code + - * / % ^}, comparisons, {@code and or not !},
 * string indexing and slicing; the common, comparison, conditional ({@code if}, {@code elif}), string ({@code len},
 * {@code find}, {@code contains}, {@code upper}, {@code lower}, {@code jp}, {@code join}, {@code format},
 * {@code f'...'}), date and time ({@code now}, {@code timestamp}, {@code mktime}, {@code localtime} and its parts),
 * and, in metrics, aggregation ({@code avg}, {@code sum}, {@code min}, {@code max}, {@code count}, {@code stdev}) and
 * temporal ({@code first}, {@code last}, {@code earliest}, {@code latest}, {@code statetime},
 * {@code TimeWeightedAvg}, {@code TimeWeightedStDev}) functions, and {@code pretrigger} in transforms. Function names
 * are case insensitive and every function can be called with uniform function call syntax ({@code x.log()}).
 * Evaluation yields a Double, a String, or {@link #NONE} (no output).
 */
final class SiteWiseFormula {

    /** The {@code none} constant: the expression produces no data point. */
    static final Object NONE = new Object() {
        @Override
        public String toString() {
            return "none";
        }
    };

    private static final Set<String> AGGREGATIONS = Set.of("avg", "sum", "min", "max", "count", "stdev");
    private static final Set<String> TEMPORAL = Set.of("first", "last", "earliest", "latest", "statetime",
            "timeweightedavg", "timeweightedstdev");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A data point: epoch seconds (fractional) and value. */
    record Point(double time, Object value) {
    }

    /** What an expression reads its variables from. */
    interface Context {
        /** The variable's current value (transform) or its value at the end of the window (metric). */
        Object value(String variable);

        /** The variable's timestamp in epoch seconds, for {@code timestamp(x)}. */
        double timestamp(String variable);

        /** The variable's value before the update that triggered this computation, for {@code pretrigger(x)}. */
        default Point pretrigger(String variable) {
            return null;
        }

        /** The time of the triggering input (transforms) or the end of the window (metrics). */
        double now();

        /** The variable's GOOD points in {@code (start, end]}, metrics only; for a hierarchy, every child's. */
        default List<Point> window(String variable, double start, double end) {
            throw new IllegalArgumentException("aggregation functions are allowed only in metrics");
        }

        /** The variable's last GOOD point at or before {@code time}, metrics only. */
        default Point before(String variable, double time) {
            throw new IllegalArgumentException("temporal functions are allowed only in metrics");
        }

        /** The metric window: its start and end in epoch seconds. */
        default double[] bounds() {
            return null;
        }
    }

    /** A parsed expression. */
    abstract static class Node {
        abstract Object eval(Context context);

        /** The variables this expression reads. */
        void variables(Set<String> into) {
        }
    }

    private final String text;
    private int at;

    private SiteWiseFormula(String text) {
        this.text = text;
    }

    /** Parses an expression; a syntax error is an IllegalArgumentException. */
    static Node parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("the expression is empty");
        }
        SiteWiseFormula parser = new SiteWiseFormula(expression);
        Node node = parser.or();
        parser.space();
        if (parser.at < expression.length()) {
            throw parser.error("unexpected '" + expression.charAt(parser.at) + "'");
        }
        return node;
    }

    static Set<String> variables(Node node) {
        Set<String> names = new LinkedHashSet<>();
        node.variables(names);
        return names;
    }

    /** The names of the functions an expression calls, lowercased. */
    static Set<String> functions(String expression) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                .matcher(expression);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    static boolean usesAggregation(String expression) {
        Set<String> functions = functions(expression);
        return functions.stream().anyMatch(name -> AGGREGATIONS.contains(name) || TEMPORAL.contains(name));
    }

    // ---------------------------------------------------------------- grammar

    private Node or() {
        Node left = and();
        while (keyword("or")) {
            Node l = left;
            Node r = and();
            left = new Binary(l, r, (a, context) -> truthy(a) || truthy(r.eval(context)) ? 1.0 : 0.0);
        }
        return left;
    }

    private Node and() {
        Node left = not();
        while (keyword("and")) {
            Node l = left;
            Node r = not();
            left = new Binary(l, r, (a, context) -> truthy(a) && truthy(r.eval(context)) ? 1.0 : 0.0);
        }
        return left;
    }

    private Node not() {
        space();
        if (keyword("not") || (peek("!") && !peek("!="))) {
            if (text.charAt(at) == '!') {
                at++;
            }
            Node inner = not();
            return new Unary(inner, value -> truthy(value) ? 0.0 : 1.0);
        }
        return comparison();
    }

    private Node comparison() {
        Node left = additive();
        for (String operator : List.of("<=", ">=", "==", "!=", "<", ">")) {
            if (take(operator)) {
                Node right = additive();
                String name = switch (operator) {
                    case "<=" -> "lte";
                    case ">=" -> "gte";
                    case "==" -> "eq";
                    case "!=" -> "neq";
                    case "<" -> "lt";
                    default -> "gt";
                };
                return new Call(name, List.of(left, right));
            }
        }
        return left;
    }

    private Node additive() {
        Node left = multiplicative();
        while (true) {
            if (take("+")) {
                Node l = left;
                Node r = multiplicative();
                left = new Binary(l, r, null) {
                    @Override
                    Object eval(Context context) {
                        Object a = l.eval(context);
                        Object b = r.eval(context);
                        if (a == NONE || b == NONE) {
                            return NONE;
                        }
                        if (a instanceof String || b instanceof String) {
                            String joined = string(a) + string(b);
                            return joined.length() > 1024 ? NONE : joined;
                        }
                        return number(a) + number(b);
                    }
                };
            } else if (take("-")) {
                left = arithmetic(left, multiplicative(), (a, b) -> a - b);
            } else {
                return left;
            }
        }
    }

    private Node multiplicative() {
        Node left = power();
        while (true) {
            if (take("*")) {
                left = arithmetic(left, power(), (a, b) -> a * b);
            } else if (take("/")) {
                left = arithmetic(left, power(), (a, b) -> a / b);
            } else if (take("%")) {
                // The remainder takes the sign of the left operand, as Java's % does.
                left = arithmetic(left, power(), (a, b) -> a % b);
            } else {
                return left;
            }
        }
    }

    private Node power() {
        Node base = unary();
        if (take("^")) {
            return arithmetic(base, power(), Math::pow);
        }
        return base;
    }

    private Node unary() {
        space();
        if (peek("-")) {
            at++;
            Node inner = unary();
            return new Unary(inner, value -> value == NONE ? NONE : -number(value));
        }
        if (peek("+")) {
            at++;
            return unary();
        }
        return postfix(primary());
    }

    /** Uniform function call syntax ({@code x.f(y)}) and indexing or slicing ({@code s[i]}, {@code s[a:b:c]}). */
    private Node postfix(Node node) {
        while (true) {
            space();
            if (peek(".") && at + 1 < text.length() && Character.isLetter(text.charAt(at + 1))) {
                at++;
                String name = word();
                List<Node> arguments = new ArrayList<>();
                arguments.add(node);
                if (take("(")) {
                    if (!take(")")) {
                        arguments.add(or());
                        while (take(",")) {
                            arguments.add(or());
                        }
                        expect(")");
                    }
                }
                node = call(name, arguments);
            } else if (take("[")) {
                Node target = node;
                List<Node> parts = new ArrayList<>();
                boolean slice = false;
                for (int part = 0; part < 3; part++) {
                    space();
                    parts.add(peek(":") || peek("]") ? null : or());
                    if (take(":")) {
                        slice = true;
                    } else {
                        break;
                    }
                }
                expect("]");
                boolean isSlice = slice;
                node = new Node() {
                    @Override
                    Object eval(Context context) {
                        Object value = target.eval(context);
                        if (value == NONE) {
                            return NONE;
                        }
                        String s = string(value);
                        Integer[] indexes = new Integer[3];
                        for (int i = 0; i < parts.size(); i++) {
                            Object index = parts.get(i) == null ? NONE : parts.get(i).eval(context);
                            indexes[i] = index == NONE ? null : (int) number(index);
                        }
                        return isSlice ? slice(s, indexes[0], indexes[1], indexes[2]) : index(s, indexes[0]);
                    }

                    @Override
                    void variables(Set<String> into) {
                        target.variables(into);
                    }
                };
            } else {
                return node;
            }
        }
    }

    private Node primary() {
        space();
        if (at >= text.length()) {
            throw error("the expression ends early");
        }
        char c = text.charAt(at);
        if (c == '(') {
            at++;
            Node inner = or();
            expect(")");
            return inner;
        }
        if ((c == 'f' || c == 'F') && at + 1 < text.length() && (text.charAt(at + 1) == '\''
                || text.charAt(at + 1) == '"')) {
            at++;
            return formatted(quoted());
        }
        if (c == '\'' || c == '"') {
            String value = quoted();
            return constant(value);
        }
        if (Character.isDigit(c) || (c == '.' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)))) {
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at++;
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                while (at < text.length() && Character.isDigit(text.charAt(at))) {
                    at++;
                }
            }
            return constant(Double.parseDouble(text.substring(start, at)));
        }
        if (Character.isLetter(c) || c == '_') {
            String name = word();
            space();
            if (peek("(")) {
                at++;
                List<Node> arguments = new ArrayList<>();
                if (!take(")")) {
                    // format(expression: "format") is format("format", expression).
                    Node first = or();
                    if (take(":")) {
                        Node format = or();
                        expect(")");
                        return call(name, List.of(format, first));
                    }
                    arguments.add(first);
                    while (take(",")) {
                        arguments.add(or());
                    }
                    expect(")");
                }
                return call(name, arguments);
            }
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "pi" -> constant(Math.PI);
                case "e" -> constant(Math.E);
                case "true" -> constant(1.0);
                case "false" -> constant(0.0);
                case "none" -> constant(NONE);
                default -> new Variable(name);
            };
        }
        throw error("unexpected '" + c + "'");
    }

    /** An f-string: literal text with {@code {expression}} and {@code {expression: "format"}} parts. */
    private Node formatted(String template) {
        List<Node> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int depth = 1;
                int end = i + 1;
                while (end < template.length() && depth > 0) {
                    if (template.charAt(end) == '{') {
                        depth++;
                    } else if (template.charAt(end) == '}') {
                        depth--;
                    }
                    end++;
                }
                if (depth != 0) {
                    throw error("the f-string's { isn't closed");
                }
                if (!literal.isEmpty()) {
                    parts.add(constant(literal.toString()));
                    literal.setLength(0);
                }
                parts.add(parse(template.substring(i + 1, end - 1).contains(":")
                        ? "format(" + template.substring(i + 1, end - 1) + ")"
                        : template.substring(i + 1, end - 1)));
                i = end;
            } else {
                literal.append(c);
                i++;
            }
        }
        if (!literal.isEmpty()) {
            parts.add(constant(literal.toString()));
        }
        List<Node> arguments = new ArrayList<>();
        arguments.add(constant(""));
        arguments.addAll(parts);
        return call("join", arguments);
    }

    // ---------------------------------------------------------------- nodes

    private static Node constant(Object value) {
        return new Node() {
            @Override
            Object eval(Context context) {
                return value;
            }
        };
    }

    private static final class Variable extends Node {
        private final String name;

        Variable(String name) {
            this.name = name;
        }

        @Override
        Object eval(Context context) {
            Object value = context.value(name);
            return value == null ? NONE : value;
        }

        @Override
        void variables(Set<String> into) {
            into.add(name);
        }
    }

    private static class Unary extends Node {
        private final Node inner;
        private final Function<Object, Object> operation;

        Unary(Node inner, Function<Object, Object> operation) {
            this.inner = inner;
            this.operation = operation;
        }

        @Override
        Object eval(Context context) {
            return operation.apply(inner.eval(context));
        }

        @Override
        void variables(Set<String> into) {
            inner.variables(into);
        }
    }

    @FunctionalInterface
    private interface Combiner {
        Object apply(Object left, Context context);
    }

    private static class Binary extends Node {
        final Node left;
        final Node right;
        private final Combiner combiner;

        Binary(Node left, Node right, Combiner combiner) {
            this.left = left;
            this.right = right;
            this.combiner = combiner;
        }

        @Override
        Object eval(Context context) {
            return combiner.apply(left.eval(context), context);
        }

        @Override
        void variables(Set<String> into) {
            left.variables(into);
            right.variables(into);
        }
    }

    private static Node arithmetic(Node left, Node right, DoubleBinaryOperator operation) {
        return new Binary(left, right, null) {
            @Override
            Object eval(Context context) {
                Object a = left.eval(context);
                Object b = right.eval(context);
                if (a == NONE || b == NONE) {
                    return NONE;
                }
                if (a instanceof String || b instanceof String) {
                    throw new ArithmeticException("this operator takes numbers");
                }
                return operation.applyAsDouble(number(a), number(b));
            }
        };
    }

    private Node call(String name, List<Node> arguments) {
        return new Call(name.toLowerCase(Locale.ROOT), arguments);
    }

    /** A function call. Aggregation and temporal functions evaluate their argument over the metric's window. */
    private static final class Call extends Node {
        private final String name;
        private final List<Node> arguments;

        Call(String name, List<Node> arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        @Override
        void variables(Set<String> into) {
            arguments.forEach(argument -> argument.variables(into));
        }

        @Override
        Object eval(Context context) {
            if (AGGREGATIONS.contains(name)) {
                return aggregate(context);
            }
            if (TEMPORAL.contains(name)) {
                return temporal(context);
            }
            switch (name) {
                case "if" -> {
                    Object condition = arguments.get(0).eval(context);
                    if (condition == NONE) {
                        return NONE;
                    }
                    if (truthy(condition)) {
                        return arguments.get(1).eval(context);
                    }
                    return arguments.size() > 2 ? arguments.get(2).eval(context) : Unmatched.INSTANCE;
                }
                case "elif" -> {
                    Object previous = arguments.get(0).eval(context);
                    if (previous != Unmatched.INSTANCE) {
                        return previous;
                    }
                    Object condition = arguments.get(1).eval(context);
                    if (truthy(condition)) {
                        return arguments.get(2).eval(context);
                    }
                    return arguments.size() > 3 ? arguments.get(3).eval(context) : Unmatched.INSTANCE;
                }
                case "timestamp" -> {
                    if (arguments.isEmpty()) {
                        return context.now();
                    }
                    Node argument = arguments.get(0);
                    if (argument instanceof Call call && "pretrigger".equals(call.name)) {
                        Point point = context.pretrigger(variableOf(call.arguments.get(0)));
                        return point == null ? NONE : point.time();
                    }
                    return context.timestamp(variableOf(argument));
                }
                case "pretrigger" -> {
                    Point point = context.pretrigger(variableOf(arguments.get(0)));
                    return point == null ? NONE : point.value();
                }
                default -> {
                }
            }
            List<Object> values = new ArrayList<>();
            for (Node argument : arguments) {
                Object value = argument.eval(context);
                if (value == NONE || value == Unmatched.INSTANCE) {
                    return NONE;
                }
                values.add(value);
            }
            return apply(name, values, context);
        }

        /** Each argument's values over the window: one series per argument, all pooled. */
        private List<Object> pooled(Context context) {
            double[] bounds = context.bounds();
            if (bounds == null) {
                throw new IllegalArgumentException(name + " is allowed only in metrics");
            }
            List<Object> pooled = new ArrayList<>();
            for (Node argument : arguments) {
                if (argument instanceof Call call && (AGGREGATIONS.contains(call.name) || TEMPORAL.contains(call.name))) {
                    Object value = call.eval(context);
                    if (value != NONE) {
                        pooled.add(value);
                    }
                    continue;
                }
                Set<String> names = SiteWiseFormula.variables(argument);
                if (names.isEmpty()) {
                    pooled.add(argument.eval(context));
                    continue;
                }
                if (names.size() > 1) {
                    throw new IllegalArgumentException("each argument of " + name + " can use only one variable");
                }
                String variable = names.iterator().next();
                for (Point point : context.window(variable, bounds[0], bounds[1])) {
                    Object value = argument.eval(single(context, variable, point));
                    if (value != NONE) {
                        pooled.add(value);
                    }
                }
            }
            return pooled;
        }

        private Object aggregate(Context context) {
            List<Object> values = pooled(context);
            if ("count".equals(name)) {
                return (double) values.size();
            }
            if (values.isEmpty()) {
                return NONE;
            }
            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Object value : values) {
                double number = number(value);
                sum += number;
                min = Math.min(min, number);
                max = Math.max(max, number);
            }
            double mean = sum / values.size();
            return switch (name) {
                case "avg" -> mean;
                case "sum" -> sum;
                case "min" -> min;
                case "max" -> max;
                default -> {
                    double squares = 0;
                    for (Object value : values) {
                        squares += Math.pow(number(value) - mean, 2);
                    }
                    yield values.size() < 2 ? 0.0 : Math.sqrt(squares / (values.size() - 1));
                }
            };
        }

        private Object temporal(Context context) {
            double[] bounds = context.bounds();
            if (bounds == null) {
                throw new IllegalArgumentException(name + " is allowed only in metrics");
            }
            Node argument = arguments.get(0);
            Set<String> names = SiteWiseFormula.variables(argument);
            if (names.size() != 1) {
                throw new IllegalArgumentException(name + " takes one variable");
            }
            String variable = names.iterator().next();
            List<Point> window = context.window(variable, bounds[0], bounds[1]);
            switch (name) {
                case "first", "last" -> {
                    if (window.isEmpty()) {
                        return NONE;
                    }
                    Point point = "first".equals(name) ? window.get(0) : window.get(window.size() - 1);
                    return argument.eval(single(context, variable, point));
                }
                case "earliest", "latest" -> {
                    Point point = context.before(variable, "earliest".equals(name) ? bounds[0] : bounds[1]);
                    return point == null ? NONE : argument.eval(single(context, variable, point));
                }
                case "statetime" -> {
                    Point start = context.before(variable, bounds[0]);
                    if (start == null && window.isEmpty()) {
                        return NONE;
                    }
                    double positive = 0;
                    double from = bounds[0];
                    Object current = start == null ? null : argument.eval(single(context, variable, start));
                    for (Point point : window) {
                        if (current != null && current != NONE && number(current) > 0) {
                            positive += point.time() - from;
                        }
                        from = point.time();
                        current = argument.eval(single(context, variable, point));
                    }
                    if (current != null && current != NONE && number(current) > 0) {
                        positive += bounds[1] - from;
                    }
                    return positive;
                }
                default -> {
                    boolean linear = arguments.size() > 1 && "linear".equalsIgnoreCase(
                            string(arguments.get(1).eval(context)));
                    boolean population = arguments.size() > 1 && "p".equalsIgnoreCase(
                            string(arguments.get(1).eval(context)));
                    return timeWeighted(context, argument, variable, window, bounds, linear, population,
                            "timeweightedstdev".equals(name));
                }
            }
        }

        /**
         * A time-weighted average or standard deviation over the window: each value weighted by how long it held
         * (carried forward, or interpolated linearly to the next point). A window with an interval before any
         * known point produces no value.
         */
        private Object timeWeighted(Context context, Node argument, String variable, List<Point> window,
                                    double[] bounds, boolean linear, boolean population, boolean deviation) {
            Point start = context.before(variable, bounds[0]);
            if (start == null) {
                return NONE;
            }
            List<double[]> segments = new ArrayList<>();
            double from = bounds[0];
            double value = number(argument.eval(single(context, variable, start)));
            double startTime = start.time();
            List<Point> all = new ArrayList<>(window);
            for (int i = 0; i <= all.size(); i++) {
                double to = i < all.size() ? all.get(i).time() : bounds[1];
                double next = i < all.size() ? number(argument.eval(single(context, variable, all.get(i)))) : value;
                double weight = to - from;
                if (weight > 0) {
                    double segmentValue = value;
                    if (linear && i < all.size() && to > startTime) {
                        double mid = (from + to) / 2;
                        segmentValue = value + (next - value) * (mid - startTime) / (to - startTime);
                    }
                    segments.add(new double[] {segmentValue, weight});
                }
                if (i < all.size()) {
                    from = to;
                    value = next;
                    startTime = to;
                }
            }
            double total = 0;
            double weighted = 0;
            for (double[] segment : segments) {
                total += segment[1];
                weighted += segment[0] * segment[1];
            }
            if (total <= 0) {
                return NONE;
            }
            double mean = weighted / total;
            if (!deviation) {
                return mean;
            }
            double squares = 0;
            for (double[] segment : segments) {
                squares += segment[1] * Math.pow(segment[0] - mean, 2);
            }
            double denominator = population ? total : total - 1;
            return denominator <= 0 ? 0.0 : Math.sqrt(squares / denominator);
        }
    }

    /** The result of an {@code if} with no else whose condition was false, for a following {@code elif}. */
    private enum Unmatched {
        INSTANCE
    }

    private static String variableOf(Node node) {
        Set<String> names = SiteWiseFormula.variables(node);
        if (names.size() != 1) {
            throw new IllegalArgumentException("the function takes one variable");
        }
        return names.iterator().next();
    }

    /** The context with one variable bound to a point. */
    private static Context single(Context outer, String variable, Point point) {
        return new Context() {
            @Override
            public Object value(String name) {
                return name.equals(variable) ? point.value() : outer.value(name);
            }

            @Override
            public double timestamp(String name) {
                return name.equals(variable) ? point.time() : outer.timestamp(name);
            }

            @Override
            public double now() {
                return outer.now();
            }

            @Override
            public List<Point> window(String name, double start, double end) {
                return outer.window(name, start, end);
            }

            @Override
            public Point before(String name, double time) {
                return outer.before(name, time);
            }

            @Override
            public double[] bounds() {
                return outer.bounds();
            }
        };
    }

    private static Object apply(String name, List<Object> values, Context context) {
        switch (name) {
            case "abs", "acos", "asin", "atan", "cbrt", "ceil", "cos", "cosh", "cot", "exp", "expm1", "floor", "log",
                 "log10", "log1p", "log2", "signum", "sin", "sinh", "sqrt", "tan", "tanh" -> {
                double x = number(values.get(0));
                return switch (name) {
                    case "abs" -> Math.abs(x);
                    case "acos" -> Math.acos(x);
                    case "asin" -> Math.asin(x);
                    case "atan" -> Math.atan(x);
                    case "cbrt" -> Math.cbrt(x);
                    case "ceil" -> Math.ceil(x);
                    case "cos" -> Math.cos(x);
                    case "cosh" -> Math.cosh(x);
                    case "cot" -> 1 / Math.tan(x);
                    case "exp" -> Math.exp(x);
                    case "expm1" -> Math.expm1(x);
                    case "floor" -> Math.floor(x);
                    case "log" -> Math.log(x);
                    case "log10" -> Math.log10(x);
                    case "log1p" -> Math.log1p(x);
                    case "log2" -> Math.log(x) / Math.log(2);
                    case "signum" -> Math.signum(x);
                    case "sin" -> Math.sin(x);
                    case "sinh" -> Math.sinh(x);
                    case "sqrt" -> Math.sqrt(x);
                    case "tan" -> Math.tan(x);
                    default -> Math.tanh(x);
                };
            }
            case "pow" -> {
                return Math.pow(number(values.get(0)), number(values.get(1)));
            }
            case "gt", "gte", "lt", "lte", "eq", "neq" -> {
                Object a = values.get(0);
                Object b = values.get(1);
                if ((a instanceof String) != (b instanceof String)) {
                    return NONE;
                }
                int compared = a instanceof String sa ? Integer.signum(sa.compareTo((String) b))
                        : close(number(a), number(b)) ? 0 : Double.compare(number(a), number(b));
                boolean result = switch (name) {
                    case "gt" -> compared > 0;
                    case "gte" -> compared >= 0;
                    case "lt" -> compared < 0;
                    case "lte" -> compared <= 0;
                    case "eq" -> compared == 0;
                    default -> compared != 0;
                };
                return result ? 1.0 : 0.0;
            }
            case "isnan" -> {
                return values.get(0) instanceof String ? NONE : Double.isNaN(number(values.get(0))) ? 1.0 : 0.0;
            }
            case "len" -> {
                return (double) string(values.get(0)).length();
            }
            case "find" -> {
                return (double) string(values.get(0)).indexOf(string(values.get(1)));
            }
            case "contains" -> {
                return string(values.get(0)).contains(string(values.get(1))) ? 1.0 : 0.0;
            }
            case "upper" -> {
                return string(values.get(0)).toUpperCase(Locale.ROOT);
            }
            case "lower" -> {
                return string(values.get(0)).toLowerCase(Locale.ROOT);
            }
            case "jp" -> {
                return jsonPath(string(values.get(0)), string(values.get(1)));
            }
            case "join" -> {
                List<String> parts = new ArrayList<>();
                for (int i = 1; i < values.size(); i++) {
                    parts.add(string(values.get(i)));
                }
                return String.join(string(values.get(0)), parts);
            }
            case "format" -> {
                String format = string(values.get(0));
                Object argument = values.size() > 1 ? values.get(1) : null;
                String pattern = format.contains("%") ? format : "%" + format;
                Object formatted = argument instanceof Double d && (pattern.endsWith("d") || pattern.endsWith("x")
                        || pattern.endsWith("o")) ? (Object) d.longValue() : argument;
                return String.format(Locale.ROOT, pattern, formatted);
            }
            case "now" -> {
                return Instant.now().toEpochMilli() / 1000.0;
            }
            case "mktime" -> {
                return mktime(values);
            }
            case "localtime" -> {
                return localtime(values);
            }
            case "year", "mon", "mday", "wday", "yday", "hour", "minute", "sec" -> {
                ZonedDateTime time = values.get(0) instanceof String s ? ZonedDateTime.parse(s)
                        : Instant.ofEpochMilli((long) (number(values.get(0)) * 1000)).atZone(ZoneOffset.UTC);
                return (double) switch (name) {
                    case "year" -> time.getYear();
                    case "mon" -> time.getMonthValue();
                    case "mday" -> time.getDayOfMonth();
                    case "wday" -> time.getDayOfWeek().getValue() % 7;
                    case "yday" -> time.getDayOfYear();
                    case "hour" -> time.getHour();
                    case "minute" -> time.getMinute();
                    default -> time.get(ChronoField.SECOND_OF_MINUTE);
                };
            }
            default -> throw new IllegalArgumentException("unknown function " + name);
        }
    }

    private static Object mktime(List<Object> values) {
        String zone = "UTC";
        List<Double> numbers = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String s) {
                zone = s;
            } else {
                numbers.add(number(value));
            }
        }
        if (numbers.size() < 3) {
            throw new IllegalArgumentException("mktime needs a year, month and day of month");
        }
        double seconds = numbers.size() > 5 ? numbers.get(5) : 0;
        ZonedDateTime time = ZonedDateTime.of(numbers.get(0).intValue(), numbers.get(1).intValue(),
                numbers.get(2).intValue(), numbers.size() > 3 ? numbers.get(3).intValue() : 0,
                numbers.size() > 4 ? numbers.get(4).intValue() : 0, (int) seconds,
                (int) ((seconds - Math.floor(seconds)) * 1e9), zone(zone));
        return time.toInstant().toEpochMilli() / 1000.0;
    }

    private static Object localtime(List<Object> values) {
        double time = 0;
        String zone = "UTC";
        for (Object value : values) {
            if (value instanceof String s) {
                zone = s;
            } else {
                time = number(value);
            }
        }
        return Instant.ofEpochMilli((long) (time * 1000)).atZone(zone(zone)).toString();
    }

    private static final Map<String, String> ZONES = Map.ofEntries(Map.entry("EST", "-05:00"),
            Map.entry("HST", "-10:00"), Map.entry("MST", "-07:00"), Map.entry("ACT", "Australia/Darwin"),
            Map.entry("AET", "Australia/Sydney"), Map.entry("AGT", "America/Argentina/Buenos_Aires"),
            Map.entry("ART", "Africa/Cairo"), Map.entry("AST", "America/Anchorage"),
            Map.entry("BET", "America/Sao_Paulo"), Map.entry("BST", "Asia/Dhaka"), Map.entry("CAT", "Africa/Harare"),
            Map.entry("CET", "Europe/Paris"), Map.entry("CNT", "America/St_Johns"),
            Map.entry("CST", "America/Chicago"), Map.entry("CTT", "Asia/Shanghai"),
            Map.entry("EAT", "Africa/Addis_Ababa"), Map.entry("IET", "America/Indiana/Indianapolis"),
            Map.entry("IST", "Asia/Kolkata"), Map.entry("JST", "Asia/Tokyo"), Map.entry("MIT", "Pacific/Apia"),
            Map.entry("NET", "Asia/Yerevan"), Map.entry("NST", "Pacific/Auckland"),
            Map.entry("PLT", "Asia/Karachi"), Map.entry("PRT", "America/Puerto_Rico"),
            Map.entry("PST", "America/Los_Angeles"), Map.entry("SST", "Pacific/Guadalcanal"),
            Map.entry("VST", "Asia/Ho_Chi_Minh"));

    /** A time zone as SiteWise writes them: {@code Z}, {@code +2}, {@code GMT+2}, a region ID, an abbreviation. */
    static ZoneId zone(String text) {
        String zone = text.trim();
        if (ZONES.containsKey(zone)) {
            return ZoneId.of(ZONES.get(zone));
        }
        if (zone.matches("[+-]\\d{1,2}")) {
            return ZoneOffset.ofHours(Integer.parseInt(zone));
        }
        if (zone.matches("[A-Z]{3}[+-].*")) {
            String offset = zone.substring(3);
            return ZoneId.ofOffset(zone.substring(0, 3).equals("GMT") ? "GMT" : "UTC",
                    ZoneOffset.of(offset.matches("[+-]\\d{1,2}") ? String.format("%s%02d", offset.charAt(0),
                            Integer.parseInt(offset.substring(1))) : offset));
        }
        return ZoneId.of(zone);
    }

    private static Object jsonPath(String document, String path) {
        JsonNode node;
        try {
            node = JSON.readTree(document);
        } catch (Exception e) {
            return NONE;
        }
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException("a JSONPath starts with $");
        }
        String rest = path.substring(1);
        Matcher matcher = Pattern.compile("\\.([A-Za-z0-9_]+)|\\[(\\d+)\\]|\\['([^']*)'\\]")
                .matcher(rest);
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                throw new IllegalArgumentException("the JSONPath " + path + " isn't supported");
            }
            consumed = matcher.end();
            if (matcher.group(1) != null) {
                node = node.path(matcher.group(1));
            } else if (matcher.group(2) != null) {
                node = node.path(Integer.parseInt(matcher.group(2)));
            } else {
                node = node.path(matcher.group(3));
            }
        }
        if (consumed != rest.length()) {
            throw new IllegalArgumentException("the JSONPath " + path + " isn't supported");
        }
        if (node.isMissingNode() || node.isNull()) {
            return NONE;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? 1.0 : 0.0;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    // ---------------------------------------------------------------- values

    static boolean truthy(Object value) {
        if (value == null || value == NONE || value == Unmatched.INSTANCE) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        return number(value) != 0;
    }

    static double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        throw new ArithmeticException("the value " + value + " isn't a number");
    }

    static String string(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    private static boolean close(double a, double b) {
        return a == b || Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    private static String index(String s, Integer index) {
        int i = index == null ? 0 : index < 0 ? s.length() + index : index;
        if (i < 0 || i >= s.length()) {
            throw new IndexOutOfBoundsException("the index is outside the string");
        }
        return String.valueOf(s.charAt(i));
    }

    private static String slice(String s, Integer start, Integer end, Integer step) {
        int length = s.length();
        int by = step == null ? 1 : step;
        if (by == 0) {
            throw new IllegalArgumentException("a slice's step can't be 0");
        }
        int from = start == null ? (by > 0 ? 0 : length - 1) : start < 0 ? Math.max(length + start, by > 0 ? 0 : -1)
                : Math.min(start, by > 0 ? length : length - 1);
        int to = end == null ? (by > 0 ? length : -1) : end < 0 ? Math.max(length + end, by > 0 ? 0 : -1)
                : Math.min(end, length);
        StringBuilder out = new StringBuilder();
        for (int i = from; by > 0 ? i < to : i > to; i += by) {
            out.append(s.charAt(i));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- lexer

    private String word() {
        int start = at;
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
            at++;
        }
        return text.substring(start, at);
    }

    private String quoted() {
        char quote = text.charAt(at);
        int end = text.indexOf(quote, at + 1);
        if (end < 0) {
            throw error("the string isn't closed");
        }
        String value = text.substring(at + 1, end);
        at = end + 1;
        return value;
    }

    private boolean keyword(String word) {
        space();
        if (text.regionMatches(true, at, word, 0, word.length())
                && (at + word.length() >= text.length() || !Character.isLetterOrDigit(text.charAt(at + word.length())))
                && (at + word.length() >= text.length() || text.charAt(at + word.length()) != '(')) {
            at += word.length();
            return true;
        }
        return false;
    }

    private void expect(String symbol) {
        if (!take(symbol)) {
            throw error("expected " + symbol);
        }
    }

    private boolean take(String symbol) {
        space();
        if (text.startsWith(symbol, at)) {
            at += symbol.length();
            return true;
        }
        return false;
    }

    private boolean peek(String symbol) {
        return text.startsWith(symbol, at);
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + at);
    }
}
