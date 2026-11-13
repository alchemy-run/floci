package io.floci.aws.services.appflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records as AppFlow moves them: read from CSV (with a header row) or JSON (an array, or one object
 * per line), changed by a flow's tasks, and written as CSV or JSON lines. Values stay as text unless
 * a task's data type or arithmetic says otherwise.
 */
final class AppFlowRecords {
    private AppFlowRecords() {
    }

    /** Thrown when a Validate task's action is TerminateFlow. */
    static final class Terminated extends RuntimeException {
        Terminated(String message) {
            super(message);
        }
    }

    /** What applying the tasks did: the records out, and how many the filters and validations dropped. */
    record Result(List<Map<String, Object>> records, List<String> fields, int filtered) {}

    // ── Reading ────────────────────────────────────────────────────────────

    static List<Map<String, Object>> parse(String text, String fileType, ObjectMapper mapper) {
        String trimmed = text.strip();
        boolean json = "JSON".equals(fileType) || (fileType == null && (trimmed.startsWith("{")
                || trimmed.startsWith("[")));
        return json ? parseJson(trimmed, mapper) : parseCsv(text);
    }

    private static List<Map<String, Object>> parseJson(String text, ObjectMapper mapper) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if (text.startsWith("[")) {
                for (JsonNode node : mapper.readTree(text)) {
                    out.add(flatten(node));
                }
                return out;
            }
            for (String line : text.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    out.add(flatten(mapper.readTree(line)));
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("The source object is not valid JSON: " + e.getMessage());
        }
    }

    private static Map<String, Object> flatten(JsonNode node) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode value = e.getValue();
            record.put(e.getKey(), value.isNull() ? null : value.isValueNode() ? value.asText() : value.toString());
        }
        return record;
    }

    static List<Map<String, Object>> parseCsv(String text) {
        List<List<String>> rows = csvRows(text);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.getFirst();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() == 1 && row.getFirst().isEmpty()) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                record.put(header.get(i), i < row.size() ? row.get(i) : null);
            }
            out.add(record);
        }
        return out;
    }

    private static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** The field names of an S3 object: its CSV header, or the keys of its first JSON record. */
    static List<String> fieldsOf(String text, ObjectMapper mapper) {
        List<Map<String, Object>> records = parse(text, null, mapper);
        if (!records.isEmpty()) {
            return new ArrayList<>(records.getFirst().keySet());
        }
        List<List<String>> rows = csvRows(text);
        return rows.isEmpty() ? List.of() : rows.getFirst();
    }

    // ── Tasks ──────────────────────────────────────────────────────────────

    /**
     * Applies a flow's tasks: filters and validations decide which source records go on; Map_all
     * copies every field (but {@code EXCLUDE_SOURCE_FIELDS_LIST}), and Map, Merge, Arithmetic, Mask,
     * Truncate and Passthrough write their destination fields.
     */
    static Result apply(List<Map<String, Object>> source, JsonNode tasks) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> fields = new LinkedHashSet<>();
        int filtered = 0;
        boolean mapAll = false;
        Set<String> excluded = new LinkedHashSet<>();
        for (JsonNode task : tasks) {
            if ("Map_all".equals(task.path("taskType").asText())) {
                mapAll = true;
                String list = task.path("taskProperties").path("EXCLUDE_SOURCE_FIELDS_LIST").asText("");
                for (String name : list.replaceAll("[\\[\\]\"]", "").split(",")) {
                    if (!name.isBlank()) {
                        excluded.add(name.strip());
                    }
                }
            }
        }
        records:
        for (Map<String, Object> record : source) {
            for (JsonNode task : tasks) {
                String type = task.path("taskType").asText();
                if ("Filter".equals(type) && !keep(record, task)) {
                    filtered++;
                    continue records;
                }
                if ("Validate".equals(type) && !valid(record, task)) {
                    String action = task.path("taskProperties").path("VALIDATION_ACTION").asText("DropRecord");
                    if ("TerminateFlow".equals(action)) {
                        throw new Terminated("Validation failed on " + task.path("sourceFields") + ".");
                    }
                    if ("DropRecord".equals(action)) {
                        filtered++;
                        continue records;
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (mapAll) {
                record.forEach((name, value) -> {
                    if (!excluded.contains(name)) {
                        result.put(name, value);
                    }
                });
            }
            for (JsonNode task : tasks) {
                String type = task.path("taskType").asText();
                String destination = task.path("destinationField").asText(null);
                List<String> fieldsIn = new ArrayList<>();
                task.path("sourceFields").forEach(f -> fieldsIn.add(f.asText()));
                Object value = switch (type) {
                    case "Map", "Passthrough" -> fieldsIn.isEmpty() ? null : record.get(fieldsIn.getFirst());
                    case "Merge" -> merge(record, fieldsIn, task);
                    case "Arithmetic" -> arithmetic(record, fieldsIn, task);
                    case "Mask" -> mask(fieldsIn.isEmpty() ? null : record.get(fieldsIn.getFirst()), task);
                    case "Truncate" -> truncate(fieldsIn.isEmpty() ? null : record.get(fieldsIn.getFirst()), task);
                    default -> NONE;
                };
                if (value != NONE) {
                    String name = destination != null && !destination.isEmpty() ? destination
                            : fieldsIn.isEmpty() ? null : fieldsIn.getFirst();
                    if (name != null) {
                        result.put(name, convert(value, task.path("taskProperties")
                                .path("DESTINATION_DATA_TYPE").asText(null)));
                    }
                }
            }
            fields.addAll(result.keySet());
            out.add(result);
        }
        return new Result(out, new ArrayList<>(fields), filtered);
    }

    private static final Object NONE = new Object();

    private static boolean keep(Map<String, Object> record, JsonNode task) {
        String operator = operator(task);
        List<String> fieldsIn = new ArrayList<>();
        task.path("sourceFields").forEach(f -> fieldsIn.add(f.asText()));
        if ("PROJECTION".equals(operator) || "NO_OP".equals(operator) || fieldsIn.isEmpty()) {
            return true;
        }
        Object value = record.get(fieldsIn.getFirst());
        JsonNode properties = task.path("taskProperties");
        String text = value == null ? "" : value.toString();
        return switch (operator) {
            case "EQUAL_TO" -> values(properties).contains(text);
            case "NOT_EQUAL_TO" -> !values(properties).contains(text);
            case "CONTAINS" -> text.contains(properties.path("VALUE").asText(""));
            case "LESS_THAN" -> compare(text, properties.path("VALUE").asText()) < 0;
            case "LESS_THAN_OR_EQUAL_TO" -> compare(text, properties.path("VALUE").asText()) <= 0;
            case "GREATER_THAN" -> compare(text, properties.path("VALUE").asText()) > 0;
            case "GREATER_THAN_OR_EQUAL_TO" -> compare(text, properties.path("VALUE").asText()) >= 0;
            case "BETWEEN" -> compare(text, properties.path("LOWER_BOUND").asText()) >= 0
                    && compare(text, properties.path("UPPER_BOUND").asText()) <= 0;
            default -> true;
        };
    }

    private static List<String> values(JsonNode properties) {
        List<String> out = new ArrayList<>();
        if (properties.hasNonNull("VALUES")) {
            for (String value : properties.get("VALUES").asText().replaceAll("[\\[\\]\"]", "").split(",")) {
                out.add(value.strip());
            }
        }
        if (properties.hasNonNull("VALUE")) {
            out.add(properties.get("VALUE").asText());
        }
        return out;
    }

    private static int compare(String left, String right) {
        try {
            return new BigDecimal(left.strip()).compareTo(new BigDecimal(right.strip()));
        } catch (NumberFormatException e) {
            return left.compareTo(right);
        }
    }

    private static boolean valid(Map<String, Object> record, JsonNode task) {
        String operator = operator(task);
        Object value = task.path("sourceFields").isEmpty() ? null
                : record.get(task.path("sourceFields").get(0).asText());
        String text = value == null ? null : value.toString();
        return switch (operator) {
            case "VALIDATE_NON_NULL" -> text != null && !text.isEmpty();
            case "VALIDATE_NON_ZERO" -> text == null || !isNumber(text) || new BigDecimal(text).signum() != 0;
            case "VALIDATE_NON_NEGATIVE" -> text == null || !isNumber(text) || new BigDecimal(text).signum() >= 0;
            case "VALIDATE_NUMERIC" -> text != null && isNumber(text);
            default -> true;
        };
    }

    private static boolean isNumber(String text) {
        try {
            new BigDecimal(text.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String operator(JsonNode task) {
        Iterator<JsonNode> values = task.path("connectorOperator").elements();
        return values.hasNext() ? values.next().asText() : "NO_OP";
    }

    private static Object merge(Map<String, Object> record, List<String> fieldsIn, JsonNode task) {
        String format = task.path("taskProperties").path("CONCAT_FORMAT").asText(null);
        if (format != null) {
            String out = format;
            for (String field : fieldsIn) {
                Object value = record.get(field);
                out = out.replace("${" + field + "}", value == null ? "" : value.toString());
            }
            return out;
        }
        StringBuilder out = new StringBuilder();
        for (String field : fieldsIn) {
            Object value = record.get(field);
            out.append(value == null ? "" : value);
        }
        return out.toString();
    }

    private static Object arithmetic(Map<String, Object> record, List<String> fieldsIn, JsonNode task) {
        String operator = operator(task);
        List<String> order = new ArrayList<>(fieldsIn);
        String declared = task.path("taskProperties").path("MATH_OPERATION_FIELDS_ORDER").asText(null);
        if (declared != null) {
            order.clear();
            for (String name : declared.replaceAll("[\\[\\]\"]", "").split(",")) {
                order.add(name.strip());
            }
        }
        BigDecimal result = null;
        for (String field : order) {
            Object value = record.get(field);
            if (value == null || !isNumber(value.toString())) {
                return null;
            }
            BigDecimal number = new BigDecimal(value.toString().strip());
            if (result == null) {
                result = number;
                continue;
            }
            result = switch (operator) {
                case "ADDITION" -> result.add(number);
                case "SUBTRACTION" -> result.subtract(number);
                case "MULTIPLICATION" -> result.multiply(number);
                case "DIVISION" -> number.signum() == 0 ? null
                        : result.divide(number, MathContext.DECIMAL64);
                default -> result;
            };
            if (result == null) {
                return null;
            }
        }
        return result == null ? null : result.stripTrailingZeros().toPlainString();
    }

    private static Object mask(Object value, JsonNode task) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        String maskValue = task.path("taskProperties").path("MASK_VALUE").asText("*");
        int length = task.path("taskProperties").path("MASK_LENGTH").asInt(text.length());
        return switch (operator(task)) {
            case "MASK_FIRST_N" -> maskValue.repeat(Math.min(length, text.length()))
                    + text.substring(Math.min(length, text.length()));
            case "MASK_LAST_N" -> text.substring(0, text.length() - Math.min(length, text.length()))
                    + maskValue.repeat(Math.min(length, text.length()));
            default -> maskValue.repeat(text.length());
        };
    }

    private static Object truncate(Object value, JsonNode task) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        int length = task.path("taskProperties").path("TRUNCATE_LENGTH").asInt(text.length());
        return text.substring(0, Math.min(length, text.length()));
    }

    private static Object convert(Object value, String type) {
        if (value == null || type == null) {
            return value;
        }
        String text = value.toString();
        try {
            return switch (type.toLowerCase()) {
                case "integer", "int", "long" -> new BigDecimal(text.strip()).longValueExact();
                case "double", "float", "decimal", "number" -> new BigDecimal(text.strip());
                case "boolean" -> Boolean.parseBoolean(text.strip());
                default -> text;
            };
        } catch (ArithmeticException | NumberFormatException e) {
            return text;
        }
    }

    // ── Writing ────────────────────────────────────────────────────────────

    static String csv(List<Map<String, Object>> records, List<String> fields) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", fields.stream().map(AppFlowRecords::quote).toList())).append('\n');
        for (Map<String, Object> record : records) {
            List<String> row = new ArrayList<>();
            for (String field : fields) {
                Object value = record.get(field);
                row.add(value == null ? "" : quote(value.toString()));
            }
            out.append(String.join(",", row)).append('\n');
        }
        return out.toString();
    }

    private static String quote(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    static String jsonLines(List<Map<String, Object>> records, ObjectMapper mapper) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> record : records) {
            ObjectNode node = mapper.createObjectNode();
            record.forEach((name, value) -> node.set(name, mapper.valueToTree(value)));
            out.append(node).append('\n');
        }
        return out.toString();
    }
}
