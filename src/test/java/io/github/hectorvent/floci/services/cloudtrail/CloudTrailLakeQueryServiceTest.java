package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudTrailLakeQueryServiceTest {
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regions = new RegionResolver(REGION, "000000000000");
    private final List<Runnable> pending = new ArrayList<>();
    private final List<String> executed = new ArrayList<>();
    private List<Map<String, Object>> rows = List.of();
    private RuntimeException failure;
    private StorageFactory factory;
    private CloudTrailLakeService lake;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        factory = factory(directory);
        lake = service();
    }

    @Test
    void startQueryExecutesCapturedEventsAndPagesResults() {
        String arn = createStore("query-results");
        String storeId = arn.substring(arn.lastIndexOf('/') + 1);
        lake.ingest(event("PutParameter"));
        rows = List.of(row("e1", "PutParameter"), row("e2", "GetParameter"), row("e3", null));

        String queryId = call("StartQuery", request().put("QueryStatement",
                "SELECT eventID, eventName FROM " + storeId + " ORDER BY eventTime")).path("QueryId").asText();
        JsonNode queued = call("DescribeQuery", request().put("QueryId", queryId));
        assertEquals("QUEUED", queued.path("QueryStatus").asText());
        assertEquals(1, queued.path("QueryStatistics").path("EventsScanned").asInt());
        assertTrue(queued.path("QueryStatistics").path("BytesScanned").asLong() > 0);

        pending.removeFirst().run();
        assertEquals(1, executed.size());
        String tableName = "\"eds_" + storeId.replace("-", "") + "\"";
        assertTrue(executed.getFirst().startsWith("WITH " + tableName + " AS (SELECT struct_extract("));
        assertTrue(executed.getFirst().endsWith("SELECT eventID, eventName FROM " + tableName + " ORDER BY eventTime"));
        assertTrue(executed.getFirst().contains("\"eventName\":\"PutParameter\""));

        JsonNode finished = call("DescribeQuery", request().put("QueryId", queryId).put("EventDataStore", arn));
        assertEquals("FINISHED", finished.path("QueryStatus").asText());
        assertTrue(finished.path("QueryStatistics").has("ExecutionTimeInMillis"));

        JsonNode first = call("GetQueryResults", request().put("QueryId", queryId).put("MaxQueryResults", 2));
        assertEquals("FINISHED", first.path("QueryStatus").asText());
        assertEquals(2, first.path("QueryStatistics").path("ResultsCount").asInt());
        assertEquals(3, first.path("QueryStatistics").path("TotalResultsCount").asInt());
        assertEquals("e1", first.path("QueryResultRows").get(0).get(0).path("eventID").asText());
        assertEquals("PutParameter", first.path("QueryResultRows").get(0).get(1).path("eventName").asText());
        JsonNode second = call("GetQueryResults", request().put("QueryId", queryId).put("MaxQueryResults", 2)
                .put("NextToken", first.path("NextToken").asText()));
        assertEquals(1, second.path("QueryResultRows").size());
        assertTrue(second.path("QueryResultRows").get(0).get(1).isEmpty());
        assertFalse(second.has("NextToken"));
        assertError("InvalidNextTokenException", "GetQueryResults",
                request().put("QueryId", queryId).put("NextToken", "bogus"));
        assertError("InvalidMaxResultsException", "GetQueryResults",
                request().put("QueryId", queryId).put("MaxQueryResults", 1001));

        JsonNode listed = call("ListQueries", request().put("EventDataStore", arn).put("QueryStatus", "FINISHED"));
        assertEquals(queryId, listed.path("Queries").get(0).path("QueryId").asText());
        assertError("InvalidQueryStatusException", "ListQueries",
                request().put("EventDataStore", arn).put("QueryStatus", "DONE"));
        assertError("InvalidDateRangeException", "ListQueries",
                request().put("EventDataStore", arn).put("StartTime", 20).put("EndTime", 10));
        assertError("InactiveQueryException", "CancelQuery", request().put("QueryId", queryId));
        assertError("QueryIdNotFoundException", "DescribeQuery", request().put("QueryId", UUID.randomUUID().toString()));
    }

    @Test
    void cancelledQueryIsNotOverwrittenByLateExecution() {
        String arn = createStore("query-cancel");
        String queryId = start(arn);
        JsonNode cancelled = call("CancelQuery", request().put("QueryId", queryId));
        assertEquals("CANCELLED", cancelled.path("QueryStatus").asText());

        pending.removeFirst().run();
        assertTrue(executed.isEmpty());
        assertEquals("CANCELLED", call("DescribeQuery", request().put("QueryId", queryId))
                .path("QueryStatus").asText());
    }

    @Test
    void engineFailureMarksQueryFailedWithEngineMessage() {
        String arn = createStore("query-failure");
        failure = new RuntimeException("floci-duck query error: Binder Error: column missing does not exist");
        String queryId = start(arn);
        pending.removeFirst().run();

        JsonNode described = call("DescribeQuery", request().put("QueryId", queryId));
        assertEquals("FAILED", described.path("QueryStatus").asText());
        assertEquals("Binder Error: column missing does not exist", described.path("ErrorMessage").asText());
        JsonNode results = call("GetQueryResults", request().put("QueryId", queryId));
        assertEquals("FAILED", results.path("QueryStatus").asText());
        assertEquals(0, results.path("QueryResultRows").size());
    }

    @Test
    void startQueryValidatesStoresConcurrencyAndDelivery() {
        String arn = createStore("query-validation");
        String storeId = arn.substring(arn.lastIndexOf('/') + 1);
        assertError("EventDataStoreNotFoundException", "StartQuery", request().put("QueryStatement",
                "SELECT eventID FROM 00000000-0000-0000-0000-000000000000"));
        assertError("InvalidQueryStatementException", "StartQuery", request().put("QueryStatement", "SELECT 1"));
        assertError("InvalidParameterException", "StartQuery", request());
        assertError("UnsupportedOperationException", "StartQuery", request()
                .put("QueryStatement", "SELECT eventID FROM " + storeId).put("DeliveryS3Uri", "s3://bucket/prefix"));

        String deleted = createStore("query-validation-deleted");
        call("DeleteEventDataStore", request().put("EventDataStore", deleted));
        assertError("InactiveEventDataStoreException", "StartQuery",
                request().put("QueryStatement", "SELECT eventID FROM " + deleted));

        for (int i = 0; i < 10; i++) {
            start(arn);
        }
        AwsException limit = assertError("MaxConcurrentQueriesException", "StartQuery",
                request().put("QueryStatement", "SELECT eventID FROM " + storeId));
        assertEquals(429, limit.getHttpStatus());
    }

    @Test
    void queryInterruptedByRestartReportsFailed() {
        String arn = createStore("query-restart");
        String queryId = start(arn);

        CloudTrailLakeService restarted = service();
        JsonNode described = restarted.handle("DescribeQuery", request().put("QueryId", queryId), REGION);
        assertEquals("FAILED", described.path("QueryStatus").asText());
        assertTrue(described.path("ErrorMessage").asText().contains("interrupted"));
    }

    @Test
    void generatedQueryAliasCanBeStartedAndDescribed() {
        String arn = createStore("query-generate");
        ObjectNode generate = request().put("Prompt", "How many events were recorded in the last day?");
        generate.putArray("EventDataStores").add(arn);
        JsonNode generated = call("GenerateQuery", generate);
        String alias = generated.path("QueryAlias").asText();
        assertTrue(generated.path("QueryStatement").asText().startsWith("SELECT COUNT(*) AS eventCount FROM "));

        String queryId = call("StartQuery", request().put("QueryAlias", alias)).path("QueryId").asText();
        JsonNode described = call("DescribeQuery", request().put("QueryAlias", alias));
        assertEquals(queryId, described.path("QueryId").asText());
        assertEquals(generated.path("QueryStatement").asText(), described.path("QueryString").asText());
        assertEquals("How many events were recorded in the last day?", described.path("Prompt").asText());

        ObjectNode unanswerable = request().put("Prompt", "Compose a poem about autumn");
        unanswerable.putArray("EventDataStores").add(arn);
        assertError("GenerateResponseException", "GenerateQuery", unanswerable);
        assertError("InvalidParameterException", "StartQuery", request().put("QueryAlias", "query-unknown"));
    }

    private CloudTrailLakeService service() {
        return new CloudTrailLakeService(factory, regions, mapper, sql -> {
            if (failure != null) {
                throw failure;
            }
            executed.add(sql);
            return rows;
        }, pending::add);
    }

    private String createStore(String name) {
        return call("CreateEventDataStore", request().put("Name", name).put("MultiRegionEnabled", false)
                .put("RetentionPeriod", 7).put("TerminationProtectionEnabled", false))
                .path("EventDataStoreArn").asText();
    }

    private String start(String arn) {
        String storeId = arn.substring(arn.lastIndexOf('/') + 1);
        return call("StartQuery", request().put("QueryStatement", "SELECT eventID FROM " + storeId))
                .path("QueryId").asText();
    }

    private ObjectNode event(String name) {
        ObjectNode event = mapper.createObjectNode();
        event.put("eventID", UUID.randomUUID().toString()).put("eventTime", Instant.now().toString())
                .put("eventName", name).put("eventSource", "ssm.amazonaws.com").put("awsRegion", REGION)
                .put("eventCategory", "Management").put("readOnly", false);
        return event;
    }

    private static Map<String, Object> row(String id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventID", id);
        row.put("eventName", name);
        return row;
    }

    private ObjectNode request() {
        return mapper.createObjectNode();
    }

    private ObjectNode call(String action, ObjectNode request) {
        return lake.handle(action, request, REGION);
    }

    private AwsException assertError(String code, String action, ObjectNode request) {
        AwsException error = assertThrows(AwsException.class, () -> call(action, request));
        assertEquals(code, error.getErrorCode());
        return error;
    }

    private StorageFactory factory(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("cloudtrail")).thenReturn("memory");
        when(access.storageFlushInterval("cloudtrail")).thenReturn(1000L);
        return new StorageFactory(config, access);
    }
}
