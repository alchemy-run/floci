package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-path Control Tower restJson1: catalog baselines, empty enablement
 * lists, and typed not-found errors for operation/landing-zone identifiers.
 */
@QuarkusTest
class ControlTowerBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000501";
    private static final String MISSING_OP = "00000000-0000-0000-0000-000000000000";
    private static final String MISSING_LZ =
            "arn:aws:controltower:us-west-2:111111111111:landingzone/AAAAAAAAAAAAAAAA";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listBaselinesIncludesAwsControlTowerBaseline() {
        String arn = given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{}")
                .when()
                .post("/list-baselines")
                .then()
                .statusCode(200)
                .body("baselines.name", hasItem("AWSControlTowerBaseline"))
                .extract()
                .path("baselines.find { it.name == 'AWSControlTowerBaseline' }.arn");

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"baselineIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/get-baseline")
                .then()
                .statusCode(200)
                .body("name", equalTo("AWSControlTowerBaseline"))
                .body("arn", equalTo(arn));
    }

    @Test
    void getBaselineForAnUnknownIdentifierFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"baselineIdentifier\":\"arn:aws:controltower:us-east-1::baseline/missing\"}")
                .when()
                .post("/get-baseline")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void accountLevelListsAreEmptyWithoutALandingZone() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-enabled-baselines")
                .then()
                .statusCode(200)
                .body("enabledBaselines.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-enabled-controls")
                .then()
                .statusCode(200)
                .body("enabledControls.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-control-operations")
                .then()
                .statusCode(200)
                .body("controlOperations.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-landingzones")
                .then()
                .statusCode(200)
                .body("landingZones.size()", lessThanOrEqualTo(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/list-landingzone-operations")
                .then()
                .statusCode(200)
                .body("landingZoneOperations.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void missingIdentifiersSurfaceResourceNotFoundException() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"landingZoneIdentifier\":\"" + MISSING_LZ + "\"}")
                .when()
                .post("/get-landingzone")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + MISSING_OP + "\"}")
                .when()
                .post("/get-control-operation")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + MISSING_OP + "\"}")
                .when()
                .post("/get-baseline-operation")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"operationIdentifier\":\"" + MISSING_OP + "\"}")
                .when()
                .post("/get-landingzone-operation")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void enableControlRecordsAGettableControlOperation() {
        String authorization = auth("000000000502", EAST);
        String operationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "controlIdentifier":"arn:aws:controltower:us-east-1::control/AWS-GR_ENCRYPTED_VOLUMES",
                          "targetIdentifier":"arn:aws:organizations::000000000502:ou/o-example/ou-aaaa-bbbbbbbb"
                        }
                        """)
                .when()
                .post("/enable-control")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
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
                .body("controlOperation.status", equalTo("SUCCEEDED"))
                .body("controlOperation.operationType", equalTo("ENABLE_CONTROL"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/controltower/aws4_request";
    }
}
