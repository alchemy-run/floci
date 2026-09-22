package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CodeArtifactIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void domainAndRepositoryLifecycle() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]}")
                .post("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200)
                .body("domain.name", equalTo("lifecycle-domain"))
                .body("domain.repositoryCount", equalTo(0))
                .body("domain.arn", notNullValue());

        given().header("Authorization", AUTH).get("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200).body("domain.status", equalTo("Active"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"description\":\"a repo\"}")
                .post("/v1/repository?domain=lifecycle-domain&repository=lifecycle-repo")
                .then().statusCode(200)
                .body("repository.name", equalTo("lifecycle-repo"))
                .body("repository.domainName", equalTo("lifecycle-domain"))
                .body("repository.upstreams", emptyIterable());

        given().header("Authorization", AUTH)
                .get("/v1/repository/endpoint?domain=lifecycle-domain&repository=lifecycle-repo&format=npm")
                .then().statusCode(200)
                .body("repositoryEndpoint", equalTo("http://localhost:4566/codeartifact/npm/lifecycle-domain/lifecycle-repo/"));

        given().header("Authorization", AUTH)
                .delete("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(409).body("__type", equalTo("ConflictException"));

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=lifecycle-domain&repository=lifecycle-repo")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .delete("/v1/domain?domain=lifecycle-domain")
                .then().statusCode(200);
    }

    @Test
    void tagResourceListTagsAndUntagResourceRoundTrip() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=tag-domain").then().statusCode(200);

        String arn = "arn:aws:codeartifact:us-east-1:000000000000:domain/tag-domain";

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tags\":[{\"key\":\"team\",\"value\":\"data\"}]}")
                .post("/v1/tag?resourceArn=" + arn)
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .post("/v1/tags?resourceArn=" + arn)
                .then().statusCode(200)
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("team"))
                .body("tags[0].value", equalTo("data"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"tagKeys\":[\"team\"]}")
                .post("/v1/untag?resourceArn=" + arn)
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .post("/v1/tags?resourceArn=" + arn)
                .then().statusCode(200).body("tags", hasSize(0));
    }

    @Test
    void listDomainsReturnsSummaryShapeNotFullDescription() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=summary-domain").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domains")
                .then().statusCode(200)
                .body("domains.find { it.name == 'summary-domain' }.owner", notNullValue())
                .body("domains.find { it.name == 'summary-domain' }.repositoryCount", equalTo(null));
    }

    @Test
    void deleteRepositoryPermissionsPolicyUsesPluralPath() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=policy-domain").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"policyDocument\":\"{}\"}")
                .put("/v1/repository/permissions/policy?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200).body("policy.document", equalTo("{}"));

        given().header("Authorization", AUTH)
                .delete("/v1/repository/permissions/policies?domain=policy-domain&repository=policy-repo")
                .then().statusCode(200);
    }

    @Test
    void createRepositoryUnderMissingDomainReturnsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/repository?domain=no-such-domain&repository=valid-repo")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void authorizationTokenRouteUsesQueryDurationAndJsonErrors() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=token-wire-domain").then().statusCode(200);
        try {
            String token = given().contentType("application/json").header("Authorization", AUTH)
                    .post("/v1/authorization-token?domain=token-wire-domain&duration=900")
                    .then().statusCode(200).contentType("application/json")
                    .body("expiration", notNullValue()).extract().path("authorizationToken");
            assertTrue(token.length() > 100);
            for (String duration : new String[] {"899", "43201", "invalid", "900.5"}) {
                given().contentType("application/json").header("Authorization", AUTH)
                        .queryParam("domain", "token-wire-domain").queryParam("duration", duration)
                        .post("/v1/authorization-token").then().statusCode(400)
                        .body("__type", equalTo("ValidationException"));
            }
            given().contentType("application/json").header("Authorization", AUTH)
                    .post("/v1/authorization-token?domain=missing-token-wire-domain")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            given().header("Authorization", AUTH).delete("/v1/domain?domain=token-wire-domain")
                    .then().statusCode(200);
        }
    }

    @Test
    void packageRoutesStoreBinaryPayloadAndHonorBodyVersionPreconditions() throws Exception {
        String repository = "?domain=package-wire-domain&repository=package-wire-repo";
        String pkg = repository + "&format=generic&namespace=scope&package=payload";
        byte[] content = new byte[] {0, (byte) 128, (byte) 255, 13, 10};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=package-wire-domain").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository" + repository).then().statusCode(200);
        try {
            given().header("Authorization", AUTH).delete("/v1/package" + pkg)
                    .then().statusCode(404).contentType("application/json")
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/octet-stream").header("Authorization", AUTH)
                    .header("x-amz-content-sha256", "0".repeat(64)).body(content)
                    .post("/v1/package/version/publish" + pkg + "&version=1&asset=bytes.bin")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
            String revision = given().contentType("application/octet-stream").header("Authorization", AUTH)
                    .header("x-amz-content-sha256", hash).body(content)
                    .post("/v1/package/version/publish" + pkg + "&version=1&asset=bytes.bin&unfinished=true")
                    .then().statusCode(200).body("status", equalTo("Unfinished"))
                    .body("asset.size", equalTo(content.length)).body("asset.hashes.'SHA-256'", equalTo(hash))
                    .extract().path("versionRevision");
            byte[] downloaded = given().header("Authorization", AUTH)
                    .get("/v1/package/version/asset" + pkg + "&version=1&asset=bytes.bin&revision=" + revision)
                    .then().statusCode(200).contentType("application/octet-stream")
                    .header("X-AssetName", "bytes.bin").header("X-PackageVersion", "1")
                    .header("X-PackageVersionRevision", revision).extract().asByteArray();
            assertArrayEquals(content, downloaded);
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"versions\":[\"1\",\"absent\"],\"versionRevisions\":{\"1\":\"stale\"},\"targetStatus\":\"Published\"}")
                    .post("/v1/package/versions/update_status" + pkg).then().statusCode(200)
                    .body("failedVersions.'1'.errorCode", equalTo("MISMATCHED_REVISION"))
                    .body("failedVersions.absent.errorCode", equalTo("NOT_FOUND"));
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"versions\":[\"1\"],\"expectedStatus\":\"Unfinished\",\"targetStatus\":\"Published\"}")
                    .post("/v1/package/versions/update_status" + pkg).then().statusCode(200)
                    .body("successfulVersions.'1'.status", equalTo("Published"));
            given().header("Authorization", AUTH).get("/v1/package/version" + pkg + "&version=1")
                    .then().statusCode(200).body("packageVersion.status", equalTo("Published"));
            given().contentType("application/json").header("Authorization", AUTH)
                    .post("/v1/package/version/assets" + pkg + "&version=1")
                    .then().statusCode(200).body("assets[0].name", equalTo("bytes.bin"));
            given().header("Authorization", AUTH).get("/v1/package/version/readme" + pkg + "&version=1")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"versions\":[\"1\"]}").post("/v1/package/versions/dispose" + pkg)
                    .then().statusCode(200).body("successfulVersions.'1'.status", equalTo("Disposed"));
            given().header("Authorization", AUTH).get("/v1/package/version/asset" + pkg + "&version=1&asset=bytes.bin")
                    .then().statusCode(404).contentType("application/json")
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/json").header("Authorization", AUTH)
                    .body("{\"versions\":[\"1\"]}").post("/v1/package/versions/delete" + pkg)
                    .then().statusCode(200).body("successfulVersions.'1'.status", equalTo("Deleted"));
            given().header("Authorization", AUTH).delete("/v1/package" + pkg).then().statusCode(200);
            given().contentType("application/json").header("Authorization", AUTH).post("/v1/packages" + repository)
                    .then().statusCode(200).body("packages", emptyIterable());
        } finally {
            given().header("Authorization", AUTH).delete("/v1/repository" + repository).then().statusCode(200);
            given().header("Authorization", AUTH).delete("/v1/domain?domain=package-wire-domain").then().statusCode(200);
        }
    }

    @Test
    void invalidDomainNameReturnsValidationError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}").post("/v1/domain?domain=NOT-VALID")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }
}
