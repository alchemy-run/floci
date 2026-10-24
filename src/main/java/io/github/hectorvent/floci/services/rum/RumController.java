package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import io.github.hectorvent.floci.services.rum.model.RumMetricDefinition;
import io.github.hectorvent.floci.services.rum.model.RumMetricsDestination;
import io.github.hectorvent.floci.services.rum.model.RumResourcePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * CloudWatch RUM (Smithy restJson1): app monitors, extended-metrics destinations and metric
 * definitions, resource-based policies, and the PutRumEvents / GetAppMonitorData telemetry path.
 * Tagging is served on {@code /tags/{ResourceArn}} through {@link RumTagHandler}.
 *
 * <p>The literal {@code /appmonitor}, {@code /appmonitors} and {@code /rummetrics} paths take JAX-RS
 * precedence over S3's {@code /{bucket}} and {@code /{bucket}/{key}} template routes, so these routes
 * win with no extra routing wiring. A RUM path without a route here falls through to S3 and answers
 * with an S3 XML error the restJson1 SDK cannot parse.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RumController {

    private final RumService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RumController(RumService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
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

    @POST
    @Path("/appmonitor")
    public Response createAppMonitor(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        AppMonitor monitor = service.createAppMonitor(regionResolver.resolveRegion(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", monitor.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/appmonitor/{name}")
    public Response getAppMonitor(@Context HttpHeaders headers, @PathParam("name") String name) {
        AppMonitor monitor = service.getAppMonitor(regionResolver.resolveRegion(headers), name);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AppMonitor", objectMapper.valueToTree(monitor));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/appmonitor/{name}")
    public Response updateAppMonitor(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        JsonNode request = parse(body);
        service.updateAppMonitor(regionResolver.resolveRegion(headers), name, request);
        return Response.ok().build();
    }

    @DELETE
    @Path("/appmonitor/{name}")
    public Response deleteAppMonitor(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deleteAppMonitor(regionResolver.resolveRegion(headers), name);
        return Response.ok().build();
    }

    @POST
    @Path("/appmonitors")
    @Consumes(MediaType.WILDCARD)
    public Response listAppMonitors(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        RumService.Page page = service.listAppMonitors(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        var summaries = response.putArray("AppMonitorSummaries");
        for (AppMonitor monitor : page.monitors()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("Created", monitor.getCreated());
            summary.put("Id", monitor.getId());
            summary.put("LastModified", monitor.getLastModified());
            summary.put("Name", monitor.getName());
            summary.put("Platform", monitor.getPlatform());
            summary.put("State", monitor.getState());
            summaries.add(summary);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // Data plane (dataplane.rum.<region>.amazonaws.com in AWS). The SDK sends
    // POST /appmonitors/{Id}/; the trailing slash is tolerated by the route matcher.
    @POST
    @Path("/appmonitors/{id}")
    public Response putRumEvents(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        service.putRumEvents(regionResolver.resolveRegion(headers), id, parse(body));
        return Response.ok().build();
    }

    @POST
    @Path("/appmonitor/{name}/data")
    public Response getAppMonitorData(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        RumService.Slice<String> page = service.getAppMonitorData(
                regionResolver.resolveRegion(headers), name, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        var events = response.putArray("Events");
        page.items().forEach(events::add);
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @PUT
    @Path("/appmonitor/{name}/policy")
    public Response putResourcePolicy(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        RumResourcePolicy policy = service.putResourcePolicy(regionResolver.resolveRegion(headers), name, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyDocument", policy.getPolicyDocument());
        response.put("PolicyRevisionId", policy.getPolicyRevisionId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/appmonitor/{name}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePolicy(@Context HttpHeaders headers, @PathParam("name") String name) {
        RumResourcePolicy policy = service.getResourcePolicy(regionResolver.resolveRegion(headers), name);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyDocument", policy.getPolicyDocument());
        response.put("PolicyRevisionId", policy.getPolicyRevisionId());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/appmonitor/{name}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourcePolicy(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("policyRevisionId") String policyRevisionId) {
        RumResourcePolicy deleted = service.deleteResourcePolicy(
                regionResolver.resolveRegion(headers), name, policyRevisionId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyRevisionId", deleted.getPolicyRevisionId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/rummetrics/{name}/metricsdestination")
    public Response putRumMetricsDestination(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        service.putRumMetricsDestination(regionResolver.resolveRegion(headers), name, parse(body));
        return Response.ok().build();
    }

    @GET
    @Path("/rummetrics/{name}/metricsdestination")
    @Consumes(MediaType.WILDCARD)
    public Response listRumMetricsDestinations(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        RumService.Slice<RumMetricsDestination> page = service.listRumMetricsDestinations(
                regionResolver.resolveRegion(headers), name, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        var destinations = response.putArray("Destinations");
        for (RumMetricsDestination destination : page.items()) {
            ObjectNode summary = destinations.addObject();
            summary.put("Destination", destination.getDestination());
            if (destination.getDestinationArn() != null) {
                summary.put("DestinationArn", destination.getDestinationArn());
            }
            if (destination.getIamRoleArn() != null) {
                summary.put("IamRoleArn", destination.getIamRoleArn());
            }
        }
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/rummetrics/{name}/metricsdestination")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRumMetricsDestination(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("destination") String destination,
            @QueryParam("destinationArn") String destinationArn) {
        service.deleteRumMetricsDestination(regionResolver.resolveRegion(headers), name, destination, destinationArn);
        return Response.ok().build();
    }

    @POST
    @Path("/rummetrics/{name}/metrics")
    public Response batchCreateRumMetricDefinitions(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        RumService.BatchCreateResult result = service.batchCreateRumMetricDefinitions(
                regionResolver.resolveRegion(headers), name, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        var errors = response.putArray("Errors");
        for (RumService.BatchCreateError error : result.errors()) {
            ObjectNode entry = errors.addObject();
            entry.set("MetricDefinition", error.metricDefinition());
            entry.put("ErrorCode", error.errorCode());
            entry.put("ErrorMessage", error.errorMessage());
        }
        var created = response.putArray("MetricDefinitions");
        result.metricDefinitions().forEach(definition -> created.add(objectMapper.<JsonNode>valueToTree(definition)));
        return Response.ok(response).build();
    }

    @GET
    @Path("/rummetrics/{name}/metrics")
    @Consumes(MediaType.WILDCARD)
    public Response batchGetRumMetricDefinitions(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("destination") String destination,
            @QueryParam("destinationArn") String destinationArn,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        RumService.Slice<RumMetricDefinition> page = service.batchGetRumMetricDefinitions(
                regionResolver.resolveRegion(headers), name, destination, destinationArn, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        var definitions = response.putArray("MetricDefinitions");
        page.items().forEach(definition -> definitions.add(objectMapper.<JsonNode>valueToTree(definition)));
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/rummetrics/{name}/metrics")
    @Consumes(MediaType.WILDCARD)
    public Response batchDeleteRumMetricDefinitions(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("destination") String destination,
            @QueryParam("destinationArn") String destinationArn,
            @QueryParam("metricDefinitionIds") List<String> metricDefinitionIds) {
        RumService.BatchDeleteResult result = service.batchDeleteRumMetricDefinitions(
                regionResolver.resolveRegion(headers), name, destination, destinationArn, metricDefinitionIds);
        ObjectNode response = objectMapper.createObjectNode();
        var errors = response.putArray("Errors");
        for (RumService.BatchDeleteError error : result.errors()) {
            ObjectNode entry = errors.addObject();
            entry.put("MetricDefinitionId", error.metricDefinitionId());
            entry.put("ErrorCode", error.errorCode());
            entry.put("ErrorMessage", error.errorMessage());
        }
        var deleted = response.putArray("MetricDefinitionIds");
        result.metricDefinitionIds().forEach(deleted::add);
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/rummetrics/{name}/metrics")
    public Response updateRumMetricDefinition(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        service.updateRumMetricDefinition(regionResolver.resolveRegion(headers), name, parse(body));
        return Response.ok().build();
    }

    private static void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("NextToken", nextToken);
        }
    }
}
