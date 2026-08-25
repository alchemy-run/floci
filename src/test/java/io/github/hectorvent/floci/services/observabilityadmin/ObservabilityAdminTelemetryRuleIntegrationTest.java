package io.github.hectorvent.floci.services.observabilityadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Observability Admin restJson1 evaluation + telemetry-rule lifecycle. */
@QuarkusTest
class ObservabilityAdminTelemetryRuleIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000003601";
    private static final String OTHER_ACCOUNT = "000000003602";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTelemetryRuleOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"RuleIdentifier\":\"alchemy-nonexistent-telemetry-rule\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createTelemetryRuleWithoutEvaluationFailsWithInvalidStateException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(OTHER_ACCOUNT, EAST))
                .body("""
                        {
                          "RuleName":"needs-evaluation",
                          "Rule":{"TelemetryType":"Logs","ResourceType":"AWS::EC2::VPC"}
                        }
                        """)
                .when()
                .post("/CreateTelemetryRule")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidStateException"));
    }

    @Test
    void telemetryRuleCreateGetUpdateTagsDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("NOT_STARTED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StartTelemetryEvaluation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("RUNNING"))
                .body("HomeRegion", equalTo(EAST));

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "RuleName":"lifecycle-rule",
                          "Rule":{
                            "ResourceType":"AWS::EC2::VPC",
                            "TelemetryType":"Logs",
                            "TelemetrySourceTypes":["VPC_FLOW_LOGS"],
                            "DestinationConfiguration":{
                              "DestinationType":"cloud-watch-logs",
                              "RetentionInDays":30
                            }
                          },
                          "Tags":{"Owner":"floci"}
                        }
                        """)
                .when()
                .post("/CreateTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleArn", notNullValue())
                .extract().path("RuleArn");
        assertTrue(arn.contains(":observabilityadmin:" + EAST + ":" + ACCOUNT + ":telemetry-rule/"));
        assertTrue(arn.endsWith("telemetry-rule/lifecycle-rule"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"lifecycle-rule\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleName", equalTo("lifecycle-rule"))
                .body("RuleArn", equalTo(arn))
                .body("TelemetryRule.TelemetryType", equalTo("Logs"))
                .body("TelemetryRule.ResourceType", equalTo("AWS::EC2::VPC"))
                .body("TelemetryRule.DestinationConfiguration.RetentionInDays", equalTo(30));

        List<Map<String, Object>> summaries = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListTelemetryRules")
                .then()
                .statusCode(200)
                .extract().path("TelemetryRuleSummaries");
        assertEquals(1, summaries.size());
        assertEquals("lifecycle-rule", summaries.get(0).get("RuleName"));
        assertEquals(arn, summaries.get(0).get("RuleArn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "RuleIdentifier":"lifecycle-rule",
                          "Rule":{
                            "ResourceType":"AWS::EC2::VPC",
                            "TelemetryType":"Logs",
                            "TelemetrySourceTypes":["VPC_FLOW_LOGS"],
                            "DestinationConfiguration":{
                              "DestinationType":"cloud-watch-logs",
                              "RetentionInDays":60
                            }
                          }
                        }
                        """)
                .when()
                .post("/UpdateTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"" + arn + "\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleArn", equalTo(arn))
                .body("TelemetryRule.DestinationConfiguration.RetentionInDays", equalTo(60));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Owner", equalTo("floci"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\",\"Tags\":{\"purpose\":\"alchemy-test\"}}")
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
                .body("Tags.Owner", equalTo("floci"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"purpose\"]}")
                .when()
                .post("/UntagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"lifecycle-rule\"}")
                .when()
                .post("/DeleteTelemetryRule")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"lifecycle-rule\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/observabilityadmin/aws4_request";
    }
}
