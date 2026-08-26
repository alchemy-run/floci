package io.github.hectorvent.floci.services.ivschat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivschat.model.LoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.Room;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon IVS Chat restJson1.
 *
 * <p>Literal {@code /CreateLoggingConfiguration}, {@code /CreateRoom} and peer
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code ivschat}.
 *
 * <p>{@code @Blocking}: Alchemy Bindings invoke these operations from inside a
 * Function URL Lambda. Serving them on the Vert.x event loop deadlocks that
 * nested call (the Function URL waiter holds the loop until the Lambda
 * returns). Worker threads keep CreateChatToken / SendEvent reachable.
 */
@Path("/")
@Blocking
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvsChatController {

    private final IvsChatService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IvsChatController(IvsChatService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateLoggingConfiguration")
    public Response createLoggingConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(toLogging(service.createLoggingConfiguration(
                regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/GetLoggingConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response getLoggingConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(toLogging(service.getLoggingConfiguration(
                regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/UpdateLoggingConfiguration")
    public Response updateLoggingConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(toLogging(service.updateLoggingConfiguration(
                regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/DeleteLoggingConfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLoggingConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteLoggingConfiguration(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListLoggingConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response listLoggingConfigurations(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsChatService.LoggingPage page = service.listLoggingConfigurations(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode configs = response.putArray("loggingConfigurations");
            for (LoggingConfiguration config : page.items()) {
                configs.add(toLogging(config));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/CreateRoom")
    public Response createRoom(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                toRoom(service.createRoom(regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/GetRoom")
    @Consumes(MediaType.WILDCARD)
    public Response getRoom(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                toRoom(service.getRoom(regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/UpdateRoom")
    public Response updateRoom(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                toRoom(service.updateRoom(regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/DeleteRoom")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRoom(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteRoom(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListRooms")
    @Consumes(MediaType.WILDCARD)
    public Response listRooms(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsChatService.Page page = service.listRooms(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode rooms = response.putArray("rooms");
            for (Room room : page.items()) {
                rooms.add(toSummary(room));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/CreateChatToken")
    @Consumes(MediaType.WILDCARD)
    public Response createChatToken(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsChatService.ChatToken token = service.createChatToken(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("token", token.token());
            response.put("tokenExpirationTime", token.tokenExpirationTime());
            response.put("sessionExpirationTime", token.sessionExpirationTime());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/SendEvent")
    public Response sendEvent(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            String id = service.sendEvent(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode().put("id", id)).build();
        });
    }

    @POST
    @Path("/DeleteMessage")
    public Response deleteMessage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            String id = service.deleteMessage(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode().put("id", id)).build();
        });
    }

    @POST
    @Path("/DisconnectUser")
    public Response disconnectUser(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.disconnectUser(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode toLogging(LoggingConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", config.getArn());
        node.put("id", config.getId());
        putOptional(node, "name", config.getName());
        node.set("destinationConfiguration", toDestination(config));
        putOptional(node, "state", config.getState());
        putOptional(node, "createTime", config.getCreateTime());
        putOptional(node, "updateTime", config.getUpdateTime());
        putTags(node, config.getTags());
        return node;
    }

    private ObjectNode toDestination(LoggingConfiguration config) {
        ObjectNode dest = objectMapper.createObjectNode();
        if (config.getCloudWatchLogsLogGroupName() != null) {
            dest.putObject("cloudWatchLogs").put("logGroupName", config.getCloudWatchLogsLogGroupName());
        } else if (config.getS3BucketName() != null) {
            dest.putObject("s3").put("bucketName", config.getS3BucketName());
        } else if (config.getFirehoseDeliveryStreamName() != null) {
            dest.putObject("firehose").put("deliveryStreamName", config.getFirehoseDeliveryStreamName());
        }
        return dest;
    }

    private ObjectNode toRoom(Room room) {
        ObjectNode node = toSummary(room);
        node.put("maximumMessageRatePerSecond", room.getMaximumMessageRatePerSecond());
        node.put("maximumMessageLength", room.getMaximumMessageLength());
        return node;
    }

    private ObjectNode toSummary(Room room) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", room.getArn());
        node.put("id", room.getId());
        putOptional(node, "name", room.getName());
        putOptional(node, "createTime", room.getCreateTime());
        putOptional(node, "updateTime", room.getUpdateTime());
        if (room.getMessageReviewHandlerUri() != null && !room.getMessageReviewHandlerUri().isBlank()) {
            ObjectNode handler = node.putObject("messageReviewHandler");
            handler.put("uri", room.getMessageReviewHandlerUri());
            putOptional(handler, "fallbackResult", room.getMessageReviewHandlerFallbackResult());
        }
        ArrayNode logging = node.putArray("loggingConfigurationIdentifiers");
        if (room.getLoggingConfigurationIdentifiers() != null) {
            for (String identifier : room.getLoggingConfigurationIdentifiers()) {
                logging.add(identifier);
            }
        }
        putTags(node, room.getTags());
        return node;
    }

    private void putTags(ObjectNode parent, java.util.Map<String, String> tags) {
        ObjectNode tagsNode = parent.putObject("tags");
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
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

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        if (exception.getExtendedData() != null) {
            exception.getExtendedData().forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
