package io.github.hectorvent.floci.services.kinesisvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.kinesisvideo.model.SignalingChannel;
import io.github.hectorvent.floci.services.kinesisvideo.model.VideoStream;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Kinesis Video Streams restJson1 (PascalCase wire names).
 *
 * <p>Literal paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 * Data-plane APIs share this controller: {@code GetDataEndpoint} / 
 * {@code GetSignalingChannelEndpoint} return the emulator base URL so subsequent
 * signed calls land here.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KinesisVideoController {

    private final KinesisVideoService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public KinesisVideoController(KinesisVideoService service, ObjectMapper objectMapper,
                                  RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/createStream")
    public Response createStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            VideoStream stream = service.createStream(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("StreamARN", stream.getStreamArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describeStream")
    @Consumes(MediaType.WILDCARD)
    public Response describeStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("StreamInfo", toStream(service.describeStream(region(headers), request)));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/deleteStream")
    @Consumes(MediaType.WILDCARD)
    public Response deleteStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteStream(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/listStreams")
    @Consumes(MediaType.WILDCARD)
    public Response listStreams(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            KinesisVideoService.Page<VideoStream> page = service.listStreams(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("StreamInfoList");
            for (VideoStream stream : page.items()) {
                list.add(toStream(stream));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/updateStream")
    public Response updateStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.updateStream(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/updateDataRetention")
    public Response updateDataRetention(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.updateDataRetention(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/listTagsForStream")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> okTags(service.listTagsForStream(region(headers), request)));
    }

    @POST
    @Path("/tagStream")
    public Response tagStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.tagStream(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/untagStream")
    public Response untagStream(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.untagStream(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/createSignalingChannel")
    public Response createSignalingChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            SignalingChannel channel = service.createSignalingChannel(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ChannelARN", channel.getChannelArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describeSignalingChannel")
    @Consumes(MediaType.WILDCARD)
    public Response describeSignalingChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("ChannelInfo", toChannel(service.describeSignalingChannel(region(headers), request)));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/deleteSignalingChannel")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSignalingChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteSignalingChannel(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/listSignalingChannels")
    @Consumes(MediaType.WILDCARD)
    public Response listSignalingChannels(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            KinesisVideoService.Page<SignalingChannel> page =
                    service.listSignalingChannels(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("ChannelInfoList");
            for (SignalingChannel channel : page.items()) {
                list.add(toChannel(channel));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/updateSignalingChannel")
    public Response updateSignalingChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.updateSignalingChannel(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/ListTagsForResource")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(@Context HttpHeaders headers, String body) {
        return handle(body, request -> okTags(service.listTagsForResource(region(headers), request)));
    }

    @POST
    @Path("/TagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.tagResource(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.untagResource(region(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/getDataEndpoint")
    public Response getDataEndpoint(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("DataEndpoint", service.getDataEndpoint(region(headers), request));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getSignalingChannelEndpoint")
    public Response getSignalingChannelEndpoint(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("ResourceEndpointList");
            for (KinesisVideoService.Endpoint endpoint :
                    service.getSignalingChannelEndpoint(region(headers), request)) {
                ObjectNode item = list.addObject();
                item.put("Protocol", endpoint.protocol());
                item.put("ResourceEndpoint", endpoint.resourceEndpoint());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getHLSStreamingSessionURL")
    public Response getHls(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            service.noFragments("for the HLS request.");
            return Response.ok().build();
        });
    }

    @POST
    @Path("/getDASHStreamingSessionURL")
    public Response getDash(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            service.noFragments("for the DASH request.");
            return Response.ok().build();
        });
    }

    @POST
    @Path("/getClip")
    public Response getClip(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            service.noFragments("for the specified time range.");
            return Response.ok().build();
        });
    }

    @POST
    @Path("/getImages")
    public Response getImages(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            service.noFragments(".");
            return Response.ok().build();
        });
    }

    @POST
    @Path("/listFragments")
    public Response listFragments(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("Fragments");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/getMediaForFragmentList")
    public Response getMediaForFragmentList(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            service.invalidFragments();
            return Response.ok().build();
        });
    }

    @POST
    @Path("/getMedia")
    public Response getMedia(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireStreamForMedia(region(headers), request);
            return Response.ok(KinesisVideoService.EMPTY_WEBM)
                    .type("video/webm")
                    .build();
        });
    }

    @POST
    @Path("/v1/get-ice-server-config")
    public Response getIceServerConfig(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireChannel(region(headers), request);
            KinesisVideoService.IceServers ice = service.iceServers();
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("IceServerList");
            ObjectNode server = list.addObject();
            ArrayNode uris = server.putArray("Uris");
            ice.uris().forEach(uris::add);
            server.put("Username", ice.username());
            server.put("Password", ice.password());
            server.put("Ttl", ice.ttl());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/v1/send-alexa-offer-to-master")
    public Response sendAlexaOfferToMaster(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireChannel(region(headers), request);
            service.holdAlexaOffer();
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/joinStorageSession")
    public Response joinStorageSession(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireChannel(region(headers), request);
            throw new AwsException("InvalidArgumentException",
                    "MediaStorageConfiguration is required for WEBRTC protocol", 400);
        });
    }

    @POST
    @Path("/joinStorageSessionAsViewer")
    public Response joinStorageSessionAsViewer(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.requireChannel(region(headers), request);
            throw new AwsException("InvalidArgumentException",
                    "MediaStorageConfiguration is required for WEBRTC protocol", 400);
        });
    }

    private ObjectNode toStream(VideoStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "DeviceName", stream.getDeviceName());
        node.put("StreamName", stream.getStreamName());
        node.put("StreamARN", stream.getStreamArn());
        putOptional(node, "MediaType", stream.getMediaType());
        putOptional(node, "KmsKeyId", stream.getKmsKeyId());
        node.put("Version", stream.getVersion());
        node.put("Status", stream.getStatus());
        node.put("CreationTime", stream.getCreationTime());
        node.put("DataRetentionInHours", stream.getDataRetentionInHours());
        return node;
    }

    private ObjectNode toChannel(SignalingChannel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelName", channel.getChannelName());
        node.put("ChannelARN", channel.getChannelArn());
        node.put("ChannelType", channel.getChannelType());
        node.put("ChannelStatus", channel.getChannelStatus());
        node.put("CreationTime", channel.getCreationTime());
        node.put("Version", channel.getVersion());
        ObjectNode config = node.putObject("SingleMasterConfiguration");
        config.put("MessageTtlSeconds", channel.getMessageTtlSeconds());
        return node;
    }

    private Response okTags(Map<String, String> tags) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = response.putObject("Tags");
        tags.forEach(tagsNode::put);
        return Response.ok(response).build();
    }

    private static void putOptional(ObjectNode parent, String field, String value) {
        if (value != null) {
            parent.put(field, value);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
        node.put("Message", exception.getMessage());
        node.put("message", exception.getMessage());
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
