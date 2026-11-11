package io.floci.aws.services.iotfleetwise;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IoT FleetWise's event expression language (condition language version 1): signals as
 * {@code $variable.`Vehicle.Speed`}, numbers, booleans and strings, {@code + - * /}, comparisons, {@code && || !},
 * the window functions ({@code last_window_avg} and the rest), {@code isNull}, and {@code custom_function}. An
 * expression compiles to the {@code ConditionNode} tree the Edge Agent evaluates, with signals by their IDs.
 */
final class IotFleetWiseExpressions {

    private static final Map<String, Integer> COMPARISONS = Map.of("<", 0, ">", 1, "<=", 2, ">=", 3, "==", 4,
            "!=", 5);
    private static final Map<String, Integer> WINDOWS = Map.of("last_window_min", 0, "last_window_max", 1,
            "last_window_avg", 2, "prev_last_window_min", 3, "prev_last_window_max", 4, "prev_last_window_avg", 5);
    private static final int AND = 6;
    private static final int OR = 7;
    private static final int NOT = 8;
    private static final int PLUS = 9;
    private static final int MINUS = 10;
    private static final int MULTIPLY = 11;
    private static final int DIVIDE = 12;

    /** A parsed expression and the signals it reads. */
    record Compiled(IotFleetWiseProto.Writer node, Set<String> signals) {
    }

    private final String text;
    private final Map<String, Integer> signalIds;
    private final Set<String> signals = new LinkedHashSet<>();
    private int at;

    private IotFleetWiseExpressions(String text, Map<String, Integer> signalIds) {
        this.text = text;
        this.signalIds = signalIds;
    }

    /** Parses the expression; a signal not in {@code signalIds} or a syntax error is a ValidationException. */
    static Compiled compile(String expression, Map<String, Integer> signalIds, String field) {
        IotFleetWiseExpressions parser = new IotFleetWiseExpressions(expression, signalIds);
        try {
            IotFleetWiseProto.Writer node = parser.or();
            parser.space();
            if (parser.at < expression.length()) {
                throw parser.error("unexpected " + expression.substring(parser.at));
            }
            return new Compiled(node, parser.signals);
        } catch (IllegalArgumentException e) {
            throw IotFleetWiseStore.field(field, "The expression isn't valid: " + e.getMessage());
        }
    }

    private IotFleetWiseProto.Writer or() {
        IotFleetWiseProto.Writer left = and();
        while (take("||")) {
            left = operator(left, and(), OR);
        }
        return left;
    }

    private IotFleetWiseProto.Writer and() {
        IotFleetWiseProto.Writer left = not();
        while (take("&&")) {
            left = operator(left, not(), AND);
        }
        return left;
    }

    private IotFleetWiseProto.Writer not() {
        space();
        if (peek("!") && !peek("!=")) {
            at++;
            return operator(not(), null, NOT);
        }
        return comparison();
    }

    private IotFleetWiseProto.Writer comparison() {
        IotFleetWiseProto.Writer left = sum();
        space();
        for (String symbol : List.of("<=", ">=", "==", "!=", "<", ">")) {
            if (take(symbol)) {
                return operator(left, sum(), COMPARISONS.get(symbol));
            }
        }
        return left;
    }

    private IotFleetWiseProto.Writer sum() {
        IotFleetWiseProto.Writer left = product();
        while (true) {
            if (take("+")) {
                left = operator(left, product(), PLUS);
            } else if (take("-")) {
                left = operator(left, product(), MINUS);
            } else {
                return left;
            }
        }
    }

    private IotFleetWiseProto.Writer product() {
        IotFleetWiseProto.Writer left = unary();
        while (true) {
            if (take("*")) {
                left = operator(left, unary(), MULTIPLY);
            } else if (take("/")) {
                left = operator(left, unary(), DIVIDE);
            } else {
                return left;
            }
        }
    }

    private IotFleetWiseProto.Writer unary() {
        space();
        if (peek("-") && at + 1 < text.length() && !Character.isDigit(text.charAt(at + 1))) {
            at++;
            return operator(number(0), unary(), MINUS);
        }
        return primary();
    }

    private IotFleetWiseProto.Writer primary() {
        space();
        if (at >= text.length()) {
            throw error("the expression ends early");
        }
        char c = text.charAt(at);
        if (c == '(') {
            at++;
            IotFleetWiseProto.Writer inner = or();
            expect(")");
            return inner;
        }
        if (c == '$') {
            return signalNode(signal());
        }
        if (c == '\'' || c == '"') {
            return new IotFleetWiseProto.Writer().string(7, quoted());
        }
        if (Character.isDigit(c) || c == '-' || c == '.') {
            int start = at;
            at++;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.'
                    || text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
            }
            try {
                return number(Double.parseDouble(text.substring(start, at)));
            } catch (NumberFormatException e) {
                throw error("the number " + text.substring(start, at) + " isn't valid");
            }
        }
        String word = word();
        if ("true".equals(word) || "false".equals(word)) {
            return new IotFleetWiseProto.Writer().boolAlways(5, "true".equals(word));
        }
        if (WINDOWS.containsKey(word)) {
            expect("(");
            String name = signal();
            expect(")");
            IotFleetWiseProto.Writer window = new IotFleetWiseProto.Writer().uintAlways(1, signalIds.get(name))
                    .uint(2, WINDOWS.get(word));
            return new IotFleetWiseProto.Writer().message(2, new IotFleetWiseProto.Writer().message(1, window));
        }
        if ("isNull".equals(word)) {
            expect("(");
            IotFleetWiseProto.Writer expression = or();
            expect(")");
            return new IotFleetWiseProto.Writer().message(2, new IotFleetWiseProto.Writer()
                    .message(4, new IotFleetWiseProto.Writer().message(1, expression)));
        }
        if ("custom_function".equals(word)) {
            expect("(");
            space();
            String name = quoted();
            IotFleetWiseProto.Writer custom = new IotFleetWiseProto.Writer().string(1, name);
            while (take(",")) {
                custom.message(2, or());
            }
            expect(")");
            return new IotFleetWiseProto.Writer().message(2, new IotFleetWiseProto.Writer().message(3, custom));
        }
        throw error(word.isEmpty() ? "unexpected " + c : "unknown function or name " + word);
    }

    private String signal() {
        space();
        String prefix = "$variable.";
        if (!text.startsWith(prefix, at)) {
            throw error("a signal is written $variable.`Name`");
        }
        at += prefix.length();
        String name;
        if (at < text.length() && text.charAt(at) == '`') {
            int end = text.indexOf('`', at + 1);
            if (end < 0) {
                throw error("the signal name isn't closed with `");
            }
            name = text.substring(at + 1, end);
            at = end + 1;
        } else {
            int start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '.'
                    || text.charAt(at) == '_')) {
                at++;
            }
            name = text.substring(start, at);
        }
        if (!signalIds.containsKey(name)) {
            throw error("the signal " + name + " isn't in the signal catalog");
        }
        signals.add(name);
        return name;
    }

    private IotFleetWiseProto.Writer signalNode(String name) {
        return new IotFleetWiseProto.Writer().uintAlways(4, signalIds.get(name));
    }

    private static IotFleetWiseProto.Writer number(double value) {
        return new IotFleetWiseProto.Writer().dblAlways(3, value);
    }

    private static IotFleetWiseProto.Writer operator(IotFleetWiseProto.Writer left, IotFleetWiseProto.Writer right,
                                                     int operator) {
        IotFleetWiseProto.Writer node = new IotFleetWiseProto.Writer().message(1, left).message(2, right)
                .uint(3, operator);
        return new IotFleetWiseProto.Writer().message(1, node);
    }

    private String quoted() {
        space();
        char quote = text.charAt(at);
        if (quote != '\'' && quote != '"') {
            throw error("a string is quoted");
        }
        int end = text.indexOf(quote, at + 1);
        if (end < 0) {
            throw error("the string isn't closed");
        }
        String value = text.substring(at + 1, end);
        at = end + 1;
        return value;
    }

    private String word() {
        int start = at;
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
            at++;
        }
        return text.substring(start, at);
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

    /** The signals an expression names, for checks that don't compile it. */
    static List<String> names(String expression) {
        List<String> names = new ArrayList<>();
        int at = 0;
        while ((at = expression.indexOf("$variable.`", at)) >= 0) {
            int end = expression.indexOf('`', at + 11);
            if (end < 0) {
                break;
            }
            names.add(expression.substring(at + 11, end));
            at = end + 1;
        }
        return names;
    }
}
