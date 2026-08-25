package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Verifies EFS restJson1 file-system CRUD, policy, lifecycle, backup, tags, and not-found. */
@QuarkusTest
class EfsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"AllowMountViaMountTarget","Effect":"Allow","Principal":{"AWS":"*"},"Action":["elasticfilesystem:ClientMount"]}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingFileSystemFailsWithFileSystemNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("FileSystemId", "fs-missing00000000")
                .when()
                .get("/2015-02-01/file-systems")
                .then()
                .statusCode(404)
                .body("__type", equalTo("FileSystemNotFound"))
                .body("ErrorCode", equalTo("FileSystemNotFound"));
    }

    @Test
    void createDescribeUpdateTagPolicyBackupDeleteLifecycle() {
        String authorization = auth(EAST);
        String token = "efs-test-token-" + System.nanoTime();

        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "CreationToken":"%s",
                          "Encrypted":true,
                          "ThroughputMode":"elastic",
                          "Tags":[{"Key":"purpose","Value":"alchemy-efs-test"}]
                        }
                        """.formatted(token))
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystemId", startsWith("fs-"))
                .body("FileSystemArn", startsWith("arn:aws:elasticfilesystem:"))
                .body("Encrypted", equalTo(true))
                .body("PerformanceMode", equalTo("generalPurpose"))
                .body("ThroughputMode", equalTo("elastic"))
                .body("LifeCycleState", equalTo("available"))
                .body("FileSystemProtection.ReplicationOverwriteProtection", equalTo("ENABLED"))
                .extract()
                .path("FileSystemId");

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystems", hasSize(1))
                .body("FileSystems[0].FileSystemId", equalTo(fileSystemId))
                .body("FileSystems[0].Tags.find { it.Key == 'purpose' }.Value",
                        equalTo("alchemy-efs-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"CreationToken":"%s","Encrypted":true,"ThroughputMode":"elastic"}
                        """.formatted(token))
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(409)
                .body("__type", equalTo("FileSystemAlreadyExists"))
                .body("FileSystemId", equalTo(fileSystemId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"LifecyclePolicies":[
                          {"TransitionToIA":"AFTER_30_DAYS"},
                          {"TransitionToPrimaryStorageClass":"AFTER_1_ACCESS"}
                        ]}
                        """)
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(2));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(2));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Policy\":" + quote(POLICY) + "}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(200)
                .body("Policy", equalTo(POLICY.trim()));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(200)
                .body("Policy", equalTo(POLICY.trim()));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"BackupPolicy\":{\"Status\":\"ENABLED\"}}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
                .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo("ENABLED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
                .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ReplicationOverwriteProtection\":\"DISABLED\"}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/protection")
                .then()
                .statusCode(200)
                .body("FileSystemProtection.ReplicationOverwriteProtection", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-efs-test-updated\"}]}")
                .when()
                .post("/2015-02-01/resource-tags/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystems[0].Tags.find { it.Key == 'purpose' }.Value",
                        equalTo("alchemy-efs-test-updated"))
                .body("FileSystems[0].FileSystemProtection.ReplicationOverwriteProtection",
                        equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"LifecyclePolicies\":[]}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(404)
                .body("__type", equalTo("PolicyNotFound"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"BackupPolicy\":{\"Status\":\"DISABLED\"}}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
                .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ReplicationOverwriteProtection\":\"ENABLED\"}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/protection")
                .then()
                .statusCode(200);

        String unencrypted = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"CreationToken":"%s-plain","Encrypted":false}
                        """.formatted(token))
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("Encrypted", equalTo(false))
                .body("FileSystemId", not(equalTo(fileSystemId)))
                .extract()
                .path("FileSystemId");

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/file-systems")
                .then()
                .statusCode(404)
                .body("__type", equalTo("FileSystemNotFound"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + unencrypted)
                .then()
                .statusCode(200);
    }

    private static String quote(String value) {
        return "\"" + value.trim().replace("\"", "\\\"") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/elasticfilesystem/aws4_request";
    }
}
