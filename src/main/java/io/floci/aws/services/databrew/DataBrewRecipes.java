package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies DataBrew recipe steps to a {@link DataBrewTable}.
 * <p>
 * A step's {@code ConditionExpressions} select the rows it applies to: a column transform changes only the
 * matching rows, and {@code REMOVE_VALUES} drops them. The supported operations cover column structure (rename,
 * delete, duplicate, move, sort), text (case, prefix/suffix, replace, extract, merge, split, whitespace), missing
 * values (fill, remove), duplicates, arithmetic and rounding, and date formatting. Any other operation fails the
 * job run with {@link UnsupportedOperationException}, naming the operation.
 */
final class DataBrewRecipes {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DataBrewRecipes() {
    }

    static DataBrewTable apply(DataBrewTable source, JsonNode steps) {
        DataBrewTable table = source.copy();
        int index = 0;
        for (JsonNode step : steps) {
            index++;
            try {
                applyStep(table, step);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                String operation = step.path("Action").path("Operation").asText();
                throw new IllegalArgumentException("Recipe step " + index + " (" + operation + "): " + e.getMessage(),
                        e);
            }
        }
        return table;
    }

    static void applyStep(DataBrewTable table, JsonNode step) {
        String operation = step.path("Action").path("Operation").asText();
        Map<String, String> p = new HashMap<>();
        step.path("Action").path("Parameters").properties()
                .forEach(entry -> p.put(entry.getKey(), entry.getValue().asText()));
        List<Condition> conditions = new ArrayList<>();
        for (JsonNode condition : step.path("ConditionExpressions")) {
            conditions.add(new Condition(condition.path("Condition").asText(),
                    condition.hasNonNull("Value") ? condition.get("Value").asText() : null,
                    condition.path("TargetColumn").asText()));
        }
        switch (operation) {
            case "RENAME" -> rename(table, column(table, p, "sourceColumn"), required(p, "targetColumn"));
            case "DELETE" -> {
                for (String column : columns(table, p)) {
                    table.columns.remove(column);
                    table.rows.forEach(row -> row.remove(column));
                }
            }
            case "DUPLICATE" -> {
                String sourceColumn = column(table, p, "sourceColumn");
                String target = p.getOrDefault("targetColumn", sourceColumn + "_copy");
                insertAfter(table, sourceColumn, target);
                table.rows.forEach(row -> row.put(target, row.get(sourceColumn)));
            }
            case "MOVE_TO_START" -> move(table, column(table, p, "sourceColumn"), 0);
            case "MOVE_TO_END" -> move(table, column(table, p, "sourceColumn"), Integer.MAX_VALUE);
            case "MOVE_TO_INDEX" -> move(table, column(table, p, "sourceColumn"),
                    Integer.parseInt(required(p, "targetIndex")));
            case "MOVE_BEFORE", "MOVE_AFTER" -> {
                String sourceColumn = column(table, p, "sourceColumn");
                String target = column(table, p, "targetColumn");
                table.columns.remove(sourceColumn);
                int position = table.columns.indexOf(target) + ("MOVE_AFTER".equals(operation) ? 1 : 0);
                table.columns.add(position, sourceColumn);
            }
            case "SORT" -> sort(table, p);
            case "CHANGE_DATA_TYPE" -> changeType(table, column(table, p, "sourceColumn"),
                    required(p, "columnDataType"), conditions);
            case "UPPER_CASE" -> map(table, p, conditions, value -> value.toUpperCase(Locale.ROOT));
            case "LOWER_CASE" -> map(table, p, conditions, value -> value.toLowerCase(Locale.ROOT));
            case "CAPITAL_CASE" -> map(table, p, conditions, DataBrewRecipes::capitalCase);
            case "SENTENCE_CASE" -> map(table, p, conditions, value -> value.isEmpty() ? value
                    : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT));
            case "ADD_PREFIX" -> map(table, p, conditions, value -> required(p, "pattern") + value);
            case "ADD_SUFFIX" -> map(table, p, conditions, value -> value + required(p, "pattern"));
            case "REPLACE_TEXT" -> map(table, p, conditions,
                    value -> value.replace(required(p, "pattern"), p.getOrDefault("value", "")));
            case "REPLACE_PATTERN" -> {
                Pattern pattern = regex(required(p, "pattern"));
                map(table, p, conditions, value -> pattern.matcher(value).replaceAll(
                        Matcher.quoteReplacement(p.getOrDefault("value", ""))));
            }
            case "REMOVE_COMBINED" -> map(table, p, conditions, value -> removeCombined(value, p));
            case "EXTRACT_PATTERN" -> {
                String sourceColumn = column(table, p, "sourceColumn");
                Pattern pattern = regex(required(p, "pattern"));
                String target = p.getOrDefault("targetColumn", sourceColumn + "_extracted");
                insertAfter(table, sourceColumn, target);
                for (Map<String, String> row : table.rows) {
                    String value = row.get(sourceColumn);
                    Matcher matcher = value == null ? null : pattern.matcher(value);
                    row.put(target, matcher != null && matcher.find() && matches(row, conditions)
                            ? matcher.group() : null);
                }
            }
            case "MERGE" -> {
                List<String> sources = columns(table, p);
                String target = required(p, "targetColumn");
                String delimiter = p.getOrDefault("delimiter", "");
                insertAfter(table, sources.get(sources.size() - 1), target);
                for (Map<String, String> row : table.rows) {
                    List<String> parts = new ArrayList<>();
                    for (String sourceColumn : sources) {
                        parts.add(row.get(sourceColumn) == null ? "" : row.get(sourceColumn));
                    }
                    row.put(target, String.join(delimiter, parts));
                }
            }
            case "SPLIT_COLUMN_SINGLE_DELIMITER" -> split(table, column(table, p, "sourceColumn"),
                    required(p, "pattern"), Integer.parseInt(p.getOrDefault("limit", "1")));
            case "FILL_WITH_EMPTY" -> fill(table, p, conditions, column -> "");
            case "FILL_WITH_NULL" -> fill(table, p, conditions, column -> null);
            case "FILL_WITH_CUSTOM" -> fill(table, p, conditions, column -> required(p, "value"));
            case "FILL_WITH_MOST_FREQUENT" -> fill(table, p, conditions, column -> mostFrequent(table.values(column)));
            case "FILL_WITH_AVERAGE" -> fill(table, p, conditions,
                    column -> format(numbers(table.values(column)).stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(Math.max(1, numbers(table.values(column)).size())), 10,
                                    RoundingMode.HALF_UP)));
            case "FILL_WITH_SUM" -> fill(table, p, conditions,
                    column -> format(numbers(table.values(column)).stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
            case "FILL_WITH_MEDIAN" -> fill(table, p, conditions, column -> median(numbers(table.values(column))));
            case "FILL_WITH_LAST_VALID" -> {
                String sourceColumn = column(table, p, "sourceColumn");
                String last = null;
                for (Map<String, String> row : table.rows) {
                    String value = row.get(sourceColumn);
                    if (value == null || value.isEmpty()) {
                        if (matches(row, conditions)) {
                            row.put(sourceColumn, last);
                        }
                    } else {
                        last = value;
                    }
                }
            }
            case "REMOVE_MISSING" -> {
                String sourceColumn = column(table, p, "sourceColumn");
                table.rows.removeIf(row -> row.get(sourceColumn) == null || row.get(sourceColumn).isEmpty());
            }
            case "REMOVE_VALUES" -> {
                if (conditions.isEmpty()) {
                    throw new IllegalArgumentException("REMOVE_VALUES needs ConditionExpressions to select rows.");
                }
                table.rows.removeIf(row -> matches(row, conditions));
            }
            case "REMOVE_DUPLICATES", "DELETE_DUPLICATE_ROWS" -> {
                Set<List<String>> seen = new HashSet<>();
                List<String> keyColumns = p.containsKey("sourceColumn") ? List.of(column(table, p, "sourceColumn"))
                        : List.copyOf(table.columns);
                table.rows.removeIf(row -> {
                    List<String> key = new ArrayList<>();
                    keyColumns.forEach(column -> key.add(row.get(column)));
                    return !seen.add(key);
                });
            }
            case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE" -> arithmetic(table, operation, p, conditions);
            case "ABSOLUTE" -> map(table, p, conditions, value -> format(number(value).abs()));
            case "ROUND" -> map(table, p, conditions, value -> format(number(value).setScale(
                    Integer.parseInt(p.getOrDefault("decimalPlaces", "0")), RoundingMode.HALF_UP)));
            case "FLOOR" -> map(table, p, conditions, value -> format(number(value).setScale(0, RoundingMode.FLOOR)));
            case "CEILING" -> map(table, p, conditions,
                    value -> format(number(value).setScale(0, RoundingMode.CEILING)));
            case "DATE_FORMAT" -> dateFormat(table, p, conditions);
            default -> throw new UnsupportedOperationException("Floci does not support the recipe operation "
                    + operation + ".");
        }
    }

    // ---------------------------------------------------------------- conditions

    record Condition(String name, String value, String column) {}

    static boolean matches(Map<String, String> row, List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (!matches(row.get(condition.column()), condition)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String actual, Condition condition) {
        boolean missing = actual == null || actual.isEmpty();
        String value = condition.value();
        return switch (condition.name()) {
            case "IS_MISSING" -> missing;
            case "IS_NOT_MISSING" -> !missing;
            case "IS" -> !missing && list(value).contains(actual);
            case "IS_NOT" -> missing || !list(value).contains(actual);
            case "STARTS_WITH" -> !missing && actual.startsWith(single(value));
            case "NOT_STARTS_WITH" -> missing || !actual.startsWith(single(value));
            case "ENDS_WITH" -> !missing && actual.endsWith(single(value));
            case "NOT_ENDS_WITH" -> missing || !actual.endsWith(single(value));
            case "CONTAINS" -> !missing && actual.contains(single(value));
            case "NOT_CONTAINS" -> missing || !actual.contains(single(value));
            case "MATCHES" -> !missing && regex(single(value)).matcher(actual).find();
            case "NOT_MATCHES" -> missing || !regex(single(value)).matcher(actual).find();
            case "GREATER_THAN", "GREATER_THAN_EQUAL", "LESS_THAN", "LESS_THAN_EQUAL", "IS_EQUAL", "IS_NOT_EQUAL" ->
                    !missing && compare(actual, single(value), condition.name());
            case "IS_BETWEEN" -> !missing && between(actual, value);
            case "IS_NOT_BETWEEN" -> missing || !between(actual, value);
            case "IS_NUMERIC" -> !missing && numeric(actual);
            case "IS_NOT_NUMERIC" -> missing || !numeric(actual);
            default -> throw new UnsupportedOperationException("Floci does not support the condition "
                    + condition.name() + ".");
        };
    }

    private static boolean compare(String actual, String expected, String operator) {
        int comparison;
        if (numeric(actual) && numeric(expected)) {
            comparison = new BigDecimal(actual.strip()).compareTo(new BigDecimal(expected.strip()));
        } else {
            comparison = actual.compareTo(expected);
        }
        return switch (operator) {
            case "GREATER_THAN" -> comparison > 0;
            case "GREATER_THAN_EQUAL" -> comparison >= 0;
            case "LESS_THAN" -> comparison < 0;
            case "LESS_THAN_EQUAL" -> comparison <= 0;
            case "IS_EQUAL" -> comparison == 0;
            default -> comparison != 0;
        };
    }

    private static boolean between(String actual, String value) {
        try {
            JsonNode bounds = MAPPER.readTree(value);
            String low = bounds.path("greaterThanEquals").asText(bounds.path("greaterThan").asText(null));
            String high = bounds.path("lessThanEquals").asText(bounds.path("lessThan").asText(null));
            boolean lowInclusive = bounds.has("greaterThanEquals");
            boolean highInclusive = bounds.has("lessThanEquals");
            return (low == null || compare(actual, low, lowInclusive ? "GREATER_THAN_EQUAL" : "GREATER_THAN"))
                    && (high == null || compare(actual, high, highInclusive ? "LESS_THAN_EQUAL" : "LESS_THAN"));
        } catch (IOException | NullPointerException e) {
            throw new IllegalArgumentException("IS_BETWEEN needs a JSON Value such as "
                    + "{\"greaterThanEquals\":\"1\",\"lessThanEquals\":\"5\"}.");
        }
    }

    /** A condition value: a JSON array of strings, or the literal text. */
    private static List<String> list(String value) {
        if (value == null) {
            return List.of("");
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("[")) {
            try {
                List<String> values = new ArrayList<>();
                MAPPER.readTree(trimmed).forEach(item -> values.add(item.asText()));
                return values;
            } catch (IOException e) {
                return List.of(value);
            }
        }
        return List.of(value);
    }

    private static String single(String value) {
        return list(value).get(0);
    }

    // ---------------------------------------------------------------- transforms

    private static void map(DataBrewTable table, Map<String, String> p, List<Condition> conditions,
                            UnaryOperator<String> function) {
        String sourceColumn = column(table, p, "sourceColumn");
        String target = p.get("targetColumn");
        if (target != null && !target.equals(sourceColumn)) {
            insertAfter(table, sourceColumn, target);
        }
        String output = target == null ? sourceColumn : target;
        for (Map<String, String> row : table.rows) {
            String value = row.get(sourceColumn);
            if (value != null && matches(row, conditions)) {
                row.put(output, function.apply(value));
            } else if (!output.equals(sourceColumn)) {
                row.put(output, value);
            }
        }
    }

    private static void fill(DataBrewTable table, Map<String, String> p, List<Condition> conditions,
                             UnaryOperator<String> valueFor) {
        String sourceColumn = column(table, p, "sourceColumn");
        String fillValue = valueFor.apply(sourceColumn);
        for (Map<String, String> row : table.rows) {
            String value = row.get(sourceColumn);
            if ((value == null || value.isEmpty()) && matches(row, conditions)) {
                row.put(sourceColumn, fillValue);
            }
        }
    }

    private static void arithmetic(DataBrewTable table, String operation, Map<String, String> p,
                                   List<Condition> conditions) {
        String first = column(table, p, "sourceColumn1");
        String second = p.get("sourceColumn2");
        if (second != null && !table.columns.contains(second)) {
            throw new IllegalArgumentException("Column " + second + " does not exist.");
        }
        String constant = p.get("value2");
        if (second == null && constant == null) {
            throw new IllegalArgumentException(operation + " needs sourceColumn2 or value2.");
        }
        String target = p.getOrDefault("targetColumn", first + "_" + operation.toLowerCase(Locale.ROOT));
        insertAfter(table, first, target);
        for (Map<String, String> row : table.rows) {
            String left = row.get(first);
            String right = second != null ? row.get(second) : constant;
            if (!numeric(left) || !numeric(right) || !matches(row, conditions)) {
                row.put(target, null);
                continue;
            }
            BigDecimal a = number(left);
            BigDecimal b = number(right);
            BigDecimal result = switch (operation) {
                case "ADD" -> a.add(b);
                case "SUBTRACT" -> a.subtract(b);
                case "MULTIPLY" -> a.multiply(b);
                default -> b.signum() == 0 ? null : a.divide(b, 10, RoundingMode.HALF_UP);
            };
            row.put(target, result == null ? null : format(result));
        }
    }

    private static void changeType(DataBrewTable table, String column, String type, List<Condition> conditions) {
        for (Map<String, String> row : table.rows) {
            String value = row.get(column);
            if (value == null || !matches(row, conditions)) {
                continue;
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "int", "integer", "long", "bigint", "short", "byte" -> row.put(column,
                        numeric(value) ? number(value).setScale(0, RoundingMode.DOWN).toPlainString() : null);
                case "double", "float", "decimal" -> row.put(column, numeric(value) ? format(number(value)) : null);
                case "boolean" -> row.put(column, switch (value.strip().toLowerCase(Locale.ROOT)) {
                    case "true", "t", "yes", "y", "1" -> "true";
                    case "false", "f", "no", "n", "0" -> "false";
                    default -> null;
                });
                case "string" -> {
                }
                case "date", "timestamp" -> row.put(column, parseDateTime(value) == null ? null : value);
                default -> throw new IllegalArgumentException("Unknown columnDataType " + type + ".");
            }
        }
    }

    private static void dateFormat(DataBrewTable table, Map<String, String> p, List<Condition> conditions) {
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(required(p, "dateTimeFormat").replace("YYYY", "yyyy")
                    .replace("DD", "dd"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid dateTimeFormat: " + e.getMessage());
        }
        map(table, p, conditions, value -> {
            LocalDateTime parsed = parseDateTime(value);
            return parsed == null ? value : parsed.format(formatter);
        });
    }

    private static LocalDateTime parseDateTime(String value) {
        String text = value.strip();
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException e) {
                // Try the next layout.
            }
        }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ofPattern("MM/dd/yyyy"))) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay();
            } catch (DateTimeParseException e) {
                // Try the next layout.
            }
        }
        return null;
    }

    private static void sort(DataBrewTable table, Map<String, String> p) {
        JsonNode expressions;
        try {
            expressions = MAPPER.readTree(required(p, "expressions"));
        } catch (IOException e) {
            throw new IllegalArgumentException("SORT expressions must be a JSON array.");
        }
        Comparator<Map<String, String>> comparator = null;
        for (JsonNode expression : expressions) {
            String column = expression.path("sourceColumn").asText();
            if (!table.columns.contains(column)) {
                throw new IllegalArgumentException("Column " + column + " does not exist.");
            }
            boolean descending = "DESCENDING".equals(expression.path("ordering").asText("ASCENDING"));
            boolean nullsTop = "NULLS_TOP".equals(expression.path("nullsOrdering").asText("NULLS_BOTTOM"));
            Comparator<Map<String, String>> next = (left, right) -> {
                String a = left.get(column);
                String b = right.get(column);
                if (a == null || b == null) {
                    return a == null && b == null ? 0 : (a == null) == nullsTop ? -1 : 1;
                }
                int result = numeric(a) && numeric(b) ? number(a).compareTo(number(b)) : a.compareTo(b);
                return descending ? -result : result;
            };
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        if (comparator != null) {
            table.rows.sort(comparator);
        }
    }

    private static void split(DataBrewTable table, String column, String delimiter, int limit) {
        List<String> targets = new ArrayList<>();
        for (int index = 1; index <= limit + 1; index++) {
            targets.add(column + "_" + index);
        }
        int position = table.columns.indexOf(column);
        table.columns.remove(column);
        table.columns.addAll(position, targets);
        for (Map<String, String> row : table.rows) {
            String value = row.remove(column);
            String[] parts = value == null ? new String[0] : value.split(Pattern.quote(delimiter), limit + 1);
            for (int index = 0; index < targets.size(); index++) {
                row.put(targets.get(index), index < parts.length ? parts[index] : null);
            }
        }
    }

    private static String removeCombined(String value, Map<String, String> p) {
        String result = value;
        if (Boolean.parseBoolean(p.getOrDefault("removeAllWhitespace", "false"))) {
            result = WHITESPACE.matcher(result).replaceAll("");
        }
        if (Boolean.parseBoolean(p.getOrDefault("removeLeadingAndTrailingWhitespace", "false"))) {
            result = result.strip();
        }
        if (Boolean.parseBoolean(p.getOrDefault("removeLeadingAndTrailingQuotes", "false"))) {
            result = result.replaceAll("^[\"']+|[\"']+$", "");
        }
        if (Boolean.parseBoolean(p.getOrDefault("removeAllPunctuation", "false"))) {
            result = result.replaceAll("\\p{Punct}", "");
        }
        if (Boolean.parseBoolean(p.getOrDefault("removeNumbers", "false"))) {
            result = result.replaceAll("\\d", "");
        }
        if (Boolean.parseBoolean(p.getOrDefault("removeLetters", "false"))) {
            result = result.replaceAll("\\p{L}", "");
        }
        if (p.containsKey("customCharacters")) {
            for (char ch : p.get("customCharacters").toCharArray()) {
                result = result.replace(String.valueOf(ch), "");
            }
        }
        if (p.containsKey("customValue")) {
            result = result.replace(p.get("customValue"), "");
        }
        return result;
    }

    private static String capitalCase(String value) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char ch : value.toCharArray()) {
            out.append(start ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            start = Character.isWhitespace(ch);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static void rename(DataBrewTable table, String from, String to) {
        if (table.columns.contains(to)) {
            throw new IllegalArgumentException("Column " + to + " already exists.");
        }
        table.columns.set(table.columns.indexOf(from), to);
        for (int index = 0; index < table.rows.size(); index++) {
            Map<String, String> renamed = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : table.rows.get(index).entrySet()) {
                renamed.put(entry.getKey().equals(from) ? to : entry.getKey(), entry.getValue());
            }
            table.rows.set(index, renamed);
        }
    }

    private static void move(DataBrewTable table, String column, int index) {
        table.columns.remove(column);
        table.columns.add(Math.max(0, Math.min(index, table.columns.size())), column);
    }

    private static void insertAfter(DataBrewTable table, String after, String column) {
        if (!table.columns.contains(column)) {
            table.columns.add(table.columns.indexOf(after) + 1, column);
        }
    }

    private static String column(DataBrewTable table, Map<String, String> p, String parameter) {
        String column = required(p, parameter);
        if (!table.columns.contains(column)) {
            throw new IllegalArgumentException("Column " + column + " does not exist.");
        }
        return column;
    }

    private static List<String> columns(DataBrewTable table, Map<String, String> p) {
        String value = p.containsKey("sourceColumns") ? p.get("sourceColumns") : required(p, "sourceColumn");
        List<String> names = value.strip().startsWith("[") ? list(value) : List.of(value);
        for (String name : names) {
            if (!table.columns.contains(name)) {
                throw new IllegalArgumentException("Column " + name + " does not exist.");
            }
        }
        return names;
    }

    private static String required(Map<String, String> p, String parameter) {
        String value = p.get(parameter);
        if (value == null) {
            throw new IllegalArgumentException("Missing parameter " + parameter + ".");
        }
        return value;
    }

    private static Pattern regex(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid pattern " + pattern + ".");
        }
    }

    static boolean numeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal number(String value) {
        if (!numeric(value)) {
            throw new IllegalArgumentException("Value " + value + " is not a number.");
        }
        return new BigDecimal(value.strip());
    }

    static List<BigDecimal> numbers(List<String> values) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (String value : values) {
            if (numeric(value)) {
                numbers.add(new BigDecimal(value.strip()));
            }
        }
        return numbers;
    }

    private static String median(List<BigDecimal> numbers) {
        if (numbers.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(numbers);
        sorted.sort(Comparator.naturalOrder());
        int middle = sorted.size() / 2;
        return format(sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP));
    }

    private static String mostFrequent(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
