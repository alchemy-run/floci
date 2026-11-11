package io.floci.aws.services.docdbelastic;

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
 * The Amazon DocumentDB Elastic Clusters REST API, routed here by {@link DocDbElasticRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(DocDbElasticRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DocDbElasticController {

    /** DocumentDB elastic's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/docdbelastic-request-shapes.json", "DocumentDB elastic request shapes", new RequestConstraintCheck.Messages() {
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

    private final DocDbElasticService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public DocDbElasticController(DocDbElasticService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "docdbelastic");
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
        String path = uri.getRequestUri().getRawPath().substring(DocDbElasticRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return DocDbElasticService.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "ApplyPendingMaintenanceAction" -> service.applyPendingMaintenanceAction(input, region);
            case "CopyClusterSnapshot" -> service.copyClusterSnapshot(input, region);
            case "CreateCluster" -> service.createCluster(input, region);
            case "CreateClusterSnapshot" -> service.createClusterSnapshot(input, region);
            case "DeleteCluster" -> service.deleteCluster(input, region);
            case "DeleteClusterSnapshot" -> service.deleteClusterSnapshot(input, region);
            case "GetCluster" -> service.getCluster(input, region);
            case "GetClusterSnapshot" -> service.getClusterSnapshot(input, region);
            case "GetPendingMaintenanceAction" -> service.getPendingMaintenanceAction(input, region);
            case "ListClusterSnapshots" -> service.listClusterSnapshots(input, region);
            case "ListClusters" -> service.listClusters(input, region);
            case "ListPendingMaintenanceActions" -> service.listPendingMaintenanceActions(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "RestoreClusterFromSnapshot" -> service.restoreClusterFromSnapshot(input, region);
            case "StartCluster" -> service.startCluster(input, region);
            case "StopCluster" -> service.stopCluster(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateCluster" -> service.updateCluster(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
