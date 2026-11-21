package io.floci.aws.services.mediapackagev2;

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
 * The AWS Elemental MediaPackage v2 REST API, routed here by {@link MediaPackageRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}), which also rename its lowerCamel wire members to the model's.
 */
@Path(MediaPackageRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MediaPackageController {

    /** MediaPackage v2's request constraints, worded as a ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mediapackagev2-request-shapes.json", "MediaPackage v2 request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MediaPackageStore.validation("MEMBER_INVALID", path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MediaPackageStore.validation("MEMBER_INVALID", path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MediaPackageStore.validation("MEMBER_INVALID", path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MediaPackageStore.validation("MEMBER_INVALID", path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MediaPackageStore.validation("MEMBER_INVALID", path + " is required.");
                }
            });

    private final MediaPackageService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MediaPackageController(MediaPackageService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "mediapackagev2");
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
        String path = uri.getRequestUri().getRawPath().substring(MediaPackageRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "CancelHarvestJob" -> service.cancelHarvestJob(input, region);
            case "CreateChannel" -> service.createChannel(input, region);
            case "CreateChannelGroup" -> service.createChannelGroup(input, region);
            case "CreateHarvestJob" -> service.createHarvestJob(input, region);
            case "CreateOriginEndpoint" -> service.createOriginEndpoint(input, region);
            case "DeleteChannel" -> service.deleteChannel(input, region);
            case "DeleteChannelGroup" -> service.deleteChannelGroup(input, region);
            case "DeleteChannelPolicy" -> service.deleteChannelPolicy(input, region);
            case "DeleteOriginEndpoint" -> service.deleteOriginEndpoint(input, region);
            case "DeleteOriginEndpointPolicy" -> service.deleteOriginEndpointPolicy(input, region);
            case "GetChannel" -> service.getChannel(input, region);
            case "GetChannelGroup" -> service.getChannelGroup(input, region);
            case "GetChannelPolicy" -> service.getChannelPolicy(input, region);
            case "GetHarvestJob" -> service.getHarvestJob(input, region);
            case "GetOriginEndpoint" -> service.getOriginEndpoint(input, region);
            case "GetOriginEndpointPolicy" -> service.getOriginEndpointPolicy(input, region);
            case "ListChannelGroups" -> service.listChannelGroups(input, region);
            case "ListChannels" -> service.listChannels(input, region);
            case "ListHarvestJobs" -> service.listHarvestJobs(input, region);
            case "ListOriginEndpoints" -> service.listOriginEndpoints(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "PutChannelPolicy" -> service.putChannelPolicy(input, region);
            case "PutOriginEndpointPolicy" -> service.putOriginEndpointPolicy(input, region);
            case "ResetChannelState" -> service.resetChannelState(input, region);
            case "ResetOriginEndpointState" -> service.resetOriginEndpointState(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateChannel" -> service.updateChannel(input, region);
            case "UpdateChannelGroup" -> service.updateChannelGroup(input, region);
            case "UpdateOriginEndpoint" -> service.updateOriginEndpoint(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
