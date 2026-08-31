package io.github.hectorvent.floci.services.timestream;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory Amazon Timestream for LiveAnalytics (write + query). JSON 1.0
 * {@code Timestream_20181101.*}. DescribeEndpoints returns an Address that
 * Alchemy's {@code https://} discovery rewrite can reach (the inbound Host
 * when the gateway already speaks TLS; a sidecar TLS port otherwise).
 *
 * WriteRecords ingests the valid subset of a batch and reports out-of-retention
 * timestamps as {@code RejectedRecordsException} (HTTP 419). Query COUNT(*)
 * supports an optional {@code WHERE <dimension> = '<value>'} filter used by
 * Alchemy's RecordsSink tests.
 */
@ApplicationScoped
public class TimestreamService implements Resettable {

    private static final int DEFAULT_MEMORY_HOURS = 6;
    private static final int DEFAULT_MAGNETIC_DAYS = 73_000;
    private static final int ENDPOINT_CACHE_MINUTES = 1_440;
    private static final int MAX_RECORDS_PER_WRITE = 100;
    static final String NOT_ONBOARDED_MESSAGE =
            "Only existing Timestream for LiveAnalytics customers can access this service.";
    private static final String OUT_OF_RETENTION_REASON =
            "The record timestamp is outside the retention period of the memory store and magnetic store writes are not enabled.";
    private static final Pattern COUNT_FROM = Pattern.compile(
            "SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s+(?:AS\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+)?"
                    + "FROM\\s+\"([^\"]+)\"\\s*\\.\\s*\"([^\"]+)\""
                    + "(?:\\s+WHERE\\s+\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*=\\s*'([^']*)')?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static final class Database {
        String name;
        String arn;
        String kmsKeyId;
        long creationTime;
        long lastUpdatedTime;
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, Table> tables = new LinkedHashMap<>();
    }

    static final class Table {
        String databaseName;
        String tableName;
        String arn;
        int memoryStoreRetentionPeriodInHours = DEFAULT_MEMORY_HOURS;
        int magneticStoreRetentionPeriodInDays = DEFAULT_MAGNETIC_DAYS;
        JsonNode magneticStoreWriteProperties;
        JsonNode schema;
        long creationTime;
        long lastUpdatedTime;
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<Map<String, Object>> records = Collections.synchronizedList(new ArrayList<>());
    }

    static final class ScheduledQueryRecord {
        String name;
        String arn;
        String queryString;
        String state = "ENABLED";
        String executionRoleArn;
        String kmsKeyId;
        long creationTime;
        Object scheduleConfiguration;
        Object notificationConfiguration;
        Object targetConfiguration;
        Object errorReportConfiguration;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    private final RegionResolver regionResolver;
    private final TimestreamDiscoveryTls discoveryTls;
    private final ConcurrentHashMap<String, Database> databases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledQueryRecord> scheduledQueriesByName =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledQueryRecord> scheduledQueriesByArn =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> tagsByArn = new ConcurrentHashMap<>();

    @Inject
    public TimestreamService(RegionResolver regionResolver, TimestreamDiscoveryTls discoveryTls) {
        this.regionResolver = regionResolver;
        this.discoveryTls = discoveryTls;
    }

    TimestreamService() {
        this.regionResolver = null;
        this.discoveryTls = null;
    }

    @Override
    public void clear() {
        databases.clear();
        scheduledQueriesByName.clear();
        scheduledQueriesByArn.clear();
        tagsByArn.clear();
    }

    public Map<String, Object> describeEndpoints(String host) {
        String address = discoveryTls != null
                ? discoveryTls.httpsAddress(host)
                : echoHost(host);
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("Address", address);
        endpoint.put("CachePeriodInMinutes", ENDPOINT_CACHE_MINUTES);
        return Map.of("Endpoints", List.of(endpoint));
    }

    private static String echoHost(String host) {
        String address = host == null || host.isBlank() ? "localhost:4566" : host.trim();
        if (address.startsWith("[") && address.contains("]")) {
            int end = address.indexOf(']');
            String ipv6 = address.substring(1, end);
            String rest = address.substring(end + 1);
            address = ipv6 + rest;
        }
        return address;
    }

    public Map<String, Object> createDatabase(JsonNode request, String region) {
        String name = requireName(request, "DatabaseName");
        if (databases.containsKey(name)) {
            throw conflict("The database " + name + " already exists.");
        }
        Database database = new Database();
        database.name = name;
        database.arn = databaseArn(region, name);
        database.kmsKeyId = textOrNull(request, "KmsKeyId");
        if (database.kmsKeyId == null) {
            database.kmsKeyId = "alias/aws/timestream";
        }
        long now = nowSeconds();
        database.creationTime = now;
        database.lastUpdatedTime = now;
        applyTags(database.tags, request.get("Tags"));
        tagsByArn.put(database.arn, database.tags);
        databases.put(name, database);
        return Map.of("Database", databaseView(database));
    }

    public Map<String, Object> describeDatabase(JsonNode request) {
        return Map.of("Database", databaseView(requireDatabase(requireName(request, "DatabaseName"))));
    }

    public Map<String, Object> updateDatabase(JsonNode request) {
        Database database = requireDatabase(requireName(request, "DatabaseName"));
        String kmsKeyId = textOrNull(request, "KmsKeyId");
        if (kmsKeyId != null) {
            database.kmsKeyId = kmsKeyId;
            database.lastUpdatedTime = nowSeconds();
        }
        return Map.of("Database", databaseView(database));
    }

    public Map<String, Object> deleteDatabase(JsonNode request) {
        String name = requireName(request, "DatabaseName");
        Database database = databases.get(name);
        if (database == null) {
            throw notFound("The database " + name + " does not exist.");
        }
        if (!database.tables.isEmpty()) {
            throw invalid("Cannot delete the database until all tables in the database are deleted.");
        }
        databases.remove(name);
        tagsByArn.remove(database.arn);
        return Map.of();
    }

    public Map<String, Object> listDatabases() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Database database : databases.values()) {
            items.add(databaseView(database));
        }
        return Map.of("Databases", items);
    }

    public Map<String, Object> createTable(JsonNode request, String region) {
        String databaseName = requireName(request, "DatabaseName");
        String tableName = requireName(request, "TableName");
        Database database = requireDatabase(databaseName);
        if (database.tables.containsKey(tableName)) {
            throw conflict("The table " + tableName + " already exists in database " + databaseName + ".");
        }
        Table table = new Table();
        table.databaseName = databaseName;
        table.tableName = tableName;
        table.arn = tableArn(region, databaseName, tableName);
        table.creationTime = nowSeconds();
        table.lastUpdatedTime = table.creationTime;
        applyRetention(table, request.get("RetentionProperties"));
        if (request.hasNonNull("MagneticStoreWriteProperties")) {
            table.magneticStoreWriteProperties = request.get("MagneticStoreWriteProperties");
        }
        if (request.hasNonNull("Schema")) {
            table.schema = request.get("Schema");
        }
        applyTags(table.tags, request.get("Tags"));
        tagsByArn.put(table.arn, table.tags);
        database.tables.put(tableName, table);
        database.lastUpdatedTime = table.creationTime;
        return Map.of("Table", tableView(table));
    }

    public Map<String, Object> describeTable(JsonNode request) {
        return Map.of("Table", tableView(requireTable(
                requireName(request, "DatabaseName"),
                requireName(request, "TableName"))));
    }

    public Map<String, Object> updateTable(JsonNode request) {
        Table table = requireTable(
                requireName(request, "DatabaseName"),
                requireName(request, "TableName"));
        if (request.hasNonNull("RetentionProperties")) {
            applyRetention(table, request.get("RetentionProperties"));
        }
        if (request.hasNonNull("MagneticStoreWriteProperties")) {
            table.magneticStoreWriteProperties = request.get("MagneticStoreWriteProperties");
        }
        if (request.hasNonNull("Schema")) {
            table.schema = request.get("Schema");
        }
        table.lastUpdatedTime = nowSeconds();
        return Map.of("Table", tableView(table));
    }

    public Map<String, Object> deleteTable(JsonNode request) {
        String databaseName = requireName(request, "DatabaseName");
        String tableName = requireName(request, "TableName");
        Database database = requireDatabase(databaseName);
        Table table = database.tables.remove(tableName);
        if (table == null) {
            throw notFound("The table " + tableName + " does not exist in database " + databaseName + ".");
        }
        tagsByArn.remove(table.arn);
        database.lastUpdatedTime = nowSeconds();
        return Map.of();
    }

    public Map<String, Object> listTables(JsonNode request) {
        String databaseName = textOrNull(request, "DatabaseName");
        List<Map<String, Object>> items = new ArrayList<>();
        if (databaseName != null) {
            Database database = requireDatabase(databaseName);
            for (Table table : database.tables.values()) {
                items.add(tableView(table));
            }
        } else {
            for (Database database : databases.values()) {
                for (Table table : database.tables.values()) {
                    items.add(tableView(table));
                }
            }
        }
        return Map.of("Tables", items);
    }

    public Map<String, Object> writeRecords(JsonNode request) {
        Table table = requireTable(
                requireName(request, "DatabaseName"),
                requireName(request, "TableName"));
        JsonNode recordsNode = request.get("Records");
        if (recordsNode == null || !recordsNode.isArray() || recordsNode.isEmpty()) {
            throw invalid("Records must contain at least one record.");
        }
        if (recordsNode.size() > MAX_RECORDS_PER_WRITE) {
            throw invalid("A single WriteRecords request can contain at most "
                    + MAX_RECORDS_PER_WRITE + " records.");
        }
        JsonNode common = request.get("CommonAttributes");
        boolean magneticWrites = magneticWritesEnabled(table);
        long nowMillis = System.currentTimeMillis();
        long memoryCutoff = nowMillis - hoursToMillis(table.memoryStoreRetentionPeriodInHours);
        long magneticCutoff = nowMillis - daysToMillis(table.magneticStoreRetentionPeriodInDays);
        List<Map<String, Object>> rejected = new ArrayList<>();
        int memory = 0;
        int magnetic = 0;
        int index = 0;
        for (JsonNode recordNode : recordsNode) {
            Map<String, Object> record = mergeRecord(common, recordNode);
            long timeMillis = recordTimeMillis(record);
            if (timeMillis >= memoryCutoff) {
                table.records.add(record);
                memory++;
            } else if (magneticWrites && timeMillis >= magneticCutoff) {
                table.records.add(record);
                magnetic++;
            } else {
                Map<String, Object> rejection = new LinkedHashMap<>();
                rejection.put("RecordIndex", index);
                rejection.put("Reason", OUT_OF_RETENTION_REASON);
                rejected.add(rejection);
            }
            index++;
        }
        table.lastUpdatedTime = nowSeconds();
        if (!rejected.isEmpty()) {
            throw new AwsException(
                    "RejectedRecordsException",
                    "One or more records have been rejected. See RejectedRecords for details.",
                    419,
                    Map.of("RejectedRecords", rejected));
        }
        Map<String, Object> ingestedCounts = new LinkedHashMap<>();
        ingestedCounts.put("Total", memory + magnetic);
        ingestedCounts.put("MemoryStore", memory);
        ingestedCounts.put("MagneticStore", magnetic);
        return Map.of("RecordsIngested", ingestedCounts);
    }

    public Map<String, Object> query(JsonNode request) {
        String sql = requireText(request, "QueryString");
        Matcher matcher = COUNT_FROM.matcher(sql);
        if (!matcher.find()) {
            throw invalid("Unsupported query: " + sql);
        }
        String alias = matcher.group(1) != null ? matcher.group(1) : "count";
        Table table = requireTable(matcher.group(2), matcher.group(3));
        int count = countRecords(table, matcher.group(4), matcher.group(5));
        Map<String, Object> type = Map.of("ScalarType", "BIGINT");
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("Name", alias);
        column.put("Type", type);
        Map<String, Object> row = Map.of("Data", List.of(Map.of("ScalarValue", String.valueOf(count))));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("QueryId", UUID.randomUUID().toString());
        response.put("Rows", List.of(row));
        response.put("ColumnInfo", List.of(column));
        return response;
    }

    public Map<String, Object> prepareQuery(JsonNode request) {
        String sql = requireText(request, "QueryString");
        Matcher matcher = COUNT_FROM.matcher(sql);
        if (!matcher.find()) {
            throw invalid("Unsupported query: " + sql);
        }
        String alias = matcher.group(1) != null ? matcher.group(1) : "count";
        requireTable(matcher.group(2), matcher.group(3));
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("Name", alias);
        column.put("Type", Map.of("ScalarType", "BIGINT"));
        column.put("Aliased", matcher.group(1) != null);
        column.put("DatabaseName", matcher.group(2));
        column.put("TableName", matcher.group(3));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("QueryString", sql);
        response.put("Columns", List.of(column));
        response.put("Parameters", List.of());
        return response;
    }

    public Map<String, Object> cancelQuery(JsonNode request) {
        requireText(request, "QueryId");
        return Map.of("CancellationMessage", "Successfully cancelled the query");
    }

    public Map<String, Object> createScheduledQuery(JsonNode request, String region) {
        String name = requireName(request, "Name");
        if (scheduledQueriesByName.containsKey(name)) {
            throw conflict("A scheduled query with the name " + name + " already exists.");
        }
        ScheduledQueryRecord query = new ScheduledQueryRecord();
        query.name = name;
        query.arn = scheduledQueryArn(region, name);
        query.queryString = requireText(request, "QueryString");
        query.executionRoleArn = requireText(request, "ScheduledQueryExecutionRoleArn");
        query.kmsKeyId = textOrNull(request, "KmsKeyId");
        query.scheduleConfiguration = jsonValue(requireObject(request, "ScheduleConfiguration"));
        query.notificationConfiguration = jsonValue(requireObject(request, "NotificationConfiguration"));
        query.errorReportConfiguration = jsonValue(requireObject(request, "ErrorReportConfiguration"));
        if (request.hasNonNull("TargetConfiguration")) {
            query.targetConfiguration = jsonValue(request.get("TargetConfiguration"));
        }
        query.creationTime = nowSeconds();
        applyTags(query.tags, request.get("Tags"));
        tagsByArn.put(query.arn, query.tags);
        scheduledQueriesByName.put(name, query);
        scheduledQueriesByArn.put(query.arn, query);
        return Map.of("Arn", query.arn);
    }

    public Map<String, Object> describeScheduledQuery(JsonNode request) {
        return Map.of("ScheduledQuery", scheduledQueryDescription(requireScheduledQuery(request)));
    }

    public Map<String, Object> listScheduledQueries() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScheduledQueryRecord query : scheduledQueriesByName.values()) {
            items.add(scheduledQuerySummary(query));
        }
        return Map.of("ScheduledQueries", items);
    }

    public Map<String, Object> updateScheduledQuery(JsonNode request) {
        ScheduledQueryRecord query = requireScheduledQuery(request);
        String state = requireText(request, "State");
        if (!"ENABLED".equals(state) && !"DISABLED".equals(state)) {
            throw invalid("State must be ENABLED or DISABLED.");
        }
        query.state = state;
        return Map.of();
    }

    public Map<String, Object> deleteScheduledQuery(JsonNode request) {
        ScheduledQueryRecord query = requireScheduledQuery(request);
        scheduledQueriesByArn.remove(query.arn);
        scheduledQueriesByName.remove(query.name);
        tagsByArn.remove(query.arn);
        return Map.of();
    }

    public Map<String, Object> executeScheduledQuery(JsonNode request) {
        requireScheduledQuery(request);
        return Map.of();
    }

    public Map<String, Object> tagResource(JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        Map<String, String> tags = tagsForArn(arn);
        applyTags(tags, request.get("Tags"));
        return Map.of();
    }

    public Map<String, Object> untagResource(JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        Map<String, String> tags = tagsForArn(arn);
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                tags.remove(key.asText());
            }
        }
        return Map.of();
    }

    public Map<String, Object> listTagsForResource(JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        return Map.of("Tags", tagList(tagsForArn(arn)));
    }

    private Map<String, String> tagsForArn(String arn) {
        Map<String, String> tags = tagsByArn.get(arn);
        if (tags == null) {
            throw notFound("The requested resource was not found.");
        }
        return tags;
    }

    private Database requireDatabase(String name) {
        Database database = databases.get(name);
        if (database == null) {
            throw notFound("The database " + name + " does not exist.");
        }
        return database;
    }

    private Table requireTable(String databaseName, String tableName) {
        Database database = requireDatabase(databaseName);
        Table table = database.tables.get(tableName);
        if (table == null) {
            throw notFound("The table " + tableName + " does not exist in database " + databaseName + ".");
        }
        return table;
    }

    private ScheduledQueryRecord requireScheduledQuery(JsonNode request) {
        String arn = requireText(request, "ScheduledQueryArn");
        ScheduledQueryRecord query = scheduledQueriesByArn.get(arn);
        if (query == null) {
            throw notFound("The scheduled query " + arn + " does not exist.");
        }
        return query;
    }

    private Map<String, Object> scheduledQueryDescription(ScheduledQueryRecord query) {
        Map<String, Object> view = scheduledQuerySummary(query);
        view.put("QueryString", query.queryString);
        view.put("ScheduleConfiguration", query.scheduleConfiguration);
        view.put("NotificationConfiguration", query.notificationConfiguration);
        view.put("ScheduledQueryExecutionRoleArn", query.executionRoleArn);
        view.put("ErrorReportConfiguration", query.errorReportConfiguration);
        if (query.targetConfiguration != null) {
            view.put("TargetConfiguration", query.targetConfiguration);
        }
        if (query.kmsKeyId != null) {
            view.put("KmsKeyId", query.kmsKeyId);
        }
        return view;
    }

    private Map<String, Object> scheduledQuerySummary(ScheduledQueryRecord query) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("Arn", query.arn);
        view.put("Name", query.name);
        view.put("CreationTime", query.creationTime);
        view.put("State", query.state);
        if (query.errorReportConfiguration != null) {
            view.put("ErrorReportConfiguration", query.errorReportConfiguration);
        }
        if (query.targetConfiguration instanceof Map<?, ?> target) {
            Object timestream = target.get("TimestreamConfiguration");
            if (timestream instanceof Map<?, ?> config) {
                Map<String, Object> destination = new LinkedHashMap<>();
                Object databaseName = config.get("DatabaseName");
                Object tableName = config.get("TableName");
                if (databaseName != null) {
                    destination.put("DatabaseName", databaseName);
                }
                if (tableName != null) {
                    destination.put("TableName", tableName);
                }
                if (!destination.isEmpty()) {
                    view.put("TargetDestination", Map.of("TimestreamDestination", destination));
                }
            }
        }
        return view;
    }

    private Map<String, Object> databaseView(Database database) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("Arn", database.arn);
        view.put("DatabaseName", database.name);
        view.put("TableCount", database.tables.size());
        view.put("KmsKeyId", database.kmsKeyId);
        view.put("CreationTime", database.creationTime);
        view.put("LastUpdatedTime", database.lastUpdatedTime);
        return view;
    }

    private Map<String, Object> tableView(Table table) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("Arn", table.arn);
        view.put("TableName", table.tableName);
        view.put("DatabaseName", table.databaseName);
        view.put("TableStatus", "ACTIVE");
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("MemoryStoreRetentionPeriodInHours", table.memoryStoreRetentionPeriodInHours);
        retention.put("MagneticStoreRetentionPeriodInDays", table.magneticStoreRetentionPeriodInDays);
        view.put("RetentionProperties", retention);
        view.put("CreationTime", table.creationTime);
        view.put("LastUpdatedTime", table.lastUpdatedTime);
        if (table.magneticStoreWriteProperties != null) {
            view.put("MagneticStoreWriteProperties", table.magneticStoreWriteProperties);
        }
        if (table.schema != null) {
            view.put("Schema", table.schema);
        }
        return view;
    }

    private String databaseArn(String region, String name) {
        return AwsArnUtils.Arn.of("timestream", resolvedRegion(region), accountId(), "database/" + name).toString();
    }

    private String tableArn(String region, String databaseName, String tableName) {
        return AwsArnUtils.Arn.of(
                "timestream",
                resolvedRegion(region),
                accountId(),
                "database/" + databaseName + "/table/" + tableName).toString();
    }

    private String scheduledQueryArn(String region, String name) {
        return AwsArnUtils.Arn.of(
                "timestream", resolvedRegion(region), accountId(), "scheduled-query/" + name)
                .toString();
    }

    private String resolvedRegion(String region) {
        if (region != null && !region.isBlank()) {
            return region;
        }
        return regionResolver != null ? regionResolver.getDefaultRegion() : "us-east-1";
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private static void applyRetention(Table table, JsonNode retention) {
        if (retention == null || retention.isNull() || retention.isMissingNode()) {
            return;
        }
        if (retention.hasNonNull("MemoryStoreRetentionPeriodInHours")) {
            table.memoryStoreRetentionPeriodInHours = retention.get("MemoryStoreRetentionPeriodInHours").asInt();
        }
        if (retention.hasNonNull("MagneticStoreRetentionPeriodInDays")) {
            table.magneticStoreRetentionPeriodInDays = retention.get("MagneticStoreRetentionPeriodInDays").asInt();
        }
    }

    private static void applyTags(Map<String, String> tags, JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return;
        }
        for (JsonNode tag : tagsNode) {
            String key = textOrNull(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = tag.hasNonNull("Value") ? tag.get("Value").asText() : "";
            tags.put(key, value);
        }
    }

    private static List<Map<String, String>> tagList(Map<String, String> tags) {
        List<Map<String, String>> list = new ArrayList<>();
        tags.forEach((key, value) -> {
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("Key", key);
            tag.put("Value", value);
            list.add(tag);
        });
        return list;
    }

    private static Map<String, Object> mergeRecord(JsonNode common, JsonNode record) {
        Map<String, Object> merged = new LinkedHashMap<>();
        copyRecordFields(merged, common);
        copyRecordFields(merged, record);
        if (!merged.containsKey("Time")) {
            merged.put("Time", String.valueOf(System.currentTimeMillis()));
            merged.put("TimeUnit", "MILLISECONDS");
        }
        return merged;
    }

    private static boolean magneticWritesEnabled(Table table) {
        JsonNode properties = table.magneticStoreWriteProperties;
        return properties != null && properties.path("EnableMagneticStoreWrites").asBoolean(false);
    }

    private static long hoursToMillis(int hours) {
        return (long) hours * 3_600_000L;
    }

    private static long daysToMillis(int days) {
        return (long) days * 86_400_000L;
    }

    private static long recordTimeMillis(Map<String, Object> record) {
        Object time = record.get("Time");
        if (time == null) {
            return System.currentTimeMillis();
        }
        long raw;
        try {
            raw = Long.parseLong(String.valueOf(time));
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
        Object unit = record.get("TimeUnit");
        String timeUnit = unit == null ? "MILLISECONDS" : String.valueOf(unit).toUpperCase();
        return switch (timeUnit) {
            case "SECONDS" -> raw * 1_000L;
            case "MICROSECONDS" -> raw / 1_000L;
            case "NANOSECONDS" -> raw / 1_000_000L;
            default -> raw;
        };
    }

    private static int countRecords(Table table, String dimension, String value) {
        if (dimension == null || value == null) {
            return table.records.size();
        }
        int count = 0;
        synchronized (table.records) {
            for (Map<String, Object> record : table.records) {
                if (dimensionEquals(record, dimension, value)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean dimensionEquals(Map<String, Object> record, String name, String value) {
        Object dimensions = record.get("Dimensions");
        if (!(dimensions instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> dim
                    && name.equals(String.valueOf(dim.get("Name")))
                    && value.equals(String.valueOf(dim.get("Value")))) {
                return true;
            }
        }
        return false;
    }

    private static void copyRecordFields(Map<String, Object> target, JsonNode source) {
        if (source == null || source.isNull() || !source.isObject()) {
            return;
        }
        source.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                target.put(entry.getKey(), jsonValue(entry.getValue()));
            }
        });
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : node) {
                values.add(jsonValue(child));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> object.put(entry.getKey(), jsonValue(entry.getValue())));
            return object;
        }
        return node.asText();
    }

    private static String requireName(JsonNode request, String field) {
        String name = requireText(request, field);
        if (name.length() < 3 || name.length() > 256) {
            throw invalid(field + " must be between 3 and 256 characters.");
        }
        return name;
    }

    private static JsonNode requireObject(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field) || !request.get(field).isObject()) {
            throw invalid(field + " is required.");
        }
        return request.get(field);
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException notOnboarded() {
        return new AwsException("AccessDeniedException", NOT_ONBOARDED_MESSAGE, 403);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
