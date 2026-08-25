package io.github.hectorvent.floci.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.bedrockdataautomation.model.InvocationRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Bedrock Data Automation Runtime.
 * Dispatches {@code X-Amz-Target: AmazonBedrockKeystoneRuntimeService.*}.
 */
@ApplicationScoped
public class BedrockDataAutomationRuntimeJsonHandler {

    private static final Logger LOG = Logger.getLogger(BedrockDataAutomationRuntimeJsonHandler.class);

    private final BedrockDataAutomationService service;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockDataAutomationRuntimeJsonHandler(
            BedrockDataAutomationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("BedrockDataAutomationRuntime action: {0}", action);
        return switch (action) {
            case "InvokeDataAutomationAsync" -> handleInvokeAsync(request, region);
            case "GetDataAutomationStatus" -> handleGetStatus(request);
            case "InvokeDataAutomation" -> handleInvokeSync(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AmazonBedrockKeystoneRuntimeService." + action))
                    .build();
        };
    }

    private Response handleInvokeAsync(JsonNode request, String region) {
        InvocationRecord invocation = service.invokeAsync(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("invocationArn", invocation.getInvocationArn());
        return Response.ok(response).build();
    }

    private Response handleGetStatus(JsonNode request) {
        String invocationArn = request.path("invocationArn").asText(null);
        InvocationRecord invocation = service.getInvocation(invocationArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", invocation.getStatus());
        if (invocation.getOutputS3Uri() != null) {
            response.putObject("outputConfiguration").put("s3Uri", invocation.getOutputS3Uri());
        }
        if (invocation.getJobSubmissionTime() != null) {
            response.put("jobSubmissionTime", invocation.getJobSubmissionTime());
        }
        if (invocation.getJobCompletionTime() != null) {
            response.put("jobCompletionTime", invocation.getJobCompletionTime());
        }
        return Response.ok(response).build();
    }

    private Response handleInvokeSync(JsonNode request) {
        service.validateSyncInvoke(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("semanticModality", "DOCUMENT");
        return Response.ok(response).build();
    }
}
