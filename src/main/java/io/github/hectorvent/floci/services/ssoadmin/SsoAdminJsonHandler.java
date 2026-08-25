package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for IAM Identity Center (SSO Admin). Dispatched from
 * {@code AwsJson11Controller} under the {@code SWBExternalService.} target prefix.
 */
@ApplicationScoped
public class SsoAdminJsonHandler {

    static final String TARGET_PREFIX = "SWBExternalService.";

    private final SsoAdminService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SsoAdminJsonHandler(SsoAdminService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateInstance" -> ok(service.createInstance(body, region));
                case "DeleteInstance" -> ok(service.deleteInstance(body));
                case "ListInstances" -> ok(service.listInstances());
                case "DescribeInstance" -> ok(service.describeInstance(body));
                case "CreatePermissionSet" -> ok(service.createPermissionSet(body));
                case "DescribePermissionSet" -> ok(service.describePermissionSet(body));
                case "UpdatePermissionSet" -> ok(service.updatePermissionSet(body));
                case "DeletePermissionSet" -> ok(service.deletePermissionSet(body));
                case "ListPermissionSets" -> ok(service.listPermissionSets(body));
                case "CreateAccountAssignment" -> ok(service.createAccountAssignment(body));
                case "DeleteAccountAssignment" -> ok(service.deleteAccountAssignment(body));
                case "DescribeAccountAssignmentCreationStatus" ->
                        ok(service.describeAccountAssignmentCreationStatus(body));
                case "DescribeAccountAssignmentDeletionStatus" ->
                        ok(service.describeAccountAssignmentDeletionStatus(body));
                case "ListAccountAssignments" -> ok(service.listAccountAssignments(body));
                case "ListAccountAssignmentsForPrincipal" -> ok(service.listAccountAssignmentsForPrincipal(body));
                case "ListAccountsForProvisionedPermissionSet" ->
                        ok(service.listAccountsForProvisionedPermissionSet(body));
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
