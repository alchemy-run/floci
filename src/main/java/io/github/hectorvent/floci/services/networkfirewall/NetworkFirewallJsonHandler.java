package io.github.hectorvent.floci.services.networkfirewall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for AWS Network Firewall. Dispatched from
 * {@code AwsJsonController} under the {@code NetworkFirewall_20201112.} target prefix.
 */
@ApplicationScoped
public class NetworkFirewallJsonHandler {

    static final String TARGET_PREFIX = NetworkFirewallService.TARGET_PREFIX;

    private final NetworkFirewallService service;
    private final ObjectMapper objectMapper;

    @Inject
    public NetworkFirewallJsonHandler(NetworkFirewallService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateRuleGroup" -> ok(service.createRuleGroup(body, region));
                case "DescribeRuleGroup" -> ok(service.describeRuleGroup(body, region));
                case "DescribeRuleGroupMetadata" -> ok(service.describeRuleGroupMetadata(body, region));
                case "DescribeRuleGroupSummary" -> ok(service.describeRuleGroupSummary(body, region));
                case "UpdateRuleGroup" -> ok(service.updateRuleGroup(body, region));
                case "DeleteRuleGroup" -> ok(service.deleteRuleGroup(body, region));
                case "ListRuleGroups" -> ok(service.listRuleGroups(body, region));
                case "CreateFirewallPolicy" -> ok(service.createFirewallPolicy(body, region));
                case "DescribeFirewallPolicy" -> ok(service.describeFirewallPolicy(body, region));
                case "UpdateFirewallPolicy" -> ok(service.updateFirewallPolicy(body, region));
                case "DeleteFirewallPolicy" -> ok(service.deleteFirewallPolicy(body, region));
                case "ListFirewallPolicies" -> ok(service.listFirewallPolicies(body, region));
                case "CreateFirewall" -> ok(service.createFirewall(body, region));
                case "DescribeFirewall" -> ok(service.describeFirewall(body, region));
                case "ListFirewalls" -> ok(service.listFirewalls(body, region));
                case "DeleteFirewall" -> ok(service.deleteFirewall(body, region));
                case "AssociateFirewallPolicy" -> ok(service.associateFirewallPolicy(body, region));
                case "AssociateSubnets" -> ok(service.associateSubnets(body, region));
                case "DisassociateSubnets" -> ok(service.disassociateSubnets(body, region));
                case "UpdateFirewallDescription" -> ok(service.updateFirewallDescription(body, region));
                case "UpdateFirewallDeleteProtection" -> ok(service.updateFirewallDeleteProtection(body, region));
                case "UpdateSubnetChangeProtection" -> ok(service.updateSubnetChangeProtection(body, region));
                case "UpdateFirewallPolicyChangeProtection" ->
                        ok(service.updateFirewallPolicyChangeProtection(body, region));
                case "DescribeLoggingConfiguration" -> ok(service.describeLoggingConfiguration(body, region));
                case "UpdateLoggingConfiguration" -> ok(service.updateLoggingConfiguration(body, region));
                case "StartFlowCapture" -> ok(service.startFlowCapture(body, region));
                case "StartFlowFlush" -> ok(service.startFlowFlush(body, region));
                case "DescribeFlowOperation" -> ok(service.describeFlowOperation(body, region));
                case "ListFlowOperations" -> ok(service.listFlowOperations(body, region));
                case "ListFlowOperationResults" -> ok(service.listFlowOperationResults(body, region));
                case "StartAnalysisReport" -> ok(service.startAnalysisReport(body, region));
                case "ListAnalysisReports" -> ok(service.listAnalysisReports(body, region));
                case "GetAnalysisReportResults" -> ok(service.getAnalysisReportResults(body, region));
                case "TagResource" -> ok(service.tagResource(body, region));
                case "UntagResource" -> ok(service.untagResource(body, region));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body, region));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
