package io.github.hectorvent.floci.services.glacier;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Covers the Glacier vault control plane Alchemy's Vault resource reconciles:
 * Create/Describe/DeleteVault, tags, notification configuration, access policy,
 * and in-progress vault lock (initiate/get/abort).
 */
@QuarkusTest
class GlacierVaultIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "alchemy-nonexistent-glacier-vault-probe";
    private static final String VAULT = "alchemy-glacier-vault-lifecycle";
    private static final String LOCKED = "alchemy-glacier-vault-lock";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"deny-archive-deletes","Effect":"Deny","Principal":"*","Action":["glacier:DeleteArchive"],"Resource":["arn:aws:glacier:us-east-1:000000000000:vaults/alchemy-glacier-vault-lifecycle"]}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingVaultReturnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + MISSING)
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeTagNotifyPolicyDeleteLifecycle() {
        String authorization = auth(EAST);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .put("/-/vaults/" + VAULT)
                .then()
                .statusCode(201)
                .header("Location", containsString("/vaults/" + VAULT));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT)
                .then()
                .statusCode(200)
                .body("VaultName", equalTo(VAULT))
                .body("VaultARN", startsWith("arn:aws:glacier:"))
                .body("VaultARN", containsString(":vaults/" + VAULT))
                .body("CreationDate", notNullValue());

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/notification-configuration")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/access-policy")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("{\"Tags\":{\"fixture\":\"glacier-vault\"}}")
                .when()
                .post("/-/vaults/" + VAULT + "/tags?operation=add")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/tags")
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("glacier-vault"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("""
                        {"SNSTopic":"arn:aws:sns:us-east-1:000000000000:VaultEvents","Events":["ArchiveRetrievalCompleted"]}
                        """)
                .when()
                .put("/-/vaults/" + VAULT + "/notification-configuration")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/notification-configuration")
                .then()
                .statusCode(200)
                .body("SNSTopic", equalTo("arn:aws:sns:us-east-1:000000000000:VaultEvents"))
                .body("Events", hasItems("ArchiveRetrievalCompleted"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("{\"Policy\":" + quote(POLICY) + "}")
                .when()
                .put("/-/vaults/" + VAULT + "/access-policy")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/access-policy")
                .then()
                .statusCode(200)
                .body("Policy", containsString("deny-archive-deletes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("{\"Tags\":{\"stage\":\"two\"}}")
                .when()
                .post("/-/vaults/" + VAULT + "/tags?operation=add")
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("{\"TagKeys\":[\"stage\"]}")
                .when()
                .post("/-/vaults/" + VAULT + "/tags?operation=remove")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/tags")
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("glacier-vault"))
                .body("Tags.stage", equalTo(null));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + VAULT + "/access-policy")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/access-policy")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + VAULT + "/notification-configuration")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT + "/notification-configuration")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + VAULT)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + VAULT)
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));
    }

    @Test
    void vaultLockInitiateGetAbortLifecycle() {
        String authorization = auth(EAST);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .put("/-/vaults/" + LOCKED)
                .then()
                .statusCode(201);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + LOCKED + "/lock-policy")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .body("{\"Policy\":" + quote(POLICY) + "}")
                .when()
                .post("/-/vaults/" + LOCKED + "/lock-policy")
                .then()
                .statusCode(201)
                .header("x-amz-lock-id", notNullValue());

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + LOCKED + "/lock-policy")
                .then()
                .statusCode(200)
                .body("State", equalTo("InProgress"))
                .body("Policy", containsString("deny-archive-deletes"))
                .body("CreationDate", notNullValue())
                .body("ExpirationDate", notNullValue());

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + LOCKED + "/lock-policy")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + LOCKED + "/lock-policy")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + LOCKED)
                .then()
                .statusCode(204);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/glacier/aws4_request";
    }
}
