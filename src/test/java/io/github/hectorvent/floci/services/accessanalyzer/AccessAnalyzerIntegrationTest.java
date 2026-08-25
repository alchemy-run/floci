package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Access Analyzer restJson1 lifecycle, policy checks, and finding APIs. */
@QuarkusTest
class AccessAnalyzerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String IDENTITY_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":"arn:aws:s3:::alchemy-test-bucket/*"}]}
            """;
    private static final String WIDER_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject"],"Resource":"arn:aws:s3:::alchemy-test-bucket/*"}]}
            """;
    private static final String PRIVATE_BUCKET_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"s3:GetObject","Resource":"arn:aws:s3:::alchemy-test-bucket/*"}]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAnalyzerOnANonexistentAnalyzerFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", EAST))
                .when()
                .get("/analyzer/nonexistent-analyzer")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("nonexistent-analyzer"))
                .body("resourceType", equalTo("AWS::AccessAnalyzer::Analyzer"));
    }

    @Test
    void analyzerCreateTagsArchiveRulePolicyChecksAndDeleteLifecycle() {
        String authorization = auth("000000000302", EAST);
        String arn = create(authorization, """
                {
                  "analyzerName":"lifecycle-unused-access",
                  "type":"ACCOUNT_UNUSED_ACCESS",
                  "tags":{"Environment":"test"}
                }
                """);

        assertTrue(arn.contains(":analyzer/lifecycle-unused-access"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/analyzer/lifecycle-unused-access")
                .then()
                .statusCode(200)
                .body("analyzer.name", equalTo("lifecycle-unused-access"))
                .body("analyzer.arn", equalTo(arn))
                .body("analyzer.type", equalTo("ACCOUNT_UNUSED_ACCESS"))
                .body("analyzer.status", equalTo("ACTIVE"))
                .body("analyzer.tags.Environment", equalTo("test"))
                .body("analyzer.configuration.unusedAccess.unusedAccessAge", equalTo(90));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"platform\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ruleName":"unused-roles",
                          "filter":{"findingType":{"eq":["UnusedIAMRole"]}}
                        }
                        """)
                .when()
                .put("/analyzer/lifecycle-unused-access/archive-rule")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/analyzer/lifecycle-unused-access/archive-rule/unused-roles")
                .then()
                .statusCode(200)
                .body("archiveRule.ruleName", equalTo("unused-roles"))
                .body("archiveRule.filter.findingType.eq[0]", equalTo("UnusedIAMRole"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerArn\":\"" + arn + "\",\"ruleName\":\"unused-roles\"}")
                .when()
                .put("/archive-rule")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerArn\":\"" + arn + "\",\"maxResults\":25}")
                .when()
                .post("/findingv2")
                .then()
                .statusCode(200)
                .body("findings.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/findingv2/00000000-0000-0000-0000-000000000000?analyzerArn=" + encode(arn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("AWS::AccessAnalyzer::Finding"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerArn\":\"" + arn + "\"}")
                .when()
                .post("/analyzed-resource")
                .then()
                .statusCode(200)
                .body("analyzedResources.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerArn\":\"" + arn + "\"}")
                .when()
                .post("/analyzer/findings/statistics")
                .then()
                .statusCode(200)
                .body("findingsStatistics[0].unusedAccessFindingsStatistics.totalActiveFindings", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/policy/generation")
                .then()
                .statusCode(200)
                .body("policyGenerations.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/analyzer/lifecycle-unused-access")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/analyzer/lifecycle-unused-access")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createSecondAnalyzerWithTheSameNameConflicts() {
        String authorization = auth("000000000303", EAST);
        create(authorization, """
                {"analyzerName":"duplicate","type":"ACCOUNT"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerName\":\"duplicate\",\"type\":\"ACCOUNT\"}")
                .when()
                .put("/analyzer")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("resourceId", equalTo("duplicate"));
    }

    @Test
    void createSecondAnalyzerOfTheSameTypeExceedsQuota() {
        String authorization = auth("000000000309", EAST);
        create(authorization, """
                {"analyzerName":"first-account","type":"ACCOUNT"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"analyzerName\":\"second-account\",\"type\":\"ACCOUNT\"}")
                .when()
                .put("/analyzer")
                .then()
                .statusCode(402)
                .body("__type", equalTo("ServiceQuotaExceededException"));
    }

    @Test
    void analyzersAreIsolatedByAccountAndRegion() {
        String first = auth("000000000304", EAST);
        String second = auth("000000000305", EAST);
        String west = auth("000000000304", WEST);

        String firstArn = create(first, """
                {"analyzerName":"shared-name","type":"ACCOUNT"}
                """);
        String secondArn = create(second, """
                {"analyzerName":"shared-name","type":"ACCOUNT"}
                """);
        String westArn = create(west, """
                {"analyzerName":"shared-name","type":"ACCOUNT"}
                """);

        assertFalse(firstArn.equals(secondArn));
        assertFalse(firstArn.equals(westArn));
        get(first, "shared-name").then().body("analyzer.arn", equalTo(firstArn));
        get(second, "shared-name").then().body("analyzer.arn", equalTo(secondArn));
        get(west, "shared-name").then().body("analyzer.arn", equalTo(westArn));
    }

    @Test
    void validatePolicyAcceptsAWellFormedIdentityPolicy() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000306", EAST))
                .body("{\"policyDocument\":" + quote(IDENTITY_POLICY) + ",\"policyType\":\"IDENTITY_POLICY\"}")
                .when()
                .post("/policy/validation")
                .then()
                .statusCode(200)
                .body("findings.size()", equalTo(0));
    }

    @Test
    void checkApisPassForSubsetPrivateAndUngrantedAccess() {
        String authorization = auth("000000000307", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"existingPolicyDocument\":" + quote(WIDER_POLICY)
                        + ",\"newPolicyDocument\":" + quote(IDENTITY_POLICY)
                        + ",\"policyType\":\"IDENTITY_POLICY\"}")
                .when()
                .post("/policy/check-no-new-access")
                .then()
                .statusCode(200)
                .body("result", equalTo("PASS"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policyDocument\":" + quote(IDENTITY_POLICY)
                        + ",\"policyType\":\"IDENTITY_POLICY\""
                        + ",\"access\":[{\"actions\":[\"s3:DeleteBucket\"]}]}")
                .when()
                .post("/policy/check-access-not-granted")
                .then()
                .statusCode(200)
                .body("result", equalTo("PASS"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policyDocument\":" + quote(PRIVATE_BUCKET_POLICY)
                        + ",\"resourceType\":\"AWS::S3::Bucket\"}")
                .when()
                .post("/policy/check-no-public-access")
                .then()
                .statusCode(200)
                .body("result", equalTo("PASS"));
    }

    @Test
    void policyGenerationWithoutCloudTrailDetailsIsAValidationException() {
        String authorization = auth("000000000308", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policyGenerationDetails\":{\"principalArn\":\"arn:aws:iam::000000000308:role/example\"}}")
                .when()
                .put("/policy/generation")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("reason", equalTo("fieldValidationFailed"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/policy/generation/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .put("/policy/generation/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/access-analyzer/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .put("/analyzer")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract().path("arn");
    }

    private static Response get(String authorization, String analyzerName) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/analyzer/" + analyzerName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
