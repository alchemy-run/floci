package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.ivs.IvsStore.conflict;
import static io.floci.aws.services.ivs.IvsStore.key;
import static io.floci.aws.services.ivs.IvsStore.meta;
import static io.floci.aws.services.ivs.IvsStore.notFound;
import static io.floci.aws.services.ivs.IvsStore.quota;
import static io.floci.aws.services.ivs.IvsStore.validation;

/**
 * Amazon IVS Real-Time Streaming's control plane: stages and their participant tokens, encoder, storage and ingest
 * configurations, public keys, compositions and participant replication, with the sessions, participants and
 * participant events {@link IvsRealtimeLive} records as participants publish. A stage's endpoints are Floci's: WHIP
 * at {@code <gateway>/_floci/ivsrealtime/whip}, RTMP at the IVS media server's RTMP port.
 */
@ApplicationScoped
public class IvsRealtimeService {

    static final Set<String> TAGGED = Set.of("stage", "composition", "encoder-configuration",
            "storage-configuration", "ingest-configuration", "public-key");
    private static final int MAX_PUBLIC_KEYS = 3;
    private static final int DEFAULT_TOKEN_MINUTES = 720;
    private static final int ATTRIBUTES_BYTES = 1024;

    private final IvsStore store;
    private final Instance<S3Service> s3;
    private final Instance<IvsRealtimeLive> live;

    @Inject
    public IvsRealtimeService(IvsStore store, Instance<S3Service> s3, Instance<IvsRealtimeLive> live) {
        this.store = store;
        this.s3 = s3;
        this.live = live;
    }

    private EmulatorConfig.IvsServiceConfig config() {
        return store.config().services().ivs();
    }

    private ObjectNode object() {
        return store.mapper().createObjectNode();
    }

    private ObjectNode wrap(String field, ObjectNode record) {
        ObjectNode response = object();
        response.set(field, store.view(record));
        return response;
    }

    private static String idOf(ObjectNode record) {
        return meta(record).path("id").asText();
    }

    // ---------------------------------------------------------------- endpoints

    private String host() {
        URI base = URI.create(store.config().effectiveBaseUrl());
        return base.getHost() == null ? "localhost" : base.getHost();
    }

    /** The WHIP endpoint of every stage: participant tokens name their stage. */
    String whipUrl() {
        return store.config().effectiveBaseUrl().replaceAll("/+$", "") + IvsRealtimeWhip.PATH;
    }

    private ObjectNode endpoints() {
        ObjectNode endpoints = object();
        endpoints.put("events", whipUrl().replaceFirst("^http", "ws").replace(IvsRealtimeWhip.PATH,
                "/_floci/ivsrealtime/events"));
        endpoints.put("whip", whipUrl());
        endpoints.put("rtmp", "rtmp://" + host() + ":" + config().rtmpPort() + "/app/");
        endpoints.put("rtmps", "rtmps://" + host() + ":" + config().rtmpsPort() + "/app/");
        return endpoints;
    }

    // ---------------------------------------------------------------- stages

    public synchronized ObjectNode createStage(JsonNode input, String region) {
        String id = IvsStore.id(12);
        ObjectNode stage = store.record(region, "stage", id, "arn");
        stage.put("name", input.path("name").asText(""));
        stage.set("tags", store.tags(input.path("tags"), null));
        stage.set("autoParticipantRecordingConfiguration",
                recordingConfiguration(input.get("autoParticipantRecordingConfiguration"), region));
        stage.set("endpoints", endpoints());
        store.put(key(region, "stage", id), stage);
        ArrayNode tokens = store.mapper().createArrayNode();
        for (JsonNode configuration : input.path("participantTokenConfigurations")) {
            tokens.add(issue(stage, configuration, region));
        }
        ObjectNode response = wrap("stage", stage);
        response.set("participantTokens", tokens);
        live.get().stageChanged(store.account(), region, id);
        return response;
    }

    /** A stage's participant recording configuration, its defaults filled in; empty when recording is off. */
    private ObjectNode recordingConfiguration(JsonNode given, String region) {
        ObjectNode configuration = object();
        String storage = given == null ? "" : given.path("storageConfigurationArn").asText("");
        configuration.put("storageConfigurationArn", storage);
        if (!storage.isEmpty()) {
            store.require(storage, "storage-configuration", region);
        }
        ArrayNode mediaTypes = configuration.putArray("mediaTypes");
        if (given != null && given.path("mediaTypes").size() > 0) {
            given.path("mediaTypes").forEach(mediaTypes::add);
        } else {
            mediaTypes.add("AUDIO_VIDEO");
        }
        ObjectNode thumbnails = configuration.putObject("thumbnailConfiguration");
        JsonNode thumbnailsGiven = given == null ? null : given.get("thumbnailConfiguration");
        thumbnails.put("targetIntervalSeconds", thumbnailsGiven == null ? 60
                : thumbnailsGiven.path("targetIntervalSeconds").asInt(60));
        ArrayNode storageTypes = thumbnails.putArray("storage");
        if (thumbnailsGiven != null && thumbnailsGiven.has("storage")) {
            thumbnailsGiven.path("storage").forEach(storageTypes::add);
        } else {
            storageTypes.add("SEQUENTIAL");
        }
        thumbnails.put("recordingMode", thumbnailsGiven == null ? "INTERVAL"
                : thumbnailsGiven.path("recordingMode").asText("INTERVAL"));
        configuration.put("recordingReconnectWindowSeconds",
                given == null ? 0 : given.path("recordingReconnectWindowSeconds").asInt(0));
        configuration.putObject("hlsConfiguration").put("targetSegmentDurationSeconds", given == null ? 6
                : given.path("hlsConfiguration").path("targetSegmentDurationSeconds").asInt(6));
        configuration.put("recordParticipantReplicas",
                given == null || given.path("recordParticipantReplicas").asBoolean(true));
        return configuration;
    }

    public synchronized ObjectNode getStage(JsonNode input, String region) {
        return wrap("stage", store.require(input.path("arn").asText(null), "stage", region));
    }

    public synchronized ObjectNode updateStage(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("arn").asText(null), "stage", region);
        if (input.has("name")) {
            stage.put("name", input.path("name").asText(""));
        }
        if (input.has("autoParticipantRecordingConfiguration")) {
            stage.set("autoParticipantRecordingConfiguration",
                    recordingConfiguration(input.get("autoParticipantRecordingConfiguration"), region));
        }
        store.put(key(region, "stage", idOf(stage)), stage);
        return wrap("stage", stage);
    }

    public synchronized ObjectNode deleteStage(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("arn").asText(null), "stage", region);
        String id = idOf(stage);
        store.delete(store.account(), key(region, "stage", id));
        live.get().stageDeleted(store.account(), region, id, stage.path("arn").asText());
        for (ObjectNode ingest : store.list(key(region, "ingest-configuration", ""))) {
            if (stage.path("arn").asText().equals(ingest.path("stageArn").asText())) {
                ingest.put("stageArn", "");
                store.put(key(region, "ingest-configuration", idOf(ingest)), ingest);
            }
        }
        return object();
    }

    public synchronized ObjectNode listStages(JsonNode input, String region) {
        List<ObjectNode> stages = new ArrayList<>();
        for (ObjectNode stage : store.list(key(region, "stage", ""))) {
            ObjectNode summary = object();
            summary.set("arn", stage.get("arn"));
            summary.set("name", stage.get("name"));
            if (stage.hasNonNull("activeSessionId")) {
                summary.set("activeSessionId", stage.get("activeSessionId"));
            }
            summary.set("tags", stage.get("tags"));
            stages.add(summary);
        }
        return store.page(stages, input, "stages", 100);
    }

    // ---------------------------------------------------------------- participant tokens

    public synchronized ObjectNode createParticipantToken(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("stageArn").asText(null), "stage", region);
        ObjectNode response = object();
        response.set("participantToken", issue(stage, input, region));
        return response;
    }

    /** Issues a participant token for a stage, keeping its grant so the participant can be told apart later. */
    private ObjectNode issue(ObjectNode stage, JsonNode configuration, String region) {
        int duration = configuration.path("duration").asInt(DEFAULT_TOKEN_MINUTES);
        ObjectNode attributes = stringMap(configuration.path("attributes"));
        if (bytes(attributes) > ATTRIBUTES_BYTES) {
            throw validation("attributes are at most 1 KB.");
        }
        ArrayNode capabilities = store.mapper().createArrayNode();
        if (configuration.has("capabilities")) {
            configuration.path("capabilities").forEach(capabilities::add);
        } else {
            capabilities.add("PUBLISH").add("SUBSCRIBE");
        }
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plus(duration, ChronoUnit.MINUTES);
        String participantId = IvsStore.id(12);
        String userId = configuration.path("userId").asText("");
        ObjectNode payload = object();
        payload.put("exp", expires.getEpochSecond()).put("iat", issued.getEpochSecond()).put("jti", participantId);
        if (!userId.isEmpty()) {
            payload.put("user_id", userId);
        }
        payload.put("resource", stage.path("arn").asText()).put("topic", idOf(stage))
                .put("events_url", stage.path("endpoints").path("events").asText())
                .put("whip_url", stage.path("endpoints").path("whip").asText());
        ObjectNode allowed = payload.putObject("capabilities");
        capabilities.forEach(capability -> allowed.put("allow_" + capability.asText().toLowerCase(), true));
        payload.set("attributes", attributes);
        payload.put("version", "1.0");
        String token = IvsRealtimeTokens.sign(store.mapper(), IvsRealtimeTokens.ISSUER_KID, payload,
                signingKey(store.account(), region)[0]);
        ObjectNode participantToken = object();
        participantToken.put("participantId", participantId).put("token", token).put("userId", userId);
        participantToken.set("attributes", attributes);
        participantToken.put("duration", duration);
        participantToken.set("capabilities", capabilities);
        participantToken.put("expirationTime", expires.toString());
        return participantToken;
    }

    /** The key the account and Region's participant tokens are signed with, made when first needed. */
    synchronized String[] signingKey(String account, String region) {
        String keyName = key(region, "realtime-signing-key", "issuer");
        ObjectNode signing = store.get(account, keyName).orElse(null);
        if (signing == null) {
            String[] pair = IvsRealtimeTokens.newKeyPair();
            signing = object().put("private", pair[0]).put("public", pair[1]);
            signing.putObject("_").put("kind", "realtime-signing-key").put("seq", System.nanoTime());
            store.put(account, keyName, signing);
        }
        return new String[] {signing.path("private").asText(), signing.path("public").asText()};
    }

    /**
     * A participant token presented to join a stage, verified: the stage it names, its participant, user, capabilities
     * and attributes. Throws ValidationException naming why it is refused.
     */
    synchronized Grant verify(String token, String region) {
        JsonNode[] parts = IvsRealtimeTokens.parse(store.mapper(), token);
        if (parts == null || !"ES384".equals(parts[0].path("alg").asText())) {
            throw validation("The participant token is not a valid JWT.");
        }
        JsonNode payload = parts[1];
        String stageArn = payload.path("resource").asText("");
        if (!AwsArnUtils.isArnFor(stageArn, "ivs")) {
            throw validation("The participant token names no stage.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(stageArn);
        String account = arn.accountId();
        if (region != null && !region.equals(arn.region())) {
            throw validation("The participant token is for another Region.");
        }
        String kid = parts[0].path("kid").asText("");
        String publicKey;
        if (IvsRealtimeTokens.ISSUER_KID.equals(kid)) {
            publicKey = signingKey(account, arn.region())[1];
        } else {
            String[] key = IvsStore.parse(kid, arn.region(), account);
            ObjectNode imported = key == null || !"public-key".equals(key[0]) ? null
                    : store.get(account, key(arn.region(), "public-key", key[1])).orElse(null);
            if (imported == null) {
                throw validation("The participant token's signing key is unknown.");
            }
            publicKey = meta(imported).path("der").asText();
        }
        if (!IvsRealtimeTokens.verifies(token, publicKey)) {
            throw validation("The participant token's signature does not verify.");
        }
        if (payload.path("exp").asLong() < Instant.now().getEpochSecond()) {
            throw validation("The participant token has expired.");
        }
        if (!"1.0".equals(payload.path("version").asText())) {
            throw validation("The participant token's version is not 1.0.");
        }
        String participantId = payload.path("jti").asText("");
        if (!participantId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw validation("The participant token's jti is not a participant ID.");
        }
        String stageId = arn.resource().substring(arn.resource().indexOf('/') + 1);
        if (store.get(account, key(arn.region(), "stage", stageId)).isEmpty()) {
            throw notFound("Stage " + stageArn + " not found.");
        }
        if (store.get(account, key(arn.region(), "revoked-participant", stageId + "/" + participantId))
                .isPresent()) {
            throw validation("The participant was disconnected from the stage and cannot rejoin.");
        }
        boolean publish = payload.path("capabilities").path("allow_publish").asBoolean(false);
        boolean subscribe = payload.path("capabilities").path("allow_subscribe").asBoolean(false);
        return new Grant(account, arn.region(), stageId, participantId, payload.path("user_id").asText(""),
                (ObjectNode) (payload.path("attributes").isObject() ? payload.get("attributes") : object()),
                publish, subscribe);
    }

    /** A verified participant token. */
    record Grant(String account, String region, String stageId, String participantId, String userId,
                 ObjectNode attributes, boolean publish, boolean subscribe) {
    }

    public synchronized ObjectNode disconnectParticipant(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("stageArn").asText(null), "stage", region);
        String participantId = input.path("participantId").asText("");
        boolean disconnected = live.get().disconnect(store.account(), region, idOf(stage), participantId,
                input.path("reason").asText(""));
        boolean known = disconnected || !participants(region, idOf(stage), participantId).isEmpty();
        if (!known) {
            throw notFound("Participant " + participantId + " not found in stage " + stage.path("arn").asText()
                    + ".");
        }
        ObjectNode revoked = object().put("participantId", participantId);
        revoked.putObject("_").put("kind", "revoked-participant").put("seq", System.nanoTime());
        store.put(key(region, "revoked-participant", idOf(stage) + "/" + participantId), revoked);
        return object();
    }

    public synchronized ObjectNode revokeConnectionToken(JsonNode input, String region) {
        String tokenId = input.path("tokenId").asText("");
        ObjectNode revoked = object().put("tokenId", tokenId).put("revokedAt", IvsStore.now());
        revoked.putObject("_").put("kind", "revoked-connection-token").put("seq", System.nanoTime());
        store.put(key(region, "revoked-connection-token", tokenId), revoked);
        return object();
    }

    // ---------------------------------------------------------------- sessions and participants

    private List<ObjectNode> participants(String region, String stageId, String participantId) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode participant : store.list(key(region, "participant", stageId + "/"))) {
            if (participantId.equals(participant.path("participantId").asText())) {
                found.add(participant);
            }
        }
        return found;
    }

    private ObjectNode session(JsonNode input, String region, String stageField) {
        ObjectNode stage = store.require(input.path(stageField).asText(null), "stage", region);
        String sessionId = input.path("sessionId").asText("");
        return store.get(key(region, "stage-session", idOf(stage) + "/" + sessionId)).orElseThrow(() ->
                notFound("Stage session " + sessionId + " not found."));
    }

    public synchronized ObjectNode getStageSession(JsonNode input, String region) {
        return wrap("stageSession", session(input, region, "stageArn"));
    }

    public synchronized ObjectNode listStageSessions(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("stageArn").asText(null), "stage", region);
        List<ObjectNode> sessions = new ArrayList<>();
        store.list(key(region, "stage-session", idOf(stage) + "/")).forEach(s -> sessions.add(0, store.view(s)));
        return store.page(sessions, input, "stageSessions", 100);
    }

    public synchronized ObjectNode getParticipant(JsonNode input, String region) {
        ObjectNode session = session(input, region, "stageArn");
        String stageId = idOf(store.require(input.path("stageArn").asText(null), "stage", region));
        String participantId = input.path("participantId").asText("");
        ObjectNode participant = store.get(key(region, "participant", stageId + "/"
                + session.path("sessionId").asText() + "/" + participantId)).orElseThrow(() ->
                notFound("Participant " + participantId + " not found."));
        return wrap("participant", participant);
    }

    public synchronized ObjectNode listParticipants(JsonNode input, String region) {
        ObjectNode session = session(input, region, "stageArn");
        String stageId = idOf(store.require(input.path("stageArn").asText(null), "stage", region));
        int filters = 0;
        for (String filter : List.of("filterByUserId", "filterByPublished", "filterByState",
                "filterByRecordingState")) {
            filters += input.hasNonNull(filter) ? 1 : 0;
        }
        if (filters > 1) {
            throw validation("Only one of filterByUserId, filterByPublished, filterByState and filterByRecordingState "
                    + "can be given.");
        }
        List<ObjectNode> participants = new ArrayList<>();
        for (ObjectNode participant : store.list(key(region, "participant", stageId + "/"
                + session.path("sessionId").asText() + "/"))) {
            if (input.hasNonNull("filterByUserId")
                    && !input.get("filterByUserId").asText().equals(participant.path("userId").asText())
                    || input.hasNonNull("filterByPublished")
                    && input.get("filterByPublished").asBoolean() != participant.path("published").asBoolean()
                    || input.hasNonNull("filterByState")
                    && !input.get("filterByState").asText().equals(participant.path("state").asText())
                    || input.hasNonNull("filterByRecordingState")
                    && !input.get("filterByRecordingState").asText().equals(
                            participant.path("recordingState").asText())) {
                continue;
            }
            ObjectNode summary = object();
            for (String field : List.of("participantId", "userId", "state", "firstJoinTime", "published",
                    "recordingState", "replicationType", "replicationState", "sourceStageArn", "sourceSessionId",
                    "redundantIngest", "ingestConfigurationArn")) {
                if (participant.has(field)) {
                    summary.set(field, participant.get(field));
                }
            }
            participants.add(summary);
        }
        return store.page(participants, input, "participants", 100);
    }

    public synchronized ObjectNode listParticipantEvents(JsonNode input, String region) {
        ObjectNode session = session(input, region, "stageArn");
        String stageId = idOf(store.require(input.path("stageArn").asText(null), "stage", region));
        String participantId = input.path("participantId").asText("");
        ObjectNode participant = store.get(key(region, "participant", stageId + "/"
                + session.path("sessionId").asText() + "/" + participantId)).orElse(null);
        List<ObjectNode> events = new ArrayList<>();
        if (participant != null) {
            meta(participant).path("events").forEach(event -> events.add((ObjectNode) event));
        }
        return store.page(events, input, "events", 100);
    }

    public synchronized ObjectNode listParticipantReplicas(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("sourceStageArn").asText(null), "stage", region);
        String participantId = input.path("participantId").asText("");
        List<ObjectNode> replicas = new ArrayList<>();
        store.list(key(region, "replica", idOf(stage) + "/" + participantId + "/")).forEach(replica ->
                replicas.add(store.view(replica)));
        return store.page(replicas, input, "replicas", 50);
    }

    public synchronized ObjectNode startParticipantReplication(JsonNode input, String region) {
        ObjectNode source = store.require(input.path("sourceStageArn").asText(null), "stage", region);
        ObjectNode destination = store.require(input.path("destinationStageArn").asText(null), "stage", region);
        if (idOf(source).equals(idOf(destination))) {
            throw validation("The source and destination stages must differ.");
        }
        String participantId = input.path("participantId").asText("");
        String replicaKey = key(region, "replica", idOf(source) + "/" + participantId + "/" + idOf(destination));
        ObjectNode existing = store.get(replicaKey).orElse(null);
        if (existing != null && "ACTIVE".equals(existing.path("replicationState").asText())) {
            throw conflict("Participant " + participantId + " is already replicated to "
                    + destination.path("arn").asText() + ".");
        }
        IvsRealtimeLive.Publisher publisher = live.get().publisher(store.account(), region, idOf(source),
                participantId);
        if (publisher == null) {
            throw notFound("Participant " + participantId + " is not publishing to stage "
                    + source.path("arn").asText() + ".");
        }
        live.get().replicate(publisher, destination, input.path("reconnectWindowSeconds").asInt(0),
                stringMap(input.path("attributes")));
        return object();
    }

    public synchronized ObjectNode stopParticipantReplication(JsonNode input, String region) {
        ObjectNode source = store.require(input.path("sourceStageArn").asText(null), "stage", region);
        ObjectNode destination = store.require(input.path("destinationStageArn").asText(null), "stage", region);
        String participantId = input.path("participantId").asText("");
        String replicaKey = key(region, "replica", idOf(source) + "/" + participantId + "/" + idOf(destination));
        ObjectNode replica = store.get(replicaKey).orElse(null);
        if (replica == null || !"ACTIVE".equals(replica.path("replicationState").asText())) {
            throw notFound("Participant " + participantId + " is not replicated to "
                    + destination.path("arn").asText() + ".");
        }
        live.get().stopReplication(store.account(), region, idOf(source), participantId, idOf(destination));
        return object();
    }

    // ---------------------------------------------------------------- encoder configurations

    public synchronized ObjectNode createEncoderConfiguration(JsonNode input, String region) {
        String id = IvsStore.id(12);
        ObjectNode configuration = store.record(region, "encoder-configuration", id, "arn");
        configuration.put("name", input.path("name").asText(""));
        JsonNode video = input.path("video");
        configuration.putObject("video").put("width", video.path("width").asInt(1280))
                .put("height", video.path("height").asInt(720))
                .put("framerate", video.path("framerate").floatValue() == 0 ? 30f : video.path("framerate").floatValue())
                .put("bitrate", video.path("bitrate").asInt(2500000));
        configuration.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "encoder-configuration", id), configuration);
        return wrap("encoderConfiguration", configuration);
    }

    public synchronized ObjectNode getEncoderConfiguration(JsonNode input, String region) {
        return wrap("encoderConfiguration", store.require(input.path("arn").asText(null), "encoder-configuration",
                region));
    }

    public synchronized ObjectNode listEncoderConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "encoder-configuration", "")).forEach(c -> items.add(summary(c, "arn", "name",
                "tags")));
        return store.page(items, input, "encoderConfigurations", 100);
    }

    public synchronized ObjectNode deleteEncoderConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "encoder-configuration", region);
        String arn = configuration.path("arn").asText();
        for (ObjectNode composition : activeCompositions(region)) {
            if (composition.toString().contains(arn)) {
                throw conflict("Encoder configuration " + arn + " is used by composition "
                        + composition.path("arn").asText() + ".");
            }
        }
        store.delete(store.account(), key(region, "encoder-configuration", idOf(configuration)));
        return object();
    }

    // ---------------------------------------------------------------- storage configurations

    public synchronized ObjectNode createStorageConfiguration(JsonNode input, String region) {
        String bucket = input.path("s3").path("bucketName").asText("");
        if (!s3.get().bucketExists(bucket)) {
            throw notFound("S3 bucket " + bucket + " not found.");
        }
        String id = IvsStore.id(12);
        ObjectNode configuration = store.record(region, "storage-configuration", id, "arn");
        configuration.put("name", input.path("name").asText(""));
        configuration.putObject("s3").put("bucketName", bucket);
        configuration.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "storage-configuration", id), configuration);
        return wrap("storageConfiguration", configuration);
    }

    public synchronized ObjectNode getStorageConfiguration(JsonNode input, String region) {
        return wrap("storageConfiguration", store.require(input.path("arn").asText(null), "storage-configuration",
                region));
    }

    public synchronized ObjectNode listStorageConfigurations(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "storage-configuration", "")).forEach(c -> items.add(summary(c, "arn", "name", "s3",
                "tags")));
        return store.page(items, input, "storageConfigurations", 100);
    }

    public synchronized ObjectNode deleteStorageConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "storage-configuration", region);
        String arn = configuration.path("arn").asText();
        for (ObjectNode stage : store.list(key(region, "stage", ""))) {
            if (arn.equals(stage.path("autoParticipantRecordingConfiguration").path("storageConfigurationArn")
                    .asText())) {
                throw conflict("Storage configuration " + arn + " records stage " + stage.path("arn").asText() + ".");
            }
        }
        for (ObjectNode composition : activeCompositions(region)) {
            if (composition.toString().contains(arn)) {
                throw conflict("Storage configuration " + arn + " is used by composition "
                        + composition.path("arn").asText() + ".");
            }
        }
        store.delete(store.account(), key(region, "storage-configuration", idOf(configuration)));
        return object();
    }

    // ---------------------------------------------------------------- ingest configurations

    public synchronized ObjectNode createIngestConfiguration(JsonNode input, String region) {
        String protocol = input.path("ingestProtocol").asText("");
        boolean insecure = input.path("insecureIngest").asBoolean(false);
        if ("RTMP".equals(protocol) && !insecure) {
            throw validation("RTMP ingest needs insecureIngest; use RTMPS otherwise.");
        }
        String stageArn = input.path("stageArn").asText("");
        if (!stageArn.isEmpty()) {
            store.require(stageArn, "stage", region);
        }
        String id = IvsStore.id(12);
        ObjectNode configuration = store.record(region, "ingest-configuration", id, "arn");
        configuration.put("name", input.path("name").asText("")).put("ingestProtocol", protocol)
                .put("stageArn", stageArn).put("participantId", IvsStore.id(12)).put("state", "INACTIVE")
                .put("userId", input.path("userId").asText(""))
                .put("insecureIngest", insecure);
        configuration.put("streamKey", streamKey(region, id));
        boolean redundant = input.path("redundantIngest").asBoolean(false);
        configuration.put("redundantIngest", redundant);
        if (redundant) {
            ArrayNode credentials = configuration.putArray("redundantIngestCredentials");
            for (int i = 0; i < 2; i++) {
                credentials.addObject().put("participantId", IvsStore.id(12)).put("streamKey", streamKey(region, id));
            }
        }
        configuration.set("attributes", stringMap(input.path("attributes")));
        configuration.set("tags", store.tags(input.path("tags"), null));
        store.put(key(region, "ingest-configuration", id), configuration);
        live.get().ingestChanged(store.account(), region, configuration);
        return wrap("ingestConfiguration", configuration);
    }

    private String streamKey(String region, String id) {
        return "rt_" + store.account() + "_" + region + "_" + id + "_" + IvsStore.id(24);
    }

    public synchronized ObjectNode getIngestConfiguration(JsonNode input, String region) {
        return wrap("ingestConfiguration", store.require(input.path("arn").asText(null), "ingest-configuration",
                region));
    }

    public synchronized ObjectNode updateIngestConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "ingest-configuration", region);
        if ("ACTIVE".equals(configuration.path("state").asText())) {
            throw conflict("Ingest configuration " + configuration.path("arn").asText() + " is publishing.");
        }
        if (input.has("stageArn")) {
            String stageArn = input.path("stageArn").asText("");
            if (!stageArn.isEmpty()) {
                store.require(stageArn, "stage", region);
            }
            configuration.put("stageArn", stageArn);
        }
        if (input.has("redundantIngest")) {
            boolean redundant = input.path("redundantIngest").asBoolean();
            configuration.put("redundantIngest", redundant);
            if (redundant && !configuration.has("redundantIngestCredentials")) {
                ArrayNode credentials = configuration.putArray("redundantIngestCredentials");
                for (int i = 0; i < 2; i++) {
                    credentials.addObject().put("participantId", IvsStore.id(12))
                            .put("streamKey", streamKey(region, idOf(configuration)));
                }
            } else if (!redundant) {
                configuration.remove("redundantIngestCredentials");
            }
        }
        store.put(key(region, "ingest-configuration", idOf(configuration)), configuration);
        live.get().ingestChanged(store.account(), region, configuration);
        return wrap("ingestConfiguration", configuration);
    }

    public synchronized ObjectNode deleteIngestConfiguration(JsonNode input, String region) {
        ObjectNode configuration = store.require(input.path("arn").asText(null), "ingest-configuration", region);
        if ("ACTIVE".equals(configuration.path("state").asText()) && !input.path("force").asBoolean(false)) {
            throw conflict("Ingest configuration " + configuration.path("arn").asText()
                    + " is publishing; delete it with force to disconnect it.");
        }
        store.delete(store.account(), key(region, "ingest-configuration", idOf(configuration)));
        live.get().ingestDeleted(store.account(), region, configuration);
        return object();
    }

    public synchronized ObjectNode listIngestConfigurations(JsonNode input, String region) {
        String stageArn = input.path("filterByStageArn").asText(null);
        String state = input.path("filterByState").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : store.list(key(region, "ingest-configuration", ""))) {
            if (stageArn != null && !stageArn.equals(configuration.path("stageArn").asText())
                    || state != null && !state.equals(configuration.path("state").asText())) {
                continue;
            }
            items.add(summary(configuration, "name", "arn", "ingestProtocol", "stageArn", "participantId", "state",
                    "userId", "redundantIngest"));
        }
        return store.page(items, input, "ingestConfigurations", 100);
    }

    // ---------------------------------------------------------------- public keys

    public synchronized ObjectNode importPublicKey(JsonNode input, String region) {
        String material = input.path("publicKeyMaterial").asText("");
        byte[] der = IvsService.publicKey(material);
        String fingerprint = IvsService.fingerprint(der);
        List<ObjectNode> existing = store.list(key(region, "public-key", ""));
        for (ObjectNode publicKey : existing) {
            if (fingerprint.equals(publicKey.path("fingerprint").asText())) {
                throw conflict("A public key with this material already exists.");
            }
        }
        if (existing.size() >= MAX_PUBLIC_KEYS) {
            throw quota("The account has reached its limit of " + MAX_PUBLIC_KEYS + " public keys.");
        }
        String id = IvsStore.id(12);
        ObjectNode publicKey = store.record(region, "public-key", id, "arn");
        publicKey.put("name", input.path("name").asText("")).put("publicKeyMaterial", material)
                .put("fingerprint", fingerprint);
        publicKey.set("tags", store.tags(input.path("tags"), null));
        meta(publicKey).put("der", Base64.getEncoder().encodeToString(der));
        store.put(key(region, "public-key", id), publicKey);
        return wrap("publicKey", publicKey);
    }

    public synchronized ObjectNode getPublicKey(JsonNode input, String region) {
        return wrap("publicKey", store.require(input.path("arn").asText(null), "public-key", region));
    }

    public synchronized ObjectNode listPublicKeys(JsonNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(key(region, "public-key", "")).forEach(k -> items.add(summary(k, "arn", "name", "tags")));
        return store.page(items, input, "publicKeys", 100);
    }

    public synchronized ObjectNode deletePublicKey(JsonNode input, String region) {
        ObjectNode publicKey = store.require(input.path("arn").asText(null), "public-key", region);
        store.delete(store.account(), key(region, "public-key", idOf(publicKey)));
        return object();
    }

    // ---------------------------------------------------------------- compositions

    private List<ObjectNode> activeCompositions(String region) {
        List<ObjectNode> active = new ArrayList<>();
        for (ObjectNode composition : store.list(key(region, "composition", ""))) {
            String state = composition.path("state").asText();
            if (!"STOPPED".equals(state) && !"FAILED".equals(state)) {
                active.add(composition);
            }
        }
        return active;
    }

    public synchronized ObjectNode startComposition(JsonNode input, String region) {
        ObjectNode stage = store.require(input.path("stageArn").asText(null), "stage", region);
        String idempotency = input.path("idempotencyToken").asText("");
        if (!idempotency.isEmpty()) {
            for (ObjectNode composition : store.list(key(region, "composition", ""))) {
                if (idempotency.equals(meta(composition).path("idempotencyToken").asText())) {
                    return wrap("composition", composition);
                }
            }
        }
        String id = IvsStore.id(12);
        ObjectNode composition = store.record(region, "composition", id, "arn");
        composition.put("stageArn", stage.path("arn").asText()).put("state", "STARTING");
        composition.set("layout", layout(input.path("layout")));
        ArrayNode destinations = composition.putArray("destinations");
        for (JsonNode given : input.path("destinations")) {
            destinations.add(destination(given, idOf(stage), id, region));
        }
        composition.set("tags", store.tags(input.path("tags"), null));
        composition.put("startTime", IvsStore.now());
        if (!idempotency.isEmpty()) {
            meta(composition).put("idempotencyToken", idempotency);
        }
        store.put(key(region, "composition", id), composition);
        live.get().compositionStarted(store.account(), region, id);
        return wrap("composition", composition);
    }

    private ObjectNode layout(JsonNode given) {
        ObjectNode layout = object();
        if (given.has("pip")) {
            JsonNode pip = given.path("pip");
            layout.putObject("pip").put("featuredParticipantAttribute", pip.path("featuredParticipantAttribute")
                            .asText(""))
                    .put("omitStoppedVideo", pip.path("omitStoppedVideo").asBoolean(false))
                    .put("videoFillMode", pip.path("videoFillMode").asText("COVER"))
                    .put("gridGap", pip.path("gridGap").asInt(0))
                    .put("pipParticipantAttribute", pip.path("pipParticipantAttribute").asText(""))
                    .put("pipBehavior", pip.path("pipBehavior").asText("STATIC"))
                    .put("pipOffset", pip.path("pipOffset").asInt(0))
                    .put("pipPosition", pip.path("pipPosition").asText("BOTTOM_RIGHT"))
                    .put("participantOrderAttribute", pip.path("participantOrderAttribute").asText(""));
            if (pip.has("pipWidth")) {
                layout.withObject("/pip").put("pipWidth", pip.path("pipWidth").asInt());
            }
            if (pip.has("pipHeight")) {
                layout.withObject("/pip").put("pipHeight", pip.path("pipHeight").asInt());
            }
        } else {
            JsonNode grid = given.path("grid");
            layout.putObject("grid").put("featuredParticipantAttribute", grid.path("featuredParticipantAttribute")
                            .asText(""))
                    .put("omitStoppedVideo", grid.path("omitStoppedVideo").asBoolean(false))
                    .put("videoAspectRatio", grid.path("videoAspectRatio").asText("VIDEO"))
                    .put("videoFillMode", grid.path("videoFillMode").asText(""))
                    .put("gridGap", grid.path("gridGap").asInt(2))
                    .put("participantOrderAttribute", grid.path("participantOrderAttribute").asText(""));
        }
        return layout;
    }

    private ObjectNode destination(JsonNode given, String stageId, String compositionId, String region) {
        String destinationId = IvsStore.id(12);
        ObjectNode destination = object();
        destination.put("id", destinationId).put("state", "STARTING");
        ObjectNode configuration = destination.putObject("configuration");
        configuration.put("name", given.path("name").asText(""));
        if (given.has("channel") == given.has("s3")) {
            throw validation("A destination is exactly one of a channel or an S3 destination.");
        }
        if (given.has("channel")) {
            JsonNode channel = given.path("channel");
            ObjectNode resolved = store.require(channel.path("channelArn").asText(null), "channel", region);
            ObjectNode channelConfiguration = configuration.putObject("channel");
            channelConfiguration.set("channelArn", resolved.get("arn"));
            if (channel.hasNonNull("encoderConfigurationArn")) {
                store.require(channel.get("encoderConfigurationArn").asText(), "encoder-configuration", region);
                channelConfiguration.set("encoderConfigurationArn", channel.get("encoderConfigurationArn"));
            }
        } else {
            JsonNode s3Destination = given.path("s3");
            store.require(s3Destination.path("storageConfigurationArn").asText(null), "storage-configuration",
                    region);
            ObjectNode s3Configuration = configuration.putObject("s3");
            s3Configuration.set("storageConfigurationArn", s3Destination.get("storageConfigurationArn"));
            ArrayNode encoders = s3Configuration.putArray("encoderConfigurationArns");
            for (JsonNode encoder : s3Destination.path("encoderConfigurationArns")) {
                store.require(encoder.asText(), "encoder-configuration", region);
                encoders.add(encoder);
            }
            ObjectNode recording = s3Configuration.putObject("recordingConfiguration");
            recording.put("format", "HLS");
            recording.putObject("hlsConfiguration").put("targetSegmentDurationSeconds",
                    s3Destination.path("recordingConfiguration").path("hlsConfiguration")
                            .path("targetSegmentDurationSeconds").asInt(2));
            ArrayNode thumbnails = s3Configuration.putArray("thumbnailConfigurations");
            for (JsonNode thumbnail : s3Destination.path("thumbnailConfigurations")) {
                ObjectNode resolved = thumbnails.addObject();
                resolved.put("targetIntervalSeconds", thumbnail.path("targetIntervalSeconds").asInt(60));
                ArrayNode storage = resolved.putArray("storage");
                if (thumbnail.has("storage")) {
                    thumbnail.path("storage").forEach(storage::add);
                } else {
                    storage.add("SEQUENTIAL");
                }
            }
            destination.putObject("detail").putObject("s3").put("recordingPrefix", stageId + "/" + compositionId
                    + "/" + destinationId + "/composite");
        }
        return destination;
    }

    public synchronized ObjectNode getComposition(JsonNode input, String region) {
        return wrap("composition", store.require(input.path("arn").asText(null), "composition", region));
    }

    public synchronized ObjectNode listCompositions(JsonNode input, String region) {
        String stageArn = input.path("filterByStageArn").asText(null);
        String encoder = input.path("filterByEncoderConfigurationArn").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode composition : store.list(key(region, "composition", ""))) {
            if (stageArn != null && !stageArn.equals(composition.path("stageArn").asText())
                    || encoder != null && !composition.path("destinations").toString().contains(encoder)) {
                continue;
            }
            ObjectNode summary = summary(composition, "arn", "stageArn", "state", "tags", "startTime", "endTime");
            ArrayNode destinations = summary.putArray("destinations");
            composition.path("destinations").forEach(destination -> {
                ObjectNode brief = destinations.addObject();
                for (String field : List.of("id", "state", "startTime", "endTime")) {
                    if (destination.has(field)) {
                        brief.set(field, destination.get(field));
                    }
                }
            });
            items.add(summary);
        }
        return store.page(items, input, "compositions", 100);
    }

    public synchronized ObjectNode stopComposition(JsonNode input, String region) {
        ObjectNode composition = store.require(input.path("arn").asText(null), "composition", region);
        String state = composition.path("state").asText();
        if (!"STOPPED".equals(state) && !"FAILED".equals(state) && !"STOPPING".equals(state)) {
            composition.put("state", "STOPPING");
            store.put(key(region, "composition", idOf(composition)), composition);
            live.get().compositionStopped(store.account(), region, idOf(composition));
        }
        return object();
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String arn, String region) {
        String[] parts = IvsStore.parse(arn, region, store.account());
        if (parts == null || !TAGGED.contains(parts[0])) {
            throw notFound("Resource " + arn + " not found.");
        }
        return store.require(arn, parts[0], region);
    }

    public synchronized ObjectNode listTagsForResource(String arn, String region) {
        ObjectNode response = object();
        response.set("tags", tagged(arn, region).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(String arn, JsonNode input, String region) {
        ObjectNode record = tagged(arn, region);
        record.set("tags", store.tags(input.path("tags"), (ObjectNode) record.path("tags")));
        store.put(key(region, meta(record).path("kind").asText(), idOf(record)), record);
        return object();
    }

    public synchronized ObjectNode untagResource(String arn, List<String> keys, String region) {
        ObjectNode record = tagged(arn, region);
        ObjectNode tags = (ObjectNode) record.path("tags");
        if (keys != null) {
            keys.forEach(tags::remove);
        }
        store.put(key(region, meta(record).path("kind").asText(), idOf(record)), record);
        return object();
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode summary(ObjectNode record, String... fields) {
        ObjectNode summary = object();
        for (String field : fields) {
            if (record.has(field)) {
                summary.set(field, record.get(field));
            }
        }
        return summary;
    }

    private ObjectNode stringMap(JsonNode given) {
        ObjectNode map = object();
        if (given != null && given.isObject()) {
            given.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    private static int bytes(ObjectNode map) {
        int total = 0;
        for (Map.Entry<String, JsonNode> entry : (Iterable<Map.Entry<String, JsonNode>>) map::fields) {
            total += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().asText().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }
}
