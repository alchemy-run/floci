package io.floci.aws.services.mediapackagev2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MediaPackage v2's control plane: channel groups (each with its egress domain), channels (each with two ingest
 * endpoints, which {@link MediaPackageOrigin} receives HLS on), origin endpoints (each manifest with its playback URL),
 * channel and origin endpoint policies, state resets and harvest jobs, which {@link MediaPackageOrigin} runs. Harvest
 * job state changes go to the default event bus as {@code MediaPackageV2 HarvestJob Notification} events.
 */
@ApplicationScoped
public class MediaPackageService {

    private static final Logger LOG = Logger.getLogger(MediaPackageService.class);
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,256}");

    private final MediaPackageStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaPackageOrigin> origin;
    private final Provider<EventBridgeService> events;
    private final Vertx vertx;

    @Inject
    public MediaPackageService(MediaPackageStore store, Provider<MediaPackageOrigin> origin,
                               Provider<EventBridgeService> events, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.origin = origin;
        this.events = events;
        this.vertx = vertx;
    }

    private static String name(String name, String member) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw MediaPackageStore.validation("MEMBER_DOES_NOT_MATCH_PATTERN", member + " must be 1-256 letters, "
                    + "digits, hyphens and underscores.");
        }
        return name;
    }

    private static String etag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private ObjectNode stamp(ObjectNode record) {
        double now = MediaPackageStore.epoch();
        if (!record.has("CreatedAt")) {
            record.put("CreatedAt", now);
        }
        record.put("ModifiedAt", now);
        record.put("ETag", etag());
        return record;
    }

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = MediaPackageStore.view(record);
        view.set("Tags", MediaPackageStore.tags(record).deepCopy());
        return view;
    }

    /** Checks an If-Match ETag, when the request gives one. */
    private static void match(JsonNode in, ObjectNode record) {
        String expected = in.path("ETag").asText(null);
        if (expected != null && !expected.equals(record.path("ETag").asText())) {
            throw MediaPackageStore.conflict("CONFLICTING_OPERATION", "The resource changed since its ETag "
                    + expected + ".");
        }
    }

    private ObjectNode repeated(String region, String operation, JsonNode in) {
        return store.repeated(region, operation, in);
    }

    // ================================================================ channel groups

    ObjectNode group(String region, String name) {
        return store.get(region, MediaPackageStore.GROUP, name).orElseThrow(() ->
                MediaPackageStore.notFound("CHANNEL_GROUP", "The channel group " + name + " doesn't exist."));
    }

    /** The host a channel group's egress domain has, under Floci's wildcard DNS. */
    String egressHost(String region, String groupId) {
        return groupId + ".egress.mediapackagev2." + region + "." + MediaPackageOrigin.DNS_SUFFIX;
    }

    public ObjectNode createChannelGroup(JsonNode in, String region) {
        ObjectNode earlier = repeated(region, "CreateChannelGroup", in);
        if (earlier != null) {
            return earlier;
        }
        String name = name(in.path("ChannelGroupName").asText(null), "ChannelGroupName");
        if (store.get(region, MediaPackageStore.GROUP, name).isPresent()) {
            throw MediaPackageStore.conflict("RESOURCE_ALREADY_EXISTS", "The channel group " + name
                    + " already exists.");
        }
        if (store.list(region, MediaPackageStore.GROUP).size() >= store.config().maxChannelGroups()) {
            throw MediaPackageStore.quota("The account has the maximum number of channel groups.");
        }
        String id = MediaPackageStore.random(6, "abcdefghijklmnopqrstuvwxyz0123456789");
        ObjectNode group = store.record();
        group.put("ChannelGroupName", name);
        group.put("Arn", store.arn(region, "channelGroup/" + name));
        group.put("EgressDomain", egressHost(region, id));
        MediaPackageStore.copy(in, group, "Description");
        MediaPackageStore.meta(group).put("domainId", id);
        MediaPackageStore.addTags(group, in.path("Tags"));
        stamp(group);
        store.put(region, MediaPackageStore.GROUP, name, group);
        ObjectNode out = view(group);
        store.remember(region, "CreateChannelGroup", in, out);
        return out;
    }

    public ObjectNode getChannelGroup(JsonNode in, String region) {
        return view(group(region, in.path("ChannelGroupName").asText()));
    }

    public ObjectNode updateChannelGroup(JsonNode in, String region) {
        ObjectNode group = group(region, in.path("ChannelGroupName").asText());
        match(in, group);
        MediaPackageStore.copy(in, group, "Description");
        stamp(group);
        store.put(region, MediaPackageStore.GROUP, group.path("ChannelGroupName").asText(), group);
        return view(group);
    }

    public ObjectNode deleteChannelGroup(JsonNode in, String region) {
        String name = in.path("ChannelGroupName").asText();
        if (store.get(region, MediaPackageStore.GROUP, name).isEmpty()) {
            return mapper.createObjectNode();
        }
        for (ObjectNode channel : store.list(region, MediaPackageStore.CHANNEL)) {
            if (name.equals(channel.path("ChannelGroupName").asText())) {
                throw MediaPackageStore.conflict("RESOURCE_IN_USE", "The channel group has channels; delete them "
                        + "first.");
            }
        }
        store.delete(region, MediaPackageStore.GROUP, name);
        return mapper.createObjectNode();
    }

    public ObjectNode listChannelGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(region, MediaPackageStore.GROUP)) {
            ObjectNode item = mapper.createObjectNode();
            MediaPackageStore.copy(group, item, "ChannelGroupName", "Arn", "CreatedAt", "ModifiedAt", "Description");
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.page(items, in, out, "Items", 50);
        return out;
    }

    // ================================================================ channels

    static String channelKey(String group, String channel) {
        return group + "/" + channel;
    }

    ObjectNode channel(String region, String group, String name) {
        group(region, group);
        return store.get(region, MediaPackageStore.CHANNEL, channelKey(group, name)).orElseThrow(() ->
                MediaPackageStore.notFound("CHANNEL", "The channel " + name + " doesn't exist in the channel group "
                        + group + "."));
    }

    private ObjectNode channelView(String region, ObjectNode channel) {
        ObjectNode view = view(channel);
        ArrayNode endpoints = view.putArray("IngestEndpoints");
        if (!"MULTIVIEW".equals(channel.path("InputType").asText())) {
            for (int index = 1; index <= 2; index++) {
                endpoints.addObject().put("Id", String.valueOf(index)).put("Url", origin.get().ingestUrl(region,
                        channel, index));
            }
        }
        return view;
    }

    public ObjectNode createChannel(JsonNode in, String region) {
        ObjectNode earlier = repeated(region, "CreateChannel", in);
        if (earlier != null) {
            return earlier;
        }
        String groupName = in.path("ChannelGroupName").asText();
        ObjectNode group = group(region, groupName);
        String name = name(in.path("ChannelName").asText(null), "ChannelName");
        if (store.get(region, MediaPackageStore.CHANNEL, channelKey(groupName, name)).isPresent()) {
            throw MediaPackageStore.conflict("RESOURCE_ALREADY_EXISTS", "The channel " + name + " already exists.");
        }
        ObjectNode channel = store.record();
        channel.put("ChannelName", name);
        channel.put("ChannelGroupName", groupName);
        channel.put("Arn", group.path("Arn").asText() + "/channel/" + name);
        channel.put("InputType", in.path("InputType").asText("HLS"));
        channel.put("OutputLockingMode", in.path("OutputLockingMode").asText("EPOCH_LOCKED"));
        MediaPackageStore.copy(in, channel, "Description", "InputSwitchConfiguration", "OutputHeaderConfiguration",
                "MultiviewConfiguration");
        if ("CMAF".equals(channel.path("InputType").asText()) && !channel.has("InputSwitchConfiguration")) {
            channel.putObject("InputSwitchConfiguration").put("MQCSInputSwitching", false);
        }
        MediaPackageStore.meta(channel).put("ingestId", MediaPackageStore.random(8,
                "abcdefghijklmnopqrstuvwxyz0123456789"));
        MediaPackageStore.addTags(channel, in.path("Tags"));
        stamp(channel);
        store.put(region, MediaPackageStore.CHANNEL, channelKey(groupName, name), channel);
        ObjectNode out = channelView(region, channel);
        store.remember(region, "CreateChannel", in, out);
        return out;
    }

    public ObjectNode getChannel(JsonNode in, String region) {
        return channelView(region, channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName")
                .asText()));
    }

    public ObjectNode updateChannel(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText());
        match(in, channel);
        MediaPackageStore.copy(in, channel, "Description", "InputSwitchConfiguration", "OutputHeaderConfiguration",
                "MultiviewConfiguration");
        stamp(channel);
        store.put(region, MediaPackageStore.CHANNEL, channelKey(channel.path("ChannelGroupName").asText(),
                channel.path("ChannelName").asText()), channel);
        return channelView(region, channel);
    }

    public ObjectNode deleteChannel(JsonNode in, String region) {
        String group = in.path("ChannelGroupName").asText();
        String name = in.path("ChannelName").asText();
        if (store.get(region, MediaPackageStore.CHANNEL, channelKey(group, name)).isEmpty()) {
            return mapper.createObjectNode();
        }
        for (ObjectNode endpoint : store.list(region, MediaPackageStore.ENDPOINT)) {
            if (group.equals(endpoint.path("ChannelGroupName").asText()) && name.equals(endpoint.path("ChannelName")
                    .asText())) {
                throw MediaPackageStore.conflict("RESOURCE_IN_USE", "The channel has origin endpoints; delete them "
                        + "first.");
            }
        }
        store.delete(region, MediaPackageStore.CHANNEL, channelKey(group, name));
        origin.get().forget(store.account(), region, group, name);
        return mapper.createObjectNode();
    }

    public ObjectNode listChannels(JsonNode in, String region) {
        String group = group(region, in.path("ChannelGroupName").asText()).path("ChannelGroupName").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode channel : store.list(region, MediaPackageStore.CHANNEL)) {
            if (group.equals(channel.path("ChannelGroupName").asText())) {
                ObjectNode item = mapper.createObjectNode();
                MediaPackageStore.copy(channel, item, "Arn", "ChannelName", "ChannelGroupName", "CreatedAt",
                        "ModifiedAt", "Description", "InputType", "OutputLockingMode", "MultiviewConfiguration");
                items.add(item);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.page(items, in, out, "Items", 50);
        return out;
    }

    public ObjectNode resetChannelState(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText());
        double last = channel.path("ResetAt").asDouble(0);
        if (MediaPackageStore.epoch() - last < 1) {
            throw MediaPackageStore.conflict("CONFLICTING_OPERATION", "The channel was just reset.");
        }
        channel.put("ResetAt", MediaPackageStore.epoch());
        store.put(region, MediaPackageStore.CHANNEL, channelKey(channel.path("ChannelGroupName").asText(),
                channel.path("ChannelName").asText()), channel);
        origin.get().forget(store.account(), region, channel.path("ChannelGroupName").asText(),
                channel.path("ChannelName").asText());
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.copy(channel, out, "ChannelGroupName", "ChannelName", "Arn", "ResetAt");
        return out;
    }

    public ObjectNode putChannelPolicy(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText());
        MediaPackageStore.meta(channel).put("policy", policy(in.path("Policy").asText("")));
        store.put(region, MediaPackageStore.CHANNEL, channelKey(channel.path("ChannelGroupName").asText(),
                channel.path("ChannelName").asText()), channel);
        return mapper.createObjectNode();
    }

    public ObjectNode getChannelPolicy(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText());
        String policy = MediaPackageStore.meta(channel).path("policy").asText(null);
        if (policy == null) {
            throw MediaPackageStore.notFound("CHANNEL", "The channel has no policy.");
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.copy(channel, out, "ChannelGroupName", "ChannelName");
        out.put("Policy", policy);
        return out;
    }

    public ObjectNode deleteChannelPolicy(JsonNode in, String region) {
        ObjectNode channel = channel(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText());
        MediaPackageStore.meta(channel).remove("policy");
        store.put(region, MediaPackageStore.CHANNEL, channelKey(channel.path("ChannelGroupName").asText(),
                channel.path("ChannelName").asText()), channel);
        return mapper.createObjectNode();
    }

    /** A policy document, checked to be JSON with statements. */
    private String policy(String document) {
        try {
            JsonNode parsed = mapper.readTree(document);
            if (parsed == null || !parsed.has("Statement")) {
                throw MediaPackageStore.validation("INVALID_POLICY", "The policy has no Statement.");
            }
        } catch (JsonProcessingException e) {
            throw MediaPackageStore.validation("INVALID_POLICY", "The policy isn't JSON: " + e.getOriginalMessage());
        }
        return document;
    }

    // ================================================================ origin endpoints

    static String endpointKey(String group, String channel, String endpoint) {
        return group + "/" + channel + "/" + endpoint;
    }

    ObjectNode endpoint(String region, String group, String channel, String name) {
        channel(region, group, channel);
        return store.get(region, MediaPackageStore.ENDPOINT, endpointKey(group, channel, name)).orElseThrow(() ->
                MediaPackageStore.notFound("ORIGIN_ENDPOINT", "The origin endpoint " + name + " doesn't exist."));
    }

    private ObjectNode endpointView(String region, ObjectNode endpoint) {
        ObjectNode view = view(endpoint);
        String base = origin.get().egressBase(region, endpoint);
        for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests")) {
            for (JsonNode manifest : view.path(kind)) {
                ((ObjectNode) manifest).put("Url", base + manifest.path("ManifestName").asText() + ".m3u8");
            }
        }
        for (JsonNode manifest : view.path("DashManifests")) {
            ((ObjectNode) manifest).put("Url", base + manifest.path("ManifestName").asText() + ".mpd");
        }
        for (JsonNode manifest : view.path("MssManifests")) {
            ((ObjectNode) manifest).put("Url", base + manifest.path("ManifestName").asText() + ".ism/Manifest");
        }
        return view;
    }

    private void applyEndpoint(ObjectNode endpoint, JsonNode in, boolean create) {
        String container = endpoint.path("ContainerType").asText();
        if (!create && in.hasNonNull("ContainerType") && !container.equals(in.get("ContainerType").asText())) {
            throw MediaPackageStore.validation("CONTAINER_TYPE_IMMUTABLE", "An origin endpoint's container type "
                    + "can't change.");
        }
        MediaPackageStore.copy(in, endpoint, "Description", "StartoverWindowSeconds",
                "ForceEndpointErrorConfiguration", "UriSeparator", "StreamNameOutputMode");
        if (in.has("StartoverWindowSeconds") && in.get("StartoverWindowSeconds").isNull()) {
            endpoint.remove("StartoverWindowSeconds");
        }
        if (in.has("Segment") || create) {
            ObjectNode segment = in.path("Segment").isObject() ? in.get("Segment").deepCopy()
                    : mapper.createObjectNode();
            if (!segment.has("SegmentDurationSeconds")) {
                segment.put("SegmentDurationSeconds", 6);
            }
            if (!segment.has("SegmentName")) {
                segment.put("SegmentName", "segment");
            }
            if ("TS".equals(container)) {
                if (!segment.has("TsUseAudioRenditionGroup")) {
                    segment.put("TsUseAudioRenditionGroup", false);
                }
            }
            if (!segment.has("IncludeIframeOnlyStreams")) {
                segment.put("IncludeIframeOnlyStreams", false);
            }
            JsonNode method = segment.path("Encryption").path("EncryptionMethod");
            if (method.isObject() && ("TS".equals(container) ? !method.has("TsEncryptionMethod")
                    : "CMAF".equals(container) ? !method.has("CmafEncryptionMethod") : !method.has(
                    "IsmEncryptionMethod"))) {
                throw MediaPackageStore.validation("ENCRYPTION_METHOD_CONTAINER_TYPE_MISMATCH", "The encryption "
                        + "method doesn't suit the " + container + " container.");
            }
            endpoint.set("Segment", segment);
        }
        Set<String> names = new HashSet<>();
        for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests", "DashManifests", "MssManifests")) {
            if (!in.has(kind) && !create) {
                endpoint.path(kind).forEach(manifest -> names.add(manifest.path("ManifestName").asText()));
                continue;
            }
            ArrayNode manifests = mapper.createArrayNode();
            for (JsonNode manifest : in.path(kind)) {
                String name = name(manifest.path("ManifestName").asText(null), "ManifestName");
                if (!names.add(name)) {
                    throw MediaPackageStore.validation("MANIFEST_NAME_COLLISION", "Manifest names are unique within "
                            + "an origin endpoint: " + name + ".");
                }
                ObjectNode copy = manifest.deepCopy();
                if (kind.contains("Hls") && !copy.has("ChildManifestName")) {
                    copy.put("ChildManifestName", name);
                }
                if (!copy.has("ManifestWindowSeconds")) {
                    copy.put("ManifestWindowSeconds", 60);
                }
                if (kind.contains("Hls") && !copy.has("ProgramDateTimeIntervalSeconds")) {
                    copy.put("ProgramDateTimeIntervalSeconds", 0);
                }
                manifests.add(copy);
            }
            if (!manifests.isEmpty() && "DashManifests".equals(kind) && !"CMAF".equals(container)) {
                throw MediaPackageStore.validation("MEMBER_INVALID", "DASH manifests need a CMAF origin endpoint.");
            }
            if (!manifests.isEmpty() && "MssManifests".equals(kind) && !"ISM".equals(container)) {
                throw MediaPackageStore.validation("MEMBER_INVALID", "MSS manifests need an ISM origin endpoint.");
            }
            if (!manifests.isEmpty() && kind.contains("Hls") && "ISM".equals(container)) {
                throw MediaPackageStore.validation("MEMBER_INVALID", "HLS manifests need a TS or CMAF origin "
                        + "endpoint.");
            }
            endpoint.set(kind, manifests);
        }
    }

    public ObjectNode createOriginEndpoint(JsonNode in, String region) {
        ObjectNode earlier = repeated(region, "CreateOriginEndpoint", in);
        if (earlier != null) {
            return earlier;
        }
        String groupName = in.path("ChannelGroupName").asText();
        String channelName = in.path("ChannelName").asText();
        ObjectNode channel = channel(region, groupName, channelName);
        String name = name(in.path("OriginEndpointName").asText(null), "OriginEndpointName");
        if (store.get(region, MediaPackageStore.ENDPOINT, endpointKey(groupName, channelName, name)).isPresent()) {
            throw MediaPackageStore.conflict("RESOURCE_ALREADY_EXISTS", "The origin endpoint " + name
                    + " already exists.");
        }
        ObjectNode endpoint = store.record();
        endpoint.put("OriginEndpointName", name);
        endpoint.put("ChannelGroupName", groupName);
        endpoint.put("ChannelName", channelName);
        endpoint.put("Arn", channel.path("Arn").asText() + "/originEndpoint/" + name);
        endpoint.put("ContainerType", in.path("ContainerType").asText());
        applyEndpoint(endpoint, in, true);
        MediaPackageStore.addTags(endpoint, in.path("Tags"));
        stamp(endpoint);
        store.put(region, MediaPackageStore.ENDPOINT, endpointKey(groupName, channelName, name), endpoint);
        ObjectNode out = endpointView(region, endpoint);
        store.remember(region, "CreateOriginEndpoint", in, out);
        return out;
    }

    public ObjectNode getOriginEndpoint(JsonNode in, String region) {
        return endpointView(region, endpoint(region, in.path("ChannelGroupName").asText(),
                in.path("ChannelName").asText(), in.path("OriginEndpointName").asText()));
    }

    public ObjectNode updateOriginEndpoint(JsonNode in, String region) {
        ObjectNode endpoint = endpoint(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        match(in, endpoint);
        applyEndpoint(endpoint, in, false);
        stamp(endpoint);
        store.put(region, MediaPackageStore.ENDPOINT, endpointKey(endpoint.path("ChannelGroupName").asText(),
                endpoint.path("ChannelName").asText(), endpoint.path("OriginEndpointName").asText()), endpoint);
        return endpointView(region, endpoint);
    }

    public ObjectNode deleteOriginEndpoint(JsonNode in, String region) {
        String key = endpointKey(in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        store.delete(region, MediaPackageStore.ENDPOINT, key);
        return mapper.createObjectNode();
    }

    public ObjectNode listOriginEndpoints(JsonNode in, String region) {
        String group = in.path("ChannelGroupName").asText();
        String channel = channel(region, group, in.path("ChannelName").asText()).path("ChannelName").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode endpoint : store.list(region, MediaPackageStore.ENDPOINT)) {
            if (group.equals(endpoint.path("ChannelGroupName").asText()) && channel.equals(endpoint.path(
                    "ChannelName").asText())) {
                ObjectNode view = endpointView(region, endpoint);
                ObjectNode item = mapper.createObjectNode();
                MediaPackageStore.copy(view, item, "Arn", "ChannelGroupName", "ChannelName", "OriginEndpointName",
                        "ContainerType", "Description", "CreatedAt", "ModifiedAt", "ForceEndpointErrorConfiguration",
                        "UriSeparator", "StreamNameOutputMode");
                for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests", "DashManifests",
                        "MssManifests")) {
                    ArrayNode summaries = item.putArray(kind);
                    for (JsonNode manifest : view.path(kind)) {
                        ObjectNode summary = summaries.addObject();
                        MediaPackageStore.copy(manifest, summary, "ManifestName", "ChildManifestName", "Url");
                    }
                }
                items.add(item);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.page(items, in, out, "Items", 50);
        return out;
    }

    public ObjectNode resetOriginEndpointState(JsonNode in, String region) {
        ObjectNode endpoint = endpoint(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        if (MediaPackageStore.epoch() - endpoint.path("ResetAt").asDouble(0) < 1) {
            throw MediaPackageStore.conflict("CONFLICTING_OPERATION", "The origin endpoint was just reset.");
        }
        endpoint.put("ResetAt", MediaPackageStore.epoch());
        store.put(region, MediaPackageStore.ENDPOINT, endpointKey(endpoint.path("ChannelGroupName").asText(),
                endpoint.path("ChannelName").asText(), endpoint.path("OriginEndpointName").asText()), endpoint);
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.copy(endpoint, out, "ChannelGroupName", "ChannelName", "OriginEndpointName", "Arn",
                "ResetAt");
        return out;
    }

    public ObjectNode putOriginEndpointPolicy(JsonNode in, String region) {
        ObjectNode endpoint = endpoint(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        MediaPackageStore.meta(endpoint).put("policy", policy(in.path("Policy").asText("")));
        if (in.has("CdnAuthConfiguration")) {
            MediaPackageStore.meta(endpoint).set("cdnAuth", in.get("CdnAuthConfiguration").deepCopy());
        }
        store.put(region, MediaPackageStore.ENDPOINT, endpointKey(endpoint.path("ChannelGroupName").asText(),
                endpoint.path("ChannelName").asText(), endpoint.path("OriginEndpointName").asText()), endpoint);
        return mapper.createObjectNode();
    }

    public ObjectNode getOriginEndpointPolicy(JsonNode in, String region) {
        ObjectNode endpoint = endpoint(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        String policy = MediaPackageStore.meta(endpoint).path("policy").asText(null);
        if (policy == null) {
            throw MediaPackageStore.notFound("ORIGIN_ENDPOINT", "The origin endpoint has no policy.");
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.copy(endpoint, out, "ChannelGroupName", "ChannelName", "OriginEndpointName");
        out.put("Policy", policy);
        if (MediaPackageStore.meta(endpoint).has("cdnAuth")) {
            out.set("CdnAuthConfiguration", MediaPackageStore.meta(endpoint).get("cdnAuth").deepCopy());
        }
        return out;
    }

    public ObjectNode deleteOriginEndpointPolicy(JsonNode in, String region) {
        ObjectNode endpoint = endpoint(region, in.path("ChannelGroupName").asText(), in.path("ChannelName").asText(),
                in.path("OriginEndpointName").asText());
        MediaPackageStore.meta(endpoint).remove("policy");
        MediaPackageStore.meta(endpoint).remove("cdnAuth");
        store.put(region, MediaPackageStore.ENDPOINT, endpointKey(endpoint.path("ChannelGroupName").asText(),
                endpoint.path("ChannelName").asText(), endpoint.path("OriginEndpointName").asText()), endpoint);
        return mapper.createObjectNode();
    }

    // ================================================================ harvest jobs

    static String harvestKey(String group, String name) {
        return group + "/" + name;
    }

    private ObjectNode harvestJob(String region, String group, String name) {
        group(region, group);
        return store.get(region, MediaPackageStore.HARVEST, harvestKey(group, name)).orElseThrow(() ->
                MediaPackageStore.notFound("HARVEST_JOB", "The harvest job " + name + " doesn't exist."));
    }

    public ObjectNode createHarvestJob(JsonNode in, String region) {
        ObjectNode earlier = repeated(region, "CreateHarvestJob", in);
        if (earlier != null) {
            return earlier;
        }
        String groupName = in.path("ChannelGroupName").asText();
        String channelName = in.path("ChannelName").asText();
        String endpointName = in.path("OriginEndpointName").asText();
        ObjectNode endpoint = endpoint(region, groupName, channelName, endpointName);
        Set<String> available = new HashSet<>();
        for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests", "DashManifests")) {
            endpoint.path(kind).forEach(manifest -> available.add(kind + "/" + manifest.path("ManifestName")
                    .asText()));
        }
        JsonNode harvested = in.path("HarvestedManifests");
        int count = 0;
        for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests", "DashManifests")) {
            for (JsonNode manifest : harvested.path(kind)) {
                count++;
                if (!available.contains(kind + "/" + manifest.path("ManifestName").asText())) {
                    throw MediaPackageStore.validation("HARVESTED_MANIFEST_NOT_FOUND_ON_ENDPOINT", "The origin "
                            + "endpoint has no " + kind + " manifest named " + manifest.path("ManifestName").asText()
                            + ".");
                }
            }
        }
        if (count == 0) {
            throw MediaPackageStore.validation("NUM_MANIFESTS_LOW", "A harvest job harvests at least one manifest.");
        }
        double start = in.path("ScheduleConfiguration").path("StartTime").asDouble();
        double end = in.path("ScheduleConfiguration").path("EndTime").asDouble();
        if (end <= start) {
            throw MediaPackageStore.validation("END_TIME_EARLIER_THAN_START_TIME", "EndTime must be after StartTime.");
        }
        if (end - start > 86400) {
            throw MediaPackageStore.validation("INVALID_HARVEST_JOB_DURATION", "A harvest job covers at most 24 "
                    + "hours.");
        }
        double now = MediaPackageStore.epoch();
        int startover = endpoint.path("StartoverWindowSeconds").asInt(0);
        if (start < now - startover) {
            throw MediaPackageStore.validation("INVALID_HARVEST_JOB_DURATION", "The harvest window must be within "
                    + "the origin endpoint's startover window of " + startover + " seconds.");
        }
        JsonNode s3 = in.path("Destination").path("S3Destination");
        if (!s3.hasNonNull("BucketName") || !s3.hasNonNull("DestinationPath")) {
            throw MediaPackageStore.validation("HARVEST_JOB_S3_DESTINATION_MISSING_OR_INCOMPLETE", "The S3 "
                    + "destination needs a BucketName and DestinationPath.");
        }
        int running = 0;
        for (ObjectNode job : store.list(region, MediaPackageStore.HARVEST)) {
            String status = job.path("Status").asText();
            running += "QUEUED".equals(status) || "IN_PROGRESS".equals(status) ? 1 : 0;
        }
        if (running >= 20) {
            throw MediaPackageStore.validation("TOO_MANY_IN_PROGRESS_HARVEST_JOBS", "The account has the maximum "
                    + "number of harvest jobs in progress.");
        }
        String name = in.hasNonNull("HarvestJobName") ? name(in.get("HarvestJobName").asText(), "HarvestJobName")
                : "HarvestJob-" + MediaPackageStore.random(10, "abcdefghijklmnopqrstuvwxyz0123456789");
        if (store.get(region, MediaPackageStore.HARVEST, harvestKey(groupName, name)).isPresent()) {
            throw MediaPackageStore.conflict("RESOURCE_ALREADY_EXISTS", "The harvest job " + name
                    + " already exists.");
        }
        ObjectNode job = store.record();
        job.put("HarvestJobName", name);
        job.put("ChannelGroupName", groupName);
        job.put("ChannelName", channelName);
        job.put("OriginEndpointName", endpointName);
        job.put("Arn", endpoint.path("Arn").asText() + "/harvestJob/" + name);
        job.set("Destination", in.path("Destination").deepCopy());
        job.set("HarvestedManifests", harvested.deepCopy());
        job.set("ScheduleConfiguration", in.path("ScheduleConfiguration").deepCopy());
        MediaPackageStore.copy(in, job, "Description");
        job.put("Status", "QUEUED");
        MediaPackageStore.addTags(job, in.path("Tags"));
        stamp(job);
        store.put(region, MediaPackageStore.HARVEST, harvestKey(groupName, name), job);
        notify(store.account(), region, job);
        String account = store.account();
        long delay = Math.max(1, (long) ((end - now) * 1000)) + Math.max(1, store.config().transitionMillis());
        vertx.setTimer(delay, timer -> vertx.executeBlocking(() -> {
            RequestScopes.runAs(account, region, () -> runHarvest(account, region, groupName, name));
            return null;
        }, false));
        ObjectNode out = view(job);
        store.remember(region, "CreateHarvestJob", in, out);
        return out;
    }

    private void runHarvest(String account, String region, String group, String name) {
        ObjectNode job = store.getForAccount(account, region, MediaPackageStore.HARVEST, harvestKey(group, name))
                .orElse(null);
        if (job == null || !"QUEUED".equals(job.path("Status").asText())) {
            return;
        }
        job.put("Status", "IN_PROGRESS");
        stamp(job);
        store.putForAccount(account, region, MediaPackageStore.HARVEST, harvestKey(group, name), job);
        notify(account, region, job);
        try {
            ObjectNode endpoint = store.getForAccount(account, region, MediaPackageStore.ENDPOINT, endpointKey(group,
                    job.path("ChannelName").asText(), job.path("OriginEndpointName").asText())).orElseThrow(() ->
                    new IllegalStateException("The origin endpoint was deleted."));
            origin.get().harvest(account, region, endpoint, job);
            job.put("Status", "COMPLETED");
        } catch (RuntimeException e) {
            job.put("Status", "FAILED");
            job.put("ErrorMessage", e.getMessage());
        }
        ObjectNode current = store.getForAccount(account, region, MediaPackageStore.HARVEST, harvestKey(group, name))
                .orElse(null);
        if (current == null || "CANCELLED".equals(current.path("Status").asText())) {
            return;
        }
        stamp(job);
        store.putForAccount(account, region, MediaPackageStore.HARVEST, harvestKey(group, name), job);
        notify(account, region, job);
    }

    private void notify(String account, String region, ObjectNode job) {
        ObjectNode detail = mapper.createObjectNode();
        ObjectNode harvest = detail.putObject("harvestJob");
        harvest.put("channelGroupName", job.path("ChannelGroupName").asText());
        harvest.put("channelName", job.path("ChannelName").asText());
        harvest.put("originEndpointName", job.path("OriginEndpointName").asText());
        harvest.put("harvestJobName", job.path("HarvestJobName").asText());
        harvest.put("status", job.path("Status").asText());
        harvest.put("arn", job.path("Arn").asText());
        ObjectNode destination = harvest.putObject("destination").putObject("s3Destination");
        destination.put("bucketName", job.path("Destination").path("S3Destination").path("BucketName").asText());
        destination.put("destinationPath", job.path("Destination").path("S3Destination").path("DestinationPath")
                .asText());
        if (job.hasNonNull("ErrorMessage")) {
            harvest.put("message", job.get("ErrorMessage").asText());
        }
        try {
            RequestScopes.runAs(account, region, () -> events.get().publishServiceEvent("aws.mediapackagev2",
                    "MediaPackageV2 HarvestJob Notification", detail.toString(), job.path("Arn").asText(), region));
        } catch (RuntimeException e) {
            LOG.debugv("MediaPackage v2 could not send the event of harvest job {0}: {1}", job.path("Arn").asText(),
                    e.getMessage());
        }
    }

    public ObjectNode getHarvestJob(JsonNode in, String region) {
        return view(harvestJob(region, in.path("ChannelGroupName").asText(), in.path("HarvestJobName").asText()));
    }

    public ObjectNode cancelHarvestJob(JsonNode in, String region) {
        ObjectNode job = harvestJob(region, in.path("ChannelGroupName").asText(), in.path("HarvestJobName").asText());
        match(in, job);
        String status = job.path("Status").asText();
        if (!"QUEUED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw MediaPackageStore.conflict("CONFLICTING_OPERATION", "The harvest job is " + status
                    + " and can't be cancelled.");
        }
        job.put("Status", "CANCELLED");
        stamp(job);
        store.put(region, MediaPackageStore.HARVEST, harvestKey(job.path("ChannelGroupName").asText(),
                job.path("HarvestJobName").asText()), job);
        notify(store.account(), region, job);
        return mapper.createObjectNode();
    }

    public ObjectNode listHarvestJobs(JsonNode in, String region) {
        String group = group(region, in.path("ChannelGroupName").asText()).path("ChannelGroupName").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.list(region, MediaPackageStore.HARVEST)) {
            if (group.equals(job.path("ChannelGroupName").asText())
                    && (!in.hasNonNull("ChannelName") || in.get("ChannelName").asText().equals(job.path(
                    "ChannelName").asText()))
                    && (!in.hasNonNull("OriginEndpointName") || in.get("OriginEndpointName").asText().equals(
                    job.path("OriginEndpointName").asText()))
                    && (!in.hasNonNull("Status") || in.get("Status").asText().equals(job.path("Status").asText()))) {
                ObjectNode item = view(job);
                item.remove("Tags");
                items.add(item);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaPackageStore.page(items, in, out, "Items", 50);
        return out;
    }

    // ================================================================ tags

    private Object[] tagged(String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, "mediapackagev2")) {
            throw MediaPackageStore.validation("MEMBER_INVALID", "The resource ARN " + arn + " is not valid.");
        }
        for (String kind : List.of(MediaPackageStore.GROUP, MediaPackageStore.CHANNEL, MediaPackageStore.ENDPOINT,
                MediaPackageStore.HARVEST)) {
            for (ObjectNode record : store.list(region, kind)) {
                if (arn.equals(record.path("Arn").asText())) {
                    return new Object[] {kind, record};
                }
            }
        }
        throw MediaPackageStore.notFound("CHANNEL_GROUP", "The resource " + arn + " doesn't exist.");
    }

    private void saveTagged(String region, Object[] tagged) {
        ObjectNode record = (ObjectNode) tagged[1];
        store.put(region, (String) tagged[0], MediaPackageStore.meta(record).path("id").asText(), record);
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, in.path("ResourceArn").asText());
        MediaPackageStore.addTags((ObjectNode) tagged[1], in.path("Tags"));
        saveTagged(region, tagged);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, in.path("ResourceArn").asText());
        in.path("TagKeys").forEach(key -> MediaPackageStore.tags((ObjectNode) tagged[1]).remove(key.asText()));
        saveTagged(region, tagged);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", MediaPackageStore.tags((ObjectNode) tagged(region, in.path("ResourceArn").asText())[1])
                .deepCopy());
        return out;
    }

}
