package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * The rows DataBrew works on: named columns over string cells, where {@code null} is a missing value. CSV and JSON
 * (JSON Lines, or one array or object with {@code MultiLine}) read and write here; nested JSON values stay as their
 * JSON text.
 */
final class DataBrewTable {

    final List<String> columns;
    final List<Map<String, String>> rows;

    DataBrewTable(List<String> columns, List<Map<String, String>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    static DataBrewTable empty() {
        return new DataBrewTable(new ArrayList<>(), new ArrayList<>());
    }

    DataBrewTable copy() {
        List<Map<String, String>> copied = new ArrayList<>();
        for (Map<String, String> row : rows) {
            copied.add(new LinkedHashMap<>(row));
        }
        return new DataBrewTable(new ArrayList<>(columns), copied);
    }

    void append(DataBrewTable other) {
        for (String column : other.columns) {
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        rows.addAll(other.rows);
    }

    List<String> values(String column) {
        List<String> values = new ArrayList<>();
        for (Map<String, String> row : rows) {
            values.add(row.get(column));
        }
        return values;
    }

    // ---------------------------------------------------------------- CSV

    static DataBrewTable readCsv(String text, char delimiter, boolean header) {
        List<List<String>> records = parseCsv(text, delimiter);
        List<String> columns = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        if (records.isEmpty()) {
            return new DataBrewTable(columns, rows);
        }
        int start = 0;
        if (header) {
            Set<String> seen = new LinkedHashSet<>();
            for (String name : records.get(0)) {
                String base = name.isBlank() ? "Column" + (seen.size() + 1) : name.strip();
                String unique = base;
                int suffix = 2;
                while (!seen.add(unique)) {
                    unique = base + "_" + suffix++;
                }
            }
            columns.addAll(seen);
            start = 1;
        }
        for (int index = start; index < records.size(); index++) {
            List<String> record = records.get(index);
            if (record.size() == 1 && record.get(0).isEmpty()) {
                continue;
            }
            while (columns.size() < record.size()) {
                columns.add("Column" + (columns.size() + 1));
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < columns.size(); column++) {
                String cell = column < record.size() ? record.get(column) : null;
                row.put(columns.get(column), cell == null || cell.isEmpty() ? null : cell);
            }
            rows.add(row);
        }
        return new DataBrewTable(columns, rows);
    }

    /** RFC 4180 records: quoted fields may hold delimiters, doubled quotes and line breaks. */
    static List<List<String>> parseCsv(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            any = true;
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < body.length() && body.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && index + 1 < body.length() && body.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                any = false;
            } else {
                field.append(ch);
            }
        }
        if (any || !field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    String toCsv(char delimiter, boolean header) {
        StringBuilder out = new StringBuilder();
        if (header) {
            appendRecord(out, columns, delimiter);
        }
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                cells.add(row.get(column));
            }
            appendRecord(out, cells, delimiter);
        }
        return out.toString();
    }

    private static void appendRecord(StringBuilder out, List<String> cells, char delimiter) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                out.append(delimiter);
            }
            String cell = cells.get(index);
            if (cell == null) {
                continue;
            }
            if (cell.indexOf(delimiter) >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0
                    || cell.indexOf('\r') >= 0) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append('\n');
    }

    // ---------------------------------------------------------------- JSON

    static DataBrewTable readJson(ObjectMapper mapper, String text, boolean multiLine) {
        List<JsonNode> records = new ArrayList<>();
        try {
            if (multiLine) {
                JsonNode root = mapper.readTree(text);
                if (root != null && root.isArray()) {
                    root.forEach(records::add);
                } else if (root != null) {
                    records.add(root);
                }
            } else {
                for (String line : text.split("\\r?\\n")) {
                    if (!line.isBlank()) {
                        records.add(mapper.readTree(line));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The dataset is not valid JSON"
                    + (multiLine ? "" : " Lines; set MultiLine for a JSON document") + ": " + e.getMessage());
        }
        List<String> columns = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode record : records) {
            if (!record.isObject()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            record.properties().forEach(entry -> {
                if (!columns.contains(entry.getKey())) {
                    columns.add(entry.getKey());
                }
                JsonNode value = entry.getValue();
                row.put(entry.getKey(), value.isNull() ? null : value.isValueNode() ? value.asText() : value.toString());
            });
            rows.add(row);
        }
        for (Map<String, String> row : rows) {
            for (String column : columns) {
                row.putIfAbsent(column, null);
            }
        }
        return new DataBrewTable(columns, rows);
    }

    String toJsonLines(ObjectMapper mapper) {
        StringBuilder out = new StringBuilder();
        for (Map<String, String> row : rows) {
            out.append(rowJson(mapper, row)).append('\n');
        }
        return out.toString();
    }

    ArrayNode toJsonArray(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        for (Map<String, String> row : rows) {
            array.add(rowJson(mapper, row));
        }
        return array;
    }

    private ObjectNode rowJson(ObjectMapper mapper, Map<String, String> row) {
        ObjectNode node = mapper.createObjectNode();
        for (String column : columns) {
            String value = row.get(column);
            if (value == null) {
                node.putNull(column);
            } else {
                node.put(column, value);
            }
        }
        return node;
    }

    static byte[] gzip(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
