package io.github.hectorvent.floci.services.networkfirewall;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * JSON 1.0 Network Firewall rule-group coverage used by Alchemy:
 * typed {@code ResourceNotFoundException} on describe of a missing group,
 * stateful Suricata create/update, stateless definition, tags, and delete.
 */
@QuarkusTest
class NetworkFirewallRuleGroupIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "NetworkFirewall_20201112.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/network-firewall/aws4_request";
    private static final String SURICATA_V1 =
            "pass tcp any any -> any 80 (msg:\"allow http\"; sid:100001; rev:1;)";
    private static final String SURICATA_V2 =
            "drop tcp any any -> any 23 (msg:\"block telnet\"; sid:100002; rev:1;)";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRuleGroup_missing_returnsResourceNotFound() {
        nfw("DescribeRuleGroup", """
                {"RuleGroupName":"alchemy-nonexistent-nfw-rulegroup-probe","Type":"STATEFUL"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void statefulRuleGroup_createUpdateTagsAndDelete() {
        String name = "floci-nfw-rg-" + UUID.randomUUID().toString().substring(0, 8);

        var created = nfw("CreateRuleGroup", """
                {
                  "RuleGroupName": "%s",
                  "Type": "STATEFUL",
                  "Capacity": 100,
                  "Description": "v1",
                  "Rules": %s,
                  "Tags": [{"Key":"fixture","Value":"nfw-rulegroup"}]
                }
                """.formatted(name, jsonString(SURICATA_V1)))
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupName", equalTo(name))
                .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
                .body("RuleGroupResponse.Type", equalTo("STATEFUL"))
                .body("RuleGroupResponse.Capacity", equalTo(100))
                .body("RuleGroupResponse.RuleGroupArn", containsString(":stateful-rulegroup/"))
                .extract();
        String arn = created.path("RuleGroupResponse.RuleGroupArn");
        String token = created.path("UpdateToken");

        nfw("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
                .body("RuleGroup.RulesSource.RulesString", equalTo(SURICATA_V1))
                .body("RuleGroupResponse.Description", equalTo("v1"));

        nfw("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        nfw("UpdateRuleGroup", """
                {
                  "UpdateToken": "%s",
                  "RuleGroupArn": "%s",
                  "Type": "STATEFUL",
                  "Description": "v2",
                  "Rules": %s
                }
                """.formatted(token, arn, jsonString(SURICATA_V2)))
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupArn", equalTo(arn));

        nfw("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("RuleGroup.RulesSource.RulesString", equalTo(SURICATA_V2))
                .body("RuleGroupResponse.Description", equalTo("v2"));

        nfw("ListRuleGroups", "{\"Scope\":\"ACCOUNT\"}")
                .then()
                .statusCode(200)
                .body("RuleGroups.Arn", hasItem(arn));

        nfw("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        nfw("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void statelessRuleGroup_createDescribeDelete() {
        String name = "floci-nfw-stateless-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = nfw("CreateRuleGroup", """
                {
                  "RuleGroupName": "%s",
                  "Type": "STATELESS",
                  "Capacity": 10,
                  "RuleGroup": {
                    "RulesSource": {
                      "StatelessRulesAndCustomActions": {
                        "StatelessRules": [{
                          "Priority": 1,
                          "RuleDefinition": {
                            "Actions": ["aws:pass"],
                            "MatchAttributes": {
                              "Sources": [{"AddressDefinition": "0.0.0.0/0"}],
                              "Destinations": [{"AddressDefinition": "0.0.0.0/0"}],
                              "Protocols": [6],
                              "DestinationPorts": [{"FromPort": 80, "ToPort": 80}]
                            }
                          }
                        }]
                      }
                    }
                  }
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.Type", equalTo("STATELESS"))
                .body("RuleGroupResponse.Capacity", equalTo(10))
                .body("RuleGroupResponse.RuleGroupArn", containsString(":stateless-rulegroup/"))
                .extract().path("RuleGroupResponse.RuleGroupArn");

        nfw("DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATELESS\"}")
                .then()
                .statusCode(200)
                .body("RuleGroup.RulesSource.StatelessRulesAndCustomActions.StatelessRules[0].Priority",
                        equalTo(1));

        nfw("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        nfw("ListRuleGroups", "{}")
                .then()
                .statusCode(200)
                .body("RuleGroups.Arn", not(hasItem(arn)));
    }

    private static Response nfw(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
