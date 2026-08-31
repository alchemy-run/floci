package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Control Tower restJson1 probes and enable/disable lifecycle. */
@QuarkusTest
class ControlTowerIntegrationTest {

    private static final String WEST = "us-west-2";
    private static final String MISSING_OPERATION = "00000000-0000-0000-0000-000000000000";
    private static final String MISSING_CONTROL =
            "arn:aws:controltower:us-west-2:111111111111:enabledcontrol/AAAAAAAAAAAAAAAA";
    private static final String MISSING_BASELINE =
            "arn:aws:controltower:us-west-2:111111111111:enabledbaseline/AAAAAAAAAAAAAAAA";
    private static final String CONTROL_ARN =
            "arn:aws:controltower:us-west-2::control/AWS-GR_ENCRYPTED_VOLUMES";
    private static final String OU_ARN =
            "arn:aws:organizations::111111111111:ou/o-example/ou-example";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getLandingZoneOperationOnABogusIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(WEST))
                .body("{\"operationIdentifier\":\"" + MISSING_OPERATION + "\"}")
                .when()
                .post("/get-landingzone-operation")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listLandingZonesYieldsAnArray() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(WEST))
                .body("{}")
                .when()
                .post("/list-landingzones")
                .then()
                .statusCode(200)
                .body("landingZones", notNullValue());
    }

    @Test
    void getEnabledControlOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(WEST))
                .body("{\"enabledControlIdentifier\":\"" + MISSING_CONTROL + "\"}")
                .when()
                .post("/get-enabled-control")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getEnabledBaselineOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(WEST))
                .body("{\"enabledBaselineIdentifier\":\"" + MISSING_BASELINE + "\"}")
                .when()
                .post("/get-enabled-baseline")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listBaselinesIncludesAwsControlTowerBaseline() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(WEST))
                .body("{}")
                .when()
                .post("/list-baselines")
                .then()
                .statusCode(200)
                .body("baselines.name", hasItem("AWSControlTowerBaseline"));
    }

    @Test
    void enableGetAndDisableControlLifecycle() {
        String authorization = auth(WEST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"controlIdentifier\":\"" + CONTROL_ARN + "\",\"targetIdentifier\":\"" + OU_ARN
                        + "\",\"tags\":{\"fixture\":\"controltower-enabled-control\"}}")
                .when()
                .post("/enable-control")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .body("operationIdentifier", notNullValue())
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledControlIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-enabled-control")
                .then()
                .statusCode(200)
                .body("enabledControlDetails.controlIdentifier", equalTo(CONTROL_ARN))
                .body("enabledControlDetails.targetIdentifier", equalTo(OU_ARN))
                .body("enabledControlDetails.statusSummary.status", equalTo("SUCCEEDED"));

        String operationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledControlIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/disable-control")
                .then()
                .statusCode(200)
                .body("operationIdentifier", notNullValue())
                .extract()
                .path("operationIdentifier");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + operationId + "\"}")
                .when()
                .post("/get-control-operation")
                .then()
                .statusCode(200)
                .body("controlOperation.status", equalTo("SUCCEEDED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledControlIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-enabled-control")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void enableGetAndDisableBaselineLifecycle() {
        String authorization = auth(WEST);
        String baselineArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-baselines")
                .then()
                .statusCode(200)
                .body("baselines", hasSize(4))
                .extract()
                .path("baselines.find { it.name == 'AWSControlTowerBaseline' }.arn");

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"baselineIdentifier\":\"" + baselineArn
                        + "\",\"baselineVersion\":\"4.0\",\"targetIdentifier\":\"" + OU_ARN
                        + "\",\"tags\":{\"fixture\":\"controltower-enabled-baseline\"}}")
                .when()
                .post("/enable-baseline")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledBaselineIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-enabled-baseline")
                .then()
                .statusCode(200)
                .body("enabledBaselineDetails.statusSummary.status", equalTo("SUCCEEDED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledBaselineIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/disable-baseline")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enabledBaselineIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-enabled-baseline")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/controltower/aws4_request";
    }
}
