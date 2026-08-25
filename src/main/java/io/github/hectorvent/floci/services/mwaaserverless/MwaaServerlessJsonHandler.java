package io.github.hectorvent.floci.services.mwaaserverless;

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
 * JSON 1.0 handler for Amazon MWAA Serverless. Dispatched from
 * {@code AwsJsonController} under the {@code AmazonMWAAServerless.} target prefix.
 */
@ApplicationScoped
public class MwaaServerlessJsonHandler {

    static final String TARGET_PREFIX = "AmazonMWAAServerless.";

    private final MwaaServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public MwaaServerlessJsonHandler(MwaaServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateWorkflow" -> ok(service.createWorkflow(body, region));
                case "GetWorkflow" -> ok(service.getWorkflow(body));
                case "UpdateWorkflow" -> ok(service.updateWorkflow(body));
                case "DeleteWorkflow" -> ok(service.deleteWorkflow(body));
                case "ListWorkflows" -> ok(service.listWorkflows(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "ListWorkflowVersions" -> ok(service.listWorkflowVersions(body));
                case "StartWorkflowRun" -> ok(service.startWorkflowRun(body));
                case "GetWorkflowRun" -> ok(service.getWorkflowRun(body));
                case "StopWorkflowRun" -> ok(service.stopWorkflowRun(body));
                case "ListWorkflowRuns" -> ok(service.listWorkflowRuns(body));
                case "ListTaskInstances" -> ok(service.listTaskInstances(body));
                case "GetTaskInstance" -> ok(service.getTaskInstance(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response error(AwsException e) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("__type", e.jsonType());
        if (e.getMessage() != null) {
            body.put("message", e.getMessage());
        }
        Map<String, Object> extra = e.getExtendedData();
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (value instanceof String s) {
                    body.put(key, s);
                }
            });
        }
        return Response.status(e.getHttpStatus()).entity(body).build();
    }
}
