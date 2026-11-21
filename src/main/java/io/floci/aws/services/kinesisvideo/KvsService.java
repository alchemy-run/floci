package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerReachableUrls;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.floci.aws.services.kinesisvideo.KvsStore.CHANNEL;
import static io.floci.aws.services.kinesisvideo.KvsStore.STREAM;
import static io.floci.aws.services.kinesisvideo.KvsStore.invalid;
import static io.floci.aws.services.kinesisvideo.KvsStore.meta;
import static io.floci.aws.services.kinesisvideo.KvsStore.notFound;
import static io.floci.aws.services.kinesisvideo.KvsStore.text;
import static io.floci.aws.services.kinesisvideo.KvsStore.view;

/**
 * Kinesis Video Streams' control plane: streams (retention, media type, device, encryption key, storage tier),
 * signaling channels, tags, the notification, image generation, media storage and edge configurations, and the data
 * and signaling endpoints, which are Floci's own.
 *
 * <p>Streams and channels are {@code CREATING} for a transition, then {@code ACTIVE}; updates go {@code UPDATING} and
 * deletes {@code DELETING}. Every change takes the resource's current version and gives it a new one, and is refused
 * while the resource isn't {@code ACTIVE}. Edge configurations stay {@code SYNCING} until an edge agent acknowledges
 * them, and Floci runs no edge agent.
 */
@ApplicationScoped
public class KvsService {

    static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_.\\-]+");
    static final int MAX_TAGS = 50;

    private final KvsStore store;
    private final KmsService kms;

    @Inject
    public KvsService(KvsStore store, KmsService kms) {
        this.store = store;
        this.kms = kms;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private ObjectNode empty() {
        return mapper().createObjectNode();
    }

    static String version() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    // ---------------------------------------------------------------- statuses

    void pending(ObjectNode record, String field, String now, String then) {
        record.put(field, now);
        meta(record).put("settleAt", System.currentTimeMillis() + store.config().transitionMillis())
                .put("settleTo", then).put("settleField", field);
    }

    ObjectNode settle(String account, String region, String kind, String name, ObjectNode record) {
        if (record == null) {
            return null;
        }
        ObjectNode m = meta(record);
        if (m.has("settleAt") && m.get("settleAt").asLong() <= System.currentTimeMillis()) {
            String to = m.get("settleTo").asText();
            String field = m.get("settleField").asText();
            m.remove(List.of("settleAt", "settleTo", "settleField"));
            if ("DELETED".equals(to)) {
                store.delete(account, region, kind, name);
                return null;
            }
            record.put(field, to);
            store.put(account, region, kind, name, record);
        }
        return record;
    }

    // ---------------------------------------------------------------- streams

    /** The stream a request names by {@code StreamName} or {@code StreamARN} (or the given member names). */
    ObjectNode stream(String account, String region, JsonNode in, String nameField, String arnField) {
        String name = text(in, nameField);
        String arn = text(in, arnField);
        if (name == null && arn == null) {
            throw invalid("Specify either " + nameField + " or " + arnField + ".");
        }
        if (name == null) {
            name = nameOf(arn, STREAM);
        }
        ObjectNode stream = settle(account, region, STREAM, name, store.get(account, region, STREAM, name)
                .orElse(null));
        if (stream == null || arn != null && !arn.equals(stream.path("StreamARN").asText())) {
            throw notFound("The requested stream is not found or not active.");
        }
        return stream;
    }

    ObjectNode stream(String account, String region, JsonNode in) {
        return stream(account, region, in, "StreamName", "StreamARN");
    }

    ObjectNode activeStream(String account, String region, JsonNode in) {
        ObjectNode stream = stream(account, region, in);
        if (!"ACTIVE".equals(stream.path("Status").asText())) {
            throw notFound("The stream " + stream.path("StreamName").asText() + " is not active.");
        }
        return stream;
    }

    static String nameOf(String arn, String kind) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            String[] parts = resource.split("/");
            if (parts.length >= 2 && kind.equals(parts[0])) {
                return parts[1];
            }
        } catch (IllegalArgumentException e) {
            // Not an ARN.
        }
        throw invalid("The ARN " + arn + " is not a Kinesis Video " + kind + " ARN.");
    }

    public ObjectNode createStream(JsonNode in, String region) {
        String account = store.account();
        String name = text(in, "StreamName");
        if (!NAME.matcher(name).matches()) {
            throw invalid("The stream name " + name + " is not valid.");
        }
        if (store.get(account, region, STREAM, name).isPresent()) {
            throw KvsStore.inUse("The stream " + name + " already exists.");
        }
        if (store.list(account, region, STREAM).size() >= store.config().maxStreams()) {
            throw new AwsException("AccountStreamLimitExceededException", "The account has reached its limit of "
                    + store.config().maxStreams() + " streams.", 400);
        }
        int retention = in.path("DataRetentionInHours").asInt(0);
        if (retention < 0 || retention > 87_600) {
            throw invalid("DataRetentionInHours must be between 0 and 87600.");
        }
        long created = System.currentTimeMillis();
        String arn = store.arn(account, region, STREAM, name, created);
        ObjectNode stream = store.record();
        if (in.hasNonNull("DeviceName")) {
            stream.put("DeviceName", text(in, "DeviceName"));
        }
        stream.put("StreamName", name).put("StreamARN", arn);
        if (in.hasNonNull("MediaType")) {
            stream.put("MediaType", text(in, "MediaType"));
        }
        stream.put("KmsKeyId", kmsKey(in, region, account)).put("Version", version())
                .put("CreationTime", created / 1000.0).put("DataRetentionInHours", retention);
        JsonNode storage = in.get("StreamStorageConfiguration");
        meta(stream).put("storageTier", storage == null ? "HOT" : storage.path("DefaultStorageTier").asText("HOT"));
        ObjectNode tags = meta(stream).putObject("tags");
        in.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        checkTags(tags);
        pending(stream, "Status", "CREATING", "ACTIVE");
        store.put(account, region, STREAM, name, stream);
        return empty().put("StreamARN", arn);
    }

    private String kmsKey(JsonNode in, String region, String account) {
        String key = text(in, "KmsKeyId");
        if (key == null) {
            return AwsArnUtils.Arn.of("kms", region, account, "alias/aws/kinesisvideo").toString();
        }
        if (!key.startsWith("alias/aws/") && kms.findKeyArn(key, region).isEmpty()) {
            throw invalid("The KMS key " + key + " doesn't exist.");
        }
        return key;
    }

    public ObjectNode describeStream(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("StreamInfo", view(stream(store.account(), region, in)));
        return out;
    }

    public ObjectNode listStreams(JsonNode in, String region) {
        String account = store.account();
        String prefix = in.path("StreamNameCondition").path("ComparisonValue").asText("");
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode stream : store.list(account, region, STREAM)) {
            ObjectNode settled = settle(account, region, STREAM, stream.path("StreamName").asText(), stream);
            if (settled != null && settled.path("StreamName").asText().startsWith(prefix)) {
                items.add(view(settled));
            }
        }
        return page(items, in, "StreamInfoList");
    }

    private ObjectNode page(List<JsonNode> items, JsonNode in, String name) {
        int start = 0;
        String token = text(in, "NextToken");
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        int max = in.hasNonNull("MaxResults") ? in.get("MaxResults").asInt() : 10_000;
        int end = Math.min(items.size(), start + max);
        ObjectNode out = empty();
        ArrayNode array = out.putArray(name);
        if (start < items.size()) {
            items.subList(start, end).forEach(array::add);
        }
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private void checkVersion(ObjectNode record, JsonNode in, boolean required) {
        String version = text(in, "CurrentVersion");
        if (version == null && !required) {
            return;
        }
        if (!record.path("Version").asText().equals(version)) {
            throw KvsStore.versionMismatch("The version " + version + " is not the current version.");
        }
    }

    public ObjectNode updateStream(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        checkVersion(stream, in, true);
        if (in.hasNonNull("DeviceName")) {
            stream.put("DeviceName", text(in, "DeviceName"));
        }
        if (in.hasNonNull("MediaType")) {
            stream.put("MediaType", text(in, "MediaType"));
        }
        stream.put("Version", version());
        pending(stream, "Status", "UPDATING", "ACTIVE");
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode updateDataRetention(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        checkVersion(stream, in, true);
        int change = in.path("DataRetentionChangeInHours").asInt();
        int current = stream.path("DataRetentionInHours").asInt();
        int next = "INCREASE_DATA_RETENTION".equals(text(in, "Operation")) ? current + change : current - change;
        if (next < 0) {
            throw invalid("The data retention can't be decreased below 0 hours.");
        }
        if (next > 87_600) {
            throw invalid("The data retention can't exceed 87600 hours.");
        }
        stream.put("DataRetentionInHours", next).put("Version", version());
        pending(stream, "Status", "UPDATING", "ACTIVE");
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode deleteStream(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        checkVersion(stream, in, false);
        if ("DELETING".equals(stream.path("Status").asText())) {
            throw KvsStore.inUse("The stream is being deleted.");
        }
        String name = stream.path("StreamName").asText();
        store.deleteFragments(account, region, name);
        pending(stream, "Status", "DELETING", "DELETED");
        store.put(account, region, STREAM, name, stream);
        return empty();
    }

    // ---------------------------------------------------------------- stream configurations

    public ObjectNode describeStreamStorageConfiguration(JsonNode in, String region) {
        ObjectNode stream = stream(store.account(), region, in);
        ObjectNode out = empty().put("StreamName", stream.path("StreamName").asText())
                .put("StreamARN", stream.path("StreamARN").asText());
        out.putObject("StreamStorageConfiguration").put("DefaultStorageTier", meta(stream).path("storageTier")
                .asText("HOT"));
        return out;
    }

    public ObjectNode updateStreamStorageConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        checkVersion(stream, in, true);
        meta(stream).put("storageTier", in.path("StreamStorageConfiguration").path("DefaultStorageTier")
                .asText("HOT"));
        stream.put("Version", version());
        pending(stream, "Status", "UPDATING", "ACTIVE");
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode describeNotificationConfiguration(JsonNode in, String region) {
        ObjectNode stream = stream(store.account(), region, in);
        ObjectNode out = empty();
        if (meta(stream).has("notification")) {
            out.set("NotificationConfiguration", meta(stream).get("notification"));
        }
        return out;
    }

    public ObjectNode updateNotificationConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        JsonNode config = in.get("NotificationConfiguration");
        if (config == null || config.isNull()) {
            meta(stream).remove("notification");
        } else {
            String uri = config.path("DestinationConfig").path("Uri").asText();
            if (!AwsArnUtils.isArnFor(uri, "sns")) {
                throw invalid("The notification destination must be an SNS topic ARN.");
            }
            meta(stream).set("notification", config.deepCopy());
        }
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode describeImageGenerationConfiguration(JsonNode in, String region) {
        ObjectNode stream = stream(store.account(), region, in);
        ObjectNode out = empty();
        if (meta(stream).has("images")) {
            out.set("ImageGenerationConfiguration", meta(stream).get("images"));
        }
        return out;
    }

    public ObjectNode updateImageGenerationConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        JsonNode config = in.get("ImageGenerationConfiguration");
        if (config == null || config.isNull()) {
            meta(stream).remove("images");
        } else {
            String uri = config.path("DestinationConfig").path("Uri").asText();
            if (!uri.startsWith("s3://")) {
                throw invalid("The image destination must be an S3 URI.");
            }
            int interval = config.path("SamplingInterval").asInt();
            if (interval < 200 || interval > 20_000) {
                throw invalid("SamplingInterval must be between 200 and 20000 milliseconds.");
            }
            meta(stream).set("images", config.deepCopy());
        }
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode startEdgeConfigurationUpdate(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        JsonNode config = in.get("EdgeConfig");
        if (!config.path("HubDeviceArn").asText().contains(":iot")) {
            throw invalid("The hub device must be an AWS IoT thing ARN.");
        }
        double now = System.currentTimeMillis() / 1000.0;
        ObjectNode edge = meta(stream).has("edge") ? (ObjectNode) meta(stream).get("edge") : empty()
                .put("CreationTime", now);
        edge.put("LastUpdatedTime", now).put("SyncStatus", "SYNCING");
        edge.set("EdgeConfig", config.deepCopy());
        meta(stream).set("edge", edge);
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return edgeView(stream, edge);
    }

    private ObjectNode edgeView(ObjectNode stream, ObjectNode edge) {
        ObjectNode out = empty().put("StreamName", stream.path("StreamName").asText())
                .put("StreamARN", stream.path("StreamARN").asText());
        out.setAll(edge.deepCopy());
        return out;
    }

    private ObjectNode edge(ObjectNode stream) {
        JsonNode edge = meta(stream).get("edge");
        if (edge == null) {
            throw new AwsException("StreamEdgeConfigurationNotFoundException", "The stream has no edge "
                    + "configuration.", 404);
        }
        ObjectNode e = (ObjectNode) edge;
        if ("DELETING".equals(e.path("SyncStatus").asText()) && e.path("deleteAt").asLong() <= System
                .currentTimeMillis()) {
            meta(stream).remove("edge");
            throw new AwsException("StreamEdgeConfigurationNotFoundException", "The stream has no edge "
                    + "configuration.", 404);
        }
        return e;
    }

    public ObjectNode describeEdgeConfiguration(JsonNode in, String region) {
        ObjectNode stream = stream(store.account(), region, in);
        ObjectNode edge = edge(stream);
        ObjectNode out = edgeView(stream, edge);
        out.remove("deleteAt");
        return out;
    }

    public ObjectNode deleteEdgeConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        ObjectNode edge = edge(stream);
        edge.put("SyncStatus", "DELETING").put("LastUpdatedTime", System.currentTimeMillis() / 1000.0)
                .put("deleteAt", System.currentTimeMillis() + store.config().transitionMillis());
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode listEdgeAgentConfigurations(JsonNode in, String region) {
        String account = store.account();
        String hub = text(in, "HubDeviceArn");
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode stream : store.list(account, region, STREAM)) {
            JsonNode edge = meta(stream).get("edge");
            if (edge != null && hub.equals(edge.path("EdgeConfig").path("HubDeviceArn").asText())) {
                ObjectNode item = edgeView(stream, (ObjectNode) edge);
                item.remove("deleteAt");
                items.add(item);
            }
        }
        ObjectNode out = page(items, in, "EdgeConfigs");
        return out;
    }

    public ObjectNode describeMappedResourceConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode channel : store.list(account, region, CHANNEL)) {
            JsonNode storage = meta(channel).path("storage");
            if (stream.path("StreamARN").asText().equals(storage.path("StreamARN").asText())
                    && "ENABLED".equals(storage.path("Status").asText())) {
                items.add(empty().put("Type", "SIGNALING_CHANNEL").put("ARN", channel.path("ChannelARN").asText()));
            }
        }
        return page(items, in, "MappedResourceConfigurationList");
    }

    // ---------------------------------------------------------------- endpoints

    /**
     * Floci's base URL, with a loopback host named {@code localhost.floci.io}: it resolves to the loopback address on
     * the host and to Floci inside its containers, so Lambda functions can reach the data endpoints too.
     */
    String endpoint() {
        String base = store.emulator().effectiveBaseUrl().replaceAll("/+$", "");
        return base.replaceFirst("(?i)^((?:https?)://)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?=[:/]|$)",
                "$1" + ContainerReachableUrls.FLOCI_DNS_HOST);
    }

    public ObjectNode getDataEndpoint(JsonNode in, String region) {
        activeStream(store.account(), region, in);
        return empty().put("DataEndpoint", endpoint());
    }

    public ObjectNode getSignalingChannelEndpoint(JsonNode in, String region) {
        String account = store.account();
        ObjectNode channel = activeChannel(account, region, in);
        JsonNode config = in.path("SingleMasterChannelEndpointConfiguration");
        List<String> protocols = new ArrayList<>();
        config.path("Protocols").forEach(p -> protocols.add(p.asText()));
        if (protocols.isEmpty()) {
            protocols.add("WSS");
            protocols.add("HTTPS");
        }
        String role = config.path("Role").asText("MASTER");
        boolean storage = "ENABLED".equals(meta(channel).path("storage").path("Status").asText());
        ObjectNode out = empty();
        ArrayNode list = out.putArray("ResourceEndpointList");
        String base = endpoint();
        for (String protocol : protocols) {
            String url = switch (protocol) {
                case "WSS" -> base.replaceFirst("^http", "ws") + KvsSignaling.PATH + region;
                case "HTTPS" -> base;
                case "WEBRTC" -> {
                    if (!storage) {
                        throw invalid("The WEBRTC protocol needs the channel's MediaStorageConfiguration "
                                + "enabled.");
                    }
                    yield base;
                }
                default -> throw invalid("Unknown protocol " + protocol + ".");
            };
            list.addObject().put("Protocol", protocol).put("ResourceEndpoint", url);
        }
        meta(channel).put("lastRole", role);
        return out;
    }

    // ---------------------------------------------------------------- signaling channels

    ObjectNode channel(String account, String region, JsonNode in) {
        String name = text(in, "ChannelName");
        String arn = text(in, "ChannelARN");
        if (name == null && arn == null) {
            throw invalid("Specify either ChannelName or ChannelARN.");
        }
        if (name == null) {
            name = nameOf(arn, CHANNEL);
        }
        ObjectNode channel = settle(account, region, CHANNEL, name, store.get(account, region, CHANNEL, name)
                .orElse(null));
        if (channel == null || arn != null && !arn.equals(channel.path("ChannelARN").asText())) {
            throw notFound("The requested channel is not found or not active.");
        }
        return channel;
    }

    ObjectNode activeChannel(String account, String region, JsonNode in) {
        ObjectNode channel = channel(account, region, in);
        if (!"ACTIVE".equals(channel.path("ChannelStatus").asText())) {
            throw notFound("The channel " + channel.path("ChannelName").asText() + " is not active.");
        }
        return channel;
    }

    /** A channel by ARN, or null. */
    ObjectNode channelByArn(String account, String region, String arn) {
        try {
            return channel(account, region, empty().put("ChannelARN", arn));
        } catch (AwsException e) {
            return null;
        }
    }

    public ObjectNode createSignalingChannel(JsonNode in, String region) {
        String account = store.account();
        String name = text(in, "ChannelName");
        if (!NAME.matcher(name).matches()) {
            throw invalid("The channel name " + name + " is not valid.");
        }
        if (store.get(account, region, CHANNEL, name).isPresent()) {
            throw KvsStore.inUse("The signaling channel " + name + " already exists.");
        }
        if (store.list(account, region, CHANNEL).size() >= store.config().maxChannels()) {
            throw new AwsException("AccountChannelLimitExceededException", "The account has reached its limit of "
                    + store.config().maxChannels() + " signaling channels.", 400);
        }
        long created = System.currentTimeMillis();
        String arn = store.arn(account, region, CHANNEL, name, created);
        ObjectNode channel = store.record();
        channel.put("ChannelName", name).put("ChannelARN", arn)
                .put("ChannelType", in.hasNonNull("ChannelType") ? text(in, "ChannelType") : "SINGLE_MASTER")
                .put("CreationTime", created / 1000.0);
        channel.putObject("SingleMasterConfiguration").put("MessageTtlSeconds",
                in.path("SingleMasterConfiguration").path("MessageTtlSeconds").asInt(60));
        channel.put("Version", version());
        ObjectNode tags = meta(channel).putObject("tags");
        in.path("Tags").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        checkTags(tags);
        pending(channel, "ChannelStatus", "CREATING", "ACTIVE");
        store.put(account, region, CHANNEL, name, channel);
        return empty().put("ChannelARN", arn);
    }

    public ObjectNode describeSignalingChannel(JsonNode in, String region) {
        ObjectNode out = empty();
        out.set("ChannelInfo", view(channel(store.account(), region, in)));
        return out;
    }

    public ObjectNode listSignalingChannels(JsonNode in, String region) {
        String account = store.account();
        String prefix = in.path("ChannelNameCondition").path("ComparisonValue").asText("");
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode channel : store.list(account, region, CHANNEL)) {
            ObjectNode settled = settle(account, region, CHANNEL, channel.path("ChannelName").asText(), channel);
            if (settled != null && settled.path("ChannelName").asText().startsWith(prefix)) {
                items.add(view(settled));
            }
        }
        return page(items, in, "ChannelInfoList");
    }

    public ObjectNode updateSignalingChannel(JsonNode in, String region) {
        String account = store.account();
        ObjectNode channel = activeChannel(account, region, in);
        checkVersion(channel, in, true);
        if (in.has("SingleMasterConfiguration")) {
            ((ObjectNode) channel.get("SingleMasterConfiguration")).put("MessageTtlSeconds",
                    in.path("SingleMasterConfiguration").path("MessageTtlSeconds").asInt(60));
        }
        channel.put("Version", version());
        pending(channel, "ChannelStatus", "UPDATING", "ACTIVE");
        store.put(account, region, CHANNEL, channel.path("ChannelName").asText(), channel);
        return empty();
    }

    public ObjectNode deleteSignalingChannel(JsonNode in, String region) {
        String account = store.account();
        ObjectNode channel = channel(account, region, in);
        checkVersion(channel, in, false);
        if ("DELETING".equals(channel.path("ChannelStatus").asText())) {
            throw KvsStore.inUse("The channel is being deleted.");
        }
        pending(channel, "ChannelStatus", "DELETING", "DELETED");
        store.put(account, region, CHANNEL, channel.path("ChannelName").asText(), channel);
        return empty();
    }

    public ObjectNode describeMediaStorageConfiguration(JsonNode in, String region) {
        ObjectNode channel = channel(store.account(), region, in);
        ObjectNode out = empty();
        JsonNode storage = meta(channel).get("storage");
        out.set("MediaStorageConfiguration", storage == null ? empty().put("Status", "DISABLED") : storage);
        return out;
    }

    public ObjectNode updateMediaStorageConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode channel = activeChannel(account, region, in);
        JsonNode config = in.get("MediaStorageConfiguration");
        if ("ENABLED".equals(text(config, "Status"))) {
            String streamArn = text(config, "StreamARN");
            if (streamArn == null) {
                throw invalid("An enabled MediaStorageConfiguration needs a StreamARN.");
            }
            ObjectNode stream = stream(account, region, empty().put("StreamARN", streamArn));
            if (stream.path("DataRetentionInHours").asInt() == 0) {
                throw invalid("The stream " + stream.path("StreamName").asText() + " needs data retention to store "
                        + "media.");
            }
            for (ObjectNode other : store.list(account, region, CHANNEL)) {
                if (!other.path("ChannelARN").asText().equals(channel.path("ChannelARN").asText())
                        && streamArn.equals(meta(other).path("storage").path("StreamARN").asText())
                        && "ENABLED".equals(meta(other).path("storage").path("Status").asText())) {
                    throw KvsStore.inUse("The stream already stores another channel's media.");
                }
            }
        }
        meta(channel).set("storage", config.deepCopy());
        store.put(account, region, CHANNEL, channel.path("ChannelName").asText(), channel);
        return empty();
    }

    // ---------------------------------------------------------------- tags

    private static void checkTags(ObjectNode tags) {
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TagsPerResourceExceededLimitException", "A resource can have at most "
                    + MAX_TAGS + " tags.", 400);
        }
    }

    private ObjectNode tagged(String account, String region, String arn) {
        String kind;
        try {
            kind = AwsArnUtils.parse(arn).resource().split("/")[0];
        } catch (IllegalArgumentException e) {
            throw invalid("The ARN " + arn + " is not valid.");
        }
        JsonNode in = empty().put(STREAM.equals(kind) ? "StreamARN" : "ChannelARN", arn);
        ObjectNode record = STREAM.equals(kind) ? stream(account, region, in) : channel(account, region, in);
        if (!"ACTIVE".equals(record.path(STREAM.equals(kind) ? "Status" : "ChannelStatus").asText())) {
            throw notFound("The resource " + arn + " is not active.");
        }
        meta(record).put("kind", kind);
        return record;
    }

    private void saveTagged(String account, String region, ObjectNode record) {
        String kind = meta(record).path("kind").asText();
        store.put(account, region, kind, record.path(STREAM.equals(kind) ? "StreamName" : "ChannelName").asText(),
                record);
    }

    private static ObjectNode tags(ObjectNode record) {
        return meta(record).has("tags") ? (ObjectNode) meta(record).get("tags") : meta(record).putObject("tags");
    }

    public ObjectNode tagStream(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        ObjectNode tags = tags(stream);
        in.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        checkTags(tags);
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode untagStream(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = activeStream(account, region, in);
        ObjectNode tags = tags(stream);
        in.path("TagKeyList").forEach(k -> tags.remove(k.asText()));
        store.put(account, region, STREAM, stream.path("StreamName").asText(), stream);
        return empty();
    }

    public ObjectNode listTagsForStream(JsonNode in, String region) {
        ObjectNode stream = stream(store.account(), region, in);
        ObjectNode out = empty();
        out.set("Tags", tags(stream).deepCopy());
        return out;
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = tagged(account, region, text(in, "ResourceARN"));
        ObjectNode tags = tags(record);
        in.path("Tags").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        checkTags(tags);
        saveTagged(account, region, record);
        return empty();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = tagged(account, region, text(in, "ResourceARN"));
        ObjectNode tags = tags(record);
        in.path("TagKeyList").forEach(k -> tags.remove(k.asText()));
        saveTagged(account, region, record);
        return empty();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        String account = store.account();
        String arn = text(in, "ResourceARN");
        String kind;
        try {
            kind = AwsArnUtils.parse(arn).resource().split("/")[0];
        } catch (IllegalArgumentException e) {
            throw invalid("The ARN " + arn + " is not valid.");
        }
        JsonNode req = empty().put(STREAM.equals(kind) ? "StreamARN" : "ChannelARN", arn);
        ObjectNode record = STREAM.equals(kind) ? stream(account, region, req) : channel(account, region, req);
        ObjectNode out = empty();
        out.set("Tags", tags(record).deepCopy());
        return out;
    }
}
