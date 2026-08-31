package io.github.hectorvent.floci.services.cloudwatch.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudWatchAlarmMuteRuleJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchMetricsJsonHandler handler;

    @BeforeEach
    void setUp() {
        CloudWatchMetricsService service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
        handler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    @Test
    void listAlarmMuteRulesOnEmptyAccountReturnsEmptyArray() {
        ObjectNode req = MAPPER.createObjectNode();
        Response resp = handler.handle("ListAlarmMuteRules", req, REGION);
        assertEquals(200, resp.getStatus());
        ObjectNode body = (ObjectNode) resp.getEntity();
        assertTrue(body.path("AlarmMuteRuleSummaries").isArray());
        assertEquals(0, body.path("AlarmMuteRuleSummaries").size());
        assertFalse(body.has("NextToken"));
    }

    @Test
    void putGetListDeleteAlarmMuteRuleRoundTrip() {
        ObjectNode put = MAPPER.createObjectNode();
        put.put("Name", "DailyMaintenanceWindow");
        put.put("Description", "Mute alarms during daily maintenance");
        ObjectNode schedule = put.putObject("Rule").putObject("Schedule");
        schedule.put("Expression", "cron(0 2 * * ?)");
        schedule.put("Duration", "PT2H");
        schedule.put("Timezone", "UTC");
        put.putObject("MuteTargets").putArray("AlarmNames").add("WebServerCPUAlarm");
        put.putArray("Tags").addObject().put("Key", "Team").put("Value", "Ops");

        assertEquals(200, handler.handle("PutAlarmMuteRule", put, REGION).getStatus());

        ObjectNode getReq = MAPPER.createObjectNode();
        getReq.put("AlarmMuteRuleName", "DailyMaintenanceWindow");
        Response getResp = handler.handle("GetAlarmMuteRule", getReq, REGION);
        assertEquals(200, getResp.getStatus());
        ObjectNode got = (ObjectNode) getResp.getEntity();
        assertEquals("DailyMaintenanceWindow", got.path("Name").asText());
        assertTrue(got.path("AlarmMuteRuleArn").asText().contains(":alarm-mute-rule:DailyMaintenanceWindow"));
        assertEquals("RECURRING", got.path("MuteType").asText());
        assertEquals("SCHEDULED", got.path("Status").asText());
        assertEquals("cron(0 2 * * ?)", got.path("Rule").path("Schedule").path("Expression").asText());

        Response listResp = handler.handle("ListAlarmMuteRules", MAPPER.createObjectNode(), REGION);
        assertEquals(200, listResp.getStatus());
        ObjectNode listed = (ObjectNode) listResp.getEntity();
        assertEquals(1, listed.path("AlarmMuteRuleSummaries").size());
        assertTrue(listed.path("AlarmMuteRuleSummaries").get(0).path("AlarmMuteRuleArn")
                .asText().contains(":alarm-mute-rule:"));

        ObjectNode deleteReq = MAPPER.createObjectNode();
        deleteReq.put("AlarmMuteRuleName", "DailyMaintenanceWindow");
        assertEquals(200, handler.handle("DeleteAlarmMuteRule", deleteReq, REGION).getStatus());
        assertEquals(200, handler.handle("DeleteAlarmMuteRule", deleteReq, REGION).getStatus());

        try {
            handler.handle("GetAlarmMuteRule", getReq, REGION);
            throw new AssertionError("expected ResourceNotFoundException");
        } catch (AwsException e) {
            assertEquals("ResourceNotFoundException", e.getErrorCode());
            assertEquals(404, e.getHttpStatus());
        }
    }
}
