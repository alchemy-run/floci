package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.ivs.IvsStore.conflict;
import static io.floci.aws.services.ivs.IvsStore.key;
import static io.floci.aws.services.ivs.IvsStore.meta;
import static io.floci.aws.services.ivs.IvsStore.notBroadcasting;
import static io.floci.aws.services.ivs.IvsStore.notFound;
import static io.floci.aws.services.ivs.IvsStore.quota;
import static io.floci.aws.services.ivs.IvsStore.validation;

/**
 * Amazon IVS low-latency streaming: channels with their stream keys, playback key pairs, playback restriction
 * policies, recording and ad configurations, live streams and their sessions, timed metadata, ad breaks, viewer
 * session revocations, and tags. Channels stream for real through floci's media server (see {@link IvsMediaServer})
 * when Docker is reachable: a broadcaster publishes with the channel's stream key, viewers play its HLS at the
 * playback URL, and a recording configuration records it to S3. With {@code mock: true}, or without Docker,
 * channels are never live.
 */
@ApplicationScoped
public class IvsService {

    private static final Logger LOG = Logger.getLogger(IvsService.class);
    private static final Set<String> TYPES = Set.of("BASIC", "STANDARD", "ADVANCED_SD", "ADVANCED_HD");
    private static final int MAX_CHANNELS = 5000;
    private static final int MAX_KEY_PAIRS = 3;
    private static final int MAX_METADATA_BYTES = 1024;
    private static final int METADATA_PER_SECOND = 5;

    private final IvsStore store;
    private final IvsLive live;
    private final IvsMediaServer media;
    private final Instance<S3Service> s3;
    private final Map<String, long[]> metadataRate = new ConcurrentHashMap<>();
    private final ExecutorService starter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivs-media-start");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public IvsService(IvsStore store, IvsLive live, IvsMediaServer media, Instance<S3Service> s3) {
        this.store = store;
        this.live = live;
        this.media = media;
        this.s3 = s3;
    }

    private EmulatorConfig.IvsServiceConfig config() {
        return store.config().services().ivs();
    }

    // ---------------------------------------------------------------- the media server

    /** Starts the media server in the background when a channel first needs it, and configures every key. */
    private void ensureMedia() {
        if (config().mock() || media.endpoint() != null) {
            return;
        }
        starter.execute(() -> {
            try {
                if (!media.dockerReachable()) {
                    LOG.warnv("No Docker daemon is reachable; IVS channels cannot be broadcast to");
                    return;
                }
                media.ensureStarted();
                live.startPolling();
                configureAllPaths();
            } catch (RuntimeException e) {
                LOG.warnv("The IVS media server could not be started: {0}", e.getMessage());
            }
        });
    }

    private synchronized void configureAllPaths() {
        store.everywhere(key -> key.contains("/stream-key/")).forEach(entry -> {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            ObjectNode channel = store.get(entry.accountId(), key(region, "channel",
                    meta(entry.value()).path("channelId").asText())).orElse(null);
            if (channel != null) {
                configurePath(entry.value(), channel);
            }
        });
    }

    private void configurePath(ObjectNode streamKey, ObjectNode channel) {
        media.configurePath(streamKey.path("value").asText(), !channel.path("recordingConfigurationArn").asText("")
                .isEmpty(), channel.path("srt").path("passphrase").asText(null));
    }

    /** Starts the media server when persisted channels need it. */
    public void restorePersistedRuntime() {
        if (!store.everywhere(key -> key.contains("/channel/")).isEmpty()) {
            ensureMedia();
        }
    }

    // ---------------------------------------------------------------- channels

    private String ingestHost() {
        URI base = URI.create(store.config().effectiveBaseUrl());
        return base.getHost() == null ? "localhost" : base.getHost();
    }

    String playbackUrl(String region, String account, String channelId) {
        return store.config().effectiveBaseUrl().replaceAll("/+$", "") + IvsPlaybackController.PLAYBACK_PREFIX
                + region + "." + account + ".channel." + channelId + ".m3u8";
    }

    public synchronized ObjectNode createChannel(JsonNode input, String region) {
        if (store.list(key(region, "channel", "")).size() >= MAX_CHANNELS) {
            throw quota("The account has reached its limit of " + MAX_CHANNELS + " channels in this Region.");
        }
        String id = IvsStore.id(12);
        ObjectNode channel = store.record(region, "channel", id, "arn");
        channel.put("name", input.path("name").asText(""));
        applyChannel(channel, input, region, true);
        channel.put("ingestEndpoint", ingestHost() + ":" + config().rtmpPort())
                .put("playbackUrl", playbackUrl(region, store.account(), id));
        channel.putObject("srt").put("endpoint", ingestHost() + ":" + config().srtPort())
                .put("passphrase", IvsStore.id(32));
        channel.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "channel", id), channel);
        ObjectNode streamKey = newStreamKey(region, id, null);
        ensureMedia();
        configurePath(streamKey, channel);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("channel", store.view(channel));
        response.set("streamKey", store.view(streamKey));
        return response;
    }

    /** Applies the settings CreateChannel and UpdateChannel share, checking the resources they name. */
    private void applyChannel(ObjectNode channel, JsonNode input, String region, boolean create) {
        String type = input.path("type").asText(create ? "STANDARD" : channel.path("type").asText("STANDARD"));
        if (!TYPES.contains(type)) {
            throw validation("type must be BASIC, STANDARD, ADVANCED_SD or ADVANCED_HD.");
        }
        String latency = input.path("latencyMode").asText(create ? "LOW" : channel.path("latencyMode").asText("LOW"));
        if (!Set.of("LOW", "NORMAL").contains(latency)) {
            throw validation("latencyMode must be LOW or NORMAL.");
        }
        String preset = input.path("preset").asText(create ? "" : channel.path("preset").asText(""));
        boolean advanced = type.startsWith("ADVANCED");
        if (advanced && preset.isEmpty()) {
            preset = "HIGHER_BANDWIDTH_DELIVERY";
        }
        if (!advanced && !preset.isEmpty() && input.has("preset")) {
            throw validation("A preset applies only to ADVANCED_SD and ADVANCED_HD channels.");
        }
        if (input.has("name")) {
            channel.put("name", input.get("name").asText());
        }
        channel.put("latencyMode", latency).put("type", type).put("preset", advanced ? preset : "")
                .put("authorized", input.path("authorized").asBoolean(create ? false
                        : channel.path("authorized").asBoolean()))
                .put("insecureIngest", input.path("insecureIngest").asBoolean(create ? false
                        : channel.path("insecureIngest").asBoolean()));
        String container = input.path("containerFormat").asText(create ? "TS"
                : channel.path("containerFormat").asText("TS"));
        JsonNode multitrack = input.has("multitrackInputConfiguration") ? input.get("multitrackInputConfiguration")
                : channel.path("multitrackInputConfiguration");
        if (multitrack.path("enabled").asBoolean(false)) {
            if (!"STANDARD".equals(type)) {
                throw validation("Multitrack input requires a STANDARD channel.");
            }
            if (!"FRAGMENTED_MP4".equals(container)) {
                throw validation("Multitrack input requires the FRAGMENTED_MP4 container format.");
            }
        }
        channel.put("containerFormat", container);
        ObjectNode multitrackOut = channel.putObject("multitrackInputConfiguration");
        multitrackOut.put("enabled", multitrack.path("enabled").asBoolean(false))
                .put("policy", multitrack.path("policy").asText("ALLOW"))
                .put("maximumResolution", multitrack.path("maximumResolution").asText("FULL_HD"));
        channel.put("recordingConfigurationArn", linked(input, channel, "recordingConfigurationArn",
                "recording-configuration", region, create));
        channel.put("playbackRestrictionPolicyArn", linked(input, channel, "playbackRestrictionPolicyArn",
                "playback-restriction-policy", region, create));
        channel.put("adConfigurationArn", linked(input, channel, "adConfigurationArn", "ad-configuration", region,
                create));
    }

    private String linked(JsonNode input, ObjectNode channel, String field, String kind, String region,
                          boolean create) {
        String arn = input.has(field) ? input.get(field).asText("") : create ? "" : channel.path(field).asText("");
        if (!arn.isEmpty()) {
            store.require(arn, kind, region);
        }
        return arn;
    }

    public synchronized ObjectNode getChannel(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("channel", store.view(store.require(input.path("arn").asText(null), "channel", region)));
        return response;
    }

    public synchronized ObjectNode batchGetChannel(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode channels = response.putArray("channels");
        ArrayNode errors = response.putArray("errors");
        for (JsonNode arn : input.path("arns")) {
            try {
                channels.add(store.view(store.require(arn.asText(), "channel", region)));
            } catch (AwsException e) {
                errors.addObject().put("arn", arn.asText()).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
            }
        }
        return response;
    }

    public synchronized ObjectNode listChannels(JsonNode input, String region) {
        int filters = 0;
        for (String field : List.of("filterByName", "filterByRecordingConfigurationArn",
                "filterByPlaybackRestrictionPolicyArn", "filterByAdConfigurationArn")) {
            filters += input.hasNonNull(field) && !input.get(field).asText().isEmpty() ? 1 : 0;
        }
        if (filters > 1) {
            throw conflict("ListChannels takes at most one filter.");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode channel : store.list(key(region, "channel", ""))) {
            if (!matches(input, "filterByName", channel.path("name").asText())
                    || !matches(input, "filterByRecordingConfigurationArn",
                    channel.path("recordingConfigurationArn").asText())
                    || !matches(input, "filterByPlaybackRestrictionPolicyArn",
                    channel.path("playbackRestrictionPolicyArn").asText())
                    || !matches(input, "filterByAdConfigurationArn", channel.path("adConfigurationArn").asText())) {
                continue;
            }
            ObjectNode summary = store.mapper().createObjectNode();
            for (String field : List.of("arn", "name", "latencyMode", "authorized", "recordingConfigurationArn",
                    "tags", "insecureIngest", "type", "preset", "playbackRestrictionPolicyArn",
                    "adConfigurationArn")) {
                summary.set(field, channel.get(field));
            }
            items.add(summary);
        }
        return store.page(items, input, "channels", 100);
    }

    private static boolean matches(JsonNode input, String field, String value) {
        String filter = input.path(field).asText("");
        if (filter.isEmpty()) {
            return true;
        }
        return "filterByName".equals(field) ? value.startsWith(filter) : value.equals(filter);
    }

    public synchronized ObjectNode updateChannel(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("arn").asText(null), "channel", region);
        String id = meta(channel).path("id").asText();
        if (live.stream(store.account(), region, id) != null) {
            throw conflict("Channel " + channel.path("arn").asText() + " is live; stop the stream to update it.");
        }
        applyChannel(channel, input, region, false);
        store.put(key(region, "channel", id), channel);
        for (ObjectNode streamKey : keysOf(region, id)) {
            configurePath(streamKey, channel);
        }
        ObjectNode response = store.mapper().createObjectNode();
        response.set("channel", store.view(channel));
        return response;
    }

    public synchronized ObjectNode deleteChannel(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("arn").asText(null), "channel", region);
        String id = meta(channel).path("id").asText();
        if (live.stream(store.account(), region, id) != null) {
            throw conflict("Channel " + channel.path("arn").asText() + " is live and cannot be deleted.");
        }
        for (ObjectNode streamKey : keysOf(region, id)) {
            media.removePath(streamKey.path("value").asText());
            store.delete(store.account(), key(region, "stream-key", meta(streamKey).path("id").asText()));
        }
        for (ObjectNode session : live.sessions(region, id)) {
            store.delete(store.account(), key(region, "session", id + "/" + session.path("streamId").asText()));
        }
        store.delete(store.account(), key(region, "revocation", id));
        live.forget(store.account(), region, id);
        store.delete(store.account(), key(region, "channel", id));
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- stream keys

    private List<ObjectNode> keysOf(String region, String channelId) {
        List<ObjectNode> keys = new ArrayList<>();
        for (ObjectNode streamKey : store.list(key(region, "stream-key", ""))) {
            if (channelId.equals(meta(streamKey).path("channelId").asText())) {
                keys.add(streamKey);
            }
        }
        return keys;
    }

    private ObjectNode newStreamKey(String region, String channelId, JsonNode tags) {
        String id = IvsStore.id(12);
        ObjectNode streamKey = store.record(region, "stream-key", id, "arn");
        streamKey.put("value", "sk_" + region + "_" + IvsStore.id(30))
                .put("channelArn", store.arn(region, "channel/" + channelId));
        streamKey.set("tags", store.tags(tags, null));
        meta(streamKey).put("channelId", channelId);
        store.put(key(region, "stream-key", id), streamKey);
        return streamKey;
    }

    public synchronized ObjectNode createStreamKey(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        String channelId = meta(channel).path("id").asText();
        if (!keysOf(region, channelId).isEmpty()) {
            throw quota("Channel " + channel.path("arn").asText() + " already has its one stream key.");
        }
        ObjectNode streamKey = newStreamKey(region, channelId, input.path("tags"));
        configurePath(streamKey, channel);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("streamKey", store.view(streamKey));
        return response;
    }

    public synchronized ObjectNode getStreamKey(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("streamKey", store.view(store.require(input.path("arn").asText(null), "stream-key", region)));
        return response;
    }

    public synchronized ObjectNode batchGetStreamKey(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode keys = response.putArray("streamKeys");
        ArrayNode errors = response.putArray("errors");
        for (JsonNode arn : input.path("arns")) {
            try {
                keys.add(store.view(store.require(arn.asText(), "stream-key", region)));
            } catch (AwsException e) {
                errors.addObject().put("arn", arn.asText()).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
            }
        }
        return response;
    }

    public synchronized ObjectNode listStreamKeys(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode streamKey : keysOf(region, meta(channel).path("id").asText())) {
            ObjectNode summary = store.mapper().createObjectNode();
            summary.set("arn", streamKey.get("arn"));
            summary.set("channelArn", streamKey.get("channelArn"));
            summary.set("tags", streamKey.get("tags"));
            items.add(summary);
        }
        return store.page(items, input, "streamKeys", 50);
    }

    /** Deletes a stream key: the broadcaster using it is disconnected and it can no longer be used. */
    public synchronized ObjectNode deleteStreamKey(JsonNode input, String region) {
        ObjectNode streamKey = store.require(input.path("arn").asText(null), "stream-key", region);
        media.removePath(streamKey.path("value").asText());
        store.delete(store.account(), key(region, "stream-key", meta(streamKey).path("id").asText()));
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- streams and sessions

    private IvsLive.Live liveStream(ObjectNode channel, String region) {
        IvsLive.Live stream = live.stream(store.account(), region, meta(channel).path("id").asText());
        if (stream == null) {
            throw notBroadcasting(channel.path("arn").asText());
        }
        return stream;
    }

    public synchronized ObjectNode getStream(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        IvsLive.Live stream = liveStream(channel, region);
        ObjectNode response = store.mapper().createObjectNode();
        response.putObject("stream").put("channelArn", channel.path("arn").asText())
                .put("streamId", stream.streamId).put("playbackUrl", channel.path("playbackUrl").asText())
                .put("startTime", stream.started.toString()).put("state", "LIVE").put("health", stream.health)
                .put("viewerCount", stream.viewers);
        return response;
    }

    public synchronized ObjectNode listStreams(JsonNode input, String region) {
        String health = input.path("filterBy").path("health").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (IvsLive.Live stream : live.streams(store.account(), region)) {
            if (health != null && !health.equals(stream.health)) {
                continue;
            }
            ObjectNode summary = store.mapper().createObjectNode();
            summary.put("channelArn", store.arn(region, "channel/" + stream.channelId))
                    .put("streamId", stream.streamId).put("state", "LIVE").put("health", stream.health)
                    .put("viewerCount", stream.viewers).put("startTime", stream.started.toString());
            items.add(summary);
        }
        return store.page(items, input, "streams", 100);
    }

    public synchronized ObjectNode getStreamSession(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        String channelId = meta(channel).path("id").asText();
        String streamId = input.path("streamId").asText(null);
        ObjectNode session;
        if (streamId == null || streamId.isEmpty()) {
            List<ObjectNode> sessions = live.sessions(region, channelId);
            if (sessions.isEmpty()) {
                throw notFound("Channel " + channel.path("arn").asText() + " has no stream sessions.");
            }
            session = sessions.get(0);
        } else {
            session = store.get(key(region, "session", channelId + "/" + streamId))
                    .orElseThrow(() -> notFound("Stream session " + streamId + " not found."));
        }
        ObjectNode response = store.mapper().createObjectNode();
        response.set("streamSession", store.view(session));
        return response;
    }

    public synchronized ObjectNode listStreamSessions(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode session : live.sessions(region, meta(channel).path("id").asText())) {
            ObjectNode summary = store.mapper().createObjectNode();
            summary.set("streamId", session.get("streamId"));
            summary.set("startTime", session.get("startTime"));
            if (session.has("endTime")) {
                summary.set("endTime", session.get("endTime"));
            }
            boolean error = false;
            for (JsonNode event : session.path("truncatedEvents")) {
                error |= event.path("name").asText().contains("Failure");
            }
            summary.put("hasErrorEvent", error);
            items.add(summary);
        }
        return store.page(items, input, "streamSessions", 100);
    }

    public synchronized ObjectNode stopStream(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        IvsLive.Live stream = liveStream(channel, region);
        if (!live.stop(stream)) {
            throw new AwsException("StreamUnavailable", "The stream could not be stopped.", 503);
        }
        return store.mapper().createObjectNode();
    }

    /**
     * Inserts timed metadata into a live stream, at most 5 a second and 1 KB each. floci records it on the
     * stream's session; it is not carried in the HLS segments.
     */
    public synchronized ObjectNode putMetadata(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        String metadata = input.path("metadata").asText("");
        if (metadata.isEmpty() || metadata.getBytes(StandardCharsets.UTF_8).length
                > MAX_METADATA_BYTES) {
            throw validation("metadata is 1 byte to 1 KB.");
        }
        IvsLive.Live stream = liveStream(channel, region);
        long now = System.currentTimeMillis();
        long[] window = metadataRate.computeIfAbsent(channel.path("arn").asText(), arn -> new long[] {now, 0});
        if (now - window[0] >= 1000) {
            window[0] = now;
            window[1] = 0;
        }
        if (++window[1] > METADATA_PER_SECOND) {
            throw IvsStore.throttled("A channel takes at most " + METADATA_PER_SECOND + " metadata insertions a "
                    + "second.");
        }
        live.sessionEvent(stream, "Metadata Inserted", "IVS Stream State Change");
        return store.mapper().createObjectNode();
    }

    /** Inserts an ad break into a live stream of a channel with an ad configuration. */
    public synchronized ObjectNode insertAdBreak(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        int duration = input.path("durationSeconds").asInt(0);
        if (duration < 1 || duration > 300) {
            throw validation("durationSeconds is 1 to 300.");
        }
        IvsLive.Live stream = liveStream(channel, region);
        if (channel.path("adConfigurationArn").asText("").isEmpty()) {
            throw conflict("Channel " + channel.path("arn").asText() + " has no ad configuration.");
        }
        String adBreakId = IvsStore.id(12);
        ObjectNode extra = store.mapper().createObjectNode();
        extra.put("ad_break_id", adBreakId).put("duration_seconds", duration)
                .put("target_start_time", Instant.now().plusSeconds(10).toString());
        live.event(stream, "IVS Ad Break State Change", "Ad Break Inserted", extra);
        live.sessionEvent(stream, "Ad Break Inserted", "IVS Ad Break State Change");
        ObjectNode response = store.mapper().createObjectNode();
        response.put("adBreakId", adBreakId);
        return response;
    }

    // ---------------------------------------------------------------- viewer sessions

    /** Revokes a viewer's sessions up to a version: tokens of those versions no longer play. */
    public synchronized ObjectNode startViewerSessionRevocation(JsonNode input, String region) {
        ObjectNode channel = store.require(input.path("channelArn").asText(null), "channel", region);
        revoke(region, channel, input.path("viewerId").asText(""), input.path(
                "viewerSessionVersionsLessThanOrEqualTo").asInt(0));
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode batchStartViewerSessionRevocation(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        ArrayNode errors = response.putArray("errors");
        JsonNode sessions = input.path("viewerSessions");
        if (sessions.size() < 1 || sessions.size() > 20) {
            throw validation("viewerSessions holds 1 to 20 viewer sessions.");
        }
        for (JsonNode session : sessions) {
            try {
                ObjectNode channel = store.require(session.path("channelArn").asText(null), "channel", region);
                revoke(region, channel, session.path("viewerId").asText(""),
                        session.path("viewerSessionVersionsLessThanOrEqualTo").asInt(0));
            } catch (AwsException e) {
                errors.addObject().put("channelArn", session.path("channelArn").asText())
                        .put("viewerId", session.path("viewerId").asText()).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
            }
        }
        return response;
    }

    private void revoke(String region, ObjectNode channel, String viewerId, int version) {
        if (viewerId.isEmpty() || viewerId.length() > 40) {
            throw validation("viewerId is 1 to 40 characters.");
        }
        if (version < 0) {
            throw validation("viewerSessionVersionsLessThanOrEqualTo is 0 or more.");
        }
        String key = key(region, "revocation", meta(channel).path("id").asText());
        ObjectNode revocations = store.get(key).orElseGet(() -> {
            ObjectNode created = store.mapper().createObjectNode();
            created.putObject("_").put("kind", "revocation");
            return created;
        });
        int current = revocations.path(viewerId).asInt(-1);
        revocations.put(viewerId, Math.max(current, version));
        store.put(key, revocations);
    }

    /** The highest revoked session version of a viewer, or -1. */
    synchronized int revokedVersion(String account, String region, String channelId, String viewerId) {
        return store.get(account, key(region, "revocation", channelId)).map(record -> record.path(viewerId)
                .asInt(-1)).orElse(-1);
    }

    // ---------------------------------------------------------------- playback key pairs

    public synchronized ObjectNode importPlaybackKeyPair(JsonNode input, String region) {
        String material = input.path("publicKeyMaterial").asText("");
        byte[] der = publicKey(material);
        String fingerprint = fingerprint(der);
        List<ObjectNode> existing = store.list(key(region, "playback-key", ""));
        for (ObjectNode keyPair : existing) {
            if (fingerprint.equals(keyPair.path("fingerprint").asText())) {
                throw conflict("A playback key pair with this public key already exists.");
            }
        }
        if (existing.size() >= MAX_KEY_PAIRS) {
            throw quota("The account has reached its limit of " + MAX_KEY_PAIRS + " playback key pairs.");
        }
        String id = IvsStore.id(12);
        ObjectNode keyPair = store.record(region, "playback-key", id, "arn");
        keyPair.put("name", input.path("name").asText("")).put("fingerprint", fingerprint);
        keyPair.set("tags", store.tags(input.path("tags"), null));
        meta(keyPair).put("der", Base64.getEncoder().encodeToString(der));
        store.put(key(region, "playback-key", id), keyPair);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("keyPair", store.view(keyPair));
        return response;
    }

    /** The DER of an ECDSA P-384 public key given as PEM or base64. */
    private static byte[] publicKey(String material) {
        String body = material.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (key.getParams().getCurve().getField().getFieldSize() != 384) {
                throw validation("A playback key pair is an ECDSA P-384 key.");
            }
        } catch (IllegalArgumentException | InvalidKeySpecException | ClassCastException
                 | NoSuchAlgorithmException e) {
            throw validation("publicKeyMaterial must be an ECDSA P-384 public key in PEM.");
        }
        return der;
    }

    private static String fingerprint(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(der);
            return HexFormat.ofDelimiter(":").formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized ObjectNode getPlaybackKeyPair(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("keyPair", store.view(store.require(input.path("arn").asText(null), "playback-key", region)));
        return response;
    }

    public synchronized ObjectNode listPlaybackKeyPairs(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "playback-key", "")).forEach(keyPair -> {
            ObjectNode summary = store.mapper().createObjectNode();
            summary.set("arn", keyPair.get("arn"));
            summary.set("name", keyPair.get("name"));
            summary.set("tags", keyPair.get("tags"));
            items.add(summary);
        });
        return store.page(items, input, "keyPairs", 100);
    }

    public synchronized ObjectNode deletePlaybackKeyPair(JsonNode input, String region) {
        ObjectNode keyPair = store.require(input.path("arn").asText(null), "playback-key", region);
        store.delete(store.account(), key(region, "playback-key", meta(keyPair).path("id").asText()));
        return store.mapper().createObjectNode();
    }

    /** The public keys playback tokens of an account and Region may be signed with. */
    synchronized List<byte[]> playbackKeys(String account, String region) {
        List<byte[]> keys = new ArrayList<>();
        store.list(account, key(region, "playback-key", "")).forEach(keyPair -> keys.add(Base64.getDecoder()
                .decode(meta(keyPair).path("der").asText())));
        return keys;
    }

    // ---------------------------------------------------------------- playback restriction policies

    public synchronized ObjectNode createPlaybackRestrictionPolicy(JsonNode input, String region) {
        String id = IvsStore.id(12);
        ObjectNode policy = store.record(region, "playback-restriction-policy", id, "arn");
        policy.put("name", input.path("name").asText(""));
        applyPolicy(policy, input, true);
        policy.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "playback-restriction-policy", id), policy);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("playbackRestrictionPolicy", store.view(policy));
        return response;
    }

    private void applyPolicy(ObjectNode policy, JsonNode input, boolean create) {
        if (input.has("allowedCountries") || create) {
            ArrayNode countries = policy.putArray("allowedCountries");
            for (JsonNode country : input.path("allowedCountries")) {
                if (!country.asText().matches("[A-Z]{2}")) {
                    throw validation("allowedCountries takes ISO 3166-1 alpha-2 codes, not " + country.asText()
                            + ".");
                }
                countries.add(country.asText());
            }
        }
        if (input.has("allowedOrigins") || create) {
            ArrayNode origins = policy.putArray("allowedOrigins");
            for (JsonNode origin : input.path("allowedOrigins")) {
                origins.add(origin.asText());
            }
        }
        if (input.has("name")) {
            policy.put("name", input.get("name").asText());
        }
        policy.put("enableStrictOriginEnforcement", input.path("enableStrictOriginEnforcement").asBoolean(
                !create && policy.path("enableStrictOriginEnforcement").asBoolean(false)));
    }

    public synchronized ObjectNode getPlaybackRestrictionPolicy(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("playbackRestrictionPolicy", store.view(store.require(input.path("arn").asText(null),
                "playback-restriction-policy", region)));
        return response;
    }

    public synchronized ObjectNode listPlaybackRestrictionPolicies(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "playback-restriction-policy", "")).forEach(policy -> items.add(store.view(policy)));
        return store.page(items, input, "playbackRestrictionPolicies", 100);
    }

    public synchronized ObjectNode updatePlaybackRestrictionPolicy(JsonNode input, String region) {
        ObjectNode policy = store.require(input.path("arn").asText(null), "playback-restriction-policy", region);
        applyPolicy(policy, input, false);
        store.put(key(region, "playback-restriction-policy", meta(policy).path("id").asText()), policy);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("playbackRestrictionPolicy", store.view(policy));
        return response;
    }

    public synchronized ObjectNode deletePlaybackRestrictionPolicy(JsonNode input, String region) {
        ObjectNode policy = store.require(input.path("arn").asText(null), "playback-restriction-policy", region);
        refuseIfAttached(region, "playbackRestrictionPolicyArn", policy.path("arn").asText());
        store.delete(store.account(), key(region, "playback-restriction-policy", meta(policy).path("id").asText()));
        return store.mapper().createObjectNode();
    }

    /** A channel's playback restriction policy, or null. */
    synchronized ObjectNode restrictionPolicy(String account, String region, String arn) {
        if (arn == null || arn.isEmpty()) {
            return null;
        }
        return store.get(account, key(region, "playback-restriction-policy", arn.substring(arn.lastIndexOf('/')
                + 1))).orElse(null);
    }

    private void refuseIfAttached(String region, String field, String arn) {
        for (ObjectNode channel : store.list(key(region, "channel", ""))) {
            if (arn.equals(channel.path(field).asText())) {
                throw conflict("The resource is used by channel " + channel.path("arn").asText() + ".");
            }
        }
    }

    // ---------------------------------------------------------------- recording configurations

    public synchronized ObjectNode createRecordingConfiguration(JsonNode input, String region) {
        String bucket = input.path("destinationConfiguration").path("s3").path("bucketName").asText("");
        if (bucket.isEmpty()) {
            throw validation("destinationConfiguration.s3.bucketName is required.");
        }
        int window = input.path("recordingReconnectWindowSeconds").asInt(0);
        if (window < 0 || window > 300) {
            throw validation("recordingReconnectWindowSeconds is 0 to 300.");
        }
        String id = IvsStore.id(12);
        ObjectNode configuration = store.record(region, "recording-configuration", id, "arn");
        configuration.put("name", input.path("name").asText(""));
        configuration.set("destinationConfiguration", input.get("destinationConfiguration").deepCopy());
        JsonNode thumbnails = input.path("thumbnailConfiguration");
        String mode = thumbnails.path("recordingMode").asText("INTERVAL");
        int interval = thumbnails.path("targetIntervalSeconds").asInt(60);
        if (interval < 1 || interval > 60) {
            throw validation("thumbnailConfiguration.targetIntervalSeconds is 1 to 60.");
        }
        ObjectNode thumbnailOut = configuration.putObject("thumbnailConfiguration");
        thumbnailOut.put("recordingMode", mode).put("targetIntervalSeconds", interval);
        if (thumbnails.hasNonNull("resolution")) {
            thumbnailOut.put("resolution", thumbnails.get("resolution").asText());
        }
        ArrayNode storage = thumbnailOut.putArray("storage");
        if (thumbnails.path("storage").isArray() && !thumbnails.get("storage").isEmpty()) {
            thumbnails.get("storage").forEach(storage::add);
        } else {
            storage.add("SEQUENTIAL");
        }
        configuration.put("recordingReconnectWindowSeconds", window);
        JsonNode renditions = input.path("renditionConfiguration");
        ObjectNode renditionOut = configuration.putObject("renditionConfiguration");
        renditionOut.put("renditionSelection", renditions.path("renditionSelection").asText("ALL"));
        renditionOut.set("renditions", renditions.path("renditions").isArray() ? renditions.get("renditions")
                .deepCopy() : store.mapper().createArrayNode());
        configuration.set("tags", store.tags(input.path("tags"), null));
        boolean exists = s3.get().bucketExists(bucket);
        configuration.put("state", exists ? "ACTIVE" : "CREATE_FAILED");
        store.put(key(region, "recording-configuration", id), configuration);
        ObjectNode response = store.mapper().createObjectNode();
        ObjectNode view = store.view(configuration);
        if (exists && config().transitionMillis() > 0) {
            view.put("state", "CREATING");
        }
        response.set("recordingConfiguration", view);
        return response;
    }

    public synchronized ObjectNode getRecordingConfiguration(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("recordingConfiguration", store.view(store.require(input.path("arn").asText(null),
                "recording-configuration", region)));
        return response;
    }

    public synchronized ObjectNode listRecordingConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "recording-configuration", "")).forEach(configuration -> {
            ObjectNode summary = store.mapper().createObjectNode();
            for (String field : List.of("arn", "name", "destinationConfiguration", "state", "tags")) {
                summary.set(field, configuration.get(field));
            }
            items.add(summary);
        });
        return store.page(items, input, "recordingConfigurations", 100);
    }

    public synchronized ObjectNode deleteRecordingConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "recording-configuration", region);
        refuseIfAttached(region, "recordingConfigurationArn", configuration.path("arn").asText());
        store.delete(store.account(), key(region, "recording-configuration", meta(configuration).path("id")
                .asText()));
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- ad configurations

    public synchronized ObjectNode createAdConfiguration(JsonNode input, String region) {
        String id = IvsStore.id(12);
        ObjectNode configuration = store.record(region, "ad-configuration", id, "arn");
        configuration.put("name", input.path("name").asText(""));
        applyAdConfiguration(configuration, input);
        configuration.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "ad-configuration", id), configuration);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("adConfiguration", store.view(configuration));
        return response;
    }

    private void applyAdConfiguration(ObjectNode configuration, JsonNode input) {
        if (input.has("mediaTailorPlaybackConfigurations")) {
            JsonNode playback = input.get("mediaTailorPlaybackConfigurations");
            if (playback.size() != 1) {
                throw validation("mediaTailorPlaybackConfigurations takes one MediaTailor playback configuration.");
            }
            for (JsonNode entry : playback) {
                String arn = entry.path("playbackConfigurationArn").asText("");
                if (!AwsArnUtils.isArnFor(arn, "mediatailor") || !AwsArnUtils.parse(arn).resource()
                        .startsWith("playbackConfiguration/")) {
                    throw validation("playbackConfigurationArn must be a MediaTailor playback configuration ARN.");
                }
            }
            configuration.set("mediaTailorPlaybackConfigurations", playback.deepCopy());
        }
        if (input.has("postRollConfiguration")) {
            configuration.set("postRollConfiguration", input.get("postRollConfiguration").deepCopy());
        }
        if (input.has("name")) {
            configuration.put("name", input.get("name").asText());
        }
    }

    public synchronized ObjectNode getAdConfiguration(JsonNode input, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("adConfiguration", store.view(store.require(input.path("arn").asText(null), "ad-configuration",
                region)));
        return response;
    }

    public synchronized ObjectNode listAdConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "ad-configuration", "")).forEach(configuration -> items.add(store.view(configuration)));
        return store.page(items, input, "adConfigurations", 100);
    }

    public synchronized ObjectNode updateAdConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "ad-configuration", region);
        applyAdConfiguration(configuration, input);
        store.put(key(region, "ad-configuration", meta(configuration).path("id").asText()), configuration);
        ObjectNode response = store.mapper().createObjectNode();
        response.set("adConfiguration", store.view(configuration));
        return response;
    }

    public synchronized ObjectNode deleteAdConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "ad-configuration", region);
        refuseIfAttached(region, "adConfigurationArn", configuration.path("arn").asText());
        store.delete(store.account(), key(region, "ad-configuration", meta(configuration).path("id").asText()));
        return store.mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- tags

    private static final Set<String> TAGGED = Set.of("channel", "stream-key", "playback-key",
            "playback-restriction-policy", "recording-configuration", "ad-configuration");

    /** Whether an ARN names a resource this service tags; IVS Real-Time's resources share the tag routes. */
    static boolean tags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "ivs")) {
            return true;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        return TAGGED.contains(resource.substring(0, Math.max(0, resource.indexOf('/'))));
    }

    private ObjectNode tagged(String arn, String region) {
        String[] parts = IvsStore.parse(arn, region, store.account());
        if (parts == null || !TAGGED.contains(parts[0])) {
            throw notFound("Resource " + arn + " not found.");
        }
        return store.require(arn, parts[0], region);
    }

    public synchronized ObjectNode listTagsForResource(String arn, String region) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set("tags", tagged(arn, region).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(String arn, JsonNode input, String region) {
        ObjectNode record = tagged(arn, region);
        record.set("tags", store.tags(input.path("tags"), (ObjectNode) record.path("tags")));
        store.put(key(region, meta(record).path("kind").asText(), meta(record).path("id").asText()), record);
        return store.mapper().createObjectNode();
    }

    public synchronized ObjectNode untagResource(String arn, List<String> keys, String region) {
        ObjectNode record = tagged(arn, region);
        ObjectNode tags = (ObjectNode) record.path("tags");
        keys.forEach(tags::remove);
        store.put(key(region, meta(record).path("kind").asText(), meta(record).path("id").asText()), record);
        return store.mapper().createObjectNode();
    }

    /** The channel a playback URL names, with the account that owns it. */
    synchronized ObjectNode channelFor(String account, String region, String channelId) {
        return store.get(account, key(region, "channel", channelId)).orElse(null);
    }
}
