package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;

/**
 * ExecuteQuery: SiteWise's SQL over the views {@code asset}, {@code asset_property}, {@code raw_time_series},
 * {@code latest_value_time_series} and {@code precomputed_aggregates}, run on floci-duck. The views are built from the
 * Region's assets and time series as common table expressions; views listed with commas in a FROM clause are joined
 * on {@code asset_id} and {@code property_id} as SiteWise joins them; and SiteWise's functions that DuckDB spells
 * differently ({@code STR_REPLACE}, {@code DATE_ADD} and the rest, {@code TO_TIMESTAMP}, {@code TO_DATE},
 * {@code TO_TIME}, {@code SUBSTR} with a pattern, {@code CAST ... FORMAT}, {@code STDDEV}, {@code NOW}) are
 * rewritten. Results page through {@code nextToken}.
 */
@ApplicationScoped
public class SiteWiseQuery {

    static final Map<String, List<String[]>> VIEWS = new LinkedHashMap<>();

    static {
        VIEWS.put("asset", columns("asset_id VARCHAR", "asset_name VARCHAR", "asset_description VARCHAR",
                "asset_model_id VARCHAR", "parent_asset_id VARCHAR", "asset_external_id VARCHAR",
                "asset_model_external_id VARCHAR", "hierarchy_id VARCHAR"));
        VIEWS.put("asset_property", columns("asset_id VARCHAR", "property_id VARCHAR", "property_name VARCHAR",
                "property_alias VARCHAR", "property_external_id VARCHAR", "asset_composite_model_id VARCHAR",
                "property_type VARCHAR", "property_data_type VARCHAR", "int_attribute_value INTEGER",
                "double_attribute_value DOUBLE", "boolean_attribute_value BOOLEAN", "string_attribute_value VARCHAR"));
        List<String[]> series = columns("asset_id VARCHAR", "property_id VARCHAR", "property_alias VARCHAR",
                "event_timestamp TIMESTAMP", "quality VARCHAR", "boolean_value BOOLEAN", "int_value INTEGER",
                "double_value DOUBLE", "string_value VARCHAR");
        VIEWS.put("raw_time_series", series);
        VIEWS.put("latest_value_time_series", series);
        VIEWS.put("precomputed_aggregates", columns("asset_id VARCHAR", "property_id VARCHAR",
                "property_alias VARCHAR", "event_timestamp TIMESTAMP", "quality VARCHAR", "resolution VARCHAR",
                "sum_value DOUBLE", "count_value INTEGER", "average_value DOUBLE", "maximum_value DOUBLE",
                "minimum_value DOUBLE", "stdev_value DOUBLE"));
    }

    private static final String MACROS = String.join("\n",
            "CREATE OR REPLACE MACRO sw_add(u, n, t) AS CASE u WHEN 'YEAR' THEN t + to_years(CAST(n AS INTEGER))"
                    + " WHEN 'MONTH' THEN t + to_months(CAST(n AS INTEGER)) WHEN 'DAY' THEN t + to_days(CAST(n AS"
                    + " INTEGER)) WHEN 'HOUR' THEN t + to_hours(CAST(n AS BIGINT)) WHEN 'MINUTE' THEN t +"
                    + " to_minutes(CAST(n AS BIGINT)) ELSE t + to_seconds(CAST(n AS DOUBLE)) END;",
            "CREATE OR REPLACE MACRO sw_now() AS CAST(current_timestamp AS TIMESTAMP);");

    private static final Set<String> CLAUSE_ENDS = Set.of("WHERE", "GROUP", "ORDER", "LIMIT", "HAVING", "UNION",
            "OFFSET", "INTERSECT", "EXCEPT");

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseAssets> assets;
    private final Instance<SiteWiseData> data;
    private final Instance<SiteWiseModels> models;
    private final Instance<FlociDuckClient> duck;

    @Inject
    public SiteWiseQuery(SiteWiseStore store, Instance<SiteWiseAssets> assets, Instance<SiteWiseData> data,
                         Instance<SiteWiseModels> models, Instance<FlociDuckClient> duck) {
        this.store = store;
        this.mapper = store.mapper();
        this.assets = assets;
        this.data = data;
        this.models = models;
        this.duck = duck;
    }

    private final AtomicBoolean warmed = new AtomicBoolean();

    /**
     * Starts floci-duck in the background once SiteWise is in use, so the first ExecuteQuery doesn't wait for the
     * sidecar to start.
     */
    void warm() {
        if (store.config().prestartQueryEngine() && warmed.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                try {
                    duck.get().query("SELECT 1", null);
                } catch (RuntimeException e) {
                    warmed.set(false);
                }
            }, "iotsitewise-duck-warmup");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static List<String[]> columns(String... definitions) {
        List<String[]> columns = new ArrayList<>();
        for (String definition : definitions) {
            columns.add(definition.split(" "));
        }
        return columns;
    }

    ObjectNode executeQuery(ObjectNode in, String region) {
        String account = store.account();
        String statement = in.get("queryStatement").asText().trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1);
        }
        String translated = translate(statement);
        Map<String, String> tables = new LinkedHashMap<>();
        String lower = statement.toLowerCase(Locale.ROOT);
        for (String view : VIEWS.keySet()) {
            if (Pattern.compile("\\b" + view + "\\b").matcher(lower).find()) {
                tables.put(view, table(account, region, view));
            }
        }
        StringBuilder ctes = new StringBuilder();
        for (Map.Entry<String, String> table : tables.entrySet()) {
            ctes.append(ctes.isEmpty() ? "WITH " : ", ").append(table.getKey()).append(" AS (")
                    .append(table.getValue()).append(")");
        }
        String sql = ctes.isEmpty() ? translated : ctes + " " + translated;
        List<Map<String, Object>> described;
        List<Map<String, Object>> rows;
        try {
            described = duck.get().query("DESCRIBE " + sql, MACROS, account);
            rows = duck.get().query(sql, MACROS, account);
        } catch (RuntimeException e) {
            throw new AwsException("ValidationException", "The query isn't valid: "
                    + e.getMessage().replace("floci-duck query error: ", ""), 400);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode columns = out.putArray("columns");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> column : described) {
            String name = String.valueOf(column.get("column_name"));
            names.add(name);
            columns.addObject().put("name", name).putObject("type").put("scalarType",
                    scalar(String.valueOf(column.get("column_type"))));
        }
        int start = SiteWiseStore.start(in.path("nextToken").asText(null), rows.size());
        int max = in.hasNonNull("maxResults") ? in.get("maxResults").asInt() : 100;
        int end = Math.min(rows.size(), start + max);
        ArrayNode list = out.putArray("rows");
        for (Map<String, Object> row : rows.subList(start, end)) {
            ArrayNode datum = list.addObject().putArray("data");
            for (String name : names) {
                Object value = row.get(name);
                if (value == null) {
                    datum.addObject().put("nullValue", true);
                } else {
                    datum.addObject().put("scalarValue", String.valueOf(value));
                }
            }
        }
        if (end < rows.size()) {
            out.put("nextToken", SiteWiseStore.token(end));
        }
        return out;
    }

    private static String scalar(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.startsWith("BOOL")) {
            return "BOOLEAN";
        }
        if (upper.contains("INT")) {
            return "INT";
        }
        if (upper.startsWith("DOUBLE") || upper.startsWith("FLOAT") || upper.startsWith("DECIMAL")
                || upper.startsWith("REAL")) {
            return "DOUBLE";
        }
        if (upper.startsWith("TIMESTAMP") || upper.startsWith("DATE") || upper.startsWith("TIME")) {
            return "TIMESTAMP";
        }
        return "STRING";
    }

    // ---------------------------------------------------------------- views

    private String table(String account, String region, String view) {
        List<String[]> columns = VIEWS.get(view);
        List<List<String>> rows = switch (view) {
            case "asset" -> assetRows(account, region);
            case "asset_property" -> propertyRows(account, region);
            case "raw_time_series" -> seriesRows(account, region, false);
            case "latest_value_time_series" -> seriesRows(account, region, true);
            default -> aggregateRows(account, region);
        };
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append("CAST(").append(rows.isEmpty() ? "NULL" : "c" + i).append(" AS ")
                    .append(columns.get(i)[1]).append(") AS ").append(columns.get(i)[0]);
        }
        if (rows.isEmpty()) {
            return select + " WHERE false";
        }
        select.append(" FROM (VALUES ");
        for (int r = 0; r < rows.size(); r++) {
            select.append(r == 0 ? "(" : ", (");
            List<String> row = rows.get(r);
            for (int i = 0; i < row.size(); i++) {
                select.append(i == 0 ? "" : ", ").append(row.get(i));
            }
            select.append(")");
        }
        select.append(") AS v(");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append("c").append(i);
        }
        return select.append(")").toString();
    }

    static String literal(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private List<List<String>> assetRows(String account, String region) {
        List<List<String>> rows = new ArrayList<>();
        for (ObjectNode asset : assets.get().all(account, region)) {
            ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
            JsonNode parent = meta(asset).path("parent");
            rows.add(List.of(literal(asset.get("assetId").asText()), literal(asset.get("assetName").asText()),
                    literal(asset.path("assetDescription").asText(null)), literal(asset.get("assetModelId").asText()),
                    literal(parent.path("assetId").asText(null)), literal(asset.path("assetExternalId").asText(null)),
                    literal(model == null ? null : model.path("assetModelExternalId").asText(null)),
                    literal(parent.path("hierarchyId").asText(null))));
        }
        return rows;
    }

    private List<List<String>> propertyRows(String account, String region) {
        List<List<String>> rows = new ArrayList<>();
        for (ObjectNode asset : assets.get().all(account, region)) {
            Map<String, String> composites = new LinkedHashMap<>();
            asset.path("assetCompositeModels").forEach(c -> c.path("properties")
                    .forEach(p -> composites.put(p.get("id").asText(), c.get("id").asText())));
            for (ObjectNode property : SiteWiseAssets.properties(asset)) {
                String id = property.get("id").asText();
                ObjectNode definition = assets.get().modelProperty(account, region, asset, id);
                JsonNode type = definition == null ? null : definition.get("type");
                String kind = type == null ? null : type.has("attribute") ? "attribute"
                        : type.has("measurement") ? "measurement" : type.has("transform") ? "transform" : "metric";
                String dataType = property.get("dataType").asText();
                String[] attribute = {"NULL", "NULL", "NULL", "NULL"};
                if ("attribute".equals(kind)) {
                    List<JsonNode> points = data.get().points(account, region,
                            SiteWiseData.associatedId(asset.get("assetId").asText(), id));
                    if (!points.isEmpty()) {
                        JsonNode v = points.get(points.size() - 1).get("v");
                        attribute[0] = v.hasNonNull("integerValue") ? v.get("integerValue").asText() : "NULL";
                        attribute[1] = v.hasNonNull("doubleValue") ? v.get("doubleValue").asText() : "NULL";
                        attribute[2] = v.hasNonNull("booleanValue") ? v.get("booleanValue").asText() : "NULL";
                        attribute[3] = v.hasNonNull("stringValue") ? literal(v.get("stringValue").asText()) : "NULL";
                    }
                }
                rows.add(List.of(literal(asset.get("assetId").asText()), literal(id),
                        literal(property.get("name").asText()), literal(property.path("alias").asText(null)),
                        literal(property.path("externalId").asText(null)), literal(composites.get(id)),
                        literal(kind), literal(dataType), attribute[0], attribute[1], attribute[2], attribute[3]));
            }
        }
        return rows;
    }

    private static String timestamp(JsonNode point) {
        Instant at = Instant.ofEpochSecond(point.get("t").asLong(), point.get("n").asLong());
        return "TIMESTAMP '" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC)
                .format(at) + "'";
    }

    private List<List<String>> seriesRows(String account, String region, boolean latestOnly) {
        List<List<String>> rows = new ArrayList<>();
        for (ObjectNode series : store.list(account, region, SiteWiseStore.TIME_SERIES)) {
            List<JsonNode> points = data.get().points(account, region, series.get("timeSeriesId").asText());
            if (latestOnly && !points.isEmpty()) {
                points = List.of(points.get(points.size() - 1));
            }
            for (JsonNode point : points) {
                JsonNode v = point.get("v");
                rows.add(List.of(literal(series.path("assetId").asText(null)),
                        literal(series.path("propertyId").asText(null)), literal(series.path("alias").asText(null)),
                        timestamp(point), literal(point.path("q").asText("GOOD")),
                        v.hasNonNull("booleanValue") ? v.get("booleanValue").asText() : "NULL",
                        v.hasNonNull("integerValue") ? v.get("integerValue").asText() : "NULL",
                        v.hasNonNull("doubleValue") ? doubleLiteral(v.get("doubleValue").asDouble()) : "NULL",
                        v.hasNonNull("stringValue") ? literal(v.get("stringValue").asText()) : "NULL"));
            }
        }
        return rows;
    }

    private static String doubleLiteral(double value) {
        if (Double.isNaN(value)) {
            return "CAST('NaN' AS DOUBLE)";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "CAST('Infinity' AS DOUBLE)" : "CAST('-Infinity' AS DOUBLE)";
        }
        return Double.toString(value);
    }

    private List<List<String>> aggregateRows(String account, String region) {
        List<List<String>> rows = new ArrayList<>();
        for (ObjectNode series : store.list(account, region, SiteWiseStore.TIME_SERIES)) {
            List<JsonNode> points = data.get().points(account, region, series.get("timeSeriesId").asText());
            for (String resolution : List.of("1m", "15m", "1h", "1d")) {
                long seconds = SiteWiseData.resolutionSeconds(resolution);
                Map<String, List<Double>> windows = new TreeMap<>();
                for (JsonNode point : points) {
                    if (!(SiteWiseData.number(point.get("v")) instanceof Double d)) {
                        continue;
                    }
                    long window = Math.floorDiv(point.get("t").asLong(), seconds) * seconds;
                    windows.computeIfAbsent(window + "|" + point.path("q").asText("GOOD"), k -> new ArrayList<>())
                            .add(d);
                }
                for (Map.Entry<String, List<Double>> window : windows.entrySet()) {
                    long start = Long.parseLong(window.getKey().substring(0, window.getKey().indexOf('|')));
                    String quality = window.getKey().substring(window.getKey().indexOf('|') + 1);
                    List<Double> values = window.getValue();
                    double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                    double mean = sum / values.size();
                    double squares = values.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum();
                    ObjectNode at = mapper.createObjectNode().put("t", start).put("n", 0);
                    rows.add(List.of(literal(series.path("assetId").asText(null)),
                            literal(series.path("propertyId").asText(null)), literal(series.path("alias").asText(null)),
                            timestamp(at), literal(quality), literal(resolution), doubleLiteral(sum),
                            String.valueOf(values.size()), doubleLiteral(mean),
                            doubleLiteral(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)),
                            doubleLiteral(values.stream().mapToDouble(Double::doubleValue).min().orElse(0)),
                            doubleLiteral(values.size() < 2 ? 0 : Math.sqrt(squares / (values.size() - 1)))));
                }
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- translation

    record Token(String kind, String text) {
        boolean is(String word) {
            return ("word".equals(kind) || "symbol".equals(kind)) && text.equalsIgnoreCase(word);
        }
    }

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            int start = i;
            if (Character.isWhitespace(c)) {
                while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token("space", sql.substring(start, i)));
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                tokens.add(new Token("space", " "));
            } else if (c == '\'' || c == '"') {
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == c && i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                        i += 2;
                    } else if (sql.charAt(i) == c) {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                tokens.add(new Token(c == '\'' ? "string" : "quoted", sql.substring(start, i)));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '.' && Character.isDigit(c))) {
                    i++;
                }
                tokens.add(new Token("word", sql.substring(start, i)));
            } else if (i + 1 < sql.length() && List.of("<=", ">=", "<>", "!=", "||").contains(sql.substring(i, i + 2))) {
                i += 2;
                tokens.add(new Token("symbol", sql.substring(start, i)));
            } else {
                i++;
                tokens.add(new Token("symbol", String.valueOf(c)));
            }
        }
        return tokens;
    }

    static String translate(String sql) {
        List<Token> tokens = tokenize(sql);
        tokens = rewriteFunctions(tokens);
        tokens = implicitJoins(tokens);
        StringBuilder out = new StringBuilder();
        tokens.forEach(t -> out.append(t.text()));
        return out.toString();
    }

    private static int close(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is("(")) {
                depth++;
            } else if (tokens.get(i).is(")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AwsException("ValidationException", "The query has an unclosed parenthesis.", 400);
    }

    private static List<List<Token>> arguments(List<Token> tokens, int open, int close) {
        List<List<Token>> arguments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (int i = open + 1; i < close; i++) {
            Token token = tokens.get(i);
            if (token.is("(")) {
                depth++;
            } else if (token.is(")")) {
                depth--;
            }
            if (depth == 0 && token.is(",")) {
                arguments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty() || !arguments.isEmpty()) {
            arguments.add(current);
        }
        return arguments;
    }

    private static String text(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        tokens.forEach(t -> out.append(t.text()));
        return out.toString().trim();
    }

    private static Token singleString(List<Token> argument) {
        List<Token> meaningful = argument.stream().filter(t -> !"space".equals(t.kind())).toList();
        return meaningful.size() == 1 && "string".equals(meaningful.get(0).kind()) ? meaningful.get(0) : null;
    }

    /** A Java date pattern ({@code yyyy-MM-dd HH:mm:ss}) as a strptime format. */
    static String strptime(String pattern) {
        String body = pattern.substring(1, pattern.length() - 1).replace("''", "'");
        String format = body.replace("yyyy", "%Y").replace("MM", "%m").replace("dd", "%d").replace("HH", "%H")
                .replace("mm", "%M").replace("ss", "%S").replace("SSS", "%g").replace("yy", "%y");
        return "'" + format.replace("'", "''") + "'";
    }

    private static List<Token> rewriteFunctions(List<Token> tokens) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int next = i + 1;
            while (next < tokens.size() && "space".equals(tokens.get(next).kind())) {
                next++;
            }
            boolean call = "word".equals(token.kind()) && next < tokens.size() && tokens.get(next).is("(");
            if (!call) {
                out.add(token);
                continue;
            }
            String name = token.text().toUpperCase(Locale.ROOT);
            int close = close(tokens, next);
            List<List<Token>> arguments = arguments(tokens, next, close);
            List<String> rewritten = new ArrayList<>();
            for (List<Token> argument : arguments) {
                rewritten.add(text(rewriteFunctions(argument)));
            }
            String replacement = switch (name) {
                case "STR_REPLACE" -> "replace(" + String.join(", ", rewritten) + ")";
                case "STDDEV" -> "stddev_samp(" + String.join(", ", rewritten) + ")";
                case "NOW" -> "sw_now()";
                case "DATE_ADD", "TIMESTAMP_ADD", "DATE_SUB", "TIMESTAMP_SUB" -> {
                    if (rewritten.size() != 3) {
                        throw new AwsException("ValidationException", name + " takes a unit, an amount and a time.",
                                400);
                    }
                    String unit = rewritten.get(0).toUpperCase(Locale.ROOT);
                    if (!Set.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND").contains(unit)) {
                        throw new AwsException("ValidationException", "The unit " + unit + " isn't YEAR, MONTH, DAY,"
                                + " HOUR, MINUTE or SECOND.", 400);
                    }
                    String amount = name.endsWith("SUB") ? "-(" + rewritten.get(1) + ")" : rewritten.get(1);
                    yield "sw_add('" + unit + "', " + amount + ", CAST(" + rewritten.get(2) + " AS TIMESTAMP))";
                }
                case "TO_TIMESTAMP" -> rewritten.size() == 2
                        ? "strptime(" + rewritten.get(0) + ", " + strptime(rewritten.get(1)) + ")"
                        : "CAST(to_timestamp(CAST(" + rewritten.get(0) + " AS DOUBLE)) AS TIMESTAMP)";
                case "TO_DATE" -> rewritten.size() == 2
                        ? "CAST(strptime(" + rewritten.get(0) + ", " + strptime(rewritten.get(1)) + ") AS DATE)"
                        : "CAST(CASE WHEN typeof(" + rewritten.get(0) + ") LIKE 'TIMESTAMP%' THEN CAST("
                        + rewritten.get(0) + " AS VARCHAR) ELSE CAST(epoch_ms(CAST(" + rewritten.get(0)
                        + " AS BIGINT)) AS VARCHAR) END AS DATE)";
                case "TO_TIME" -> rewritten.size() == 2
                        ? "CAST(strptime(" + rewritten.get(0) + ", " + strptime(rewritten.get(1)) + ") AS TIME)"
                        : "CAST(CASE WHEN typeof(" + rewritten.get(0) + ") LIKE 'TIMESTAMP%' THEN CAST("
                        + rewritten.get(0) + " AS VARCHAR) ELSE CAST(epoch_ms(CAST(" + rewritten.get(0)
                        + " AS BIGINT)) AS VARCHAR) END AS TIMESTAMP)::TIME";
                case "SUBSTR" -> arguments.size() == 2 && singleString(arguments.get(1)) != null
                        ? "regexp_extract(" + rewritten.get(0) + ", " + rewritten.get(1) + ")"
                        : "substr(" + String.join(", ", rewritten) + ")";
                case "CAST" -> cast(arguments.isEmpty() ? List.of() : rewriteFunctions(arguments.get(0)));
                default -> null;
            };
            if (replacement == null) {
                out.add(token);
                for (int j = i + 1; j < next; j++) {
                    out.add(tokens.get(j));
                }
                out.add(new Token("symbol", "("));
                out.add(new Token("raw", String.join(",", rewritten)));
                out.add(new Token("symbol", ")"));
            } else {
                out.add(new Token("raw", replacement));
            }
            i = close;
        }
        return out;
    }

    /** {@code CAST(x AS TIMESTAMP FORMAT 'pattern')} as strptime; other casts as DuckDB writes them. */
    private static String cast(List<Token> inside) {
        String text = text(inside);
        Matcher format = Pattern
                .compile("(?is)(.*)\\s+AS\\s+TIMESTAMP\\s+FORMAT\\s+('(?:[^']|'')*')").matcher(text);
        if (format.matches()) {
            return "strptime(" + format.group(1) + ", " + strptime(format.group(2)) + ")";
        }
        Matcher type = Pattern.compile("(?is)(.*)\\s+AS\\s+(\\w+)").matcher(text);
        if (type.matches()) {
            String target = switch (type.group(2).toUpperCase(Locale.ROOT)) {
                case "STRING", "CHAR", "CHARACTER" -> "VARCHAR";
                case "INT", "INTEGER" -> "INTEGER";
                default -> type.group(2);
            };
            return "CAST(" + type.group(1) + " AS " + target + ")";
        }
        return "CAST(" + text + ")";
    }

    /**
     * Joins views listed with commas in a FROM clause on {@code asset_id}, and on {@code property_id} where both have
     * it, as SiteWise's internal schema joins them.
     */
    private static List<Token> implicitJoins(List<Token> tokens) {
        List<Token> out = new ArrayList<>(tokens);
        for (int i = 0; i < out.size(); i++) {
            if (!out.get(i).is("FROM")) {
                continue;
            }
            int depth = 0;
            int end = out.size();
            boolean explicit = false;
            for (int j = i + 1; j < out.size(); j++) {
                Token token = out.get(j);
                if (token.is("(")) {
                    depth++;
                } else if (token.is(")")) {
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                    depth--;
                } else if (depth == 0 && "word".equals(token.kind())
                        && CLAUSE_ENDS.contains(token.text().toUpperCase(Locale.ROOT))) {
                    end = j;
                    break;
                } else if (depth == 0 && token.is("JOIN")) {
                    explicit = true;
                }
            }
            if (explicit) {
                continue;
            }
            List<List<Token>> items = new ArrayList<>();
            List<Token> current = new ArrayList<>();
            depth = 0;
            for (int j = i + 1; j < end; j++) {
                Token token = out.get(j);
                if (token.is("(")) {
                    depth++;
                } else if (token.is(")")) {
                    depth--;
                }
                if (depth == 0 && token.is(",")) {
                    items.add(current);
                    current = new ArrayList<>();
                } else {
                    current.add(token);
                }
            }
            items.add(current);
            if (items.size() < 2) {
                continue;
            }
            List<String[]> refs = new ArrayList<>();
            for (List<Token> item : items) {
                List<Token> words = item.stream().filter(t -> !"space".equals(t.kind())).toList();
                if (words.isEmpty() || !VIEWS.containsKey(words.get(0).text().toLowerCase(Locale.ROOT))) {
                    refs = null;
                    break;
                }
                String view = words.get(0).text().toLowerCase(Locale.ROOT);
                String alias = words.size() >= 3 && words.get(1).is("AS") ? words.get(2).text()
                        : words.size() >= 2 ? words.get(1).text() : view;
                refs.add(new String[] {view, alias});
            }
            if (refs == null) {
                continue;
            }
            List<String> predicates = new ArrayList<>();
            for (int a = 0; a < refs.size(); a++) {
                for (int b = a + 1; b < refs.size(); b++) {
                    predicates.add(refs.get(a)[1] + ".asset_id = " + refs.get(b)[1] + ".asset_id");
                    if (!"asset".equals(refs.get(a)[0]) && !"asset".equals(refs.get(b)[0])) {
                        predicates.add(refs.get(a)[1] + ".property_id = " + refs.get(b)[1] + ".property_id");
                    }
                }
            }
            String condition = "(" + String.join(" AND ", predicates) + ")";
            if (end < out.size() && out.get(end).is("WHERE")) {
                int whereEnd = out.size();
                depth = 0;
                for (int j = end + 1; j < out.size(); j++) {
                    Token token = out.get(j);
                    if (token.is("(")) {
                        depth++;
                    } else if (token.is(")")) {
                        if (depth == 0) {
                            whereEnd = j;
                            break;
                        }
                        depth--;
                    } else if (depth == 0 && "word".equals(token.kind()) && !token.is("WHERE")
                            && CLAUSE_ENDS.contains(token.text().toUpperCase(Locale.ROOT))) {
                        whereEnd = j;
                        break;
                    }
                }
                out.add(whereEnd, new Token("raw", ") "));
                out.add(end + 1, new Token("raw", " " + condition + " AND ("));
            } else {
                out.add(end, new Token("raw", " WHERE " + condition + " "));
            }
        }
        return out;
    }
}
