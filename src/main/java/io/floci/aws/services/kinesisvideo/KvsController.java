package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The Kinesis Video Streams REST APIs (the control plane, media, archived media, signaling and WebRTC storage), routed
 * here by {@link KvsRouteFilter}. Requests are JSON bodies checked against the APIs' shapes; {@code GetMedia},
 * {@code GetMediaForFragmentList} and {@code GetClip} answer media with its {@code Content-Type}, and
 * {@code PutMedia} streams its acknowledgements as it reads the producer's media.
 */
@Path(KvsRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class KvsController {

    private static final List<String> MODELS = List.of("kinesisvideo", "kinesis-video-media",
            "kinesis-video-archived-media", "kinesis-video-signaling", "kinesis-video-webrtc-storage");
    private static final Map<String, RequestConstraintCheck> SHAPES = new HashMap<>();

    static {
        for (String model : MODELS) {
            SHAPES.put(model, RequestConstraintCheck.load("aws/" + model + "-request-shapes.json", model
                    + " request shapes", new RequestConstraintCheck.Messages() {
                        @Override
                        public AwsException type(String path, String type) {
                            return KvsStore.invalid(path + " must be a " + type + ".");
                        }

                        @Override
                        public AwsException bounds(String path, String type, Long min, Long max, long size) {
                            return KvsStore.invalid(path + " has " + size + ", outside " + (min == null ? "" : min)
                                    + ".." + (max == null ? "" : max) + ".");
                        }

                        @Override
                        public AwsException pattern(String path, String pattern) {
                            return KvsStore.invalid(path + " must match " + pattern + ".");
                        }

                        @Override
                        public AwsException enumValue(String path, List<String> values) {
                            return KvsStore.invalid(path + " must be one of " + String.join(", ", values) + ".");
                        }

                        @Override
                        public AwsException required(String path) {
                            return KvsStore.invalid(path + " is required.");
                        }
                    }));
        }
    }

    private final KvsService service;
    private final KvsMedia media;
    private final KvsArchive archive;
    private final KvsSignaling signaling;
    private final KvsStore store;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public KvsController(KvsService service, KvsMedia media, KvsArchive archive, KvsSignaling signaling,
                         KvsStore store, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.media = media;
        this.archive = archive;
        this.signaling = signaling;
        this.store = store;
        this.regions = regions;
        this.mapper = mapper;
    }

    /** The operation a path names, and the API it belongs to. */
    private record Operation(String name, String model) {
    }

    private static Operation operation(String path) {
        return switch (path) {
            case "getMedia" -> new Operation("GetMedia", "kinesis-video-media");
            case "getClip" -> new Operation("GetClip", "kinesis-video-archived-media");
            case "getDASHStreamingSessionURL" -> new Operation("GetDASHStreamingSessionURL",
                    "kinesis-video-archived-media");
            case "getHLSStreamingSessionURL" -> new Operation("GetHLSStreamingSessionURL",
                    "kinesis-video-archived-media");
            case "getImages" -> new Operation("GetImages", "kinesis-video-archived-media");
            case "getMediaForFragmentList" -> new Operation("GetMediaForFragmentList", "kinesis-video-archived-media");
            case "listFragments" -> new Operation("ListFragments", "kinesis-video-archived-media");
            case "joinStorageSession" -> new Operation("JoinStorageSession", "kinesis-video-webrtc-storage");
            case "joinStorageSessionAsViewer" -> new Operation("JoinStorageSessionAsViewer",
                    "kinesis-video-webrtc-storage");
            default -> new Operation(Character.toUpperCase(path.charAt(0)) + path.substring(1), "kinesisvideo");
        };
    }

    @POST
    @Path("/v1/{op}")
    public Response signaling(@PathParam("op") String op, String body) {
        String name = switch (op) {
            case "get-ice-server-config" -> "GetIceServerConfig";
            case "send-alexa-offer-to-master" -> "SendAlexaOfferToMaster";
            default -> null;
        };
        if (name == null) {
            return error(new AwsException("UnknownOperationException", "Unknown operation /v1/" + op, 404));
        }
        return json(new Operation(name, "kinesis-video-signaling"), body, in -> dispatch(name, in,
                regions.getRegion()));
    }

    @POST
    @Path("/putMedia")
    @Produces(MediaType.APPLICATION_JSON)
    public Response putMedia(InputStream body, @Context HttpHeaders headers) {
        Map<String, String> values = new HashMap<>();
        headers.getRequestHeaders().forEach((k, v) -> values.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? null
                : v.get(0)));
        String account = store.account();
        String region = regions.getRegion();
        try {
            ObjectNode probe = mapper.createObjectNode();
            if (values.get("x-amzn-stream-name") != null) {
                probe.put("StreamName", values.get("x-amzn-stream-name"));
            }
            if (values.get("x-amzn-stream-arn") != null) {
                probe.put("StreamARN", values.get("x-amzn-stream-arn"));
            }
            service.activeStream(account, region, probe);
        } catch (AwsException e) {
            return error(e);
        }
        StreamingOutput output = out -> media.putMedia(account, region, values, body, out);
        return Response.ok(output, MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/{op}")
    public Response call(@PathParam("op") String op, String body) {
        Operation operation = operation(op);
        String region = regions.getRegion();
        String account = store.account();
        switch (operation.name()) {
            case "GetMedia" -> {
                try {
                    ObjectNode in = parse(operation, body);
                    KvsMedia.Start start = media.start(account, region, in);
                    StreamingOutput output = out -> media.getMedia(start, out);
                    return Response.ok(output, "video/webm").build();
                } catch (AwsException e) {
                    return error(e);
                }
            }
            case "GetMediaForFragmentList" -> {
                return binary(operation, body, "video/webm", in -> archive.getMediaForFragmentList(in, region));
            }
            case "GetClip" -> {
                return binary(operation, body, "video/mp4", in -> archive.getClip(in, region));
            }
            default -> {
                return json(operation, body, in -> dispatch(operation.name(), in, region));
            }
        }
    }

    private ObjectNode dispatch(String name, ObjectNode in, String region) {
        return switch (name) {
            case "CreateSignalingChannel" -> service.createSignalingChannel(in, region);
            case "CreateStream" -> service.createStream(in, region);
            case "DeleteEdgeConfiguration" -> service.deleteEdgeConfiguration(in, region);
            case "DeleteSignalingChannel" -> service.deleteSignalingChannel(in, region);
            case "DeleteStream" -> service.deleteStream(in, region);
            case "DescribeEdgeConfiguration" -> service.describeEdgeConfiguration(in, region);
            case "DescribeImageGenerationConfiguration" -> service.describeImageGenerationConfiguration(in, region);
            case "DescribeMappedResourceConfiguration" -> service.describeMappedResourceConfiguration(in, region);
            case "DescribeMediaStorageConfiguration" -> service.describeMediaStorageConfiguration(in, region);
            case "DescribeNotificationConfiguration" -> service.describeNotificationConfiguration(in, region);
            case "DescribeSignalingChannel" -> service.describeSignalingChannel(in, region);
            case "DescribeStream" -> service.describeStream(in, region);
            case "DescribeStreamStorageConfiguration" -> service.describeStreamStorageConfiguration(in, region);
            case "GetDataEndpoint" -> service.getDataEndpoint(in, region);
            case "GetSignalingChannelEndpoint" -> service.getSignalingChannelEndpoint(in, region);
            case "ListEdgeAgentConfigurations" -> service.listEdgeAgentConfigurations(in, region);
            case "ListSignalingChannels" -> service.listSignalingChannels(in, region);
            case "ListStreams" -> service.listStreams(in, region);
            case "ListTagsForResource" -> service.listTagsForResource(in, region);
            case "ListTagsForStream" -> service.listTagsForStream(in, region);
            case "StartEdgeConfigurationUpdate" -> service.startEdgeConfigurationUpdate(in, region);
            case "TagResource" -> service.tagResource(in, region);
            case "TagStream" -> service.tagStream(in, region);
            case "UntagResource" -> service.untagResource(in, region);
            case "UntagStream" -> service.untagStream(in, region);
            case "UpdateDataRetention" -> service.updateDataRetention(in, region);
            case "UpdateImageGenerationConfiguration" -> service.updateImageGenerationConfiguration(in, region);
            case "UpdateMediaStorageConfiguration" -> service.updateMediaStorageConfiguration(in, region);
            case "UpdateNotificationConfiguration" -> service.updateNotificationConfiguration(in, region);
            case "UpdateSignalingChannel" -> service.updateSignalingChannel(in, region);
            case "UpdateStream" -> service.updateStream(in, region);
            case "UpdateStreamStorageConfiguration" -> service.updateStreamStorageConfiguration(in, region);
            case "GetDASHStreamingSessionURL" -> archive.getDashStreamingSessionUrl(in, region);
            case "GetHLSStreamingSessionURL" -> archive.getHlsStreamingSessionUrl(in, region);
            case "GetImages" -> archive.getImages(in, region);
            case "ListFragments" -> archive.listFragments(in, region);
            case "JoinStorageSession" -> signaling.joinStorageSession(in, region);
            case "JoinStorageSessionAsViewer" -> signaling.joinStorageSessionAsViewer(in, region);
            case "GetIceServerConfig" -> signaling.getIceServerConfig(in, region);
            case "SendAlexaOfferToMaster" -> signaling.sendAlexaOfferToMaster(in, region);
            // PutMedia streams its body, so it has its own method; a JSON call to it names the endpoint.
            case "PutMedia" -> throw KvsStore.invalid("PutMedia streams the producer's media: POST it to /putMedia.");
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + name, 404);
        };
    }

    private ObjectNode parse(Operation operation, String body) {
        ObjectNode in;
        if (body == null || body.isBlank()) {
            in = mapper.createObjectNode();
        } else {
            try {
                JsonNode parsed = mapper.readTree(body);
                if (!parsed.isObject()) {
                    throw KvsStore.invalid("The request body must be a JSON object.");
                }
                in = (ObjectNode) parsed;
            } catch (JsonProcessingException e) {
                throw KvsStore.invalid("The request body is not valid JSON.");
            }
        }
        RequestConstraintCheck check = SHAPES.get(operation.model());
        if (check != null) {
            check.check(operation.name(), in);
        }
        return in;
    }

    private Response json(Operation operation, String body, Function<ObjectNode, ObjectNode> handler) {
        try {
            ObjectNode out = handler.apply(parse(operation, body));
            return Response.ok(out.toString(), MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response binary(Operation operation, String body, String type, Function<ObjectNode, byte[]> handler) {
        try {
            byte[] out = handler.apply(parse(operation, body));
            return Response.ok(out, type).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException e) {
        ObjectNode error = mapper.createObjectNode();
        error.put("Message", e.getMessage());
        return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
    }
}
