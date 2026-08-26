package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.1 handler for Amazon SageMaker. Dispatched from {@code AwsJson11Controller}
 * under the {@code SageMaker.} target prefix.
 */
@ApplicationScoped
public class SageMakerJsonHandler {

    private final SageMakerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SageMakerJsonHandler(SageMakerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateCluster" -> ok(service.createCluster(body, region));
                case "DescribeCluster" -> ok(service.describeCluster(body, region));
                case "UpdateCluster" -> ok(service.updateCluster(body, region));
                case "DeleteCluster" -> ok(service.deleteCluster(body, region));
                case "ListClusters" -> ok(service.listClusters(body, region));
                case "ListClusterNodes" -> ok(service.listClusterNodes(body, region));
                case "CreateClusterSchedulerConfig" -> ok(service.createClusterSchedulerConfig(body, region));
                case "DescribeClusterSchedulerConfig" -> ok(service.describeClusterSchedulerConfig(body, region));
                case "UpdateClusterSchedulerConfig" -> ok(service.updateClusterSchedulerConfig(body, region));
                case "DeleteClusterSchedulerConfig" -> ok(service.deleteClusterSchedulerConfig(body, region));
                case "ListClusterSchedulerConfigs" -> ok(service.listClusterSchedulerConfigs(body, region));
                case "CreateModel" -> ok(service.createModel(body, region));
                case "DescribeModel" -> ok(service.describeModel(body, region));
                case "DeleteModel" -> ok(service.deleteModel(body, region));
                case "ListModels" -> ok(service.listModels(body, region));
                case "CreateEndpointConfig" -> ok(service.createEndpointConfig(body, region));
                case "DescribeEndpointConfig" -> ok(service.describeEndpointConfig(body, region));
                case "DeleteEndpointConfig" -> ok(service.deleteEndpointConfig(body, region));
                case "ListEndpointConfigs" -> ok(service.listEndpointConfigs(body, region));
                case "CreateEndpoint" -> ok(service.createEndpoint(body, region));
                case "DescribeEndpoint" -> ok(service.describeEndpoint(body, region));
                case "UpdateEndpoint" -> ok(service.updateEndpoint(body, region));
                case "UpdateEndpointWeightsAndCapacities" ->
                        ok(service.updateEndpointWeightsAndCapacities(body, region));
                case "DeleteEndpoint" -> ok(service.deleteEndpoint(body, region));
                case "ListEndpoints" -> ok(service.listEndpoints(body, region));
                case "CreateFeatureGroup" -> ok(service.createFeatureGroup(body, region));
                case "DescribeFeatureGroup" -> ok(service.describeFeatureGroup(body, region));
                case "DeleteFeatureGroup" -> ok(service.deleteFeatureGroup(body, region));
                case "ListFeatureGroups" -> ok(service.listFeatureGroups(body, region));
                case "CreateComputeQuota" -> ok(service.createComputeQuota(body, region));
                case "DescribeComputeQuota" -> ok(service.describeComputeQuota(body, region));
                case "UpdateComputeQuota" -> ok(service.updateComputeQuota(body, region));
                case "DeleteComputeQuota" -> ok(service.deleteComputeQuota(body, region));
                case "ListComputeQuotas" -> ok(service.listComputeQuotas(body, region));
                case "AddTags" -> ok(service.addTags(body, region));
                case "DeleteTags" -> ok(service.deleteTags(body, region));
                case "ListTags" -> ok(service.listTags(body, region));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("SageMaker." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
