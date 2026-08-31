package io.github.hectorvent.floci.services.neptunegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.neptunegraph.model.Graph;
import io.github.hectorvent.floci.services.neptunegraph.model.GraphSnapshot;
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

/**
 * Amazon Neptune Analytics restJson1.
 *
 * <p>Literal {@code /graphs}, {@code /snapshots}, {@code /importtasks} and
 * {@code /exporttasks} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}. Requests are signed as {@code neptune-graph}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NeptuneGraphController {

    private final NeptuneGraphService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public NeptuneGraphController(
            NeptuneGraphService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/graphs")
    public Response createGraph(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            Graph graph = service.createGraph(regionResolver.resolveRegion(headers), parse(body));
            return Response.ok(service.toGraph(graph)).build();
        });
    }

    @GET
    @Path("/graphs")
    @Consumes(MediaType.WILDCARD)
    public Response listGraphs(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            NeptuneGraphService.Page<Graph> page = service.listGraphs(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("graphs", service.graphSummaries(page.items()));
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/graphs/{graphIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getGraph(
            @Context HttpHeaders headers, @PathParam("graphIdentifier") String graphIdentifier) {
        return handle(() -> Response.ok(
                service.toGraph(service.getGraph(regionResolver.resolveRegion(headers), graphIdentifier))).build());
    }

    @PATCH
    @Path("/graphs/{graphIdentifier}")
    public Response updateGraph(
            @Context HttpHeaders headers,
            @PathParam("graphIdentifier") String graphIdentifier,
            String body) {
        return handle(() -> {
            Graph graph = service.updateGraph(
                    regionResolver.resolveRegion(headers), graphIdentifier, parse(body));
            return Response.ok(service.toGraph(graph)).build();
        });
    }

    @DELETE
    @Path("/graphs/{graphIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGraph(
            @Context HttpHeaders headers,
            @PathParam("graphIdentifier") String graphIdentifier,
            @QueryParam("skipSnapshot") String skipSnapshot) {
        return handle(() -> {
            Graph graph = service.deleteGraph(
                    regionResolver.resolveRegion(headers), graphIdentifier, skipSnapshot);
            return Response.ok(service.toGraph(graph)).build();
        });
    }

    @POST
    @Path("/snapshots")
    public Response createGraphSnapshot(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            GraphSnapshot snapshot = service.createGraphSnapshot(
                    regionResolver.resolveRegion(headers), parse(body));
            return Response.ok(service.toSnapshot(snapshot)).build();
        });
    }

    @GET
    @Path("/snapshots")
    @Consumes(MediaType.WILDCARD)
    public Response listGraphSnapshots(
            @Context HttpHeaders headers,
            @QueryParam("graphIdentifier") String graphIdentifier,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            NeptuneGraphService.Page<GraphSnapshot> page = service.listGraphSnapshots(
                    regionResolver.resolveRegion(headers), graphIdentifier, maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("graphSnapshots", service.snapshotSummaries(page.items()));
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/snapshots/{snapshotIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getGraphSnapshot(
            @Context HttpHeaders headers, @PathParam("snapshotIdentifier") String snapshotIdentifier) {
        return handle(() -> Response.ok(service.toSnapshot(
                service.getGraphSnapshot(regionResolver.resolveRegion(headers), snapshotIdentifier))).build());
    }

    @GET
    @Path("/importtasks/{taskIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getImportTask(@PathParam("taskIdentifier") String taskIdentifier) {
        return handle(() -> {
            service.getImportTask(taskIdentifier);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/exporttasks/{taskIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getExportTask(@PathParam("taskIdentifier") String taskIdentifier) {
        return handle(() -> {
            service.getExportTask(taskIdentifier);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/importtasks")
    @Consumes(MediaType.WILDCARD)
    public Response listImportTasks() {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("tasks");
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/exporttasks")
    @Consumes(MediaType.WILDCARD)
    public Response listExportTasks() {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("tasks");
            return Response.ok(response).build();
        });
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

    private Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", e.jsonType())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
