package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SageMakerJsonHandler {
    private final SageMakerService service;
    private final SageMakerFeatureStoreService featureStore;
    private final SageMakerHyperPodService hyperPod;

    @Inject
    public SageMakerJsonHandler(SageMakerService service, SageMakerFeatureStoreService featureStore,
                                SageMakerHyperPodService hyperPod) {
        this.service = service;
        this.featureStore = featureStore;
        this.hyperPod = hyperPod;
    }

    public Response handle(String action, JsonNode request, String region) {
        Object entity = switch (action) {
            case "CreateModel" -> service.createModel(request, region);
            case "DescribeModel" -> service.describeModel(request, region);
            case "DeleteModel" -> service.deleteModel(request, region);
            case "ListModels" -> service.listModels(request, region);
            case "CreateEndpointConfig" -> service.createEndpointConfig(request, region);
            case "DescribeEndpointConfig" -> service.describeEndpointConfig(request, region);
            case "DeleteEndpointConfig" -> service.deleteEndpointConfig(request, region);
            case "ListEndpointConfigs" -> service.listEndpointConfigs(request, region);
            case "CreateEndpoint" -> service.createEndpoint(request, region);
            case "DescribeEndpoint" -> service.describeEndpoint(request, region);
            case "DeleteEndpoint" -> service.deleteEndpoint(request, region);
            case "ListEndpoints" -> service.listEndpoints(request, region);
            case "UpdateEndpoint" -> service.updateEndpoint(request, region);
            case "CreateTrainingJob" -> service.createTrainingJob(request, region);
            case "DescribeTrainingJob" -> service.describeTrainingJob(request, region);
            case "ListTrainingJobs" -> service.listTrainingJobs(request, region);
            case "StopTrainingJob" -> service.stopTrainingJob(request, region);
            case "CreateFeatureGroup" -> featureStore.createFeatureGroup(request, region);
            case "DescribeFeatureGroup" -> featureStore.describeFeatureGroup(request, region);
            case "UpdateFeatureGroup" -> featureStore.updateFeatureGroup(request, region);
            case "DeleteFeatureGroup" -> featureStore.deleteFeatureGroup(request, region);
            case "ListFeatureGroups" -> featureStore.listFeatureGroups(request, region);
            case "CreateCluster" -> hyperPod.createCluster(request, region);
            case "DescribeCluster" -> hyperPod.describeCluster(request, region);
            case "DeleteCluster" -> hyperPod.deleteCluster(request, region);
            case "ListClusters" -> hyperPod.listClusters(request, region);
            case "ListClusterNodes" -> hyperPod.listClusterNodes(request, region);
            case "DescribeClusterNode" -> hyperPod.describeClusterNode(request, region);
            case "CreateClusterSchedulerConfig" -> hyperPod.createClusterSchedulerConfig(request, region);
            case "DescribeClusterSchedulerConfig" -> hyperPod.describeClusterSchedulerConfig(request, region);
            case "UpdateClusterSchedulerConfig" -> hyperPod.updateClusterSchedulerConfig(request, region);
            case "DeleteClusterSchedulerConfig" -> hyperPod.deleteClusterSchedulerConfig(request, region);
            case "ListClusterSchedulerConfigs" -> hyperPod.listClusterSchedulerConfigs(request, region);
            case "CreateComputeQuota" -> hyperPod.createComputeQuota(request, region);
            case "DescribeComputeQuota" -> hyperPod.describeComputeQuota(request, region);
            case "UpdateComputeQuota" -> hyperPod.updateComputeQuota(request, region);
            case "DeleteComputeQuota" -> hyperPod.deleteComputeQuota(request, region);
            case "ListComputeQuotas" -> hyperPod.listComputeQuotas(request, region);
            case "AddTags", "ListTags", "DeleteTags" -> tags(action, request);
            default -> throw SageMakerService.validation("Action " + action + " is not supported");
        };
        return Response.ok(entity).build();
    }

    private Object tags(String action, JsonNode request) {
        return featureStore.tags(action, request)
                .or(() -> hyperPod.tags(action, request))
                .map(Object.class::cast)
                .orElseGet(() -> switch (action) {
                    case "AddTags" -> service.addTags(request);
                    case "DeleteTags" -> service.deleteTags(request);
                    default -> service.listTags(request);
                });
    }
}
