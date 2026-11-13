package io.floci.aws.services.osis;

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
 * The Amazon OpenSearch Ingestion REST API, routed here by {@link OsisRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(OsisRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class OsisController {

    /** OpenSearch Ingestion's request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/osis-request-shapes.json", "OpenSearch Ingestion request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return OsisStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return OsisStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return OsisStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return OsisStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return OsisStore.validation(path + " is required.");
                }
            });

    private final OsisService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public OsisController(OsisService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "osis");
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

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(OsisRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CreatePipeline" -> service.createPipeline(input, region);
            case "CreatePipelineEndpoint" -> service.createPipelineEndpoint(input, region);
            case "DeletePipeline" -> service.deletePipeline(input, region);
            case "DeletePipelineEndpoint" -> service.deletePipelineEndpoint(input, region);
            case "DeleteResourcePolicy" -> service.deleteResourcePolicy(input, region);
            case "GetPipeline" -> service.getPipeline(input, region);
            case "GetPipelineBlueprint" -> service.getPipelineBlueprint(input, region);
            case "GetPipelineChangeProgress" -> service.getPipelineChangeProgress(input, region);
            case "GetResourcePolicy" -> service.getResourcePolicy(input, region);
            case "ListPipelineBlueprints" -> service.listPipelineBlueprints(input, region);
            case "ListPipelineEndpointConnections" -> service.listPipelineEndpointConnections(input, region);
            case "ListPipelineEndpoints" -> service.listPipelineEndpoints(input, region);
            case "ListPipelines" -> service.listPipelines(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "PutResourcePolicy" -> service.putResourcePolicy(input, region);
            case "RevokePipelineEndpointConnections" -> service.revokePipelineEndpointConnections(input, region);
            case "StartPipeline" -> service.startPipeline(input, region);
            case "StopPipeline" -> service.stopPipeline(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdatePipeline" -> service.updatePipeline(input, region);
            case "ValidatePipeline" -> service.validatePipeline(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
