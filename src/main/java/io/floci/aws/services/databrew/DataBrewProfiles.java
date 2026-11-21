package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataBrew profile jobs and data quality rulesets.
 * <p>
 * A profile reports the sample's row and duplicate counts and, per column, its inferred type, missing, distinct
 * and unique value counts, most common values, and length or numeric statistics. A ruleset's rules are check
 * expressions over aggregate statistics ({@code AGG(MISSING_VALUES_PERCENTAGE) <= :val1}) or over each row
 * ({@code :col1 > :val1}, {@code starts_with}, {@code ends_with}, {@code contains}, {@code matches},
 * {@code is_between ... and ...}, {@code in (...)}, combined with {@code and}, {@code or}, {@code not} and
 * parentheses). {@code :name} references come from the rule's {@code SubstitutionMap}, a backquoted value names a
 * column, and {@code ColumnSelectors} apply the rule to each selected column in turn. A row rule passes when the
 * share or count of passing rows meets its {@code Threshold} (100% of rows by default).
 */
final class DataBrewProfiles {

    private static final MathContext CONTEXT = new MathContext(16, RoundingMode.HALF_UP);

    private DataBrewProfiles() {
    }

    // ---------------------------------------------------------------- profile

    static ObjectNode profile(ObjectMapper mapper, DataBrewTable table, JsonNode configuration) {
        ObjectNode profile = mapper.createObjectNode();
        profile.put("sampleSize", table.rows.size());
        profile.put("rowsCount", table.rows.size());
        profile.put("columnsCount", table.columns.size());
        profile.put("duplicateRowsCount", duplicateRows(table));
        List<String> selected = selectColumns(table, configuration.path("ProfileColumns"));
        ArrayNode columns = profile.putArray("columns");
        for (String column : selected) {
            columns.add(columnProfile(mapper, column, table.values(column)));
        }
        return profile;
    }

    private static List<String> selectColumns(DataBrewTable table, JsonNode selectors) {
        if (!selectors.isArray() || selectors.isEmpty()) {
            return table.columns;
        }
        List<String> selected = new ArrayList<>();
        for (JsonNode selector : selectors) {
            if (selector.hasNonNull("Name") && table.columns.contains(selector.get("Name").asText())
                    && !selected.contains(selector.get("Name").asText())) {
                selected.add(selector.get("Name").asText());
            }
            if (selector.hasNonNull("Regex")) {
                Pattern pattern = Pattern.compile(selector.get("Regex").asText());
                for (String column : table.columns) {
                    if (pattern.matcher(column).matches() && !selected.contains(column)) {
                        selected.add(column);
                    }
                }
            }
        }
        return selected;
    }

    private static ObjectNode columnProfile(ObjectMapper mapper, String name, List<String> values) {
        ObjectNode column = mapper.createObjectNode();
        column.put("name", name);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int missing = 0;
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                missing++;
            } else {
                counts.merge(value, 1, Integer::sum);
            }
        }
        int present = values.size() - missing;
        List<BigDecimal> numbers = DataBrewRecipes.numbers(values);
        String type = present == 0 ? "string" : numbers.size() == present
                ? numbers.stream().allMatch(number -> number.stripTrailingZeros().scale() <= 0) ? "int" : "double"
                : counts.keySet().stream().allMatch(value -> Set.of("true", "false").contains(
                        value.toLowerCase(Locale.ROOT))) ? "boolean" : "string";
        column.put("type", type);
        column.put("rowsCount", values.size());
        column.put("missingValuesCount", missing);
        column.put("missingValuesPercentage", percent(missing, values.size()));
        column.put("validValuesCount", present);
        column.put("distinctValuesCount", counts.size());
        column.put("uniqueValuesCount", counts.values().stream().filter(count -> count == 1).count());
        ArrayNode common = column.putArray("mostCommonValues");
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                .forEach(entry -> common.addObject().put("value", entry.getKey()).put("count", entry.getValue())
                        .put("percentage", percent(entry.getValue(), values.size())));
        if ("int".equals(type) || "double".equals(type)) {
            BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mean = sum.divide(BigDecimal.valueOf(numbers.size()), CONTEXT);
            BigDecimal variance = BigDecimal.ZERO;
            for (BigDecimal number : numbers) {
                variance = variance.add(number.subtract(mean).pow(2));
            }
            variance = variance.divide(BigDecimal.valueOf(numbers.size()), CONTEXT);
            List<BigDecimal> sorted = new ArrayList<>(numbers);
            sorted.sort(BigDecimal::compareTo);
            column.put("min", DataBrewRecipes.format(sorted.get(0)));
            column.put("max", DataBrewRecipes.format(sorted.get(sorted.size() - 1)));
            column.put("sum", DataBrewRecipes.format(sum));
            column.put("mean", DataBrewRecipes.format(mean));
            column.put("median", DataBrewRecipes.format(percentile(sorted, 50)));
            column.put("percentile25", DataBrewRecipes.format(percentile(sorted, 25)));
            column.put("percentile75", DataBrewRecipes.format(percentile(sorted, 75)));
            column.put("variance", DataBrewRecipes.format(variance));
            column.put("standardDeviation", DataBrewRecipes.format(variance.sqrt(CONTEXT)));
            column.put("zerosCount", numbers.stream().filter(number -> number.signum() == 0).count());
            column.put("negativesCount", numbers.stream().filter(number -> number.signum() < 0).count());
        } else {
            int minLength = Integer.MAX_VALUE;
            int maxLength = 0;
            long total = 0;
            for (String value : counts.keySet()) {
                minLength = Math.min(minLength, value.length());
                maxLength = Math.max(maxLength, value.length());
            }
            for (String value : values) {
                total += value == null ? 0 : value.length();
            }
            column.put("minLength", present == 0 ? 0 : minLength);
            column.put("maxLength", maxLength);
            column.put("averageLength", present == 0 ? 0 : (double) total / present);
        }
        return column;
    }

    private static BigDecimal percentile(List<BigDecimal> sorted, int percentile) {
        double rank = percentile / 100.0 * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        BigDecimal fraction = BigDecimal.valueOf(rank - low);
        return sorted.get(low).add(sorted.get(high).subtract(sorted.get(low)).multiply(fraction), CONTEXT);
    }

    private static int duplicateRows(DataBrewTable table) {
        Set<List<String>> seen = new HashSet<>();
        int duplicates = 0;
        for (Map<String, String> row : table.rows) {
            if (!seen.add(new ArrayList<>(row.values()))) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 10000.0 / whole) / 100.0;
    }

    // ---------------------------------------------------------------- rulesets

    static ObjectNode validate(ObjectMapper mapper, DataBrewTable table, JsonNode ruleset) {
        ObjectNode result = mapper.createObjectNode();
        result.put("rulesetName", ruleset.path("Name").asText());
        result.put("rulesetArn", ruleset.path("ResourceArn").asText());
        ArrayNode rules = result.putArray("ruleResults");
        boolean passed = true;
        for (JsonNode rule : ruleset.path("Rules")) {
            if (rule.path("Disabled").asBoolean(false)) {
                continue;
            }
            ObjectNode ruleResult = rules.addObject();
            ruleResult.put("name", rule.path("Name").asText());
            String status;
            try {
                status = evaluate(mapper, table, rule, ruleResult) ? "SUCCEEDED" : "FAILED";
            } catch (IllegalArgumentException e) {
                status = "ERROR";
                ruleResult.put("message", e.getMessage());
            }
            ruleResult.put("status", status);
            passed &= "SUCCEEDED".equals(status);
        }
        result.put("status", passed ? "SUCCEEDED" : "FAILED");
        return result;
    }

    private static boolean evaluate(ObjectMapper mapper, DataBrewTable table, JsonNode rule, ObjectNode result) {
        Map<String, String> substitutions = new HashMap<>();
        rule.path("SubstitutionMap").properties().forEach(entry -> substitutions.put(entry.getKey(),
                entry.getValue().asText()));
        Node expression = new Parser(rule.path("CheckExpression").asText(), substitutions).parse();
        List<String> columns = selectColumns(table, rule.path("ColumnSelectors"));
        boolean scoped = rule.path("ColumnSelectors").isArray() && !rule.path("ColumnSelectors").isEmpty();
        if (scoped && columns.isEmpty()) {
            throw new IllegalArgumentException("No column matches the rule's column selectors.");
        }
        List<String> targets = scoped ? columns : Collections.singletonList((String) null);
        ArrayNode columnResults = result.putArray("columnResults");
        boolean passed = true;
        for (String column : targets) {
            boolean columnPassed;
            if (expression.aggregate()) {
                columnPassed = truthy(expression.value(new Scope(table, column, null)));
            } else {
                int passing = 0;
                for (Map<String, String> row : table.rows) {
                    if (truthy(expression.value(new Scope(table, column, row)))) {
                        passing++;
                    }
                }
                int failing = table.rows.size() - passing;
                columnPassed = threshold(rule.path("Threshold"), passing, table.rows.size());
                if (column == null) {
                    result.put("failedRowsCount", failing);
                }
                if (column != null) {
                    columnResults.addObject().put("name", column).put("failedRowsCount", failing)
                            .put("status", columnPassed ? "SUCCEEDED" : "FAILED");
                }
                passed &= columnPassed;
                continue;
            }
            if (column != null) {
                columnResults.addObject().put("name", column).put("status", columnPassed ? "SUCCEEDED" : "FAILED");
            }
            passed &= columnPassed;
        }
        if (columnResults.isEmpty()) {
            result.remove("columnResults");
        }
        return passed;
    }

    private static boolean threshold(JsonNode threshold, int passing, int total) {
        double required = threshold.path("Value").asDouble(100);
        boolean count = "COUNT".equals(threshold.path("Unit").asText("PERCENTAGE"));
        double actual = count ? passing : total == 0 ? 100 : passing * 100.0 / total;
        return switch (threshold.path("Type").asText("GREATER_THAN_OR_EQUAL")) {
            case "LESS_THAN_OR_EQUAL" -> actual <= required;
            case "GREATER_THAN" -> actual > required;
            case "LESS_THAN" -> actual < required;
            default -> actual >= required;
        };
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean bool && bool;
    }

    /** The table a rule runs over, the column a selector picked, and the row a row rule is checking. */
    private record Scope(DataBrewTable table, String column, Map<String, String> row) {}

    private interface Node {
        Object value(Scope scope);

        boolean aggregate();
    }

    /** Recursive-descent parser for DataBrew check expressions. */
    private static final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private final Map<String, String> substitutions;
        private int position;

        Parser(String expression, Map<String, String> substitutions) {
            this.substitutions = substitutions;
            Matcher matcher = Pattern.compile(
                    "\\s*(AGG\\([A-Z_]+\\)|:[A-Za-z0-9_]+|`[^`]*`|'[^']*'|\"[^\"]*\"|[<>!=]=|[<>()=,]|[A-Za-z_][A-Za-z0-9_]*"
                            + "|-?[0-9]+(?:\\.[0-9]+)?)").matcher(expression);
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                tokens.add(matcher.group(1));
                end = matcher.end();
            }
            if (!expression.substring(end).isBlank()) {
                throw new IllegalArgumentException("Cannot parse the check expression near '"
                        + expression.substring(end).strip() + "'.");
            }
        }

        Node parse() {
            Node node = or();
            if (position < tokens.size()) {
                throw new IllegalArgumentException("Unexpected '" + tokens.get(position) + "' in the check expression.");
            }
            return node;
        }

        private Node or() {
            Node left = and();
            while (keyword("or")) {
                Node right = and();
                Node first = left;
                left = logical(first, right, false);
            }
            return left;
        }

        private Node and() {
            Node left = unary();
            while (keyword("and")) {
                Node right = unary();
                Node first = left;
                left = logical(first, right, true);
            }
            return left;
        }

        private Node unary() {
            if (keyword("not")) {
                Node inner = unary();
                return node(scope -> !truthy(inner.value(scope)), inner.aggregate());
            }
            if (symbol("(")) {
                Node inner = or();
                expect(")");
                return inner;
            }
            return predicate();
        }

        private Node predicate() {
            Node left = operand();
            if (position >= tokens.size()) {
                return left;
            }
            String token = tokens.get(position).toLowerCase(Locale.ROOT);
            switch (token) {
                case "==", "=", "!=", "<", "<=", ">", ">=" -> {
                    position++;
                    Node right = operand();
                    return node(scope -> compare(left.value(scope), right.value(scope), token),
                            left.aggregate() || right.aggregate());
                }
                case "starts_with", "ends_with", "contains", "matches" -> {
                    position++;
                    Node right = operand();
                    return node(scope -> text(left.value(scope), right.value(scope), token),
                            left.aggregate() || right.aggregate());
                }
                case "is_between" -> {
                    position++;
                    Node low = operand();
                    if (!keyword("and")) {
                        throw new IllegalArgumentException("is_between needs 'and' between its bounds.");
                    }
                    Node high = operand();
                    return node(scope -> compare(left.value(scope), low.value(scope), ">=")
                                    && compare(left.value(scope), high.value(scope), "<="),
                            left.aggregate() || low.aggregate() || high.aggregate());
                }
                case "in" -> {
                    position++;
                    expect("(");
                    List<Node> options = new ArrayList<>();
                    do {
                        options.add(operand());
                    } while (symbol(","));
                    expect(")");
                    return node(scope -> {
                        Object value = left.value(scope);
                        for (Node option : options) {
                            if (compare(value, option.value(scope), "==")) {
                                return true;
                            }
                        }
                        return false;
                    }, left.aggregate());
                }
                case "is_missing", "is_not_missing" -> {
                    position++;
                    boolean wantMissing = "is_missing".equals(token);
                    return node(scope -> {
                        Object value = left.value(scope);
                        boolean missing = value == null || value.toString().isEmpty();
                        return missing == wantMissing;
                    }, left.aggregate());
                }
                default -> {
                    return left;
                }
            }
        }

        private Node operand() {
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("The check expression ends early.");
            }
            String token = tokens.get(position++);
            if (token.startsWith("AGG(")) {
                String statistic = token.substring(4, token.length() - 1);
                return node(scope -> aggregate(statistic, scope), true);
            }
            if (token.startsWith(":")) {
                String substituted = substitutions.get(token);
                if (substituted == null) {
                    return node(scope -> scope.column() == null ? null
                            : scope.row() == null ? null : scope.row().get(scope.column()), false);
                }
                return literal(substituted);
            }
            return literal(token);
        }

        private Node literal(String token) {
            if (token.startsWith("`") && token.endsWith("`") && token.length() >= 2) {
                String column = token.substring(1, token.length() - 1);
                return node(scope -> {
                    if (!scope.table().columns.contains(column)) {
                        throw new IllegalArgumentException("Column " + column + " does not exist.");
                    }
                    return scope.row() == null ? null : scope.row().get(column);
                }, false);
            }
            String text = (token.startsWith("'") || token.startsWith("\"")) && token.length() >= 2
                    ? token.substring(1, token.length() - 1) : token;
            return node(scope -> text, false);
        }

        private Node logical(Node left, Node right, boolean and) {
            return node(scope -> and ? truthy(left.value(scope)) && truthy(right.value(scope))
                    : truthy(left.value(scope)) || truthy(right.value(scope)), left.aggregate() || right.aggregate());
        }

        private boolean keyword(String word) {
            if (position < tokens.size() && tokens.get(position).equalsIgnoreCase(word)) {
                position++;
                return true;
            }
            return false;
        }

        private boolean symbol(String symbol) {
            if (position < tokens.size() && tokens.get(position).equals(symbol)) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(String symbol) {
            if (!symbol(symbol)) {
                throw new IllegalArgumentException("Expected '" + symbol + "' in the check expression.");
            }
        }
    }

    private static Node node(Function<Scope, Object> function, boolean aggregate) {
        return new Node() {
            @Override
            public Object value(Scope scope) {
                return function.apply(scope);
            }

            @Override
            public boolean aggregate() {
                return aggregate;
            }
        };
    }

    private static boolean compare(Object left, Object right, String operator) {
        if (left == null || right == null) {
            return "!=".equals(operator) && (left == null) != (right == null);
        }
        String a = left.toString();
        String b = right.toString();
        int comparison = DataBrewRecipes.numeric(a) && DataBrewRecipes.numeric(b)
                ? new BigDecimal(a.strip()).compareTo(new BigDecimal(b.strip())) : a.compareTo(b);
        return switch (operator) {
            case "==", "=" -> comparison == 0;
            case "!=" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            default -> comparison >= 0;
        };
    }

    private static boolean text(Object left, Object right, String operator) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.toString();
        String b = right.toString();
        return switch (operator) {
            case "starts_with" -> a.startsWith(b);
            case "ends_with" -> a.endsWith(b);
            case "contains" -> a.contains(b);
            default -> Pattern.compile(b).matcher(a).find();
        };
    }

    private static Object aggregate(String statistic, Scope scope) {
        DataBrewTable table = scope.table();
        String column = scope.column();
        switch (statistic) {
            case "ROW_COUNT":
                return Integer.toString(table.rows.size());
            case "COLUMN_COUNT":
                return Integer.toString(table.columns.size());
            case "DUPLICATE_ROWS_COUNT":
                return Integer.toString(duplicateRows(table));
            case "DUPLICATE_ROWS_PERCENTAGE":
                return Double.toString(percent(duplicateRows(table), table.rows.size()));
            default:
                break;
        }
        if (column == null) {
            throw new IllegalArgumentException("AGG(" + statistic + ") needs ColumnSelectors naming its column.");
        }
        List<String> values = table.values(column);
        long missing = values.stream().filter(value -> value == null || value.isEmpty()).count();
        Map<String, Integer> counts = new HashMap<>();
        values.stream().filter(value -> value != null && !value.isEmpty())
                .forEach(value -> counts.merge(value, 1, Integer::sum));
        List<BigDecimal> numbers = DataBrewRecipes.numbers(values);
        return switch (statistic) {
            case "MISSING_VALUES_COUNT" -> Long.toString(missing);
            case "MISSING_VALUES_PERCENTAGE" -> Double.toString(percent(missing, values.size()));
            case "VALID_VALUES_COUNT" -> Long.toString(values.size() - missing);
            case "VALID_VALUES_PERCENTAGE" -> Double.toString(percent(values.size() - missing, values.size()));
            case "DISTINCT_VALUES_COUNT" -> Integer.toString(counts.size());
            case "UNIQUE_VALUES_COUNT" -> Long.toString(counts.values().stream().filter(count -> count == 1).count());
            case "MIN", "MAX", "SUM", "MEAN", "MEDIAN", "STANDARD_DEVIATION" -> numeric(statistic, numbers);
            default -> throw new IllegalArgumentException("Floci does not support AGG(" + statistic + ").");
        };
    }

    private static String numeric(String statistic, List<BigDecimal> numbers) {
        if (numbers.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(numbers);
        sorted.sort(BigDecimal::compareTo);
        BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(numbers.size()), CONTEXT);
        return DataBrewRecipes.format(switch (statistic) {
            case "MIN" -> sorted.get(0);
            case "MAX" -> sorted.get(sorted.size() - 1);
            case "SUM" -> sum;
            case "MEAN" -> mean;
            case "MEDIAN" -> percentile(sorted, 50);
            default -> {
                BigDecimal variance = BigDecimal.ZERO;
                for (BigDecimal number : numbers) {
                    variance = variance.add(number.subtract(mean).pow(2));
                }
                yield variance.divide(BigDecimal.valueOf(numbers.size()), CONTEXT).sqrt(CONTEXT);
            }
        });
    }
}
