package io.floci.aws.services.frauddetector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CSV files batch jobs and model training read: a header row naming {@code EVENT_ID},
 * {@code EVENT_TIMESTAMP}, {@code ENTITY_ID}, {@code ENTITY_TYPE}, {@code EVENT_LABEL}, {@code LABEL_TIMESTAMP}
 * and the event type's variables, then one event per row. Quoted cells may hold commas, newlines and doubled quotes.
 */
final class FraudDetectorCsv {

    private static final List<String> RESERVED = List.of("EVENT_ID", "EVENT_TIMESTAMP", "ENTITY_ID", "ENTITY_TYPE",
            "EVENT_LABEL", "LABEL_TIMESTAMP");

    private FraudDetectorCsv() {
    }

    static List<Map<String, String>> parse(String content) {
        List<List<String>> records = records(content);
        if (records.isEmpty()) {
            throw new AwsException("ValidationException", "The CSV file is empty.", 400);
        }
        List<String> header = records.getFirst();
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() == 1 && record.getFirst().isBlank()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i).trim();
                row.put(RESERVED.contains(name.toUpperCase()) ? name.toUpperCase() : name,
                        i < record.size() ? record.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<List<String>> records(String content) {
        List<List<String>> out = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(cell.toString());
                cell.setLength(0);
                out.add(record);
                record = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !record.isEmpty()) {
            record.add(cell.toString());
            out.add(record);
        }
        return out;
    }

    /** A row as a SendEvent-shaped request: the event type's variables, entity and label. */
    static ObjectNode eventRequest(ObjectMapper mapper, Map<String, String> row, ObjectNode eventType) {
        ObjectNode request = mapper.createObjectNode().put("eventId", row.getOrDefault("EVENT_ID", ""))
                .put("eventTypeName", eventType.path("name").asText())
                .put("eventTimestamp", row.getOrDefault("EVENT_TIMESTAMP", ""));
        ObjectNode variables = request.putObject("eventVariables");
        row.forEach((name, value) -> {
            if (!RESERVED.contains(name) && !value.isEmpty()) {
                variables.put(name, value);
            }
        });
        String entityType = row.getOrDefault("ENTITY_TYPE", "");
        request.putArray("entities").addObject()
                .put("entityType", entityType.isEmpty() ? eventType.path("entityTypes").path(0).asText()
                        : entityType)
                .put("entityId", row.getOrDefault("ENTITY_ID", "unknown"));
        String label = row.getOrDefault("EVENT_LABEL", "");
        if (!label.isEmpty()) {
            request.put("assignedLabel", label);
            String labelTimestamp = row.getOrDefault("LABEL_TIMESTAMP", "");
            request.put("labelTimestamp", labelTimestamp.isEmpty() ? row.getOrDefault("EVENT_TIMESTAMP", "")
                    : labelTimestamp);
        }
        return request;
    }

    /** A CSV cell, quoted when it needs to be. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
