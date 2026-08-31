package io.github.hectorvent.floci.services.directoryservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for AWS Directory Service. Dispatched from {@code AwsJson11Controller}
 * under the {@code DirectoryService_20150416.} target prefix.
 */
@ApplicationScoped
public class DirectoryServiceJsonHandler {

    static final String TARGET_PREFIX = "DirectoryService_20150416.";

    private final DirectoryService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DirectoryServiceJsonHandler(DirectoryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetDirectoryLimits" -> ok(service.getDirectoryLimits());
                case "CreateDirectory" -> ok(service.createDirectory(body, region));
                case "CreateMicrosoftAD" -> ok(service.createMicrosoftAD(body, region));
                case "DescribeDirectories" -> ok(service.describeDirectories(body, region));
                case "DeleteDirectory" -> ok(service.deleteDirectory(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "AddTagsToResource" -> ok(service.addTagsToResource(body));
                case "RemoveTagsFromResource" -> ok(service.removeTagsFromResource(body));
                case "DescribeEventTopics" -> ok(service.describeEventTopics(body));
                case "RegisterEventTopic" -> ok(service.registerEventTopic(body, region));
                case "DeregisterEventTopic" -> ok(service.deregisterEventTopic(body));
                case "DescribeConditionalForwarders" -> ok(service.describeConditionalForwarders(body));
                case "CreateConditionalForwarder" -> ok(service.createConditionalForwarder(body));
                case "UpdateConditionalForwarder" -> ok(service.updateConditionalForwarder(body));
                case "DeleteConditionalForwarder" -> ok(service.deleteConditionalForwarder(body));
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
