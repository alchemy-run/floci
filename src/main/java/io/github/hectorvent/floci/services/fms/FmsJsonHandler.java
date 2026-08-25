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

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
