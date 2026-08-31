package io.github.hectorvent.floci.services.verifiedpermissions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy {@code AWS.VerifiedPermissions.PolicyTemplate}:
 * create a template, instantiate a template-linked policy, update the
 * description in place, then delete.
 */
@QuarkusTest
class VerifiedPermissionsPolicyTemplateIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/verifiedpermissions/aws4_request";
    private static final String STATEMENT = """
            permit(
              principal == ?principal,
              action == PhotoApp::Action::"viewPhoto",
              resource
            );
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPolicyTemplate_missing_returnsResourceNotFound() {
        avp("GetPolicyTemplate",
                "{\"policyStoreId\":\"PSmissing\",\"policyTemplateId\":\"PTmissing\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("POLICY_STORE"));
    }

    @Test
    void policyTemplate_createLinkedPolicyUpdateDescriptionDelete() {
        String policyStoreId = avp("CreatePolicyStore",
                "{\"validationSettings\":{\"mode\":\"OFF\"}}")
                .then()
                .statusCode(200)
                .body("policyStoreId", notNullValue())
                .extract().path("policyStoreId");

        String policyTemplateId = avp("CreatePolicyTemplate", """
                {
                  "policyStoreId": "%s",
                  "statement": %s,
                  "description": "initial description"
                }
                """.formatted(policyStoreId, jsonString(STATEMENT)))
                .then()
                .statusCode(200)
                .body("policyTemplateId", notNullValue())
                .body("policyStoreId", equalTo(policyStoreId))
                .extract().path("policyTemplateId");

        avp("GetPolicyTemplate",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"policyTemplateId\":\"" + policyTemplateId + "\"}")
                .then()
                .statusCode(200)
                .body("statement", containsString("?principal"))
                .body("description", equalTo("initial description"));

        avp("ListPolicyTemplates", "{\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then()
                .statusCode(200)
                .body("policyTemplates.policyTemplateId", hasItem(policyTemplateId));

        String policyId = avp("CreatePolicy", """
                {
                  "policyStoreId": "%s",
                  "definition": {
                    "templateLinked": {
                      "policyTemplateId": "%s",
                      "principal": {"entityType":"PhotoApp::User","entityId":"alice"}
                    }
                  }
                }
                """.formatted(policyStoreId, policyTemplateId))
                .then()
                .statusCode(200)
                .body("policyType", equalTo("TEMPLATE_LINKED"))
                .extract().path("policyId");

        avp("GetPolicy",
                "{\"policyStoreId\":\"" + policyStoreId + "\",\"policyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("policyType", equalTo("TEMPLATE_LINKED"))
                .body("definition.templateLinked.policyTemplateId", equalTo(policyTemplateId))
                .body("definition.templateLinked.principal.entityId", equalTo("alice"));

        avp("UpdatePolicyTemplate", """
                {
                  "policyStoreId": "%s",
                  "policyTemplateId": "%s",
                  "statement": %s,
                  "description": "updated description"
                }
                """.formatted(policyStoreId, policyTemplateId, jsonString(STATEMENT)))
                .then()
                .statusCode(200)
                .body("policyTemplateId", equalTo(policyTemplateId));

        avp("GetPolicyTemplate",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"policyTemplateId\":\"" + policyTemplateId + "\"}")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated description"));

        avp("DeletePolicy",
                "{\"policyStoreId\":\"" + policyStoreId + "\",\"policyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200);

        avp("DeletePolicyTemplate",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"policyTemplateId\":\"" + policyTemplateId + "\"}")
                .then()
                .statusCode(200);

        avp("GetPolicyTemplate",
                "{\"policyStoreId\":\"" + policyStoreId
                        + "\",\"policyTemplateId\":\"" + policyTemplateId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("POLICY_TEMPLATE"));

        avp("DeletePolicyStore", "{\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then()
                .statusCode(200);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
