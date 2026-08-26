package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for AWS Organizations. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSOrganizationsV20161128.} target prefix.
 */
@ApplicationScoped
public class OrganizationsJsonHandler {

    static final String TARGET_PREFIX = OrganizationsService.TARGET_PREFIX;

    private final OrganizationsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public OrganizationsJsonHandler(OrganizationsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "ListAccounts" -> ok(service.listAccounts(body));
                case "DescribeAccount" -> ok(service.describeAccount(body));
                case "ListParents" -> ok(service.listParents(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "CreateAccount" -> ok(service.createAccount(body));
                case "DescribeCreateAccountStatus" -> ok(service.describeCreateAccountStatus(body));
                case "ListCreateAccountStatus" -> ok(service.listCreateAccountStatus(body));
                case "RemoveAccountFromOrganization" -> ok(service.removeAccountFromOrganization(body));
                case "DescribeOrganization" -> ok(service.describeOrganization());
                case "CreateOrganization" -> ok(service.createOrganization(body));
                case "DeleteOrganization" -> ok(service.deleteOrganization());
                case "EnableAllFeatures" -> ok(service.enableAllFeatures());
                case "EnableAWSServiceAccess" -> ok(service.enableAWSServiceAccess(body));
                case "DisableAWSServiceAccess" -> ok(service.disableAWSServiceAccess(body));
                case "ListAWSServiceAccessForOrganization" -> ok(service.listAWSServiceAccessForOrganization(body));
                case "ListRoots" -> ok(service.listRoots(body));
                case "MoveAccount" -> ok(service.moveAccount(body));
                case "ListOrganizationalUnitsForParent" -> ok(service.listOrganizationalUnitsForParent(body));
                case "DescribeOrganizationalUnit" -> ok(service.describeOrganizationalUnit(body));
                case "CreateOrganizationalUnit" -> ok(service.createOrganizationalUnit(body));
                case "UpdateOrganizationalUnit" -> ok(service.updateOrganizationalUnit(body));
                case "DeleteOrganizationalUnit" -> ok(service.deleteOrganizationalUnit(body));
                case "ListDelegatedAdministrators" -> ok(service.listDelegatedAdministrators(body));
                case "ListDelegatedServicesForAccount" -> ok(service.listDelegatedServicesForAccount(body));
                case "RegisterDelegatedAdministrator" -> ok(service.registerDelegatedAdministrator(body));
                case "DeregisterDelegatedAdministrator" -> ok(service.deregisterDelegatedAdministrator(body));
                case "DescribeResourcePolicy" -> ok(service.describeResourcePolicy());
                case "PutResourcePolicy" -> ok(service.putResourcePolicy(body));
                case "DeleteResourcePolicy" -> {
                    service.deleteResourcePolicy();
                    yield ok(objectMapper.createObjectNode());
                }
                case "ListPolicies" -> ok(service.listPolicies(body));
                case "DescribePolicy" -> ok(service.describePolicy(body));
                case "CreatePolicy" -> ok(service.createPolicy(body));
                case "UpdatePolicy" -> ok(service.updatePolicy(body));
                case "DeletePolicy" -> ok(service.deletePolicy(body));
                case "ListTargetsForPolicy" -> ok(service.listTargetsForPolicy(body));
                case "AttachPolicy" -> {
                    service.attachPolicy(body);
                    yield ok(objectMapper.createObjectNode());
                }
                case "DetachPolicy" -> {
                    service.detachPolicy(body);
                    yield ok(objectMapper.createObjectNode());
                }
                case "ListAccountsForParent" -> ok(service.listAccountsForParent(body));
                case "ListChildren" -> ok(service.listChildren(body));
                case "ListPoliciesForTarget" -> ok(service.listPoliciesForTarget(body));
                case "DescribeEffectivePolicy" -> ok(service.describeEffectivePolicy(body));
                case "ListAccountsWithInvalidEffectivePolicy" ->
                        ok(service.listAccountsWithInvalidEffectivePolicy(body));
                case "ListEffectivePolicyValidationErrors" ->
                        ok(service.listEffectivePolicyValidationErrors(body));
                case "ListHandshakesForAccount" -> ok(service.listHandshakesForAccount(body));
                case "ListHandshakesForOrganization" -> ok(service.listHandshakesForOrganization(body));
                case "DescribeHandshake" -> ok(service.describeHandshake(body));
                case "AcceptHandshake" -> ok(service.acceptHandshake(body));
                case "DeclineHandshake" -> ok(service.declineHandshake(body));
                case "CancelHandshake" -> ok(service.cancelHandshake(body));
                case "InviteAccountToOrganization" -> ok(service.inviteAccountToOrganization(body));
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
