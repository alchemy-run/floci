package io.github.hectorvent.floci.services.ecr;

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
import static org.hamcrest.Matchers.*;

/**
 * In-tree control-plane integration test for ECR. Does not require Docker —
 * the registry container is started lazily and these tests never trigger
 * ensureStarted().
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String REPO = "floci-it/integration";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createRepository() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo(REPO))
            .body("repository.repositoryArn", startsWith("arn:aws:ecr:"))
            .body("repository.repositoryArn", endsWith(":repository/" + REPO))
            .body("repository.repositoryUri", containsString("/" + REPO))
            .body("repository.repositoryUri", containsString("localhost:"))
            .body("repository.imageTagMutability", equalTo("MUTABLE"))
            .body("repository.imageScanningConfiguration.scanOnPush", equalTo(false));
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
            .body("repositories[0].repositoryName", equalTo(REPO));
    }

    @Test
    @Order(4)
    void describeRepositoriesAll() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repositories", not(empty()));
    }

    @Test
    @Order(5)
    void describeMissingFails() {
        given()
            .header("X-Amz-Target", PREFIX + "DescribeRepositories")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["does-not-exist-int"] }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RepositoryNotFoundException"));
    }

    @Test
    @Order(6)
    void invalidRepoNameFails() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "Invalid_Caps" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(6)
    void doubleHyphenRepoNameSucceedsLikeAws() {
        given()
            .header("X-Amz-Target", PREFIX + "CreateRepository")
            .contentType(CT)
            .body("""
                { "repositoryName": "aws-ecs-service-image-form--task" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repository.repositoryName", equalTo("aws-ecs-service-image-form--task"));
    }

    @Test
    @Order(7)
    void getAuthorizationToken() {
        String token = given()
            .header("X-Amz-Target", PREFIX + "GetAuthorizationToken")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("authorizationData[0].authorizationToken", not(emptyString()))
            .body("authorizationData[0].proxyEndpoint", startsWith("http"))
            .body("authorizationData[0].proxyEndpoint", containsString(".ecr."))
            .body("authorizationData[0].expiresAt", notNullValue())
            .extract().jsonPath().getString("authorizationData[0].authorizationToken");

        String decoded = new String(Base64.getDecoder().decode(token));
        org.junit.jupiter.api.Assertions.assertTrue(decoded.startsWith("AWS:"),
                "Decoded auth token must start with 'AWS:' but was: " + decoded);
    }

    @Test
    @Order(8)
    void batchGetRepositoryScanningConfiguration() {
        given()
            .header("X-Amz-Target", PREFIX + "BatchGetRepositoryScanningConfiguration")
            .contentType(CT)
            .body("""
                { "repositoryNames": ["%s"] }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("scanningConfigurations[0].repositoryName", equalTo(REPO))
            .body("scanningConfigurations[0].repositoryArn", startsWith("arn:aws:ecr:"))
            .body("scanningConfigurations[0].scanOnPush", equalTo(false))
            .body("scanningConfigurations[0].scanFrequency", equalTo("MANUAL"))
            .body("scanningConfigurations[0].appliedScanFilters", empty())
            .body("failures", empty());
    }

    @Test
    @Order(9)
    void putImageScanningConfiguration() {
        given()
            .header("X-Amz-Target", PREFIX + "PutImageScanningConfiguration")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "imageScanningConfiguration": { "scanOnPush": true } }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("repositoryName", equalTo(REPO))
            .body("imageScanningConfiguration.scanOnPush", equalTo(true));
    }

    @Test
    @Order(10)
    void registryPolicyRoundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        given()
            .header("X-Amz-Target", PREFIX + "PutRegistryPolicy")
            .contentType(CT)
            .body("{\"policyText\": " + toJsonString(policy) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("registryId", not(emptyString()))
            .body("policyText", equalTo(policy));

        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("policyText", equalTo(policy));

        given()
            .header("X-Amz-Target", PREFIX + "DeleteRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", PREFIX + "GetRegistryPolicy")
            .contentType(CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RegistryPolicyNotFoundException"));
    }

    @Test
    @Order(11)
    void layerUploadPutImageAndScan() {
        byte[] layer = "floci-it-layer\n".repeat(8).getBytes();
        String layerDigest = sha256(layer);
        String uploadId = given()
            .header("X-Amz-Target", PREFIX + "InitiateLayerUpload")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s" }
                """.formatted(REPO))
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
                """.formatted(REPO, uploadId, layer.length - 1, java.util.Base64.getEncoder().encodeToString(layer)))
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
                """.formatted(REPO, uploadId, layerDigest))
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
                { "repositoryName": "%s", "imageManifest": %s, "imageManifestMediaType": "application/vnd.docker.distribution.manifest.v2+json", "imageTag": "it" }
                """.formatted(REPO, toJsonString(manifest)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("image.imageId.imageTag", equalTo("it"))
            .body("image.imageId.imageDigest", startsWith("sha256:"));

        given()
            .header("X-Amz-Target", PREFIX + "GetDownloadUrlForLayer")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "layerDigest": "%s" }
                """.formatted(REPO, layerDigest))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("downloadUrl", startsWith("https://"))
            .body("layerDigest", equalTo(layerDigest));

        given()
            .header("X-Amz-Target", PREFIX + "BatchCheckLayerAvailability")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "layerDigests": ["%s"] }
                """.formatted(REPO, layerDigest))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("layers[0].layerAvailability", equalTo("AVAILABLE"));

        given()
            .header("X-Amz-Target", PREFIX + "StartImageScan")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "imageId": { "imageTag": "it" } }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedImageTypeException"));

        given()
            .header("X-Amz-Target", PREFIX + "DescribeImageScanFindings")
            .contentType(CT)
            .body("""
                { "repositoryName": "%s", "imageId": { "imageTag": "it" } }
                """.formatted(REPO))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ScanNotFoundException"));
    }

    @Test
    @Order(12)
    void deleteRepositoryForce() {
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
