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
 * JSON 1.0 Network Firewall policy coverage used by Alchemy:
 * typed {@code ResourceNotFoundException} on describe of a missing policy,
 * create / in-place update / delete of a policy that references a rule group.
 */
@QuarkusTest
class NetworkFirewallFirewallPolicyIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/network-firewall/aws4_request";
    private static final String TARGET = "NetworkFirewall_20201112.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFirewallPolicy_missing_returnsResourceNotFound() {
        nfw("DescribeFirewallPolicy",
                "{\"FirewallPolicyName\":\"alchemy-nonexistent-nfw-policy-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateDeletePolicyReferencingRuleGroup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String groupName = "floci-nfw-rg-" + suffix;
        String policyName = "floci-nfw-policy-" + suffix;

        String groupArn = nfw("CreateRuleGroup", """
                {
                  "RuleGroupName": "%s",
                  "Type": "STATEFUL",
                  "Capacity": 100,
                  "Rules": "pass tcp any any -> any 443 (msg:\\"allow https\\"; sid:200001; rev:1;)",
                  "Tags": [{"Key":"fixture","Value":"nfw-rg"}]
                }
                """.formatted(groupName))
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupName", equalTo(groupName))
                .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
                .body("RuleGroupResponse.RuleGroupArn", containsString(":stateful-rulegroup/"))
                .extract().path("RuleGroupResponse.RuleGroupArn");

        nfw("DescribeRuleGroup",
                "{\"RuleGroupName\":\"%s\",\"Type\":\"STATEFUL\"}".formatted(groupName))
                .then()
                .statusCode(200)
                .body("RuleGroup.RulesSource.RulesString", containsString("sid:200001"))
                .body("RuleGroupResponse.RuleGroupArn", equalTo(groupArn));

        String policyArn = nfw("CreateFirewallPolicy", """
                {
                  "FirewallPolicyName": "%s",
                  "Description": "v1",
                  "FirewallPolicy": {
                    "StatelessDefaultActions": ["aws:forward_to_sfe"],
                    "StatelessFragmentDefaultActions": ["aws:forward_to_sfe"],
                    "StatefulRuleGroupReferences": [{"ResourceArn": "%s"}]
                  },
                  "Tags": [{"Key":"fixture","Value":"nfw-policy"}]
                }
                """.formatted(policyName, groupArn))
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyName", equalTo(policyName))
                .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"))
                .body("FirewallPolicyResponse.FirewallPolicyArn", containsString(":firewall-policy/"))
                .body("FirewallPolicyResponse.Description", equalTo("v1"))
                .extract().path("FirewallPolicyResponse.FirewallPolicyArn");

        String updateToken = nfw("DescribeFirewallPolicy",
                "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"))
                .body("FirewallPolicy.StatefulRuleGroupReferences[0].ResourceArn", equalTo(groupArn))
                .body("FirewallPolicyResponse.Description", equalTo("v1"))
                .body("FirewallPolicyResponse.Tags.Key", hasItem("fixture"))
                .extract().path("UpdateToken");

        nfw("ListFirewallPolicies", "{}")
                .then()
                .statusCode(200)
                .body("FirewallPolicies.Arn", hasItem(policyArn));

        nfw("UpdateFirewallPolicy", """
                {
                  "UpdateToken": "%s",
                  "FirewallPolicyArn": "%s",
                  "Description": "drop everything",
                  "FirewallPolicy": {
                    "StatelessDefaultActions": ["aws:drop"],
                    "StatelessFragmentDefaultActions": ["aws:drop"]
                  }
                }
                """.formatted(updateToken, policyArn))
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(policyArn));

        nfw("DescribeFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallPolicy.StatelessDefaultActions[0]", equalTo("aws:drop"))
                .body("FirewallPolicyResponse.Description", equalTo("drop everything"));

        nfw("DeleteFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(200);

        nfw("DescribeFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        nfw("ListFirewallPolicies", "{}")
                .then()
                .statusCode(200)
                .body("FirewallPolicies.Arn", not(hasItem(policyArn)));

        nfw("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200);

        nfw("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteRuleGroup_whileReferenced_returnsInvalidOperation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String groupName = "floci-nfw-rg-inuse-" + suffix;
        String policyName = "floci-nfw-policy-inuse-" + suffix;

        String groupArn = nfw("CreateRuleGroup", """
                {
                  "RuleGroupName": "%s",
                  "Type": "STATEFUL",
                  "Capacity": 10,
                  "Rules": "pass ip any any -> any any (sid:1;)"
                }
                """.formatted(groupName))
                .then()
                .statusCode(200)
                .extract().path("RuleGroupResponse.RuleGroupArn");

        nfw("CreateFirewallPolicy", """
                {
                  "FirewallPolicyName": "%s",
                  "FirewallPolicy": {
                    "StatelessDefaultActions": ["aws:pass"],
                    "StatelessFragmentDefaultActions": ["aws:pass"],
                    "StatefulRuleGroupReferences": [{"ResourceArn": "%s"}]
                  }
                }
                """.formatted(policyName, groupArn))
                .then()
                .statusCode(200);

        nfw("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidOperationException"));

        nfw("DeleteFirewallPolicy", "{\"FirewallPolicyName\":\"" + policyName + "\"}")
                .then()
                .statusCode(200);
        nfw("DeleteRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200);
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
}
