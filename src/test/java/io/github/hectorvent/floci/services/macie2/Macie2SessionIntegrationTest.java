package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Macie2 restJson1 session enablement — the operations Alchemy
 * {@code Session.test.ts} drives.
 */
@QuarkusTest
class Macie2SessionIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000911";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMacieSessionWhenNotEnabledIsAccessDenied() {
        given()
                .header("Authorization", auth("000000000912", EAST))
                .when()
                .get("/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Macie is not enabled"));
    }

    @Test
    void enableUpdatePauseAndDisableMacieSession() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"ENABLED\",\"findingPublishingFrequency\":\"SIX_HOURS\"}")
                .when()
                .post("/macie")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/macie")
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("SIX_HOURS"))
                .body("serviceRole", notNullValue())
                .body("createdAt", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"PAUSED\",\"findingPublishingFrequency\":\"FIFTEEN_MINUTES\"}")
                .when()
                .patch("/macie")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/macie")
                .then()
                .statusCode(200)
                .body("status", equalTo("PAUSED"))
                .body("findingPublishingFrequency", equalTo("FIFTEEN_MINUTES"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/macie")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void enableMacieWhenAlreadyEnabledIsConflict() {
        String authorization = auth("000000000913", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"ENABLED\"}")
                .when()
                .post("/macie")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"ENABLED\"}")
                .when()
                .post("/macie")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/macie2/aws4_request";
    }
}
