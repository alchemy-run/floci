package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RumServiceTest {

    private static final String REGION = "us-east-1";
    private static final RegionResolver REGION_RESOLVER = new RegionResolver(REGION, "000000000000");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RumService service = new RumService(new InMemoryStorage<>(), REGION_RESOLVER);

    @Test
    void ownerAccountIsCapturedButNeverSerializedIntoTheApiBody() {
        AppMonitor monitor = service.createAppMonitor(REGION,
                objectMapper.createObjectNode().put("Name", "monitor").put("Domain", "example.com"));

        assertEquals("000000000000", monitor.getOwnerAccountId());
        JsonNode serialized = objectMapper.valueToTree(monitor);
        serialized.fieldNames().forEachRemaining(field ->
                assertTrue(!field.toLowerCase().contains("account"),
                        "GetAppMonitor body must not expose the owner account: " + serialized));
    }

    @Test
    void updateAppMonitorAtomicallyReplacesTheStoredSnapshot() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com","CwLogEnabled":false}
                """));

        service.updateAppMonitor(REGION, "monitor", request("""
                {
                  "Domain":"new.example.com",
                  "AppMonitorConfiguration":{"AllowCookies":true,"SessionSampleRate":0.5},
                  "CwLogEnabled":true,
                  "CustomEvents":{"Status":"ENABLED"}
                }
                """));

        AppMonitor updated = service.getAppMonitor(REGION, "monitor");
        assertNotSame(original, updated);
        assertEquals("old.example.com", original.getDomain());
        assertEquals("new.example.com", updated.getDomain());
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getName(), updated.getName());
        assertEquals(original.getState(), updated.getState());
        assertEquals("Web", updated.getPlatform());
        assertEquals(original.getCreated(), updated.getCreated());
        assertEquals(19, updated.getLastModified().length());
        assertTrue(updated.getAppMonitorConfiguration().path("AllowCookies").booleanValue());
        assertTrue(updated.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals("ENABLED", updated.getCustomEvents().path("Status").textValue());
    }

    @Test
    void emptyUpdateLeavesTheStoredSnapshotUnchanged() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        service.updateAppMonitor(REGION, "monitor", request("{}"));

        assertEquals(original, service.getAppMonitor(REGION, "monitor"));
    }

    @Test
    void updateAppMonitorRejectsBlankDomain() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.updateAppMonitor(REGION, "monitor", request("{\"Domain\":\"  \"}")));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void updateAppMonitorRejectsMissingMonitor() throws Exception {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.updateAppMonitor(REGION, "missing", request("{\"Domain\":\"new.example.com\"}")));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("missing", error.getExtendedData().get("resourceName"));
    }

    @Test
    void createAppMonitorRejectsDuplicateWithoutChangingOriginal() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createAppMonitor(REGION, request("""
                        {"Name":"monitor","Domain":"new.example.com"}
                        """)));

        assertEquals("ConflictException", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("old.example.com", service.getAppMonitor(REGION, "monitor").getDomain());
        assertEquals(original.getId(), service.getAppMonitor(REGION, "monitor").getId());
    }

    @Test
    void listAppMonitorsPaginatesInNameOrder() throws Exception {
        for (String name : List.of("monitor-c", "monitor-a", "monitor-b")) {
            service.createAppMonitor(REGION, request("""
                    {"Name":"%s","Domain":"example.com"}
                    """.formatted(name)));
        }

        RumService.Page first = service.listAppMonitors(REGION, "2", null);
        RumService.Page second = service.listAppMonitors(REGION, "2", first.nextToken());

        assertEquals(List.of("monitor-a", "monitor-b"),
                first.monitors().stream().map(AppMonitor::getName).toList());
        assertEquals(List.of("monitor-c"), second.monitors().stream().map(AppMonitor::getName).toList());
        assertFalse(first.nextToken().isBlank());
        assertEquals(null, second.nextToken());
    }

    @Test
    void listAppMonitorsRejectsInvalidLimitsAndTokens() {
        for (String limit : List.of("0", "101", "not-a-number")) {
            AwsException error = assertThrows(
                    AwsException.class, () -> service.listAppMonitors(REGION, limit, null));
            assertEquals("ValidationException", error.getErrorCode());
        }
        AwsException tokenError = assertThrows(
                AwsException.class, () -> service.listAppMonitors(REGION, null, "not-a-token"));
        assertEquals("ValidationException", tokenError.getErrorCode());
    }

    @Test
    void createAppMonitorSupportsDomainListAndDefaultsLoggingOff() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","DomainList":["example.com","localhost"]}
                """));

        assertEquals(List.of("example.com", "localhost"), monitor.getDomainList());
        assertEquals(null, monitor.getDomain());
        assertFalse(monitor.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals(19, monitor.getCreated().length());
        assertEquals(19, monitor.getLastModified().length());
        assertEquals("Web", monitor.getPlatform());
    }

    @Test
    void createAppMonitorAcceptsDigitInTagKey() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com","Tags":{"env1":"test"}}
                """));

        assertEquals("test", monitor.getTags().get("env1"));
    }

    @Test
    void createAppMonitorRejectsAwsReservedTagKey() throws Exception {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createAppMonitor(REGION, request("""
                        {"Name":"monitor","Domain":"example.com","Tags":{"aws:team":"test"}}
                        """)));

        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void appMonitorConfigurationCanBeReloadedFromPersistentStorage(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("rum.json");
        var firstStore = new PersistentStorage<String, AppMonitor>(
                file, new TypeReference<Map<String, AppMonitor>>() {
                });
        firstStore.load();
        RumService firstService = new RumService(firstStore, REGION_RESOLVER);
        AppMonitor created = firstService.createAppMonitor(REGION, request("""
                {
                  "Name":"persistent-monitor",
                  "Domain":"example.com",
                  "AppMonitorConfiguration":{"AllowCookies":true},
                  "CwLogEnabled":true,
                  "Tags":{"Owner":"floci"}
                }
                """));

        var reloadedStore = new PersistentStorage<String, AppMonitor>(
                file, new TypeReference<Map<String, AppMonitor>>() {
                });
        reloadedStore.load();
        AppMonitor reloaded = new RumService(reloadedStore, REGION_RESOLVER).getAppMonitor(REGION, "persistent-monitor");

        assertEquals(created.getId(), reloaded.getId());
        assertEquals(created.getCreated(), reloaded.getCreated());
        assertEquals("Web", reloaded.getPlatform());
        assertEquals("example.com", reloaded.getDomain());
        assertTrue(reloaded.getAppMonitorConfiguration().path("AllowCookies").booleanValue());
        assertTrue(reloaded.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals("floci", reloaded.getTags().get("Owner"));
    }

    @Test
    void tagResourceMergesTagsAndUntagResourceRemovesThem() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"tagged","Domain":"example.com","Tags":{"Owner":"floci"}}
                """));
        String arn = "arn:aws:rum:us-east-1:000000000000:appmonitor/tagged";

        service.tagResource(REGION, arn, Map.of("phase", "two", "Owner", "rum"));
        assertEquals(Map.of("Owner", "rum", "phase", "two"), service.listTagsForResource(REGION, arn));
        assertEquals("two", service.getAppMonitor(REGION, "tagged").getTags().get("phase"));

        service.untagResource(REGION, arn, List.of("phase"));
        assertEquals(Map.of("Owner", "rum"), service.getAppMonitor(REGION, "tagged").getTags());
    }

    @Test
    void tagOperationsRejectForeignOrMissingAppMonitorArns() {
        AwsException malformed = assertThrows(AwsException.class,
                () -> service.tagResource(REGION, "arn:aws:rum:us-east-1:000000000000:bogus/x", Map.of("k", "v")));
        assertEquals("ValidationException", malformed.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.listTagsForResource(REGION, "arn:aws:rum:us-east-1:000000000000:appmonitor/absent"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals("absent", missing.getExtendedData().get("resourceName"));
    }

    @Test
    void tagResourceEnforcesTheFiftyTagLimit() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"full\",\"Domain\":\"example.com\"}"));
        Map<String, String> tags = new java.util.HashMap<>();
        for (int i = 0; i < 51; i++) {
            tags.put("key" + i, "value");
        }
        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(REGION, "arn:aws:rum:us-east-1:000000000000:appmonitor/full", tags));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void metricsDestinationAndDefinitionLifecycle() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"metrics\",\"Domain\":\"example.com\"}"));
        service.putRumMetricsDestination(REGION, "metrics", request("{\"Destination\":\"CloudWatch\"}"));

        RumService.BatchCreateResult created = service.batchCreateRumMetricDefinitions(REGION, "metrics", request("""
                {
                  "Destination":"CloudWatch",
                  "MetricDefinitions":[
                    {"Name":"SessionCount","EventPattern":"{\\"event_type\\":[\\"com.amazon.rum.session_start_event\\"]}"},
                    {"Name":"NotAnExtendedMetric","EventPattern":"{\\"event_type\\":[\\"x\\"]}"},
                    {"Name":"JsErrorCount","EventPattern":"{\\"event_type\\":[\\"com.amazon.rum.http_event\\"]}"},
                    {"Name":"Checkouts","Namespace":"AWS/Custom","EventPattern":"{\\"event_type\\":[\\"a\\"]}"}
                  ]
                }
                """));
        assertEquals(1, created.metricDefinitions().size());
        assertEquals(3, created.errors().size());
        assertTrue(created.errors().stream().allMatch(e -> "ValidationException".equals(e.errorCode())));
        assertEquals("NotAnExtendedMetric", created.errors().getFirst().metricDefinition().path("Name").asText());

        String sessionCountId = created.metricDefinitions().getFirst().getMetricDefinitionId();
        service.updateRumMetricDefinition(REGION, "metrics", request("""
                {
                  "Destination":"CloudWatch",
                  "MetricDefinitionId":"%s",
                  "MetricDefinition":{
                    "Name":"SessionCount",
                    "EventPattern":"{\\"event_type\\":[\\"com.amazon.rum.session_start_event\\"],\\"metadata\\":{\\"browserName\\":[\\"Chrome\\"]}}",
                    "DimensionKeys":{"metadata.browserName":"BrowserName"}
                  }
                }
                """.formatted(sessionCountId)));
        var definitions = service.batchGetRumMetricDefinitions(REGION, "metrics", "CloudWatch", null, null, null);
        assertEquals(Map.of("metadata.browserName", "BrowserName"),
                definitions.items().getFirst().getDimensionKeys());
        assertEquals(sessionCountId, definitions.items().getFirst().getMetricDefinitionId());

        AwsException undeclaredDimension = assertThrows(AwsException.class,
                () -> service.updateRumMetricDefinition(REGION, "metrics", request("""
                        {
                          "Destination":"CloudWatch",
                          "MetricDefinitionId":"%s",
                          "MetricDefinition":{
                            "Name":"SessionCount",
                            "EventPattern":"{\\"event_type\\":[\\"com.amazon.rum.session_start_event\\"]}",
                            "DimensionKeys":{"metadata.browserName":"BrowserName"}
                          }
                        }
                        """.formatted(sessionCountId))));
        assertEquals("ValidationException", undeclaredDimension.getErrorCode());

        RumService.BatchDeleteResult deleted = service.batchDeleteRumMetricDefinitions(
                REGION, "metrics", "CloudWatch", null, List.of(sessionCountId, "missing-id"));
        assertEquals(List.of(sessionCountId), deleted.metricDefinitionIds());
        assertEquals("missing-id", deleted.errors().getFirst().metricDefinitionId());
        assertTrue(service.batchGetRumMetricDefinitions(REGION, "metrics", "CloudWatch", null, null, null)
                .items().isEmpty());

        assertEquals(List.of("CloudWatch"), service.listRumMetricsDestinations(REGION, "metrics", null, null)
                .items().stream().map(d -> d.getDestination()).toList());
        service.deleteRumMetricsDestination(REGION, "metrics", "CloudWatch", null);
        AwsException gone = assertThrows(AwsException.class,
                () -> service.deleteRumMetricsDestination(REGION, "metrics", "CloudWatch", null));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void metricDefinitionsRequireAnExistingDestination() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"no-destination\",\"Domain\":\"example.com\"}"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.batchGetRumMetricDefinitions(REGION, "no-destination", "CloudWatch", null, null, null));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals("CloudWatch", error.getExtendedData().get("resourceName"));
    }

    @Test
    void evidentlyDestinationRequiresExperimentArnAndRole() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"evidently\",\"Domain\":\"example.com\"}"));
        String experiment = "arn:aws:evidently:us-east-1:000000000000:project/p/experiment/e";
        String role = "arn:aws:iam::000000000000:role/rum-evidently";

        for (String body : List.of(
                "{\"Destination\":\"Evidently\",\"IamRoleArn\":\"" + role + "\"}",
                "{\"Destination\":\"Evidently\",\"DestinationArn\":\"" + experiment + "\"}",
                "{\"Destination\":\"CloudWatch\",\"IamRoleArn\":\"" + role + "\"}",
                "{\"Destination\":\"CloudWatch\",\"DestinationArn\":\"" + experiment + "\"}",
                "{\"Destination\":\"Kinesis\"}")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putRumMetricsDestination(REGION, "evidently", request(body)), body);
            assertEquals("ValidationException", error.getErrorCode(), body);
        }

        service.putRumMetricsDestination(REGION, "evidently", request(
                "{\"Destination\":\"Evidently\",\"DestinationArn\":\"" + experiment + "\",\"IamRoleArn\":\""
                        + role + "\"}"));
        var destination = service.listRumMetricsDestinations(REGION, "evidently", null, null).items().getFirst();
        assertEquals(experiment, destination.getDestinationArn());
        assertEquals(role, destination.getIamRoleArn());
    }

    @Test
    void resourcePolicyRevisionsGuardPutAndDelete() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"policy\",\"Domain\":\"example.com\"}"));
        AwsException missing = assertThrows(AwsException.class, () -> service.getResourcePolicy(REGION, "policy"));
        assertEquals("PolicyNotFoundException", missing.getErrorCode());

        var first = service.putResourcePolicy(REGION, "policy", policyRequest("One", null));
        assertEquals(first.getPolicyRevisionId(), service.getResourcePolicy(REGION, "policy").getPolicyRevisionId());

        AwsException stale = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, "policy", policyRequest("Two", "stale-revision")));
        assertEquals("InvalidPolicyRevisionIdException", stale.getErrorCode());

        var second = service.putResourcePolicy(REGION, "policy", policyRequest("Two", first.getPolicyRevisionId()));
        assertNotEquals(first.getPolicyRevisionId(), second.getPolicyRevisionId());
        assertTrue(service.getResourcePolicy(REGION, "policy").getPolicyDocument().contains("Two"));

        assertEquals(second.getPolicyRevisionId(),
                service.deleteResourcePolicy(REGION, "policy", null).getPolicyRevisionId());
        AwsException deleted = assertThrows(AwsException.class,
                () -> service.deleteResourcePolicy(REGION, "policy", null));
        assertEquals("PolicyNotFoundException", deleted.getErrorCode());
    }

    @Test
    void resourcePolicyRejectsUnsupportedActionsAndOversizedDocuments() throws Exception {
        service.createAppMonitor(REGION, request("{\"Name\":\"policy-validation\",\"Domain\":\"example.com\"}"));
        String getAction = objectMapper.writeValueAsString(Map.of("PolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                "Action":"rum:GetAppMonitor","Resource":"*"}]}"""));
        AwsException malformed = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, "policy-validation", request(getAction)));
        assertEquals("MalformedPolicyDocumentException", malformed.getErrorCode());

        String oversized = objectMapper.writeValueAsString(Map.of("PolicyDocument", "x".repeat(4097)));
        AwsException tooLarge = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, "policy-validation", request(oversized)));
        assertEquals("PolicySizeLimitExceededException", tooLarge.getErrorCode());

        AwsException noMonitor = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(REGION, "absent", policyRequest("One", null)));
        assertEquals("ResourceNotFoundException", noMonitor.getErrorCode());
    }

    @Test
    void putRumEventsStoresEventsThatGetAppMonitorDataFiltersAndPages() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request(
                "{\"Name\":\"events\",\"Domain\":\"example.com\"}"));
        long nowSeconds = java.time.Instant.now().getEpochSecond();
        service.putRumEvents(REGION, monitor.getId(), request("""
                {
                  "BatchId":"%s",
                  "AppMonitorDetails":{"id":"%s","version":"1.0.0"},
                  "UserDetails":{"userId":"%s","sessionId":"%s"},
                  "RumEvents":[
                    {"id":"%s","timestamp":%d,"type":"com.amazon.rum.session_start_event",
                     "metadata":"{\\"browserName\\":\\"Chrome\\"}","details":"{}"},
                    {"id":"%s","timestamp":%d.5,"type":"com.amazon.rum.page_view_event",
                     "metadata":"{\\"browserName\\":\\"Firefox\\"}","details":"{\\"pageId\\":\\"/\\"}"},
                    {"id":"%s","timestamp":%d,"type":"com.amazon.rum.js_error_event","details":"{}"}
                  ]
                }
                """.formatted(uuid(), monitor.getId(), uuid(), uuid(),
                uuid(), nowSeconds - 10, uuid(), nowSeconds - 5, uuid(), nowSeconds - 7200)));

        long nowMillis = java.time.Instant.now().toEpochMilli();
        String lastHour = "{\"After\":%d,\"Before\":%d}".formatted(nowMillis - 3_600_000, nowMillis);
        RumService.Slice<String> all = service.getAppMonitorData(REGION, "events",
                request("{\"TimeRange\":" + lastHour + "}"));
        assertEquals(2, all.items().size());
        JsonNode first = objectMapper.readTree(all.items().getFirst());
        assertEquals("com.amazon.rum.session_start_event", first.path("event_type").asText());
        assertEquals(monitor.getId(), first.path("application_id").asText());
        assertEquals("events", first.path("application_name").asText());
        assertEquals("Chrome", first.path("metadata").path("browserName").asText());
        assertEquals((nowSeconds - 10) * 1000, first.path("event_timestamp").asLong());
        assertEquals((nowSeconds - 5) * 1000 + 500,
                objectMapper.readTree(all.items().get(1)).path("event_timestamp").asLong());

        RumService.Slice<String> firefox = service.getAppMonitorData(REGION, "events", request(
                "{\"TimeRange\":" + lastHour + ",\"Filters\":[{\"Name\":\"Browser\",\"Values\":[\"Firefox\"]}]}"));
        assertEquals(1, firefox.items().size());
        RumService.Slice<String> inverted = service.getAppMonitorData(REGION, "events", request(
                "{\"TimeRange\":" + lastHour + ",\"Filters\":[{\"Name\":\"Invert\",\"Values\":[\"Firefox\"]}]}"));
        assertEquals(1, inverted.items().size());
        assertTrue(inverted.items().getFirst().contains("session_start_event"));

        RumService.Slice<String> page = service.getAppMonitorData(REGION, "events",
                request("{\"TimeRange\":" + lastHour + ",\"MaxResults\":1}"));
        assertEquals(1, page.items().size());
        RumService.Slice<String> next = service.getAppMonitorData(REGION, "events", request(
                "{\"TimeRange\":" + lastHour + ",\"MaxResults\":1,\"NextToken\":\"" + page.nextToken() + "\"}"));
        assertEquals(1, next.items().size());
        assertEquals(null, next.nextToken());

        AwsException badFilter = assertThrows(AwsException.class, () -> service.getAppMonitorData(REGION, "events",
                request("{\"TimeRange\":" + lastHour + ",\"Filters\":[{\"Name\":\"Region\",\"Values\":[\"x\"]}]}")));
        assertEquals("ValidationException", badFilter.getErrorCode());
    }

    @Test
    void putRumEventsValidatesTheMonitorAndPayload() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request(
                "{\"Name\":\"ingest\",\"Domain\":\"example.com\"}"));
        String event = "{\"id\":\"%s\",\"timestamp\":1,\"type\":\"t\",\"details\":\"{}\"}".formatted(uuid());

        AwsException unknown = assertThrows(AwsException.class, () -> service.putRumEvents(REGION, uuid(),
                request(eventsBody("{}", uuid(), event))));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());

        for (String body : List.of(
                eventsBody("{}", "not-a-uuid", event),
                eventsBody("{\"id\":\"" + uuid() + "\"}", uuid(), event),
                eventsBody("{}", uuid(), "{\"id\":\"%s\",\"timestamp\":1,\"type\":\"t\",\"details\":\"{\"}"
                        .formatted(uuid())),
                eventsBody("{}", uuid(), "{\"id\":\"x\",\"timestamp\":1,\"type\":\"t\",\"details\":\"{}\"}"))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putRumEvents(REGION, monitor.getId(), request(body)), body);
            assertEquals("ValidationException", error.getErrorCode(), body);
        }
    }

    @Test
    void deleteAppMonitorRemovesItsDestinationsPolicyAndEvents() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request(
                "{\"Name\":\"cascade\",\"Domain\":\"example.com\"}"));
        service.putRumMetricsDestination(REGION, "cascade", request("{\"Destination\":\"CloudWatch\"}"));
        service.putResourcePolicy(REGION, "cascade", policyRequest("One", null));
        service.putRumEvents(REGION, monitor.getId(), request(eventsBody("{}", uuid(),
                "{\"id\":\"%s\",\"timestamp\":%d,\"type\":\"t\",\"details\":\"{}\"}"
                        .formatted(uuid(), java.time.Instant.now().getEpochSecond()))));

        service.deleteAppMonitor(REGION, "cascade");
        service.createAppMonitor(REGION, request("{\"Name\":\"cascade\",\"Domain\":\"example.com\"}"));

        assertTrue(service.listRumMetricsDestinations(REGION, "cascade", null, null).items().isEmpty());
        assertEquals("PolicyNotFoundException", assertThrows(AwsException.class,
                () -> service.getResourcePolicy(REGION, "cascade")).getErrorCode());
        assertTrue(service.getAppMonitorData(REGION, "cascade", request("{\"TimeRange\":{\"After\":0}}"))
                .items().isEmpty());
    }

    private JsonNode policyRequest(String sid, String revisionId) throws Exception {
        String document = """
                {"Version":"2012-10-17","Statement":[{"Sid":"%s","Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":["rum:PutRumEvents"],
                "Resource":"arn:aws:rum:us-east-1:000000000000:appmonitor/policy"}]}""".formatted(sid);
        Map<String, String> body = new java.util.HashMap<>();
        body.put("PolicyDocument", document);
        if (revisionId != null) {
            body.put("PolicyRevisionId", revisionId);
        }
        return objectMapper.valueToTree(body);
    }

    private static String eventsBody(String appMonitorDetails, String batchId, String event) {
        return "{\"BatchId\":\"%s\",\"AppMonitorDetails\":%s,\"UserDetails\":{},\"RumEvents\":[%s]}"
                .formatted(batchId, appMonitorDetails, event);
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    private JsonNode request(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
