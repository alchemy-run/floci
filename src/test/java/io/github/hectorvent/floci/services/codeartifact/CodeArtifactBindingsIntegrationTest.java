package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Alchemy {@code test/AWS/CodeArtifact/Bindings.test.ts}: domain token,
 * repository endpoint, and generic package publish/copy/dispose.
 */
@QuarkusTest
class CodeArtifactBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000920";
    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "alchemy-test-ca-bind";
    private static final String REPO = "alchemy-test-ca-bind-repo";
    private static final String MIRROR = "alchemy-test-ca-bind-mirror";
    private static final String PKG = "test-package";
    private static final String NAMESPACE = "alchemy";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void domainTokenEndpointAndGenericPackageLifecycle() {
        String authorization = auth(ACCOUNT);

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{\"tags\":[{\"key\":\"env\",\"value\":\"test\"}]}")
                .queryParam("domain", DOMAIN)
                .when()
                .post("/v1/domain")
                .then()
                .statusCode(200)
                .body("domain.name", equalTo(DOMAIN))
                .body("domain.status", equalTo("Active"))
                .body("domain.arn", containsString(":domain/" + DOMAIN));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .when()
                .get("/v1/domain")
                .then()
                .statusCode(200)
                .body("domain.s3BucketArn", containsString("codeartifact"));

        createRepository(authorization, REPO);
        createRepository(authorization, MIRROR);

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("duration", 900)
                .when()
                .post("/v1/authorization-token")
                .then()
                .statusCode(200)
                .body("authorizationToken.length()", greaterThan(100));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .when()
                .get("/v1/repository/endpoint")
                .then()
                .statusCode(200)
                .body("repositoryEndpoint", containsString("codeartifact"))
                .body("repositoryEndpoint", containsString(REPO));

        publish(authorization, REPO, "1.0.0", false);

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .when()
                .get("/v1/package")
                .then()
                .statusCode(200)
                .body("package.name", equalTo(PKG))
                .body("package.format", equalTo("generic"))
                .body("package.namespace", equalTo(NAMESPACE));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .queryParam("version", "1.0.0")
                .when()
                .get("/v1/package/version")
                .then()
                .statusCode(200)
                .body("packageVersion.version", equalTo("1.0.0"))
                .body("packageVersion.status", equalTo("Published"));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .when()
                .post("/v1/packages")
                .then()
                .statusCode(200)
                .body("packages.package", hasItem(PKG));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .when()
                .post("/v1/package/versions")
                .then()
                .statusCode(200)
                .body("versions.version", hasItem("1.0.0"));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .queryParam("version", "1.0.0")
                .when()
                .post("/v1/package/version/assets")
                .then()
                .statusCode(200)
                .body("assets.name", hasItem("artifact.txt"));

        Response asset = given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .queryParam("version", "1.0.0")
                .queryParam("asset", "artifact.txt")
                .when()
                .get("/v1/package/version/asset");
        assertEquals(200, asset.statusCode());
        assertEquals("hello codeartifact 1.0.0", asset.asString());

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .queryParam("version", "1.0.0")
                .when()
                .get("/v1/package/version/readme")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        publish(authorization, REPO, "2.0.0", true)
                .body("status", equalTo("Unfinished"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .body("{\"versions\":[\"2.0.0\"],\"targetStatus\":\"Published\"}")
                .when()
                .post("/v1/package/versions/update_status")
                .then()
                .statusCode(200)
                .body("successfulVersions.get('2.0.0').status", equalTo("Published"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", REPO)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .body("{\"restrictions\":{\"publish\":\"ALLOW\",\"upstream\":\"BLOCK\"}}")
                .when()
                .post("/v1/package")
                .then()
                .statusCode(200)
                .body("originConfiguration.restrictions.publish", equalTo("ALLOW"))
                .body("originConfiguration.restrictions.upstream", equalTo("BLOCK"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .queryParam("domain", DOMAIN)
                .queryParam("source-repository", REPO)
                .queryParam("destination-repository", MIRROR)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .body("{\"versions\":[\"1.0.0\"]}")
                .when()
                .post("/v1/package/versions/copy")
                .then()
                .statusCode(200)
                .body("successfulVersions.get('1.0.0').status", equalTo("Published"));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", MIRROR)
                .when()
                .post("/v1/packages")
                .then()
                .statusCode(200)
                .body("packages.package", hasItem(PKG));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", MIRROR)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .body("{\"versions\":[\"1.0.0\"]}")
                .when()
                .post("/v1/package/versions/dispose")
                .then()
                .statusCode(200)
                .body("successfulVersions.get('1.0.0').status", equalTo("Disposed"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", MIRROR)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .body("{\"versions\":[\"1.0.0\"]}")
                .when()
                .post("/v1/package/versions/delete")
                .then()
                .statusCode(200)
                .body("successfulVersions.get('1.0.0').status", equalTo("Deleted"));

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", MIRROR)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .when()
                .delete("/v1/package")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("domain", DOMAIN)
                .queryParam("repository", MIRROR)
                .when()
                .post("/v1/packages")
                .then()
                .statusCode(200)
                .body("packages.package", not(hasItem(PKG)));
    }

    @Test
    void describeDomainOnAMissingDomainFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000921"))
                .queryParam("domain", "missing-domain")
                .when()
                .get("/v1/domain")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("domain"));
    }

    private static void createRepository(String authorization, String name) {
        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{}")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", name)
                .when()
                .post("/v1/repository")
                .then()
                .statusCode(200)
                .body("repository.name", equalTo(name));
    }

    private static io.restassured.response.ValidatableResponse publish(
            String authorization, String repository, String version, boolean unfinished) {
        byte[] content = ("hello codeartifact " + version).getBytes(StandardCharsets.UTF_8);
        return given()
                .header("Authorization", authorization)
                .header("x-amz-content-sha256", sha256(content))
                .contentType("application/octet-stream")
                .queryParam("domain", DOMAIN)
                .queryParam("repository", repository)
                .queryParam("format", "generic")
                .queryParam("namespace", NAMESPACE)
                .queryParam("package", PKG)
                .queryParam("version", version)
                .queryParam("asset", "artifact.txt")
                .queryParam("unfinished", unfinished)
                .body(content)
                .when()
                .post("/v1/package/version/publish")
                .then()
                .statusCode(200);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION + "/codeartifact/aws4_request";
    }
}
