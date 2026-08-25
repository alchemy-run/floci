package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Verifies EFS restJson1 access-point CRUD, tags, and not-found. */
@QuarkusTest
class EfsAccessPointIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAccessPointOnANonexistentIdFailsWithAccessPointNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/2015-02-01/access-points?AccessPointId=fsap-missing00000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AccessPointNotFound"));
    }

    @Test
    void createDescribeTagUntagReplaceDeleteAccessPoint() {
        String authorization = auth(EAST);
        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CreationToken\":\"efs-ap-it-fs\",\"Encrypted\":true}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystemId", startsWith("fs-"))
                .body("LifeCycleState", equalTo("available"))
                .extract()
                .path("FileSystemId");

        String createBody = """
                {
                  "ClientToken":"efs-ap-it-ap",
                  "FileSystemId":"%s",
                  "PosixUser":{"Uid":1000,"Gid":1000},
                  "RootDirectory":{
                    "Path":"/app",
                    "CreationInfo":{"OwnerUid":1000,"OwnerGid":1000,"Permissions":"750"}
                  },
                  "Tags":[{"Key":"purpose","Value":"alchemy-efs-ap-test"}]
                }
                """.formatted(fileSystemId);

        String accessPointId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(createBody)
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .body("AccessPointId", startsWith("fsap-"))
                .body("AccessPointArn", startsWith("arn:aws:elasticfilesystem:"))
                .body("FileSystemId", equalTo(fileSystemId))
                .body("LifeCycleState", equalTo("available"))
                .body("PosixUser.Uid", equalTo(1000))
                .body("RootDirectory.Path", equalTo("/app"))
                .body("Tags[0].Key", equalTo("purpose"))
                .body("Tags[0].Value", equalTo("alchemy-efs-ap-test"))
                .extract()
                .path("AccessPointId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/access-points?AccessPointId=" + accessPointId)
                .then()
                .statusCode(200)
                .body("AccessPoints[0].AccessPointId", equalTo(accessPointId))
                .body("AccessPoints[0].LifeCycleState", equalTo("available"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-efs-ap-retag\"}]}")
                .when()
                .post("/2015-02-01/resource-tags/" + accessPointId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/access-points?AccessPointId=" + accessPointId)
                .then()
                .statusCode(200)
                .body("AccessPoints[0].Tags[0].Value", equalTo("alchemy-efs-ap-retag"));

        String replacedId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ClientToken":"efs-ap-it-ap-replaced",
                          "FileSystemId":"%s",
                          "PosixUser":{"Uid":1001,"Gid":1000},
                          "RootDirectory":{
                            "Path":"/app",
                            "CreationInfo":{"OwnerUid":1001,"OwnerGid":1000,"Permissions":"750"}
                          },
                          "Tags":[{"Key":"purpose","Value":"alchemy-efs-ap-retag"}]
                        }
                        """.formatted(fileSystemId))
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .body("AccessPointId", not(equalTo(accessPointId)))
                .body("PosixUser.Uid", equalTo(1001))
                .extract()
                .path("AccessPointId");

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/access-points/" + accessPointId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/access-points?AccessPointId=" + accessPointId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("AccessPointNotFound"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/access-points/" + replacedId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems?FileSystemId=" + fileSystemId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    @Test
    void createAccessPointOnMissingFileSystemFailsWithFileSystemNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("""
                        {
                          "ClientToken":"efs-ap-it-missing-fs",
                          "FileSystemId":"fs-missing00000000"
                        }
                        """)
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(404)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/elasticfilesystem/aws4_request";
    }
}
