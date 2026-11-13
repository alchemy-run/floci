package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** The Bedrock Data Automation runtime, JSON 1.1 under the {@code AmazonBedrockKeystoneRuntimeService.} prefix. */
@ApplicationScoped
public class BdaRuntimeJsonHandler {
    private final BdaRuntime runtime;

    @Inject
    public BdaRuntimeJsonHandler(BdaRuntime runtime) {
        this.runtime = runtime;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            Object body = switch (action) {
                case "InvokeDataAutomationAsync" -> runtime.invokeAsync(request, region);
                case "InvokeDataAutomation" -> runtime.invoke(request, region);
                case "GetDataAutomationStatus" -> runtime.status(request, region);
                case "ListTagsForResource" -> runtime.listTags(request, region);
                case "TagResource" -> runtime.tag(request, region);
                case "UntagResource" -> runtime.untag(request, region);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            // The runtime model gives its errors no HTTP status, so every client error answers 400.
            return JsonErrorResponseUtils.createErrorResponse(e.getHttpStatus() < 500 && e.getHttpStatus() != 400
                    ? new AwsException(e.getErrorCode(), e.getMessage(), 400) : e);
        }
    }
}
