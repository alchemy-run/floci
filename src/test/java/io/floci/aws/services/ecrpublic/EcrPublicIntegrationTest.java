package io.floci.aws.services.ecrpublic;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Amazon ECR Public over JSON 1.1: the account's registry and alias, public repositories with gallery catalog data,
 * logos, policies and tags, and images pushed through the layer upload API into floci's registry.
 */
@QuarkusTest
class EcrPublicIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/ecr-public/aws4_request")
                .header("X-Amz-Target", "SpencerFrontendService." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static ValidatableResponse ecr(String action, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/ecr/aws4_request")
                .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String upload(String repository, byte[] blob) throws NoSuchAlgorithmException {
        String uploadId = call("InitiateLayerUpload", "{\"repositoryName\":\"" + repository + "\"}")
                .statusCode(200).extract().path("uploadId");
        call("UploadLayerPart", "{\"repositoryName\":\"" + repository + "\",\"uploadId\":\"" + uploadId
                + "\",\"partFirstByte\":0,\"partLastByte\":" + (blob.length - 1) + ",\"layerPartBlob\":\""
                + Base64.getEncoder().encodeToString(blob) + "\"}").statusCode(200);
        String digest = digest(blob);
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + repository + "\",\"uploadId\":\"" + uploadId
                + "\",\"layerDigests\":[\"" + digest + "\"]}").statusCode(200)
                .body("layerDigest", equalTo(digest));
        return digest;
    }

    @Test
    void registryRepositoriesCatalogPoliciesAndTags() {
        String alias = call("DescribeRegistries", "{}").statusCode(200).body("registries", hasSize(1))
                .body("registries[0].registryId", equalTo(ACCOUNT))
                .body("registries[0].registryArn", equalTo("arn:aws:ecr-public::" + ACCOUNT + ":registry/"
                        + ACCOUNT))
                .body("registries[0].aliases[0].status", equalTo("ACTIVE"))
                .extract().path("registries[0].aliases[0].name");
        call("PutRegistryCatalogData", "{\"displayName\":\"Floci\"}").statusCode(200)
                .body("registryCatalogData.displayName", equalTo("Floci"));
        call("GetRegistryCatalogData", "{}").statusCode(200).body("registryCatalogData.displayName",
                equalTo("Floci"));
        call("GetAuthorizationToken", "{}").statusCode(200)
                .body("authorizationData.authorizationToken", not(equalTo("")));

        String name = "gallery-" + UUID.randomUUID().toString().substring(0, 8);
        call("CreateRepository", "{\"repositoryName\":\"" + name + "\",\"catalogData\":{\"description\":\"d\","
                + "\"architectures\":[\"x86-64\"],\"operatingSystems\":[\"Linux\"]},\"tags\":[{\"Key\":\"team\","
                + "\"Value\":\"a\"}]}").statusCode(200)
                .body("repository.repositoryArn", equalTo("arn:aws:ecr-public::" + ACCOUNT + ":repository/" + name))
                .body("repository.repositoryUri", equalTo("public.ecr.aws/" + alias + "/" + name))
                .body("catalogData.description", equalTo("d")).body("catalogData.marketplaceCertified",
                        equalTo(false));
        call("CreateRepository", "{\"repositoryName\":\"" + name + "\"}").statusCode(400)
                .body("__type", containsString("RepositoryAlreadyExistsException"));
        call("CreateRepository", "{\"repositoryName\":\"" + name + "x\",\"catalogData\":{\"architectures\":"
                + "[\"sparc\"]}}").statusCode(400).body("__type", containsString("InvalidParameterException"));
        call("DescribeRepositories", "{\"repositoryNames\":[\"" + name + "\"],\"registryId\":\"" + ACCOUNT + "\"}")
                .statusCode(200).body("repositories[0].repositoryName", equalTo(name));
        call("DescribeRepositories", "{\"registryId\":\"111111111111\"}").statusCode(400)
                .body("__type", containsString("RegistryNotFoundException"));
        ecr("DescribeRepositories", "{}").statusCode(200).body("repositories.repositoryName", not(hasItem(name)));

        call("PutRepositoryCatalogData", "{\"repositoryName\":\"" + name + "\",\"catalogData\":{\"usageText\":"
                + "\"docker pull\",\"logoImageBlob\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}}")
                .statusCode(200).body("catalogData.description", equalTo("d"))
                .body("catalogData.usageText", equalTo("docker pull"));
        String logo = call("GetRepositoryCatalogData", "{\"repositoryName\":\"" + name + "\"}").statusCode(200)
                .extract().path("catalogData.logoUrl");
        given().get(logo.substring(logo.indexOf("/_ecr-public"))).then().statusCode(200)
                .contentType("image/png");

        call("GetRepositoryPolicy", "{\"repositoryName\":\"" + name + "\"}").statusCode(400)
                .body("__type", containsString("RepositoryPolicyNotFoundException"));
        String policy = "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                + "\\\"Principal\\\":\\\"*\\\",\\\"Action\\\":\\\"ecr-public:BatchCheckLayerAvailability\\\"}]}";
        call("SetRepositoryPolicy", "{\"repositoryName\":\"" + name + "\",\"policyText\":\"" + policy + "\"}")
                .statusCode(200);
        call("GetRepositoryPolicy", "{\"repositoryName\":\"" + name + "\"}").statusCode(200)
                .body("policyText", containsString("BatchCheckLayerAvailability"));
        call("DeleteRepositoryPolicy", "{\"repositoryName\":\"" + name + "\"}").statusCode(200);

        String arn = "arn:aws:ecr-public::" + ACCOUNT + ":repository/" + name;
        call("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .statusCode(200);
        call("UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"team\"]}").statusCode(200);
        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}").statusCode(200).body("tags", hasSize(1))
                .body("tags[0].Key", equalTo("env"));
        call("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"Key\":\"aws:x\",\"Value\":\"y\"}]}")
                .statusCode(400).body("__type", containsString("InvalidTagParameterException"));

        call("DeleteRepository", "{\"repositoryName\":\"" + name + "\"}").statusCode(200);
        call("DescribeRepositories", "{\"repositoryNames\":[\"" + name + "\"]}").statusCode(400)
                .body("__type", containsString("RepositoryNotFoundException"));
    }

    @Test
    void imagesArePushedThroughTheLayerUploadApi() throws NoSuchAlgorithmException {
        String name = "images-" + UUID.randomUUID().toString().substring(0, 8);
        call("CreateRepository", "{\"repositoryName\":\"" + name + "\"}").statusCode(200);
        byte[] layer = "floci-ecr-public-layer".getBytes(StandardCharsets.UTF_8);
        byte[] config = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"config\":{},\"rootfs\":{\"type\":\"layers\","
                .concat("\"diff_ids\":[]}}").getBytes(StandardCharsets.UTF_8);
        String layerDigest = upload(name, layer);
        String configDigest = upload(name, config);
        call("BatchCheckLayerAvailability", "{\"repositoryName\":\"" + name + "\",\"layerDigests\":[\""
                + layerDigest + "\"]}").statusCode(200).body("layers[0].layerAvailability", equalTo("AVAILABLE"));
        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"size\":"
                + config.length + ",\"digest\":\"" + configDigest + "\"},\"layers\":[{\"mediaType\":"
                + "\"application/vnd.docker.image.rootfs.diff.tar.gzip\",\"size\":" + layer.length + ",\"digest\":\""
                + layerDigest + "\"}]}";
        call("PutImage", "{\"repositoryName\":\"" + name + "\",\"imageTag\":\"v1\",\"imageManifest\":\""
                + manifest.replace("\"", "\\\"") + "\"}").statusCode(200)
                .body("image.imageId.imageDigest", startsWith("sha256:"))
                .body("image.registryId", equalTo(ACCOUNT));
        call("DescribeImages", "{\"repositoryName\":\"" + name + "\"}").statusCode(200)
                .body("imageDetails", hasSize(1)).body("imageDetails[0].imageTags", hasItem("v1"));
        call("DescribeImageTags", "{\"repositoryName\":\"" + name + "\"}").statusCode(200)
                .body("imageTagDetails[0].imageTag", equalTo("v1"))
                .body("imageTagDetails[0].imageDetail.imageDigest", startsWith("sha256:"));
        call("BatchDeleteImage", "{\"repositoryName\":\"" + name + "\",\"imageIds\":[{\"imageTag\":\"missing\"}]}")
                .statusCode(200).body("failures[0].failureCode", equalTo("ImageNotFound"));

        call("DeleteRepository", "{\"repositoryName\":\"" + name + "\"}").statusCode(400)
                .body("__type", containsString("RepositoryNotEmptyException"));
        call("DeleteRepository", "{\"repositoryName\":\"" + name + "\",\"force\":true}").statusCode(200);
        call("DescribeImages", "{\"repositoryName\":\"" + name + "\"}").statusCode(400)
                .body("__type", containsString("RepositoryNotFoundException"));
    }
}
