package io.floci.aws.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Personalize's datasets as Floci keeps them: Avro schemas and the fields they declare, the rows of each dataset
 * (imported from CSV or recorded through the events API), and the CSV they're exported as.
 */
final class PersonalizeData {

    static final String INTERACTIONS = "INTERACTIONS";
    static final String ITEMS = "ITEMS";
    static final String USERS = "USERS";
    static final String ACTIONS = "ACTIONS";
    static final String ACTION_INTERACTIONS = "ACTION_INTERACTIONS";
    static final List<String> TYPES = List.of(INTERACTIONS, ITEMS, USERS, ACTIONS, ACTION_INTERACTIONS);

    /** The field that identifies a row in a dataset that holds one row per thing. */
    static final Map<String, String> KEYS = Map.of(ITEMS, "ITEM_ID", USERS, "USER_ID", ACTIONS, "ACTION_ID");

    /** The fields each dataset type's schema must declare. */
    static final Map<String, List<String>> REQUIRED = Map.of(
            INTERACTIONS, List.of("USER_ID", "ITEM_ID", "TIMESTAMP"),
            ITEMS, List.of("ITEM_ID"),
            USERS, List.of("USER_ID"),
            ACTIONS, List.of("ACTION_ID"),
            ACTION_INTERACTIONS, List.of("USER_ID", "ACTION_ID", "TIMESTAMP", "EVENT_TYPE"));

    private static final Set<String> PRIMITIVES = Set.of("string", "int", "long", "float", "double", "boolean",
            "null");

    /** A schema field: its name, its value type, and whether it may be null. */
    record Field(String name, String type, boolean nullable) {
    }

    private PersonalizeData() {
    }

    /** The dataset type a request names, in Personalize's canonical uppercase form. */
    static String datasetType(String given) {
        String type = given.toUpperCase(Locale.ROOT).replace("-", "_");
        if ("ACTIONINTERACTIONS".equals(type)) {
            type = ACTION_INTERACTIONS;
        }
        if (!TYPES.contains(type)) {
            throw PersonalizeStore.invalid("The dataset type " + given + " isn't one of Interactions, Items, Users, "
                    + "Actions or Action_Interactions.");
        }
        return type;
    }

    /** Parses and checks an Avro record schema, returning its fields. */
    static Map<String, Field> fields(ObjectMapper mapper, String schema) {
        JsonNode root;
        try {
            root = mapper.readTree(schema);
        } catch (Exception e) {
            throw PersonalizeStore.invalid("The schema isn't valid JSON: " + e.getMessage());
        }
        if (!"record".equals(root.path("type").asText()) || !root.path("fields").isArray()) {
            throw PersonalizeStore.invalid("The schema must be an Avro record with fields.");
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        for (JsonNode field : root.path("fields")) {
            String name = field.path("name").asText("");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw PersonalizeStore.invalid("The schema field name '" + name + "' isn't valid.");
            }
            String type = null;
            boolean nullable = false;
            JsonNode declared = field.path("type");
            List<JsonNode> types = new ArrayList<>();
            if (declared.isArray()) {
                declared.forEach(types::add);
            } else {
                types.add(declared);
            }
            for (JsonNode t : types) {
                String value = t.asText("");
                if (!PRIMITIVES.contains(value)) {
                    throw PersonalizeStore.invalid("The schema field " + name + " has an unsupported type " + t
                            + ".");
                }
                if ("null".equals(value)) {
                    nullable = true;
                } else if (type == null) {
                    type = value;
                }
            }
            if (type == null) {
                throw PersonalizeStore.invalid("The schema field " + name + " needs a type other than null.");
            }
            if (fields.put(name.toUpperCase(Locale.ROOT), new Field(name, type, nullable)) != null) {
                throw PersonalizeStore.invalid("The schema declares the field " + name + " twice.");
            }
        }
        return fields;
    }

    /** Fails unless the schema declares the fields a dataset of the type needs. */
    static void checkSchemaFor(Map<String, Field> fields, String type) {
        for (String required : REQUIRED.get(type)) {
            if (!fields.containsKey(required)) {
                throw PersonalizeStore.invalid("The schema of a " + type + " dataset needs the field "
                        + required + ".");
            }
        }
    }

    /** The upper-cased name a field's values are kept under. */
    static String column(Field field) {
        return field.name().toUpperCase(Locale.ROOT);
    }

    /** Converts a value to a field's type, or fails naming the field. */
    static void set(ObjectNode row, Field field, String text) {
        if (text == null || text.isEmpty()) {
            if (!field.nullable() && !"string".equals(field.type())) {
                throw PersonalizeStore.invalid("The field " + field.name() + " can't be empty.");
            }
            if (field.nullable()) {
                row.putNull(column(field));
            } else {
                row.put(column(field), "");
            }
            return;
        }
        try {
            switch (field.type()) {
                case "int", "long" -> row.put(column(field), Long.parseLong(text.trim()));
                case "float", "double" -> row.put(column(field), Double.parseDouble(text.trim()));
                case "boolean" -> {
                    String value = text.trim().toLowerCase(Locale.ROOT);
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new NumberFormatException(text);
                    }
                    row.put(column(field), Boolean.parseBoolean(value));
                }
                default -> row.put(column(field), text);
            }
        } catch (NumberFormatException e) {
            throw PersonalizeStore.invalid("The value '" + text + "' of " + field.name() + " isn't a "
                    + field.type() + ".");
        }
    }

    /** Converts a JSON value given through the events API to a field's type. */
    static void set(ObjectNode row, Field field, JsonNode value) {
        if (value == null || value.isNull()) {
            set(row, field, (String) null);
        } else if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(part -> parts.add(part.asText()));
            set(row, field, String.join("|", parts));
        } else {
            set(row, field, value.asText());
        }
    }

    /** {@code numberOfRatings} as {@code NUMBER_OF_RATINGS}. */
    static String fieldName(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- CSV

    /** Parses CSV text (RFC 4180 quoting) into rows of cells. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || cell.length() > 0) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw PersonalizeStore.invalid("The CSV has an unterminated quoted value.");
        }
        if (any || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Rows from a CSV file with a header naming the schema's fields. */
    static List<ObjectNode> rows(ObjectMapper mapper, Map<String, Field> fields, String type, String text) {
        List<List<String>> table = csv(text);
        if (table.isEmpty()) {
            return List.of();
        }
        List<Field> columns = new ArrayList<>();
        for (String header : table.get(0)) {
            Field field = fields.get(header.trim().toUpperCase(Locale.ROOT));
            if (field == null) {
                throw PersonalizeStore.invalid("The column " + header + " isn't a field of the dataset's schema.");
            }
            columns.add(field);
        }
        for (String required : REQUIRED.get(type)) {
            if (columns.stream().noneMatch(column -> column.name().equalsIgnoreCase(required))) {
                throw PersonalizeStore.invalid("The CSV has no " + required + " column.");
            }
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.size() != columns.size()) {
                throw PersonalizeStore.invalid("Row " + r + " has " + cells.size() + " values for "
                        + columns.size() + " columns.");
            }
            ObjectNode row = mapper.createObjectNode();
            for (int c = 0; c < columns.size(); c++) {
                set(row, columns.get(c), cells.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /** A dataset's rows as CSV, with the schema's fields as the header. */
    static String toCsv(Map<String, Field> fields, List<ObjectNode> rows) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", fields.values().stream().map(Field::name).toList())).append('\n');
        for (ObjectNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (String key : fields.keySet()) {
                JsonNode value = row.get(key);
                String text = value == null || value.isNull() ? "" : value.asText();
                cells.add(text.contains(",") || text.contains("\"") || text.contains("\n")
                        ? "\"" + text.replace("\"", "\"\"") + "\"" : text);
            }
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- stored rows

    /** The rows a dataset holds, each marked with how it arrived ({@code bulk} or {@code put}). */
    static List<ObjectNode> stored(ObjectNode data) {
        List<ObjectNode> rows = new ArrayList<>();
        if (data != null) {
            data.path("rows").forEach(row -> rows.add((ObjectNode) row));
        }
        return rows;
    }

    /** Adds rows to a dataset, replacing those with the same key in a dataset of things. */
    static ObjectNode append(ObjectMapper mapper, ObjectNode data, String type, List<ObjectNode> rows, String source,
                             boolean replaceBulk) {
        ObjectNode out = data == null ? mapper.createObjectNode() : data;
        List<ObjectNode> existing = stored(out);
        if (replaceBulk) {
            existing.removeIf(row -> "bulk".equals(row.path("_src").asText()));
        }
        String key = KEYS.get(type);
        Map<String, ObjectNode> keyed = new LinkedHashMap<>();
        List<ObjectNode> events = new ArrayList<>();
        for (ObjectNode row : existing) {
            if (key == null) {
                events.add(row);
            } else {
                keyed.put(row.path(key).asText(), row);
            }
        }
        for (ObjectNode row : rows) {
            ObjectNode copy = row.deepCopy();
            copy.put("_src", source);
            if (key == null) {
                events.add(copy);
            } else {
                keyed.put(copy.path(key).asText(), copy);
            }
        }
        ArrayNode array = out.putArray("rows");
        (key == null ? events : new ArrayList<>(keyed.values())).forEach(array::add);
        return out;
    }

    /** Whether a row arrived the way an export's ingestion mode asks for. */
    static boolean exported(ObjectNode row, String mode) {
        String source = row.path("_src").asText("bulk");
        return switch (mode) {
            case "BULK" -> "bulk".equals(source);
            case "PUT" -> "put".equals(source);
            default -> true;
        };
    }
}
