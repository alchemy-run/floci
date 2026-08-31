package io.github.hectorvent.floci.services.ivsrealtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivsrealtime.model.ParticipantToken;
import io.github.hectorvent.floci.services.ivsrealtime.model.Stage;
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
 * Amazon IVS Real-Time restJson1 (stages, participant tokens, compositions).
 *
 * <p>Literal {@code /CreateStage}, {@code /ListStages}, {@code /CreateParticipantToken}
 * and peer paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 * Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Distilled signs as {@code ivs}; some clients sign as {@code ivsrealtime}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvsRealtimeController {

    private final IvsRealtimeService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IvsRealtimeController(
            IvsRealtimeService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateStage")
    public Response createStage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Stage stage = service.createStage(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("stage", toStage(stage));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetStage")
    @Consumes(MediaType.WILDCARD)
    public Response getStage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Stage stage = service.getStage(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("stage", toStage(stage));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/UpdateStage")
    public Response updateStage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Stage stage = service.updateStage(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("stage", toStage(stage));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeleteStage")
    @Consumes(MediaType.WILDCARD)
    public Response deleteStage(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteStage(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListStages")
    @Consumes(MediaType.WILDCARD)
    public Response listStages(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsRealtimeService.Page page = service.listStages(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode stages = response.putArray("stages");
            for (Object item : page.items()) {
                stages.add(toSummary((Stage) item));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/CreateParticipantToken")
    public Response createParticipantToken(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ParticipantToken token = service.createParticipantToken(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("participantToken", toToken(token));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ListStageSessions")
    @Consumes(MediaType.WILDCARD)
    public Response listStageSessions(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listStageSessions(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("stageSessions");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetStageSession")
    @Consumes(MediaType.WILDCARD)
    public Response getStageSession(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.getStageSession(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListParticipants")
    @Consumes(MediaType.WILDCARD)
    public Response listParticipants(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listParticipants(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("participants");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetParticipant")
    @Consumes(MediaType.WILDCARD)
    public Response getParticipant(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.getParticipant(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListParticipantEvents")
    @Consumes(MediaType.WILDCARD)
    public Response listParticipantEvents(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listParticipantEvents(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("events");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ListParticipantReplicas")
    @Consumes(MediaType.WILDCARD)
    public Response listParticipantReplicas(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listParticipantReplicas(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("replicas");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DisconnectParticipant")
    public Response disconnectParticipant(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.disconnectParticipant(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StartParticipantReplication")
    public Response startParticipantReplication(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.startParticipantReplication(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StopParticipantReplication")
    public Response stopParticipantReplication(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.stopParticipantReplication(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListCompositions")
    @Consumes(MediaType.WILDCARD)
    public Response listCompositions(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listCompositions(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("compositions");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetComposition")
    @Consumes(MediaType.WILDCARD)
    public Response getComposition(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.getComposition(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StopComposition")
    public Response stopComposition(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.stopComposition(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StartComposition")
    public Response startComposition(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.startComposition(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode toStage(Stage stage) {
        ObjectNode node = toSummary(stage);
        if (stage.hasRecordingConfiguration()) {
            ObjectNode recording = node.putObject("autoParticipantRecordingConfiguration");
            recording.put("storageConfigurationArn", stage.getRecordingStorageConfigurationArn());
            ArrayNode mediaTypes = recording.putArray("mediaTypes");
            if (stage.getRecordingMediaTypes() != null) {
                for (String mediaType : stage.getRecordingMediaTypes()) {
                    mediaTypes.add(mediaType);
                }
            }
            if (stage.getRecordingReconnectWindowSeconds() != null) {
                recording.put("recordingReconnectWindowSeconds", stage.getRecordingReconnectWindowSeconds());
            }
            if (stage.getRecordParticipantReplicas() != null) {
                recording.put("recordParticipantReplicas", stage.getRecordParticipantReplicas());
            }
        }
        ObjectNode endpoints = node.putObject("endpoints");
        String id = stage.getId();
        endpoints.put("whip", "https://" + id + ".global-contribute.live-video.net");
        endpoints.put("events", "wss://global.events.live-video.net");
        endpoints.put("rtmp", "rtmp://" + id + ".global-contribute.live-video.net/app/");
        endpoints.put("rtmps", "rtmps://" + id + ".global-contribute.live-video.net:443/app/");
        return node;
    }

    private ObjectNode toSummary(Stage stage) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", stage.getArn());
        putOptional(node, "name", stage.getName());
        putOptional(node, "activeSessionId", stage.getActiveSessionId());
        putTags(node, stage.getTags());
        return node;
    }

    private ObjectNode toToken(ParticipantToken token) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("participantId", token.getParticipantId());
        node.put("token", token.getToken());
        node.put("duration", token.getDuration());
        putOptional(node, "userId", token.getUserId());
        putOptional(node, "expirationTime", token.getExpirationTime());
        ObjectNode attributes = node.putObject("attributes");
        if (token.getAttributes() != null) {
            token.getAttributes().forEach(attributes::put);
        }
        ArrayNode capabilities = node.putArray("capabilities");
        if (token.getCapabilities() != null) {
            for (String capability : token.getCapabilities()) {
                capabilities.add(capability);
            }
        }
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
