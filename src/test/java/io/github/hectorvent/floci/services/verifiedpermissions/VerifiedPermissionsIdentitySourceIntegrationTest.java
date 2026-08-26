package io.github.hectorvent.floci.services.verifiedpermissions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy {@code AWS.VerifiedPermissions.IdentitySource}:
 * create an OIDC source, update client ids in place, delete.
 */
@QuarkusTest
class VerifiedPermissionsIdentitySourceIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/verifiedpermissions/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getIdentitySource_missing_returnsResourceNotFound() {
        avp("GetIdentitySource",
                "{\"policyStoreId\":\"PSmissing\",\"identitySourceId\":\"ISmissing\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("POLICY_STORE"));
    }

    @Test
    void identitySource_oidcCreateUpdateDelete() {
        String policyStoreId = avp("CreatePolicyStore",
                "{\"validationSettings\":{\"mode\":\"OFF\"}}")
                .then()
                .statusCode(200)
                .body("policyStoreId", notNullValue())
                .extract().path("policyStoreId");

        String createBody = """
                {
                  "policyStoreId": "%s",
                  "principalEntityType": "PhotoApp::User",
                  "configuration": {
                    "openIdConnectConfiguration": {
                      "issuer": "https://accounts.google.com",
                      "tokenSelection": {
                        "identityTokenOnly": {
                          "clientIds": ["alchemy-test-client"]
                        }
                      }
                    }
                  }
                }
                """.formatted(policyStoreId);

        String identitySourceId = avp("CreateIdentitySource", createBody)
                .then()
                .statusCode(200)
                .body("identitySourceId", notNullValue())
                .body("policyStoreId", equalTo(policyStoreId))
                .extract().path("identitySourceId");

        avp("GetIdentitySource",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"identitySourceId\":\"" + identitySourceId + "\"}")
                .then()
                .statusCode(200)
                .body("principalEntityType", equalTo("PhotoApp::User"))
                .body("configuration.openIdConnectConfiguration.issuer",
                        equalTo("https://accounts.google.com"))
                .body("configuration.openIdConnectConfiguration.tokenSelection.identityTokenOnly.clientIds",
                        contains("alchemy-test-client"))
                .body("configuration.openIdConnectConfiguration.tokenSelection.identityTokenOnly.principalIdClaim",
                        equalTo("sub"));

        String updateBody = """
                {
                  "policyStoreId": "%s",
                  "identitySourceId": "%s",
                  "principalEntityType": "PhotoApp::User",
                  "updateConfiguration": {
                    "openIdConnectConfiguration": {
                      "issuer": "https://accounts.google.com",
                      "tokenSelection": {
                        "identityTokenOnly": {
                          "clientIds": ["alchemy-test-client", "second-client"]
                        }
                      }
                    }
                  }
                }
                """.formatted(policyStoreId, identitySourceId);

        avp("UpdateIdentitySource", updateBody)
                .then()
                .statusCode(200)
                .body("identitySourceId", equalTo(identitySourceId));

        avp("GetIdentitySource",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"identitySourceId\":\"" + identitySourceId + "\"}")
                .then()
                .statusCode(200)
                .body("configuration.openIdConnectConfiguration.tokenSelection.identityTokenOnly.clientIds",
                        contains("alchemy-test-client", "second-client"));

        avp("DeleteIdentitySource",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"identitySourceId\":\"" + identitySourceId + "\"}")
                .then()
                .statusCode(200);

        avp("GetIdentitySource",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"identitySourceId\":\"" + identitySourceId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("IDENTITY_SOURCE"));

        avp("DeletePolicyStore", "{\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then()
                .statusCode(200);
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
