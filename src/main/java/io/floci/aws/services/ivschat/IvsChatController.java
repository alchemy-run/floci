package io.floci.aws.services.ivschat;

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
 * The Amazon IVS Chat REST API, routed here by {@link IvsChatRouteFilter}: every operation a
 * {@code POST /<Operation>} with a JSON body, and the tag routes. Errors carry {@code message}, the error's other
 * members, and {@code x-amzn-ErrorType}.
 */
@Path(IvsChatRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class IvsChatController {

    /** IVS Chat's request constraints, reported as ValidationException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load("aws/ivschat-request-shapes.json",
            "IVS Chat request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return IvsChatStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return IvsChatStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return IvsChatStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return IvsChatStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return IvsChatStore.validation(path + " is required.");
                }
            });

    private final IvsChatService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public IvsChatController(IvsChatService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/CreateChatToken")
    public Response createChatToken(String body) {
        return call("CreateChatToken", body, input -> service.createChatToken(input, region()));
    }

    @POST
    @Path("/CreateLoggingConfiguration")
    public Response createLoggingConfiguration(String body) {
        return call("CreateLoggingConfiguration", body, input -> service.createLoggingConfiguration(input, region()));
    }

    @POST
    @Path("/CreateRoom")
    public Response createRoom(String body) {
        return call("CreateRoom", body, input -> service.createRoom(input, region()));
    }

    @POST
    @Path("/DeleteLoggingConfiguration")
    public Response deleteLoggingConfiguration(String body) {
        return call("DeleteLoggingConfiguration", body, input -> service.deleteLoggingConfiguration(input, region()));
    }

    @POST
    @Path("/DeleteMessage")
    public Response deleteMessage(String body) {
        return call("DeleteMessage", body, input -> service.deleteMessage(input, region()));
    }

    @POST
    @Path("/DeleteRoom")
    public Response deleteRoom(String body) {
        return call("DeleteRoom", body, input -> service.deleteRoom(input, region()));
    }

    @POST
    @Path("/DisconnectUser")
    public Response disconnectUser(String body) {
        return call("DisconnectUser", body, input -> service.disconnectUser(input, region()));
    }

    @POST
    @Path("/GetLoggingConfiguration")
    public Response getLoggingConfiguration(String body) {
        return call("GetLoggingConfiguration", body, input -> service.getLoggingConfiguration(input, region()));
    }

    @POST
    @Path("/GetRoom")
    public Response getRoom(String body) {
        return call("GetRoom", body, input -> service.getRoom(input, region()));
    }

    @POST
    @Path("/ListLoggingConfigurations")
    public Response listLoggingConfigurations(String body) {
        return call("ListLoggingConfigurations", body, input -> service.listLoggingConfigurations(input, region()));
    }

    @POST
    @Path("/ListRooms")
    public Response listRooms(String body) {
        return call("ListRooms", body, input -> service.listRooms(input, region()));
    }

    @POST
    @Path("/SendEvent")
    public Response sendEvent(String body) {
        return call("SendEvent", body, input -> service.sendEvent(input, region()));
    }

    @POST
    @Path("/UpdateLoggingConfiguration")
    public Response updateLoggingConfiguration(String body) {
        return call("UpdateLoggingConfiguration", body, input -> service.updateLoggingConfiguration(input, region()));
    }

    @POST
    @Path("/UpdateRoom")
    public Response updateRoom(String body) {
        return call("UpdateRoom", body, input -> service.updateRoom(input, region()));
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
                throw IvsChatStore.validation("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw IvsChatStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private Response reply(Supplier<ObjectNode> call) {
        try {
            return Response.ok(call.get().toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("message", e.getMessage());
            if (e.getExtendedData() != null) {
                e.getExtendedData().forEach((name, value) -> error.set(name, mapper.valueToTree(value)));
            }
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }
}
