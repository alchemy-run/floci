package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * Covers the EFS restJson1 operations exercised by Alchemy's Bindings fixture:
 * describe FS/mount targets/access points, backup + lifecycle round-trips,
 * runtime access-point create/delete, and ReplicationNotFound.
 */
@QuarkusTest
class EfsBindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void bindingsRoundTrip_fileSystemBackupLifecycleAccessPointReplication() {
        String authorization = auth(EAST);
        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "CreationToken":"efs-bindings-it-fs",
                          "Encrypted":true,
                          "Tags":[{"Key":"purpose","Value":"alchemy-efs-bindings"}]
                        }
                        """)
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystemId", startsWith("fs-"))
                .body("LifeCycleState", equalTo("available"))
                .body("Encrypted", equalTo(true))
                .extract()
                .path("FileSystemId");

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystems[0].FileSystemId", equalTo(fileSystemId))
                .body("FileSystems[0].LifeCycleState", equalTo("available"))
                .body("FileSystems[0].Encrypted", equalTo(true));

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/mount-targets")
                .then()
                .statusCode(200)
                .body("MountTargets", hasSize(0));

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .body("AccessPoints", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
                .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo("DISABLED"));

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
                .body("{\"LifecyclePolicies\":[{\"TransitionToIA\":\"AFTER_30_DAYS\"}]}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(1));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"LifecyclePolicies\":[]}")
                .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(0));

        String accessPointId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ClientToken":"alchemy-efs-bindings-access-point",
                          "FileSystemId":"%s",
                          "PosixUser":{"Uid":1000,"Gid":1000},
                          "RootDirectory":{
                            "Path":"/bindings-test",
                            "CreationInfo":{"OwnerUid":1000,"OwnerGid":1000,"Permissions":"750"}
                          }
                        }
                        """.formatted(fileSystemId))
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .body("AccessPointId", startsWith("fsap-"))
                .body("LifeCycleState", equalTo("available"))
                .extract()
                .path("AccessPointId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ClientToken":"alchemy-efs-bindings-access-point",
                          "FileSystemId":"%s",
                          "PosixUser":{"Uid":1000,"Gid":1000}
                        }
                        """.formatted(fileSystemId))
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(409)
                .body("__type", equalTo("AccessPointAlreadyExists"))
                .body("AccessPointId", equalTo(accessPointId));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/access-points/" + accessPointId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("FileSystemId", fileSystemId)
                .when()
                .get("/2015-02-01/file-systems/replication-configurations")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ReplicationNotFound"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/elasticfilesystem/aws4_request";
    }
}
