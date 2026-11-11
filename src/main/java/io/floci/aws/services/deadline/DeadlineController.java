package io.floci.aws.services.deadline;

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
 * The AWS Deadline Cloud REST API, routed here by {@link DeadlineRouteFilter}. Requests are matched to operations by
 * Deadline's HTTP bindings ({@link RestJsonDispatcher}) and run by the farm, job and worker services.
 */
@Path(DeadlineRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DeadlineController {

    /** Deadline Cloud's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/deadline-request-shapes.json", "Deadline request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
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

    private final DeadlineService farms;
    private final DeadlineJobs jobs;
    private final DeadlineWorkers workers;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public DeadlineController(DeadlineService farms, DeadlineJobs jobs, DeadlineWorkers workers,
                              RegionResolver regions, ObjectMapper mapper) {
        this.farms = farms;
        this.jobs = jobs;
        this.workers = workers;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "deadline");
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
        String path = uri.getRequestUri().getRawPath().substring(DeadlineRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CreateFarm" -> farms.createFarm(input, region);
            case "GetFarm" -> farms.getFarm(input, region);
            case "UpdateFarm" -> farms.updateFarm(input, region);
            case "DeleteFarm" -> farms.deleteFarm(input, region);
            case "ListFarms" -> farms.listFarms(input, region);
            case "CreateQueue" -> farms.createQueue(input, region);
            case "GetQueue" -> farms.getQueue(input, region);
            case "UpdateQueue" -> farms.updateQueue(input, region);
            case "DeleteQueue" -> farms.deleteQueue(input, region);
            case "ListQueues" -> farms.listQueues(input, region);
            case "CreateFleet" -> farms.createFleet(input, region);
            case "GetFleet" -> farms.getFleet(input, region);
            case "UpdateFleet" -> farms.updateFleet(input, region);
            case "DeleteFleet" -> farms.deleteFleet(input, region);
            case "ListFleets" -> farms.listFleets(input, region);
            case "CreateStorageProfile" -> farms.createStorageProfile(input, region);
            case "GetStorageProfile" -> farms.getStorageProfile(input, region);
            case "GetStorageProfileForQueue" -> farms.getStorageProfileForQueue(input, region);
            case "UpdateStorageProfile" -> farms.updateStorageProfile(input, region);
            case "DeleteStorageProfile" -> farms.deleteStorageProfile(input, region);
            case "ListStorageProfiles" -> farms.listStorageProfiles(input, region);
            case "ListStorageProfilesForQueue" -> farms.listStorageProfilesForQueue(input, region);
            case "CreateBudget" -> farms.createBudget(input, region);
            case "GetBudget" -> farms.getBudget(input, region);
            case "UpdateBudget" -> farms.updateBudget(input, region);
            case "DeleteBudget" -> farms.deleteBudget(input, region);
            case "ListBudgets" -> farms.listBudgets(input, region);
            case "CreateLimit" -> farms.createLimit(input, region);
            case "GetLimit" -> farms.getLimit(input, region);
            case "UpdateLimit" -> farms.updateLimit(input, region);
            case "DeleteLimit" -> farms.deleteLimit(input, region);
            case "ListLimits" -> farms.listLimits(input, region);
            case "CreateQueueFleetAssociation" -> farms.createQueueFleetAssociation(input, region);
            case "GetQueueFleetAssociation" -> farms.getQueueFleetAssociation(input, region);
            case "UpdateQueueFleetAssociation" -> farms.updateQueueFleetAssociation(input, region);
            case "DeleteQueueFleetAssociation" -> farms.deleteQueueFleetAssociation(input, region);
            case "ListQueueFleetAssociations" -> farms.listQueueFleetAssociations(input, region);
            case "CreateQueueLimitAssociation" -> farms.createQueueLimitAssociation(input, region);
            case "GetQueueLimitAssociation" -> farms.getQueueLimitAssociation(input, region);
            case "UpdateQueueLimitAssociation" -> farms.updateQueueLimitAssociation(input, region);
            case "DeleteQueueLimitAssociation" -> farms.deleteQueueLimitAssociation(input, region);
            case "ListQueueLimitAssociations" -> farms.listQueueLimitAssociations(input, region);
            case "CreateQueueEnvironment" -> farms.createQueueEnvironment(input, region);
            case "GetQueueEnvironment" -> farms.getQueueEnvironment(input, region);
            case "UpdateQueueEnvironment" -> farms.updateQueueEnvironment(input, region);
            case "DeleteQueueEnvironment" -> farms.deleteQueueEnvironment(input, region);
            case "ListQueueEnvironments" -> farms.listQueueEnvironments(input, region);
            case "AssociateMemberToFarm" -> farms.associateMemberToFarm(input, region);
            case "DisassociateMemberFromFarm" -> farms.disassociateMemberFromFarm(input, region);
            case "ListFarmMembers" -> farms.listFarmMembers(input, region);
            case "AssociateMemberToQueue" -> farms.associateMemberToQueue(input, region);
            case "DisassociateMemberFromQueue" -> farms.disassociateMemberFromQueue(input, region);
            case "ListQueueMembers" -> farms.listQueueMembers(input, region);
            case "AssociateMemberToFleet" -> farms.associateMemberToFleet(input, region);
            case "DisassociateMemberFromFleet" -> farms.disassociateMemberFromFleet(input, region);
            case "ListFleetMembers" -> farms.listFleetMembers(input, region);
            case "AssociateMemberToJob" -> farms.associateMemberToJob(input, region);
            case "DisassociateMemberFromJob" -> farms.disassociateMemberFromJob(input, region);
            case "ListJobMembers" -> farms.listJobMembers(input, region);
            case "ListMemberships" -> farms.listMemberships(input, region);
            case "CreateMonitor" -> farms.createMonitor(input, region);
            case "GetMonitor" -> farms.getMonitor(input, region);
            case "UpdateMonitor" -> farms.updateMonitor(input, region);
            case "DeleteMonitor" -> farms.deleteMonitor(input, region);
            case "ListMonitors" -> farms.listMonitors(input, region);
            case "GetMonitorSettings" -> farms.getMonitorSettings(input, region);
            case "UpdateMonitorSettings" -> farms.updateMonitorSettings(input, region);
            case "CreateLicenseEndpoint" -> farms.createLicenseEndpoint(input, region);
            case "GetLicenseEndpoint" -> farms.getLicenseEndpoint(input, region);
            case "DeleteLicenseEndpoint" -> farms.deleteLicenseEndpoint(input, region);
            case "ListLicenseEndpoints" -> farms.listLicenseEndpoints(input, region);
            case "ListAvailableMeteredProducts" -> farms.listAvailableMeteredProducts(input, region);
            case "PutMeteredProduct" -> farms.putMeteredProduct(input, region);
            case "DeleteMeteredProduct" -> farms.deleteMeteredProduct(input, region);
            case "ListMeteredProducts" -> farms.listMeteredProducts(input, region);
            case "ListVolumes" -> farms.listVolumes(input, region);
            case "GetVolume" -> farms.getVolume(input, region);
            case "DeleteVolume" -> farms.deleteVolume(input, region);
            case "ListTagsForResource" -> farms.listTagsForResource(input, region);
            case "TagResource" -> farms.tagResource(input, region);
            case "UntagResource" -> farms.untagResource(input, region);
            case "AssumeQueueRoleForRead" -> farms.assumeQueueRoleForRead(input, region);
            case "AssumeQueueRoleForUser" -> farms.assumeQueueRoleForUser(input, region);
            case "AssumeFleetRoleForRead" -> farms.assumeFleetRoleForRead(input, region);
            case "CreateJob" -> jobs.createJob(input, region);
            case "GetJob" -> jobs.getJob(input, region);
            case "ListJobs" -> jobs.listJobs(input, region);
            case "ListJobParameterDefinitions" -> jobs.listJobParameterDefinitions(input, region);
            case "GetStep" -> jobs.getStep(input, region);
            case "ListSteps" -> jobs.listSteps(input, region);
            case "ListStepConsumers" -> jobs.listStepConsumers(input, region);
            case "ListStepDependencies" -> jobs.listStepDependencies(input, region);
            case "GetTask" -> jobs.getTask(input, region);
            case "ListTasks" -> jobs.listTasks(input, region);
            case "GetSession" -> jobs.getSession(input, region);
            case "ListSessions" -> jobs.listSessions(input, region);
            case "GetSessionAction" -> jobs.getSessionAction(input, region);
            case "ListSessionActions" -> jobs.listSessionActions(input, region);
            case "UpdateJob" -> jobs.updateJob(input, region);
            case "UpdateStep" -> jobs.updateStep(input, region);
            case "UpdateTask" -> jobs.updateTask(input, region);
            case "BatchUpdateJob" -> jobs.batchUpdateJob(input, region);
            case "BatchUpdateTask" -> jobs.batchUpdateTask(input, region);
            case "BatchGetJob" -> jobs.batchGetJob(input, region);
            case "BatchGetStep" -> jobs.batchGetStep(input, region);
            case "BatchGetTask" -> jobs.batchGetTask(input, region);
            case "BatchGetSession" -> jobs.batchGetSession(input, region);
            case "BatchGetSessionAction" -> jobs.batchGetSessionAction(input, region);
            case "SearchJobs" -> jobs.searchJobs(input, region);
            case "SearchSteps" -> jobs.searchSteps(input, region);
            case "SearchTasks" -> jobs.searchTasks(input, region);
            case "SearchWorkers" -> jobs.searchWorkers(input, region);
            case "CopyJobTemplate" -> jobs.copyJobTemplate(input, region);
            case "StartSessionsStatisticsAggregation" -> jobs.startSessionsStatisticsAggregation(input, region);
            case "GetSessionsStatisticsAggregation" -> jobs.getSessionsStatisticsAggregation(input, region);
            case "CreateWorker" -> workers.createWorker(input, region);
            case "GetWorker" -> workers.getWorker(input, region);
            case "ListWorkers" -> workers.listWorkers(input, region);
            case "BatchGetWorker" -> workers.batchGetWorker(input, region);
            case "DeleteWorker" -> workers.deleteWorker(input, region);
            case "UpdateWorker" -> workers.updateWorker(input, region);
            case "UpdateWorkerSchedule" -> workers.updateWorkerSchedule(input, region);
            case "ListSessionsForWorker" -> workers.listSessionsForWorker(input, region);
            case "UpdateSession" -> workers.updateSession(input, region);
            case "AssumeFleetRoleForWorker" -> workers.assumeFleetRoleForWorker(input, region);
            case "AssumeQueueRoleForWorker" -> workers.assumeQueueRoleForWorker(input, region);
            case "BatchGetJobEntity" -> workers.batchGetJobEntity(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
