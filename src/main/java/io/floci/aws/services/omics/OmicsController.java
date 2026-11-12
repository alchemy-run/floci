package io.floci.aws.services.omics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The AWS HealthOmics REST API, routed here by {@link OmicsRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(OmicsRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class OmicsController {

    /** HealthOmics' request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/omics-request-shapes.json", "HealthOmics request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return OmicsStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return OmicsStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return OmicsStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return OmicsStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return OmicsStore.validation(path + " is required.");
                }
            });

    private final OmicsStorage storage;
    private final OmicsAnalytics analytics;
    private final OmicsWorkflows workflows;
    private final OmicsCommon common;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public OmicsController(OmicsStorage storage, OmicsAnalytics analytics, OmicsWorkflows workflows,
                           OmicsCommon common, RegionResolver regions, ObjectMapper mapper) {
        this.storage = storage;
        this.analytics = analytics;
        this.workflows = workflows;
        this.common = common;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "omics");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(OmicsRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "AbortMultipartReadSetUpload" -> storage.abortMultipartReadSetUpload(input, region);
            case "AcceptShare" -> analytics.acceptShare(input, region);
            case "BatchDeleteReadSet" -> storage.batchDeleteReadSet(input, region);
            case "CancelAnnotationImportJob" -> analytics.cancelAnnotationImportJob(input, region);
            case "CancelRun" -> workflows.cancelRun(input, region);
            case "CancelRunBatch" -> workflows.cancelRunBatch(input, region);
            case "CancelVariantImportJob" -> analytics.cancelVariantImportJob(input, region);
            case "CompleteMultipartReadSetUpload" -> storage.completeMultipartReadSetUpload(input, region);
            case "CreateAnnotationStore" -> analytics.createAnnotationStore(input, region);
            case "CreateAnnotationStoreVersion" -> analytics.createAnnotationStoreVersion(input, region);
            case "CreateConfiguration" -> workflows.createConfiguration(input, region);
            case "CreateMultipartReadSetUpload" -> storage.createMultipartReadSetUpload(input, region);
            case "CreateReferenceStore" -> storage.createReferenceStore(input, region);
            case "CreateRunCache" -> workflows.createRunCache(input, region);
            case "CreateRunGroup" -> workflows.createRunGroup(input, region);
            case "CreateSequenceStore" -> storage.createSequenceStore(input, region);
            case "CreateShare" -> analytics.createShare(input, region);
            case "CreateVariantStore" -> analytics.createVariantStore(input, region);
            case "CreateWorkflow" -> workflows.createWorkflow(input, region);
            case "CreateWorkflowVersion" -> workflows.createWorkflowVersion(input, region);
            case "DeleteAnnotationStore" -> analytics.deleteAnnotationStore(input, region);
            case "DeleteAnnotationStoreVersions" -> analytics.deleteAnnotationStoreVersions(input, region);
            case "DeleteBatch" -> workflows.deleteBatch(input, region);
            case "DeleteConfiguration" -> workflows.deleteConfiguration(input, region);
            case "DeleteReference" -> storage.deleteReference(input, region);
            case "DeleteReferenceStore" -> storage.deleteReferenceStore(input, region);
            case "DeleteRun" -> workflows.deleteRun(input, region);
            case "DeleteRunBatch" -> workflows.deleteRunBatch(input, region);
            case "DeleteRunCache" -> workflows.deleteRunCache(input, region);
            case "DeleteRunGroup" -> workflows.deleteRunGroup(input, region);
            case "DeleteS3AccessPolicy" -> storage.deleteS3AccessPolicy(input, region);
            case "DeleteSequenceStore" -> storage.deleteSequenceStore(input, region);
            case "DeleteShare" -> analytics.deleteShare(input, region);
            case "DeleteVariantStore" -> analytics.deleteVariantStore(input, region);
            case "DeleteWorkflow" -> workflows.deleteWorkflow(input, region);
            case "DeleteWorkflowVersion" -> workflows.deleteWorkflowVersion(input, region);
            case "GetAnnotationImportJob" -> analytics.getAnnotationImportJob(input, region);
            case "GetAnnotationStore" -> analytics.getAnnotationStore(input, region);
            case "GetAnnotationStoreVersion" -> analytics.getAnnotationStoreVersion(input, region);
            case "GetBatch" -> workflows.getBatch(input, region);
            case "GetConfiguration" -> workflows.getConfiguration(input, region);
            case "GetReadSet" -> storage.getReadSet(input, region);
            case "GetReadSetActivationJob" -> storage.getReadSetActivationJob(input, region);
            case "GetReadSetExportJob" -> storage.getReadSetExportJob(input, region);
            case "GetReadSetImportJob" -> storage.getReadSetImportJob(input, region);
            case "GetReadSetMetadata" -> storage.getReadSetMetadata(input, region);
            case "GetReference" -> storage.getReference(input, region);
            case "GetReferenceImportJob" -> storage.getReferenceImportJob(input, region);
            case "GetReferenceMetadata" -> storage.getReferenceMetadata(input, region);
            case "GetReferenceStore" -> storage.getReferenceStore(input, region);
            case "GetRun" -> workflows.getRun(input, region);
            case "GetRunCache" -> workflows.getRunCache(input, region);
            case "GetRunGroup" -> workflows.getRunGroup(input, region);
            case "GetRunTask" -> workflows.getRunTask(input, region);
            case "GetS3AccessPolicy" -> storage.getS3AccessPolicy(input, region);
            case "GetSequenceStore" -> storage.getSequenceStore(input, region);
            case "GetShare" -> analytics.getShare(input, region);
            case "GetVariantImportJob" -> analytics.getVariantImportJob(input, region);
            case "GetVariantStore" -> analytics.getVariantStore(input, region);
            case "GetWorkflow" -> workflows.getWorkflow(input, region);
            case "GetWorkflowVersion" -> workflows.getWorkflowVersion(input, region);
            case "ListAnnotationImportJobs" -> analytics.listAnnotationImportJobs(input, region);
            case "ListAnnotationStoreVersions" -> analytics.listAnnotationStoreVersions(input, region);
            case "ListAnnotationStores" -> analytics.listAnnotationStores(input, region);
            case "ListBatch" -> workflows.listBatch(input, region);
            case "ListConfigurations" -> workflows.listConfigurations(input, region);
            case "ListMultipartReadSetUploads" -> storage.listMultipartReadSetUploads(input, region);
            case "ListReadSetActivationJobs" -> storage.listReadSetActivationJobs(input, region);
            case "ListReadSetExportJobs" -> storage.listReadSetExportJobs(input, region);
            case "ListReadSetImportJobs" -> storage.listReadSetImportJobs(input, region);
            case "ListReadSetUploadParts" -> storage.listReadSetUploadParts(input, region);
            case "ListReadSets" -> storage.listReadSets(input, region);
            case "ListReferenceImportJobs" -> storage.listReferenceImportJobs(input, region);
            case "ListReferenceStores" -> storage.listReferenceStores(input, region);
            case "ListReferences" -> storage.listReferences(input, region);
            case "ListRunCaches" -> workflows.listRunCaches(input, region);
            case "ListRunGroups" -> workflows.listRunGroups(input, region);
            case "ListRunTasks" -> workflows.listRunTasks(input, region);
            case "ListRuns" -> workflows.listRuns(input, region);
            case "ListRunsInBatch" -> workflows.listRunsInBatch(input, region);
            case "ListSequenceStores" -> storage.listSequenceStores(input, region);
            case "ListShares" -> analytics.listShares(input, region);
            case "ListTagsForResource" -> common.listTagsForResource(input);
            case "ListVariantImportJobs" -> analytics.listVariantImportJobs(input, region);
            case "ListVariantStores" -> analytics.listVariantStores(input, region);
            case "ListWorkflowVersions" -> workflows.listWorkflowVersions(input, region);
            case "ListWorkflows" -> workflows.listWorkflows(input, region);
            case "PutS3AccessPolicy" -> storage.putS3AccessPolicy(input, region);
            case "StartAnnotationImportJob" -> analytics.startAnnotationImportJob(input, region);
            case "StartReadSetActivationJob" -> storage.startReadSetActivationJob(input, region);
            case "StartReadSetExportJob" -> storage.startReadSetExportJob(input, region);
            case "StartReadSetImportJob" -> storage.startReadSetImportJob(input, region);
            case "StartReferenceImportJob" -> storage.startReferenceImportJob(input, region);
            case "StartRun" -> workflows.startRun(input, region);
            case "StartRunBatch" -> workflows.startRunBatch(input, region);
            case "StartVariantImportJob" -> analytics.startVariantImportJob(input, region);
            case "TagResource" -> common.tagResource(input);
            case "UntagResource" -> common.untagResource(input);
            case "UpdateAnnotationStore" -> analytics.updateAnnotationStore(input, region);
            case "UpdateAnnotationStoreVersion" -> analytics.updateAnnotationStoreVersion(input, region);
            case "UpdateRunCache" -> workflows.updateRunCache(input, region);
            case "UpdateRunGroup" -> workflows.updateRunGroup(input, region);
            case "UpdateSequenceStore" -> storage.updateSequenceStore(input, region);
            case "UpdateVariantStore" -> analytics.updateVariantStore(input, region);
            case "UpdateWorkflow" -> workflows.updateWorkflow(input, region);
            case "UpdateWorkflowVersion" -> workflows.updateWorkflowVersion(input, region);
            case "UploadReadSetPart" -> storage.uploadReadSetPart(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
