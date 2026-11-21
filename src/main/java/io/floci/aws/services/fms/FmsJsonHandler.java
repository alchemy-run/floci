package io.floci.aws.services.fms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.1 dispatch for AWS Firewall Manager ({@code X-Amz-Target: AWSFMS_20180101.<Action>}). */
@ApplicationScoped
public class FmsJsonHandler {

    /** Firewall Manager's request constraints; it reports a violation as InvalidInputException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/fms-request-shapes.json",
            "Firewall Manager request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final FmsService service;

    @Inject
    public FmsJsonHandler(FmsService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return FmsService.invalidInput("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AssociateAdminAccount" -> service.associateAdminAccount(request);
            case "DisassociateAdminAccount" -> service.disassociateAdminAccount(request);
            case "GetAdminAccount" -> service.getAdminAccount(request);
            case "PutAdminAccount" -> service.putAdminAccount(request);
            case "GetAdminScope" -> service.getAdminScope(request);
            case "ListAdminAccountsForOrganization" -> service.listAdminAccountsForOrganization(request);
            case "ListAdminsManagingAccount" -> service.listAdminsManagingAccount(request);
            case "ListMemberAccounts" -> service.listMemberAccounts(request);
            case "PutPolicy" -> service.putPolicy(request, region);
            case "GetPolicy" -> service.getPolicy(request, region);
            case "DeletePolicy" -> service.deletePolicy(request, region);
            case "ListPolicies" -> service.listPolicies(request, region);
            case "ListComplianceStatus" -> service.listComplianceStatus(request, region);
            case "GetComplianceDetail" -> service.getComplianceDetail(request, region);
            case "GetViolationDetails" -> service.getViolationDetails(request, region);
            case "GetProtectionStatus" -> service.getProtectionStatus(request, region);
            case "PutAppsList" -> service.putAppsList(request, region);
            case "GetAppsList" -> service.getAppsList(request, region);
            case "DeleteAppsList" -> service.deleteAppsList(request, region);
            case "ListAppsLists" -> service.listAppsLists(request, region);
            case "PutProtocolsList" -> service.putProtocolsList(request, region);
            case "GetProtocolsList" -> service.getProtocolsList(request, region);
            case "DeleteProtocolsList" -> service.deleteProtocolsList(request, region);
            case "ListProtocolsLists" -> service.listProtocolsLists(request, region);
            case "PutResourceSet" -> service.putResourceSet(request, region);
            case "GetResourceSet" -> service.getResourceSet(request, region);
            case "DeleteResourceSet" -> service.deleteResourceSet(request, region);
            case "ListResourceSets" -> service.listResourceSets(request, region);
            case "BatchAssociateResource" -> service.batchAssociateResource(request, region);
            case "BatchDisassociateResource" -> service.batchDisassociateResource(request, region);
            case "ListResourceSetResources" -> service.listResourceSetResources(request, region);
            case "ListDiscoveredResources" -> service.listDiscoveredResources(request, region);
            case "PutNotificationChannel" -> service.putNotificationChannel(request, region);
            case "GetNotificationChannel" -> service.getNotificationChannel(request, region);
            case "DeleteNotificationChannel" -> service.deleteNotificationChannel(request, region);
            case "AssociateThirdPartyFirewall" -> service.associateThirdPartyFirewall(request, region);
            case "DisassociateThirdPartyFirewall" -> service.disassociateThirdPartyFirewall(request, region);
            case "GetThirdPartyFirewallAssociationStatus" -> service.getThirdPartyFirewallAssociationStatus(request, region);
            case "ListThirdPartyFirewallFirewallPolicies" -> service.listThirdPartyFirewallFirewallPolicies(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AWSFMS_20180101." + action)).build();
        }
        return Response.ok(result).build();
    }
}
