package io.github.hectorvent.floci.services.glacier;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Covers the Glacier restJson1 operations Alchemy's Bindings fixture probes:
 * ListJobs / UploadArchive on a missing vault (typed ResourceNotFoundException),
 * empty-vault ListJobs + ListMultipartUploads, and the typed 400s for a wrong
 * checksum and a non-power-of-two part size.
 */
@QuarkusTest
class GlacierBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "alchemy-nonexistent-glacier-vault-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listJobsOnMissingVaultReturnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + MISSING + "/jobs")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void uploadArchiveOnMissingVaultReturnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .header("x-amz-glacier-version", "2012-06-01")
                .header("x-amz-sha256-tree-hash", "0".repeat(64))
                .contentType("application/octet-stream")
                .body("alchemy-glacier-probe")
                .when()
                .post("/-/vaults/" + MISSING + "/archives")
                .then()
                .statusCode(404)
                .body("code", equalTo("ResourceNotFoundException"));
    }

    @Test
    void emptyVaultListsJobsAndUploads() {
        String vault = "alchemy-glacier-bindings-empty";
        String authorization = auth(EAST);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .put("/-/vaults/" + vault)
                .then()
                .statusCode(201);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + vault + "/jobs")
                .then()
                .statusCode(200)
                .body("JobList", hasSize(0));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .get("/-/vaults/" + vault + "/multipart-uploads")
                .then()
                .statusCode(200)
                .body("UploadsList", hasSize(0));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + vault)
                .then()
                .statusCode(204);
    }

    @Test
    void wrongChecksumAndTinyPartSizeAreTyped400s() {
        String vault = "alchemy-glacier-bindings-reject";
        String authorization = auth(EAST);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .put("/-/vaults/" + vault)
                .then()
                .statusCode(201);

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .header("x-amz-sha256-tree-hash", "0".repeat(64))
                .contentType("application/octet-stream")
                .body("alchemy-glacier-binding-probe")
                .when()
                .post("/-/vaults/" + vault + "/archives")
                .then()
                .statusCode(400)
                .body("code", equalTo("InvalidParameterValueException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .header("x-amz-part-size", "1")
                .when()
                .post("/-/vaults/" + vault + "/multipart-uploads")
                .then()
                .statusCode(400)
                .body("code", equalTo("InvalidParameterValueException"));

        given()
                .header("Authorization", authorization)
                .header("x-amz-glacier-version", "2012-06-01")
                .when()
                .delete("/-/vaults/" + vault)
                .then()
                .statusCode(204);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/glacier/aws4_request";
    }
}
