package io.github.hectorvent.floci.services.fms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * JSON 1.1 FMS admin-account coverage used by Alchemy:
 * {@code GetAdminAccount} typed {@code ResourceNotFoundException} when none is
 * designated, plus associate / disassociate round-trip.
 */
@QuarkusTest
class FmsAdminAccountIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/fms/aws4_request";
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAdminAccount_withNoAdmin_returnsResourceNotFound() {
        fms("DisassociateAdminAccount", "{}");
        fms("GetAdminAccount", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateAdminAccount_getAndDisassociate() {
        fms("DisassociateAdminAccount", "{}");

        fms("AssociateAdminAccount", "{\"AdminAccount\":\"" + ACCOUNT + "\"}")
                .then()
                .statusCode(200);

        fms("GetAdminAccount", "{}")
                .then()
                .statusCode(200)
                .body("AdminAccount", equalTo(ACCOUNT))
                .body("RoleStatus", equalTo("READY"));

        fms("DisassociateAdminAccount", "{}")
                .then()
                .statusCode(200);

        fms("GetAdminAccount", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateAdminAccount_missingAccount_returnsInvalidInput() {
        fms("AssociateAdminAccount", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void disassociateAdminAccount_whenNone_returnsResourceNotFound() {
        fms("DisassociateAdminAccount", "{}");
        fms("DisassociateAdminAccount", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response fms(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSFMS_20180101." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
