package io.github.hectorvent.floci.services.deadline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.deadline.model.DeadlineAggregation;
import io.github.hectorvent.floci.services.deadline.model.DeadlineBudget;
import io.github.hectorvent.floci.services.deadline.model.DeadlineBudget.BudgetAction;
import io.github.hectorvent.floci.services.deadline.model.DeadlineFarm;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob.DeadlineStep;
import io.github.hectorvent.floci.services.deadline.model.DeadlineJob.DeadlineTask;
import io.github.hectorvent.floci.services.deadline.model.DeadlineQueue;
import io.github.hectorvent.floci.services.deadline.model.DeadlineStorageProfile;
import io.github.hectorvent.floci.services.deadline.model.DeadlineStorageProfile.FileSystemLocation;
import io.github.hectorvent.floci.services.deadline.model.Monitor;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * AWS Deadline Cloud restJson1. SigV4 {@code deadline} paths are rewritten onto
 * {@link DeadlineRoutingFilter#INTERNAL_PREFIX} so they do not fall through to S3.
 */
@Path(DeadlineRoutingFilter.INTERNAL_PREFIX + "/2023-10-12")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeadlineController {

    private final DeadlineService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DeadlineController(
            DeadlineService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/farms")
    public Response createFarm(
            @Context HttpHeaders headers,
            @HeaderParam("X-Amz-Client-Token") String clientToken,
            String body) {
        DeadlineFarm farm = service.createFarm(region(headers), parse(body), clientToken);
        return idResponse("farmId", farm.getFarmId());
    }

    @GET
    @Path("/farms")
    @Consumes(MediaType.WILDCARD)
    public Response listFarms(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode farms = response.putArray("farms");
        for (DeadlineFarm farm : service.listFarms(region(headers))) {
            farms.add(toFarmSummary(farm));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}")
    @Consumes(MediaType.WILDCARD)
    public Response getFarm(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        return Response.ok(toFarm(service.getFarm(region(headers), farmId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}")
    public Response updateFarm(@Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        service.updateFarm(region(headers), farmId, parse(body));
        return empty();
    }

    @DELETE
    @Path("/farms/{farmId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFarm(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        service.deleteFarm(region(headers), farmId);
        return empty();
    }

    @POST
    @Path("/farms/{farmId}/queues")
    public Response createQueue(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @HeaderParam("X-Amz-Client-Token") String clientToken,
            String body) {
        DeadlineQueue queue = service.createQueue(region(headers), farmId, parse(body), clientToken);
        return idResponse("queueId", queue.getQueueId());
    }

    @GET
    @Path("/farms/{farmId}/queues")
    @Consumes(MediaType.WILDCARD)
    public Response listQueues(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode queues = response.putArray("queues");
        for (DeadlineQueue queue : service.listQueues(region(headers), farmId)) {
            queues.add(toQueueSummary(queue));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}")
    @Consumes(MediaType.WILDCARD)
    public Response getQueue(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId) {
        return Response.ok(toQueue(service.getQueue(region(headers), farmId, queueId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/queues/{queueId}")
    public Response updateQueue(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            String body) {
        service.updateQueue(region(headers), farmId, queueId, parse(body));
        return empty();
    }

    @DELETE
    @Path("/farms/{farmId}/queues/{queueId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteQueue(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId) {
        service.deleteQueue(region(headers), farmId, queueId);
        return empty();
    }

    @POST
    @Path("/farms/{farmId}/storage-profiles")
    public Response createStorageProfile(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        DeadlineStorageProfile profile = service.createStorageProfile(region(headers), farmId, parse(body));
        return idResponse("storageProfileId", profile.getStorageProfileId());
    }

    @GET
    @Path("/farms/{farmId}/storage-profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listStorageProfiles(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode profiles = response.putArray("storageProfiles");
        for (DeadlineStorageProfile profile : service.listStorageProfiles(region(headers), farmId)) {
            profiles.add(toStorageProfileSummary(profile));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/storage-profiles/{storageProfileId}")
    @Consumes(MediaType.WILDCARD)
    public Response getStorageProfile(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("storageProfileId") String storageProfileId) {
        return Response.ok(toStorageProfile(
                service.getStorageProfile(region(headers), farmId, storageProfileId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/storage-profiles/{storageProfileId}")
    public Response updateStorageProfile(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("storageProfileId") String storageProfileId,
            String body) {
        service.updateStorageProfile(region(headers), farmId, storageProfileId, parse(body));
        return empty();
    }

    @DELETE
    @Path("/farms/{farmId}/storage-profiles/{storageProfileId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteStorageProfile(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("storageProfileId") String storageProfileId) {
        service.deleteStorageProfile(region(headers), farmId, storageProfileId);
        return empty();
    }

    @POST
    @Path("/farms/{farmId}/budgets")
    public Response createBudget(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        DeadlineBudget budget = service.createBudget(region(headers), farmId, parse(body));
        return idResponse("budgetId", budget.getBudgetId());
    }

    @GET
    @Path("/farms/{farmId}/budgets")
    @Consumes(MediaType.WILDCARD)
    public Response listBudgets(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode budgets = response.putArray("budgets");
        for (DeadlineBudget budget : service.listBudgets(region(headers), farmId)) {
            budgets.add(toBudgetSummary(budget));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/budgets/{budgetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getBudget(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("budgetId") String budgetId) {
        return Response.ok(toBudget(service.getBudget(region(headers), farmId, budgetId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/budgets/{budgetId}")
    public Response updateBudget(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("budgetId") String budgetId,
            String body) {
        service.updateBudget(region(headers), farmId, budgetId, parse(body));
        return empty();
    }

    @DELETE
    @Path("/farms/{farmId}/budgets/{budgetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBudget(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("budgetId") String budgetId) {
        service.deleteBudget(region(headers), farmId, budgetId);
        return empty();
    }

    @GET
    @Path("/farms/{farmId}/fleets")
    @Consumes(MediaType.WILDCARD)
    public Response listFleets(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        service.getFarm(region(headers), farmId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("fleets");
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/limits")
    @Consumes(MediaType.WILDCARD)
    public Response listLimits(@Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        service.getFarm(region(headers), farmId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("limits");
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queue-fleet-associations")
    @Consumes(MediaType.WILDCARD)
    public Response listQueueFleetAssociations(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        service.getFarm(region(headers), farmId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("queueFleetAssociations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queue-limit-associations")
    @Consumes(MediaType.WILDCARD)
    public Response listQueueLimitAssociations(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId) {
        service.getFarm(region(headers), farmId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("queueLimitAssociations");
        return Response.ok(response).build();
    }

    @POST
    @Path("/farms/{farmId}/queues/{queueId}/jobs")
    public Response createJob(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @HeaderParam("X-Amz-Client-Token") String clientToken,
            String body) {
        DeadlineJob job = service.createJob(region(headers), farmId, queueId, parse(body), clientToken);
        return idResponse("jobId", job.getJobId());
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        for (DeadlineJob job : service.listJobs(region(headers), farmId, queueId)) {
            jobs.add(toJobSummary(job));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getJob(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId) {
        return Response.ok(toJob(service.getJob(region(headers), farmId, queueId, jobId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}")
    public Response updateJob(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            String body) {
        service.updateJob(region(headers), farmId, queueId, jobId, parse(body));
        return empty();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps")
    @Consumes(MediaType.WILDCARD)
    public Response listSteps(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode steps = response.putArray("steps");
        for (DeadlineStep step : service.listSteps(region(headers), farmId, queueId, jobId)) {
            steps.add(toStep(step));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps/{stepId}")
    @Consumes(MediaType.WILDCARD)
    public Response getStep(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            @PathParam("stepId") String stepId) {
        return Response.ok(toStep(service.getStep(region(headers), farmId, queueId, jobId, stepId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps/{stepId}")
    public Response updateStep(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            @PathParam("stepId") String stepId,
            String body) {
        service.updateStep(region(headers), farmId, queueId, jobId, stepId, parse(body));
        return empty();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps/{stepId}/tasks")
    @Consumes(MediaType.WILDCARD)
    public Response listTasks(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            @PathParam("stepId") String stepId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tasks = response.putArray("tasks");
        for (DeadlineTask task : service.listTasks(region(headers), farmId, queueId, jobId, stepId)) {
            tasks.add(toTask(task));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps/{stepId}/tasks/{taskId}")
    @Consumes(MediaType.WILDCARD)
    public Response getTask(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            @PathParam("stepId") String stepId,
            @PathParam("taskId") String taskId) {
        return Response.ok(toTask(
                service.getTask(region(headers), farmId, queueId, jobId, stepId, taskId))).build();
    }

    @PATCH
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/steps/{stepId}/tasks/{taskId}")
    public Response updateTask(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId,
            @PathParam("stepId") String stepId,
            @PathParam("taskId") String taskId,
            String body) {
        service.updateTask(region(headers), farmId, queueId, jobId, stepId, taskId, parse(body));
        return empty();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/parameter-definitions")
    @Consumes(MediaType.WILDCARD)
    public Response listJobParameterDefinitions(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId) {
        service.getJob(region(headers), farmId, queueId, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("jobParameterDefinitions");
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/sessions")
    @Consumes(MediaType.WILDCARD)
    public Response listSessions(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId) {
        service.getJob(region(headers), farmId, queueId, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("sessions");
        return Response.ok(response).build();
    }

    @GET
    @Path("/farms/{farmId}/queues/{queueId}/jobs/{jobId}/session-actions")
    @Consumes(MediaType.WILDCARD)
    public Response listSessionActions(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @PathParam("queueId") String queueId,
            @PathParam("jobId") String jobId) {
        service.getJob(region(headers), farmId, queueId, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("sessionActions");
        return Response.ok(response).build();
    }

    @POST
    @Path("/farms/{farmId}/search/jobs")
    public Response searchJobs(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        List<DeadlineJob> jobs = service.searchJobs(region(headers), farmId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("jobs");
        for (DeadlineJob job : jobs) {
            array.add(toJobSummary(job));
        }
        response.put("nextItemOffset", jobs.size());
        response.put("totalResults", jobs.size());
        return Response.ok(response).build();
    }

    @POST
    @Path("/farms/{farmId}/search/steps")
    public Response searchSteps(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        List<DeadlineStep> steps = service.searchSteps(region(headers), farmId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("steps");
        for (DeadlineStep step : steps) {
            array.add(toStep(step));
        }
        response.put("totalResults", steps.size());
        return Response.ok(response).build();
    }

    @POST
    @Path("/farms/{farmId}/search/tasks")
    public Response searchTasks(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        List<DeadlineService.TaskHit> hits = service.searchTasks(region(headers), farmId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("tasks");
        for (DeadlineService.TaskHit hit : hits) {
            ObjectNode node = toTask(hit.task());
            node.put("jobId", hit.job().getJobId());
            node.put("stepId", hit.step().getStepId());
            node.put("queueId", hit.job().getQueueId());
            array.add(node);
        }
        response.put("totalResults", hits.size());
        return Response.ok(response).build();
    }

    @POST
    @Path("/farms/{farmId}/sessions-statistics-aggregation")
    public Response startAggregation(
            @Context HttpHeaders headers, @PathParam("farmId") String farmId, String body) {
        DeadlineAggregation aggregation = service.startAggregation(region(headers), farmId, parse(body));
        return idResponse("aggregationId", aggregation.getAggregationId());
    }

    @GET
    @Path("/farms/{farmId}/sessions-statistics-aggregation")
    @Consumes(MediaType.WILDCARD)
    public Response getAggregation(
            @Context HttpHeaders headers,
            @PathParam("farmId") String farmId,
            @QueryParam("aggregationId") String aggregationId) {
        DeadlineAggregation aggregation = service.getAggregation(region(headers), farmId, aggregationId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", aggregation.getStatus());
        response.putArray("statistics");
        return Response.ok(response).build();
    }

    @GET
    @Path("/monitors")
    @Consumes(MediaType.WILDCARD)
    public Response listMonitors(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode monitors = response.putArray("monitors");
        for (Monitor monitor : service.listMonitors(region(headers))) {
            ObjectNode node = monitors.addObject();
            node.put("monitorId", monitor.getMonitorId());
            node.put("displayName", monitor.getDisplayName());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/monitors/{monitorId}")
    @Consumes(MediaType.WILDCARD)
    public Response getMonitor(@Context HttpHeaders headers, @PathParam("monitorId") String monitorId) {
        Monitor monitor = service.getMonitor(region(headers), monitorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("monitorId", monitor.getMonitorId());
        response.put("displayName", monitor.getDisplayName());
        response.put("subdomain", monitor.getSubdomain());
        response.put("url", monitor.getUrl());
        response.put("roleArn", monitor.getRoleArn());
        response.put("identityCenterInstanceArn", monitor.getIdentityCenterInstanceArn());
        response.put("identityCenterApplicationArn", monitor.getIdentityCenterApplicationArn());
        response.put("createdAt", monitor.getCreatedAt());
        response.put("createdBy", monitor.getCreatedBy());
        return Response.ok(response).build();
    }

    @GET
    @Path("/tags/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response listTags(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tags = response.putObject("tags");
        for (Map.Entry<String, String> entry : service.listTags(region(headers), resourceArn).entrySet()) {
            tags.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/tags/{resourceArn: .+}")
    public Response tagResource(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn, String body) {
        service.tagResource(region(headers), resourceArn, parse(body));
        return empty();
    }

    @DELETE
    @Path("/tags/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(
            @Context HttpHeaders headers,
            @PathParam("resourceArn") String resourceArn,
            @QueryParam("tagKeys") List<String> tagKeys) {
        service.untagResource(region(headers), resourceArn, tagKeys);
        return empty();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response idResponse(String field, String value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put(field, value);
        return Response.ok(response).build();
    }

    private ObjectNode toFarm(DeadlineFarm farm) {
        ObjectNode response = toFarmSummary(farm);
        response.put("costScaleFactor", farm.getCostScaleFactor());
        putOptional(response, "description", farm.getDescription());
        return response;
    }

    private ObjectNode toFarmSummary(DeadlineFarm farm) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("farmId", farm.getFarmId());
        response.put("displayName", farm.getDisplayName());
        putOptional(response, "kmsKeyArn", farm.getKmsKeyArn());
        response.put("createdAt", farm.getCreatedAt());
        response.put("createdBy", farm.getCreatedBy());
        putOptional(response, "updatedAt", farm.getUpdatedAt());
        putOptional(response, "updatedBy", farm.getUpdatedBy());
        return response;
    }

    private ObjectNode toQueue(DeadlineQueue queue) {
        ObjectNode response = toQueueSummary(queue);
        putOptional(response, "description", queue.getDescription());
        putOptional(response, "roleArn", queue.getRoleArn());
        ArrayNode allowed = response.putArray("allowedStorageProfileIds");
        queue.getAllowedStorageProfileIds().forEach(allowed::add);
        ArrayNode required = response.putArray("requiredFileSystemLocationNames");
        queue.getRequiredFileSystemLocationNames().forEach(required::add);
        if (queue.getJobRunAsUser() != null) {
            response.set("jobRunAsUser", queue.getJobRunAsUser());
        }
        if (queue.getJobAttachmentSettings() != null) {
            response.set("jobAttachmentSettings", queue.getJobAttachmentSettings());
        }
        if (queue.getSchedulingConfiguration() != null) {
            response.set("schedulingConfiguration", queue.getSchedulingConfiguration());
        }
        return response;
    }

    private ObjectNode toQueueSummary(DeadlineQueue queue) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("farmId", queue.getFarmId());
        response.put("queueId", queue.getQueueId());
        response.put("displayName", queue.getDisplayName());
        response.put("status", queue.getStatus());
        response.put("defaultBudgetAction", queue.getDefaultBudgetAction());
        response.put("createdAt", queue.getCreatedAt());
        response.put("createdBy", queue.getCreatedBy());
        putOptional(response, "updatedAt", queue.getUpdatedAt());
        putOptional(response, "updatedBy", queue.getUpdatedBy());
        return response;
    }

    private ObjectNode toStorageProfile(DeadlineStorageProfile profile) {
        ObjectNode response = toStorageProfileSummary(profile);
        response.put("createdAt", profile.getCreatedAt());
        response.put("createdBy", profile.getCreatedBy());
        putOptional(response, "updatedAt", profile.getUpdatedAt());
        putOptional(response, "updatedBy", profile.getUpdatedBy());
        ArrayNode locations = response.putArray("fileSystemLocations");
        for (FileSystemLocation location : profile.getFileSystemLocations()) {
            ObjectNode node = locations.addObject();
            node.put("name", location.getName());
            node.put("path", location.getPath());
            node.put("type", location.getType());
        }
        return response;
    }

    private ObjectNode toStorageProfileSummary(DeadlineStorageProfile profile) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("storageProfileId", profile.getStorageProfileId());
        response.put("displayName", profile.getDisplayName());
        response.put("osFamily", profile.getOsFamily());
        return response;
    }

    private ObjectNode toBudget(DeadlineBudget budget) {
        ObjectNode response = toBudgetSummary(budget);
        putOptional(response, "description", budget.getDescription());
        ArrayNode actions = response.putArray("actions");
        for (BudgetAction action : budget.getActions()) {
            ObjectNode node = actions.addObject();
            node.put("type", action.getType());
            node.put("thresholdPercentage", action.getThresholdPercentage());
            putOptional(node, "description", action.getDescription());
        }
        ObjectNode schedule = response.putObject("schedule");
        ObjectNode fixed = schedule.putObject("fixed");
        fixed.put("startTime", budget.getStartTime());
        fixed.put("endTime", budget.getEndTime());
        return response;
    }

    private ObjectNode toBudgetSummary(DeadlineBudget budget) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("budgetId", budget.getBudgetId());
        response.putObject("usageTrackingResource").put("queueId", budget.getQueueId());
        response.put("status", budget.getStatus());
        response.put("displayName", budget.getDisplayName());
        response.put("approximateDollarLimit", budget.getApproximateDollarLimit());
        response.putObject("usages").put("approximateDollarUsage", budget.getApproximateDollarUsage());
        response.put("createdBy", budget.getCreatedBy());
        response.put("createdAt", budget.getCreatedAt());
        putOptional(response, "updatedBy", budget.getUpdatedBy());
        putOptional(response, "updatedAt", budget.getUpdatedAt());
        return response;
    }

    private ObjectNode toJob(DeadlineJob job) {
        ObjectNode response = toJobSummary(job);
        response.put("farmId", job.getFarmId());
        response.put("queueId", job.getQueueId());
        response.put("taskRunStatus", job.getTaskRunStatus());
        putOptional(response, "description", job.getDescription());
        putOptional(response, "lifecycleStatusMessage", job.getLifecycleStatusMessage());
        putOptional(response, "updatedAt", job.getUpdatedAt());
        putOptional(response, "updatedBy", job.getUpdatedBy());
        return response;
    }

    private ObjectNode toJobSummary(DeadlineJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        response.put("name", job.getName());
        response.put("priority", job.getPriority());
        response.put("lifecycleStatus", job.getLifecycleStatus());
        if (job.getLifecycleStatusMessage() != null) {
            response.put("lifecycleStatusMessage", job.getLifecycleStatusMessage());
        }
        response.put("queueId", job.getQueueId());
        response.put("createdAt", job.getCreatedAt());
        response.put("createdBy", job.getCreatedBy());
        return response;
    }

    private ObjectNode toStep(DeadlineStep step) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stepId", step.getStepId());
        response.put("name", step.getName());
        response.put("lifecycleStatus", step.getLifecycleStatus());
        response.put("taskRunStatus", step.getTaskRunStatus());
        ObjectNode counts = response.putObject("taskRunStatusCounts");
        java.util.Map<String, Integer> tally = new java.util.LinkedHashMap<>();
        for (DeadlineTask task : step.getTasks()) {
            tally.merge(task.getRunStatus(), 1, Integer::sum);
        }
        tally.forEach(counts::put);
        response.put("createdAt", step.getCreatedAt());
        response.put("createdBy", step.getCreatedBy());
        putOptional(response, "updatedAt", step.getUpdatedAt());
        putOptional(response, "updatedBy", step.getUpdatedBy());
        return response;
    }

    private ObjectNode toTask(DeadlineTask task) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("taskId", task.getTaskId());
        response.put("runStatus", task.getRunStatus());
        response.put("createdAt", task.getCreatedAt());
        response.put("createdBy", task.getCreatedBy());
        putOptional(response, "targetRunStatus", task.getTargetRunStatus());
        putOptional(response, "updatedAt", task.getUpdatedAt());
        putOptional(response, "updatedBy", task.getUpdatedBy());
        return response;
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
