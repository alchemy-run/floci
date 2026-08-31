package io.github.hectorvent.floci.services.observabilityadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Observability Admin restJson1 APIs used by Alchemy bindings. */
@QuarkusTest
class ObservabilityAdminIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002401";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTelemetryEvaluationStatusDefaultsToNotStarted() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, "us-west-2"))
                .body("{}")
                .when()
                .post("/GetTelemetryEvaluationStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("NOT_STARTED"));
    }

    @Test
    void getMissingTelemetryRuleFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"RuleIdentifier\":\"missing-rule\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createTelemetryRuleWithoutOnboardingFailsWithInvalidStateException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, "eu-west-1"))
                .body("""
                        {
                          "RuleName":"needs-onboarding",
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
    void evaluationRuleListTagsAndResourceTelemetryLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "bindings-" + UUID.randomUUID().toString().substring(0, 8);

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
                          "RuleName":"%s",
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
                        """.formatted(name))
                .when()
                .post("/CreateTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleArn", notNullValue())
                .extract().path("RuleArn");
        assertTrue(arn.contains(":observabilityadmin:" + EAST + ":" + ACCOUNT + ":telemetry-rule/" + name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"" + name + "\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(200)
                .body("RuleName", equalTo(name))
                .body("RuleArn", equalTo(arn))
                .body("TelemetryRule.TelemetryType", equalTo("Logs"))
                .body("TelemetryRule.DestinationConfiguration.RetentionInDays", equalTo(30));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "RuleIdentifier":"%s",
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
                        """.formatted(name))
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
                .body("TelemetryRule.DestinationConfiguration.RetentionInDays", equalTo(60));

        List<Map<String, Object>> summaries = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListTelemetryRules")
                .then()
                .statusCode(200)
                .extract().path("TelemetryRuleSummaries");
        assertTrue(summaries.stream().anyMatch(summary -> name.equals(summary.get("RuleName"))));

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
                .body("""
                        {
                          "ResourceARN":"%s",
                          "Tags":{"purpose":"alchemy-test"}
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
                .body("Tags.Owner", equalTo("floci"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "TagKeys":["purpose"]
                        }
                        """.formatted(arn))
                .when()
                .post("/UntagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceTypes\":[\"AWS::EC2::VPC\"],\"MaxResults\":10}")
                .when()
                .post("/ListResourceTelemetry")
                .then()
                .statusCode(200)
                .body("TelemetryConfigurations.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTelemetryEnrichmentStatus")
                .then()
                .statusCode(200)
                .body("Status", equalTo("Stopped"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"" + name + "\"}")
                .when()
                .post("/DeleteTelemetryRule")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RuleIdentifier\":\"" + name + "\"}")
                .when()
                .post("/GetTelemetryRule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/StopTelemetryEvaluation")
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
                .body("Status", equalTo("STOPPED"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/observabilityadmin/aws4_request";
    }
}
