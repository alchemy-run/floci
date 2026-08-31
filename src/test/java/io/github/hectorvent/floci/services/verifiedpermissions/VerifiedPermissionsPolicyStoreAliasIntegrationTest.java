package io.github.hectorvent.floci.services.verifiedpermissions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy {@code AWS.VerifiedPermissions.PolicyStoreAlias}:
 * live AWS currently rejects CreatePolicyStoreAlias with ValidationException
 * ("Invalid input") because the API is not generally available.
 */
@QuarkusTest
class VerifiedPermissionsPolicyStoreAliasIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/verifiedpermissions/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createPolicyStoreAlias_rejectsWithValidationException() {
        String policyStoreId = avp("CreatePolicyStore", "{\"validationSettings\":{\"mode\":\"OFF\"}}")
                .then()
                .statusCode(200)
                .body("policyStoreId", notNullValue())
                .extract()
                .path("policyStoreId");

        avp("CreatePolicyStoreAlias",
                "{\"aliasName\":\"alchemy-probe-alias\",\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Invalid input"));
    }

    @Test
    void createPolicyStoreAlias_missingAliasName_isValidationException() {
        avp("CreatePolicyStoreAlias", "{\"policyStoreId\":\"PSmissing\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static Response avp(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "VerifiedPermissions." + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
