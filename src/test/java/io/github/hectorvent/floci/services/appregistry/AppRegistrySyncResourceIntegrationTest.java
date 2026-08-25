package io.github.hectorvent.floci.services.appregistry;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Alchemy {@code Bindings.test.ts} SyncResource grant probe: a nonexistent
 * CloudFormation stack is rejected with typed {@code ResourceNotFoundException}.
 */
@QuarkusTest
class AppRegistrySyncResourceIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING_STACK =
            "alchemy-appregistry-bindings-nonexistent-stack";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void syncResourceOnANonexistentCfnStackFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .post("/sync/CFN_STACK/" + MISSING_STACK)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void syncResourceWithAnInvalidResourceTypeFailsWithValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000702", EAST))
                .when()
                .post("/sync/NOT_A_TYPE/" + MISSING_STACK)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/servicecatalog/aws4_request";
    }
}
