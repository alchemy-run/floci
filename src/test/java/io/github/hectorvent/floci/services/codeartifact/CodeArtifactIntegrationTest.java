package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies CodeArtifact restJson1 domain + repository lifecycle. */
@QuarkusTest
class CodeArtifactIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String DOMAIN = "lifecycle-domain";
    private static final String SHARED = "lifecycle-shared";
    private static final String REPO = "lifecycle-repo";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeDomainOnANonexistentDomainFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000801", EAST))
                .when()
                .get("/v1/domain?domain=missing-domain")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("missing-domain"))
                .body("resourceType", equalTo("domain"));
    }

    @Test
    void domainAndRepositoryCreateUpdateTagsAndDeleteLifecycle() {
        String authorization = auth("000000000802", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":[{\"key\":\"env\",\"value\":\"test\"}]}")
                .when()
                .post("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(200)
                .body("domain.name", equalTo(DOMAIN))
                .body("domain.arn", notNullValue())
                .body("domain.status", equalTo("Active"))
                .body("domain.repositoryCount", equalTo(0));

        String domainArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(200)
                .body("domain.name", equalTo(DOMAIN))
                .body("domain.arn", notNullValue())
                .extract().path("domain.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/v1/domains")
                .then()
                .statusCode(200)
                .body("domains.find { it.name == '" + DOMAIN + "' }.arn", equalTo(domainArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"description\":\"shared-first\"}")
                .when()
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + SHARED)
                .then()
                .statusCode(200)
                .body("repository.name", equalTo(SHARED))
                .body("repository.domainName", equalTo(DOMAIN))
                .body("repository.description", equalTo("shared-first"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"first",
                          "upstreams":[{"repositoryName":"%s"}],
                          "tags":[{"key":"env","value":"test"}]
                        }
                        """.formatted(SHARED))
                .when()
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then()
                .statusCode(200)
                .body("repository.name", equalTo(REPO))
                .body("repository.description", equalTo("first"))
                .body("repository.upstreams[0].repositoryName", equalTo(SHARED))
                .body("repository.arn", notNullValue());

        String repositoryArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then()
                .statusCode(200)
                .body("repository.name", equalTo(REPO))
                .body("repository.description", equalTo("first"))
                .body("repository.upstreams[0].repositoryName", equalTo(SHARED))
                .extract().path("repository.arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(200)
                .body("domain.repositoryCount", greaterThanOrEqualTo(2));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"second",
                          "upstreams":[{"repositoryName":"%s"}]
                        }
                        """.formatted(SHARED))
                .when()
                .put("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then()
                .statusCode(200)
                .body("repository.description", equalTo("second"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/tags?resourceArn=" + encode(repositoryArn))
                .then()
                .statusCode(200)
                .body("tags.find { it.key == 'env' }.value", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":[{\"key\":\"team\",\"value\":\"platform\"}]}")
                .when()
                .post("/v1/tag?resourceArn=" + encode(repositoryArn))
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/tags?resourceArn=" + encode(repositoryArn))
                .then()
                .statusCode(200)
                .body("tags.find { it.key == 'team' }.value", equalTo("platform"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then()
                .statusCode(200)
                .body("repository.name", equalTo(REPO));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + SHARED)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(200)
                .body("domain.name", equalTo(DOMAIN));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/domain?domain=" + DOMAIN)
                .then()
                .statusCode(200);
    }

    @Test
    void deletingARepositoryUsedAsAnUpstreamFailsWithConflictException() {
        String authorization = auth("000000000803", EAST);
        String domain = "conflict-domain";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/v1/domain?domain=" + domain)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/v1/repository?domain=" + domain + "&repository=upstream-repo")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"upstreams\":[{\"repositoryName\":\"upstream-repo\"}]}")
                .when()
                .post("/v1/repository?domain=" + domain + "&repository=downstream-repo")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/repository?domain=" + domain + "&repository=upstream-repo")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/domain?domain=" + domain)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/repository?domain=" + domain + "&repository=downstream-repo")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/repository?domain=" + domain + "&repository=upstream-repo")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/domain?domain=" + domain)
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/codeartifact/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
