package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AccessAnalyzerIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/access-analyzer/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void analyzerLifecycle() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-analyzer\",\"type\":\"ORGANIZATION\"}")
                .put("/analyzer").then().statusCode(200).body("arn", notNullValue());
        given().header("Authorization", AUTH).get("/analyzer")
                .then().statusCode(200).body("analyzers", hasSize(1)).body("analyzers[0].name", equalTo("org-analyzer"));
        given().header("Authorization", AUTH).delete("/analyzer/org-analyzer").then().statusCode(200);
        given().header("Authorization", AUTH).get("/analyzer")
                .then().statusCode(200).body("analyzers", hasSize(0));
    }

    @Test
    void analyzerQuotasAreIndependentByExactType() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-external\",\"type\":\"ACCOUNT\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-unused\",\"type\":\"ACCOUNT_UNUSED_ACCESS\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-external-2\",\"type\":\"ACCOUNT\"}")
                .put("/analyzer").then().statusCode(402).body("__type", equalTo("ServiceQuotaExceededException"));

        given().header("Authorization", AUTH).delete("/analyzer/account-unused").then().statusCode(200);
        given().header("Authorization", AUTH).delete("/analyzer/account-external").then().statusCode(200);
    }

    @Test
    void organizationInternalAccessAnalyzerLimitIsOne() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-internal-1\",\"type\":\"ORGANIZATION_INTERNAL_ACCESS\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-internal-2\",\"type\":\"ORGANIZATION_INTERNAL_ACCESS\"}")
                .put("/analyzer").then().statusCode(402).body("__type", equalTo("ServiceQuotaExceededException"));
        given().header("Authorization", AUTH).delete("/analyzer/org-internal-1").then().statusCode(200);
    }

    @Test
    void trailingJsonReturnsSerializationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"trailing-json\",\"type\":\"ACCOUNT\"} {}")
                .put("/analyzer").then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void missingAnalyzerUsesAccessAnalyzerJsonNotS3Xml() {
        given().header("Authorization", AUTH).get("/analyzer/missing-access-analyzer")
                .then().statusCode(404).contentType("application/json")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("missing-access-analyzer"))
                .body("resourceType", equalTo("AWS::AccessAnalyzer::Analyzer"));
    }

    @Test
    void tagsArchiveRulesAndBindingsUseAwsPathsAndRespectAccountAndRegion() {
        String owner = auth("111122223333", "us-east-1");
        String foreign = auth("444455556666", "us-east-1");
        String otherRegion = auth("111122223333", "us-west-2");
        String arn = "arn:aws:access-analyzer:us-east-1:111122223333:analyzer/contract-unused";
        String path = "/analyzer/contract-unused";
        given().contentType("application/json").header("Authorization", owner)
                .body(Map.of("analyzerName", "contract-unused", "type", "ACCOUNT_UNUSED_ACCESS",
                        "configuration", Map.of("unusedAccess", Map.of("unusedAccessAge", 180)),
                        "tags", Map.of("Environment", "test", "remove", "yes")))
                .put("/analyzer").then().statusCode(200).body("arn", equalTo(arn));
        try {
            given().header("Authorization", owner).get(path).then().statusCode(200)
                    .body("analyzer.type", equalTo("ACCOUNT_UNUSED_ACCESS"))
                    .body("analyzer.configuration.unusedAccess.unusedAccessAge", equalTo(180))
                    .body("analyzer.createdAt", notNullValue());
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("tags", Map.of("Environment", "prod", "extra", "x")))
                    .post("/tags/{arn}", arn).then().statusCode(200);
            given().header("Authorization", owner).queryParam("tagKeys", "remove", "extra")
                    .delete("/tags/{arn}", arn).then().statusCode(200);
            given().header("Authorization", owner).get("/tags/{arn}", arn).then().statusCode(200)
                    .body("tags", equalTo(Map.of("Environment", "prod")));
            given().header("Authorization", owner).get(path).then().statusCode(200)
                    .body("analyzer.tags", equalTo(Map.of("Environment", "prod")));
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("ruleName", "trusted", "filter", Map.of("findingType", Map.of("eq", List.of("UnusedIAMRole")))))
                    .put(path + "/archive-rule").then().statusCode(200);
            given().header("Authorization", owner).get(path + "/archive-rule/trusted").then().statusCode(200)
                    .body("archiveRule.filter.findingType.eq", equalTo(List.of("UnusedIAMRole")))
                    .body("archiveRule.createdAt", notNullValue()).body("archiveRule.updatedAt", notNullValue());
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("filter", Map.of("findingType", Map.of("eq", List.of("UnusedIAMUserAccessKey")))))
                    .put(path + "/archive-rule/trusted").then().statusCode(200);
            given().header("Authorization", owner).get(path + "/archive-rule").then().statusCode(200)
                    .body("archiveRules[0].filter.findingType.eq", equalTo(List.of("UnusedIAMUserAccessKey")));
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("analyzerArn", arn, "ruleName", "trusted"))
                    .put("/archive-rule").then().statusCode(200);
            for (String authorization : List.of(foreign, otherRegion)) {
                given().header("Authorization", authorization).get(path).then().statusCode(404);
                given().header("Authorization", authorization).get(path + "/archive-rule/trusted").then().statusCode(404);
                given().header("Authorization", authorization).get("/tags/{arn}", arn).then().statusCode(404);
                given().contentType("application/json").header("Authorization", authorization)
                        .body(Map.of("analyzerArn", arn)).post("/findingv2").then().statusCode(404);
                given().contentType("application/json").header("Authorization", authorization)
                        .body(Map.of("tags", Map.of("Environment", "foreign"))).post("/tags/{arn}", arn)
                        .then().statusCode(404);
            }
            for (String collection : List.of("findingv2", "analyzed-resource")) {
                given().contentType("application/json").header("Authorization", owner)
                        .body(Map.of("analyzerArn", arn, "maxResults", 25)).post("/" + collection)
                        .then().statusCode(200).body(collection.equals("findingv2") ? "findings" : "analyzedResources", hasSize(0));
            }
            given().contentType("application/json").header("Authorization", owner).body(Map.of("analyzerArn", arn))
                    .post("/analyzer/findings/statistics").then().statusCode(200)
                    .body("findingsStatistics[0].unusedAccessFindingsStatistics.totalActiveFindings", equalTo(0));
            given().header("Authorization", owner).queryParam("analyzerArn", arn).get("/findingv2/unknown")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("analyzerArn", arn, "resourceArn", "arn:aws:s3:::example"))
                    .post("/resource/scan").then().statusCode(400).body("__type", equalTo("ValidationException"));
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("analyzerArn", arn, "configurations", Map.of()))
                    .put("/access-preview").then().statusCode(400).body("__type", equalTo("ValidationException"));
            given().header("Authorization", owner).delete(path + "/archive-rule/trusted").then().statusCode(200);
            given().contentType("application/json").header("Authorization", owner)
                    .body(Map.of("analyzerArn", arn, "ruleName", "trusted")).put("/archive-rule")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            given().header("Authorization", owner).delete(path).then().statusCode(200);
        }
        given().header("Authorization", owner).get(path).then().statusCode(404);
        given().header("Authorization", owner).get("/tags/{arn}", arn).then().statusCode(404);
    }

    @Test
    void policyEndpointsEvaluatePositiveNegativeAndUnsupportedRequests() {
        String read = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::example/*\"}}";
        String wide = read.replace("s3:GetObject", "s3:*");
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("policyType", "IDENTITY_POLICY", "policyDocument", read)).post("/policy/validation")
                .then().statusCode(200).body("findings[0].findingType", equalTo("WARNING"));
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("policyType", "IDENTITY_POLICY", "policyDocument", "{")).post("/policy/validation")
                .then().statusCode(200).body("findings[0].findingType", equalTo("ERROR"));
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("policyType", "IDENTITY_POLICY", "existingPolicyDocument", wide, "newPolicyDocument", read))
                .post("/policy/check-no-new-access").then().statusCode(200).body("result", equalTo("PASS"));
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("policyType", "IDENTITY_POLICY", "existingPolicyDocument", read, "newPolicyDocument", wide))
                .post("/policy/check-no-new-access").then().statusCode(200).body("result", equalTo("FAIL"));
        for (String action : List.of("s3:DeleteBucket", "s3:GetObject")) {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body(Map.of("policyType", "IDENTITY_POLICY", "policyDocument", read,
                            "access", List.of(Map.of("actions", List.of(action)))))
                    .post("/policy/check-access-not-granted").then().statusCode(200)
                    .body("result", equalTo(action.equals("s3:GetObject") ? "FAIL" : "PASS"));
        }
        String publicPolicy = read.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Allow\",\"Principal\":\"*\"");
        String privatePolicy = publicPolicy.replace("\"Principal\":\"*\"", "\"Principal\":{\"AWS\":\"111111111111\"}");
        for (String policy : List.of(publicPolicy, privatePolicy)) {
            given().contentType("application/json").header("Authorization", AUTH)
                    .body(Map.of("resourceType", "AWS::S3::Bucket", "policyDocument", policy))
                    .post("/policy/check-no-public-access").then().statusCode(200)
                    .body("result", equalTo(policy.equals(publicPolicy) ? "FAIL" : "PASS"));
        }
        String conditional = publicPolicy.replace("\"Effect\":\"Allow\"", "\"Effect\":\"Allow\",\"Condition\":{}");
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("resourceType", "AWS::S3::Bucket", "policyDocument", conditional))
                .post("/policy/check-no-public-access").then().statusCode(400)
                .body("__type", equalTo("ValidationException")).body("reason", equalTo("other"));
    }

    @Test
    void policyGenerationRejectsMissingInputsAndDoesNotInventJobs() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(Map.of("policyGenerationDetails", Map.of("principalArn", "arn:aws:iam::111111111111:role/example")))
                .put("/policy/generation").then().statusCode(400)
                .body("__type", equalTo("ValidationException")).body("message", equalTo("Missing cloudTrailDetails"));
        given().header("Authorization", AUTH).get("/policy/generation/unknown").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .put("/policy/generation/unknown").then().statusCode(400).body("__type", equalTo("ValidationException"));
        given().header("Authorization", AUTH).get("/policy/generation").then().statusCode(200)
                .body("policyGenerations", hasSize(0));
    }

    private String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260904/" + region + "/access-analyzer/aws4_request";
    }

    @Test
    void invalidAnalyzerTypeReturnsValidationError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"bad-analyzer\",\"type\":\"INVALID\"}")
                .put("/analyzer").then().statusCode(400).body("__type", equalTo("ValidationException"));
    }
}
