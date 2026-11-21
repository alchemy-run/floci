package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HealthLake's CSV transformation profiles: a YAML document of tables, each a CSV file mapped to FHIR R4 resources
 * through field mappings, aggregations of child tables, and grouped rows, with the direct, concat, dateFormat,
 * valueMap, expression, conditional, resourceReference and regex transforms. A resource's id is derived from the
 * data store (or the job), the profile's {@code sourceSystemId}, the table, the row's primary key and the resource
 * type, so loading the same rows again updates the same resources.
 */
final class HealthLakeCsvMapper {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\[(\\d*)])?");

    /** A converted table set: the resources, and what went wrong on the way. */
    record Result(List<JsonNode> resources, List<String> warnings, int rows) {
    }

    /** A CSV file: its header and rows, each a column-to-value map. */
    record Table(List<String> header, List<Map<String, String>> rows) {
    }

    private HealthLakeCsvMapper() {
    }

    /** Parses and checks a profile's YAML; throws IllegalArgumentException with what is wrong. */
    static JsonNode profile(String yaml) {
        JsonNode profile;
        try {
            profile = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("The CSV mapping is not valid YAML: " + e.getMessage());
        }
        if (profile == null || !profile.isObject()) {
            throw new IllegalArgumentException("The CSV mapping must be a YAML object with sourceSystemId and tables.");
        }
        if (!profile.hasNonNull("sourceSystemId")) {
            throw new IllegalArgumentException("The CSV mapping needs a sourceSystemId.");
        }
        if (!profile.path("tables").isArray() || profile.path("tables").isEmpty()) {
            throw new IllegalArgumentException("The CSV mapping needs a list of tables.");
        }
        Set<String> declared = new LinkedHashSet<>();
        profile.path("tables").forEach(table -> declared.add(table.path("tableName").asText()));
        for (JsonNode table : profile.path("tables")) {
            String name = table.path("tableName").asText("");
            if (!TABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("tableName " + name + " may contain only letters, digits and "
                        + "underscores.");
            }
            if (!table.hasNonNull("primaryKeyColumn")) {
                throw new IllegalArgumentException("Table " + name + " needs a primaryKeyColumn.");
            }
            if (!table.path("resourceMappings").isArray() || table.path("resourceMappings").isEmpty()) {
                throw new IllegalArgumentException("Table " + name + " needs at least one resourceMappings entry.");
            }
            for (JsonNode key : table.path("foreignKeys")) {
                if (!declared.contains(key.path("referencedTable").asText())) {
                    throw new IllegalArgumentException("A foreign key of " + name + " references "
                            + key.path("referencedTable").asText() + ", which the mapping does not declare.");
                }
            }
            for (JsonNode mapping : table.path("resourceMappings")) {
                if (!mapping.hasNonNull("targetResourceType")) {
                    throw new IllegalArgumentException("A resource mapping of " + name + " needs a "
                            + "targetResourceType.");
                }
                for (JsonNode field : mapping.path("fieldMappings")) {
                    checkField(name, field, declared);
                }
                for (JsonNode field : mapping.path("arrayFieldMappings")) {
                    checkField(name, field, declared);
                    if (field.path("fhirPath").asText().split("\\[]", -1).length != 2) {
                        throw new IllegalArgumentException("An arrayFieldMappings path of " + name + " has exactly "
                                + "one [] placeholder: " + field.path("fhirPath").asText());
                    }
                }
                for (JsonNode aggregation : mapping.path("aggregationMappings")) {
                    if (!aggregation.hasNonNull("childTable") || !aggregation.hasNonNull("joinColumn")) {
                        throw new IllegalArgumentException("An aggregation of " + name + " needs childTable and "
                                + "joinColumn.");
                    }
                    if (!aggregation.path("mergeIntoParent").asBoolean(false)
                            && !aggregation.hasNonNull("targetFhirArray")) {
                        throw new IllegalArgumentException("An array aggregation of " + name + " needs "
                                + "targetFhirArray.");
                    }
                    for (JsonNode field : aggregation.path("childFieldMappings")) {
                        checkField(name, field, declared);
                    }
                }
            }
        }
        return profile;
    }

    private static void checkField(String table, JsonNode field, Set<String> declared) {
        if (!field.hasNonNull("fhirPath")) {
            throw new IllegalArgumentException("A field mapping of " + table + " needs a fhirPath.");
        }
        boolean single = field.hasNonNull("sourceColumn");
        boolean multiple = field.path("sourceColumns").isArray();
        if (single == multiple) {
            throw new IllegalArgumentException("Field " + field.path("fhirPath").asText() + " of " + table + " sets "
                    + "exactly one of sourceColumn and sourceColumns.");
        }
        JsonNode transform = field.path("transform");
        String type = transform.path("type").asText("direct");
        boolean concat = "concat".equals(type) || ("conditional".equals(type)
                && "concat".equals(transform.path("innerTransform").path("type").asText()));
        if (multiple != concat) {
            throw new IllegalArgumentException("Field " + field.path("fhirPath").asText() + " of " + table + " uses "
                    + (multiple ? "sourceColumns, which only concat takes" : "concat, which takes sourceColumns") + ".");
        }
        checkTransform(table, field.path("fhirPath").asText(), transform, declared);
    }

    private static void checkTransform(String table, String path, JsonNode transform, Set<String> declared) {
        String type = transform.path("type").asText("direct");
        switch (type) {
            case "direct" -> {
            }
            case "concat" -> require(transform.has("separator"), table, path, "concat needs a separator");
            case "dateFormat" -> {
                require(transform.hasNonNull("fromPattern") && transform.hasNonNull("toPattern"), table, path,
                        "dateFormat needs fromPattern and toPattern");
                try {
                    DateTimeFormatter.ofPattern(transform.get("fromPattern").asText());
                    DateTimeFormatter.ofPattern(transform.get("toPattern").asText());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Field " + path + " of " + table + " has a date pattern Java "
                            + "does not read: " + e.getMessage());
                }
            }
            case "valueMap" -> require(transform.path("mappings").isObject(), table, path, "valueMap needs mappings");
            case "expression" -> require(transform.hasNonNull("expression"), table, path,
                    "expression needs an expression");
            case "conditional" -> {
                require(transform.hasNonNull("conditionColumn"), table, path, "conditional needs a conditionColumn");
                if (transform.has("innerTransform")) {
                    checkTransform(table, path, transform.get("innerTransform"), declared);
                }
            }
            case "resourceReference" -> {
                require(transform.hasNonNull("referencedTable") && transform.hasNonNull("referencedResourceType"),
                        table, path, "resourceReference needs referencedTable and referencedResourceType");
                require(declared.contains(transform.get("referencedTable").asText()), table, path,
                        "resourceReference names " + transform.get("referencedTable").asText()
                                + ", which the mapping does not declare");
            }
            case "regex" -> {
                require(transform.hasNonNull("pattern") && transform.path("group").asInt(0) >= 1, table, path,
                        "regex needs a pattern and a group of 1 or more");
                try {
                    Pattern.compile(transform.get("pattern").asText());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Field " + path + " of " + table + " has a pattern Java "
                            + "does not read: " + e.getMessage());
                }
            }
            default -> throw new IllegalArgumentException("Field " + path + " of " + table + " uses an unknown "
                    + "transform, " + type + ".");
        }
    }

    private static void require(boolean condition, String table, String path, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Field " + path + " of " + table + ": " + message + ".");
        }
    }

    // ---------------------------------------------------------------- CSV

    /** Parses CSV text with a header row; the delimiter, comma, tab or pipe, is detected from the header. */
    static Table parse(String text) {
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> lines = records(content);
        if (lines.isEmpty()) {
            return new Table(List.of(), List.of());
        }
        String first = lines.get(0);
        char delimiter = count(first, '\t') > count(first, ',') ? '\t' : count(first, '|') > count(first, ',')
                ? '|' : ',';
        List<String> header = fields(first, delimiter);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> values = fields(lines.get(i), delimiter);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), column < values.size() ? values.get(column) : "");
            }
            rows.add(row);
        }
        return new Table(header, rows);
    }

    private static int count(String line, char c) {
        int n = 0;
        for (char ch : line.toCharArray()) {
            if (ch == c) {
                n++;
            }
        }
        return n;
    }

    /** Lines of CSV, a quoted field's line breaks kept inside it. */
    private static List<String> records(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static List<String> fields(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                out.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString().trim());
        return out;
    }

    // ---------------------------------------------------------------- conversion

    /**
     * Converts tables (by name) with a profile. Every declared table must be present with the columns its mappings
     * read; a child table that is missing aggregates nothing, with a warning.
     */
    static Result convert(JsonNode profile, Map<String, Table> tables, String scope) {
        List<String> warnings = new ArrayList<>();
        String source = profile.path("sourceSystemId").asText();
        for (JsonNode table : profile.path("tables")) {
            String name = table.path("tableName").asText();
            Table data = tables.get(name);
            if (data == null) {
                throw new IllegalArgumentException("No CSV file was found for table " + name + " (" + name
                        + ".csv).");
            }
            List<String> required = new ArrayList<>(List.of(table.path("primaryKeyColumn").asText()));
            for (JsonNode mapping : table.path("resourceMappings")) {
                for (JsonNode field : mapping.path("fieldMappings")) {
                    required.addAll(columns(field));
                }
            }
            for (String column : required) {
                if (!data.header().contains(column)) {
                    throw new IllegalArgumentException("Table " + name + " has no column " + column + ".");
                }
            }
        }
        List<JsonNode> resources = new ArrayList<>();
        int rows = 0;
        for (JsonNode table : profile.path("tables")) {
            String name = table.path("tableName").asText();
            Table data = tables.get(name);
            String key = table.path("primaryKeyColumn").asText();
            Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
            for (Map<String, String> row : data.rows()) {
                rows++;
                String id = row.getOrDefault(key, "");
                if (table.path("groupByPk").asBoolean(false)) {
                    groups.computeIfAbsent(id, k -> new ArrayList<>()).add(row);
                } else {
                    groups.computeIfAbsent(id + "#" + groups.size(), k -> new ArrayList<>()).add(row);
                }
            }
            for (List<Map<String, String>> group : groups.values()) {
                Map<String, String> row = group.get(0);
                for (JsonNode mapping : table.path("resourceMappings")) {
                    String type = mapping.path("targetResourceType").asText();
                    ObjectNode resource = JSON.createObjectNode();
                    resource.put("resourceType", type).put("id", id(scope, source, name, row.get(key), type));
                    for (JsonNode field : mapping.path("fieldMappings")) {
                        apply(resource, field, row, field.path("fhirPath").asText(), scope, source, warnings);
                    }
                    for (JsonNode field : mapping.path("arrayFieldMappings")) {
                        for (int index = 0; index < group.size(); index++) {
                            String path = field.path("fhirPath").asText().replace("[]", "[" + index + "]");
                            apply(resource, field, group.get(index), path, scope, source, warnings);
                        }
                    }
                    for (JsonNode aggregation : mapping.path("aggregationMappings")) {
                        aggregate(resource, aggregation, row.get(key), tables, scope, source, warnings);
                    }
                    resources.add(resource);
                }
            }
        }
        return new Result(resources, warnings, rows);
    }

    private static void aggregate(ObjectNode resource, JsonNode aggregation, String parentKey,
                                  Map<String, Table> tables, String scope, String source, List<String> warnings) {
        String childName = aggregation.path("childTable").asText();
        Table child = tables.get(childName);
        if (child == null) {
            warnings.add("Child table " + childName + " has no CSV file; nothing was aggregated from it.");
            return;
        }
        String join = aggregation.path("joinColumn").asText();
        List<Map<String, String>> matches = new ArrayList<>();
        for (Map<String, String> row : child.rows()) {
            if (parentKey != null && parentKey.equals(row.get(join))) {
                matches.add(row);
            }
        }
        if (aggregation.path("mergeIntoParent").asBoolean(false)) {
            if (matches.size() > 1) {
                warnings.add(matches.size() + " rows of " + childName + " match " + parentKey + "; the first is "
                        + "merged.");
            }
            if (!matches.isEmpty()) {
                for (JsonNode field : aggregation.path("childFieldMappings")) {
                    apply(resource, field, matches.get(0), field.path("fhirPath").asText(), scope, source, warnings);
                }
            }
            return;
        }
        String array = aggregation.path("targetFhirArray").asText();
        int offset = resource.path(array).size();
        for (int index = 0; index < matches.size(); index++) {
            for (JsonNode field : aggregation.path("childFieldMappings")) {
                apply(resource, field, matches.get(index), array + "[" + (offset + index) + "]."
                        + field.path("fhirPath").asText(), scope, source, warnings);
            }
        }
    }

    private static List<String> columns(JsonNode field) {
        List<String> columns = new ArrayList<>();
        if (field.hasNonNull("sourceColumn")) {
            columns.add(field.get("sourceColumn").asText());
        }
        field.path("sourceColumns").forEach(column -> columns.add(column.asText()));
        return columns;
    }

    private static void apply(ObjectNode resource, JsonNode field, Map<String, String> row, String path,
                              String scope, String source, List<String> warnings) {
        String value = transform(field.path("transform"), field, row, scope, source, warnings);
        if (value == null || value.isEmpty()) {
            return;
        }
        JsonNode typed = typed(value, field.path("targetFhirType").asText("string"), path, warnings);
        if (typed != null) {
            set(resource, path, typed);
        }
    }

    private static String transform(JsonNode transform, JsonNode field, Map<String, String> row, String scope,
                                    String source, List<String> warnings) {
        String type = transform.path("type").asText("direct");
        String column = field.path("sourceColumn").asText(null);
        String value = column == null ? null : row.get(column);
        switch (type) {
            case "direct":
                return value;
            case "concat": {
                List<String> parts = new ArrayList<>();
                for (JsonNode name : field.path("sourceColumns")) {
                    String part = row.get(name.asText());
                    if (part != null && !part.isEmpty()) {
                        parts.add(part);
                    }
                }
                return String.join(transform.path("separator").asText(""), parts);
            }
            case "dateFormat": {
                if (value == null || value.isEmpty()) {
                    return null;
                }
                try {
                    TemporalAccessor parsed = DateTimeFormatter.ofPattern(transform.path("fromPattern").asText())
                            .parse(value);
                    return DateTimeFormatter.ofPattern(transform.path("toPattern").asText()).format(parsed);
                } catch (RuntimeException e) {
                    warnings.add("The value " + value + " of " + column + " does not convert with "
                            + transform.path("fromPattern").asText() + " to " + transform.path("toPattern").asText()
                            + ": " + e.getMessage());
                    return null;
                }
            }
            case "valueMap": {
                if (value == null || value.isEmpty()) {
                    return null;
                }
                JsonNode mapped = transform.path("mappings").get(value);
                if (mapped == null) {
                    warnings.add("The value " + value + " of " + column + " is not in its valueMap.");
                    return null;
                }
                return mapped.asText();
            }
            case "expression": {
                Matcher matcher = PLACEHOLDER.matcher(transform.path("expression").asText());
                StringBuilder out = new StringBuilder();
                while (matcher.find()) {
                    String replacement = row.get(matcher.group(1));
                    if (replacement == null) {
                        warnings.add("The expression names a column the table does not have: " + matcher.group(1));
                        replacement = "";
                    }
                    matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(out);
                return out.toString();
            }
            case "conditional": {
                String condition = row.get(transform.path("conditionColumn").asText());
                if (condition == null || condition.isEmpty()) {
                    return transform.hasNonNull("absentValue") ? transform.get("absentValue").asText() : null;
                }
                if (transform.has("innerTransform")) {
                    return transform(transform.get("innerTransform"), field, row, scope, source, warnings);
                }
                return transform.hasNonNull("presentValue") ? transform.get("presentValue").asText() : condition;
            }
            case "resourceReference": {
                if (value == null || value.isEmpty()) {
                    return null;
                }
                String referenced = transform.path("referencedResourceType").asText();
                return referenced + "/" + id(scope, source, transform.path("referencedTable").asText(), value,
                        referenced);
            }
            case "regex": {
                if (value == null) {
                    return null;
                }
                Matcher matcher = Pattern.compile(transform.path("pattern").asText()).matcher(value);
                int group = transform.path("group").asInt();
                if (!matcher.find() || group > matcher.groupCount()) {
                    warnings.add("The value " + value + " of " + column + " does not match "
                            + transform.path("pattern").asText() + " with a group " + group + ".");
                    return null;
                }
                return matcher.group(group);
            }
            default:
                return null;
        }
    }

    private static JsonNode typed(String value, String type, String path, List<String> warnings) {
        switch (type) {
            case "boolean":
                if (Set.of("true", "TRUE", "1").contains(value)) {
                    return JSON.getNodeFactory().booleanNode(true);
                }
                if (Set.of("false", "FALSE", "0").contains(value)) {
                    return JSON.getNodeFactory().booleanNode(false);
                }
                warnings.add("The value " + value + " of " + path + " is not a boolean; it was kept as a string.");
                return JSON.getNodeFactory().textNode(value);
            case "integer":
                try {
                    return JSON.getNodeFactory().numberNode(Long.parseLong(value.trim()));
                } catch (NumberFormatException e) {
                    warnings.add("The value " + value + " of " + path + " is not an integer; it was kept as a "
                            + "string.");
                    return JSON.getNodeFactory().textNode(value);
                }
            case "decimal":
                try {
                    return JSON.getNodeFactory().numberNode(new BigDecimal(value.trim()));
                } catch (NumberFormatException e) {
                    warnings.add("The value " + value + " of " + path + " is not a decimal; it was kept as a "
                            + "string.");
                    return JSON.getNodeFactory().textNode(value);
                }
            case "json":
                try {
                    JsonNode parsed = JSON.readTree(value);
                    if (parsed == null || !(parsed.isObject() || parsed.isArray())) {
                        warnings.add("The value of " + path + " is not a JSON object or array; it was left out.");
                        return null;
                    }
                    return parsed;
                } catch (IOException e) {
                    warnings.add("The value of " + path + " is not valid JSON; it was left out.");
                    return null;
                }
            default:
                return JSON.getNodeFactory().textNode(value);
        }
    }

    /** Sets a value at a dot path with indexed segments ({@code name[0].given[0]}), merging into what is there. */
    static void set(ObjectNode root, String path, JsonNode value) {
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (int i = 0; i < segments.length; i++) {
            Matcher matcher = SEGMENT.matcher(segments[i]);
            if (!matcher.matches()) {
                return;
            }
            String name = matcher.group(1);
            boolean last = i == segments.length - 1;
            ObjectNode parent = (ObjectNode) current;
            if (matcher.group(2) == null) {
                if (last) {
                    if (value.isObject() && parent.path(name).isObject()) {
                        mergeUnder((ObjectNode) parent.get(name), (ObjectNode) value);
                    } else if (!(parent.has(name) && parent.get(name).isContainerNode() && !value.isContainerNode())) {
                        parent.set(name, value);
                    }
                    return;
                }
                if (!parent.path(name).isObject()) {
                    parent.putObject(name);
                }
                current = parent.get(name);
                continue;
            }
            int index = matcher.group(2).isEmpty() ? parent.path(name).size() : Integer.parseInt(matcher.group(2));
            ArrayNode array = parent.path(name).isArray() ? (ArrayNode) parent.get(name) : parent.putArray(name);
            while (array.size() <= index) {
                array.add(last && !value.isObject() ? JSON.nullNode() : JSON.createObjectNode());
            }
            if (last) {
                if (value.isObject() && array.get(index).isObject()) {
                    mergeUnder((ObjectNode) array.get(index), (ObjectNode) value);
                } else {
                    array.set(index, value);
                }
                return;
            }
            if (!array.get(index).isObject()) {
                array.set(index, JSON.createObjectNode());
            }
            current = array.get(index);
        }
    }

    /** Embeds a JSON object under fields already mapped, which keep their values. */
    private static void mergeUnder(ObjectNode target, ObjectNode value) {
        value.fields().forEachRemaining(entry -> {
            if (!target.has(entry.getKey())) {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    /** A resource's stable id, from its scope, source system, table, primary key and type. */
    static String id(String scope, String source, String table, String key, String type) {
        return UUID.nameUUIDFromBytes((scope + "|" + source + "|" + table + "|" + key + "|" + type)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
