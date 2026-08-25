package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for Amazon OpenSearch Serverless. Dispatched from
 * {@code AwsJsonController} under the {@code OpenSearchServerless.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/opensearch-service/latest/APIReference/API_Operations_Amazon_OpenSearch_Serverless.html">OpenSearch Serverless API</a>
 */
@ApplicationScoped
public class OpenSearchServerlessJsonHandler {

    private static final String TARGET_PREFIX = "OpenSearchServerless.";

    private final OpenSearchServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchServerlessJsonHandler(
            OpenSearchServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "GetAccountSettings" -> ok(service.getAccountSettings(region));
                case "UpdateAccountSettings" -> ok(service.updateAccountSettings(body, region));
                case "GetPoliciesStats" -> ok(service.getPoliciesStats());
                case "BatchGetEffectiveLifecyclePolicy" ->
                        ok(service.batchGetEffectiveLifecyclePolicy(body));
                case "CreateCollectionGroup" -> ok(service.createCollectionGroup(body, region));
                case "BatchGetCollectionGroup" -> ok(service.batchGetCollectionGroup(body));
                case "ListCollectionGroups" -> ok(service.listCollectionGroups(body));
                case "UpdateCollectionGroup" -> ok(service.updateCollectionGroup(body));
                case "DeleteCollectionGroup" -> ok(service.deleteCollectionGroup(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
