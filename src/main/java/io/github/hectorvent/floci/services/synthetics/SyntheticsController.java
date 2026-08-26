package io.github.hectorvent.floci.services.synthetics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.synthetics.model.Canary;
import io.github.hectorvent.floci.services.synthetics.model.CanaryRun;
import io.github.hectorvent.floci.services.synthetics.model.Group;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.function.Supplier;

/**
 * CloudWatch Synthetics (Smithy restJson1).
 *
 * <p>Literal {@code /canary}, {@code /canaries}, {@code /group} and
 * {@code /groups} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyntheticsController {

    private final SyntheticsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SyntheticsController(
            SyntheticsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/canary")
    public Response createCanary(@Context HttpHeaders headers, String body) {
        return run(() -> {
            Canary canary = service.createCanary(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Canary", toCanary(canary));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/canary/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response getCanary(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Canary", toCanary(service.getCanary(region(headers), name)));
            return Response.ok(response).build();
        });
    }

    @PATCH
    @Path("/canary/{Name}")
    public Response updateCanary(
            @Context HttpHeaders headers, @PathParam("Name") String name, String body) {
        return run(() -> {
            service.updateCanary(region(headers), name, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/canary/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCanary(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> {
            service.deleteCanary(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/canaries")
    @Consumes(MediaType.WILDCARD)
    public Response describeCanaries(@Context HttpHeaders headers, String body) {
        return run(() -> {
            SyntheticsService.Page<Canary> page = service.describeCanaries(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode canaries = response.putArray("Canaries");
            for (Canary canary : page.items()) {
                canaries.add(toCanary(canary));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/canaries/last-run")
    @Consumes(MediaType.WILDCARD)
    public Response describeCanariesLastRun(@Context HttpHeaders headers, String body) {
        return run(() -> {
            SyntheticsService.Page<SyntheticsService.CanaryLastRun> page =
                    service.describeCanariesLastRun(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode lastRuns = response.putArray("CanariesLastRun");
            for (SyntheticsService.CanaryLastRun lastRun : page.items()) {
                ObjectNode item = lastRuns.addObject();
                item.put("CanaryName", lastRun.canaryName());
                item.set("LastRun", toRun(lastRun.lastRun()));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/canary/{Name}/runs")
    @Consumes(MediaType.WILDCARD)
    public Response getCanaryRuns(
            @Context HttpHeaders headers, @PathParam("Name") String name, String body) {
        return run(() -> {
            SyntheticsService.Page<CanaryRun> page =
                    service.getCanaryRuns(region(headers), name, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode runs = response.putArray("CanaryRuns");
            for (CanaryRun run : page.items()) {
                runs.add(toRun(run));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/canary/{Name}/start")
    @Consumes(MediaType.WILDCARD)
    public Response startCanary(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> {
            service.startCanary(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/canary/{Name}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopCanary(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> {
            service.stopCanary(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/runtime-versions")
    @Consumes(MediaType.WILDCARD)
    public Response describeRuntimeVersions() {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode versions = response.putArray("RuntimeVersions");
            ObjectNode version = versions.addObject();
            version.put("VersionName", "syn-nodejs-puppeteer-16.1");
            version.put("Description", "CloudWatch Synthetics Node.js Puppeteer runtime");
            version.put("ReleaseDate", InstantSeconds.now());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/group")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        return run(() -> {
            Group group = service.createGroup(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Group", toGroup(group));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/group/{GroupIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getGroup(@PathParam("GroupIdentifier") String identifier) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Group", toGroup(service.getGroup(identifier)));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/group/{GroupIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGroup(@PathParam("GroupIdentifier") String identifier) {
        return run(() -> {
            service.deleteGroup(identifier);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/groups")
    @Consumes(MediaType.WILDCARD)
    public Response listGroups(String body) {
        return run(() -> {
            SyntheticsService.Page<Group> page = service.listGroups(parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode groups = response.putArray("Groups");
            for (Group group : page.items()) {
                groups.add(toGroupSummary(group));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @PATCH
    @Path("/group/{GroupIdentifier}/associate")
    public Response associateResource(@PathParam("GroupIdentifier") String identifier, String body) {
        return run(() -> {
            service.associateResource(identifier, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @PATCH
    @Path("/group/{GroupIdentifier}/disassociate")
    public Response disassociateResource(@PathParam("GroupIdentifier") String identifier, String body) {
        return run(() -> {
            service.disassociateResource(identifier, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/group/{GroupIdentifier}/resources")
    @Consumes(MediaType.WILDCARD)
    public Response listGroupResources(@PathParam("GroupIdentifier") String identifier, String body) {
        return run(() -> {
            SyntheticsService.Page<String> page = service.listGroupResources(identifier, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode resources = response.putArray("Resources");
            for (String arn : page.items()) {
                resources.add(arn);
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/resource/{ResourceArn}/groups")
    @Consumes(MediaType.WILDCARD)
    public Response listAssociatedGroups(@PathParam("ResourceArn") String resourceArn, String body) {
        return run(() -> {
            SyntheticsService.Page<Group> page = service.listAssociatedGroups(resourceArn, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode groups = response.putArray("Groups");
            for (Group group : page.items()) {
                groups.add(toGroupSummary(group));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    private ObjectNode toCanary(Canary canary) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", canary.getId());
        node.put("Name", canary.getName());
        ObjectNode code = node.putObject("Code");
        if (canary.getHandler() != null) {
            code.put("Handler", canary.getHandler());
        }
        node.put("ExecutionRoleArn", canary.getExecutionRoleArn());
        ObjectNode schedule = node.putObject("Schedule");
        if (canary.getScheduleExpression() != null) {
            schedule.put("Expression", canary.getScheduleExpression());
        }
        if (canary.getScheduleDurationInSeconds() != null) {
            schedule.put("DurationInSeconds", canary.getScheduleDurationInSeconds());
        }
        if (canary.getTimeoutInSeconds() != null
                || canary.getMemoryInMB() != null
                || canary.getActiveTracing() != null
                || canary.getEphemeralStorage() != null) {
            ObjectNode runConfig = node.putObject("RunConfig");
            if (canary.getTimeoutInSeconds() != null) {
                runConfig.put("TimeoutInSeconds", canary.getTimeoutInSeconds());
            }
            if (canary.getMemoryInMB() != null) {
                runConfig.put("MemoryInMB", canary.getMemoryInMB());
            }
            if (canary.getActiveTracing() != null) {
                runConfig.put("ActiveTracing", canary.getActiveTracing());
            }
            if (canary.getEphemeralStorage() != null) {
                runConfig.put("EphemeralStorage", canary.getEphemeralStorage());
            }
        }
        if (canary.getSuccessRetentionPeriodInDays() != null) {
            node.put("SuccessRetentionPeriodInDays", canary.getSuccessRetentionPeriodInDays());
        }
        if (canary.getFailureRetentionPeriodInDays() != null) {
            node.put("FailureRetentionPeriodInDays", canary.getFailureRetentionPeriodInDays());
        }
        ObjectNode status = node.putObject("Status");
        status.put("State", canary.getState());
        if (canary.getStateReason() != null) {
            status.put("StateReason", canary.getStateReason());
        }
        if (canary.getStateReasonCode() != null) {
            status.put("StateReasonCode", canary.getStateReasonCode());
        }
        ObjectNode timeline = node.putObject("Timeline");
        putEpoch(timeline, "Created", canary.getCreated());
        putEpoch(timeline, "LastModified", canary.getLastModified());
        putEpoch(timeline, "LastStarted", canary.getLastStarted());
        putEpoch(timeline, "LastStopped", canary.getLastStopped());
        if (canary.getArtifactS3Location() != null) {
            node.put("ArtifactS3Location", canary.getArtifactS3Location());
        }
        if (canary.getEngineArn() != null) {
            node.put("EngineArn", canary.getEngineArn());
        }
        if (canary.getRuntimeVersion() != null) {
            node.put("RuntimeVersion", canary.getRuntimeVersion());
        }
        if (!canary.getSubnetIds().isEmpty() || !canary.getSecurityGroupIds().isEmpty()) {
            ObjectNode vpc = node.putObject("VpcConfig");
            ArrayNode subnets = vpc.putArray("SubnetIds");
            canary.getSubnetIds().forEach(subnets::add);
            ArrayNode groups = vpc.putArray("SecurityGroupIds");
            canary.getSecurityGroupIds().forEach(groups::add);
        }
        if (canary.getProvisionedResourceCleanup() != null) {
            node.put("ProvisionedResourceCleanup", canary.getProvisionedResourceCleanup());
        }
        ObjectNode tags = node.putObject("Tags");
        for (Map.Entry<String, String> entry : canary.getTags().entrySet()) {
            tags.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private ObjectNode toRun(CanaryRun run) {
        ObjectNode node = objectMapper.createObjectNode();
        if (run.getId() != null) {
            node.put("Id", run.getId());
        }
        if (run.getName() != null) {
            node.put("Name", run.getName());
        }
        ObjectNode status = node.putObject("Status");
        if (run.getState() != null) {
            status.put("State", run.getState());
        }
        if (run.getTestResult() != null) {
            status.put("TestResult", run.getTestResult());
        }
        ObjectNode timeline = node.putObject("Timeline");
        putEpoch(timeline, "Started", run.getStarted());
        putEpoch(timeline, "Completed", run.getCompleted());
        if (run.getArtifactS3Location() != null) {
            node.put("ArtifactS3Location", run.getArtifactS3Location());
        }
        return node;
    }

    private ObjectNode toGroup(Group group) {
        ObjectNode node = toGroupSummary(group);
        putEpoch(node, "CreatedTime", group.getCreatedTime());
        putEpoch(node, "LastModifiedTime", group.getLastModifiedTime());
        ObjectNode tags = node.putObject("Tags");
        for (Map.Entry<String, String> entry : group.getTags().entrySet()) {
            tags.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private ObjectNode toGroupSummary(Group group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", group.getId());
        node.put("Name", group.getName());
        node.put("Arn", group.getArn());
        return node;
    }

    private static void putEpoch(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
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

    private static final class InstantSeconds {
        private InstantSeconds() {
        }

        static long now() {
            return java.time.Instant.now().getEpochSecond();
        }
    }
}
