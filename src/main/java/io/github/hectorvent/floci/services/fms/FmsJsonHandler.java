package io.github.hectorvent.floci.services.fms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.fms.model.FmsAdminAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for AWS Firewall Manager. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSFMS_20180101.} target prefix.
 */
@ApplicationScoped
public class FmsJsonHandler {

    private final FmsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public FmsJsonHandler(FmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetAdminAccount" -> getAdminAccount(region);
                case "AssociateAdminAccount" -> {
                    service.associateAdminAccount(region, body);
                    yield ok();
                }
                case "DisassociateAdminAccount" -> {
                    service.disassociateAdminAccount(region);
                    yield ok();
                }
                case "ListAdminsManagingAccount" -> listAdminsManagingAccount();
                case "ListAdminAccountsForOrganization" -> listAdminAccountsForOrganization();
                case "ListPolicies" -> listPolicies();
                case "ListResourceSets" -> listResourceSets();
                case "ListMemberAccounts" -> listMemberAccounts();
                case "GetNotificationChannel" -> getNotificationChannel();
                case "ListAppsLists" -> listAppsLists();
                case "ListProtocolsLists" -> listProtocolsLists();
                case "GetThirdPartyFirewallAssociationStatus" ->
                        getThirdPartyFirewallAssociationStatus(body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        "AWSFMS_20180101." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response getAdminAccount(String region) {
        FmsAdminAccount admin = service.getAdminAccount(region);
        ObjectNode response = objectMapper.createObjectNode();
        if (admin.getAdminAccount() != null) {
            response.put("AdminAccount", admin.getAdminAccount());
        }
        if (admin.getRoleStatus() != null) {
            response.put("RoleStatus", admin.getRoleStatus());
        }
        return Response.ok(response).build();
    }

    private Response listAdminsManagingAccount() {
        FmsAdminAccount admin = service.requireManagingAdmin();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("AdminAccounts").add(admin.getAdminAccount());
        return Response.ok(response).build();
    }

    private Response listAdminAccountsForOrganization() {
        FmsAdminAccount admin = service.requireOrganizationAdmin();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putArray("AdminAccounts").addObject();
        summary.put("AdminAccount", admin.getAdminAccount());
        summary.put("DefaultAdmin", true);
        summary.put("Status", "ONBOARDING_COMPLETE");
        return Response.ok(response).build();
    }

    private Response listPolicies() {
        service.requireAdmin();
        return emptyList("PolicyList");
    }

    private Response listResourceSets() {
        service.requireAdmin();
        return emptyList("ResourceSets");
    }

    private Response listMemberAccounts() {
        FmsAdminAccount admin = service.requireAdmin();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("MemberAccounts").add(admin.getAdminAccount());
        return Response.ok(response).build();
    }

    private Response getNotificationChannel() {
        service.requireAdmin();
        throw new AwsException(
                "ResourceNotFoundException",
                "The referenced item does not exist.",
                400);
    }

    private Response listAppsLists() {
        service.requireAdmin();
        return emptyList("AppsLists");
    }

    private Response listProtocolsLists() {
        service.requireAdmin();
        return emptyList("ProtocolsLists");
    }

    private Response getThirdPartyFirewallAssociationStatus(JsonNode request) {
        service.requireThirdPartyFirewall(request);
        service.requireAdmin();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ThirdPartyFirewallStatus", "NOT_EXIST");
        response.put("MarketplaceOnboardingStatus", "NO_SUBSCRIPTION");
        return Response.ok(response).build();
    }

    private Response emptyList(String field) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(field);
        return Response.ok(response).build();
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
