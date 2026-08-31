package io.github.hectorvent.floci.services.ivs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivs.model.Channel;
import io.github.hectorvent.floci.services.ivs.model.PlaybackKeyPair;
import io.github.hectorvent.floci.services.ivs.model.PlaybackRestrictionPolicy;
import io.github.hectorvent.floci.services.ivs.model.StreamKey;
import io.github.hectorvent.floci.services.ivsrealtime.IvsRealtimeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon IVS restJson1 — channel, playback key pair, and restriction-policy lifecycle.
 *
 * <p>{@code CreateChannel} also provisions the channel's default stream key
 * (limit one per channel), matching live AWS. Tag APIs share {@code /tags/{arn}}
 * via {@link TagHandler} using ARN service {@code ivs}.
 */
@ApplicationScoped
public class IvsService implements TagHandler {

    static final String SERVICE = "ivs";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "ivs:v1:";
    private static final String DEFAULT_LATENCY = "LOW";
    private static final String DEFAULT_TYPE = "STANDARD";
    private static final Set<String> LATENCY_MODES = Set.of("LOW", "NORMAL");
    private static final Set<String> CHANNEL_TYPES = Set.of(
            "BASIC", "STANDARD", "ADVANCED_SD", "ADVANCED_HD");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final String POLICY_RESOURCE = "playback-restriction-policy/";
    private static final String KEY_PAIR_RESOURCE = "playback-key/";
    private static final String STREAM_KEY_RESOURCE = "stream-key/";

    private final StorageBackend<String, Channel> store;
    private final StorageBackend<String, PlaybackRestrictionPolicy> policyStore;
    private final StorageBackend<String, PlaybackKeyPair> keyPairStore;
    private final RegionResolver regionResolver;

    @Inject
    IvsRecordingConfigurationService recordingConfigurations;

    @Inject
    IvsRealtimeService stages;

    @Inject
    public IvsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("ivs", "ivs-channels.json",
                        new TypeReference<Map<String, Channel>>() {
                        }),
                storageFactory.create("ivs", "ivs-playback-restriction-policies.json",
                        new TypeReference<Map<String, PlaybackRestrictionPolicy>>() {
                        }),
                storageFactory.create("ivs", "ivs-playback-key-pairs.json",
                        new TypeReference<Map<String, PlaybackKeyPair>>() {
                        }),
                regionResolver);
    }

    IvsService(StorageBackend<String, Channel> store, RegionResolver regionResolver) {
        this(store, null, null, regionResolver);
    }

    IvsService(
            StorageBackend<String, Channel> store,
            StorageBackend<String, PlaybackRestrictionPolicy> policyStore,
            RegionResolver regionResolver) {
        this(store, policyStore, null, regionResolver);
    }

    IvsService(
            StorageBackend<String, Channel> store,
            StorageBackend<String, PlaybackRestrictionPolicy> policyStore,
            StorageBackend<String, PlaybackKeyPair> keyPairStore,
            RegionResolver regionResolver) {
        this.store = store;
        this.policyStore = policyStore;
        this.keyPairStore = keyPairStore;
        this.regionResolver = regionResolver;
    }

    public synchronized Channel createChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        String latencyMode = optionalEnum(request, "latencyMode", LATENCY_MODES, DEFAULT_LATENCY);
        String type = optionalEnum(request, "type", CHANNEL_TYPES, DEFAULT_TYPE);
        boolean authorized = optionalBoolean(request, "authorized", false);
        boolean insecureIngest = optionalBoolean(request, "insecureIngest", false);
        String recordingConfigurationArn = optionalText(request, "recordingConfigurationArn");
        String preset = optionalText(request, "preset");
        String playbackRestrictionPolicyArn = optionalText(request, "playbackRestrictionPolicyArn");
        String containerFormat = optionalText(request, "containerFormat");
        String adConfigurationArn = optionalText(request, "adConfigurationArn");
        Map<String, String> tags = readTags(request.get("tags"));

        String account = regionResolver.getAccountId();
        String id = newId();
        while (store.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        String arn = AwsArnUtils.Arn.of(SERVICE, region, account, "channel/" + id).toString();
        String streamKeyId = newId();
        String streamKeyArn = AwsArnUtils.Arn.of(SERVICE, region, account, "stream-key/" + streamKeyId)
                .toString();

        Channel channel = new Channel();
        channel.setId(id);
        channel.setArn(arn);
        channel.setName(name);
        channel.setLatencyMode(latencyMode);
        channel.setType(type);
        channel.setAuthorized(authorized);
        channel.setInsecureIngest(insecureIngest);
        channel.setRecordingConfigurationArn(recordingConfigurationArn);
        channel.setPreset(preset);
        channel.setPlaybackRestrictionPolicyArn(playbackRestrictionPolicyArn);
        channel.setContainerFormat(containerFormat);
        channel.setAdConfigurationArn(adConfigurationArn);
        channel.setIngestEndpoint(id + ".global-contribute.live-video.net");
        channel.setPlaybackUrl("https://" + id + "." + region
                + ".playback.live-video.net/api/video/v1/" + region + "." + account
                + ".channel." + id + ".m3u8");
        channel.setSrtEndpoint(id + ".srt.live-video.net");
        channel.setSrtPassphrase(newPassphrase());
        channel.setStreamKeyArn(streamKeyArn);
        channel.setStreamKeyValue("sk_" + region + "_" + newId() + newId());
        channel.setStreamKeyTags(new LinkedHashMap<>());
        channel.setTags(tags);
        store.put(storageKey(region, id), channel);
        return channel;
    }

    public Channel getChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        return requireChannel(region, arn);
    }

    public synchronized Channel updateChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        Channel channel = requireChannel(region, arn);
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            channel.setName(name);
        }
        if (request.has("latencyMode") && !request.get("latencyMode").isNull()) {
            channel.setLatencyMode(optionalEnum(request, "latencyMode", LATENCY_MODES, channel.getLatencyMode()));
        }
        if (request.has("type") && !request.get("type").isNull()) {
            channel.setType(optionalEnum(request, "type", CHANNEL_TYPES, channel.getType()));
        }
        if (request.has("authorized") && !request.get("authorized").isNull()) {
            channel.setAuthorized(requireBoolean(request, "authorized"));
        }
        if (request.has("insecureIngest") && !request.get("insecureIngest").isNull()) {
            channel.setInsecureIngest(requireBoolean(request, "insecureIngest"));
        }
        if (request.has("recordingConfigurationArn")) {
            channel.setRecordingConfigurationArn(optionalText(request, "recordingConfigurationArn"));
        }
        if (request.has("preset")) {
            channel.setPreset(optionalText(request, "preset"));
        }
        if (request.has("playbackRestrictionPolicyArn")) {
            channel.setPlaybackRestrictionPolicyArn(optionalText(request, "playbackRestrictionPolicyArn"));
        }
        if (request.has("containerFormat")) {
            channel.setContainerFormat(optionalText(request, "containerFormat"));
        }
        if (request.has("adConfigurationArn")) {
            channel.setAdConfigurationArn(optionalText(request, "adConfigurationArn"));
        }
        store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
        return channel;
    }

    public synchronized void deleteChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        Channel channel = requireChannel(region, arn);
        store.delete(storageKey(channelRegion(channel, region), channel.getId()));
    }

    public Page listChannels(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        String filterByName = optionalText(request, "filterByName");
        String filterByRecording = optionalText(request, "filterByRecordingConfigurationArn");
        String filterByPlayback = optionalText(request, "filterByPlaybackRestrictionPolicyArn");
        String filterByAd = optionalText(request, "filterByAdConfigurationArn");

        List<Channel> channels = new ArrayList<>(store.scan(key -> key.startsWith(region + "::")));
        channels.sort(Comparator.comparing(Channel::getArn, Comparator.nullsLast(String::compareTo)));
        if (filterByName != null) {
            channels = channels.stream().filter(c -> filterByName.equals(c.getName())).toList();
        }
        if (filterByRecording != null) {
            channels = channels.stream()
                    .filter(c -> filterByRecording.equals(c.getRecordingConfigurationArn()))
                    .toList();
        }
        if (filterByPlayback != null) {
            channels = channels.stream()
                    .filter(c -> filterByPlayback.equals(c.getPlaybackRestrictionPolicyArn()))
                    .toList();
        }
        if (filterByAd != null) {
            channels = channels.stream()
                    .filter(c -> filterByAd.equals(c.getAdConfigurationArn()))
                    .toList();
        }

        int offset = decodeOffset(optionalText(request, "nextToken"), channels.size());
        int end = Math.min(offset + maxResults, channels.size());
        String responseToken = end < channels.size() ? encodeOffset(end) : null;
        return new Page(channels.subList(offset, end), responseToken);
    }

    public void getStream(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        throw notBroadcasting();
    }

    public void getStreamSession(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        throw notFound(requireText(request, "channelArn"));
    }

    public void listStreamSessions(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
    }

    public void listStreams(String region, JsonNode request) {
        requireObject(request, "Request body");
    }

    public void putMetadata(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        requireText(request, "metadata");
        throw notBroadcasting();
    }

    public void stopStream(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        throw notBroadcasting();
    }

    public void startViewerSessionRevocation(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        requireText(request, "viewerId");
    }

    public void insertAdBreak(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, requireText(request, "channelArn"));
        throw notBroadcasting();
    }

    public List<BatchRevocationError> batchStartViewerSessionRevocation(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode sessions = request.get("viewerSessions");
        if (sessions == null || sessions.isNull()) {
            return List.of();
        }
        if (!sessions.isArray()) {
            throw validation("viewerSessions must be an array.");
        }
        List<BatchRevocationError> errors = new ArrayList<>();
        for (JsonNode session : sessions) {
            requireObject(session, "viewerSessions members");
            String channelArn = requireText(session, "channelArn");
            String viewerId = requireText(session, "viewerId");
            try {
                requireChannel(region, channelArn);
            } catch (AwsException e) {
                errors.add(new BatchRevocationError(channelArn, viewerId, e.jsonType(), e.getMessage()));
            }
        }
        return errors;
    }

    public synchronized PlaybackRestrictionPolicy createPlaybackRestrictionPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        List<String> allowedCountries = readStringList(request, "allowedCountries");
        List<String> allowedOrigins = readStringList(request, "allowedOrigins");
        boolean strict = optionalBoolean(request, "enableStrictOriginEnforcement", false);
        Map<String, String> tags = readTags(request.get("tags"));

        String account = regionResolver.getAccountId();
        String id = newId();
        while (policyStore.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        String arn = AwsArnUtils.Arn.of(SERVICE, region, account, POLICY_RESOURCE + id).toString();
        PlaybackRestrictionPolicy policy = new PlaybackRestrictionPolicy();
        policy.setId(id);
        policy.setArn(arn);
        policy.setName(name);
        policy.setAllowedCountries(allowedCountries);
        policy.setAllowedOrigins(allowedOrigins);
        policy.setEnableStrictOriginEnforcement(strict);
        policy.setTags(tags);
        policyStore.put(storageKey(region, id), policy);
        return policy;
    }

    public PlaybackRestrictionPolicy getPlaybackRestrictionPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requirePolicy(region, requireText(request, "arn"));
    }

    public synchronized PlaybackRestrictionPolicy updatePlaybackRestrictionPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        PlaybackRestrictionPolicy policy = requirePolicy(region, requireText(request, "arn"));
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            policy.setName(name);
        }
        if (request.has("allowedCountries")) {
            policy.setAllowedCountries(readStringList(request, "allowedCountries"));
        }
        if (request.has("allowedOrigins")) {
            policy.setAllowedOrigins(readStringList(request, "allowedOrigins"));
        }
        if (request.has("enableStrictOriginEnforcement")
                && !request.get("enableStrictOriginEnforcement").isNull()) {
            policy.setEnableStrictOriginEnforcement(requireBoolean(request, "enableStrictOriginEnforcement"));
        }
        policyStore.put(storageKey(resourceRegion(policy.getArn(), region), policy.getId()), policy);
        return policy;
    }

    public synchronized void deletePlaybackRestrictionPolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        PlaybackRestrictionPolicy policy = requirePolicy(region, requireText(request, "arn"));
        String arn = policy.getArn();
        boolean attached = store.scan(key -> true).stream()
                .anyMatch(channel -> arn.equals(channel.getPlaybackRestrictionPolicyArn()));
        if (attached) {
            throw conflict("Resource: " + arn + " is associated with a channel");
        }
        policyStore.delete(storageKey(resourceRegion(arn, region), policy.getId()));
    }

    public PolicyPage listPlaybackRestrictionPolicies(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        List<PlaybackRestrictionPolicy> policies = new ArrayList<>(
                policyStore.scan(key -> key.startsWith(region + "::")));
        policies.sort(Comparator.comparing(PlaybackRestrictionPolicy::getArn, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), policies.size());
        int end = Math.min(offset + maxResults, policies.size());
        String responseToken = end < policies.size() ? encodeOffset(end) : null;
        return new PolicyPage(policies.subList(offset, end), responseToken);
    }

    public synchronized PlaybackKeyPair importPlaybackKeyPair(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireKeyPairStore();
        String publicKeyMaterial = requireText(request, "publicKeyMaterial");
        String fingerprint = fingerprintOf(publicKeyMaterial);
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
            PlaybackKeyPair existing = findKeyPairByName(region, name);
            if (existing != null) {
                throw conflict("A playback key pair named " + name + " already exists.");
            }
        }
        Map<String, String> tags = readTags(request.get("tags"));
        String account = regionResolver.getAccountId();
        String id = newId();
        while (keyPairStore.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        if (name == null) {
            name = id;
        }
        String arn = AwsArnUtils.Arn.of(SERVICE, region, account, KEY_PAIR_RESOURCE + id).toString();
        PlaybackKeyPair keyPair = new PlaybackKeyPair();
        keyPair.setId(id);
        keyPair.setArn(arn);
        keyPair.setName(name);
        keyPair.setFingerprint(fingerprint);
        keyPair.setPublicKeyMaterial(publicKeyMaterial);
        keyPair.setTags(tags);
        keyPairStore.put(storageKey(region, id), keyPair);
        return keyPair;
    }

    public PlaybackKeyPair getPlaybackKeyPair(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireKeyPair(region, requireText(request, "arn"));
    }

    public synchronized void deletePlaybackKeyPair(String region, JsonNode request) {
        requireObject(request, "Request body");
        PlaybackKeyPair keyPair = requireKeyPair(region, requireText(request, "arn"));
        keyPairStore.delete(storageKey(resourceRegion(keyPair.getArn(), region), keyPair.getId()));
    }

    public KeyPairPage listPlaybackKeyPairs(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireKeyPairStore();
        int maxResults = parseMaxResults(request);
        List<PlaybackKeyPair> keyPairs = new ArrayList<>(
                keyPairStore.scan(key -> key.startsWith(region + "::")));
        keyPairs.sort(Comparator.comparing(PlaybackKeyPair::getArn, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), keyPairs.size());
        int end = Math.min(offset + maxResults, keyPairs.size());
        String responseToken = end < keyPairs.size() ? encodeOffset(end) : null;
        return new KeyPairPage(keyPairs.subList(offset, end), responseToken);
    }

    public synchronized StreamKey createStreamKey(String region, JsonNode request) {
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, requireText(request, "channelArn"));
        if (channel.getStreamKeyArn() != null && !channel.getStreamKeyArn().isBlank()) {
            throw quotaExceeded("A stream key already exists for this channel.");
        }
        provisionStreamKey(channel, channelRegion(channel, region), readTags(request.get("tags")));
        store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
        return channel.getStreamKey();
    }

    public StreamKey getStreamKey(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        Channel channel = requireChannelByStreamKeyArn(region, arn);
        StreamKey key = channel.getStreamKey();
        if (key == null) {
            throw notFound(arn);
        }
        return key;
    }

    public synchronized void deleteStreamKey(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        Channel channel = requireChannelByStreamKeyArn(region, arn);
        channel.setStreamKeyArn(null);
        channel.setStreamKeyValue(null);
        channel.setStreamKeyTags(new LinkedHashMap<>());
        store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
    }

    public List<StreamKey> listStreamKeys(String region, JsonNode request) {
        requireObject(request, "Request body");
        parseMaxResults(request);
        Channel channel = requireChannel(region, requireText(request, "channelArn"));
        StreamKey key = channel.getStreamKey();
        return key == null ? List.of() : List.of(key);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (isRecordingArn(arn)) {
            return recordingConfigurations.listTags(region, arn);
        }
        if (isStreamKeyArn(arn)) {
            Channel channel = requireChannelByStreamKeyArn(region, arn);
            return channel.getStreamKeyTags() == null ? Map.of() : Map.copyOf(channel.getStreamKeyTags());
        }
        if (isKeyPairArn(arn)) {
            PlaybackKeyPair keyPair = requireKeyPair(region, arn);
            return keyPair.getTags() == null ? Map.of() : Map.copyOf(keyPair.getTags());
        }
        if (isPolicyArn(arn)) {
            PlaybackRestrictionPolicy policy = requirePolicy(region, arn);
            return policy.getTags() == null ? Map.of() : Map.copyOf(policy.getTags());
        }
        if (isStageArn(arn)) {
            return stages.listTags(region, arn);
        }
        Channel channel = requireChannel(region, arn);
        return channel.getTags() == null ? Map.of() : Map.copyOf(channel.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        if (isRecordingArn(arn)) {
            recordingConfigurations.tagResource(region, arn, tags);
            return;
        }
        if (isStreamKeyArn(arn)) {
            Channel channel = requireChannelByStreamKeyArn(region, arn);
            Map<String, String> current = channel.getStreamKeyTags() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(channel.getStreamKeyTags());
            if (tags != null) {
                current.putAll(tags);
            }
            channel.setStreamKeyTags(current);
            store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
            return;
        }
        if (isKeyPairArn(arn)) {
            PlaybackKeyPair keyPair = requireKeyPair(region, arn);
            Map<String, String> current = keyPair.getTags() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(keyPair.getTags());
            if (tags != null) {
                current.putAll(tags);
            }
            keyPair.setTags(current);
            keyPairStore.put(storageKey(resourceRegion(keyPair.getArn(), region), keyPair.getId()), keyPair);
            return;
        }
        if (isPolicyArn(arn)) {
            PlaybackRestrictionPolicy policy = requirePolicy(region, arn);
            Map<String, String> current = policy.getTags() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(policy.getTags());
            if (tags != null) {
                current.putAll(tags);
            }
            policy.setTags(current);
            policyStore.put(storageKey(resourceRegion(policy.getArn(), region), policy.getId()), policy);
            return;
        }
        if (isStageArn(arn)) {
            stages.tagResource(region, arn, tags);
            return;
        }
        Channel channel = requireChannel(region, arn);
        Map<String, String> current = channel.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(channel.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        channel.setTags(current);
        store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        if (isRecordingArn(arn)) {
            recordingConfigurations.untagResource(region, arn, tagKeys);
            return;
        }
        if (isStreamKeyArn(arn)) {
            Channel channel = requireChannelByStreamKeyArn(region, arn);
            if (channel.getStreamKeyTags() != null && tagKeys != null) {
                tagKeys.forEach(channel.getStreamKeyTags()::remove);
            }
            store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
            return;
        }
        if (isKeyPairArn(arn)) {
            PlaybackKeyPair keyPair = requireKeyPair(region, arn);
            if (keyPair.getTags() != null && tagKeys != null) {
                tagKeys.forEach(keyPair.getTags()::remove);
            }
            keyPairStore.put(storageKey(resourceRegion(keyPair.getArn(), region), keyPair.getId()), keyPair);
            return;
        }
        if (isPolicyArn(arn)) {
            PlaybackRestrictionPolicy policy = requirePolicy(region, arn);
            if (policy.getTags() != null && tagKeys != null) {
                tagKeys.forEach(policy.getTags()::remove);
            }
            policyStore.put(storageKey(resourceRegion(policy.getArn(), region), policy.getId()), policy);
            return;
        }
        if (isStageArn(arn)) {
            stages.untagResource(region, arn, tagKeys);
            return;
        }
        Channel channel = requireChannel(region, arn);
        if (channel.getTags() != null && tagKeys != null) {
            tagKeys.forEach(channel.getTags()::remove);
        }
        store.put(storageKey(channelRegion(channel, region), channel.getId()), channel);
    }

    private Channel requireChannel(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith("channel/")) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring("channel/".length());
        if (id.isBlank() || !CHANNEL_ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        String channelRegion = parsed.region() == null || parsed.region().isBlank()
                ? region
                : parsed.region();
        return store.get(storageKey(channelRegion, id)).orElseThrow(() -> notFound(arn));
    }

    private Channel requireChannelByStreamKeyArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith(STREAM_KEY_RESOURCE)) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring(STREAM_KEY_RESOURCE.length());
        if (id.isBlank() || !CHANNEL_ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        for (Channel channel : store.scan(key -> true)) {
            if (arn.equals(channel.getStreamKeyArn())) {
                return channel;
            }
        }
        throw notFound(arn);
    }

    private void provisionStreamKey(Channel channel, String region, Map<String, String> tags) {
        String account = regionResolver.getAccountId();
        String streamKeyId = newId();
        String streamKeyArn = AwsArnUtils.Arn.of(SERVICE, region, account, STREAM_KEY_RESOURCE + streamKeyId)
                .toString();
        channel.setStreamKeyArn(streamKeyArn);
        channel.setStreamKeyValue("sk_" + region + "_" + newId() + newId());
        channel.setStreamKeyTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
    }

    private boolean isRecordingArn(String arn) {
        return recordingConfigurations != null && recordingConfigurations.ownsArn(arn);
    }

    private boolean isStageArn(String arn) {
        return stages != null && stages.ownsArn(arn);
    }

    private static boolean isStreamKeyArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            return resource != null && resource.startsWith(STREAM_KEY_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private PlaybackRestrictionPolicy requirePolicy(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith(POLICY_RESOURCE)) {
            throw notFound(arn);
        }
        if (policyStore == null) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring(POLICY_RESOURCE.length());
        if (id.isBlank() || !CHANNEL_ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        String policyRegion = parsed.region() == null || parsed.region().isBlank()
                ? region
                : parsed.region();
        return policyStore.get(storageKey(policyRegion, id)).orElseThrow(() -> notFound(arn));
    }

    private PlaybackKeyPair requireKeyPair(String region, String arn) {
        requireKeyPairStore();
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith(KEY_PAIR_RESOURCE)) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring(KEY_PAIR_RESOURCE.length());
        if (id.isBlank() || !CHANNEL_ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        String keyPairRegion = parsed.region() == null || parsed.region().isBlank()
                ? region
                : parsed.region();
        return keyPairStore.get(storageKey(keyPairRegion, id)).orElseThrow(() -> notFound(arn));
    }

    private void requireKeyPairStore() {
        if (keyPairStore == null) {
            throw new AwsException("InternalServerException", "Playback key pair store is unavailable.", 500);
        }
    }

    private PlaybackKeyPair findKeyPairByName(String region, String name) {
        requireKeyPairStore();
        for (PlaybackKeyPair existing : keyPairStore.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(existing.getName())) {
                return existing;
            }
        }
        return null;
    }

    private static boolean isKeyPairArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            return resource != null && resource.startsWith(KEY_PAIR_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String fingerprintOf(String pem) {
        byte[] der = decodePemPublicKey(pem);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(der);
            return HexFormat.ofDelimiter(":").withLowerCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required", e);
        }
    }

    private static byte[] decodePemPublicKey(String pem) {
        String trimmed = pem.trim();
        if (!trimmed.contains("BEGIN PUBLIC KEY") || !trimmed.contains("END PUBLIC KEY")) {
            throw validation("publicKeyMaterial must be a PEM-encoded public key.");
        }
        String base64 = trimmed
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        if (base64.isEmpty()) {
            throw validation("publicKeyMaterial must be a PEM-encoded public key.");
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw validation("publicKeyMaterial must be a PEM-encoded public key.");
        }
    }

    private static boolean isPolicyArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            return resource != null && resource.startsWith(POLICY_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String resourceRegion(String arn, String fallback) {
        try {
            String region = AwsArnUtils.parse(arn).region();
            return region == null || region.isBlank() ? fallback : region;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String channelRegion(Channel channel, String fallback) {
        try {
            String region = AwsArnUtils.parse(channel.getArn()).region();
            return region == null || region.isBlank() ? fallback : region;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String newPassphrase() {
        UUID uuid = UUID.randomUUID();
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits(uuid.getMostSignificantBits())
                + hex.toHexDigits(uuid.getLeastSignificantBits()).substring(0, 16);
    }

    private static void validateName(String name) {
        if (name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 0-128 letters, digits, hyphens, or underscores.");
        }
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("maxResults") || request.get("maxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("maxResults");
        if (!value.isNumber() && !value.isTextual()) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
        int parsed;
        try {
            parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and 100.");
        }
        return parsed;
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean defaultValue) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        return requireBoolean(parent, field);
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static String optionalEnum(JsonNode parent, String field, Set<String> allowed, String defaultValue) {
        String value = optionalText(parent, field);
        if (value == null) {
            return defaultValue;
        }
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isNull()) {
                return;
            }
            if (!valueNode.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return new ArrayList<>();
        }
        JsonNode value = parent.get(field);
        if (!value.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw validation(field + " must be an array of strings.");
            }
            items.add(item.textValue());
        }
        return items;
    }

    private static AwsException notFound(String arn) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource: " + arn + " not found",
                404);
    }

    private static AwsException notBroadcasting() {
        return new AwsException(
                "ChannelNotBroadcasting",
                "the channel is not currently broadcasting",
                404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException quotaExceeded(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    public record BatchRevocationError(String channelArn, String viewerId, String code, String message) {
    }

    public record Page(List<Channel> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record PolicyPage(List<PlaybackRestrictionPolicy> items, String nextToken) {
        public PolicyPage {
            items = List.copyOf(items);
        }
    }

    public record KeyPairPage(List<PlaybackKeyPair> items, String nextToken) {
        public KeyPairPage {
            items = List.copyOf(items);
        }
    }
}
