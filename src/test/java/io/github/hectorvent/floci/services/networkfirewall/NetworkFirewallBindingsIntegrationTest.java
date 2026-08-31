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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy {@code FirewallBindings.test.ts}: firewalls
 * become READY immediately, flow capture/flush complete on start, and the
 * analysis-report interface returns typed rejections when analysis types are
 * not enabled.
 */
@QuarkusTest
class NetworkFirewallBindingsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/network-firewall/aws4_request";
    private static final String TARGET = "NetworkFirewall_20201112.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFirewall_missing_returnsResourceNotFound() {
        nfw("DescribeFirewall", "{\"FirewallName\":\"missing-firewall\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void firewallBindings_flowAndAnalysisRoundTrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String policyName = "floci-nfw-policy-" + suffix;
        String firewallName = "floci-nfw-fw-" + suffix;

        nfw("CreateFirewallPolicy", """
                {
                  "FirewallPolicyName": "%s",
                  "FirewallPolicy": {
                    "StatelessDefaultActions": ["aws:pass"],
                    "StatelessFragmentDefaultActions": ["aws:pass"]
                  },
                  "Tags": [{"Key": "fixture", "Value": "nfw-bindings"}]
                }
                """.formatted(policyName))
                .then()
                .statusCode(200)
                .body("FirewallPolicyResponse.FirewallPolicyName", equalTo(policyName))
                .body("FirewallPolicyResponse.FirewallPolicyStatus", equalTo("ACTIVE"));

        String policyArn = nfw("DescribeFirewallPolicy",
                "{\"FirewallPolicyName\":\"" + policyName + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallPolicy.StatelessDefaultActions[0]", equalTo("aws:pass"))
                .extract().path("FirewallPolicyResponse.FirewallPolicyArn");

        nfw("CreateFirewall", """
                {
                  "FirewallName": "%s",
                  "FirewallPolicyArn": "%s",
                  "VpcId": "vpc-nfwbindings",
                  "SubnetMappings": [{"SubnetId": "subnet-nfwbindings"}],
                  "Tags": [{"Key": "fixture", "Value": "nfw-bindings"}]
                }
                """.formatted(firewallName, policyArn))
                .then()
                .statusCode(200)
                .body("FirewallStatus.Status", equalTo("READY"))
                .body("FirewallStatus.SyncStates.size()", greaterThanOrEqualTo(1));

        String firewallArn = nfw("DescribeFirewall",
                "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(200)
                .body("FirewallStatus.Status", equalTo("READY"))
                .extract().path("Firewall.FirewallArn");

        String flowOperationId = nfw("StartFlowCapture", """
                {
                  "FirewallArn": "%s",
                  "FlowFilters": [{"SourceAddress": {"AddressDefinition": "10.78.1.10/32"}}]
                }
                """.formatted(firewallArn))
                .then()
                .statusCode(200)
                .body("FlowOperationStatus", equalTo("COMPLETED"))
                .body("FlowOperationId", notNullValue())
                .extract().path("FlowOperationId");

        nfw("DescribeFlowOperation", """
                {
                  "FirewallArn": "%s",
                  "FlowOperationId": "%s"
                }
                """.formatted(firewallArn, flowOperationId))
                .then()
                .statusCode(200)
                .body("FlowOperationStatus", equalTo("COMPLETED"))
                .body("FlowOperationType", equalTo("FLOW_CAPTURE"));

        nfw("ListFlowOperations", """
                {
                  "FirewallArn": "%s",
                  "FlowOperationType": "FLOW_CAPTURE"
                }
                """.formatted(firewallArn))
                .then()
                .statusCode(200)
                .body("FlowOperations", hasSize(greaterThanOrEqualTo(1)));

        nfw("ListFlowOperationResults", """
                {
                  "FirewallArn": "%s",
                  "FlowOperationId": "%s"
                }
                """.formatted(firewallArn, flowOperationId))
                .then()
                .statusCode(200)
                .body("Flows", hasSize(greaterThanOrEqualTo(0)));

        nfw("StartFlowFlush", """
                {
                  "FirewallArn": "%s",
                  "FlowFilters": [{"SourceAddress": {"AddressDefinition": "10.78.1.10/32"}}]
                }
                """.formatted(firewallArn))
                .then()
                .statusCode(200)
                .body("FlowOperationStatus", equalTo("COMPLETED"));

        nfw("StartAnalysisReport", """
                {
                  "FirewallArn": "%s",
                  "AnalysisType": "TLS_SNI"
                }
                """.formatted(firewallArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        nfw("ListAnalysisReports", "{\"FirewallArn\":\"" + firewallArn + "\"}")
                .then()
                .statusCode(200)
                .body("AnalysisReports", hasSize(0));

        nfw("GetAnalysisReportResults", """
                {
                  "FirewallArn": "%s",
                  "AnalysisReportId": "alchemy-nonexistent-analysis-report-id"
                }
                """.formatted(firewallArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        nfw("DeleteFirewall", "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(200);
        nfw("DescribeFirewall", "{\"FirewallName\":\"" + firewallName + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        nfw("DeleteFirewallPolicy", "{\"FirewallPolicyName\":\"" + policyName + "\"}")
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
