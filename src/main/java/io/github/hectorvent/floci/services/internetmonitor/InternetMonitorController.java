package io.github.hectorvent.floci.services.internetmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.internetmonitor.model.Monitor;
import io.github.hectorvent.floci.services.internetmonitor.model.Query;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.util.function.Supplier;

/**
 * CloudWatch Internet Monitor (Smithy restJson1).
 *
 * <p>Literal {@code /v20210603/Monitors} and {@code /v20210603/InternetEvents}
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/v20210603")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InternetMonitorController {

    private final InternetMonitorService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public InternetMonitorController(
            InternetMonitorService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/Monitors")
    public Response createMonitor(@Context HttpHeaders headers, String body) {
        return run(() -> {
            Monitor monitor = service.createMonitor(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("Arn", monitor.getMonitorArn());
            response.put("Status", monitor.getStatus());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/Monitors")
    @Consumes(MediaType.WILDCARD)
    public Response listMonitors(
            @Context HttpHeaders headers,
            @QueryParam("MaxResults") String maxResults,
            @QueryParam("NextToken") String nextToken,
            @QueryParam("MonitorStatus") String monitorStatus) {
        return run(() -> {
            InternetMonitorService.Page<Monitor> page =
                    service.listMonitors(region(headers), maxResults, nextToken, monitorStatus);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode monitors = response.putArray("Monitors");
            for (Monitor monitor : page.items()) {
                ObjectNode summary = monitors.addObject();
                summary.put("MonitorName", monitor.getMonitorName());
                summary.put("MonitorArn", monitor.getMonitorArn());
                summary.put("Status", monitor.getStatus());
                if (monitor.getProcessingStatus() != null) {
                    summary.put("ProcessingStatus", monitor.getProcessingStatus());
                }
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/Monitors/{MonitorName}")
    @Consumes(MediaType.WILDCARD)
    public Response getMonitor(@Context HttpHeaders headers, @PathParam("MonitorName") String name) {
        return run(() -> Response.ok(toGet(service.getMonitor(region(headers), name))).build());
    }

    @PATCH
    @Path("/Monitors/{MonitorName}")
    public Response updateMonitor(
            @Context HttpHeaders headers, @PathParam("MonitorName") String name, String body) {
        return run(() -> {
            Monitor monitor = service.updateMonitor(region(headers), name, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("MonitorArn", monitor.getMonitorArn());
            response.put("Status", monitor.getStatus());
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/Monitors/{MonitorName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMonitor(@Context HttpHeaders headers, @PathParam("MonitorName") String name) {
        return run(() -> {
            service.deleteMonitor(region(headers), name);
            return Response.ok().build();
        });
    }

    @GET
    @Path("/Monitors/{MonitorName}/HealthEvents")
    @Consumes(MediaType.WILDCARD)
    public Response listHealthEvents(@Context HttpHeaders headers, @PathParam("MonitorName") String name) {
        service.listHealthEvents(region(headers), name);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("HealthEvents");
        return Response.ok(response).build();
    }

    @GET
    @Path("/Monitors/{MonitorName}/HealthEvents/{EventId}")
    @Consumes(MediaType.WILDCARD)
    public Response getHealthEvent(
            @Context HttpHeaders headers,
            @PathParam("MonitorName") String name,
            @PathParam("EventId") String eventId) {
        service.getHealthEvent(region(headers), name, eventId);
        return Response.ok().build();
    }

    @GET
    @Path("/InternetEvents")
    @Consumes(MediaType.WILDCARD)
    public Response listInternetEvents() {
        service.listInternetEvents();
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("InternetEvents");
        return Response.ok(response).build();
    }

    @GET
    @Path("/InternetEvents/{EventId}")
    @Consumes(MediaType.WILDCARD)
    public Response getInternetEvent(@PathParam("EventId") String eventId) {
        service.getInternetEvent(eventId);
        return Response.ok().build();
    }

    @POST
    @Path("/Monitors/{MonitorName}/Queries")
    public Response startQuery(
            @Context HttpHeaders headers, @PathParam("MonitorName") String name, String body) {
        Query query = service.startQuery(region(headers), name, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueryId", query.getQueryId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/Monitors/{MonitorName}/Queries/{QueryId}/Status")
    @Consumes(MediaType.WILDCARD)
    public Response getQueryStatus(
            @Context HttpHeaders headers,
            @PathParam("MonitorName") String name,
            @PathParam("QueryId") String queryId) {
        Query query = service.getQueryStatus(region(headers), name, queryId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Status", query.getStatus());
        return Response.ok(response).build();
    }

    @GET
    @Path("/Monitors/{MonitorName}/Queries/{QueryId}/Results")
    @Consumes(MediaType.WILDCARD)
    public Response getQueryResults(
            @Context HttpHeaders headers,
            @PathParam("MonitorName") String name,
            @PathParam("QueryId") String queryId) {
        service.getQueryResults(region(headers), name, queryId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Fields");
        response.putArray("Data");
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/Monitors/{MonitorName}/Queries/{QueryId}")
    @Consumes(MediaType.WILDCARD)
    public Response stopQuery(
            @Context HttpHeaders headers,
            @PathParam("MonitorName") String name,
            @PathParam("QueryId") String queryId) {
        service.stopQuery(region(headers), name, queryId);
        return Response.ok().build();
    }

    private ObjectNode toGet(Monitor monitor) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MonitorName", monitor.getMonitorName());
        response.put("MonitorArn", monitor.getMonitorArn());
        ArrayNode resources = response.putArray("Resources");
        for (String resource : monitor.getResources()) {
            resources.add(resource);
        }
        response.put("Status", monitor.getStatus());
        response.put("CreatedAt", monitor.getCreatedAt());
        response.put("ModifiedAt", monitor.getModifiedAt());
        if (monitor.getProcessingStatus() != null) {
            response.put("ProcessingStatus", monitor.getProcessingStatus());
        }
        if (monitor.getProcessingStatusInfo() != null) {
            response.put("ProcessingStatusInfo", monitor.getProcessingStatusInfo());
        }
        ObjectNode tags = response.putObject("Tags");
        monitor.getTags().forEach(tags::put);
        if (monitor.getMaxCityNetworksToMonitor() != null) {
            response.put("MaxCityNetworksToMonitor", monitor.getMaxCityNetworksToMonitor());
        }
        if (monitor.getTrafficPercentageToMonitor() != null) {
            response.put("TrafficPercentageToMonitor", monitor.getTrafficPercentageToMonitor());
        }
        if (monitor.getInternetMeasurementsLogDelivery() != null) {
            response.set("InternetMeasurementsLogDelivery", monitor.getInternetMeasurementsLogDelivery());
        }
        if (monitor.getHealthEventsConfig() != null) {
            response.set("HealthEventsConfig", monitor.getHealthEventsConfig());
        }
        return response;
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
}
