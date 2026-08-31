package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for AWS Payment Cryptography control plane. Dispatched from
 * {@code AwsJsonController} under the {@code PaymentCryptographyControlPlane.} target prefix.
 */
@ApplicationScoped
public class PaymentCryptographyJsonHandler {

    static final String TARGET_PREFIX = "PaymentCryptographyControlPlane.";

    private final PaymentCryptographyService service;
    private final ObjectMapper objectMapper;

    @Inject
    public PaymentCryptographyJsonHandler(PaymentCryptographyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateKey" -> ok(service.createKey(body, region));
                case "GetKey" -> ok(service.getKey(body));
                case "ListKeys" -> ok(service.listKeys(body));
                case "DeleteKey" -> ok(service.deleteKey(body));
                case "RestoreKey" -> ok(service.restoreKey(body));
                case "StartKeyUsage" -> ok(service.startKeyUsage(body));
                case "StopKeyUsage" -> ok(service.stopKeyUsage(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "GetPublicKeyCertificate" -> ok(service.getPublicKeyCertificate(body));
                case "CreateAlias" -> ok(service.createAlias(body, region));
                case "GetAlias" -> ok(service.getAlias(body));
                case "UpdateAlias" -> ok(service.updateAlias(body));
                case "DeleteAlias" -> ok(service.deleteAlias(body));
                case "ListAliases" -> ok(service.listAliases(body));
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
