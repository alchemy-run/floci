package io.github.hectorvent.floci.services.s3files;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/** Verifies S3 Files restJson1 file-system and access-point CRUD, tags, policy, and not-found. */
@QuarkusTest
class S3FilesIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"*"},"Action":["s3files:ClientMount"]}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getFileSystemOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/file-systems/fs-0123456789abcdef0")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("errorCode", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listFileSystemsSucceeds() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("maxResults", 5)
                .when()
                .get("/file-systems")
                .then()
                .statusCode(200)
                .body("fileSystems", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    @Test
    void createGetTagPolicyAccessPointAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String bucket = "arn:aws:s3:::s3files-it-bucket";
        String roleArn = "arn:aws:iam::000000000000:role/s3files-it";

        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "bucket":"%s",
                          "roleArn":"%s",
                          "clientToken":"s3files-it-fs",
                          "tags":[{"key":"fixture","value":"s3files-filesystem"}]
                        }
                        """.formatted(bucket, roleArn))
                .when()
                .put("/file-systems")
                .then()
                .statusCode(200)
                .body("fileSystemId", startsWith("fs-"))
                .body("fileSystemArn", org.hamcrest.Matchers.containsString(":file-system/"))
                .body("status", equalTo("available"))
                .body("bucket", equalTo(bucket))
                .body("roleArn", equalTo(roleArn))
                .extract()
                .path("fileSystemId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/file-systems/" + fileSystemId)
                .then()
                .statusCode(200)
                .body("status", equalTo("available"))
                .body("bucket", equalTo(bucket))
                .body("tags.find { it.key == 'fixture' }.value", equalTo("s3files-filesystem"));

        given()
                .header("Authorization", authorization)
                .queryParam("maxResults", 5)
                .when()
                .get("/file-systems")
                .then()
                .statusCode(200)
                .body("fileSystems.find { it.fileSystemId == '%s' }.status".formatted(fileSystemId),
                        equalTo("available"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":[{\"key\":\"alchemy::id\",\"value\":\"Files\"}]}")
                .when()
                .post("/resource-tags/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/resource-tags/" + fileSystemId)
                .then()
                .statusCode(200)
                .body("tags.find { it.key == 'alchemy::id' }.value", equalTo("Files"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policy\":" + quote(POLICY) + "}")
                .when()
                .put("/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(200)
                .body("policy", equalTo(POLICY.trim()));

        String accessPointId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"s3files-it-ap",
                          "fileSystemId":"%s",
                          "posixUser":{"uid":1000,"gid":1000},
                          "rootDirectory":{
                            "path":"/app",
                            "creationPermissions":{"ownerUid":1000,"ownerGid":1000,"permissions":"0755"}
                          },
                          "tags":[{"key":"purpose","value":"s3files-ap"}]
                        }
                        """.formatted(fileSystemId))
                .when()
                .put("/access-points")
                .then()
                .statusCode(200)
                .body("accessPointId", startsWith("fsap-"))
                .body("fileSystemId", equalTo(fileSystemId))
                .body("status", equalTo("available"))
                .body("posixUser.uid", equalTo(1000))
                .body("rootDirectory.path", equalTo("/app"))
                .extract()
                .path("accessPointId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/access-points/" + accessPointId)
                .then()
                .statusCode(200)
                .body("posixUser.uid", equalTo(1000))
                .body("rootDirectory.path", equalTo("/app"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/access-points/" + accessPointId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/access-points/" + accessPointId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/file-systems/" + fileSystemId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String quote(String value) {
        return "\"" + value.trim().replace("\"", "\\\"") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/s3files/aws4_request";
    }
}
