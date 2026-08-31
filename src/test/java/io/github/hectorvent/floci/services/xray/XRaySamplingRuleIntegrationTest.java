package io.github.hectorvent.floci.services.xray;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies X-Ray restJson1 sampling-rule lifecycle used by Alchemy. */
@QuarkusTest
class XRaySamplingRuleIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000004901";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSamplingRulesOnEmptyAccountReturnsTheDefaultRule() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000004902", EAST))
                .body("{}")
                .when()
                .post("/GetSamplingRules")
                .then()
                .statusCode(200)
                .body("SamplingRuleRecords.SamplingRule.RuleName", hasItem("Default"));
    }

    @Test
    void createGetUpdateTagsDeleteSamplingRuleLifecycle() {
        String authorization = auth(ACCOUNT, EAST);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SamplingRule":{
                            "RuleName":"lifecycle-rule",
                            "ResourceARN":"*",
                            "Priority":9000,
                            "FixedRate":0.05,
                            "ReservoirSize":1,
                            "ServiceName":"alchemy-xray-test-*",
                            "ServiceType":"*",
                            "Host":"*",
                            "HTTPMethod":"*",
                            "URLPath":"*",
                            "Version":1
                          },
                          "Tags":[{"Key":"Environment","Value":"test"}]
                        }
                        """)
                .when()
                .post("/CreateSamplingRule")
                .then()
                .statusCode(200)
                .body("SamplingRuleRecord.SamplingRule.RuleName", equalTo("lifecycle-rule"))
                .body("SamplingRuleRecord.SamplingRule.Priority", equalTo(9000))
                .body("SamplingRuleRecord.SamplingRule.FixedRate", equalTo(0.05f))
                .body("SamplingRuleRecord.SamplingRule.ReservoirSize", equalTo(1))
                .body("SamplingRuleRecord.SamplingRule.ServiceName", equalTo("alchemy-xray-test-*"))
                .body("SamplingRuleRecord.SamplingRule.ServiceType", equalTo("*"))
                .body("SamplingRuleRecord.SamplingRule.RuleARN", notNullValue())
                .extract().path("SamplingRuleRecord.SamplingRule.RuleARN");
        assertTrue(arn.contains(":xray:" + EAST + ":" + ACCOUNT + ":sampling-rule/lifecycle-rule"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SamplingRule":{
                            "RuleName":"lifecycle-rule",
                            "ResourceARN":"*",
                            "Priority":9000,
                            "FixedRate":0.05,
                            "ReservoirSize":1,
                            "ServiceName":"alchemy-xray-test-*",
                            "ServiceType":"*",
                            "Host":"*",
                            "HTTPMethod":"*",
                            "URLPath":"*",
                            "Version":1
                          }
                        }
                        """)
                .when()
                .post("/CreateSamplingRule")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Sampling rule already exists"));

        List<Map<String, Object>> records = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetSamplingRules")
                .then()
                .statusCode(200)
                .extract().path("SamplingRuleRecords.SamplingRule");
        assertTrue(records.stream().anyMatch(rule -> "lifecycle-rule".equals(rule.get("RuleName"))
                && arn.equals(rule.get("RuleARN"))));
        assertTrue(records.stream().anyMatch(rule -> "Default".equals(rule.get("RuleName"))));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SamplingRuleUpdate":{
                            "RuleName":"lifecycle-rule",
                            "Priority":8000,
                            "FixedRate":0.1,
                            "ReservoirSize":2,
                            "HTTPMethod":"GET",
                            "Attributes":{"tier":"premium"}
                          }
                        }
                        """)
                .when()
                .post("/UpdateSamplingRule")
                .then()
                .statusCode(200)
                .body("SamplingRuleRecord.SamplingRule.Priority", equalTo(8000))
                .body("SamplingRuleRecord.SamplingRule.FixedRate", equalTo(0.1f))
                .body("SamplingRuleRecord.SamplingRule.ReservoirSize", equalTo(2))
                .body("SamplingRuleRecord.SamplingRule.HTTPMethod", equalTo("GET"))
                .body("SamplingRuleRecord.SamplingRule.Attributes.tier", equalTo("premium"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Environment"))
                .body("Tags.Value", hasItem("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "Tags":[{"Key":"Extra","Value":"1"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/TagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Extra"))
                .body("Tags.Value", hasItem("1"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "TagKeys":["Extra"]
                        }
                        """.formatted(arn))
                .when()
                .post("/UntagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Environment"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleName\":\"lifecycle-rule\"}")
                .when()
                .post("/DeleteSamplingRule")
                .then()
                .statusCode(200)
                .body("SamplingRuleRecord.SamplingRule.RuleName", equalTo("lifecycle-rule"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleName\":\"lifecycle-rule\"}")
                .when()
                .post("/DeleteSamplingRule")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Sampling rule does not exist"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SamplingRuleUpdate":{
                            "RuleName":"lifecycle-rule",
                            "Priority":8000
                          }
                        }
                        """)
                .when()
                .post("/UpdateSamplingRule")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Sampling rule does not exist"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/xray/aws4_request";
    }
}
