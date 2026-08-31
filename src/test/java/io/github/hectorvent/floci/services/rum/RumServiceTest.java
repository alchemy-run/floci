package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import io.github.hectorvent.floci.services.rum.model.MetricDefinition;
import io.github.hectorvent.floci.services.rum.model.ResourcePolicy;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RumService service = new RumService(new InMemoryStorage<>());

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
    void tagResourceMergesAndUntagResourceRemovesKeys() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com","Tags":{"fixture":"rum-app-monitor"}}
                """));
        String arn = "arn:aws:rum:" + REGION + ":000000000000:appmonitor/monitor";

        service.tagResource(REGION, arn, Map.of("phase", "two", "alchemy::id", "TestMonitor"));
        AppMonitor tagged = service.getAppMonitor(REGION, "monitor");
        assertEquals("rum-app-monitor", tagged.getTags().get("fixture"));
        assertEquals("two", tagged.getTags().get("phase"));
        assertEquals("TestMonitor", tagged.getTags().get("alchemy::id"));

        service.untagResource(REGION, arn, List.of("phase"));
        AppMonitor afterRemoval = service.getAppMonitor(REGION, "monitor");
        assertEquals(null, afterRemoval.getTags().get("phase"));
        assertEquals("rum-app-monitor", afterRemoval.getTags().get("fixture"));
        assertEquals("TestMonitor", afterRemoval.getTags().get("alchemy::id"));
    }

    @Test
    void getAppMonitorRejectsAMissingMonitor() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.getAppMonitor(REGION, "alchemy-nonexistent-rum-monitor-probe"));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("alchemy-nonexistent-rum-monitor-probe", error.getExtendedData().get("resourceName"));
        assertEquals("AppMonitor", error.getExtendedData().get("resourceType"));
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
        RumService firstService = new RumService(firstStore);
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
        AppMonitor reloaded = new RumService(reloadedStore).getAppMonitor(REGION, "persistent-monitor");

        assertEquals(created.getId(), reloaded.getId());
        assertEquals(created.getCreated(), reloaded.getCreated());
        assertEquals("Web", reloaded.getPlatform());
        assertEquals("example.com", reloaded.getDomain());
        assertTrue(reloaded.getAppMonitorConfiguration().path("AllowCookies").booleanValue());
        assertTrue(reloaded.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals("floci", reloaded.getTags().get("Owner"));
    }

    @Test
    void putRumEventsStoresEventsThatGetAppMonitorDataReturns() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request("""
                {"Name":"events-monitor","Domain":"example.com"}
                """));
        String eventId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        service.putRumEvents(REGION, monitor.getId(), request("""
                {
                  "BatchId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "AppMonitorDetails":{"name":"events-monitor","id":"%s"},
                  "UserDetails":{"userId":"u","sessionId":"s"},
                  "RumEvents":[{
                    "id":"%s",
                    "timestamp":1710000000,
                    "type":"com.amazon.rum.session_start_event",
                    "details":"{}"
                  }]
                }
                """.formatted(monitor.getId(), eventId)));

        RumService.EventPage page = service.getAppMonitorData(REGION, "events-monitor", request("""
                {"TimeRange":{"After":1709990000000,"Before":1710010000000}}
                """));
        assertEquals(1, page.events().size());
        assertTrue(page.events().getFirst().contains(eventId));
    }

    @Test
    void putRumEventsRejectsUnknownMonitorIds() throws Exception {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.putRumEvents(REGION, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", request("""
                        {
                          "BatchId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                          "AppMonitorDetails":{"name":"missing","id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"},
                          "UserDetails":{"userId":"u","sessionId":"s"},
                          "RumEvents":[]
                        }
                        """)));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void resourcePolicyPutGetUpdateAndDelete() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com"}
                """));
        String firstDocument = """
                {"Version":"2012-10-17","Statement":[{"Sid":"AlchemyRumTest","Effect":"Allow","Action":"rum:PutRumEvents","Resource":"*","Principal":{"AWS":"*"}}]}
                """;
        ResourcePolicy created = service.putResourcePolicy(REGION, "monitor", request("""
                {"PolicyDocument":%s}
                """.formatted(objectMapper.writeValueAsString(firstDocument))));

        assertTrue(created.getPolicyRevisionId().length() > 0);
        ResourcePolicy observed = service.getResourcePolicy(REGION, "monitor");
        assertEquals(firstDocument, observed.getPolicyDocument());
        assertEquals(created.getPolicyRevisionId(), observed.getPolicyRevisionId());

        ResourcePolicy unchangedAttempt = service.putResourcePolicy(REGION, "monitor", request("""
                {"PolicyDocument":%s,"PolicyRevisionId":"%s"}
                """.formatted(
                objectMapper.writeValueAsString(firstDocument), created.getPolicyRevisionId())));
        assertNotEquals(created.getPolicyRevisionId(), unchangedAttempt.getPolicyRevisionId());

        String rotated = firstDocument.replace("AlchemyRumTest", "AlchemyRumTestRotated");
        ResourcePolicy updated = service.putResourcePolicy(REGION, "monitor", request("""
                {"PolicyDocument":%s,"PolicyRevisionId":"%s"}
                """.formatted(
                objectMapper.writeValueAsString(rotated), unchangedAttempt.getPolicyRevisionId())));
        assertNotEquals(unchangedAttempt.getPolicyRevisionId(), updated.getPolicyRevisionId());
        assertTrue(service.getResourcePolicy(REGION, "monitor").getPolicyDocument().contains("AlchemyRumTestRotated"));

        ResourcePolicy deleted = service.deleteResourcePolicy(REGION, "monitor", updated.getPolicyRevisionId());
        assertEquals(updated.getPolicyRevisionId(), deleted.getPolicyRevisionId());
        AwsException missing = assertThrows(
                AwsException.class, () -> service.getResourcePolicy(REGION, "monitor"));
        assertEquals("PolicyNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void resourcePolicyRejectsStaleRevisionAndMissingMonitor() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com"}
                """));
        service.putResourcePolicy(REGION, "monitor", request("""
                {"PolicyDocument":"{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[]}"}
                """));

        AwsException stale = assertThrows(
                AwsException.class,
                () -> service.putResourcePolicy(REGION, "monitor", request("""
                        {"PolicyDocument":"{\\"Version\\":\\"2012-10-17\\"}","PolicyRevisionId":"not-the-current-revision"}
                        """)));
        assertEquals("InvalidPolicyRevisionIdException", stale.getErrorCode());

        AwsException missingMonitor = assertThrows(
                AwsException.class, () -> service.getResourcePolicy(REGION, "missing"));
        assertEquals("ResourceNotFoundException", missingMonitor.getErrorCode());
    }

    @Test
    void metricsDestinationAndDefinitionsLifecycle() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com"}
                """));
        service.putRumMetricsDestination(REGION, "monitor", request("""
                {"Destination":"CloudWatch"}
                """));
        assertEquals(List.of("CloudWatch"), service.listRumMetricsDestinations(REGION, "monitor", null, null)
                .destinations().stream().map(d -> d.getDestination()).toList());

        String sessionPattern = "{\"event_type\":[\"com.amazon.rum.session_start_event\"]}";
        JsonNode createResponse = service.batchCreateRumMetricDefinitions(REGION, "monitor", request("""
                {
                  "Destination":"CloudWatch",
                  "MetricDefinitions":[{"Name":"SessionCount","EventPattern":%s}]
                }
                """.formatted(objectMapper.writeValueAsString(sessionPattern))));
        assertEquals(0, createResponse.get("Errors").size());
        assertEquals("SessionCount", createResponse.get("MetricDefinitions").get(0).get("Name").textValue());

        List<MetricDefinition> first = service.batchGetRumMetricDefinitions(
                REGION, "monitor", "CloudWatch", null, null, null).definitions();
        assertEquals(List.of("SessionCount"), first.stream().map(MetricDefinition::getName).toList());

        String sessionId = first.getFirst().getMetricDefinitionId();
        String browserPattern = "{\"event_type\":[\"com.amazon.rum.session_start_event\"],\"metadata\":{\"browserName\":[\"Chrome\",\"Firefox\",\"Safari\"]}}";
        service.updateRumMetricDefinition(REGION, "monitor", request("""
                {
                  "Destination":"CloudWatch",
                  "MetricDefinitionId":"%s",
                  "MetricDefinition":{
                    "Name":"SessionCount",
                    "EventPattern":%s,
                    "DimensionKeys":{"metadata.browserName":"BrowserName"}
                  }
                }
                """.formatted(sessionId, objectMapper.writeValueAsString(browserPattern))));

        String jsPattern = "{\"event_type\":[\"com.amazon.rum.js_error_event\"]}";
        service.batchCreateRumMetricDefinitions(REGION, "monitor", request("""
                {
                  "Destination":"CloudWatch",
                  "MetricDefinitions":[{"Name":"JsErrorCount","EventPattern":%s}]
                }
                """.formatted(objectMapper.writeValueAsString(jsPattern))));

        List<MetricDefinition> updated = service.batchGetRumMetricDefinitions(
                REGION, "monitor", "CloudWatch", null, null, null).definitions();
        assertEquals(List.of("JsErrorCount", "SessionCount"),
                updated.stream().map(MetricDefinition::getName).toList());
        MetricDefinition session = updated.stream()
                .filter(d -> "SessionCount".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("metadata.browserName", "BrowserName"), session.getDimensionKeys());

        JsonNode deleted = service.batchDeleteRumMetricDefinitions(
                REGION, "monitor", "CloudWatch", null, List.of(session.getMetricDefinitionId()));
        assertEquals(0, deleted.get("Errors").size());
        assertEquals(List.of("JsErrorCount"), service.batchGetRumMetricDefinitions(
                REGION, "monitor", "CloudWatch", null, null, null)
                .definitions().stream().map(MetricDefinition::getName).toList());

        service.deleteRumMetricsDestination(REGION, "monitor", "CloudWatch", null);
        assertEquals(List.of(), service.listRumMetricsDestinations(REGION, "monitor", null, null).destinations());
    }

    @Test
    void deleteAppMonitorRemovesPolicyAndDestinations() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"example.com"}
                """));
        service.putResourcePolicy(REGION, "monitor", request("""
                {"PolicyDocument":"{\\"Version\\":\\"2012-10-17\\"}"}
                """));
        service.putRumMetricsDestination(REGION, "monitor", request("""
                {"Destination":"CloudWatch"}
                """));
        service.deleteAppMonitor(REGION, "monitor");

        AwsException monitorGone = assertThrows(
                AwsException.class, () -> service.getAppMonitor(REGION, "monitor"));
        assertEquals("ResourceNotFoundException", monitorGone.getErrorCode());
        AwsException policyGone = assertThrows(
                AwsException.class, () -> service.getResourcePolicy(REGION, "monitor"));
        assertEquals("ResourceNotFoundException", policyGone.getErrorCode());
    }

    private JsonNode request(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
