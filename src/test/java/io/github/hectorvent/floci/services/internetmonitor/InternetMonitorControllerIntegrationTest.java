package io.github.hectorvent.floci.services.internetmonitor;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies CloudWatch Internet Monitor restJson1 operations used by Alchemy
 * {@code Monitor.test.ts}: GetMonitor of an unknown name is a typed
 * ResourceNotFoundException, and create/update/tag/delete converge.
 */
@QuarkusTest
class InternetMonitorControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000601";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMonitorOnANonexistentMonitorFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/v20210603/Monitors/alchemy-nonexistent-internetmonitor-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void monitorCreateUpdateTagAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "lifecycle-monitor";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "MonitorName":"lifecycle-monitor",
                          "Resources":[],
                          "MaxCityNetworksToMonitor":1,
                          "Tags":{"purpose":"alchemy-test","alchemy::id":"AppMonitor"}
                        }
                        """)
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(200)
                .body("Arn", containsString(":monitor/"))
                .body("Status", equalTo("ACTIVE"))
                .extract()
                .path("Arn");

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
                .body("Resources", equalTo(java.util.List.of()))
                .body("MaxCityNetworksToMonitor", equalTo(1))
                .body("Tags.purpose", equalTo("alchemy-test"))
                .body("Tags.'alchemy::id'", equalTo("AppMonitor"))
                .body("CreatedAt", notNullValue())
                .body("ModifiedAt", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxCityNetworksToMonitor\":2}")
                .when()
                .patch("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .body("MonitorArn", equalTo(arn))
                .body("Status", equalTo("ACTIVE"));

        String encodedArn = URLEncoder.encode(arn, StandardCharsets.UTF_8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"updated\":\"true\"}}")
                .when()
                .post("/tags/" + encodedArn)
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .body("MaxCityNetworksToMonitor", equalTo(2))
                .body("Tags.updated", equalTo("true"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/v20210603/Monitors/" + name)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Status\":\"INACTIVE\"}")
                .when()
                .patch("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .body("Status", equalTo("INACTIVE"));

        String deleteBody = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/v20210603/Monitors/" + name)
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertTrue(deleteBody == null || deleteBody.isBlank() || deleteBody.equals("{}"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/v20210603/Monitors/" + name)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateCreateReturnsConflict() {
        String authorization = auth("000000000602", EAST);
        String body = """
                {"MonitorName":"duplicate-monitor","MaxCityNetworksToMonitor":1}
                """;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/v20210603/Monitors")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/internetmonitor/aws4_request";
    }
}
