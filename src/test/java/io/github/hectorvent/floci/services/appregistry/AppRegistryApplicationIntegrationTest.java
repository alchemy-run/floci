package io.github.hectorvent.floci.services.appregistry;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Alchemy {@code Application.test.ts} ungated probe: new-customer
 * {@code CreateApplication} is denied with typed {@code AccessDeniedException}
 * because AppRegistry is in maintenance mode.
 */
@QuarkusTest
class AppRegistryApplicationIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createApplicationIsDeniedWithMaintenanceModeAccessDeniedException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000601", EAST))
                .body("""
                        {
                          "name":"alchemy-appregistry-maintenance-probe",
                          "clientToken":"alchemy-appregistry-maintenance-probe"
                        }
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("maintenance mode"));
    }

    @Test
    void getApplicationOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000602", EAST))
                .when()
                .get("/applications/nonexistent-application")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/servicecatalog/aws4_request";
    }
}
