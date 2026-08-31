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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy {@code Bindings.test.ts}: policy and
 * stateful rule-group describes by ARN, including Suricata summary and
 * metadata (capacity / type) without the full definition.
 */
@QuarkusTest
class NetworkFirewallDescribeBindingsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/network-firewall/aws4_request";
    private static final String TARGET = "NetworkFirewall_20201112.";
    private static final String SURICATA =
            "pass tcp any any -> any 443 (msg:\"allow https\"; sid:100001; rev:1;)";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRuleGroupSummary_missing_returnsResourceNotFound() {
        nfw("DescribeRuleGroupSummary",
                "{\"RuleGroupName\":\"alchemy-nonexistent-nfw-summary\",\"Type\":\"STATEFUL\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeRuleGroupMetadata_missing_returnsResourceNotFound() {
        nfw("DescribeRuleGroupMetadata",
                "{\"RuleGroupName\":\"alchemy-nonexistent-nfw-metadata\",\"Type\":\"STATEFUL\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void alchemyBindings_describePolicyAndRuleGroupByArn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String groupName = "floci-nfw-bind-rg-" + suffix;
        String policyName = "floci-nfw-bind-pol-" + suffix;

        String groupArn = nfw("CreateRuleGroup", """
                {
                  "RuleGroupName": "%s",
                  "Type": "STATEFUL",
                  "Capacity": 10,
                  "Rules": %s,
                  "SummaryConfiguration": {"RuleOptions": ["SID", "MSG"]}
                }
                """.formatted(groupName, jsonString(SURICATA)))
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
                .body("RuleGroupResponse.Type", equalTo("STATEFUL"))
                .body("RuleGroupResponse.Capacity", equalTo(10))
                .body("RuleGroupResponse.SummaryConfiguration.RuleOptions[0]", equalTo("SID"))
                .extract().path("RuleGroupResponse.RuleGroupArn");

        nfw("DescribeRuleGroup", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("RuleGroupResponse.RuleGroupStatus", equalTo("ACTIVE"))
                .body("RuleGroupResponse.Type", equalTo("STATEFUL"))
                .body("RuleGroup.RulesSource.RulesString", containsString("allow https"));

        nfw("DescribeRuleGroupSummary", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("Summary.RuleSummaries", hasSize(1))
                .body("Summary.RuleSummaries[0].SID", equalTo("100001"))
                .body("Summary.RuleSummaries[0].Msg", equalTo("allow https"));

        nfw("DescribeRuleGroupMetadata", "{\"RuleGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("Capacity", equalTo(10))
                .body("Type", equalTo("STATEFUL"))
                .body("LastModifiedTime", notNullValue());

        String policyArn = nfw("CreateFirewallPolicy", """
                {
                  "FirewallPolicyName": "%s",
                  "FirewallPolicy": {
                    "StatelessDefaultActions": ["aws:forward_to_sfe"],
                    "StatelessFragmentDefaultActions": ["aws:forward_to_sfe"],
                    "StatefulRuleGroupReferences": [{"ResourceArn": "%s"}]
                  }
                }
                """.formatted(policyName, groupArn))
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"))
                .extract().path("FirewallPolicyResponse.FirewallPolicyArn");

        nfw("DescribeFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"))
                .body("FirewallPolicy.StatelessDefaultActions[0]", equalTo("aws:forward_to_sfe"));

        nfw("DeleteFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
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

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
