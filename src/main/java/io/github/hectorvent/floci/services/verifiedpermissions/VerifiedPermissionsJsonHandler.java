package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JSON 1.0 handler for Amazon Verified Permissions. Dispatched from
 * {@code AwsJsonController} under the {@code VerifiedPermissions.} target prefix.
 */
@ApplicationScoped
public class VerifiedPermissionsJsonHandler {

    private static final String TARGET_PREFIX = "VerifiedPermissions.";

    private final VerifiedPermissionsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public VerifiedPermissionsJsonHandler(VerifiedPermissionsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreatePolicyStore" -> ok(service.createPolicyStore(body, region));
                case "GetPolicyStore" -> ok(service.getPolicyStore(body));
                case "UpdatePolicyStore" -> ok(service.updatePolicyStore(body));
                case "DeletePolicyStore" -> ok(service.deletePolicyStore(body));
                case "ListPolicyStores" -> ok(service.listPolicyStores(body));
                case "CreatePolicyStoreAlias" -> ok(service.createPolicyStoreAlias(body));
                case "PutSchema" -> ok(service.putSchema(body));
                case "GetSchema" -> ok(service.getSchema(body));
                case "CreatePolicyTemplate" -> ok(service.createPolicyTemplate(body));
                case "GetPolicyTemplate" -> ok(service.getPolicyTemplate(body));
                case "UpdatePolicyTemplate" -> ok(service.updatePolicyTemplate(body));
                case "DeletePolicyTemplate" -> ok(service.deletePolicyTemplate(body));
                case "ListPolicyTemplates" -> ok(service.listPolicyTemplates(body));
                case "CreateIdentitySource" -> ok(service.createIdentitySource(body));
                case "GetIdentitySource" -> ok(service.getIdentitySource(body));
                case "UpdateIdentitySource" -> ok(service.updateIdentitySource(body));
                case "DeleteIdentitySource" -> ok(service.deleteIdentitySource(body));
                case "CreatePolicy" -> ok(service.createPolicy(body));
                case "GetPolicy" -> ok(service.getPolicy(body));
                case "UpdatePolicy" -> ok(service.updatePolicy(body));
                case "DeletePolicy" -> ok(service.deletePolicy(body));
                case "BatchGetPolicy" -> ok(service.batchGetPolicy(body));
                case "IsAuthorized" -> ok(service.isAuthorized(body));
                case "BatchIsAuthorized" -> ok(service.batchIsAuthorized(body));
                case "BatchIsAuthorizedWithToken" -> ok(service.batchIsAuthorizedWithToken(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException exception) {
        Map<String, Object> extra = exception.getExtendedData();
        if (extra == null || extra.isEmpty()) {
            return JsonErrorResponseUtils.createErrorResponse(exception);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        extra.forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
        return Response.status(exception.getHttpStatus()).entity(node).build();
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
