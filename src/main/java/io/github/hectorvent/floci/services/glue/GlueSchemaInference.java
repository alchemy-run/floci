package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Schema inference for the Glue crawler's built-in classifiers. Floci implements the CSV
 * (comma, tab, pipe, semicolon, Ctrl-A delimited), JSON (JSON Lines or a single document) and
 * Parquet (schema read by the caller) classifiers; other formats are not classified.
 */
final class GlueSchemaInference {

    static final String CSV = "csv";
    static final String JSON = "json";
    static final String PARQUET = "parquet";

    private static final char[] CSV_DELIMITERS = {',', '\t', '|', ';', '\u0001'};
    private static final Pattern BIGINT = Pattern.compile("[-+]?\\d{1,18}");
    private static final Pattern DOUBLE = Pattern.compile("[-+]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][-+]?\\d+)?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GlueSchemaInference() {}

    /** The schema a classifier derived from a sample of one object. */
    record FileSchema(String classification, List<Column> columns, Map<String, String> serdeParameters,
                      Map<String, String> tableParameters, long records, long sampledBytes) {}

    static String classificationFor(String key, String contentType, byte[] sample) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".parquet") || startsWith(sample, "PAR1")) {
            return PARQUET;
        }
        if (lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".ndjson")
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json"))) {
            return JSON;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".psv") || lower.endsWith(".txt")
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/"))) {
            String text = decode(sample);
            if (text == null) {
                return null;
            }
            String trimmed = text.stripLeading();
            return trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON : CSV;
        }
        String text = decode(sample);
        if (text == null) {
            return null;
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JSON;
        }
        return CSV;
    }

    /** Returns the CSV schema, or null when the sample is not delimited text with at least two columns. */
    static FileSchema inferCsv(byte[] sample, boolean truncated) {
        String text = decode(sample);
        if (text == null) {
            return null;
        }
        List<String> lines = lines(text, truncated);
        if (lines.isEmpty()) {
            return null;
        }
        char delimiter = detectDelimiter(lines);
        if (delimiter == 0) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(splitCsv(line, delimiter));
        }
        int width = rows.getFirst().size();
        boolean quoted = lines.stream().anyMatch(line -> line.indexOf('"') >= 0);
        List<String> header = rows.getFirst();
        List<List<String>> data = rows.subList(1, rows.size());
        boolean hasHeader = hasHeader(header, data);
        List<List<String>> records = hasHeader ? data : rows;

        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String type = null;
            for (List<String> record : records) {
                String value = i < record.size() ? record.get(i) : "";
                type = widen(type, csvType(value));
            }
            String name = hasHeader ? columnName(header.get(i), i) : "col" + i;
            columns.add(new Column(name, type == null ? "string" : type));
        }
        Map<String, String> serde = new LinkedHashMap<>();
        serde.put("field.delim", String.valueOf(delimiter));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("delimiter", String.valueOf(delimiter));
        params.put("areColumnsQuoted", String.valueOf(quoted));
        params.put("columnsOrdered", "true");
        if (hasHeader) {
            params.put("skip.header.line.count", "1");
        }
        return new FileSchema(CSV, columns, serde, params, records.size(), sample.length);
    }

    /** Returns the JSON schema, or null when the sample holds no JSON objects. */
    static FileSchema inferJson(byte[] sample, boolean truncated) {
        String text = decode(sample);
        if (text == null) {
            return null;
        }
        List<JsonNode> records = jsonLines(lines(text, truncated));
        if (records.isEmpty() && !truncated) {
            try {
                JsonNode document = MAPPER.readTree(text.strip());
                if (document != null && document.isArray()) {
                    document.forEach(records::add);
                } else if (document != null) {
                    records.add(document);
                }
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        long count = 0;
        for (JsonNode record : records) {
            if (record == null || !record.isObject()) {
                continue;
            }
            count++;
            Iterator<Map.Entry<String, JsonNode>> iterator = record.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> field = iterator.next();
                String name = field.getKey().toLowerCase(Locale.ROOT);
                fields.put(name, widen(fields.get(name), jsonType(field.getValue())));
            }
        }
        if (count == 0) {
            return null;
        }
        List<Column> columns = new ArrayList<>();
        fields.forEach((name, type) -> columns.add(new Column(name, type == null ? "string" : type)));
        Map<String, String> serde = new LinkedHashMap<>();
        serde.put("paths", String.join(",", fields.keySet().stream().sorted().toList()));
        return new FileSchema(JSON, columns, serde, new LinkedHashMap<>(), count, sample.length);
    }

    /** Builds a Parquet schema from (column name, DuckDB type) pairs. */
    static FileSchema parquetSchema(List<Map.Entry<String, String>> duckColumns, long objectSize) {
        List<Column> columns = new ArrayList<>();
        for (Map.Entry<String, String> column : duckColumns) {
            columns.add(new Column(column.getKey().toLowerCase(Locale.ROOT), hiveType(column.getValue())));
        }
        Map<String, String> serde = new LinkedHashMap<>();
        serde.put("serialization.format", "1");
        return new FileSchema(PARQUET, columns, serde, new LinkedHashMap<>(), 0, objectSize);
    }

    static void applyFormat(StorageDescriptor descriptor, String classification, Map<String, String> serdeParameters) {
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setParameters(new LinkedHashMap<>(serdeParameters));
        switch (classification) {
            case PARQUET -> {
                descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
                descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat");
                serde.setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe");
            }
            case JSON -> {
                descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
                descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
                serde.setSerializationLibrary("org.openx.data.jsonserde.JsonSerDe");
            }
            default -> {
                descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
                descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
                serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
            }
        }
        descriptor.setSerdeInfo(serde);
    }

    /** Merges two schemas of the same classification, widening conflicting column types. */
    static List<Column> mergeColumns(List<Column> left, List<Column> right) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Column column : left) {
            merged.put(column.getName(), column.getType());
        }
        for (Column column : right) {
            merged.merge(column.getName(), column.getType(), GlueSchemaInference::widen);
        }
        List<Column> columns = new ArrayList<>();
        merged.forEach((name, type) -> columns.add(new Column(name, type)));
        return columns;
    }

    /** Glue treats schemas as the same table when their column names overlap by at least 70%. */
    static boolean similar(FileSchema left, FileSchema right) {
        if (!left.classification().equals(right.classification())) {
            return false;
        }
        Set<String> leftNames = new HashSet<>();
        left.columns().forEach(column -> leftNames.add(column.getName()));
        Set<String> rightNames = new HashSet<>();
        right.columns().forEach(column -> rightNames.add(column.getName()));
        Set<String> union = new HashSet<>(leftNames);
        union.addAll(rightNames);
        if (union.isEmpty()) {
            return true;
        }
        leftNames.retainAll(rightNames);
        return leftNames.size() * 10 >= union.size() * 7;
    }

    static String tableName(String prefix, String folder) {
        String base = folder.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return (prefix == null ? "" : prefix) + base;
    }

    static String widen(String current, String next) {
        if (next == null) {
            return current;
        }
        if (current == null || current.equals(next)) {
            return next;
        }
        if (isNumeric(current) && isNumeric(next)) {
            return "double".equals(current) || "double".equals(next) ? "double" : "bigint";
        }
        return "string";
    }

    private static boolean isNumeric(String type) {
        return "int".equals(type) || "bigint".equals(type) || "double".equals(type);
    }

    private static String csvType(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (BIGINT.matcher(trimmed).matches()) {
            return "bigint";
        }
        if (DOUBLE.matcher(trimmed).matches()) {
            return "double";
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return "boolean";
        }
        return "string";
    }

    private static String jsonType(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isInt()) {
            return "int";
        }
        if (value.isIntegralNumber()) {
            return "bigint";
        }
        if (value.isNumber()) {
            return "double";
        }
        if (value.isArray()) {
            String element = null;
            for (JsonNode item : value) {
                element = widen(element, jsonType(item));
            }
            return "array<" + (element == null ? "string" : element) + ">";
        }
        if (value.isObject()) {
            List<String> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = value.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> field = iterator.next();
                String type = jsonType(field.getValue());
                fields.add(field.getKey().toLowerCase(Locale.ROOT) + ":" + (type == null ? "string" : type));
            }
            return "struct<" + String.join(",", fields) + ">";
        }
        return "string";
    }

    static String hiveType(String duckType) {
        String type = duckType.trim().toUpperCase(Locale.ROOT);
        if (type.endsWith("[]")) {
            return "array<" + hiveType(type.substring(0, type.length() - 2)) + ">";
        }
        if (type.startsWith("DECIMAL")) {
            return type.toLowerCase(Locale.ROOT);
        }
        return switch (type) {
            case "BIGINT", "HUGEINT", "UBIGINT" -> "bigint";
            case "INTEGER", "UINTEGER" -> "int";
            case "SMALLINT", "USMALLINT" -> "smallint";
            case "TINYINT", "UTINYINT" -> "tinyint";
            case "DOUBLE" -> "double";
            case "FLOAT", "REAL" -> "float";
            case "BOOLEAN" -> "boolean";
            case "DATE" -> "date";
            case "BLOB" -> "binary";
            default -> type.startsWith("TIMESTAMP") ? "timestamp" : "string";
        };
    }

    private static boolean hasHeader(List<String> header, List<List<String>> data) {
        if (header.stream().anyMatch(value -> !"string".equals(csvType(value)))) {
            return false;
        }
        if (data.isEmpty()) {
            return true;
        }
        for (int i = 0; i < header.size(); i++) {
            String type = null;
            for (List<String> row : data) {
                type = widen(type, csvType(i < row.size() ? row.get(i) : ""));
            }
            if (type != null && !"string".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static char detectDelimiter(List<String> lines) {
        for (char delimiter : CSV_DELIMITERS) {
            int width = splitCsv(lines.getFirst(), delimiter).size();
            if (width < 2) {
                continue;
            }
            boolean consistent = true;
            for (String line : lines) {
                if (splitCsv(line, delimiter).size() != width) {
                    consistent = false;
                    break;
                }
            }
            if (consistent) {
                return delimiter;
            }
        }
        return 0;
    }

    static List<String> splitCsv(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String columnName(String header, int index) {
        String name = header.trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? "col" + index : name;
    }

    private static List<String> lines(String text, boolean truncated) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (truncated && !lines.isEmpty()) {
            lines.removeLast();
        }
        lines.removeIf(String::isBlank);
        return lines;
    }

    // JSON Lines: every non-blank line is one object. Anything else is not line-delimited.
    private static List<JsonNode> jsonLines(List<String> lines) {
        List<JsonNode> records = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return new ArrayList<>();
            }
            try {
                records.add(MAPPER.readTree(trimmed));
            } catch (JsonProcessingException e) {
                return new ArrayList<>();
            }
        }
        return records;
    }

    private static boolean startsWith(byte[] sample, String magic) {
        byte[] bytes = magic.getBytes(StandardCharsets.US_ASCII);
        if (sample.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (sample[i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    /** Decodes UTF-8 text, or returns null for binary content. */
    private static String decode(byte[] sample) {
        for (byte b : sample) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample, 0, utf8Boundary(sample)))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // A truncated sample may end inside a multi-byte sequence; drop the incomplete tail.
    private static int utf8Boundary(byte[] sample) {
        int end = sample.length;
        int back = 0;
        while (back < 3 && end - back - 1 >= 0 && (sample[end - back - 1] & 0xC0) == 0x80) {
            back++;
        }
        int lead = end - back - 1;
        if (lead < 0) {
            return end;
        }
        int b = sample[lead] & 0xFF;
        int expected = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
        return back + 1 < expected ? lead : end;
    }
}
