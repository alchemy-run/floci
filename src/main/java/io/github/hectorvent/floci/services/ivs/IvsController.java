package io.github.hectorvent.floci.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivs.model.Channel;
import io.github.hectorvent.floci.services.ivs.model.PlaybackKeyPair;
import io.github.hectorvent.floci.services.ivs.model.PlaybackRestrictionPolicy;
import io.github.hectorvent.floci.services.ivs.model.StreamKey;
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
 * Amazon IVS restJson1 (channels, idle-stream data plane, tags).
 *
 * <p>Literal {@code /CreateChannel}, {@code /ListChannels}, {@code /GetStream}
 * and peer paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 * Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code ivs}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvsController {

    private final IvsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IvsController(IvsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/CreateChannel")
    public Response createChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Channel channel = service.createChannel(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(channel));
            if (channel.getStreamKey() != null) {
                response.set("streamKey", toStreamKey(channel.getStreamKey()));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetChannel")
    @Consumes(MediaType.WILDCARD)
    public Response getChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Channel channel = service.getChannel(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(channel));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/UpdateChannel")
    public Response updateChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Channel channel = service.updateChannel(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(channel));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeleteChannel")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteChannel(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListChannels")
    @Consumes(MediaType.WILDCARD)
    public Response listChannels(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsService.Page page = service.listChannels(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode channels = response.putArray("channels");
            for (Channel channel : page.items()) {
                channels.add(toSummary(channel));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/CreateStreamKey")
    public Response createStreamKey(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            StreamKey key = service.createStreamKey(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("streamKey", toStreamKey(key));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetStreamKey")
    @Consumes(MediaType.WILDCARD)
    public Response getStreamKey(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            StreamKey key = service.getStreamKey(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("streamKey", toStreamKey(key));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeleteStreamKey")
    @Consumes(MediaType.WILDCARD)
    public Response deleteStreamKey(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteStreamKey(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListStreamKeys")
    @Consumes(MediaType.WILDCARD)
    public Response listStreamKeys(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            java.util.List<StreamKey> keys = service.listStreamKeys(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode streamKeys = response.putArray("streamKeys");
            for (StreamKey key : keys) {
                streamKeys.add(toStreamKeySummary(key));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetStream")
    public Response getStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.getStream(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/GetStreamSession")
    public Response getStreamSession(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.getStreamSession(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListStreamSessions")
    @Consumes(MediaType.WILDCARD)
    public Response listStreamSessions(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listStreamSessions(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("streamSessions");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ListStreams")
    @Consumes(MediaType.WILDCARD)
    public Response listStreams(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.listStreams(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("streams");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/PutMetadata")
    public Response putMetadata(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.putMetadata(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StopStream")
    public Response stopStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.stopStream(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/StartViewerSessionRevocation")
    public Response startViewerSessionRevocation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.startViewerSessionRevocation(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/InsertAdBreak")
    public Response insertAdBreak(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.insertAdBreak(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/BatchStartViewerSessionRevocation")
    public Response batchStartViewerSessionRevocation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            java.util.List<IvsService.BatchRevocationError> errors =
                    service.batchStartViewerSessionRevocation(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode errorNodes = response.putArray("errors");
            for (IvsService.BatchRevocationError error : errors) {
                ObjectNode node = errorNodes.addObject();
                node.put("channelArn", error.channelArn());
                node.put("viewerId", error.viewerId());
                if (error.code() != null) {
                    node.put("code", error.code());
                }
                if (error.message() != null) {
                    node.put("message", error.message());
                }
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/CreatePlaybackRestrictionPolicy")
    public Response createPlaybackRestrictionPolicy(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            PlaybackRestrictionPolicy policy = service.createPlaybackRestrictionPolicy(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("playbackRestrictionPolicy", toPolicy(policy));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetPlaybackRestrictionPolicy")
    @Consumes(MediaType.WILDCARD)
    public Response getPlaybackRestrictionPolicy(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            PlaybackRestrictionPolicy policy = service.getPlaybackRestrictionPolicy(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("playbackRestrictionPolicy", toPolicy(policy));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/UpdatePlaybackRestrictionPolicy")
    public Response updatePlaybackRestrictionPolicy(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            PlaybackRestrictionPolicy policy = service.updatePlaybackRestrictionPolicy(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("playbackRestrictionPolicy", toPolicy(policy));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeletePlaybackRestrictionPolicy")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaybackRestrictionPolicy(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deletePlaybackRestrictionPolicy(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListPlaybackRestrictionPolicies")
    @Consumes(MediaType.WILDCARD)
    public Response listPlaybackRestrictionPolicies(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsService.PolicyPage page = service.listPlaybackRestrictionPolicies(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode policies = response.putArray("playbackRestrictionPolicies");
            for (PlaybackRestrictionPolicy policy : page.items()) {
                policies.add(toPolicy(policy));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/ImportPlaybackKeyPair")
    public Response importPlaybackKeyPair(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            PlaybackKeyPair keyPair = service.importPlaybackKeyPair(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("keyPair", toKeyPair(keyPair));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/GetPlaybackKeyPair")
    @Consumes(MediaType.WILDCARD)
    public Response getPlaybackKeyPair(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            PlaybackKeyPair keyPair = service.getPlaybackKeyPair(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("keyPair", toKeyPair(keyPair));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/DeletePlaybackKeyPair")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaybackKeyPair(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deletePlaybackKeyPair(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListPlaybackKeyPairs")
    @Consumes(MediaType.WILDCARD)
    public Response listPlaybackKeyPairs(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            IvsService.KeyPairPage page = service.listPlaybackKeyPairs(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode keyPairs = response.putArray("keyPairs");
            for (PlaybackKeyPair keyPair : page.items()) {
                keyPairs.add(toKeyPairSummary(keyPair));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    private ObjectNode toChannel(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", channel.getArn());
        node.put("name", channel.getName());
        node.put("latencyMode", channel.getLatencyMode());
        node.put("type", channel.getType());
        node.put("authorized", channel.isAuthorized());
        node.put("insecureIngest", channel.isInsecureIngest());
        node.put("ingestEndpoint", channel.getIngestEndpoint());
        node.put("playbackUrl", channel.getPlaybackUrl());
        putOptional(node, "recordingConfigurationArn", channel.getRecordingConfigurationArn());
        putOptional(node, "playbackRestrictionPolicyArn", channel.getPlaybackRestrictionPolicyArn());
        putOptional(node, "preset", channel.getPreset());
        putOptional(node, "containerFormat", channel.getContainerFormat());
        putOptional(node, "adConfigurationArn", channel.getAdConfigurationArn());
        ObjectNode srt = node.putObject("srt");
        putOptional(srt, "endpoint", channel.getSrtEndpoint());
        putOptional(srt, "passphrase", channel.getSrtPassphrase());
        putTags(node, channel.getTags());
        return node;
    }

    private ObjectNode toSummary(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", channel.getArn());
        node.put("name", channel.getName());
        node.put("latencyMode", channel.getLatencyMode());
        node.put("type", channel.getType());
        node.put("authorized", channel.isAuthorized());
        node.put("insecureIngest", channel.isInsecureIngest());
        putOptional(node, "recordingConfigurationArn", channel.getRecordingConfigurationArn());
        putOptional(node, "playbackRestrictionPolicyArn", channel.getPlaybackRestrictionPolicyArn());
        putOptional(node, "preset", channel.getPreset());
        putOptional(node, "adConfigurationArn", channel.getAdConfigurationArn());
        putTags(node, channel.getTags());
        return node;
    }

    private ObjectNode toPolicy(PlaybackRestrictionPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", policy.getArn());
        putOptional(node, "name", policy.getName());
        ArrayNode countries = node.putArray("allowedCountries");
        if (policy.getAllowedCountries() != null) {
            for (String country : policy.getAllowedCountries()) {
                countries.add(country);
            }
        }
        ArrayNode origins = node.putArray("allowedOrigins");
        if (policy.getAllowedOrigins() != null) {
            for (String origin : policy.getAllowedOrigins()) {
                origins.add(origin);
            }
        }
        node.put("enableStrictOriginEnforcement", policy.isEnableStrictOriginEnforcement());
        putTags(node, policy.getTags());
        return node;
    }

    private ObjectNode toKeyPair(PlaybackKeyPair keyPair) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", keyPair.getArn());
        putOptional(node, "name", keyPair.getName());
        putOptional(node, "fingerprint", keyPair.getFingerprint());
        putTags(node, keyPair.getTags());
        return node;
    }

    private ObjectNode toKeyPairSummary(PlaybackKeyPair keyPair) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", keyPair.getArn());
        putOptional(node, "name", keyPair.getName());
        putTags(node, keyPair.getTags());
        return node;
    }

    private ObjectNode toStreamKey(StreamKey key) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", key.getArn());
        node.put("channelArn", key.getChannelArn());
        node.put("value", key.getValue());
        putTags(node, key.getTags());
        return node;
    }

    private ObjectNode toStreamKeySummary(StreamKey key) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", key.getArn());
        node.put("channelArn", key.getChannelArn());
        putTags(node, key.getTags());
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

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
