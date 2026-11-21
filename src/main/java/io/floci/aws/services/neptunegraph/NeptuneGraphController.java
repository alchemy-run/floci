package io.floci.aws.services.neptunegraph;

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
 * The Amazon Neptune Analytics REST API, routed here by {@link NeptuneGraphRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(NeptuneGraphRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class NeptuneGraphController {

    /** Neptune Analytics' request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/neptunegraph-request-shapes.json", "Neptune Analytics request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return NeptuneGraphStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return NeptuneGraphStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return NeptuneGraphStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return NeptuneGraphStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return NeptuneGraphStore.validation(path + " is required.");
                }
            });

    private final NeptuneGraphService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public NeptuneGraphController(NeptuneGraphService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "neptunegraph");
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
        String path = uri.getRequestUri().getRawPath().substring(NeptuneGraphRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CancelExportTask" -> service.cancelExportTask(input, region);
            case "CancelImportTask" -> service.cancelImportTask(input, region);
            case "CancelQuery" -> service.cancelQuery(input, region);
            case "CreateGraph" -> service.createGraph(input, region);
            case "CreateGraphSnapshot" -> service.createGraphSnapshot(input, region);
            case "CreateGraphUsingImportTask" -> service.createGraphUsingImportTask(input, region);
            case "CreatePrivateGraphEndpoint" -> service.createPrivateGraphEndpoint(input, region);
            case "DeleteGraph" -> service.deleteGraph(input, region);
            case "DeleteGraphSnapshot" -> service.deleteGraphSnapshot(input, region);
            case "DeletePrivateGraphEndpoint" -> service.deletePrivateGraphEndpoint(input, region);
            case "ExecuteQuery" -> service.executeQuery(input, region);
            case "GetExportTask" -> service.getExportTask(input, region);
            case "GetGraph" -> service.getGraph(input, region);
            case "GetGraphSnapshot" -> service.getGraphSnapshot(input, region);
            case "GetGraphSummary" -> service.getGraphSummary(input, region);
            case "GetImportTask" -> service.getImportTask(input, region);
            case "GetPrivateGraphEndpoint" -> service.getPrivateGraphEndpoint(input, region);
            case "GetQuery" -> service.getQuery(input, region);
            case "ListExportTasks" -> service.listExportTasks(input, region);
            case "ListGraphSnapshots" -> service.listGraphSnapshots(input, region);
            case "ListGraphs" -> service.listGraphs(input, region);
            case "ListImportTasks" -> service.listImportTasks(input, region);
            case "ListPrivateGraphEndpoints" -> service.listPrivateGraphEndpoints(input, region);
            case "ListQueries" -> service.listQueries(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "ResetGraph" -> service.resetGraph(input, region);
            case "RestoreGraphFromSnapshot" -> service.restoreGraphFromSnapshot(input, region);
            case "StartExportTask" -> service.startExportTask(input, region);
            case "StartGraph" -> service.startGraph(input, region);
            case "StartImportTask" -> service.startImportTask(input, region);
            case "StopGraph" -> service.stopGraph(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateGraph" -> service.updateGraph(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
