package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies PutRumEvents + GetAppMonitorData against the restJson1 data plane. */
@QuarkusTest
class RumEventsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String TYPE = "com.amazon.rum.session_start_event";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putRumEventsThenGetAppMonitorDataRoundTripsTheBatch() {
        String authorization = auth("000000000201", EAST);
        String name = "events-monitor";
        String id = create(authorization, name);

        String batchId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        long epochSeconds = 1_710_000_000L;
        String putBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "BatchId":"%s",
                          "AppMonitorDetails":{"name":"%s","id":"%s"},
                          "UserDetails":{"userId":"user-1","sessionId":"session-1"},
                          "RumEvents":[{
                            "id":"%s",
                            "timestamp":%d,
                            "type":"%s",
                            "details":"{}"
                          }]
                        }
                        """.formatted(batchId, name, id, eventId, epochSeconds, TYPE))
                .when()
                .post("/appmonitors/" + id + "/")
                .then()
                .statusCode(200)
                .extract().asString();
        assertTrue(putBody.isEmpty());

        Response data = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TimeRange\":{\"After\":1709990000000,\"Before\":1710010000000}}")
                .when()
                .post("/appmonitor/" + name + "/data")
                .then()
                .statusCode(200)
                .extract().response();

        List<String> events = data.path("Events");
        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains(eventId));
        assertTrue(events.getFirst().contains(TYPE));
    }

    @Test
    void getAppMonitorDataOnANewMonitorReturnsAnEmptyEventsList() {
        String authorization = auth("000000000202", EAST);
        String name = "empty-data-monitor";
        create(authorization, name);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TimeRange\":{\"After\":0}}")
                .when()
                .post("/appmonitor/" + name + "/data")
                .then()
                .statusCode(200)
                .body("Events.size()", equalTo(0));
    }

    @Test
    void putRumEventsRejectsUnknownMonitorIdsAndInvalidUuids() {
        String authorization = auth("000000000203", EAST);
        String missingId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String body = """
                {
                  "BatchId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "AppMonitorDetails":{"name":"missing","id":"%s"},
                  "UserDetails":{"userId":"u","sessionId":"s"},
                  "RumEvents":[]
                }
                """.formatted(missingId);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/appmonitors/" + missingId + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("AppMonitor"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/appmonitors/not-a-uuid/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getAppMonitorDataRejectsUnknownMonitorsAndMissingTimeRange() {
        String authorization = auth("000000000204", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TimeRange\":{\"After\":0}}")
                .when()
                .post("/appmonitor/no-such-monitor/data")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        create(authorization, "needs-timerange");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/appmonitor/needs-timerange/data")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void eventTypeFilterAndTimeRangeExcludeNonMatchingEvents() {
        String authorization = auth("000000000205", EAST);
        String name = "filter-monitor";
        String id = create(authorization, name);
        putEvent(authorization, id, name, "11111111-1111-1111-1111-111111111111",
                1_710_000_000L, TYPE);
        putEvent(authorization, id, name, "22222222-2222-2222-2222-222222222222",
                1_710_000_100L, "com.amazon.rum.js_error_event");

        List<String> typed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "TimeRange":{"After":1710000000000},
                          "Filters":[{"Name":"EventType","Values":["com.amazon.rum.js_error_event"]}]
                        }
                        """)
                .when()
                .post("/appmonitor/" + name + "/data")
                .then()
                .statusCode(200)
                .extract().path("Events");
        assertEquals(1, typed.size());
        assertTrue(typed.getFirst().contains("js_error_event"));

        List<String> tooLate = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TimeRange\":{\"After\":1810000000000}}")
                .when()
                .post("/appmonitor/" + name + "/data")
                .then()
                .statusCode(200)
                .extract().path("Events");
        assertEquals(0, tooLate.size());
    }

    @Test
    void deleteAppMonitorDropsStoredEvents() {
        String authorization = auth("000000000206", EAST);
        String name = "delete-events-monitor";
        String id = create(authorization, name);
        putEvent(authorization, id, name, UUID.randomUUID().toString(), 1_710_000_000L, TYPE);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/appmonitor/" + name)
                .then()
                .statusCode(200);

        create(authorization, name);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TimeRange\":{\"After\":0}}")
                .when()
                .post("/appmonitor/" + name + "/data")
                .then()
                .statusCode(200)
                .body("Events.size()", equalTo(0));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/rum/aws4_request";
    }

    private static String create(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\",\"Domain\":\"example.com\"}")
                .when()
                .post("/appmonitor")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");
    }

    private static void putEvent(
            String authorization, String id, String name, String eventId, long timestamp, String type) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(Map.of(
                        "BatchId", UUID.randomUUID().toString(),
                        "AppMonitorDetails", Map.of("name", name, "id", id),
                        "UserDetails", Map.of("userId", "u", "sessionId", "s"),
                        "RumEvents", List.of(Map.of(
                                "id", eventId,
                                "timestamp", timestamp,
                                "type", type,
                                "details", "{}"))))
                .when()
                .post("/appmonitors/" + id + "/")
                .then()
                .statusCode(200);
    }
}
