package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The IoT SiteWise REST API (its api., data. and monitor. hosts alike), routed here by {@link SiteWiseRouteFilter}.
 * Each operation's path, query string, header and body members are gathered into one request, checked against the
 * API's shapes, and handed to {@link SiteWiseService}; responses carry the operation's status code and errors carry
 * {@code message} and {@code x-amzn-ErrorType}.
 */
@Path(SiteWiseRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class SiteWiseController {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/iotsitewise-request-shapes.json", "IoT SiteWise request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return SiteWiseStore.invalid(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return SiteWiseStore.invalid(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return SiteWiseStore.invalid(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return SiteWiseStore.invalid(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return SiteWiseStore.invalid(path + " is required.");
                }
            });

    private final SiteWiseService service;
    private final SiteWiseAssistant assistant;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public SiteWiseController(SiteWiseService service, SiteWiseAssistant assistant, RegionResolver regions,
                              ObjectMapper mapper) {
        this.service = service;
        this.assistant = assistant;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/assets/{assetId}/associate")
    public Response associateAssets(@PathParam("assetId") String assetId, String body) {
        return call("AssociateAssets", 200, List.of(), body, in -> {
            in.put("assetId", assetId);
        });
    }

    @POST
    @Path("/timeseries/associate/")
    public Response associateTimeSeriesToAssetProperty(@QueryParam("alias") String alias, @QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, String body) {
        return call("AssociateTimeSeriesToAssetProperty", 200, List.of(), body, in -> {
            in.put("alias", alias);
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
        });
    }

    @POST
    @Path("/datasets/{datasetId}/data-segments/associate")
    public Response batchAssociateDataSegmentsToDataset(@PathParam("datasetId") String datasetId, String body) {
        return call("BatchAssociateDataSegmentsToDataset", 200, List.of(), body, in -> {
            in.put("datasetId", datasetId);
        });
    }

    @POST
    @Path("/projects/{projectId}/assets/associate")
    public Response batchAssociateProjectAssets(@PathParam("projectId") String projectId, String body) {
        return call("BatchAssociateProjectAssets", 200, List.of(), body, in -> {
            in.put("projectId", projectId);
        });
    }

    @POST
    @Path("/datasets/{datasetId}/data-segments/batch-delete")
    public Response batchDeleteDatasetDataSegments(@PathParam("datasetId") String datasetId, String body) {
        return call("BatchDeleteDatasetDataSegments", 200, List.of(), body, in -> {
            in.put("datasetId", datasetId);
        });
    }

    @POST
    @Path("/datasets/{datasetId}/data-segments/disassociate")
    public Response batchDisassociateDataSegmentsFromDataset(@PathParam("datasetId") String datasetId, String body) {
        return call("BatchDisassociateDataSegmentsFromDataset", 200, List.of(), body, in -> {
            in.put("datasetId", datasetId);
        });
    }

    @POST
    @Path("/projects/{projectId}/assets/disassociate")
    public Response batchDisassociateProjectAssets(@PathParam("projectId") String projectId, String body) {
        return call("BatchDisassociateProjectAssets", 200, List.of(), body, in -> {
            in.put("projectId", projectId);
        });
    }

    @POST
    @Path("/properties/batch/aggregates")
    public Response batchGetAssetPropertyAggregates(String body) {
        return call("BatchGetAssetPropertyAggregates", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/properties/batch/latest")
    public Response batchGetAssetPropertyValue(String body) {
        return call("BatchGetAssetPropertyValue", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/properties/batch/history")
    public Response batchGetAssetPropertyValueHistory(String body) {
        return call("BatchGetAssetPropertyValueHistory", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/properties")
    public Response batchPutAssetPropertyValue(String body) {
        return call("BatchPutAssetPropertyValue", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/enrichment-jobs/{jobId}/cancel")
    public Response cancelEnrichmentJob(@PathParam("workspaceName") String workspaceName, @PathParam("jobId") String jobId) {
        return call("CancelEnrichmentJob", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("jobId", jobId);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}/executions/{pipelineExecutionId}/cancel")
    public Response cancelPipelineExecution(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, @PathParam("pipelineExecutionId") String pipelineExecutionId, String body) {
        return call("CancelPipelineExecution", 202, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
            in.put("pipelineExecutionId", pipelineExecutionId);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/queries/{queryId}/cancel")
    public Response cancelQuery(@PathParam("workspaceName") String workspaceName, @PathParam("queryId") String queryId) {
        return call("CancelQuery", 202, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("queryId", queryId);
        });
    }

    @POST
    @Path("/access-policies")
    public Response createAccessPolicy(String body) {
        return call("CreateAccessPolicy", 201, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/applications")
    public Response createApplication(String body) {
        return call("CreateApplication", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/assets")
    public Response createAsset(String body) {
        return call("CreateAsset", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/asset-models")
    public Response createAssetModel(String body) {
        return call("CreateAssetModel", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/asset-models/{assetModelId}/composite-models")
    public Response createAssetModelCompositeModel(@PathParam("assetModelId") String assetModelId, @HeaderParam("If-Match") String ifMatch, @HeaderParam("If-None-Match") String ifNoneMatch, @HeaderParam("Match-For-Version-Type") String matchForVersionType, String body) {
        return call("CreateAssetModelCompositeModel", 202, List.of(), body, in -> {
            in.put("assetModelId", assetModelId);
            in.put("ifMatch", ifMatch);
            in.put("ifNoneMatch", ifNoneMatch);
            in.put("matchForVersionType", matchForVersionType);
        });
    }

    @POST
    @Path("/jobs")
    public Response createBulkImportJob(String body) {
        return call("CreateBulkImportJob", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/computation-models")
    public Response createComputationModel(String body) {
        return call("CreateComputationModel", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/dashboards")
    public Response createDashboard(String body) {
        return call("CreateDashboard", 201, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/datasets")
    public Response createDataset(String body) {
        return call("CreateDataset", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/dataset-export-jobs")
    public Response createDatasetExportJob(@PathParam("workspaceName") String workspaceName, String body) {
        return call("CreateDatasetExportJob", 202, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/enrichment-jobs")
    public Response createEnrichmentJob(@PathParam("workspaceName") String workspaceName, String body) {
        return call("CreateEnrichmentJob", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/20200301/gateways")
    public Response createGateway(String body) {
        return call("CreateGateway", 201, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/pipelines")
    public Response createPipeline(@PathParam("workspaceName") String workspaceName, String body) {
        return call("CreatePipeline", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/portals")
    public Response createPortal(String body) {
        return call("CreatePortal", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/projects")
    public Response createProject(String body) {
        return call("CreateProject", 201, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/tasks")
    public Response createTask(@PathParam("workspaceName") String workspaceName, String body) {
        return call("CreateTask", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/workspaces")
    public Response createWorkspace(String body) {
        return call("CreateWorkspace", 200, List.of(), body, in -> {
        });
    }

    @DELETE
    @Path("/access-policies/{accessPolicyId}")
    public Response deleteAccessPolicy(@PathParam("accessPolicyId") String accessPolicyId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteAccessPolicy", 204, List.of(), null, in -> {
            in.put("accessPolicyId", accessPolicyId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/workspaces/{workspaceName}/applications/{id}")
    public Response deleteApplication(@PathParam("workspaceName") String workspaceName, @PathParam("id") String id) {
        return call("DeleteApplication", 204, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("id", id);
        });
    }

    @DELETE
    @Path("/assets/{assetId}")
    public Response deleteAsset(@PathParam("assetId") String assetId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteAsset", 202, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/asset-models/{assetModelId}")
    public Response deleteAssetModel(@PathParam("assetModelId") String assetModelId, @QueryParam("clientToken") String clientToken, @HeaderParam("If-Match") String ifMatch, @HeaderParam("If-None-Match") String ifNoneMatch, @HeaderParam("Match-For-Version-Type") String matchForVersionType) {
        return call("DeleteAssetModel", 202, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("clientToken", clientToken);
            in.put("ifMatch", ifMatch);
            in.put("ifNoneMatch", ifNoneMatch);
            in.put("matchForVersionType", matchForVersionType);
        });
    }

    @DELETE
    @Path("/asset-models/{assetModelId}/composite-models/{assetModelCompositeModelId}")
    public Response deleteAssetModelCompositeModel(@PathParam("assetModelId") String assetModelId, @PathParam("assetModelCompositeModelId") String assetModelCompositeModelId, @QueryParam("clientToken") String clientToken, @HeaderParam("If-Match") String ifMatch, @HeaderParam("If-None-Match") String ifNoneMatch, @HeaderParam("Match-For-Version-Type") String matchForVersionType) {
        return call("DeleteAssetModelCompositeModel", 202, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("assetModelCompositeModelId", assetModelCompositeModelId);
            in.put("clientToken", clientToken);
            in.put("ifMatch", ifMatch);
            in.put("ifNoneMatch", ifNoneMatch);
            in.put("matchForVersionType", matchForVersionType);
        });
    }

    @DELETE
    @Path("/asset-models/{assetModelId}/interface/{interfaceAssetModelId}/asset-model-interface-relationship")
    public Response deleteAssetModelInterfaceRelationship(@PathParam("assetModelId") String assetModelId, @PathParam("interfaceAssetModelId") String interfaceAssetModelId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteAssetModelInterfaceRelationship", 202, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("interfaceAssetModelId", interfaceAssetModelId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/computation-models/{computationModelId}")
    public Response deleteComputationModel(@PathParam("computationModelId") String computationModelId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteComputationModel", 202, List.of(), null, in -> {
            in.put("computationModelId", computationModelId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/dashboards/{dashboardId}")
    public Response deleteDashboard(@PathParam("dashboardId") String dashboardId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteDashboard", 204, List.of(), null, in -> {
            in.put("dashboardId", dashboardId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/datasets/{datasetId}")
    public Response deleteDataset(@PathParam("datasetId") String datasetId, @QueryParam("workspaceName") String workspaceName, @QueryParam("clientToken") String clientToken) {
        return call("DeleteDataset", 202, List.of(), null, in -> {
            in.put("datasetId", datasetId);
            in.put("workspaceName", workspaceName);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/20200301/gateways/{gatewayId}")
    public Response deleteGateway(@PathParam("gatewayId") String gatewayId) {
        return call("DeleteGateway", 200, List.of(), null, in -> {
            in.put("gatewayId", gatewayId);
        });
    }

    @DELETE
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}")
    public Response deletePipeline(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName) {
        return call("DeletePipeline", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
        });
    }

    @DELETE
    @Path("/portals/{portalId}")
    public Response deletePortal(@PathParam("portalId") String portalId, @QueryParam("clientToken") String clientToken) {
        return call("DeletePortal", 202, List.of(), null, in -> {
            in.put("portalId", portalId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/projects/{projectId}")
    public Response deleteProject(@PathParam("projectId") String projectId, @QueryParam("clientToken") String clientToken) {
        return call("DeleteProject", 204, List.of(), null, in -> {
            in.put("projectId", projectId);
            in.put("clientToken", clientToken);
        });
    }

    @DELETE
    @Path("/workspaces/{workspaceName}/tasks/{taskName}")
    public Response deleteTask(@PathParam("workspaceName") String workspaceName, @PathParam("taskName") String taskName) {
        return call("DeleteTask", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("taskName", taskName);
        });
    }

    @POST
    @Path("/timeseries/delete/")
    public Response deleteTimeSeries(@QueryParam("alias") String alias, @QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("workspaceName") String workspaceName, String body) {
        return call("DeleteTimeSeries", 200, List.of(), body, in -> {
            in.put("alias", alias);
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("workspaceName", workspaceName);
        });
    }

    @DELETE
    @Path("/workspaces/{workspaceName}")
    public Response deleteWorkspace(@PathParam("workspaceName") String workspaceName, @QueryParam("clientToken") String clientToken) {
        return call("DeleteWorkspace", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("clientToken", clientToken);
        });
    }

    @GET
    @Path("/access-policies/{accessPolicyId}")
    public Response describeAccessPolicy(@PathParam("accessPolicyId") String accessPolicyId) {
        return call("DescribeAccessPolicy", 200, List.of(), null, in -> {
            in.put("accessPolicyId", accessPolicyId);
        });
    }

    @GET
    @Path("/actions/{actionId}")
    public Response describeAction(@PathParam("actionId") String actionId) {
        return call("DescribeAction", 200, List.of(), null, in -> {
            in.put("actionId", actionId);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/applications/{id}")
    public Response describeApplication(@PathParam("workspaceName") String workspaceName, @PathParam("id") String id) {
        return call("DescribeApplication", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("id", id);
        });
    }

    @GET
    @Path("/assets/{assetId}")
    public Response describeAsset(@PathParam("assetId") String assetId, @QueryParam("excludeProperties") String excludeProperties) {
        return call("DescribeAsset", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            bool(in, "excludeProperties", excludeProperties);
        });
    }

    @GET
    @Path("/assets/{assetId}/composite-models/{assetCompositeModelId}")
    public Response describeAssetCompositeModel(@PathParam("assetId") String assetId, @PathParam("assetCompositeModelId") String assetCompositeModelId) {
        return call("DescribeAssetCompositeModel", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("assetCompositeModelId", assetCompositeModelId);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}")
    public Response describeAssetModel(@PathParam("assetModelId") String assetModelId, @QueryParam("excludeProperties") String excludeProperties, @QueryParam("assetModelVersion") String assetModelVersion) {
        return call("DescribeAssetModel", 200, List.of("eTag", "ETag"), null, in -> {
            in.put("assetModelId", assetModelId);
            bool(in, "excludeProperties", excludeProperties);
            in.put("assetModelVersion", assetModelVersion);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}/composite-models/{assetModelCompositeModelId}")
    public Response describeAssetModelCompositeModel(@PathParam("assetModelId") String assetModelId, @PathParam("assetModelCompositeModelId") String assetModelCompositeModelId, @QueryParam("assetModelVersion") String assetModelVersion) {
        return call("DescribeAssetModelCompositeModel", 200, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("assetModelCompositeModelId", assetModelCompositeModelId);
            in.put("assetModelVersion", assetModelVersion);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}/interface/{interfaceAssetModelId}/asset-model-interface-relationship")
    public Response describeAssetModelInterfaceRelationship(@PathParam("assetModelId") String assetModelId, @PathParam("interfaceAssetModelId") String interfaceAssetModelId) {
        return call("DescribeAssetModelInterfaceRelationship", 200, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("interfaceAssetModelId", interfaceAssetModelId);
        });
    }

    @GET
    @Path("/assets/{assetId}/properties/{propertyId}")
    public Response describeAssetProperty(@PathParam("assetId") String assetId, @PathParam("propertyId") String propertyId) {
        return call("DescribeAssetProperty", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
        });
    }

    @GET
    @Path("/jobs/{jobId}")
    public Response describeBulkImportJob(@PathParam("jobId") String jobId, @QueryParam("workspaceName") String workspaceName) {
        return call("DescribeBulkImportJob", 200, List.of(), null, in -> {
            in.put("jobId", jobId);
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/computation-models/{computationModelId}")
    public Response describeComputationModel(@PathParam("computationModelId") String computationModelId, @QueryParam("computationModelVersion") String computationModelVersion) {
        return call("DescribeComputationModel", 200, List.of(), null, in -> {
            in.put("computationModelId", computationModelId);
            in.put("computationModelVersion", computationModelVersion);
        });
    }

    @GET
    @Path("/computation-models/{computationModelId}/execution-summary")
    public Response describeComputationModelExecutionSummary(@PathParam("computationModelId") String computationModelId, @QueryParam("resolveToResourceType") String resolveToResourceType, @QueryParam("resolveToResourceId") String resolveToResourceId) {
        return call("DescribeComputationModelExecutionSummary", 200, List.of(), null, in -> {
            in.put("computationModelId", computationModelId);
            in.put("resolveToResourceType", resolveToResourceType);
            in.put("resolveToResourceId", resolveToResourceId);
        });
    }

    @GET
    @Path("/dashboards/{dashboardId}")
    public Response describeDashboard(@PathParam("dashboardId") String dashboardId) {
        return call("DescribeDashboard", 200, List.of(), null, in -> {
            in.put("dashboardId", dashboardId);
        });
    }

    @GET
    @Path("/datasets/{datasetId}")
    public Response describeDataset(@PathParam("datasetId") String datasetId, @QueryParam("workspaceName") String workspaceName, @QueryParam("datasetVersion") String datasetVersion) {
        return call("DescribeDataset", 200, List.of(), null, in -> {
            in.put("datasetId", datasetId);
            in.put("workspaceName", workspaceName);
            in.put("datasetVersion", datasetVersion);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/dataset-export-jobs/{jobId}")
    public Response describeDatasetExportJob(@PathParam("workspaceName") String workspaceName, @PathParam("jobId") String jobId) {
        return call("DescribeDatasetExportJob", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("jobId", jobId);
        });
    }

    @GET
    @Path("/configuration/account/encryption")
    public Response describeDefaultEncryptionConfiguration() {
        return call("DescribeDefaultEncryptionConfiguration", 200, List.of(), null, in -> {
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/enrichment-jobs/{jobId}")
    public Response describeEnrichmentJob(@PathParam("workspaceName") String workspaceName, @PathParam("jobId") String jobId) {
        return call("DescribeEnrichmentJob", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("jobId", jobId);
        });
    }

    @GET
    @Path("/executions/{executionId}")
    public Response describeExecution(@PathParam("executionId") String executionId) {
        return call("DescribeExecution", 200, List.of(), null, in -> {
            in.put("executionId", executionId);
        });
    }

    @GET
    @Path("/20200301/gateways/{gatewayId}")
    public Response describeGateway(@PathParam("gatewayId") String gatewayId) {
        return call("DescribeGateway", 200, List.of(), null, in -> {
            in.put("gatewayId", gatewayId);
        });
    }

    @GET
    @Path("/20200301/gateways/{gatewayId}/capability/{capabilityNamespace}")
    public Response describeGatewayCapabilityConfiguration(@PathParam("gatewayId") String gatewayId, @PathParam("capabilityNamespace") String capabilityNamespace) {
        return call("DescribeGatewayCapabilityConfiguration", 200, List.of(), null, in -> {
            in.put("gatewayId", gatewayId);
            in.put("capabilityNamespace", capabilityNamespace);
        });
    }

    @GET
    @Path("/logging")
    public Response describeLoggingOptions(@QueryParam("workspaceName") String workspaceName) {
        return call("DescribeLoggingOptions", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}")
    public Response describePipeline(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, @QueryParam("version") String pipelineVersion) {
        return call("DescribePipeline", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
            in.put("pipelineVersion", pipelineVersion);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}/executions/{pipelineExecutionId}")
    public Response describePipelineExecution(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, @PathParam("pipelineExecutionId") String pipelineExecutionId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("DescribePipelineExecution", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
            in.put("pipelineExecutionId", pipelineExecutionId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/portals/{portalId}")
    public Response describePortal(@PathParam("portalId") String portalId) {
        return call("DescribePortal", 200, List.of(), null, in -> {
            in.put("portalId", portalId);
        });
    }

    @GET
    @Path("/projects/{projectId}")
    public Response describeProject(@PathParam("projectId") String projectId) {
        return call("DescribeProject", 200, List.of(), null, in -> {
            in.put("projectId", projectId);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/queries/{queryId}")
    public Response describeQuery(@PathParam("workspaceName") String workspaceName, @PathParam("queryId") String queryId) {
        return call("DescribeQuery", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("queryId", queryId);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/searches/{searchId}")
    public Response describeSearch(@PathParam("workspaceName") String workspaceName, @PathParam("searchId") String searchId) {
        return call("DescribeSearch", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("searchId", searchId);
        });
    }

    @GET
    @Path("/configuration/account/storage")
    public Response describeStorageConfiguration() {
        return call("DescribeStorageConfiguration", 200, List.of(), null, in -> {
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/tasks/{taskName}")
    public Response describeTask(@PathParam("workspaceName") String workspaceName, @PathParam("taskName") String taskName, @QueryParam("version") String taskVersion) {
        return call("DescribeTask", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("taskName", taskName);
            in.put("taskVersion", taskVersion);
        });
    }

    @GET
    @Path("/timeseries/describe/")
    public Response describeTimeSeries(@QueryParam("alias") String alias, @QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("workspaceName") String workspaceName) {
        return call("DescribeTimeSeries", 200, List.of(), null, in -> {
            in.put("alias", alias);
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}")
    public Response describeWorkspace(@PathParam("workspaceName") String workspaceName) {
        return call("DescribeWorkspace", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/assets/{assetId}/disassociate")
    public Response disassociateAssets(@PathParam("assetId") String assetId, String body) {
        return call("DisassociateAssets", 200, List.of(), body, in -> {
            in.put("assetId", assetId);
        });
    }

    @POST
    @Path("/timeseries/disassociate/")
    public Response disassociateTimeSeriesFromAssetProperty(@QueryParam("alias") String alias, @QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, String body) {
        return call("DisassociateTimeSeriesFromAssetProperty", 200, List.of(), body, in -> {
            in.put("alias", alias);
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
        });
    }

    @POST
    @Path("/actions")
    public Response executeAction(String body) {
        return call("ExecuteAction", 202, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/queries/execution")
    public Response executeQuery(String body) {
        return call("ExecuteQuery", 200, List.of(), body, in -> {
        });
    }

    @GET
    @Path("/properties/aggregates")
    public Response getAssetPropertyAggregates(@QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("propertyAlias") String propertyAlias, @QueryParam("aggregateTypes") List<String> aggregateTypes, @QueryParam("resolution") String resolution, @QueryParam("qualities") List<String> qualities, @QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate, @QueryParam("timeOrdering") String timeOrdering, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("GetAssetPropertyAggregates", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("propertyAlias", propertyAlias);
            list(in, "aggregateTypes", aggregateTypes);
            in.put("resolution", resolution);
            list(in, "qualities", qualities);
            in.put("startDate", startDate);
            in.put("endDate", endDate);
            in.put("timeOrdering", timeOrdering);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/properties/latest")
    public Response getAssetPropertyValue(@QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("propertyAlias") String propertyAlias) {
        return call("GetAssetPropertyValue", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("propertyAlias", propertyAlias);
        });
    }

    @GET
    @Path("/properties/history")
    public Response getAssetPropertyValueHistory(@QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("propertyAlias") String propertyAlias, @QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate, @QueryParam("qualities") List<String> qualities, @QueryParam("timeOrdering") String timeOrdering, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("GetAssetPropertyValueHistory", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("propertyAlias", propertyAlias);
            in.put("startDate", startDate);
            in.put("endDate", endDate);
            list(in, "qualities", qualities);
            in.put("timeOrdering", timeOrdering);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/get-capture-data")
    public Response getCaptureData(@PathParam("workspaceName") String workspaceName, String body) {
        return call("GetCaptureData", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/properties/interpolated")
    public Response getInterpolatedAssetPropertyValues(@QueryParam("assetId") String assetId, @QueryParam("propertyId") String propertyId, @QueryParam("propertyAlias") String propertyAlias, @QueryParam("startTimeInSeconds") String startTimeInSeconds, @QueryParam("startTimeOffsetInNanos") String startTimeOffsetInNanos, @QueryParam("endTimeInSeconds") String endTimeInSeconds, @QueryParam("endTimeOffsetInNanos") String endTimeOffsetInNanos, @QueryParam("quality") String quality, @QueryParam("intervalInSeconds") String intervalInSeconds, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("type") String type, @QueryParam("intervalWindowInSeconds") String intervalWindowInSeconds) {
        return call("GetInterpolatedAssetPropertyValues", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
            in.put("propertyAlias", propertyAlias);
            integer(in, "startTimeInSeconds", startTimeInSeconds);
            integer(in, "startTimeOffsetInNanos", startTimeOffsetInNanos);
            integer(in, "endTimeInSeconds", endTimeInSeconds);
            integer(in, "endTimeOffsetInNanos", endTimeOffsetInNanos);
            in.put("quality", quality);
            integer(in, "intervalInSeconds", intervalInSeconds);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("type", type);
            integer(in, "intervalWindowInSeconds", intervalWindowInSeconds);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/queries/{queryId}/results")
    public Response getQueryResults(@PathParam("workspaceName") String workspaceName, @PathParam("queryId") String queryId, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("GetQueryResults", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("queryId", queryId);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/searches/{searchId}/results")
    public Response getSearchResults(@PathParam("searchId") String searchId, @PathParam("workspaceName") String workspaceName, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("GetSearchResults", 200, List.of(), null, in -> {
            in.put("searchId", searchId);
            in.put("workspaceName", workspaceName);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @POST
    @Path("/assistant/invocation")
    public Response invokeAssistant(String body) {
        try {
            ObjectNode in = parse(body);
            SHAPES.check("InvokeAssistant", in);
            SiteWiseAssistant.Reply reply = assistant.invoke(in, regions.getRegion());
            return Response.ok(reply.body()).type("application/vnd.amazon.eventstream")
                    .header("x-amz-iotsitewise-assistant-conversation-id", reply.conversationId()).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/access-policies")
    public Response listAccessPolicies(@QueryParam("identityType") String identityType, @QueryParam("identityId") String identityId, @QueryParam("resourceType") String resourceType, @QueryParam("resourceId") String resourceId, @QueryParam("iamArn") String iamArn, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListAccessPolicies", 200, List.of(), null, in -> {
            in.put("identityType", identityType);
            in.put("identityId", identityId);
            in.put("resourceType", resourceType);
            in.put("resourceId", resourceId);
            in.put("iamArn", iamArn);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/actions")
    public Response listActions(@QueryParam("targetResourceType") String targetResourceType, @QueryParam("targetResourceId") String targetResourceId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("resolveToResourceType") String resolveToResourceType, @QueryParam("resolveToResourceId") String resolveToResourceId) {
        return call("ListActions", 200, List.of(), null, in -> {
            in.put("targetResourceType", targetResourceType);
            in.put("targetResourceId", targetResourceId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("resolveToResourceType", resolveToResourceType);
            in.put("resolveToResourceId", resolveToResourceId);
        });
    }

    @GET
    @Path("/applications")
    public Response listApplications(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListApplications", 200, List.of(), null, in -> {
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}/composite-models")
    public Response listAssetModelCompositeModels(@PathParam("assetModelId") String assetModelId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("assetModelVersion") String assetModelVersion) {
        return call("ListAssetModelCompositeModels", 200, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("assetModelVersion", assetModelVersion);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}/properties")
    public Response listAssetModelProperties(@PathParam("assetModelId") String assetModelId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("filter") String filter, @QueryParam("assetModelVersion") String assetModelVersion) {
        return call("ListAssetModelProperties", 200, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("filter", filter);
            in.put("assetModelVersion", assetModelVersion);
        });
    }

    @GET
    @Path("/asset-models")
    public Response listAssetModels(@QueryParam("assetModelTypes") List<String> assetModelTypes, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("assetModelVersion") String assetModelVersion) {
        return call("ListAssetModels", 200, List.of(), null, in -> {
            list(in, "assetModelTypes", assetModelTypes);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("assetModelVersion", assetModelVersion);
        });
    }

    @GET
    @Path("/assets/{assetId}/properties")
    public Response listAssetProperties(@PathParam("assetId") String assetId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("filter") String filter) {
        return call("ListAssetProperties", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("filter", filter);
        });
    }

    @GET
    @Path("/assets/{assetId}/assetRelationships")
    public Response listAssetRelationships(@PathParam("assetId") String assetId, @QueryParam("traversalType") String traversalType, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListAssetRelationships", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("traversalType", traversalType);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/assets")
    public Response listAssets(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("assetModelId") String assetModelId, @QueryParam("filter") String filter) {
        return call("ListAssets", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("assetModelId", assetModelId);
            in.put("filter", filter);
        });
    }

    @GET
    @Path("/assets/{assetId}/hierarchies")
    public Response listAssociatedAssets(@PathParam("assetId") String assetId, @QueryParam("hierarchyId") String hierarchyId, @QueryParam("traversalDirection") String traversalDirection, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListAssociatedAssets", 200, List.of(), null, in -> {
            in.put("assetId", assetId);
            in.put("hierarchyId", hierarchyId);
            in.put("traversalDirection", traversalDirection);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/jobs")
    public Response listBulkImportJobs(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("filter") String filter, @QueryParam("workspaceName") String workspaceName) {
        return call("ListBulkImportJobs", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("filter", filter);
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/asset-models/{assetModelId}/composition-relationships")
    public Response listCompositionRelationships(@PathParam("assetModelId") String assetModelId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListCompositionRelationships", 200, List.of(), null, in -> {
            in.put("assetModelId", assetModelId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @POST
    @Path("/computation-models/data-binding-usages")
    public Response listComputationModelDataBindingUsages(String body) {
        return call("ListComputationModelDataBindingUsages", 200, List.of(), body, in -> {
        });
    }

    @GET
    @Path("/computation-models/{computationModelId}/resolve-to-resources")
    public Response listComputationModelResolveToResources(@PathParam("computationModelId") String computationModelId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListComputationModelResolveToResources", 200, List.of(), null, in -> {
            in.put("computationModelId", computationModelId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/computation-models")
    public Response listComputationModels(@QueryParam("computationModelType") String computationModelType, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListComputationModels", 200, List.of(), null, in -> {
            in.put("computationModelType", computationModelType);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/dashboards")
    public Response listDashboards(@QueryParam("projectId") String projectId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListDashboards", 200, List.of(), null, in -> {
            in.put("projectId", projectId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/datasets/{datasetId}/data-segment-relationships")
    public Response listDatasetDataSegmentRelationships(@PathParam("datasetId") String datasetId, @QueryParam("workspaceName") String workspaceName, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListDatasetDataSegmentRelationships", 200, List.of(), null, in -> {
            in.put("datasetId", datasetId);
            in.put("workspaceName", workspaceName);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/datasets/{datasetId}/data-segments")
    public Response listDatasetDataSegments(@PathParam("datasetId") String datasetId, @QueryParam("workspaceName") String workspaceName, @QueryParam("datasetVersion") String datasetVersion, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListDatasetDataSegments", 200, List.of(), null, in -> {
            in.put("datasetId", datasetId);
            in.put("workspaceName", workspaceName);
            in.put("datasetVersion", datasetVersion);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/dataset-export-jobs")
    public Response listDatasetExportJobs(@PathParam("workspaceName") String workspaceName, @QueryParam("filter") String filter, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListDatasetExportJobs", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("filter", filter);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/datasets")
    public Response listDatasets(@QueryParam("sourceType") String sourceType, @QueryParam("workspaceName") String workspaceName, @QueryParam("datasetType") String datasetType, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListDatasets", 200, List.of(), null, in -> {
            in.put("sourceType", sourceType);
            in.put("workspaceName", workspaceName);
            in.put("datasetType", datasetType);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/enrichment-jobs")
    public Response listEnrichmentJobs(@PathParam("workspaceName") String workspaceName, @QueryParam("datasetId") String datasetId, @QueryParam("propertyAlias") String propertyAlias, @QueryParam("timeSeriesId") String timeSeriesId, @QueryParam("status") String status, @QueryParam("jobType") String jobType, @QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListEnrichmentJobs", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("datasetId", datasetId);
            in.put("propertyAlias", propertyAlias);
            in.put("timeSeriesId", timeSeriesId);
            in.put("status", status);
            in.put("jobType", jobType);
            in.put("startDate", startDate);
            in.put("endDate", endDate);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @GET
    @Path("/executions")
    public Response listExecutions(@QueryParam("targetResourceType") String targetResourceType, @QueryParam("targetResourceId") String targetResourceId, @QueryParam("resolveToResourceType") String resolveToResourceType, @QueryParam("resolveToResourceId") String resolveToResourceId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("actionType") String actionType) {
        return call("ListExecutions", 200, List.of(), null, in -> {
            in.put("targetResourceType", targetResourceType);
            in.put("targetResourceId", targetResourceId);
            in.put("resolveToResourceType", resolveToResourceType);
            in.put("resolveToResourceId", resolveToResourceId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("actionType", actionType);
        });
    }

    @GET
    @Path("/20200301/gateways")
    public Response listGateways(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListGateways", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/interface/{interfaceAssetModelId}/asset-models")
    public Response listInterfaceRelationships(@PathParam("interfaceAssetModelId") String interfaceAssetModelId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListInterfaceRelationships", 200, List.of(), null, in -> {
            in.put("interfaceAssetModelId", interfaceAssetModelId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}/executions")
    public Response listPipelineExecutions(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("state") String state, @QueryParam("startTimeAfter") String startTimeAfter, @QueryParam("startTimeBefore") String startTimeBefore, @QueryParam("endTimeAfter") String endTimeAfter, @QueryParam("endTimeBefore") String endTimeBefore) {
        return call("ListPipelineExecutions", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("state", state);
            in.put("startTimeAfter", startTimeAfter);
            in.put("startTimeBefore", startTimeBefore);
            in.put("endTimeAfter", endTimeAfter);
            in.put("endTimeBefore", endTimeBefore);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/pipelines")
    public Response listPipelines(@PathParam("workspaceName") String workspaceName, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListPipelines", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/portals")
    public Response listPortals(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListPortals", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/projects/{projectId}/assets")
    public Response listProjectAssets(@PathParam("projectId") String projectId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListProjectAssets", 200, List.of(), null, in -> {
            in.put("projectId", projectId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/projects")
    public Response listProjects(@QueryParam("portalId") String portalId, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListProjects", 200, List.of(), null, in -> {
            in.put("portalId", portalId);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/queries")
    public Response listQueries(@PathParam("workspaceName") String workspaceName, @QueryParam("filter") String filter, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListQueries", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("filter", filter);
            integer(in, "maxResults", maxResults);
            in.put("nextToken", nextToken);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/searches/list")
    public Response listSearches(@PathParam("workspaceName") String workspaceName, String body) {
        return call("ListSearches", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/tags")
    public Response listTagsForResource(@QueryParam("resourceArn") String resourceArn) {
        return call("ListTagsForResource", 200, List.of(), null, in -> {
            in.put("resourceArn", resourceArn);
        });
    }

    @GET
    @Path("/workspaces/{workspaceName}/tasks")
    public Response listTasks(@PathParam("workspaceName") String workspaceName, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListTasks", 200, List.of(), null, in -> {
            in.put("workspaceName", workspaceName);
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @GET
    @Path("/timeseries/")
    public Response listTimeSeries(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("assetId") String assetId, @QueryParam("aliasPrefix") String aliasPrefix, @QueryParam("timeSeriesType") String timeSeriesType, @QueryParam("workspaceName") String workspaceName) {
        return call("ListTimeSeries", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
            in.put("assetId", assetId);
            in.put("aliasPrefix", aliasPrefix);
            in.put("timeSeriesType", timeSeriesType);
            in.put("workspaceName", workspaceName);
        });
    }

    @GET
    @Path("/workspaces")
    public Response listWorkspaces(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListWorkspaces", 200, List.of(), null, in -> {
            in.put("nextToken", nextToken);
            integer(in, "maxResults", maxResults);
        });
    }

    @PUT
    @Path("/asset-models/{assetModelId}/interface/{interfaceAssetModelId}/asset-model-interface-relationship")
    public Response putAssetModelInterfaceRelationship(@PathParam("assetModelId") String assetModelId, @PathParam("interfaceAssetModelId") String interfaceAssetModelId, String body) {
        return call("PutAssetModelInterfaceRelationship", 202, List.of(), body, in -> {
            in.put("assetModelId", assetModelId);
            in.put("interfaceAssetModelId", interfaceAssetModelId);
        });
    }

    @POST
    @Path("/configuration/account/encryption")
    public Response putDefaultEncryptionConfiguration(String body) {
        return call("PutDefaultEncryptionConfiguration", 200, List.of(), body, in -> {
        });
    }

    @PUT
    @Path("/logging")
    public Response putLoggingOptions(String body) {
        return call("PutLoggingOptions", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/configuration/account/storage")
    public Response putStorageConfiguration(String body) {
        return call("PutStorageConfiguration", 200, List.of(), body, in -> {
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}/executions")
    public Response startPipelineExecution(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, String body) {
        return call("StartPipelineExecution", 202, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/queries")
    public Response startQuery(@PathParam("workspaceName") String workspaceName, String body) {
        return call("StartQuery", 202, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/workspaces/{workspaceName}/searches")
    public Response startSearch(@PathParam("workspaceName") String workspaceName, String body) {
        return call("StartSearch", 202, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    @POST
    @Path("/tags")
    public Response tagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        return call("TagResource", 200, List.of(), body, in -> {
            in.put("resourceArn", resourceArn);
        });
    }

    @DELETE
    @Path("/tags")
    public Response untagResource(@QueryParam("resourceArn") String resourceArn, @QueryParam("tagKeys") List<String> tagKeys) {
        return call("UntagResource", 200, List.of(), null, in -> {
            in.put("resourceArn", resourceArn);
            list(in, "tagKeys", tagKeys);
        });
    }

    @PUT
    @Path("/access-policies/{accessPolicyId}")
    public Response updateAccessPolicy(@PathParam("accessPolicyId") String accessPolicyId, String body) {
        return call("UpdateAccessPolicy", 200, List.of(), body, in -> {
            in.put("accessPolicyId", accessPolicyId);
        });
    }

    @PUT
    @Path("/assets/{assetId}")
    public Response updateAsset(@PathParam("assetId") String assetId, String body) {
        return call("UpdateAsset", 202, List.of(), body, in -> {
            in.put("assetId", assetId);
        });
    }

    @PUT
    @Path("/asset-models/{assetModelId}")
    public Response updateAssetModel(@PathParam("assetModelId") String assetModelId, @HeaderParam("If-Match") String ifMatch, @HeaderParam("If-None-Match") String ifNoneMatch, @HeaderParam("Match-For-Version-Type") String matchForVersionType, String body) {
        return call("UpdateAssetModel", 202, List.of(), body, in -> {
            in.put("assetModelId", assetModelId);
            in.put("ifMatch", ifMatch);
            in.put("ifNoneMatch", ifNoneMatch);
            in.put("matchForVersionType", matchForVersionType);
        });
    }

    @PUT
    @Path("/asset-models/{assetModelId}/composite-models/{assetModelCompositeModelId}")
    public Response updateAssetModelCompositeModel(@PathParam("assetModelId") String assetModelId, @PathParam("assetModelCompositeModelId") String assetModelCompositeModelId, @HeaderParam("If-Match") String ifMatch, @HeaderParam("If-None-Match") String ifNoneMatch, @HeaderParam("Match-For-Version-Type") String matchForVersionType, String body) {
        return call("UpdateAssetModelCompositeModel", 202, List.of(), body, in -> {
            in.put("assetModelId", assetModelId);
            in.put("assetModelCompositeModelId", assetModelCompositeModelId);
            in.put("ifMatch", ifMatch);
            in.put("ifNoneMatch", ifNoneMatch);
            in.put("matchForVersionType", matchForVersionType);
        });
    }

    @PUT
    @Path("/assets/{assetId}/properties/{propertyId}")
    public Response updateAssetProperty(@PathParam("assetId") String assetId, @PathParam("propertyId") String propertyId, String body) {
        return call("UpdateAssetProperty", 200, List.of(), body, in -> {
            in.put("assetId", assetId);
            in.put("propertyId", propertyId);
        });
    }

    @POST
    @Path("/computation-models/{computationModelId}")
    public Response updateComputationModel(@PathParam("computationModelId") String computationModelId, String body) {
        return call("UpdateComputationModel", 202, List.of(), body, in -> {
            in.put("computationModelId", computationModelId);
        });
    }

    @PUT
    @Path("/dashboards/{dashboardId}")
    public Response updateDashboard(@PathParam("dashboardId") String dashboardId, String body) {
        return call("UpdateDashboard", 200, List.of(), body, in -> {
            in.put("dashboardId", dashboardId);
        });
    }

    @PUT
    @Path("/datasets/{datasetId}")
    public Response updateDataset(@PathParam("datasetId") String datasetId, String body) {
        return call("UpdateDataset", 202, List.of(), body, in -> {
            in.put("datasetId", datasetId);
        });
    }

    @PUT
    @Path("/20200301/gateways/{gatewayId}")
    public Response updateGateway(@PathParam("gatewayId") String gatewayId, String body) {
        return call("UpdateGateway", 200, List.of(), body, in -> {
            in.put("gatewayId", gatewayId);
        });
    }

    @POST
    @Path("/20200301/gateways/{gatewayId}/capability")
    public Response updateGatewayCapabilityConfiguration(@PathParam("gatewayId") String gatewayId, String body) {
        return call("UpdateGatewayCapabilityConfiguration", 201, List.of(), body, in -> {
            in.put("gatewayId", gatewayId);
        });
    }

    @PUT
    @Path("/workspaces/{workspaceName}/pipelines/{pipelineName}")
    public Response updatePipeline(@PathParam("workspaceName") String workspaceName, @PathParam("pipelineName") String pipelineName, String body) {
        return call("UpdatePipeline", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
            in.put("pipelineName", pipelineName);
        });
    }

    @PUT
    @Path("/portals/{portalId}")
    public Response updatePortal(@PathParam("portalId") String portalId, String body) {
        return call("UpdatePortal", 202, List.of(), body, in -> {
            in.put("portalId", portalId);
        });
    }

    @PUT
    @Path("/projects/{projectId}")
    public Response updateProject(@PathParam("projectId") String projectId, String body) {
        return call("UpdateProject", 200, List.of(), body, in -> {
            in.put("projectId", projectId);
        });
    }

    @PUT
    @Path("/workspaces/{workspaceName}/tasks/{taskName}")
    public Response updateTask(@PathParam("workspaceName") String workspaceName, @PathParam("taskName") String taskName, String body) {
        return call("UpdateTask", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
            in.put("taskName", taskName);
        });
    }

    @PUT
    @Path("/workspaces/{workspaceName}")
    public Response updateWorkspace(@PathParam("workspaceName") String workspaceName, String body) {
        return call("UpdateWorkspace", 200, List.of(), body, in -> {
            in.put("workspaceName", workspaceName);
        });
    }

    // ---------------------------------------------------------------- plumbing

    private Response call(String operation, int status, List<String> headers, String body,
                          Consumer<ObjectNode> members) {
        try {
            ObjectNode in = parse(body);
            members.accept(in);
            List<String> absent = new ArrayList<>();
            in.fields().forEachRemaining(field -> {
                if (field.getValue().isNull()) {
                    absent.add(field.getKey());
                }
            });
            in.remove(absent);
            SHAPES.check(operation, in);
            ObjectNode out = service.handle(operation, in, regions.getRegion());
            Response.ResponseBuilder response = Response.status(status).type(MediaType.APPLICATION_JSON);
            for (int i = 0; i + 1 < headers.size(); i += 2) {
                JsonNode value = out.remove(headers.get(i));
                if (value != null && !value.isNull()) {
                    response.header(headers.get(i + 1), value.asText());
                }
            }
            return response.entity(out.toString()).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException e) {
        ObjectNode error = mapper.createObjectNode();
        error.put("message", e.getMessage());
        if (e.getExtendedData() != null) {
            e.getExtendedData().forEach((key, value) -> error.set(key, mapper.valueToTree(value)));
        }
        return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw SiteWiseStore.invalid("The request body must be a JSON object.");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException e) {
            throw SiteWiseStore.invalid("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void integer(ObjectNode in, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            in.put(name, Long.parseLong(value));
        } catch (NumberFormatException e) {
            in.put(name, value);
        }
    }

    private static void bool(ObjectNode in, String name, String value) {
        if (value != null) {
            in.put(name, Boolean.parseBoolean(value));
        }
    }

    private static void list(ObjectNode in, String name, List<String> values) {
        if (values != null && !values.isEmpty()) {
            values.forEach(in.putArray(name)::add);
        }
    }
}
