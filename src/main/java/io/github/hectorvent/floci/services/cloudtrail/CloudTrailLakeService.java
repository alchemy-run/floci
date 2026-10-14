package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@ApplicationScoped
public class CloudTrailLakeService {
    private static final Logger LOG = Logger.getLogger(CloudTrailLakeService.class);
    private static final int MAX_CONCURRENT_QUERIES = 10;
    private static final int MAX_QUERY_RESULTS = 1000;
    private static final int MAX_STORED_RESULT_ROWS = 100_000;
    private static final long QUERY_RETENTION_MILLIS = 7L * 86400000;
    private static final Set<String> QUERY_STATUSES = Set.of("QUEUED", "RUNNING", "FINISHED", "FAILED",
            "CANCELLED", "TIMED_OUT");
    private static final String DUCK_ERROR_PREFIX = "floci-duck query error: ";
    private static final Set<String> MUTABLE = Set.of("Name", "AdvancedEventSelectors", "MultiRegionEnabled",
            "OrganizationEnabled", "RetentionPeriod", "TerminationProtectionEnabled", "BillingMode", "KmsKeyId");
    private static final Set<String> FIELDS = Set.of("eventCategory", "eventSource", "eventName", "readOnly",
            "resources.type", "resources.ARN", "userIdentity.arn");
    private static final Set<String> OPERATORS = Set.of("Equals", "NotEquals", "StartsWith", "NotStartsWith",
            "EndsWith", "NotEndsWith");
    private final StorageBackend<String, ObjectNode> stores;
    private final StorageBackend<String, ObjectNode> events;
    private final AccountAwareStorageBackend<ObjectNode> queries;
    private final AccountAwareStorageBackend<ObjectNode> generatedQueries;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final SqlEngine engine;
    private final Executor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Executes one translated DuckDB statement and returns its rows as ordered column maps. */
    @FunctionalInterface
    interface SqlEngine {
        List<Map<String, Object>> query(String sql);
    }

    @Inject
    public CloudTrailLakeService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper,
                                 FlociDuckClient duck, Vertx vertx) {
        this(factory, regions, mapper, sql -> duck.query(sql, null),
                task -> vertx.executeBlocking(() -> {
                    task.run();
                    return null;
                }));
    }

    CloudTrailLakeService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper,
                          SqlEngine engine, Executor executor) {
        this.stores = factory.create("cloudtrail", "cloudtrail-event-data-stores.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.events = factory.create("cloudtrail", "cloudtrail-lake-events.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.queries = factory.create("cloudtrail", "cloudtrail-lake-queries.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.generatedQueries = factory.create("cloudtrail", "cloudtrail-lake-generated-queries.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regions = regions;
        this.mapper = mapper;
        this.engine = engine;
        this.executor = executor;
    }

    public synchronized ObjectNode handle(String action, JsonNode request, String region) {
        purgeExpired();
        purgeQueries();
        return switch (action) {
            case "CreateEventDataStore" -> create(request, region);
            case "ListEventDataStores" -> CloudTrailPages.page(mapper, request,
                    regions.getAccountId() + ":" + region + ":stores", "EventDataStores",
                    stores.scan(k -> k.startsWith(region + ":")).stream()
                            .sorted(Comparator.comparing(s -> s.path("EventDataStoreArn").asText()))
                            .map(this::publicStore).toList(), 100, 100, "InvalidMaxResultsException");
            case "GetEventDataStore" -> publicStore(find(request.path("EventDataStore").asText(null), region));
            case "UpdateEventDataStore" -> update(request, region);
            case "DeleteEventDataStore", "RestoreEventDataStore", "StartEventDataStoreIngestion",
                 "StopEventDataStoreIngestion" -> transition(action, request, region);
            case "StartQuery" -> startQuery(request, region);
            case "DescribeQuery" -> describeQuery(request, region);
            case "GetQueryResults" -> getQueryResults(request, region);
            case "ListQueries" -> listQueries(request, region);
            case "CancelQuery" -> cancelQuery(request, region);
            case "GenerateQuery" -> generateQuery(request, region);
            default -> throw unsupported("CloudTrail Lake operation is not implemented: " + action);
        };
    }

    private ObjectNode create(JsonNode request, String region) {
        ObjectNode value = mapper.createObjectNode();
        value.put("Name", request.path("Name").asText(""));
        value.put("MultiRegionEnabled", true).put("OrganizationEnabled", false)
                .put("RetentionPeriod", 366).put("TerminationProtectionEnabled", true)
                .put("BillingMode", "EXTENDABLE_RETENTION_PRICING");
        value.putArray("AdvancedEventSelectors").addObject().putArray("FieldSelectors")
                .addObject().put("Field", "eventCategory").putArray("Equals").add("Management");
        MUTABLE.forEach(field -> { if (request.has(field)) value.set(field, request.get(field).deepCopy()); });
        validate(value);
        requireAvailableName(region, value.path("Name").asText(), null);
        if (request.has("StartIngestion") && !request.path("StartIngestion").isBoolean()) {
            throw invalid("StartIngestion must be a boolean.");
        }
        String arn = regions.buildArn("cloudtrail", region, "eventdatastore/" + UUID.randomUUID());
        double now = System.currentTimeMillis() / 1000.0;
        value.put("EventDataStoreArn", arn).put("CreatedTimestamp", now).put("UpdatedTimestamp", now)
                .put("Status", request.path("StartIngestion").asBoolean(true) ? "ENABLED" : "STOPPED_INGESTION");
        value.set("TagsList", request.has("TagsList") ? request.get("TagsList").deepCopy() : mapper.createArrayNode());
        validateTags(value.path("TagsList"));
        stores.put(key(region, arn), value);
        return value.deepCopy();
    }

    private ObjectNode update(JsonNode request, String region) {
        ObjectNode value = find(request.path("EventDataStore").asText(null), region).deepCopy();
        requireActive(value);
        MUTABLE.forEach(field -> { if (request.has(field)) value.set(field, request.get(field).deepCopy()); });
        validate(value);
        requireAvailableName(region, value.path("Name").asText(), value.path("EventDataStoreArn").asText());
        save(region, value);
        return publicStore(value);
    }

    private ObjectNode transition(String action, JsonNode request, String region) {
        ObjectNode value = find(request.path("EventDataStore").asText(null), region).deepCopy();
        String status = value.path("Status").asText();
        if ("RestoreEventDataStore".equals(action)) {
            if (!"PENDING_DELETION".equals(status)) {
                throw new AwsException("InvalidEventDataStoreStatusException", "Store is not pending deletion.", 400);
            }
            value.put("Status", "STOPPED_INGESTION");
            value.remove("_deleteAfter");
        } else {
            requireActive(value);
            switch (action) {
                case "DeleteEventDataStore" -> {
                    if (value.path("TerminationProtectionEnabled").asBoolean()) {
                        throw new AwsException("EventDataStoreTerminationProtectedException",
                                "Disable termination protection before deleting this event data store.", 400);
                    }
                    value.put("Status", "PENDING_DELETION")
                            .put("_deleteAfter", System.currentTimeMillis() + 7L * 86400000);
                }
                case "StartEventDataStoreIngestion" -> value.put("Status", "ENABLED");
                case "StopEventDataStoreIngestion" -> value.put("Status", "STOPPED_INGESTION");
                default -> throw new IllegalArgumentException(action);
            }
        }
        save(region, value);
        return "RestoreEventDataStore".equals(action) ? publicStore(value) : mapper.createObjectNode();
    }

    private void save(String region, ObjectNode value) {
        value.put("UpdatedTimestamp", System.currentTimeMillis() / 1000.0);
        stores.put(key(region, value.path("EventDataStoreArn").asText()), value);
    }

    private void requireActive(ObjectNode value) {
        if ("PENDING_DELETION".equals(value.path("Status").asText())) {
            throw new AwsException("InactiveEventDataStoreException", "Event data store is pending deletion.", 400);
        }
    }

    private ObjectNode find(String identifier, String region) {
        if (identifier == null || identifier.isBlank()) throw invalid("EventDataStore is required.");
        if (identifier.startsWith("arn:") && !identifier.matches(
                "arn:[^:]+:cloudtrail:[^:]+:[0-9]{12}:eventdatastore/[0-9a-fA-F-]{36}")) {
            throw new AwsException("EventDataStoreARNInvalidException", "Invalid event data store ARN.", 400);
        }
        ObjectNode found = stores.scan(k -> k.startsWith(region + ":")).stream()
                .filter(s -> identifier.equals(s.path("EventDataStoreArn").asText())
                        || identifier.equals(id(s.path("EventDataStoreArn").asText())))
                .findFirst().orElse(null);
        if (found == null) throw new AwsException("EventDataStoreNotFoundException",
                "Event data store not found: " + identifier, 400);
        return found;
    }

    private void requireAvailableName(String region, String name, String ownArn) {
        if (stores.scan(k -> k.startsWith(region + ":")).stream().anyMatch(s ->
                name.equals(s.path("Name").asText()) && !s.path("EventDataStoreArn").asText().equals(ownArn))) {
            throw new AwsException("EventDataStoreAlreadyExistsException", "Event data store name is already in use.", 400);
        }
    }

    private void validate(ObjectNode value) {
        if (!value.path("Name").isTextual() || !value.path("Name").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{1,126}[A-Za-z0-9]")) {
            throw invalid("Name must be 3-128 characters with alphanumeric first and last characters.");
        }
        String billing = value.path("BillingMode").asText();
        if (!Set.of("EXTENDABLE_RETENTION_PRICING", "FIXED_RETENTION_PRICING").contains(billing)) {
            throw invalid("Invalid BillingMode.");
        }
        int retention = value.path("RetentionPeriod").asInt();
        if (!value.path("RetentionPeriod").isIntegralNumber() || !value.path("RetentionPeriod").canConvertToInt() || retention < 7
                || retention > ("FIXED_RETENTION_PRICING".equals(billing) ? 3653 : 2557)) {
            throw invalid("Invalid RetentionPeriod for the billing mode.");
        }
        for (String field : List.of("MultiRegionEnabled", "OrganizationEnabled", "TerminationProtectionEnabled")) {
            if (!value.path(field).isBoolean()) throw invalid(field + " must be a boolean.");
        }
        if (value.path("OrganizationEnabled").asBoolean()) {
            throw unsupported("Organization-wide Lake ingestion is not implemented.");
        }
        if (value.hasNonNull("KmsKeyId")) throw unsupported("KMS-encrypted Lake storage is not implemented.");
        JsonNode selectors = value.path("AdvancedEventSelectors");
        if (!selectors.isArray() || selectors.isEmpty() || selectors.size() > 5) {
            throw new AwsException("InvalidEventSelectorsException", "Provide one to five advanced selectors.", 400);
        }
        for (JsonNode selector : selectors) {
            JsonNode fields = selector.path("FieldSelectors");
            if (!fields.isArray() || fields.isEmpty()) throw invalid("FieldSelectors must not be empty.");
            boolean supportedCategory = false;
            for (JsonNode field : fields) {
                String name = field.path("Field").asText();
                if (!FIELDS.contains(name)) throw unsupported("Unsupported Lake selector field: " + name);
                int operators = 0;
                var names = field.fieldNames();
                while (names.hasNext()) {
                    String operator = names.next();
                    if ("Field".equals(operator)) continue;
                    if (!OPERATORS.contains(operator) || !field.path(operator).isArray() || field.path(operator).isEmpty()) {
                        throw invalid("Invalid selector operator: " + operator);
                    }
                    for (JsonNode operand : field.path(operator)) {
                        if (!operand.isTextual() || operand.asText().isEmpty()) throw invalid("Invalid selector value.");
                    }
                    operators++;
                }
                if (operators == 0) throw invalid("A selector field requires an operator.");
                if ("eventCategory".equals(name)) {
                    JsonNode categories = field.path("Equals");
                    supportedCategory = categories.isArray() && !categories.isEmpty();
                    for (JsonNode category : categories) {
                        if (!"Management".equals(category.asText())) {
                            throw unsupported("Lake currently ingests captured management events only.");
                        }
                    }
                }
            }
            if (!supportedCategory) throw unsupported("Lake selectors must explicitly select Management events.");
        }
    }

    private ObjectNode taggedStore(String arn, String region) {
        purgeExpired();
        try {
            return find(arn, region);
        } catch (AwsException error) {
            if ("EventDataStoreNotFoundException".equals(error.getErrorCode())) {
                throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 400);
            }
            throw error;
        }
    }

    public synchronized Map<String, String> tags(String arn, String region) {
        ObjectNode value = taggedStore(arn, region);
        Map<String, String> result = new java.util.LinkedHashMap<>();
        value.path("TagsList").forEach(t -> result.put(t.path("Key").asText(), t.path("Value").asText("")));
        return result;
    }

    public synchronized void updateTags(String arn, String region, Map<String, String> additions, List<String> removals) {
        ObjectNode value = taggedStore(arn, region).deepCopy();
        requireActive(value);
        Map<String, String> tags = tags(arn, region);
        tags.putAll(additions);
        removals.forEach(tags::remove);
        var array = value.putArray("TagsList");
        tags.forEach((k, v) -> array.addObject().put("Key", k).put("Value", v == null ? "" : v));
        validateTags(array);
        save(region, value);
    }

    private static void validateTags(JsonNode tags) {
        if (!tags.isArray()) throw invalid("TagsList must be an array.");
        if (tags.size() > 50) throw new AwsException("TagsLimitExceededException", "Maximum 50 tags.", 400);
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode tag : tags) {
            if (!tag.path("Key").isTextual() || tag.path("Key").asText().isEmpty()
                    || tag.path("Key").asText().length() > 128 || !keys.add(tag.path("Key").asText())
                    || (tag.has("Value") && (!tag.path("Value").isTextual() || tag.path("Value").asText().length() > 256))) {
                throw new AwsException("InvalidTagParameterException", "Invalid or duplicate tag.", 400);
            }
        }
    }

    public synchronized void ingest(ObjectNode event) {
        purgeExpired();
        for (String key : stores.keys()) {
            ObjectNode store = stores.get(key).orElse(null);
            if (store == null || !"ENABLED".equals(store.path("Status").asText())) continue;
            if (!store.path("MultiRegionEnabled").asBoolean()
                    && !key.startsWith(event.path("awsRegion").asText() + ":")) continue;
            if (matchesSelectors(store.path("AdvancedEventSelectors"), event)) {
                events.put(store.path("EventDataStoreArn").asText() + "/" + event.path("eventID").asText(), event.deepCopy());
            }
        }
    }

    // Collected records remain available while ingestion is stopped; SQL execution is separate.
    synchronized List<ObjectNode> collectedEvents(String arn, String region) {
        purgeExpired();
        find(arn, region);
        return events.scan(k -> k.startsWith(arn + "/")).stream().map(ObjectNode::deepCopy).toList();
    }

    static boolean matchesSelectors(JsonNode selectors, ObjectNode event) {
        for (JsonNode selector : selectors) {
            boolean matches = !selector.path("FieldSelectors").isEmpty();
            for (JsonNode field : selector.path("FieldSelectors")) {
                String path = field.path("Field").asText();
                List<String> values;
                if (path.startsWith("resources.")) {
                    var resources = new java.util.ArrayList<String>();
                    event.path("resources").forEach(r -> {
                        if (r.has(path.substring(10))) resources.add(r.path(path.substring(10)).asText());
                    });
                    values = resources;
                } else if ("userIdentity.arn".equals(path)) {
                    values = event.path("userIdentity").has("arn")
                            ? List.of(event.path("userIdentity").path("arn").asText()) : List.of();
                } else {
                    values = event.has(path) ? List.of(event.path(path).asText()) : List.of();
                }
                if (values.isEmpty()) { matches = false; break; }
                for (String operator : OPERATORS) {
                    if (!field.has(operator)) continue;
                    boolean negative = operator.startsWith("Not");
                    boolean any = false;
                    for (JsonNode operand : field.path(operator)) {
                        String expected = operand.asText();
                        any |= values.stream().anyMatch(v -> operator.endsWith("Equals") ? v.equals(expected)
                                : operator.endsWith("StartsWith") ? v.startsWith(expected) : v.endsWith(expected));
                    }
                    if (negative ? any : !any) matches = false;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        for (String key : stores.keys()) {
            ObjectNode value = stores.get(key).orElse(null);
            if (value == null) continue;
            String arn = value.path("EventDataStoreArn").asText();
            boolean deleted = value.has("_deleteAfter") && value.path("_deleteAfter").asLong() <= now;
            long cutoff = now - value.path("RetentionPeriod").asLong(366) * 86400000;
            for (String eventKey : events.keys()) {
                if (!eventKey.startsWith(arn + "/")) continue;
                ObjectNode event = events.get(eventKey).orElse(null);
                if (deleted || (event != null && Instant.parse(event.path("eventTime").asText()).toEpochMilli() < cutoff)) {
                    events.delete(eventKey);
                }
            }
            if (deleted) stores.delete(key);
        }
    }

    private ObjectNode startQuery(JsonNode request, String region) {
        if (request.hasNonNull("DeliveryS3Uri")) {
            throw unsupported("Delivering CloudTrail Lake query results to S3 is not implemented.");
        }
        String statement = optionalText(request, "QueryStatement");
        String alias = optionalText(request, "QueryAlias");
        String prompt = null;
        if (statement != null && alias != null) {
            throw invalid("Specify either QueryStatement or QueryAlias, not both.");
        }
        if (statement == null) {
            if (alias == null) {
                throw invalid("QueryStatement or QueryAlias is required.");
            }
            ObjectNode generated = generatedQueries.get(region + ":" + alias)
                    .orElseThrow(() -> invalid("No query exists for QueryAlias " + alias + "."));
            statement = generated.path("QueryStatement").asText();
            prompt = generated.path("Prompt").asText(null);
        }
        List<String> parameters = queryParameters(request.get("QueryParameters"));
        long active = 0;
        for (ObjectNode query : queries.scan(k -> k.startsWith(region + ":"))) {
            if (isActive(current(region, query))) {
                active++;
            }
        }
        if (active >= MAX_CONCURRENT_QUERIES) {
            throw new AwsException("MaxConcurrentQueriesException",
                    "You are already running the maximum number of concurrent queries.", 429);
        }

        Map<String, ObjectNode> referenced = new LinkedHashMap<>();
        String translated = CloudTrailLakeSql.translate(statement, parameters, storeId -> {
            ObjectNode store = find(storeId, region);
            requireActive(store);
            referenced.put(storeId, store);
            return tableName(storeId);
        });
        if (referenced.isEmpty()) {
            throw CloudTrailLakeSql.invalidStatement(
                    "The query must reference an event data store ID in its FROM clause.");
        }
        Map<String, String> tables = new LinkedHashMap<>();
        ArrayNode arns = mapper.createArrayNode();
        long scanned = 0;
        long bytes = 0;
        for (Map.Entry<String, ObjectNode> entry : referenced.entrySet()) {
            String arn = entry.getValue().path("EventDataStoreArn").asText();
            arns.add(arn);
            List<ObjectNode> collected = events.scan(k -> k.startsWith(arn + "/"));
            scanned += collected.size();
            for (ObjectNode event : collected) {
                bytes += event.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            tables.put(tableName(entry.getKey()), CloudTrailLakeSql.eventTable(collected, mapper));
        }
        String sql = CloudTrailLakeSql.withEventTables(translated, tables);

        String queryId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ObjectNode query = mapper.createObjectNode();
        query.put("QueryId", queryId).put("QueryString", statement).put("QueryStatus", "QUEUED")
                .put("CreationTime", now / 1000.0).put("EventsScanned", scanned).put("BytesScanned", bytes)
                .put("_createdMillis", now);
        query.set("EventDataStoreArns", arns);
        if (alias != null) {
            query.put("QueryAlias", alias);
        }
        if (prompt != null) {
            query.put("Prompt", prompt);
        }
        String account = queries.accountId();
        String queryKey = region + ":" + queryId;
        queries.put(queryKey, query);
        inFlight.add(account + "/" + queryKey);
        executor.execute(() -> run(account, queryKey, sql));
        return mapper.createObjectNode().put("QueryId", queryId);
    }

    private void run(String account, String queryKey, String sql) {
        long started = System.nanoTime();
        try {
            if (!markRunning(account, queryKey)) {
                return;
            }
            List<Map<String, Object>> rows = engine.query(sql);
            finish(account, queryKey, rows, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.startsWith(DUCK_ERROR_PREFIX)) {
                message = message.substring(DUCK_ERROR_PREFIX.length());
            }
            LOG.debugv("CloudTrail Lake query {0} failed: {1}", queryKey, message);
            fail(account, queryKey, message, (System.nanoTime() - started) / 1_000_000);
        } finally {
            inFlight.remove(account + "/" + queryKey);
        }
    }

    private synchronized boolean markRunning(String account, String queryKey) {
        ObjectNode query = queries.getForAccount(account, queryKey).orElse(null);
        if (query == null || !"QUEUED".equals(query.path("QueryStatus").asText())) {
            return false;
        }
        ObjectNode updated = query.deepCopy().put("QueryStatus", "RUNNING");
        queries.putForAccount(account, queryKey, updated);
        return true;
    }

    private synchronized void finish(String account, String queryKey, List<Map<String, Object>> rows,
                                      long elapsedMillis) {
        ObjectNode query = queries.getForAccount(account, queryKey).orElse(null);
        if (query == null || !"RUNNING".equals(query.path("QueryStatus").asText())) {
            return;
        }
        if (rows.size() > MAX_STORED_RESULT_ROWS) {
            throw new IllegalStateException("The query returned more than " + MAX_STORED_RESULT_ROWS
                    + " rows, which exceeds the emulator result limit. Add a LIMIT clause.");
        }
        ArrayNode resultRows = mapper.createArrayNode();
        for (Map<String, Object> row : rows) {
            ArrayNode columns = resultRows.addArray();
            for (Map.Entry<String, Object> column : row.entrySet()) {
                ObjectNode cell = columns.addObject();
                if (column.getValue() != null) {
                    cell.put(column.getKey(), CloudTrailLakeSql.render(column.getValue()));
                }
            }
        }
        ObjectNode updated = query.deepCopy();
        updated.put("QueryStatus", "FINISHED").put("ExecutionTimeInMillis", elapsedMillis)
                .put("TotalResultsCount", rows.size());
        updated.set("QueryResultRows", resultRows);
        queries.putForAccount(account, queryKey, updated);
    }

    private synchronized void fail(String account, String queryKey, String message, long elapsedMillis) {
        ObjectNode query = queries.getForAccount(account, queryKey).orElse(null);
        if (query == null || !isActive(query)) {
            return;
        }
        ObjectNode updated = query.deepCopy();
        updated.put("QueryStatus", "FAILED").put("ErrorMessage", message).put("ExecutionTimeInMillis", elapsedMillis);
        queries.putForAccount(account, queryKey, updated);
    }

    private ObjectNode describeQuery(JsonNode request, String region) {
        ObjectNode query = requestedQuery(request, region, true);
        ObjectNode response = mapper.createObjectNode();
        response.put("QueryId", query.path("QueryId").asText())
                .put("QueryString", query.path("QueryString").asText())
                .put("QueryStatus", query.path("QueryStatus").asText());
        ObjectNode statistics = response.putObject("QueryStatistics");
        statistics.put("EventsScanned", query.path("EventsScanned").asLong())
                .put("BytesScanned", query.path("BytesScanned").asLong())
                .put("CreationTime", query.path("CreationTime").asDouble());
        if (query.has("ExecutionTimeInMillis")) {
            statistics.put("ExecutionTimeInMillis", query.path("ExecutionTimeInMillis").asLong());
        }
        if (query.has("ErrorMessage")) {
            response.put("ErrorMessage", query.path("ErrorMessage").asText());
        }
        if (query.has("Prompt")) {
            response.put("Prompt", query.path("Prompt").asText());
        }
        return response;
    }

    private ObjectNode getQueryResults(JsonNode request, String region) {
        ObjectNode query = requestedQuery(request, region, false);
        int maximum = MAX_QUERY_RESULTS;
        if (request.has("MaxQueryResults")) {
            JsonNode value = request.path("MaxQueryResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1
                    || value.asInt() > MAX_QUERY_RESULTS) {
                throw new AwsException("InvalidMaxResultsException",
                        "MaxQueryResults must be between 1 and " + MAX_QUERY_RESULTS + ".", 400);
            }
            maximum = value.asInt();
        }
        String queryId = query.path("QueryId").asText();
        JsonNode rows = query.path("QueryResultRows");
        int total = rows.isArray() ? rows.size() : 0;
        int start = 0;
        if (request.hasNonNull("NextToken")) {
            start = resultOffset(request.path("NextToken").asText(), queryId, total);
        }
        int end = Math.min(start + maximum, total);
        ObjectNode response = mapper.createObjectNode();
        response.put("QueryStatus", query.path("QueryStatus").asText());
        ObjectNode statistics = response.putObject("QueryStatistics");
        statistics.put("ResultsCount", end - start).put("TotalResultsCount", total)
                .put("BytesScanned", query.path("BytesScanned").asLong());
        ArrayNode page = response.putArray("QueryResultRows");
        for (int i = start; i < end; i++) {
            page.add(rows.get(i).deepCopy());
        }
        if (end < total) {
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((queryId + ":" + end).getBytes(StandardCharsets.UTF_8)));
        }
        if (query.has("ErrorMessage")) {
            response.put("ErrorMessage", query.path("ErrorMessage").asText());
        }
        return response;
    }

    private static int resultOffset(String token, String queryId, int total) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            int offset = Integer.parseInt(decoded.substring(separator + 1));
            if (separator < 0 || !queryId.equals(decoded.substring(0, separator)) || offset <= 0 || offset >= total) {
                throw new IllegalArgumentException("Token does not address a page of this query.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidNextTokenException", "Invalid NextToken for this query.", 400);
        }
    }

    private ObjectNode listQueries(JsonNode request, String region) {
        ObjectNode store = find(request.path("EventDataStore").asText(null), region);
        requireActive(store);
        String arn = store.path("EventDataStoreArn").asText();
        double from = timeBound(request, "StartTime", Double.NEGATIVE_INFINITY);
        double to = timeBound(request, "EndTime", Double.POSITIVE_INFINITY);
        if (from > to) {
            throw new AwsException("InvalidDateRangeException", "StartTime must not be after EndTime.", 400);
        }
        String status = optionalText(request, "QueryStatus");
        if (status != null && !QUERY_STATUSES.contains(status)) {
            throw new AwsException("InvalidQueryStatusException", "Invalid QueryStatus: " + status, 400);
        }
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode stored : queries.scan(k -> k.startsWith(region + ":"))) {
            ObjectNode query = current(region, stored);
            double created = query.path("CreationTime").asDouble();
            if (!queriesStore(query, arn) || created < from || created > to
                    || (status != null && !status.equals(query.path("QueryStatus").asText()))) {
                continue;
            }
            matching.add(mapper.createObjectNode().put("QueryId", query.path("QueryId").asText())
                    .put("QueryStatus", query.path("QueryStatus").asText()).put("CreationTime", created));
        }
        matching.sort(Comparator.<ObjectNode>comparingDouble(q -> q.path("CreationTime").asDouble()).reversed()
                .thenComparing(q -> q.path("QueryId").asText()));
        return CloudTrailPages.page(mapper, request, queries.accountId() + ":" + region + ":queries:" + arn,
                "Queries", matching, MAX_QUERY_RESULTS, MAX_QUERY_RESULTS, "InvalidMaxResultsException");
    }

    private ObjectNode cancelQuery(JsonNode request, String region) {
        ObjectNode query = requestedQuery(request, region, false);
        String queryId = query.path("QueryId").asText();
        if (!isActive(query)) {
            throw new AwsException("InactiveQueryException",
                    "Query " + queryId + " is " + query.path("QueryStatus").asText() + " and cannot be cancelled.", 400);
        }
        ObjectNode updated = query.deepCopy().put("QueryStatus", "CANCELLED");
        queries.put(region + ":" + queryId, updated);
        return mapper.createObjectNode().put("QueryId", queryId).put("QueryStatus", "CANCELLED");
    }

    private ObjectNode generateQuery(JsonNode request, String region) {
        JsonNode requested = request.path("EventDataStores");
        if (!requested.isArray() || requested.size() != 1 || !requested.get(0).isTextual()) {
            throw invalid("EventDataStores must contain exactly one event data store.");
        }
        String prompt = optionalText(request, "Prompt");
        if (prompt == null || prompt.length() < 3 || prompt.length() > 500) {
            throw invalid("Prompt must be between 3 and 500 characters.");
        }
        ObjectNode store = find(requested.get(0).asText(), region);
        requireActive(store);
        String arn = store.path("EventDataStoreArn").asText();
        String statement = CloudTrailLakeQueryGenerator.generate(prompt, id(arn))
                .orElseThrow(() -> new AwsException("GenerateResponseException",
                        "A query could not be generated for the prompt. Rephrase it as a question about the"
                                + " events recorded in the event data store.", 400));
        String alias = "query-" + UUID.randomUUID();
        ObjectNode generated = mapper.createObjectNode().put("QueryStatement", statement).put("Prompt", prompt)
                .put("EventDataStoreArn", arn).put("_createdMillis", System.currentTimeMillis());
        generatedQueries.put(region + ":" + alias, generated);
        return mapper.createObjectNode().put("QueryStatement", statement).put("QueryAlias", alias);
    }

    private ObjectNode requestedQuery(JsonNode request, String region, boolean allowAlias) {
        String queryId = optionalText(request, "QueryId");
        ObjectNode query;
        if (queryId == null) {
            String alias = allowAlias ? optionalText(request, "QueryAlias") : null;
            if (alias == null) {
                throw invalid("QueryId is required.");
            }
            query = queries.scan(k -> k.startsWith(region + ":")).stream()
                    .filter(q -> alias.equals(q.path("QueryAlias").asText(null)))
                    .max(Comparator.comparingDouble(q -> q.path("CreationTime").asDouble()))
                    .orElseThrow(() -> queryNotFound(alias));
        } else {
            query = queries.get(region + ":" + queryId).orElseThrow(() -> queryNotFound(queryId));
        }
        if (request.hasNonNull("EventDataStore")) {
            ObjectNode store = find(request.path("EventDataStore").asText(), region);
            if (!queriesStore(query, store.path("EventDataStoreArn").asText())) {
                throw queryNotFound(query.path("QueryId").asText());
            }
        }
        return current(region, query);
    }

    // A query persisted as active but not executing in this process was cut off by a restart.
    private ObjectNode current(String region, ObjectNode query) {
        String queryKey = region + ":" + query.path("QueryId").asText();
        if (isActive(query) && !inFlight.contains(queries.accountId() + "/" + queryKey)) {
            ObjectNode updated = query.deepCopy();
            updated.put("QueryStatus", "FAILED")
                    .put("ErrorMessage", "The query was interrupted because the emulator restarted before it completed.");
            queries.put(queryKey, updated);
            return updated;
        }
        return query;
    }

    private void purgeQueries() {
        long cutoff = System.currentTimeMillis() - QUERY_RETENTION_MILLIS;
        for (AccountAwareStorageBackend<ObjectNode> backend : List.of(queries, generatedQueries)) {
            for (String key : backend.keys()) {
                ObjectNode value = backend.get(key).orElse(null);
                if (value != null && value.path("_createdMillis").asLong() < cutoff && !isActive(value)) {
                    backend.delete(key);
                }
            }
        }
    }

    private static boolean isActive(ObjectNode query) {
        String status = query.path("QueryStatus").asText();
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    private static boolean queriesStore(ObjectNode query, String arn) {
        for (JsonNode referenced : query.path("EventDataStoreArns")) {
            if (arn.equals(referenced.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> queryParameters(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.isEmpty() || node.size() > CloudTrailLakeSql.MAX_PARAMETERS) {
            throw invalid("QueryParameters must contain between 1 and " + CloudTrailLakeSql.MAX_PARAMETERS
                    + " values.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isEmpty()
                    || value.asText().length() > CloudTrailLakeSql.MAX_PARAMETER_LENGTH) {
                throw invalid("Each QueryParameters value must be a string of 1 to "
                        + CloudTrailLakeSql.MAX_PARAMETER_LENGTH + " characters.");
            }
            values.add(value.asText());
        }
        return values;
    }

    private static double timeBound(JsonNode request, String field, double fallback) {
        if (!request.has(field)) {
            return fallback;
        }
        JsonNode value = request.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw invalid(field + " must be an epoch timestamp.");
        }
        return value.asDouble();
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " must be a non-empty string.");
        }
        return value.asText();
    }

    private static String tableName(String storeId) {
        return CloudTrailLakeSql.quoteIdentifier("eds_" + storeId.replace("-", ""));
    }

    private static AwsException queryNotFound(String queryId) {
        return new AwsException("QueryIdNotFoundException", "Query not found: " + queryId, 404);
    }

    private ObjectNode publicStore(ObjectNode store) {
        ObjectNode copy = store.deepCopy();
        copy.remove(List.of("TagsList", "_deleteAfter"));
        return copy;
    }

    private static String id(String arn) { return arn.substring(arn.lastIndexOf('/') + 1); }
    private static String key(String region, String arn) { return region + ":" + id(arn); }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
    private static AwsException unsupported(String message) { return new AwsException("UnsupportedOperationException", message, 400); }
}
