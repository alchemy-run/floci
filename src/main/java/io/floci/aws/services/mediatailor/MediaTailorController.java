package io.floci.aws.services.mediatailor;

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
 * The AWS Elemental MediaTailor REST API, routed here by {@link MediaTailorRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(MediaTailorRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MediaTailorController {

    /** MediaTailor's request constraints, worded as a BadRequestException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mediatailor-request-shapes.json", "MediaTailor request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MediaTailorStore.badRequest(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MediaTailorStore.badRequest(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MediaTailorStore.badRequest(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MediaTailorStore.badRequest(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MediaTailorStore.badRequest(path + " is required.");
                }
            });

    private final MediaTailorService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MediaTailorController(MediaTailorService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "mediatailor");
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
        String path = uri.getRequestUri().getRawPath().substring(MediaTailorRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        try {
            return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                    (operation, input) -> handle(operation, input, region));
        } catch (AwsException e) {
            if ("ValidationException".equals(e.getErrorCode())) {
                throw MediaTailorStore.badRequest(e.getMessage());
            }
            throw e;
        }
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "ConfigureLogsForChannel" -> service.configureLogsForChannel(input, region);
            case "ConfigureLogsForPlaybackConfiguration" -> service.configureLogsForPlaybackConfiguration(input, region);
            case "CreateChannel" -> service.createChannel(input, region);
            case "CreateLiveSource" -> service.createLiveSource(input, region);
            case "CreatePrefetchSchedule" -> service.createPrefetchSchedule(input, region);
            case "CreateProgram" -> service.createProgram(input, region);
            case "CreateSourceLocation" -> service.createSourceLocation(input, region);
            case "CreateVodSource" -> service.createVodSource(input, region);
            case "DeleteChannel" -> service.deleteChannel(input, region);
            case "DeleteChannelPolicy" -> service.deleteChannelPolicy(input, region);
            case "DeleteFunction" -> service.deleteFunction(input, region);
            case "DeleteLiveSource" -> service.deleteLiveSource(input, region);
            case "DeletePlaybackConfiguration" -> service.deletePlaybackConfiguration(input, region);
            case "DeletePrefetchSchedule" -> service.deletePrefetchSchedule(input, region);
            case "DeleteProgram" -> service.deleteProgram(input, region);
            case "DeleteSourceLocation" -> service.deleteSourceLocation(input, region);
            case "DeleteVodSource" -> service.deleteVodSource(input, region);
            case "DescribeChannel" -> service.describeChannel(input, region);
            case "DescribeLiveSource" -> service.describeLiveSource(input, region);
            case "DescribeProgram" -> service.describeProgram(input, region);
            case "DescribeSourceLocation" -> service.describeSourceLocation(input, region);
            case "DescribeVodSource" -> service.describeVodSource(input, region);
            case "GetChannelPolicy" -> service.getChannelPolicy(input, region);
            case "GetChannelSchedule" -> service.getChannelSchedule(input, region);
            case "GetFunction" -> service.getFunction(input, region);
            case "GetPlaybackConfiguration" -> service.getPlaybackConfiguration(input, region);
            case "GetPrefetchSchedule" -> service.getPrefetchSchedule(input, region);
            case "ListAlerts" -> service.listAlerts(input, region);
            case "ListChannels" -> service.listChannels(input, region);
            case "ListFunctions" -> service.listFunctions(input, region);
            case "ListLiveSources" -> service.listLiveSources(input, region);
            case "ListPlaybackConfigurations" -> service.listPlaybackConfigurations(input, region);
            case "ListPrefetchSchedules" -> service.listPrefetchSchedules(input, region);
            case "ListSourceLocations" -> service.listSourceLocations(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "ListVodSources" -> service.listVodSources(input, region);
            case "PutChannelPolicy" -> service.putChannelPolicy(input, region);
            case "PutFunction" -> service.putFunction(input, region);
            case "PutPlaybackConfiguration" -> service.putPlaybackConfiguration(input, region);
            case "StartChannel" -> service.startChannel(input, region);
            case "StopChannel" -> service.stopChannel(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateChannel" -> service.updateChannel(input, region);
            case "UpdateLiveSource" -> service.updateLiveSource(input, region);
            case "UpdateProgram" -> service.updateProgram(input, region);
            case "UpdateSourceLocation" -> service.updateSourceLocation(input, region);
            case "UpdateVodSource" -> service.updateVodSource(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
