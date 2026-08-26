package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies Security Hub restJson1 hub enablement plus the action-target,
 * insight, automation-rule, and finding-aggregator lifecycle Alchemy
 * {@code Resources.test.ts} drives.
 */
@QuarkusTest
class SecurityHubIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000731";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeHubWhenNotEnabledIsInvalidAccess() {
        given()
                .header("Authorization", auth("000000000732", EAST))
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));
    }

    @Test
    void resourceLifecycleCreateUpdateTagsAndDisable() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"EnableDefaultStandards\":false,\"Tags\":{\"env\":\"test\"}}")
                .when()
                .post("/accounts")
                .then()
                .statusCode(200);

        String hubArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(200)
                .body("HubArn", startsWith("arn:aws:securityhub:" + EAST + ":" + ACCOUNT + ":hub/default"))
                .body("AutoEnableControls", equalTo(true))
                .extract()
                .path("HubArn");

        String actionArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"Escalate\",\"Description\":\"Escalate the selected findings\",\"Id\":\"Escalate\"}")
                .when()
                .post("/actionTargets")
                .then()
                .statusCode(200)
                .body("ActionTargetArn", startsWith("arn:aws:securityhub:"))
                .extract()
                .path("ActionTargetArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ActionTargetArns\":[\"" + actionArn + "\"]}")
                .when()
                .post("/actionTargets/get")
                .then()
                .statusCode(200)
                .body("ActionTargets", hasSize(1))
                .body("ActionTargets[0].Name", equalTo("Escalate"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"Escalate\",\"Description\":\"Escalate the selected findings to on-call\"}")
                .when()
                .patch("/actionTargets/" + encode(actionArn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ActionTargetArns\":[\"" + actionArn + "\"]}")
                .when()
                .post("/actionTargets/get")
                .then()
                .statusCode(200)
                .body("ActionTargets[0].Description", equalTo("Escalate the selected findings to on-call"));

        String insightArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"ActiveByResource",
                          "GroupByAttribute":"ResourceId",
                          "Filters":{"RecordState":[{"Value":"ACTIVE","Comparison":"EQUALS"}]}
                        }
                        """)
                .when()
                .post("/insights")
                .then()
                .statusCode(200)
                .body("InsightArn", notNullValue())
                .extract()
                .path("InsightArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"InsightArns\":[\"" + insightArn + "\"]}")
                .when()
                .post("/insights/get")
                .then()
                .statusCode(200)
                .body("Insights[0].GroupByAttribute", equalTo("ResourceId"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"ActiveByResource",
                          "GroupByAttribute":"SeverityLabel",
                          "Filters":{"RecordState":[{"Value":"ACTIVE","Comparison":"EQUALS"}]}
                        }
                        """)
                .when()
                .patch("/insights/" + encode(insightArn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"InsightArns\":[\"" + insightArn + "\"]}")
                .when()
                .post("/insights/get")
                .then()
                .statusCode(200)
                .body("Insights[0].GroupByAttribute", equalTo("SeverityLabel"));

        String ruleArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "RuleName":"SuppressInfo",
                          "Description":"Suppress informational findings",
                          "RuleOrder":1,
                          "RuleStatus":"ENABLED",
                          "Criteria":{"SeverityLabel":[{"Value":"INFORMATIONAL","Comparison":"EQUALS"}]},
                          "Actions":[{"Type":"FINDING_FIELDS_UPDATE","FindingFieldsUpdate":{"Workflow":{"Status":"SUPPRESSED"}}}],
                          "Tags":{"env":"test","alchemy::id":"SuppressInfo"}
                        }
                        """)
                .when()
                .post("/automationrules/create")
                .then()
                .statusCode(200)
                .body("RuleArn", notNullValue())
                .extract()
                .path("RuleArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AutomationRulesArns\":[\"" + ruleArn + "\"]}")
                .when()
                .post("/automationrules/get")
                .then()
                .statusCode(200)
                .body("Rules[0].RuleOrder", equalTo(1))
                .body("Rules[0].RuleStatus", equalTo("ENABLED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(ruleArn))
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("test"))
                .body("Tags['alchemy::id']", equalTo("SuppressInfo"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "UpdateAutomationRulesRequestItems":[{
                            "RuleArn":"%s",
                            "RuleName":"SuppressInfo",
                            "Description":"Suppress informational findings",
                            "RuleOrder":5,
                            "RuleStatus":"DISABLED",
                            "Criteria":{"SeverityLabel":[{"Value":"INFORMATIONAL","Comparison":"EQUALS"}]},
                            "Actions":[{"Type":"FINDING_FIELDS_UPDATE","FindingFieldsUpdate":{"Workflow":{"Status":"SUPPRESSED"}}}]
                          }]
                        }
                        """.formatted(ruleArn))
                .when()
                .patch("/automationrules/update")
                .then()
                .statusCode(200)
                .body("ProcessedAutomationRules", hasSize(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AutomationRulesArns\":[\"" + ruleArn + "\"]}")
                .when()
                .post("/automationrules/get")
                .then()
                .statusCode(200)
                .body("Rules[0].RuleOrder", equalTo(5))
                .body("Rules[0].RuleStatus", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"env\":\"prod\",\"alchemy::id\":\"SuppressInfo\"}}")
                .when()
                .post("/tags/" + encode(ruleArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(ruleArn))
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("prod"));

        String aggregatorArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionLinkingMode\":\"SPECIFIED_REGIONS\",\"Regions\":[\"eu-west-1\"]}")
                .when()
                .post("/findingAggregator/create")
                .then()
                .statusCode(200)
                .body("FindingAggregatorArn", notNullValue())
                .body("RegionLinkingMode", equalTo("SPECIFIED_REGIONS"))
                .body("Regions", equalTo(java.util.List.of("eu-west-1")))
                .extract()
                .path("FindingAggregatorArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/findingAggregator/get/" + encode(aggregatorArn))
                .then()
                .statusCode(200)
                .body("Regions", equalTo(java.util.List.of("eu-west-1")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"FindingAggregatorArn\":\"" + aggregatorArn
                        + "\",\"RegionLinkingMode\":\"SPECIFIED_REGIONS\",\"Regions\":[\"eu-west-1\",\"eu-central-1\"]}")
                .when()
                .patch("/findingAggregator/update")
                .then()
                .statusCode(200)
                .body("Regions", hasItems("eu-central-1", "eu-west-1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/findingAggregator/list")
                .then()
                .statusCode(200)
                .body("FindingAggregators", hasSize(1));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/accounts")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accounts")
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(hubArn))
                .then()
                .statusCode(401)
                .body("__type", equalTo("InvalidAccessException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/securityhub/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
