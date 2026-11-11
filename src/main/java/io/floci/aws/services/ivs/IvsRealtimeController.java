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
 * The Amazon IVS Real-Time Streaming REST API, routed here by {@link IvsRouteFilter}: every operation a
 * {@code POST /<Operation>} with a JSON body, and the tag routes of its resources. Errors carry
 * {@code exceptionMessage} and {@code x-amzn-ErrorType}.
 */
@Path(IvsRouteFilter.REALTIME_PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class IvsRealtimeController {

    /** IVS Real-Time's request constraints, reported as ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load("aws/ivs-realtime-request-shapes.json",
            "IVS Real-Time request shapes", new RequestConstraintCheck.Messages() {
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

    private final IvsRealtimeService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public IvsRealtimeController(IvsRealtimeService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/CreateEncoderConfiguration")
    public Response createEncoderConfiguration(String body) {
        return call("CreateEncoderConfiguration", body, input -> service.createEncoderConfiguration(input, region()));
    }

    @POST
    @Path("/CreateIngestConfiguration")
    public Response createIngestConfiguration(String body) {
        return call("CreateIngestConfiguration", body, input -> service.createIngestConfiguration(input, region()));
    }

    @POST
    @Path("/CreateParticipantToken")
    public Response createParticipantToken(String body) {
        return call("CreateParticipantToken", body, input -> service.createParticipantToken(input, region()));
    }

    @POST
    @Path("/CreateStage")
    public Response createStage(String body) {
        return call("CreateStage", body, input -> service.createStage(input, region()));
    }

    @POST
    @Path("/CreateStorageConfiguration")
    public Response createStorageConfiguration(String body) {
        return call("CreateStorageConfiguration", body, input -> service.createStorageConfiguration(input, region()));
    }

    @POST
    @Path("/DeleteEncoderConfiguration")
    public Response deleteEncoderConfiguration(String body) {
        return call("DeleteEncoderConfiguration", body, input -> service.deleteEncoderConfiguration(input, region()));
    }

    @POST
    @Path("/DeleteIngestConfiguration")
    public Response deleteIngestConfiguration(String body) {
        return call("DeleteIngestConfiguration", body, input -> service.deleteIngestConfiguration(input, region()));
    }

    @POST
    @Path("/DeletePublicKey")
    public Response deletePublicKey(String body) {
        return call("DeletePublicKey", body, input -> service.deletePublicKey(input, region()));
    }

    @POST
    @Path("/DeleteStage")
    public Response deleteStage(String body) {
        return call("DeleteStage", body, input -> service.deleteStage(input, region()));
    }

    @POST
    @Path("/DeleteStorageConfiguration")
    public Response deleteStorageConfiguration(String body) {
        return call("DeleteStorageConfiguration", body, input -> service.deleteStorageConfiguration(input, region()));
    }

    @POST
    @Path("/DisconnectParticipant")
    public Response disconnectParticipant(String body) {
        return call("DisconnectParticipant", body, input -> service.disconnectParticipant(input, region()));
    }

    @POST
    @Path("/GetComposition")
    public Response getComposition(String body) {
        return call("GetComposition", body, input -> service.getComposition(input, region()));
    }

    @POST
    @Path("/GetEncoderConfiguration")
    public Response getEncoderConfiguration(String body) {
        return call("GetEncoderConfiguration", body, input -> service.getEncoderConfiguration(input, region()));
    }

    @POST
    @Path("/GetIngestConfiguration")
    public Response getIngestConfiguration(String body) {
        return call("GetIngestConfiguration", body, input -> service.getIngestConfiguration(input, region()));
    }

    @POST
    @Path("/GetParticipant")
    public Response getParticipant(String body) {
        return call("GetParticipant", body, input -> service.getParticipant(input, region()));
    }

    @POST
    @Path("/GetPublicKey")
    public Response getPublicKey(String body) {
        return call("GetPublicKey", body, input -> service.getPublicKey(input, region()));
    }

    @POST
    @Path("/GetStage")
    public Response getStage(String body) {
        return call("GetStage", body, input -> service.getStage(input, region()));
    }

    @POST
    @Path("/GetStageSession")
    public Response getStageSession(String body) {
        return call("GetStageSession", body, input -> service.getStageSession(input, region()));
    }

    @POST
    @Path("/GetStorageConfiguration")
    public Response getStorageConfiguration(String body) {
        return call("GetStorageConfiguration", body, input -> service.getStorageConfiguration(input, region()));
    }

    @POST
    @Path("/ImportPublicKey")
    public Response importPublicKey(String body) {
        return call("ImportPublicKey", body, input -> service.importPublicKey(input, region()));
    }

    @POST
    @Path("/ListCompositions")
    public Response listCompositions(String body) {
        return call("ListCompositions", body, input -> service.listCompositions(input, region()));
    }

    @POST
    @Path("/ListEncoderConfigurations")
    public Response listEncoderConfigurations(String body) {
        return call("ListEncoderConfigurations", body, input -> service.listEncoderConfigurations(input, region()));
    }

    @POST
    @Path("/ListIngestConfigurations")
    public Response listIngestConfigurations(String body) {
        return call("ListIngestConfigurations", body, input -> service.listIngestConfigurations(input, region()));
    }

    @POST
    @Path("/ListParticipantEvents")
    public Response listParticipantEvents(String body) {
        return call("ListParticipantEvents", body, input -> service.listParticipantEvents(input, region()));
    }

    @POST
    @Path("/ListParticipantReplicas")
    public Response listParticipantReplicas(String body) {
        return call("ListParticipantReplicas", body, input -> service.listParticipantReplicas(input, region()));
    }

    @POST
    @Path("/ListParticipants")
    public Response listParticipants(String body) {
        return call("ListParticipants", body, input -> service.listParticipants(input, region()));
    }

    @POST
    @Path("/ListPublicKeys")
    public Response listPublicKeys(String body) {
        return call("ListPublicKeys", body, input -> service.listPublicKeys(input, region()));
    }

    @POST
    @Path("/ListStageSessions")
    public Response listStageSessions(String body) {
        return call("ListStageSessions", body, input -> service.listStageSessions(input, region()));
    }

    @POST
    @Path("/ListStages")
    public Response listStages(String body) {
        return call("ListStages", body, input -> service.listStages(input, region()));
    }

    @POST
    @Path("/ListStorageConfigurations")
    public Response listStorageConfigurations(String body) {
        return call("ListStorageConfigurations", body, input -> service.listStorageConfigurations(input, region()));
    }

    @POST
    @Path("/RevokeConnectionToken")
    public Response revokeConnectionToken(String body) {
        return call("RevokeConnectionToken", body, input -> service.revokeConnectionToken(input, region()));
    }

    @POST
    @Path("/StartComposition")
    public Response startComposition(String body) {
        return call("StartComposition", body, input -> service.startComposition(input, region()));
    }

    @POST
    @Path("/StartParticipantReplication")
    public Response startParticipantReplication(String body) {
        return call("StartParticipantReplication", body, input -> service.startParticipantReplication(input, region()));
    }

    @POST
    @Path("/StopComposition")
    public Response stopComposition(String body) {
        return call("StopComposition", body, input -> service.stopComposition(input, region()));
    }

    @POST
    @Path("/StopParticipantReplication")
    public Response stopParticipantReplication(String body) {
        return call("StopParticipantReplication", body, input -> service.stopParticipantReplication(input, region()));
    }

    @POST
    @Path("/UpdateIngestConfiguration")
    public Response updateIngestConfiguration(String body) {
        return call("UpdateIngestConfiguration", body, input -> service.updateIngestConfiguration(input, region()));
    }

    @POST
    @Path("/UpdateStage")
    public Response updateStage(String body) {
        return call("UpdateStage", body, input -> service.updateStage(input, region()));
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
