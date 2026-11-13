package io.floci.aws.services.ivs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Amazon IVS REST API, routed here by {@link IvsRouteFilter}: every operation a {@code POST /<Operation>} with
 * a JSON body, and the tag routes. Errors carry {@code exceptionMessage} and {@code x-amzn-ErrorType}.
 */
@Path(IvsRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class IvsController {

    /** IVS's request constraints, reported as ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load("aws/ivs-request-shapes.json",
            "IVS request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return IvsStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return IvsStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return IvsStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return IvsStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return IvsStore.validation(path + " is required.");
                }
            });

    private final IvsService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public IvsController(IvsService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/BatchGetChannel")
    public Response batchGetChannel(String body) {
        return call("BatchGetChannel", body, input -> service.batchGetChannel(input, region()));
    }

    @POST
    @Path("/BatchGetStreamKey")
    public Response batchGetStreamKey(String body) {
        return call("BatchGetStreamKey", body, input -> service.batchGetStreamKey(input, region()));
    }

    @POST
    @Path("/BatchStartViewerSessionRevocation")
    public Response batchStartViewerSessionRevocation(String body) {
        return call("BatchStartViewerSessionRevocation", body, input -> service.batchStartViewerSessionRevocation(input, region()));
    }

    @POST
    @Path("/CreateAdConfiguration")
    public Response createAdConfiguration(String body) {
        return call("CreateAdConfiguration", body, input -> service.createAdConfiguration(input, region()));
    }

    @POST
    @Path("/CreateChannel")
    public Response createChannel(String body) {
        return call("CreateChannel", body, input -> service.createChannel(input, region()));
    }

    @POST
    @Path("/CreatePlaybackRestrictionPolicy")
    public Response createPlaybackRestrictionPolicy(String body) {
        return call("CreatePlaybackRestrictionPolicy", body, input -> service.createPlaybackRestrictionPolicy(input, region()));
    }

    @POST
    @Path("/CreateRecordingConfiguration")
    public Response createRecordingConfiguration(String body) {
        return call("CreateRecordingConfiguration", body, input -> service.createRecordingConfiguration(input, region()));
    }

    @POST
    @Path("/CreateStreamKey")
    public Response createStreamKey(String body) {
        return call("CreateStreamKey", body, input -> service.createStreamKey(input, region()));
    }

    @POST
    @Path("/DeleteAdConfiguration")
    public Response deleteAdConfiguration(String body) {
        return call("DeleteAdConfiguration", body, input -> service.deleteAdConfiguration(input, region()));
    }

    @POST
    @Path("/DeleteChannel")
    public Response deleteChannel(String body) {
        return call("DeleteChannel", body, input -> service.deleteChannel(input, region()));
    }

    @POST
    @Path("/DeletePlaybackKeyPair")
    public Response deletePlaybackKeyPair(String body) {
        return call("DeletePlaybackKeyPair", body, input -> service.deletePlaybackKeyPair(input, region()));
    }

    @POST
    @Path("/DeletePlaybackRestrictionPolicy")
    public Response deletePlaybackRestrictionPolicy(String body) {
        return call("DeletePlaybackRestrictionPolicy", body, input -> service.deletePlaybackRestrictionPolicy(input, region()));
    }

    @POST
    @Path("/DeleteRecordingConfiguration")
    public Response deleteRecordingConfiguration(String body) {
        return call("DeleteRecordingConfiguration", body, input -> service.deleteRecordingConfiguration(input, region()));
    }

    @POST
    @Path("/DeleteStreamKey")
    public Response deleteStreamKey(String body) {
        return call("DeleteStreamKey", body, input -> service.deleteStreamKey(input, region()));
    }

    @POST
    @Path("/GetAdConfiguration")
    public Response getAdConfiguration(String body) {
        return call("GetAdConfiguration", body, input -> service.getAdConfiguration(input, region()));
    }

    @POST
    @Path("/GetChannel")
    public Response getChannel(String body) {
        return call("GetChannel", body, input -> service.getChannel(input, region()));
    }

    @POST
    @Path("/GetPlaybackKeyPair")
    public Response getPlaybackKeyPair(String body) {
        return call("GetPlaybackKeyPair", body, input -> service.getPlaybackKeyPair(input, region()));
    }

    @POST
    @Path("/GetPlaybackRestrictionPolicy")
    public Response getPlaybackRestrictionPolicy(String body) {
        return call("GetPlaybackRestrictionPolicy", body, input -> service.getPlaybackRestrictionPolicy(input, region()));
    }

    @POST
    @Path("/GetRecordingConfiguration")
    public Response getRecordingConfiguration(String body) {
        return call("GetRecordingConfiguration", body, input -> service.getRecordingConfiguration(input, region()));
    }

    @POST
    @Path("/GetStream")
    public Response getStream(String body) {
        return call("GetStream", body, input -> service.getStream(input, region()));
    }

    @POST
    @Path("/GetStreamKey")
    public Response getStreamKey(String body) {
        return call("GetStreamKey", body, input -> service.getStreamKey(input, region()));
    }

    @POST
    @Path("/GetStreamSession")
    public Response getStreamSession(String body) {
        return call("GetStreamSession", body, input -> service.getStreamSession(input, region()));
    }

    @POST
    @Path("/ImportPlaybackKeyPair")
    public Response importPlaybackKeyPair(String body) {
        return call("ImportPlaybackKeyPair", body, input -> service.importPlaybackKeyPair(input, region()));
    }

    @POST
    @Path("/InsertAdBreak")
    public Response insertAdBreak(String body) {
        return call("InsertAdBreak", body, input -> service.insertAdBreak(input, region()));
    }

    @POST
    @Path("/ListAdConfigurations")
    public Response listAdConfigurations(String body) {
        return call("ListAdConfigurations", body, input -> service.listAdConfigurations(input, region()));
    }

    @POST
    @Path("/ListChannels")
    public Response listChannels(String body) {
        return call("ListChannels", body, input -> service.listChannels(input, region()));
    }

    @POST
    @Path("/ListPlaybackKeyPairs")
    public Response listPlaybackKeyPairs(String body) {
        return call("ListPlaybackKeyPairs", body, input -> service.listPlaybackKeyPairs(input, region()));
    }

    @POST
    @Path("/ListPlaybackRestrictionPolicies")
    public Response listPlaybackRestrictionPolicies(String body) {
        return call("ListPlaybackRestrictionPolicies", body, input -> service.listPlaybackRestrictionPolicies(input, region()));
    }

    @POST
    @Path("/ListRecordingConfigurations")
    public Response listRecordingConfigurations(String body) {
        return call("ListRecordingConfigurations", body, input -> service.listRecordingConfigurations(input, region()));
    }

    @POST
    @Path("/ListStreamKeys")
    public Response listStreamKeys(String body) {
        return call("ListStreamKeys", body, input -> service.listStreamKeys(input, region()));
    }

    @POST
    @Path("/ListStreamSessions")
    public Response listStreamSessions(String body) {
        return call("ListStreamSessions", body, input -> service.listStreamSessions(input, region()));
    }

    @POST
    @Path("/ListStreams")
    public Response listStreams(String body) {
        return call("ListStreams", body, input -> service.listStreams(input, region()));
    }

    @POST
    @Path("/PutMetadata")
    public Response putMetadata(String body) {
        return call("PutMetadata", body, input -> service.putMetadata(input, region()));
    }

    @POST
    @Path("/StartViewerSessionRevocation")
    public Response startViewerSessionRevocation(String body) {
        return call("StartViewerSessionRevocation", body, input -> service.startViewerSessionRevocation(input, region()));
    }

    @POST
    @Path("/StopStream")
    public Response stopStream(String body) {
        return call("StopStream", body, input -> service.stopStream(input, region()));
    }

    @POST
    @Path("/UpdateAdConfiguration")
    public Response updateAdConfiguration(String body) {
        return call("UpdateAdConfiguration", body, input -> service.updateAdConfiguration(input, region()));
    }

    @POST
    @Path("/UpdateChannel")
    public Response updateChannel(String body) {
        return call("UpdateChannel", body, input -> service.updateChannel(input, region()));
    }

    @POST
    @Path("/UpdatePlaybackRestrictionPolicy")
    public Response updatePlaybackRestrictionPolicy(String body) {
        return call("UpdatePlaybackRestrictionPolicy", body, input -> service.updatePlaybackRestrictionPolicy(input, region()));
    }

    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(@PathParam("resourceArn") String resourceArn) {
        return reply(() -> service.listTagsForResource(resourceArn, region()));
    }

    @POST
    @Path("/tags/{resourceArn: .+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, String body) {
        return reply(() -> service.tagResource(resourceArn, parse(body), region()));
    }

    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return reply(() -> service.untagResource(resourceArn, tagKeys, region()));
    }

    private String region() {
        return regions.getRegion();
    }

    private Response call(String operation, String body, Function<JsonNode, ObjectNode> handler) {
        return reply(() -> {
            JsonNode input = parse(body);
            SHAPES.check(operation, input);
            return handler.apply(input);
        });
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw IvsStore.validation("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw IvsStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private Response reply(Supplier<ObjectNode> call) {
        try {
            return Response.ok(call.get().toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("exceptionMessage", e.getMessage());
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }
}
