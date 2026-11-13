package io.floci.aws.services.airflowserverless;

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

/** JSON 1.0 dispatch for Amazon MWAA Serverless ({@code X-Amz-Target: AmazonMWAAServerless.<Action>}). */
@ApplicationScoped
public class AirflowServerlessJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mwaa-serverless-request-shapes.json", "MWAA Serverless request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
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

    private final AirflowServerlessService service;

    @Inject
    public AirflowServerlessJsonHandler(AirflowServerlessService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return AirflowServerlessStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateWorkflow" -> service.createWorkflow(request, region);
            case "DeleteWorkflow" -> service.deleteWorkflow(request, region);
            case "GetTaskInstance" -> service.getTaskInstance(request, region);
            case "GetWorkflow" -> service.getWorkflow(request, region);
            case "GetWorkflowRun" -> service.getWorkflowRun(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "ListTaskInstances" -> service.listTaskInstances(request, region);
            case "ListWorkflowRuns" -> service.listWorkflowRuns(request, region);
            case "ListWorkflowVersions" -> service.listWorkflowVersions(request, region);
            case "ListWorkflows" -> service.listWorkflows(request, region);
            case "StartWorkflowRun" -> service.startWorkflowRun(request, region);
            case "StopWorkflowRun" -> service.stopWorkflowRun(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateWorkflow" -> service.updateWorkflow(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AmazonMWAAServerless." + action)).build();
        }
        return Response.ok(result).build();
    }
}
