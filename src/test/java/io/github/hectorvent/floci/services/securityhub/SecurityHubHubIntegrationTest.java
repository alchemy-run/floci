package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Security Hub restJson1 Hub enablement — the operations Alchemy
 * {@code Hub.test.ts} drives.
 */
@QuarkusTest
class SecurityHubHubIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000811";
    private static final String HUB_ARN =
            "arn:aws:securityhub:" + EAST + ":" + ACCOUNT + ":hub/default";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeHubWhenNotEnabledIsInvalidAccess() {
        given()
                .header("Authorization", auth("000000000812", EAST))
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"))
                .body("message", equalTo("Account is not subscribed to AWS Security Hub."));
    }

    @Test
    void enableUpdateRetagAndDisableHub() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"EnableDefaultStandards\":false,\"ControlFindingGenerator\":\"SECURITY_CONTROL\","
                        + "\"Tags\":{\"env\":\"test\",\"alchemy::id\":\"Hub\"}}")
                .when()
                .post("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .body("HubArn", equalTo(HUB_ARN))
                .body("AutoEnableControls", equalTo(true))
                .body("ControlFindingGenerator", equalTo("SECURITY_CONTROL"))
                .body("SubscribedAt", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + HUB_ARN)
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("test"))
                .body("Tags['alchemy::id']", equalTo("Hub"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AutoEnableControls\":false,\"ControlFindingGenerator\":\"SECURITY_CONTROL\"}")
                .when()
                .patch("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .body("AutoEnableControls", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"env\":\"prod\"}}")
                .when()
                .post("/tags/" + HUB_ARN)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + HUB_ARN)
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("prod"))
                .body("Tags['alchemy::id']", equalTo("Hub"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));
    }

    @Test
    void enableWhenAlreadyEnabledIsConflict() {
        String authorization = auth("000000000813", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"EnableDefaultStandards\":false}")
                .when()
                .post("/accounts")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"EnableDefaultStandards\":false}")
                .when()
                .post("/accounts")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceConflictException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/securityhub/aws4_request";
    }
}
