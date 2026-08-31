package io.github.hectorvent.floci.services.ecrpublic;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
/**

 * JSON 1.1 coverage for ECR Public repository lifecycle used by Alchemy:
 * create with catalog+tags, describe, catalog update, tag, delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrPublicIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "SpencerFrontendService.";
    private static final String REPO = "floci-it/public-repo";
    private static final String BINDINGS_REPO = "floci-it/public-bindings";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createRepositoryWithCatalogAndTags() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                {
                  "repositoryName": "%s",
                  "catalogData": {
                    "description": "initial description",
                    "architectures": ["x86-64"],
                    "operatingSystems": ["Linux"]
                  },
                  "tags": [{"Key": "Environment", "Value": "test"}]
                }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo(REPO))
            .body("repository.repositoryArn", containsString(":repository/" + REPO))
            .body("repository.repositoryArn", containsString("arn:aws:ecr-public::"))
            .body("repository.repositoryUri", containsString("public.ecr.aws"))
            .body("catalogData.description", equalTo("initial description"));
    }

    @Test
    @Order(2)
    void createRepositoryDuplicateFails() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryAlreadyExistsException"));
    }

    @Test
    @Order(3)
    void describeRepositoriesByName() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repositories[0].repositoryName", equalTo(REPO))
            .body("repositories[0].repositoryUri", containsString("public.ecr.aws"));
    }

    @Test
    @Order(4)
    void getRepositoryCatalogData() {
        given()
            .header("X-Amz-Target", PREFIX + "GetRepositoryCatalogData")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("catalogData.description", equalTo("initial description"))
            .body("catalogData.architectures", hasItem("x86-64"));
    }

    @Test
    @Order(5)
    void listTagsForResource() {
        String arn = given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("repositories[0].repositoryArn");

        given()
            .header("X-Amz-Target", PREFIX + "ListTagsForResource")
            .contentType(CT)
            .body("""
                { "resourceArn": "%s" }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("Environment"))
            .body("tags.find { it.Key == 'Environment' }.Value", equalTo("test"));
    }

    @Test
    @Order(6)
    void putRepositoryCatalogDataAndTag() {
        given()
            .header("X-Amz-Target", PREFIX + "PutRepositoryCatalogData")
            .contentType(CT)
            .body("""
                {
                  "repositoryName": "%s",
                  "catalogData": {
                    "description": "updated description",
                    "architectures": ["x86-64", "ARM 64"],
                    "operatingSystems": ["Linux"]
                  }
                }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("catalogData.description", equalTo("updated description"));

        String arn = given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("repositories[0].repositoryArn");

        given()
            .header("X-Amz-Target", PREFIX + "TagResource")
            .contentType(CT)
            .body("""
                { "resourceArn": "%s", "tags": [{"Key": "Extra", "Value": "yes"}] }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", PREFIX + "ListTagsForResource")
            .contentType(CT)
            .body("""
                { "resourceArn": "%s" }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags.find { it.Key == 'Extra' }.Value", equalTo("yes"));
    }

    @Test
    @Order(7)
    void getRepositoryPolicyMissing() {
        given()
            .header("X-Amz-Target", PREFIX + "GetRepositoryPolicy")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryPolicyNotFoundException"));
    }

    @Test
    @Order(8)
    void describeMissingFails() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["does-not-exist-public"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryNotFoundException"));
    }

    @Test
    @Order(9)
    void deleteRepository() {
        given()
            .header("X-Amz-Target", PREFIX + "DeleteRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "force": true }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo(REPO));

        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryNotFoundException"));
    }

    @Test
    @Order(10)
    void unknownOperation() {
        given()
            .header("X-Amz-Target", PREFIX + "NotARealOp")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    @Order(11)
    void describeRegistriesAndAuthToken() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRegistries")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registries", not(empty()))
            .body("registries[0].aliases[0].name", not(emptyString()))
            .body("registries[0].registryUri", startsWith("public.ecr.aws/"));

        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryCatalogData")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registryCatalogData", notNullValue());

        String token = given()
            .header("X-Amz-Target", PREFIX + "GetAuthorizationToken")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("authorizationData.authorizationToken", not(emptyString()))
            .body("authorizationData.expiresAt", notNullValue())
            .extract().jsonPath().getString("authorizationData.authorizationToken");
        String decoded = new String(Base64.getDecoder().decode(token));
        org.junit.jupiter.api.Assertions.assertTrue(decoded.startsWith("AWS:"),
                "Decoded auth token must start with 'AWS:' but was: " + decoded);
    }

    @Test
    @Order(12)
    void createBindingsRepo() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                {
                  "repositoryName": "%s",
                  "catalogData": {
                    "description": "alchemy ECRPublic bindings fixture",
                    "architectures": ["x86-64"],
                    "operatingSystems": ["Linux"]
                  }
                }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("catalogData.description", equalTo("alchemy ECRPublic bindings fixture"));
    }

    @Test
    @Order(13)
    void batchCheckMissingLayerIsUnavailable() {
        given()
            .header("X-Amz-Target", PREFIX + "BatchCheckLayerAvailability")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "layerDigests": ["sha256:%s"] }
                """.formatted(BINDINGS_REPO, "0".repeat(64)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("layers[0].layerAvailability", equalTo("UNAVAILABLE"));
    }

    @Test
    @Order(14)
    void batchDeleteMissingImage() {
        given()
            .header("X-Amz-Target", PREFIX + "BatchDeleteImage")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "imageIds": [{ "imageTag": "does-not-exist" }] }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("failures[0].failureCode", equalTo("ImageNotFound"));
    }

    @Test
    @Order(15)
    void layerUploadPutImageDescribe() {
        byte[] layer = "floci-ecr-public-it-layer".getBytes();
        String layerDigest = sha256(layer);
        String uploadId = given()
            .header("X-Amz-Target", PREFIX + "InitiateLayerUpload")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("uploadId", not(emptyString()))
            .body("partSize", greaterThan(0))
            .extract().jsonPath().getString("uploadId");

        given()
            .header("X-Amz-Target", PREFIX + "UploadLayerPart")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "uploadId": "%s", "partFirstByte": 0, "partLastByte": %d, "layerPartBlob": "%s" }
                """.formatted(BINDINGS_REPO, uploadId, layer.length - 1, Base64.getEncoder().encodeToString(layer)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("lastByteReceived", equalTo(layer.length - 1));

        given()
            .header("X-Amz-Target", PREFIX + "CompleteLayerUpload")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "uploadId": "%s", "layerDigests": ["%s"] }
                """.formatted(BINDINGS_REPO, uploadId, layerDigest))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("layerDigest", equalTo(layerDigest));

        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"size\":2,\"digest\":\"sha256:00\"},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",\"size\":"
                + layer.length + ",\"digest\":\"" + layerDigest + "\"}]}";

        given()
            .header("X-Amz-Target", PREFIX + "PutImage")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "imageManifest": %s, "imageManifestMediaType": "application/vnd.docker.distribution.manifest.v2+json", "imageTag": "bindings-test" }
                """.formatted(BINDINGS_REPO, toJsonString(manifest)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("image.imageId.imageTag", equalTo("bindings-test"))
            .body("image.imageId.imageDigest", startsWith("sha256:"));

        given()
            .header("X-Amz-Target", PREFIX + "DescribeImages")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("imageDetails", not(empty()))
            .body("imageDetails[0].imageDigest", startsWith("sha256:"));

        given()
            .header("X-Amz-Target", PREFIX + "DescribeImageTags")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("imageTagDetails", not(empty()))
            .body("imageTagDetails[0].imageTag", equalTo("bindings-test"));
    }

    @Test
    @Order(16)
    void deleteBindingsRepo() {
        given()
            .header("X-Amz-Target", PREFIX + "DeleteRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "force": true }
                """.formatted(BINDINGS_REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String toJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
