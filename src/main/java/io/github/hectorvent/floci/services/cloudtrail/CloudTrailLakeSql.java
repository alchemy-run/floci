package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates CloudTrail Lake SQL into DuckDB SQL over captured event records.
 *
 * <p>Lake names an event data store by its ID (or ARN) in the FROM clause. Each such reference is
 * rewritten to a common table expression that exposes the store's collected events with the Lake
 * event schema: camelCase top-level columns, lowercase struct fields, and string maps for request
 * parameters and response elements.
 */
final class CloudTrailLakeSql {
    static final int MAX_STATEMENT_LENGTH = 10000;
    static final int MAX_PARAMETER_LENGTH = 1024;
    static final int MAX_PARAMETERS = 10;

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ARN = Pattern.compile(
            "arn:aws[a-z-]*:cloudtrail:[a-z0-9-]+:[0-9]{12}:eventdatastore/(" + UUID_PATTERN + ")");
    private static final Pattern ID = Pattern.compile(UUID_PATTERN);
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    // Trino functions used by Lake that DuckDB names differently but calls with the same arguments.
    private static final Map<String, String> FUNCTIONS = Map.of(
            "approx_distinct", "approx_count_distinct",
            "json_extract_scalar", "json_extract_string");
    private static final List<String> STRING_COLUMNS = List.of("eventVersion", "eventSource", "eventName",
            "awsRegion", "sourceIPAddress", "userAgent", "errorCode", "errorMessage", "requestID", "eventID",
            "eventType", "apiVersion", "recipientAccountId", "sharedEventID", "annotation", "vpcEndpointId",
            "serviceEventDetails", "eventCategory");
    private static final List<String> MAP_COLUMNS = List.of("requestParameters", "responseElements",
            "additionalEventData");
    private static final List<String> USER_IDENTITY_FIELDS = List.of("type", "principalId", "arn", "accountId",
            "accessKeyId", "userName", "invokedBy");
    private static final List<String> COLUMNS = List.of("eventVersion", "userIdentity", "eventTime",
            "eventSource", "eventName", "awsRegion", "sourceIPAddress", "userAgent", "errorCode", "errorMessage",
            "requestParameters", "responseElements", "additionalEventData", "requestID", "eventID", "readOnly",
            "resources", "eventType", "apiVersion", "managementEvent", "recipientAccountId", "sharedEventID",
            "annotation", "vpcEndpointId", "serviceEventDetails", "eventCategory");
    private static final String STRUCTURE = "[{"
            + "\"eventVersion\":\"VARCHAR\","
            + "\"userIdentity\":{\"type\":\"VARCHAR\",\"principalid\":\"VARCHAR\",\"arn\":\"VARCHAR\","
            + "\"accountid\":\"VARCHAR\",\"accesskeyid\":\"VARCHAR\",\"username\":\"VARCHAR\","
            + "\"invokedby\":\"VARCHAR\"},"
            + "\"eventTime\":\"TIMESTAMP\","
            + "\"eventSource\":\"VARCHAR\",\"eventName\":\"VARCHAR\",\"awsRegion\":\"VARCHAR\","
            + "\"sourceIPAddress\":\"VARCHAR\",\"userAgent\":\"VARCHAR\",\"errorCode\":\"VARCHAR\","
            + "\"errorMessage\":\"VARCHAR\","
            + "\"requestParameters\":\"MAP(VARCHAR, VARCHAR)\",\"responseElements\":\"MAP(VARCHAR, VARCHAR)\","
            + "\"additionalEventData\":\"MAP(VARCHAR, VARCHAR)\","
            + "\"requestID\":\"VARCHAR\",\"eventID\":\"VARCHAR\",\"readOnly\":\"BOOLEAN\","
            + "\"resources\":[{\"accountid\":\"VARCHAR\",\"type\":\"VARCHAR\",\"arn\":\"VARCHAR\","
            + "\"arnprefix\":\"VARCHAR\"}],"
            + "\"eventType\":\"VARCHAR\",\"apiVersion\":\"VARCHAR\",\"managementEvent\":\"BOOLEAN\","
            + "\"recipientAccountId\":\"VARCHAR\",\"sharedEventID\":\"VARCHAR\",\"annotation\":\"VARCHAR\","
            + "\"vpcEndpointId\":\"VARCHAR\",\"serviceEventDetails\":\"VARCHAR\",\"eventCategory\":\"VARCHAR\""
            + "}]";

    private CloudTrailLakeSql() {}

    /**
     * Validates a Lake statement, binds its {@code ?} placeholders, and rewrites every event data
     * store reference through {@code resolver}, which receives the store ID and returns the SQL
     * identifier to use in its place.
     */
    static String translate(String statement, List<String> parameters, Function<String, String> resolver) {
        if (statement == null || statement.isBlank()) {
            throw invalidStatement("QueryStatement must not be empty.");
        }
        if (statement.length() > MAX_STATEMENT_LENGTH) {
            throw invalidStatement("QueryStatement must not exceed " + MAX_STATEMENT_LENGTH + " characters.");
        }
        String keyword = leadingKeyword(statement, true);
        if (!"SELECT".equals(keyword) && !"WITH".equals(keyword)) {
            throw invalidStatement("Only SELECT statements are supported in CloudTrail Lake queries.");
        }
        StringBuilder out = new StringBuilder(statement.length() + 64);
        int n = statement.length();
        int parameter = 0;
        boolean terminated = false;
        int i = 0;
        while (i < n) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && statement.charAt(i + 1) == '-') {
                int end = statement.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(statement, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && statement.charAt(i + 1) == '*') {
                int end = statement.indexOf("*/", i + 2);
                if (end < 0) {
                    throw invalidStatement("Unterminated comment in QueryStatement.");
                }
                out.append(statement, i, end + 2);
                i = end + 2;
                continue;
            }
            if (terminated) {
                throw invalidStatement("QueryStatement must contain exactly one SQL statement.");
            }
            if (c == ';') {
                terminated = true;
                i++;
                continue;
            }
            if (c == '\'') {
                int end = endOfQuoted(statement, i, '\'');
                out.append(statement, i, end);
                i = end;
                continue;
            }
            if (c == '"' || c == '`') {
                int end = endOfQuoted(statement, i, c);
                String doubled = String.valueOf(c) + c;
                String content = statement.substring(i + 1, end - 1).replace(doubled, String.valueOf(c));
                String store = storeId(content);
                out.append(store != null ? resolver.apply(store) : quoteIdentifier(content));
                i = end;
                continue;
            }
            if (c == '?') {
                if (parameter >= parameters.size()) {
                    throw new AwsException("InvalidParameterException",
                            "QueryParameters must supply a value for every ? placeholder.", 400);
                }
                out.append(literal(parameters.get(parameter++)));
                i++;
                continue;
            }
            boolean wordStart = i == 0 || !isWordChar(statement.charAt(i - 1));
            if (wordStart && Character.isLetterOrDigit(c)) {
                int end = matchReference(statement, i);
                if (end > 0) {
                    out.append(resolver.apply(storeId(statement.substring(i, end))));
                    i = end;
                    continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < n && isWordChar(statement.charAt(end))) {
                    end++;
                }
                String word = statement.substring(i, end);
                String renamed = FUNCTIONS.get(word.toLowerCase(Locale.ROOT));
                out.append(renamed != null && nextNonSpace(statement, end) == '(' ? renamed : word);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        if (parameter != parameters.size()) {
            throw new AwsException("InvalidParameterException",
                    "QueryParameters has more values than the statement has ? placeholders.", 400);
        }
        return out.toString();
    }

    /** Prepends one common table expression per referenced store to a translated statement. */
    static String withEventTables(String sql, Map<String, String> tables) {
        StringBuilder ctes = new StringBuilder();
        for (Map.Entry<String, String> table : tables.entrySet()) {
            if (!ctes.isEmpty()) {
                ctes.append(", ");
            }
            ctes.append(table.getKey()).append(" AS (").append(table.getValue()).append(")");
        }
        int start = skipSpaceAndComments(sql, 0);
        if ("WITH".equals(leadingKeyword(sql, false))) {
            int after = skipSpaceAndComments(sql, start + 4);
            String header = "WITH ";
            int wordEnd = after;
            while (wordEnd < sql.length() && isWordChar(sql.charAt(wordEnd))) {
                wordEnd++;
            }
            if ("RECURSIVE".equalsIgnoreCase(sql.substring(after, wordEnd))) {
                header = "WITH RECURSIVE ";
                after = skipSpaceAndComments(sql, wordEnd);
            }
            return header + ctes + ", " + sql.substring(after);
        }
        return "WITH " + ctes + " " + sql.substring(start);
    }

    /** DuckDB SELECT producing the Lake event schema from the given captured event records. */
    static String eventTable(List<ObjectNode> events, ObjectMapper mapper) {
        ArrayNode rows = mapper.createArrayNode();
        for (ObjectNode event : events) {
            rows.add(eventRow(event, mapper));
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < COLUMNS.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String column = COLUMNS.get(i);
            sql.append("struct_extract(r, '").append(column).append("') AS ").append(quoteIdentifier(column));
        }
        sql.append(" FROM (SELECT unnest(from_json(").append(literal(rows.toString())).append(", ")
                .append(literal(STRUCTURE)).append(")) AS r)");
        return sql.toString();
    }

    static ObjectNode eventRow(ObjectNode event, ObjectMapper mapper) {
        ObjectNode row = mapper.createObjectNode();
        for (String column : STRING_COLUMNS) {
            JsonNode value = event.get(column);
            if (value != null && !value.isNull()) {
                row.put(column, value.isValueNode() ? value.asText() : value.toString());
            }
        }
        row.put("eventTime", eventTime(event.path("eventTime").asText()));
        if (event.has("readOnly")) {
            row.put("readOnly", event.path("readOnly").asText().equalsIgnoreCase("true"));
        }
        if (event.has("managementEvent")) {
            row.put("managementEvent", event.path("managementEvent").asText().equalsIgnoreCase("true"));
        }
        JsonNode identity = event.path("userIdentity");
        if (identity.isObject()) {
            ObjectNode user = row.putObject("userIdentity");
            for (String field : USER_IDENTITY_FIELDS) {
                if (identity.hasNonNull(field)) {
                    user.put(field.toLowerCase(Locale.ROOT), identity.get(field).asText());
                }
            }
        }
        for (String column : MAP_COLUMNS) {
            JsonNode value = event.get(column);
            if (value != null && value.isObject()) {
                ObjectNode map = row.putObject(column);
                Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!field.getValue().isNull()) {
                        map.put(field.getKey(), field.getValue().isValueNode()
                                ? field.getValue().asText() : field.getValue().toString());
                    }
                }
            }
        }
        JsonNode resources = event.path("resources");
        if (resources.isArray()) {
            ArrayNode list = row.putArray("resources");
            for (JsonNode resource : resources) {
                ObjectNode entry = list.addObject();
                for (String field : List.of("accountId", "type", "ARN", "arnPrefix")) {
                    if (resource.hasNonNull(field)) {
                        entry.put(field.toLowerCase(Locale.ROOT), resource.get(field).asText());
                    }
                }
            }
        }
        return row;
    }

    /** Renders a DuckDB result value as the string CloudTrail Lake returns for it. */
    static String render(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > 1) {
                    out.append(", ");
                }
                out.append(entry.getKey()).append('=')
                        .append(entry.getValue() == null ? "null" : render(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) {
                if (out.length() > 1) {
                    out.append(", ");
                }
                out.append(item == null ? "null" : render(item));
            }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String eventTime(String value) {
        try {
            return EVENT_TIME.format(Instant.parse(value));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Captured CloudTrail event has an invalid eventTime: " + value, e);
        }
    }

    private static String storeId(String token) {
        Matcher arn = ARN.matcher(token);
        if (arn.matches()) {
            return arn.group(1).toLowerCase(Locale.ROOT);
        }
        return ID.matcher(token).matches() ? token.toLowerCase(Locale.ROOT) : null;
    }

    private static int matchReference(String sql, int start) {
        for (Pattern pattern : List.of(ARN, ID)) {
            Matcher matcher = pattern.matcher(sql).region(start, sql.length());
            if (matcher.lookingAt()) {
                int end = matcher.end();
                if (end == sql.length() || !(isWordChar(sql.charAt(end)) || sql.charAt(end) == '-')) {
                    return end;
                }
            }
        }
        return -1;
    }

    private static int endOfQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw invalidStatement("Unterminated quoted text in QueryStatement.");
    }

    private static String leadingKeyword(String sql, boolean skipParentheses) {
        int i = skipSpaceAndComments(sql, 0);
        while (skipParentheses && i < sql.length() && sql.charAt(i) == '(') {
            i = skipSpaceAndComments(sql, i + 1);
        }
        int end = i;
        while (end < sql.length() && isWordChar(sql.charAt(end))) {
            end++;
        }
        return sql.substring(i, end).toUpperCase(Locale.ROOT);
    }

    private static int skipSpaceAndComments(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end + 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static char nextNonSpace(String sql, int start) {
        int i = start;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i < sql.length() ? sql.charAt(i) : 0;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static AwsException invalidStatement(String message) {
        return new AwsException("InvalidQueryStatementException", message, 400);
    }
}
