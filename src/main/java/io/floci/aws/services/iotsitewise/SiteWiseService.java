package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Hands each IoT SiteWise operation to the class that implements it. */
@ApplicationScoped
public class SiteWiseService {

    private final SiteWiseAssets assets;
    private final SiteWiseComputations computations;
    private final SiteWiseData data;
    private final SiteWiseGateways gateways;
    private final SiteWiseJobs jobs;
    private final SiteWiseModels models;
    private final SiteWiseMonitor monitor;
    private final SiteWiseQuery query;
    private final SiteWiseSettings settings;
    private final SiteWiseWorkspaces workspaces;

    @Inject
    public SiteWiseService(SiteWiseAssets assets, SiteWiseComputations computations, SiteWiseData data,
                           SiteWiseGateways gateways, SiteWiseJobs jobs, SiteWiseModels models,
                           SiteWiseMonitor monitor, SiteWiseQuery query, SiteWiseSettings settings,
                           SiteWiseWorkspaces workspaces) {
        this.assets = assets;
        this.computations = computations;
        this.data = data;
        this.gateways = gateways;
        this.jobs = jobs;
        this.models = models;
        this.monitor = monitor;
        this.query = query;
        this.settings = settings;
        this.workspaces = workspaces;
    }

    ObjectNode handle(String operation, ObjectNode in, String region) {
        if (operation.startsWith("Create")) {
            query.warm();
        }
        return switch (operation) {
            case "AssociateAssets" -> assets.associateAssets(in, region);
            case "AssociateTimeSeriesToAssetProperty" -> data.associateTimeSeriesToAssetProperty(in, region);
            case "BatchAssociateDataSegmentsToDataset" -> workspaces.batchAssociateDataSegmentsToDataset(in, region);
            case "BatchAssociateProjectAssets" -> monitor.batchAssociateProjectAssets(in, region);
            case "BatchDeleteDatasetDataSegments" -> workspaces.batchDeleteDatasetDataSegments(in, region);
            case "BatchDisassociateDataSegmentsFromDataset" -> workspaces.batchDisassociateDataSegmentsFromDataset(in, region);
            case "BatchDisassociateProjectAssets" -> monitor.batchDisassociateProjectAssets(in, region);
            case "BatchGetAssetPropertyAggregates" -> data.batchGetAssetPropertyAggregates(in, region);
            case "BatchGetAssetPropertyValue" -> data.batchGetAssetPropertyValue(in, region);
            case "BatchGetAssetPropertyValueHistory" -> data.batchGetAssetPropertyValueHistory(in, region);
            case "BatchPutAssetPropertyValue" -> data.batchPutAssetPropertyValue(in, region);
            case "CancelEnrichmentJob" -> workspaces.cancelEnrichmentJob(in, region);
            case "CancelPipelineExecution" -> workspaces.cancelPipelineExecution(in, region);
            case "CancelQuery" -> workspaces.cancelQuery(in, region);
            case "CreateAccessPolicy" -> monitor.createAccessPolicy(in, region);
            case "CreateApplication" -> workspaces.createApplication(in, region);
            case "CreateAsset" -> assets.createAsset(in, region);
            case "CreateAssetModel" -> models.createAssetModel(in, region);
            case "CreateAssetModelCompositeModel" -> models.createAssetModelCompositeModel(in, region);
            case "CreateBulkImportJob" -> jobs.createBulkImportJob(in, region);
            case "CreateComputationModel" -> computations.createComputationModel(in, region);
            case "CreateDashboard" -> monitor.createDashboard(in, region);
            case "CreateDataset" -> workspaces.createDataset(in, region);
            case "CreateDatasetExportJob" -> workspaces.createDatasetExportJob(in, region);
            case "CreateEnrichmentJob" -> workspaces.createEnrichmentJob(in, region);
            case "CreateGateway" -> gateways.createGateway(in, region);
            case "CreatePipeline" -> workspaces.createPipeline(in, region);
            case "CreatePortal" -> monitor.createPortal(in, region);
            case "CreateProject" -> monitor.createProject(in, region);
            case "CreateTask" -> workspaces.createTask(in, region);
            case "CreateWorkspace" -> workspaces.createWorkspace(in, region);
            case "DeleteAccessPolicy" -> monitor.deleteAccessPolicy(in, region);
            case "DeleteApplication" -> workspaces.deleteApplication(in, region);
            case "DeleteAsset" -> assets.deleteAsset(in, region);
            case "DeleteAssetModel" -> models.deleteAssetModel(in, region);
            case "DeleteAssetModelCompositeModel" -> models.deleteAssetModelCompositeModel(in, region);
            case "DeleteAssetModelInterfaceRelationship" -> models.deleteAssetModelInterfaceRelationship(in, region);
            case "DeleteComputationModel" -> computations.deleteComputationModel(in, region);
            case "DeleteDashboard" -> monitor.deleteDashboard(in, region);
            case "DeleteDataset" -> workspaces.deleteDataset(in, region);
            case "DeleteGateway" -> gateways.deleteGateway(in, region);
            case "DeletePipeline" -> workspaces.deletePipeline(in, region);
            case "DeletePortal" -> monitor.deletePortal(in, region);
            case "DeleteProject" -> monitor.deleteProject(in, region);
            case "DeleteTask" -> workspaces.deleteTask(in, region);
            case "DeleteTimeSeries" -> data.deleteTimeSeries(in, region);
            case "DeleteWorkspace" -> workspaces.deleteWorkspace(in, region);
            case "DescribeAccessPolicy" -> monitor.describeAccessPolicy(in, region);
            case "DescribeAction" -> computations.describeAction(in, region);
            case "DescribeApplication" -> workspaces.describeApplication(in, region);
            case "DescribeAsset" -> assets.describeAsset(in, region);
            case "DescribeAssetCompositeModel" -> assets.describeAssetCompositeModel(in, region);
            case "DescribeAssetModel" -> models.describeAssetModel(in, region);
            case "DescribeAssetModelCompositeModel" -> models.describeAssetModelCompositeModel(in, region);
            case "DescribeAssetModelInterfaceRelationship" -> models.describeAssetModelInterfaceRelationship(in, region);
            case "DescribeAssetProperty" -> assets.describeAssetProperty(in, region);
            case "DescribeBulkImportJob" -> jobs.describeBulkImportJob(in, region);
            case "DescribeComputationModel" -> computations.describeComputationModel(in, region);
            case "DescribeComputationModelExecutionSummary" -> computations.describeComputationModelExecutionSummary(in, region);
            case "DescribeDashboard" -> monitor.describeDashboard(in, region);
            case "DescribeDataset" -> workspaces.describeDataset(in, region);
            case "DescribeDatasetExportJob" -> workspaces.describeDatasetExportJob(in, region);
            case "DescribeDefaultEncryptionConfiguration" -> settings.describeDefaultEncryptionConfiguration(in, region);
            case "DescribeEnrichmentJob" -> workspaces.describeEnrichmentJob(in, region);
            case "DescribeExecution" -> computations.describeExecution(in, region);
            case "DescribeGateway" -> gateways.describeGateway(in, region);
            case "DescribeGatewayCapabilityConfiguration" -> gateways.describeGatewayCapabilityConfiguration(in, region);
            case "DescribeLoggingOptions" -> settings.describeLoggingOptions(in, region);
            case "DescribePipeline" -> workspaces.describePipeline(in, region);
            case "DescribePipelineExecution" -> workspaces.describePipelineExecution(in, region);
            case "DescribePortal" -> monitor.describePortal(in, region);
            case "DescribeProject" -> monitor.describeProject(in, region);
            case "DescribeQuery" -> workspaces.describeQuery(in, region);
            case "DescribeSearch" -> workspaces.describeSearch(in, region);
            case "DescribeStorageConfiguration" -> settings.describeStorageConfiguration(in, region);
            case "DescribeTask" -> workspaces.describeTask(in, region);
            case "DescribeTimeSeries" -> data.describeTimeSeries(in, region);
            case "DescribeWorkspace" -> workspaces.describeWorkspace(in, region);
            case "DisassociateAssets" -> assets.disassociateAssets(in, region);
            case "DisassociateTimeSeriesFromAssetProperty" -> data.disassociateTimeSeriesFromAssetProperty(in, region);
            case "ExecuteAction" -> computations.executeAction(in, region);
            case "ExecuteQuery" -> query.executeQuery(in, region);
            case "GetAssetPropertyAggregates" -> data.getAssetPropertyAggregates(in, region);
            case "GetAssetPropertyValue" -> data.getAssetPropertyValue(in, region);
            case "GetAssetPropertyValueHistory" -> data.getAssetPropertyValueHistory(in, region);
            case "GetCaptureData" -> workspaces.getCaptureData(in, region);
            case "GetInterpolatedAssetPropertyValues" -> data.getInterpolatedAssetPropertyValues(in, region);
            case "GetQueryResults" -> workspaces.getQueryResults(in, region);
            case "GetSearchResults" -> workspaces.getSearchResults(in, region);
            case "ListAccessPolicies" -> monitor.listAccessPolicies(in, region);
            case "ListActions" -> computations.listActions(in, region);
            case "ListApplications" -> workspaces.listApplications(in, region);
            case "ListAssetModelCompositeModels" -> models.listAssetModelCompositeModels(in, region);
            case "ListAssetModelProperties" -> models.listAssetModelProperties(in, region);
            case "ListAssetModels" -> models.listAssetModels(in, region);
            case "ListAssetProperties" -> assets.listAssetProperties(in, region);
            case "ListAssetRelationships" -> assets.listAssetRelationships(in, region);
            case "ListAssets" -> assets.listAssets(in, region);
            case "ListAssociatedAssets" -> assets.listAssociatedAssets(in, region);
            case "ListBulkImportJobs" -> jobs.listBulkImportJobs(in, region);
            case "ListCompositionRelationships" -> models.listCompositionRelationships(in, region);
            case "ListComputationModelDataBindingUsages" -> computations.listComputationModelDataBindingUsages(in, region);
            case "ListComputationModelResolveToResources" -> computations.listComputationModelResolveToResources(in, region);
            case "ListComputationModels" -> computations.listComputationModels(in, region);
            case "ListDashboards" -> monitor.listDashboards(in, region);
            case "ListDatasetDataSegmentRelationships" -> workspaces.listDatasetDataSegmentRelationships(in, region);
            case "ListDatasetDataSegments" -> workspaces.listDatasetDataSegments(in, region);
            case "ListDatasetExportJobs" -> workspaces.listDatasetExportJobs(in, region);
            case "ListDatasets" -> workspaces.listDatasets(in, region);
            case "ListEnrichmentJobs" -> workspaces.listEnrichmentJobs(in, region);
            case "ListExecutions" -> computations.listExecutions(in, region);
            case "ListGateways" -> gateways.listGateways(in, region);
            case "ListInterfaceRelationships" -> models.listInterfaceRelationships(in, region);
            case "ListPipelineExecutions" -> workspaces.listPipelineExecutions(in, region);
            case "ListPipelines" -> workspaces.listPipelines(in, region);
            case "ListPortals" -> monitor.listPortals(in, region);
            case "ListProjectAssets" -> monitor.listProjectAssets(in, region);
            case "ListProjects" -> monitor.listProjects(in, region);
            case "ListQueries" -> workspaces.listQueries(in, region);
            case "ListSearches" -> workspaces.listSearches(in, region);
            case "ListTagsForResource" -> settings.listTagsForResource(in, region);
            case "ListTasks" -> workspaces.listTasks(in, region);
            case "ListTimeSeries" -> data.listTimeSeries(in, region);
            case "ListWorkspaces" -> workspaces.listWorkspaces(in, region);
            case "PutAssetModelInterfaceRelationship" -> models.putAssetModelInterfaceRelationship(in, region);
            case "PutDefaultEncryptionConfiguration" -> settings.putDefaultEncryptionConfiguration(in, region);
            case "PutLoggingOptions" -> settings.putLoggingOptions(in, region);
            case "PutStorageConfiguration" -> settings.putStorageConfiguration(in, region);
            case "StartPipelineExecution" -> workspaces.startPipelineExecution(in, region);
            case "StartQuery" -> workspaces.startQuery(in, region);
            case "StartSearch" -> workspaces.startSearch(in, region);
            case "TagResource" -> settings.tagResource(in, region);
            case "UntagResource" -> settings.untagResource(in, region);
            case "UpdateAccessPolicy" -> monitor.updateAccessPolicy(in, region);
            case "UpdateAsset" -> assets.updateAsset(in, region);
            case "UpdateAssetModel" -> models.updateAssetModel(in, region);
            case "UpdateAssetModelCompositeModel" -> models.updateAssetModelCompositeModel(in, region);
            case "UpdateAssetProperty" -> assets.updateAssetProperty(in, region);
            case "UpdateComputationModel" -> computations.updateComputationModel(in, region);
            case "UpdateDashboard" -> monitor.updateDashboard(in, region);
            case "UpdateDataset" -> workspaces.updateDataset(in, region);
            case "UpdateGateway" -> gateways.updateGateway(in, region);
            case "UpdateGatewayCapabilityConfiguration" -> gateways.updateGatewayCapabilityConfiguration(in, region);
            case "UpdatePipeline" -> workspaces.updatePipeline(in, region);
            case "UpdatePortal" -> monitor.updatePortal(in, region);
            case "UpdateProject" -> monitor.updateProject(in, region);
            case "UpdateTask" -> workspaces.updateTask(in, region);
            case "UpdateWorkspace" -> workspaces.updateWorkspace(in, region);
            default -> throw SiteWiseStore.invalid("The operation " + operation + " isn't supported.");
        };
    }
}
