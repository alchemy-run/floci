package io.github.hectorvent.floci.services.internetmonitor;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Internet Monitor restJson1 lifecycle used by Alchemy bindings. */
@QuarkusTest
class InternetMonitorIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMonitorOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000001801", EAST))
                .when()
                .get("/v20210603/Monitors/alchemy-nonexistent-internetmonitor")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void monitorCreateGetListUpdateTagsQueryDeleteLifecycle() {
        String authorization = auth("000000001802", EAST);
        String name = "lifecycle-monitor";
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String start = now.minus(3600, ChronoUnit.SECONDS).toString();
        String end = now.toString();

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "MonitorName":"lifecycle-monitor",
                          "Resources":[],
                          "MaxCityNetworksToMonitor":1,
                          "Tags":{"Owner":"floci"}
                        }
                        """)
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("Status", equalTo("ACTIVE"))
                .extract().path("Arn");
        assertEquals(arn("000000001802", EAST, name), arn);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .body("MonitorName", equalTo(name))
                .body("MonitorArn", equalTo(arn))
                .body("Status", equalTo("ACTIVE"))
                .body("ProcessingStatus", equalTo("OK"))
                .body("MaxCityNetworksToMonitor", equalTo(1))
                .body("Tags.Owner", equalTo("floci"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors")
                .then()
                .statusCode(200)
                .body("Monitors.size()", greaterThanOrEqualTo(1))
                .body("Monitors.find { it.MonitorName == 'lifecycle-monitor' }.MonitorArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"purpose\":\"alchemy-test\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.Owner", equalTo("floci"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name + "/HealthEvents")
                .then()
                .statusCode(200)
                .body("HealthEvents.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name
                        + "/HealthEvents/alchemy-nonexistent-internetmonitor-event-id")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/InternetEvents")
                .then()
                .statusCode(200)
                .body("InternetEvents.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/InternetEvents/alchemy-nonexistent-internetmonitor-event-id")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String queryId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StartTime":"%s",
                          "EndTime":"%s",
                          "QueryType":"MEASUREMENTS"
                        }
                        """.formatted(start, end))
                .when()
                .post("/v20210603/Monitors/" + name + "/Queries")
                .then()
                .statusCode(200)
                .body("QueryId", notNullValue())
                .extract().path("QueryId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name + "/Queries/" + queryId + "/Status")
                .then()
                .statusCode(200)
                .body("Status", equalTo("SUCCEEDED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name + "/Queries/" + queryId + "/Results")
                .then()
                .statusCode(200)
                .body("Fields.size()", equalTo(0))
                .body("Data.size()", equalTo(0));

        Response stop = given()
                .header("Authorization", authorization)
                .when()
                .delete("/v20210603/Monitors/" + name + "/Queries/" + queryId);
        assertTrue(stop.statusCode() == 200 || stop.statusCode() == 400);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Status\":\"INACTIVE\"}")
                .when()
                .patch("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .body("MonitorArn", equalTo(arn))
                .body("Status", equalTo("INACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateCreateReturnsConflictAndDeleteRequiresInactive() {
        String authorization = auth("000000001803", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MonitorName\":\"duplicate-monitor\",\"MaxCityNetworksToMonitor\":1}")
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MonitorName\":\"duplicate-monitor\",\"MaxCityNetworksToMonitor\":1}")
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v20210603/Monitors/duplicate-monitor")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/internetmonitor/aws4_request";
    }

    private static String arn(String accountId, String region, String name) {
        return "arn:aws:internetmonitor:" + region + ":" + accountId + ":monitor/" + name;
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
