package io.github.hectorvent.floci.services.kinesisvideo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisvideo.model.SignalingChannel;
import io.github.hectorvent.floci.services.kinesisvideo.model.VideoStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Amazon Kinesis Video Streams restJson1 — stream and signaling-channel
 * control plane plus the archived-media / media / signaling / WebRTC-storage
 * data plane Alchemy Bindings.test.ts exercises.
 */
@ApplicationScoped
public class KinesisVideoService {

    static final String SERVICE = "kinesisvideo";
    static final byte[] EMPTY_WEBM = hex(
            "1A45DFA39F4286810142F7810142F2810442F381084282847765626D4287810442858102");
    private static final int DEFAULT_MESSAGE_TTL = 60;
    private static final Set<String> CHANNEL_TYPES = Set.of("SINGLE_MASTER", "FULL_MESH");
    private static final Set<String> API_NAMES = Set.of(
            "PUT_MEDIA", "GET_MEDIA", "LIST_FRAGMENTS", "GET_MEDIA_FOR_FRAGMENT_LIST",
            "GET_HLS_STREAMING_SESSION_URL", "GET_DASH_STREAMING_SESSION_URL",
            "GET_CLIP", "GET_IMAGES");
    private static final long ALEXA_OFFER_HOLD_MS = 8_000L;
    private static final TypeReference<Map<String, VideoStream>> STREAM_MAP = new StreamMap();
    private static final TypeReference<Map<String, SignalingChannel>> CHANNEL_MAP = new ChannelMap();

    private static final class StreamMap extends TypeReference<Map<String, VideoStream>> {
    }

    private static final class ChannelMap extends TypeReference<Map<String, SignalingChannel>> {
    }

    private final StorageBackend<String, VideoStream> streams;
    private final StorageBackend<String, SignalingChannel> channels;
    private final RegionResolver regionResolver;
    private final ContainerReachableEndpoint reachableEndpoint;
    private final String dataEndpointOverride;

    @Inject
    public KinesisVideoService(StorageFactory storageFactory, RegionResolver regionResolver,
                               ContainerReachableEndpoint reachableEndpoint) {
        this(storageFactory.create("kinesisvideo", "kinesisvideo-streams.json", STREAM_MAP),
                storageFactory.create("kinesisvideo", "kinesisvideo-channels.json", CHANNEL_MAP),
                regionResolver, reachableEndpoint, null);
    }

    KinesisVideoService(StorageBackend<String, VideoStream> streams,
                        StorageBackend<String, SignalingChannel> channels,
                        RegionResolver regionResolver,
                        String dataEndpoint) {
        this(streams, channels, regionResolver, null, dataEndpoint);
    }

    private KinesisVideoService(StorageBackend<String, VideoStream> streams,
                                StorageBackend<String, SignalingChannel> channels,
                                RegionResolver regionResolver,
                                ContainerReachableEndpoint reachableEndpoint,
                                String dataEndpointOverride) {
        this.streams = streams;
        this.channels = channels;
        this.regionResolver = regionResolver;
        this.reachableEndpoint = reachableEndpoint;
        this.dataEndpointOverride = dataEndpointOverride;
    }

    public synchronized VideoStream createStream(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "StreamName");
        if (findStreamByName(region, name) != null) {
            throw inUse("The stream " + name + " already exists.");
        }
        long now = System.currentTimeMillis();
        VideoStream stream = new VideoStream();
        stream.setStreamName(name);
        stream.setStreamArn(arn(region, "stream/" + name + "/" + now));
        stream.setDeviceName(optionalText(request, "DeviceName"));
        stream.setMediaType(optionalText(request, "MediaType"));
        String kms = optionalText(request, "KmsKeyId");
        stream.setKmsKeyId(kms == null ? "alias/aws/kinesisvideo" : kms);
        stream.setVersion("1");
        stream.setStatus("ACTIVE");
        stream.setCreationTime(now / 1000);
        stream.setDataRetentionInHours(optionalInt(request, "DataRetentionInHours", 0));
        stream.setTags(readTagMap(request.get("Tags")));
        streams.put(storageKey(region, name), stream);
        return stream;
    }

    public VideoStream describeStream(String region, JsonNode request) {
        requireObject(request);
        return requireStream(region, request);
    }

    public synchronized void deleteStream(String region, JsonNode request) {
        requireObject(request);
        VideoStream stream = requireStream(region, request);
        String current = optionalText(request, "CurrentVersion");
        if (current != null && !current.equals(stream.getVersion())) {
            throw versionMismatch();
        }
        streams.delete(storageKey(region, stream.getStreamName()));
    }

    public Page<VideoStream> listStreams(String region, JsonNode request) {
        requireObject(request);
        String prefix = beginsWith(request.get("StreamNameCondition"));
        List<VideoStream> items = streams.scan(key -> key.startsWith(region + "::")).stream()
                .filter(s -> prefix == null || s.getStreamName().startsWith(prefix))
                .sorted(Comparator.comparing(VideoStream::getStreamName))
                .toList();
        return paginate(items, request);
    }

    public synchronized VideoStream updateStream(String region, JsonNode request) {
        requireObject(request);
        VideoStream stream = requireStream(region, request);
        requireCurrentVersion(request, stream.getVersion());
        String device = optionalText(request, "DeviceName");
        if (device != null) {
            stream.setDeviceName(device);
        }
        String media = optionalText(request, "MediaType");
        if (media != null) {
            stream.setMediaType(media);
        }
        bumpVersion(stream);
        streams.put(storageKey(region, stream.getStreamName()), stream);
        return stream;
    }

    public synchronized VideoStream updateDataRetention(String region, JsonNode request) {
        requireObject(request);
        VideoStream stream = requireStream(region, request);
        requireCurrentVersion(request, stream.getVersion());
        String operation = requireText(request, "Operation");
        int change = requireInt(request, "DataRetentionChangeInHours");
        if (change < 1) {
            throw invalid("DataRetentionChangeInHours must be at least 1.");
        }
        int current = stream.getDataRetentionInHours();
        if ("INCREASE_DATA_RETENTION".equals(operation)) {
            stream.setDataRetentionInHours(current + change);
        } else if ("DECREASE_DATA_RETENTION".equals(operation)) {
            stream.setDataRetentionInHours(Math.max(0, current - change));
        } else {
            throw invalid("Operation must be INCREASE_DATA_RETENTION or DECREASE_DATA_RETENTION.");
        }
        bumpVersion(stream);
        streams.put(storageKey(region, stream.getStreamName()), stream);
        return stream;
    }

    public Map<String, String> listTagsForStream(String region, JsonNode request) {
        requireObject(request);
        return new LinkedHashMap<>(requireStream(region, request).getTags());
    }

    public synchronized void tagStream(String region, JsonNode request) {
        requireObject(request);
        VideoStream stream = requireStream(region, request);
        stream.getTags().putAll(readTagMap(request.get("Tags")));
        streams.put(storageKey(region, stream.getStreamName()), stream);
    }

    public synchronized void untagStream(String region, JsonNode request) {
        requireObject(request);
        VideoStream stream = requireStream(region, request);
        for (String key : readStringList(request, "TagKeyList")) {
            stream.getTags().remove(key);
        }
        streams.put(storageKey(region, stream.getStreamName()), stream);
    }

    public synchronized SignalingChannel createSignalingChannel(String region, JsonNode request) {
        requireObject(request);
        String name = requireText(request, "ChannelName");
        if (findChannelByName(region, name) != null) {
            throw inUse("The signaling channel " + name + " already exists.");
        }
        String type = optionalText(request, "ChannelType");
        if (type == null) {
            type = "SINGLE_MASTER";
        }
        if (!CHANNEL_TYPES.contains(type)) {
            throw invalid("ChannelType must be SINGLE_MASTER or FULL_MESH.");
        }
        int ttl = DEFAULT_MESSAGE_TTL;
        JsonNode config = request.get("SingleMasterConfiguration");
        if (config != null && config.isObject()) {
            ttl = optionalInt(config, "MessageTtlSeconds", DEFAULT_MESSAGE_TTL);
        }
        long now = System.currentTimeMillis();
        SignalingChannel channel = new SignalingChannel();
        channel.setChannelName(name);
        channel.setChannelArn(arn(region, "channel/" + name + "/" + now));
        channel.setChannelType(type);
        channel.setChannelStatus("ACTIVE");
        channel.setVersion("1");
        channel.setCreationTime(now / 1000);
        channel.setMessageTtlSeconds(ttl);
        channel.setTags(readTagList(request.get("Tags")));
        channels.put(storageKey(region, name), channel);
        return channel;
    }

    public SignalingChannel describeSignalingChannel(String region, JsonNode request) {
        requireObject(request);
        return requireChannel(region, request);
    }

    public synchronized void deleteSignalingChannel(String region, JsonNode request) {
        requireObject(request);
        SignalingChannel channel = requireChannel(region, request);
        String current = optionalText(request, "CurrentVersion");
        if (current != null && !current.equals(channel.getVersion())) {
            throw versionMismatch();
        }
        channels.delete(storageKey(region, channel.getChannelName()));
    }

    public Page<SignalingChannel> listSignalingChannels(String region, JsonNode request) {
        requireObject(request);
        String prefix = beginsWith(request.get("ChannelNameCondition"));
        List<SignalingChannel> items = channels.scan(key -> key.startsWith(region + "::")).stream()
                .filter(c -> prefix == null || c.getChannelName().startsWith(prefix))
                .sorted(Comparator.comparing(SignalingChannel::getChannelName))
                .toList();
        return paginate(items, request);
    }

    public synchronized SignalingChannel updateSignalingChannel(String region, JsonNode request) {
        requireObject(request);
        SignalingChannel channel = requireChannel(region, request);
        requireCurrentVersion(request, channel.getVersion());
        JsonNode config = request.get("SingleMasterConfiguration");
        if (config != null && config.isObject()) {
            channel.setMessageTtlSeconds(optionalInt(config, "MessageTtlSeconds",
                    channel.getMessageTtlSeconds()));
        }
        bumpVersion(channel);
        channels.put(storageKey(region, channel.getChannelName()), channel);
        return channel;
    }

    public Map<String, String> listTagsForResource(String region, JsonNode request) {
        requireObject(request);
        return new LinkedHashMap<>(requireChannel(region, request).getTags());
    }

    public synchronized void tagResource(String region, JsonNode request) {
        requireObject(request);
        SignalingChannel channel = requireChannel(region, request);
        channel.getTags().putAll(readTagList(request.get("Tags")));
        channels.put(storageKey(region, channel.getChannelName()), channel);
    }

    public synchronized void untagResource(String region, JsonNode request) {
        requireObject(request);
        SignalingChannel channel = requireChannel(region, request);
        for (String key : readStringList(request, "TagKeyList")) {
            channel.getTags().remove(key);
        }
        channels.put(storageKey(region, channel.getChannelName()), channel);
    }

    public String getDataEndpoint(String region, JsonNode request) {
        requireObject(request);
        requireStream(region, request);
        String apiName = requireText(request, "APIName");
        if (!API_NAMES.contains(apiName)) {
            throw invalid("APIName is not valid.");
        }
        return dataEndpoint();
    }

    public List<Endpoint> getSignalingChannelEndpoint(String region, JsonNode request) {
        requireObject(request);
        SignalingChannel channel = requireChannel(region, request);
        JsonNode config = request.get("SingleMasterChannelEndpointConfiguration");
        if (config == null || !config.isObject()) {
            throw invalid("SingleMasterChannelEndpointConfiguration is required.");
        }
        List<String> protocols = readStringList(config, "Protocols");
        if (protocols.isEmpty()) {
            throw invalid("Protocols is required.");
        }
        String base = dataEndpoint();
        List<Endpoint> endpoints = new ArrayList<>();
        for (String protocol : protocols) {
            if ("WEBRTC".equals(protocol) && !channel.isMediaStorageConfigured()) {
                throw invalid("MediaStorageConfiguration is required for WEBRTC protocol");
            }
            String url = switch (protocol) {
                case "WSS" -> base.replaceFirst("^http", "ws");
                case "HTTPS", "WEBRTC" -> base;
                default -> throw invalid("Protocol " + protocol + " is not supported.");
            };
            endpoints.add(new Endpoint(protocol, url));
        }
        return endpoints;
    }

    public void requireStreamForMedia(String region, JsonNode request) {
        requireStream(region, request);
    }

    public void noFragments(String detail) {
        throw new AwsException("ResourceNotFoundException",
                "No fragments found in the stream" + (detail == null ? "." : " " + detail),
                404);
    }

    public void invalidFragments() {
        throw invalid("Fragment numbers are invalid");
    }

    public void holdAlexaOffer() {
        try {
            TimeUnit.MILLISECONDS.sleep(ALEXA_OFFER_HOLD_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public IceServers iceServers() {
        String host;
        try {
            host = java.net.URI.create(dataEndpoint()).getHost();
        } catch (Exception e) {
            host = "localhost";
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        return new IceServers(
                List.of("turn:" + host + ":3478?transport=udp", "turns:" + host + ":443?transport=tcp"),
                "floci",
                "floci-ice-secret",
                300);
    }

    VideoStream requireStream(String region, JsonNode request) {
        String name = optionalText(request, "StreamName");
        String arn = firstText(request, "StreamARN", "StreamArn");
        if (name == null && arn == null) {
            throw invalid("StreamName or StreamARN is required.");
        }
        VideoStream stream = name != null ? findStreamByName(region, name) : findStreamByArn(region, arn);
        if (stream == null) {
            throw notFound("The specified stream is not found.");
        }
        return stream;
    }

    SignalingChannel requireChannel(String region, JsonNode request) {
        String name = optionalText(request, "ChannelName");
        String arn = firstText(request, "ChannelARN", "channelArn", "ResourceARN");
        if (name == null && arn == null) {
            throw invalid("ChannelName or ChannelARN is required.");
        }
        SignalingChannel channel = name != null
                ? findChannelByName(region, name)
                : findChannelByArn(region, arn);
        if (channel == null) {
            throw notFound("The specified signaling channel is not found.");
        }
        return channel;
    }

    private VideoStream findStreamByName(String region, String name) {
        return streams.get(storageKey(region, name)).orElse(null);
    }

    private VideoStream findStreamByArn(String region, String arn) {
        String name = resourceName(arn, "stream/");
        if (name != null) {
            VideoStream byName = findStreamByName(regionFrom(arn, region), name);
            if (byName != null && arn.equals(byName.getStreamArn())) {
                return byName;
            }
        }
        for (VideoStream stream : streams.scan(key -> true)) {
            if (arn.equals(stream.getStreamArn())) {
                return stream;
            }
        }
        return null;
    }

    private SignalingChannel findChannelByName(String region, String name) {
        return channels.get(storageKey(region, name)).orElse(null);
    }

    private SignalingChannel findChannelByArn(String region, String arn) {
        String name = resourceName(arn, "channel/");
        if (name != null) {
            SignalingChannel byName = findChannelByName(regionFrom(arn, region), name);
            if (byName != null && arn.equals(byName.getChannelArn())) {
                return byName;
            }
        }
        for (SignalingChannel channel : channels.scan(key -> true)) {
            if (arn.equals(channel.getChannelArn())) {
                return channel;
            }
        }
        return null;
    }

    private String dataEndpoint() {
        if (dataEndpointOverride != null) {
            return dataEndpointOverride;
        }
        return reachableEndpoint.baseUrl();
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static void bumpVersion(VideoStream stream) {
        stream.setVersion(Integer.toString(Integer.parseInt(stream.getVersion()) + 1));
    }

    private static void bumpVersion(SignalingChannel channel) {
        channel.setVersion(Integer.toString(Integer.parseInt(channel.getVersion()) + 1));
    }

    private static void requireCurrentVersion(JsonNode request, String expected) {
        String current = requireText(request, "CurrentVersion");
        if (!current.equals(expected)) {
            throw versionMismatch();
        }
    }

    private static <T> Page<T> paginate(List<T> items, JsonNode request) {
        int max = optionalInt(request, "MaxResults", items.size() == 0 ? 1 : items.size());
        int start = 0;
        String token = optionalText(request, "NextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw invalid("NextToken is invalid.");
            }
        }
        if (start < 0) {
            start = 0;
        }
        int end = Math.min(items.size(), start + Math.max(1, max));
        List<T> page = items.subList(Math.min(start, items.size()), end);
        String next = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(page, next);
    }

    private static String beginsWith(JsonNode condition) {
        if (condition == null || condition.isNull() || !condition.isObject()) {
            return null;
        }
        String value = optionalText(condition, "ComparisonValue");
        return value == null || value.isBlank() ? null : value;
    }

    private static String resourceName(String arn, String prefix) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            if (resource == null || !resource.startsWith(prefix)) {
                return null;
            }
            String rest = resource.substring(prefix.length());
            int last = rest.lastIndexOf('/');
            return last <= 0 ? rest : rest.substring(0, last);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String regionFrom(String arn, String fallback) {
        return AwsArnUtils.regionOrDefault(arn, fallback);
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static String firstText(JsonNode parent, String... fields) {
        for (String field : fields) {
            String value = optionalText(parent, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int optionalInt(JsonNode parent, String field, int defaultValue) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        return requireInt(parent, field);
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !(value.isNumber() || value.isTextual())) {
            throw invalid(field + " must be an integer.");
        }
        try {
            return value.isNumber() ? value.intValue() : Integer.parseInt(value.textValue());
        } catch (NumberFormatException e) {
            throw invalid(field + " must be an integer.");
        }
    }

    private static Map<String, String> readTagMap(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw invalid("Tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                tags.put(entry.getKey(), value.textValue());
            }
        });
        return tags;
    }

    private static Map<String, String> readTagList(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (tagsNode.isObject()) {
            return readTagMap(tagsNode);
        }
        if (!tagsNode.isArray()) {
            throw invalid("Tags must be an array of {Key,Value} objects.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode item : tagsNode) {
            if (item == null || !item.isObject()) {
                throw invalid("Tags must be an array of {Key,Value} objects.");
            }
            String key = optionalText(item, "Key");
            String value = optionalText(item, "Value");
            if (key != null && value != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return List.of();
        }
        JsonNode value = parent.get(field);
        if (!value.isArray()) {
            throw invalid(field + " must be an array of strings.");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw invalid(field + " must be an array of strings.");
            }
            items.add(item.textValue());
        }
        return items;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }

    private static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }

    private static AwsException versionMismatch() {
        return new AwsException("VersionMismatchException", "The stream/channel version does not match.", 400);
    }

    private static byte[] hex(String value) {
        int length = value.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Endpoint(String protocol, String resourceEndpoint) {
    }

    public record IceServers(List<String> uris, String username, String password, int ttl) {
    }
}
