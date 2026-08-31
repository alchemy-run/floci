package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Binding-surface operations Alchemy EMRServerless Bindings exercise: list
 * job runs/sessions, typed not-found probes, GetResourceDashboard gate,
 * StartSession validation, and start/submit/cancel/stop.
 */
@QuarkusTest
class EmrServerlessBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "00abcdefabcdef01";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/alchemy-test-emrs-bind-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listJobRunsAndSessionsOnAnEmptyApplication() {
        String authorization = auth(EAST);
        String applicationId = createApplication(authorization);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/jobruns")
                .then()
                .statusCode(200)
                .body("jobRuns.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/sessions")
                .then()
                .statusCode(200)
                .body("sessions.size()", equalTo(0));
    }

    @Test
    void missingJobRunAndSessionSubresourcesAreResourceNotFound() {
        String authorization = auth(EAST);
        String applicationId = createApplication(authorization);
        String base = "/applications/" + applicationId;

        given().header("Authorization", authorization)
                .when().get(base + "/jobruns/" + MISSING)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().get(base + "/jobruns/" + MISSING + "/dashboard")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().get(base + "/jobruns/" + MISSING + "/attempts")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().delete(base + "/jobruns/" + MISSING)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().get(base + "/sessions/" + MISSING)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().get(base + "/sessions/" + MISSING + "/endpoint")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", authorization)
                .when().delete(base + "/sessions/" + MISSING)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getResourceDashboardIsAccessDenied() {
        String authorization = auth(EAST);
        String applicationId = createApplication(authorization);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/dashboard")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void startSessionWithoutInteractiveConfigIsValidationException() {
        String authorization = auth(EAST);
        String applicationId = createApplication(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "executionRoleArn": "%s",
                          "clientToken": "%s"
                        }
                        """.formatted(ROLE, UUID.randomUUID()))
                .when()
                .post("/applications/" + applicationId + "/sessions")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void startSubmitCancelStopJobRunRoundTrip() {
        String authorization = auth(EAST);
        String applicationId = createApplication(authorization);

        given()
                .header("Authorization", authorization)
                .when()
                .post("/applications/" + applicationId + "/start")
                .then()
                .statusCode(200);

        String jobRunId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken": "%s",
                          "executionRoleArn": "%s",
                          "jobDriver": {
                            "sparkSubmit": {
                              "entryPoint": "local:///usr/lib/spark/examples/src/main/python/pi.py"
                            }
                          },
                          "executionTimeoutMinutes": 10
                        }
                        """.formatted(UUID.randomUUID(), ROLE))
                .when()
                .post("/applications/" + applicationId + "/jobruns")
                .then()
                .statusCode(200)
                .body("jobRunId", notNullValue())
                .body("arn", startsWith("arn:aws:emr-serverless:"))
                .body("arn", org.hamcrest.Matchers.containsString("/jobruns/"))
                .extract()
                .path("jobRunId");

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then()
                .statusCode(200)
                .body("jobRunId", equalTo(jobRunId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then()
                .statusCode(200)
                .body("jobRun.jobRunId", equalTo(jobRunId))
                .body("jobRun.state", equalTo("CANCELLED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId + "/jobruns")
                .then()
                .statusCode(200)
                .body("jobRuns.id", hasItem(jobRunId));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/applications/" + applicationId + "/stop")
                .then()
                .statusCode(200);
    }

    private static String createApplication(String authorization) {
        String name = "alchemy-emrs-bind-" + UUID.randomUUID().toString().substring(0, 8);
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name": "%s",
                          "releaseLabel": "emr-7.5.0",
                          "type": "SPARK",
                          "clientToken": "%s",
                          "autoStartConfiguration": {"enabled": true},
                          "autoStopConfiguration": {"enabled": true, "idleTimeoutMinutes": 1}
                        }
                        """.formatted(name, UUID.randomUUID()))
                .when()
                .post("/applications")
                .then()
                .statusCode(200)
                .extract()
                .path("applicationId");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/emr-serverless/aws4_request";
    }
}
