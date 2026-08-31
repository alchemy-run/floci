package io.github.hectorvent.floci.services.identitycenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for IAM Identity Center Identity Store. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSIdentityStore.} target prefix.
 */
@ApplicationScoped
public class IdentityStoreJsonHandler {

    static final String TARGET_PREFIX = "AWSIdentityStore.";

    private final IdentityCenterService service;
    private final ObjectMapper objectMapper;

    @Inject
    public IdentityStoreJsonHandler(IdentityCenterService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateGroup" -> ok(service.createGroup(body));
                case "DescribeGroup" -> ok(service.describeGroup(body));
                case "ListGroups" -> ok(service.listGroups(body));
                case "DeleteGroup" -> ok(service.deleteGroup(body));
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
