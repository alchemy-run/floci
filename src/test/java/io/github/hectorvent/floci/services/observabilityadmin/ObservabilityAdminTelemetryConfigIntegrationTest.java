package io.github.hectorvent.floci.services.observabilityadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Verifies Get/Start/StopTelemetryEvaluation used by Alchemy TelemetryConfig. */
@QuarkusTest
class ObservabilityAdminTelemetryConfigIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getStatusIsNotStartedBeforeOnboarding() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000002601", EAST))
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("NOT_STARTED"))
                .body("HomeRegion", nullValue());
    }

    @Test
    void startStopAndRestartTelemetryEvaluationLifecycle() {
        String authorization = auth("000000002603", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StartTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("RUNNING"))
                .body("HomeRegion", equalTo(EAST));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StartTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("RUNNING"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StopTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("STOPPED"))
                .body("HomeRegion", equalTo(EAST));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StartTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("RUNNING"));
    }

    @Test
    void evaluationStatusIsIsolatedByAccount() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000002604", EAST))
                .body("{}")
                .when()
                .post("/StartTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", auth("000000002605", EAST))
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("NOT_STARTED"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/observabilityadmin/aws4_request";
    }
}
