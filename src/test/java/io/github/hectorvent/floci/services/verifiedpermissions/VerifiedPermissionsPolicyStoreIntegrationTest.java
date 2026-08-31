package io.github.hectorvent.floci.services.verifiedpermissions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Policy store + schema + static policy lifecycle matching Alchemy's
 * {@code PolicyStore.test.ts}: create (STRICT), PutSchema, CreatePolicy,
 * update validation mode and policy description, then delete.
 */
@QuarkusTest
class VerifiedPermissionsPolicyStoreIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/verifiedpermissions/aws4_request";
    private static final String TARGET = "VerifiedPermissions.";
    private static final String SCHEMA = """
            {"PhotoApp":{"entityTypes":{"User":{},"Photo":{}},"actions":{"viewPhoto":{"appliesTo":{"principalTypes":["User"],"resourceTypes":["Photo"]}}}}}
            """;
    private static final String STATEMENT = """
            permit(
              principal == PhotoApp::User::"alice",
              action == PhotoApp::Action::"viewPhoto",
              resource
            );
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStoreSchemaPolicyUpdateThenDelete() {
        String storeId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePolicyStore")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "validationSettings": {"mode": "STRICT"},
                          "description": "photo app",
                          "deletionProtection": "DISABLED",
                          "tags": {"Environment": "test", "alchemy::id": "Store"}
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyStoreId", org.hamcrest.Matchers.notNullValue())
                .body("arn", startsWith("arn:aws:verifiedpermissions:"))
        .extract().path("policyStoreId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"tags\":true}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("validationSettings.mode", equalTo("STRICT"))
                .body("description", equalTo("photo app"))
                .body("deletionProtection", equalTo("DISABLED"))
                .body("tags.Environment", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("Store"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "PutSchema")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"definition\":{\"cedarJson\":" + quote(SCHEMA) + "}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("namespaces", hasItem("PhotoApp"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetSchema")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("schema", org.hamcrest.Matchers.containsString("PhotoApp"));

        String policyId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePolicy")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"definition\":{\"static\":{\"statement\":"
                        + jsonString(STATEMENT) + ",\"description\":\"initial policy\"}}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyType", equalTo("STATIC"))
                .body("effect", equalTo("Permit"))
        .extract().path("policyId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicy")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + policyId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyType", equalTo("STATIC"))
                .body("definition.static.description", equalTo("initial policy"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UpdatePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId
                        + "\",\"validationSettings\":{\"mode\":\"OFF\"},\"description\":\"updated app\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyStoreId", equalTo(storeId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("validationSettings.mode", equalTo("OFF"))
                .body("description", equalTo("updated app"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UpdatePolicy")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + policyId
                        + "\",\"definition\":{\"static\":{\"statement\":" + jsonString(STATEMENT)
                        + ",\"description\":\"updated policy\"}}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyId", equalTo(policyId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListPolicyStores")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyStores.policyStoreId", hasItem(storeId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeletePolicy")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + policyId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeletePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("POLICY_STORE"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeletePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"does-not-exist\",\"tags\":true}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPolicyWithoutSchemaInStrictModeFails() {
        String storeId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"validationSettings\":{\"mode\":\"STRICT\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
        .extract().path("policyStoreId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePolicy")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId
                        + "\",\"definition\":{\"static\":{\"statement\":\"permit(principal, action, resource);\"}}}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeletePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void tagAndUntagPolicyStore() {
        String arn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePolicyStore")
                .header("Authorization", AUTH)
                .body("{\"validationSettings\":{\"mode\":\"OFF\"},\"tags\":{\"keep\":\"yes\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
        .extract().path("arn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "TagResource")
                .header("Authorization", AUTH)
                .body("{\"resourceArn\":\"" + arn + "\",\"tags\":{\"Environment\":\"prod\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        String storeId = arn.substring(arn.lastIndexOf('/') + 1);
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"tags\":true}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("prod"))
                .body("tags.keep", equalTo("yes"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UntagResource")
                .header("Authorization", AUTH)
                .body("{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"keep\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetPolicyStore")
                .header("Authorization", AUTH)
                .body("{\"policyStoreId\":\"" + storeId + "\",\"tags\":true}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("tags.keep", not(equalTo("yes")))
                .body("tags.Environment", equalTo("prod"));
    }

    private static String quote(String json) {
        return jsonString(json.strip());
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
