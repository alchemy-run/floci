package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the AWS restJson1 app-monitor lifecycle and isolation behavior. */
@QuarkusTest
class RumControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAppMonitorOnANonexistentMonitorFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000110", EAST))
                .when()
                .get("/appmonitor/alchemy-nonexistent-rum-monitor-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceName", equalTo("alchemy-nonexistent-rum-monitor-probe"))
                .body("resourceType", equalTo("AppMonitor"));
    }

    @Test
    void tagResourceAndUntagResourceConvergeOnObservedTags() {
        String authorization = auth("000000000111", EAST);
        create(authorization, """
                {
                  "Name":"tagged-monitor",
                  "Domain":"example.com",
                  "Tags":{"fixture":"rum-app-monitor","alchemy::id":"TestMonitor"}
                }
                """);
        String arn = "arn:aws:rum:" + EAST + ":000000000111:appmonitor/tagged-monitor";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        get(authorization, "tagged-monitor")
                .then()
                .statusCode(200)
                .body("AppMonitor.Tags.fixture", equalTo("rum-app-monitor"))
                .body("AppMonitor.Tags.phase", equalTo("two"))
                .body("AppMonitor.Tags['alchemy::id']", equalTo("TestMonitor"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "phase")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        get(authorization, "tagged-monitor")
                .then()
                .statusCode(200)
                .body("AppMonitor.Tags.phase", equalTo(null))
                .body("AppMonitor.Tags.fixture", equalTo("rum-app-monitor"))
                .body("AppMonitor.Tags['alchemy::id']", equalTo("TestMonitor"));
    }

    @Test
    void appMonitorCreateUpdateGetListDeleteLifecycle() {
        String authorization = auth("000000000101", EAST);
        String name = "lifecycle-monitor";
        String id = create(authorization, """
                {
                  "Name":"lifecycle-monitor",
                  "Domain":"localhost",
                  "AppMonitorConfiguration":{"AllowCookies":true,"SessionSampleRate":0.5},
                  "CwLogEnabled":true,
                  "CustomEvents":{"Status":"ENABLED"},
                  "DeobfuscationConfiguration":{"JavaScriptSourceMaps":{"Status":"ENABLED","S3Uri":"s3://source-maps/maps"}},
                  "Platform":"Android",
                  "Tags":{"Owner":"floci"}
                }
                """);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .body("AppMonitor.Id", equalTo(id))
                .body("AppMonitor.Name", equalTo(name))
                .body("AppMonitor.Domain", equalTo("localhost"))
                .body("AppMonitor.State", equalTo("CREATED"))
                .body("AppMonitor.Platform", equalTo("Android"))
                .body("AppMonitor.AppMonitorConfiguration.AllowCookies", equalTo(true))
                .body("AppMonitor.DataStorage.CwLog.CwLogEnabled", equalTo(true))
                .body("AppMonitor.CustomEvents.Status", equalTo("ENABLED"))
                .body("AppMonitor.Tags.Owner", equalTo("floci"))
                .extract().response();
        String createdTimestamp = created.path("AppMonitor.Created");
        String initialModified = created.path("AppMonitor.LastModified");
        assertTrue(createdTimestamp.matches(TIMESTAMP_PATTERN));
        assertTrue(initialModified.matches(TIMESTAMP_PATTERN));

        String updateBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DomainList":["updated.example.com","localhost"],
                          "AppMonitorConfiguration":{"EnableXRay":true},
                          "CwLogEnabled":false,
                          "CustomEvents":{"Status":"DISABLED"},
                          "DeobfuscationConfiguration":{"JavaScriptSourceMaps":{"Status":"DISABLED"}}
                        }
                        """)
                .when()
                .patch("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .extract().asString();
        assertTrue(updateBody.isEmpty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .body("AppMonitor.Id", equalTo(id))
                .body("AppMonitor.Created", equalTo(createdTimestamp))
                .body("AppMonitor.DomainList[0]", equalTo("updated.example.com"))
                .body("AppMonitor.DomainList[1]", equalTo("localhost"))
                .body("AppMonitor.AppMonitorConfiguration.EnableXRay", equalTo(true))
                .body("AppMonitor.AppMonitorConfiguration.AllowCookies", equalTo(true))
                .body("AppMonitor.AppMonitorConfiguration.SessionSampleRate", equalTo(0.5f))
                .body("AppMonitor.DataStorage.CwLog.CwLogEnabled", equalTo(false))
                .body("AppMonitor.CustomEvents.Status", equalTo("DISABLED"))
                .body("AppMonitor.DeobfuscationConfiguration.JavaScriptSourceMaps.Status", equalTo("DISABLED"))
                .body("AppMonitor.DeobfuscationConfiguration.JavaScriptSourceMaps.S3Uri", equalTo("s3://source-maps/maps"))
                .body("AppMonitor.Tags.Owner", equalTo("floci"));

        List<Map<String, Object>> summaries = list(authorization, null, null)
                .path("AppMonitorSummaries");
        assertEquals(1, summaries.size());
        assertEquals(
                Set.of("Created", "Id", "LastModified", "Name", "Platform", "State"),
                summaries.getFirst().keySet());
        assertEquals(name, summaries.getFirst().get("Name"));
        assertEquals("Android", summaries.getFirst().get("Platform"));
        assertEquals(createdTimestamp, summaries.getFirst().get("Created"));
        assertTrue(((String) summaries.getFirst().get("LastModified")).matches(TIMESTAMP_PATTERN));

        String deleteBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .extract().asString();
        assertTrue(deleteBody.isEmpty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceName", equalTo(name))
                .body("resourceType", equalTo("AppMonitor"));
    }

    @Test
    void appMonitorsAreIsolatedByAccount() {
        String firstAuth = auth("000000000102", EAST);
        String secondAuth = auth("000000000103", EAST);

        String firstId = create(firstAuth, "{\"Name\":\"shared-monitor\",\"Domain\":\"first.example.com\"}");
        String secondId = create(secondAuth, "{\"Name\":\"shared-monitor\",\"Domain\":\"second.example.com\"}");

        assertNotEquals(firstId, secondId);
        get(firstAuth, "shared-monitor").then().body("AppMonitor.Domain", equalTo("first.example.com"));
        get(secondAuth, "shared-monitor").then().body("AppMonitor.Domain", equalTo("second.example.com"));
        assertEquals(1, ((List<?>) list(firstAuth, null, null).path("AppMonitorSummaries")).size());
        assertEquals(1, ((List<?>) list(secondAuth, null, null).path("AppMonitorSummaries")).size());
    }

    @Test
    void appMonitorsAreIsolatedByRegion() {
        String eastAuth = auth("000000000104", EAST);
        String westAuth = auth("000000000104", WEST);

        String eastId = create(eastAuth, "{\"Name\":\"regional-monitor\",\"Domain\":\"east.example.com\"}");
        String westId = create(westAuth, "{\"Name\":\"regional-monitor\",\"Domain\":\"west.example.com\"}");

        assertNotEquals(eastId, westId);
        get(eastAuth, "regional-monitor").then().body("AppMonitor.Domain", equalTo("east.example.com"));
        get(westAuth, "regional-monitor").then().body("AppMonitor.Domain", equalTo("west.example.com"));
    }

    @Test
    void listAppMonitorsSupportsDeterministicPaginationAndDefaultPageSize() {
        String authorization = auth("000000000105", EAST);
        for (int i = 50; i >= 0; i--) {
            create(authorization, "{\"Name\":\"page-monitor-%02d\",\"Domain\":\"example.com\"}".formatted(i));
        }

        Response first = list(authorization, null, null);
        List<String> firstNames = first.path("AppMonitorSummaries.Name");
        String nextToken = first.path("NextToken");
        assertEquals(50, firstNames.size());
        assertEquals("page-monitor-00", firstNames.getFirst());
        assertEquals("page-monitor-49", firstNames.getLast());
        assertNotNull(nextToken);

        Response second = list(authorization, "50", nextToken);
        List<String> secondNames = second.path("AppMonitorSummaries.Name");
        assertEquals(List.of("page-monitor-50"), secondNames);
        assertNull(second.path("NextToken"));

        List<String> allNames = new ArrayList<>(firstNames);
        allNames.addAll(secondNames);
        assertEquals(51, new HashSet<>(allNames).size());
    }

    @Test
    void listAppMonitorsRejectsInvalidPaginationInputs() {
        String authorization = auth("000000000106", EAST);
        for (String maxResults : List.of("0", "101", "abc")) {
            given()
                    .header("Authorization", authorization)
                    .queryParam("maxResults", maxResults)
                    .when()
                    .post("/appmonitors")
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }

        given()
                .header("Authorization", authorization)
                .queryParam("nextToken", "not-a-token")
                .when()
                .post("/appmonitors")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void duplicateCreateReturnsConflictAndPreservesTheExistingMonitor() {
        String authorization = auth("000000000107", EAST);
        String id = create(authorization, "{\"Name\":\"duplicate-monitor\",\"Domain\":\"first.example.com\"}");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"duplicate-monitor\",\"Domain\":\"second.example.com\"}")
                .when()
                .post("/appmonitor")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("resourceName", equalTo("duplicate-monitor"))
                .body("resourceType", equalTo("AppMonitor"));

        get(authorization, "duplicate-monitor")
                .then()
                .body("AppMonitor.Id", equalTo(id))
                .body("AppMonitor.Domain", equalTo("first.example.com"));
    }

    @Test
    void requestsRejectInvalidShapesAndExplicitBlankDomains() {
        String authorization = auth("000000000108", EAST);
        create(authorization, "{\"Name\":\"validation-monitor\",\"Domain\":\"example.com\"}");

        for (String body : List.of("[]", "null", "{\"Domain\":\"  \"}", "{\"Domain\":null}")) {
            given()
                    .contentType("application/json")
                    .header("Authorization", authorization)
                    .body(body)
                    .when()
                    .patch("/appmonitor/validation-monitor")
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }

        String noOpBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .patch("/appmonitor/validation-monitor")
                .then()
                .statusCode(200)
                .extract().asString();
        assertTrue(noOpBody.isEmpty());
        assertFalse(noOpBody.contains("{}"));
    }

    @Test
    void createAppMonitorDefaultsPlatformAndRejectsInvalidValues() {
        String authorization = auth("000000000109", EAST);
        create(authorization, "{\"Name\":\"web-monitor\",\"Domain\":\"example.com\"}");

        get(authorization, "web-monitor")
                .then()
                .statusCode(200)
                .body("AppMonitor.Platform", equalTo("Web"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"invalid-platform\",\"Domain\":\"example.com\",\"Platform\":\"Desktop\"}")
                .when()
                .post("/appmonitor")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void resourcePolicyAndMetricsDestinationLifecycle() {
        String authorization = auth("000000000111", EAST);
        String name = "alchemy-test-rum-config-monitor";
        create(authorization, "{\"Name\":\"" + name + "\",\"Domain\":\"example.com\"}");

        String putDestinationBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Destination\":\"CloudWatch\"}")
                .when()
                .post("/rummetrics/" + name + "/metricsdestination")
                .then()
                .statusCode(200)
                .extract().asString();
        assertTrue(putDestinationBody.isEmpty());

        String sessionPattern = "{\"event_type\":[\"com.amazon.rum.session_start_event\"]}";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Destination":"CloudWatch",
                          "MetricDefinitions":[{"Name":"SessionCount","EventPattern":%s}]
                        }
                        """.formatted(jsonString(sessionPattern)))
                .when()
                .post("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .body("Errors.size()", equalTo(0))
                .body("MetricDefinitions[0].Name", equalTo("SessionCount"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/rummetrics/" + name + "/metricsdestination")
                .then()
                .statusCode(200)
                .body("Destinations[0].Destination", equalTo("CloudWatch"));

        String sessionId = given()
                .header("Authorization", authorization)
                .queryParam("destination", "CloudWatch")
                .when()
                .get("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .body("MetricDefinitions.Name", equalTo(List.of("SessionCount")))
                .extract().path("MetricDefinitions[0].MetricDefinitionId");

        String policyDocument = """
                {"Version":"2012-10-17","Statement":[{"Sid":"AlchemyRumTest","Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000111:root"},"Action":["rum:PutRumEvents"],"Resource":"*"}]}
                """.strip();
        String firstRevision = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"PolicyDocument\":" + jsonString(policyDocument) + "}")
                .when()
                .put("/appmonitor/" + name + "/policy")
                .then()
                .statusCode(200)
                .body("PolicyDocument", equalTo(policyDocument))
                .extract().path("PolicyRevisionId");
        assertNotNull(firstRevision);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name + "/policy")
                .then()
                .statusCode(200)
                .body("PolicyRevisionId", equalTo(firstRevision))
                .body("PolicyDocument", equalTo(policyDocument));

        String browserPattern = "{\"event_type\":[\"com.amazon.rum.session_start_event\"],\"metadata\":{\"browserName\":[\"Chrome\",\"Firefox\",\"Safari\"]}}";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Destination":"CloudWatch",
                          "MetricDefinitionId":"%s",
                          "MetricDefinition":{
                            "Name":"SessionCount",
                            "EventPattern":%s,
                            "DimensionKeys":{"metadata.browserName":"BrowserName"}
                          }
                        }
                        """.formatted(sessionId, jsonString(browserPattern)))
                .when()
                .patch("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200);

        String jsPattern = "{\"event_type\":[\"com.amazon.rum.js_error_event\"]}";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Destination":"CloudWatch",
                          "MetricDefinitions":[{"Name":"JsErrorCount","EventPattern":%s}]
                        }
                        """.formatted(jsonString(jsPattern)))
                .when()
                .post("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .body("Errors.size()", equalTo(0));

        Response afterUpdate = given()
                .header("Authorization", authorization)
                .queryParam("destination", "CloudWatch")
                .when()
                .get("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .extract().response();
        assertEquals(List.of("JsErrorCount", "SessionCount"), afterUpdate.path("MetricDefinitions.Name"));
        List<Map<String, Object>> definitions = afterUpdate.path("MetricDefinitions");
        Map<String, Object> session = definitions.stream()
                .filter(definition -> "SessionCount".equals(definition.get("Name")))
                .findFirst()
                .orElseThrow();
        assertEquals("BrowserName", ((Map<?, ?>) session.get("DimensionKeys")).get("metadata.browserName"));

        given()
                .header("Authorization", authorization)
                .queryParam("destination", "CloudWatch")
                .queryParam("metricDefinitionIds", sessionId)
                .when()
                .delete("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .body("Errors.size()", equalTo(0))
                .body("MetricDefinitionIds", equalTo(List.of(sessionId)));

        given()
                .header("Authorization", authorization)
                .queryParam("destination", "CloudWatch")
                .when()
                .get("/rummetrics/" + name + "/metrics")
                .then()
                .statusCode(200)
                .body("MetricDefinitions.Name", equalTo(List.of("JsErrorCount")));

        String rotated = policyDocument.replace("AlchemyRumTest", "AlchemyRumTestRotated");
        String secondRevision = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"PolicyDocument\":" + jsonString(rotated)
                        + ",\"PolicyRevisionId\":\"" + firstRevision + "\"}")
                .when()
                .put("/appmonitor/" + name + "/policy")
                .then()
                .statusCode(200)
                .extract().path("PolicyRevisionId");
        assertNotEquals(firstRevision, secondRevision);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name + "/policy")
                .then()
                .statusCode(200)
                .body("PolicyDocument", equalTo(rotated));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/appmonitor/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given()
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name + "/policy")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getResourcePolicyWithoutAPolicyReturnsPolicyNotFound() {
        String authorization = auth("000000000112", EAST);
        create(authorization, "{\"Name\":\"no-policy-monitor\",\"Domain\":\"example.com\"}");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/no-policy-monitor/policy")
                .then()
                .statusCode(404)
                .body("__type", equalTo("PolicyNotFoundException"));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/rum/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/appmonitor")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");
    }

    private static Response get(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/appmonitor/" + name);
    }

    private static Response list(String authorization, String maxResults, String nextToken) {
        var request = given()
                .header("Authorization", authorization);
        if (maxResults != null) {
            request.queryParam("maxResults", maxResults);
        }
        if (nextToken != null) {
            request.queryParam("nextToken", nextToken);
        }
        return request
                .when()
                .post("/appmonitors")
                .then()
                .statusCode(200)
                .extract().response();
    }
}
