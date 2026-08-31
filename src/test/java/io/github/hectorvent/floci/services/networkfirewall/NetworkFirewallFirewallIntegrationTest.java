package io.github.hectorvent.floci.services.networkfirewall;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 Network Firewall coverage used by Alchemy Firewall / LoggingConfiguration:
 * typed not-found, create/describe READY with vpce endpoints, logging, tags, delete.
 */
@QuarkusTest
class NetworkFirewallFirewallIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/network-firewall/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFirewall_missingName_returnsResourceNotFoundException() {
        nfw("DescribeFirewall", "{\"FirewallName\":\"alchemy-nonexistent-nfw-firewall-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeLoggingConfiguration_missingName_returnsResourceNotFoundException() {
        nfw("DescribeLoggingConfiguration",
                "{\"FirewallName\":\"alchemy-nonexistent-nfw-firewall-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void firewall_createDescribeLoggingTagsAndDelete() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String policyName = "nfw-policy-" + suffix;
        String firewallName = "nfw-fw-" + suffix;

        String policyArn = nfw("CreateFirewallPolicy", "{"
                + "\"FirewallPolicyName\":\"" + policyName + "\","
                + "\"FirewallPolicy\":{"
                + "\"StatelessDefaultActions\":[\"aws:pass\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]"
                + "}"
                + "}")
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyName", equalTo(policyName))
                .body("FirewallPolicyResponse.FirewallPolicyArn", startsWith("arn:aws:network-firewall:"))
                .extract().path("FirewallPolicyResponse.FirewallPolicyArn");

        String firewallArn = nfw("CreateFirewall", "{"
                + "\"FirewallName\":\"" + firewallName + "\","
                + "\"FirewallPolicyArn\":\"" + policyArn + "\","
                + "\"VpcId\":\"vpc-12345678\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-12345678\"}],"
                + "\"Description\":\"alchemy network firewall test\","
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"nfw-firewall\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("Firewall.FirewallName", equalTo(firewallName))
                .body("Firewall.FirewallArn", startsWith("arn:aws:network-firewall:"))
                .body("Firewall.FirewallId", notNullValue())
                .body("FirewallStatus.Status", equalTo("READY"))
                .body("FirewallStatus.SyncStates.size()", greaterThanOrEqualTo(1))
                .extract().path("Firewall.FirewallArn");

        nfw("DescribeFirewall", "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(200)
                .body("Firewall.FirewallPolicyArn", equalTo(policyArn))
                .body("FirewallStatus.Status", equalTo("READY"));

        nfw("DescribeLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn + "\"}")
                .then()
                .statusCode(200)
                .body("LoggingConfiguration.LogDestinationConfigs", hasSize(0));

        nfw("UpdateLoggingConfiguration", "{"
                + "\"FirewallArn\":\"" + firewallArn + "\","
                + "\"LoggingConfiguration\":{\"LogDestinationConfigs\":[{"
                + "\"LogType\":\"FLOW\","
                + "\"LogDestinationType\":\"CloudWatchLogs\","
                + "\"LogDestination\":{\"logGroup\":\"/aws/network-firewall/" + firewallName + "\"}"
                + "}]}"
                + "}")
                .then()
                .statusCode(200)
                .body("LoggingConfiguration.LogDestinationConfigs[0].LogType", equalTo("FLOW"));

        nfw("DescribeLoggingConfiguration", "{\"FirewallArn\":\"" + firewallArn + "\"}")
                .then()
                .statusCode(200)
                .body("LoggingConfiguration.LogDestinationConfigs[0].LogType", equalTo("FLOW"));

        nfw("ListTagsForResource", "{\"ResourceArn\":\"" + firewallArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        nfw("DeleteFirewall", "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(200);

        nfw("DescribeFirewall", "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        nfw("DeleteFirewallPolicy", "{\"FirewallPolicyArn\":\"" + policyArn + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response nfw(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", NetworkFirewallJsonHandler.TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
