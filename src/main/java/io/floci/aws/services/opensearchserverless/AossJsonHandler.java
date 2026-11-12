package io.floci.aws.services.opensearchserverless;

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

/** JSON 1.0 dispatch for Amazon OpenSearch Serverless ({@code X-Amz-Target: OpenSearchServerless.<Action>}). */
@ApplicationScoped
public class AossJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/opensearchserverless-request-shapes.json", "OpenSearch Serverless request shapes",
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

    private final AossService service;

    @Inject
    public AossJsonHandler(AossService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return AossStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "BatchGetCollection" -> service.batchGetCollection(request, region);
            case "BatchGetCollectionGroup" -> service.batchGetCollectionGroup(request, region);
            case "BatchGetEffectiveLifecyclePolicy" -> service.batchGetEffectiveLifecyclePolicy(request, region);
            case "BatchGetLifecyclePolicy" -> service.batchGetLifecyclePolicy(request, region);
            case "BatchGetVpcEndpoint" -> service.batchGetVpcEndpoint(request, region);
            case "CancelIngestionJob" -> service.cancelIngestionJob(request, region);
            case "CreateAccessPolicy" -> service.createAccessPolicy(request, region);
            case "CreateCollection" -> service.createCollection(request, region);
            case "CreateCollectionGroup" -> service.createCollectionGroup(request, region);
            case "CreateIndex" -> service.createIndex(request, region);
            case "CreateIngestionJob" -> service.createIngestionJob(request, region);
            case "CreateIngestionStream" -> service.createIngestionStream(request, region);
            case "CreateLifecyclePolicy" -> service.createLifecyclePolicy(request, region);
            case "CreateSecurityConfig" -> service.createSecurityConfig(request, region);
            case "CreateSecurityPolicy" -> service.createSecurityPolicy(request, region);
            case "CreateVpcEndpoint" -> service.createVpcEndpoint(request, region);
            case "DeleteAccessPolicy" -> service.deleteAccessPolicy(request, region);
            case "DeleteCollection" -> service.deleteCollection(request, region);
            case "DeleteCollectionGroup" -> service.deleteCollectionGroup(request, region);
            case "DeleteIndex" -> service.deleteIndex(request, region);
            case "DeleteIngestionStream" -> service.deleteIngestionStream(request, region);
            case "DeleteLifecyclePolicy" -> service.deleteLifecyclePolicy(request, region);
            case "DeleteSecurityConfig" -> service.deleteSecurityConfig(request, region);
            case "DeleteSecurityPolicy" -> service.deleteSecurityPolicy(request, region);
            case "DeleteVpcEndpoint" -> service.deleteVpcEndpoint(request, region);
            case "GetAccessPolicy" -> service.getAccessPolicy(request, region);
            case "GetAccountSettings" -> service.getAccountSettings(request, region);
            case "GetIndex" -> service.getIndex(request, region);
            case "GetIngestionJob" -> service.getIngestionJob(request, region);
            case "GetIngestionStream" -> service.getIngestionStream(request, region);
            case "GetPoliciesStats" -> service.getPoliciesStats(request, region);
            case "GetSecurityConfig" -> service.getSecurityConfig(request, region);
            case "GetSecurityPolicy" -> service.getSecurityPolicy(request, region);
            case "ListAccessPolicies" -> service.listAccessPolicies(request, region);
            case "ListCollectionGroups" -> service.listCollectionGroups(request, region);
            case "ListCollections" -> service.listCollections(request, region);
            case "ListIngestionJobs" -> service.listIngestionJobs(request, region);
            case "ListIngestionStreams" -> service.listIngestionStreams(request, region);
            case "ListLifecyclePolicies" -> service.listLifecyclePolicies(request, region);
            case "ListSecurityConfigs" -> service.listSecurityConfigs(request, region);
            case "ListSecurityPolicies" -> service.listSecurityPolicies(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "ListVpcEndpoints" -> service.listVpcEndpoints(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateAccessPolicy" -> service.updateAccessPolicy(request, region);
            case "UpdateAccountSettings" -> service.updateAccountSettings(request, region);
            case "UpdateCollection" -> service.updateCollection(request, region);
            case "UpdateCollectionGroup" -> service.updateCollectionGroup(request, region);
            case "UpdateIndex" -> service.updateIndex(request, region);
            case "UpdateLifecyclePolicy" -> service.updateLifecyclePolicy(request, region);
            case "UpdateSecurityConfig" -> service.updateSecurityConfig(request, region);
            case "UpdateSecurityPolicy" -> service.updateSecurityPolicy(request, region);
            case "UpdateVpcEndpoint" -> service.updateVpcEndpoint(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AmazonMWAAServerless." + action)).build();
        }
        return Response.ok(result).build();
    }
}
