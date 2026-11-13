package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.ivs.IvsStore.key;
import static io.floci.aws.services.ivs.IvsStore.meta;

/**
 * IVS Real-Time's media: what happens as participants publish to stages through the IVS media server. A WHIP
 * publisher's path is {@code rt/<stage>/<participant>}, configured when {@link IvsRealtimeWhip} admits its token; an
 * RTMP(S) publisher's is its ingest configuration's {@code app/<stream key>}. Polling the server turns a path that
 * becomes ready into a participant who joined and published, in the stage's session (started with its first
 * publisher, ended after its last), with participant events and {@code IVS Stage Update} events; a stage with
 * participant recording records each publisher to its storage configuration's bucket. Compositions mix a stage's
 * publishers with ffmpeg, into a channel's RTMP ingest or an S3 HLS recording, and replication pulls a publisher's
 * path into another stage's.
 */
@ApplicationScoped
public class IvsRealtimeLive implements Resettable {

    private static final Logger LOG = Logger.getLogger(IvsRealtimeLive.class);
    static final String STAGE_PATHS = "rt/";
    private static final long STOPPED_COMPOSITION_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final DateTimeFormatter PREFIX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    /** A path expected to be published: who will appear on the stage when it is. */
    record Expected(String account, String region, String stageId, String participantId, String userId,
                    ObjectNode attributes, String protocol, String ingestArn, Replica replica) {
    }

    /** Where a replica's media comes from. */
    record Replica(String sourceStageId, String sourceSessionId, String sourceParticipantPath) {
    }

    /** A participant publishing to a stage. */
    static final class Publisher {
        final Expected who;
        final String path;
        final String sessionId;
        final Instant joined = Instant.now();
        Recording recording;

        Publisher(Expected who, String path, String sessionId) {
            this.who = who;
            this.path = path;
            this.sessionId = sessionId;
        }

        String stageKey() {
            return who.account() + "|" + who.region() + "|" + who.stageId();
        }
    }

    /** An individual participant recording, which a reconnect window may extend. */
    static final class Recording {
        final String bucket;
        final String prefix;
        final Instant started = Instant.now();
        final ObjectNode configuration;
        final List<String> paths = new ArrayList<>();
        ScheduledFuture<?> pending;

        Recording(String bucket, String prefix, ObjectNode configuration) {
            this.bucket = bucket;
            this.prefix = prefix;
            this.configuration = configuration;
        }
    }

    /** A running composition: one ffmpeg per destination, restarted as the stage's publishers change. */
    static final class Composition {
        final String account;
        final String region;
        final String id;
        final Map<String, Integer> processes = new HashMap<>();
        final Map<String, Long> uploaded = new HashMap<>();
        List<String> inputs = List.of();
        long idleSince = System.currentTimeMillis();

        Composition(String account, String region, String id) {
            this.account = account;
            this.region = region;
            this.id = id;
        }
    }

    private final IvsStore store;
    private final IvsMediaServer media;
    private final Instance<EventBridgeService> events;
    private final Instance<S3Service> s3;
    private final Map<String, Expected> expected = new ConcurrentHashMap<>();
    private final Map<String, Publisher> publishing = new ConcurrentHashMap<>();
    private final Map<String, Recording> ending = new ConcurrentHashMap<>();
    private final Map<String, Composition> compositions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> replicationEnds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivs-realtime");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivs-realtime-media");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean polling;

    @Inject
    public IvsRealtimeLive(IvsStore store, IvsMediaServer media, Instance<EventBridgeService> events,
                           Instance<S3Service> s3) {
        this.store = store;
        this.media = media;
        this.events = events;
        this.s3 = s3;
        media.onStart(() -> {
            startPolling();
            configureAllPaths();
        });
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    @Override
    public void clear() {
        expected.clear();
        publishing.clear();
        ending.clear();
        compositions.clear();
        replicationEnds.clear();
    }

    private boolean mock() {
        return store.config().services().ivs().mock();
    }

    /** Starts the media server in the background when a stage first needs it. */
    void ensureMedia() {
        if (mock() || media.endpoint() != null) {
            return;
        }
        worker.execute(() -> {
            try {
                if (media.dockerReachable()) {
                    media.ensureStarted();
                } else {
                    LOG.warnv("No Docker daemon is reachable; IVS stages cannot be published to");
                }
            } catch (RuntimeException e) {
                LOG.warnv("The IVS media server could not be started: {0}", e.getMessage());
            }
        });
    }

    /** Starts the media server when persisted stages or ingest configurations need it. */
    public void restorePersistedRuntime() {
        if (!store.everywhere(key -> key.contains("/stage/") || key.contains("/ingest-configuration/")).isEmpty()) {
            ensureMedia();
        }
    }

    private synchronized void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        long interval = Math.max(200, store.config().services().ivs().pollMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                poll();
            } catch (RuntimeException e) {
                LOG.debugv("IVS Real-Time poll failed: {0}", e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Configures every ingest configuration's path on a server that just started. */
    private void configureAllPaths() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                key -> key.contains("/ingest-configuration/"))) {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            configureIngest(entry.accountId(), region, entry.value());
        }
        expected.forEach((path, who) -> media.configureNamedPath(path, records(who), who.replica() == null ? null
                : IvsMediaServer.RTSP + who.replica().sourceParticipantPath()));
    }

    // ---------------------------------------------------------------- stage and ingest hooks

    void stageChanged(String account, String region, String stageId) {
        ensureMedia();
    }

    void ingestChanged(String account, String region, ObjectNode configuration) {
        ensureMedia();
        configureIngest(account, region, configuration);
    }

    private void configureIngest(String account, String region, ObjectNode configuration) {
        if (media.endpoint() == null) {
            return;
        }
        boolean record = recordingConfiguration(account, region, stageIdOf(configuration.path("stageArn").asText()))
                != null;
        for (String streamKey : streamKeys(configuration)) {
            media.configureNamedPath(IvsMediaServer.pathOf(streamKey), record, null);
        }
    }

    void ingestDeleted(String account, String region, ObjectNode configuration) {
        for (String streamKey : streamKeys(configuration)) {
            media.removeNamedPath(IvsMediaServer.pathOf(streamKey));
        }
    }

    private static List<String> streamKeys(ObjectNode configuration) {
        List<String> keys = new ArrayList<>();
        keys.add(configuration.path("streamKey").asText());
        configuration.path("redundantIngestCredentials").forEach(c -> keys.add(c.path("streamKey").asText()));
        return keys;
    }

    private static String stageIdOf(String stageArn) {
        return stageArn == null || stageArn.isEmpty() ? null : stageArn.substring(stageArn.lastIndexOf('/') + 1);
    }

    /** Admits a WHIP publisher: its path takes media from now on. */
    String expectWhip(IvsRealtimeService.Grant grant) {
        String path = STAGE_PATHS + grant.stageId() + "/" + grant.participantId();
        Expected who = new Expected(grant.account(), grant.region(), grant.stageId(), grant.participantId(),
                grant.userId(), grant.attributes(), "WHIP", null, null);
        expected.put(path, who);
        media.configureNamedPath(path, records(who), null);
        return path;
    }

    /** Records a publish error: a participant who joined and could not publish. */
    void publishError(IvsRealtimeService.Grant grant, String errorCode, String protocol) {
        Expected who = new Expected(grant.account(), grant.region(), grant.stageId(), grant.participantId(),
                grant.userId(), grant.attributes(), protocol, null, null);
        publishErrorOf(who, errorCode);
    }

    private synchronized void publishErrorOf(Expected who, String errorCode) {
        String sessionId = ensureSession(who.account(), who.region(), who.stageId());
        if (sessionId == null) {
            return;
        }
        ObjectNode participant = participant(who, sessionId);
        participant.put("state", "DISCONNECTED").put("published", false);
        addEvent(participant, "JOINED", null);
        addEvent(participant, "PUBLISH_ERROR", errorCode);
        addEvent(participant, "LEFT", null);
        saveParticipant(who, sessionId, participant);
        ObjectNode detail = stageDetail(who, sessionId, "Participant Publish Error");
        detail.put("error_code", errorCode);
        publish(who.account(), who.region(), "stage/" + who.stageId(), "IVS Stage Update", detail);
        endSessionIfIdle(who.account(), who.region(), who.stageId());
    }

    // ---------------------------------------------------------------- polling

    /** Moves every stage on from the media server's paths: participants join, publish, leave. */
    synchronized void poll() {
        if (media.endpoint() == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode path : media.livePaths()) {
            String name = path.path("name").asText();
            if (!path.path("ready").asBoolean(false)) {
                continue;
            }
            if (publishing.containsKey(name)) {
                seen.add(name);
                continue;
            }
            Expected who = null;
            if (name.startsWith(STAGE_PATHS)) {
                who = expected.get(name);
            } else if (name.startsWith("app/rt_")) {
                who = ingestPublisher(name, path.path("source").path("type").asText(""));
            }
            if (who == null) {
                continue;
            }
            seen.add(name);
            join(who, name);
        }
        for (Map.Entry<String, Publisher> entry : new ArrayList<>(publishing.entrySet())) {
            if (!seen.contains(entry.getKey())) {
                publishing.remove(entry.getKey());
                leave(entry.getValue());
            }
        }
        long now = System.currentTimeMillis();
        long idleMillis = TimeUnit.SECONDS.toMillis(store.config().services().ivs().compositionIdleSeconds());
        for (Composition composition : new ArrayList<>(compositions.values())) {
            refresh(composition, now, idleMillis);
        }
    }

    /** The ingest configuration a stream key path publishes as, or null to refuse it. */
    private Expected ingestPublisher(String path, String sourceType) {
        String streamKey = path.substring("app/".length());
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                key -> key.contains("/ingest-configuration/"))) {
            ObjectNode configuration = entry.value();
            String participantId = null;
            if (streamKey.equals(configuration.path("streamKey").asText())) {
                participantId = configuration.path("participantId").asText();
            }
            for (JsonNode credential : configuration.path("redundantIngestCredentials")) {
                if (streamKey.equals(credential.path("streamKey").asText())) {
                    participantId = credential.path("participantId").asText();
                }
            }
            if (participantId == null) {
                continue;
            }
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String stageId = stageIdOf(configuration.path("stageArn").asText());
            String protocol = "rtmpsConn".equals(sourceType) ? "RTMPS" : "RTMP";
            Expected who = new Expected(entry.accountId(), region, stageId, participantId,
                    configuration.path("userId").asText(""), (ObjectNode) configuration.path("attributes"),
                    protocol, configuration.path("arn").asText(), null);
            if (stageId == null || store.get(entry.accountId(), key(region, "stage", stageId)).isEmpty()) {
                // A key of no stage is unusable.
                media.kick(path);
                return null;
            }
            if ("RTMP".equals(protocol) && !configuration.path("insecureIngest").asBoolean(false)) {
                media.kick(path);
                publishErrorOf(who, "INVALID_PROTOCOL");
                return null;
            }
            return who;
        }
        return null;
    }

    private void join(Expected who, String path) {
        String sessionId = ensureSession(who.account(), who.region(), who.stageId());
        if (sessionId == null) {
            media.kick(path);
            return;
        }
        Publisher publisher = new Publisher(who, path, sessionId);
        publishing.put(path, publisher);
        ObjectNode participant = participant(who, sessionId);
        participant.put("state", "CONNECTED").put("published", true);
        if (!participant.has("firstJoinTime")) {
            participant.put("firstJoinTime", IvsStore.now());
        }
        addEvent(participant, "JOINED", null);
        addEvent(participant, "PUBLISH_STARTED", null);
        if (who.ingestArn() != null) {
            setIngestState(who, "ACTIVE");
        }
        saveParticipant(who, sessionId, participant);
        publish(who.account(), who.region(), "stage/" + who.stageId(), "IVS Stage Update",
                stageDetail(who, sessionId, "Participant Published"));
        startRecording(publisher);
        if (who.replica() != null) {
            ObjectNode replica = store.get(who.account(), replicaKey(who)).orElse(null);
            if (replica != null) {
                replica.put("destinationSessionId", sessionId);
                store.put(who.account(), replicaKey(who), replica);
            }
        }
    }

    private void leave(Publisher publisher) {
        Expected who = publisher.who;
        ObjectNode participant = participant(who, publisher.sessionId);
        participant.put("state", "DISCONNECTED").put("published", false);
        addEvent(participant, "PUBLISH_STOPPED", null);
        addEvent(participant, "LEFT", null);
        saveParticipant(who, publisher.sessionId, participant);
        if (who.ingestArn() != null) {
            setIngestState(who, "INACTIVE");
        }
        publish(who.account(), who.region(), "stage/" + who.stageId(), "IVS Stage Update",
                stageDetail(who, publisher.sessionId, "Participant Unpublished"));
        stopRecording(publisher);
        endReplicationsOf(publisher);
        endSessionIfIdle(who.account(), who.region(), who.stageId());
    }

    // ---------------------------------------------------------------- sessions and participants

    /** The stage's active session, started when it has none; null when the stage no longer exists. */
    private String ensureSession(String account, String region, String stageId) {
        ObjectNode stage = store.get(account, key(region, "stage", stageId)).orElse(null);
        if (stage == null) {
            return null;
        }
        String sessionId = stage.path("activeSessionId").asText("");
        if (!sessionId.isEmpty()) {
            return sessionId;
        }
        sessionId = "st-" + IvsStore.id(13);
        ObjectNode session = store.mapper().createObjectNode().put("sessionId", sessionId)
                .put("startTime", IvsStore.now());
        session.putObject("_").put("kind", "stage-session").put("seq", System.nanoTime());
        store.put(account, key(region, "stage-session", stageId + "/" + sessionId), session);
        stage.put("activeSessionId", sessionId);
        store.put(account, key(region, "stage", stageId), stage);
        return sessionId;
    }

    /** Ends the stage's session once no one publishes to it. */
    private void endSessionIfIdle(String account, String region, String stageId) {
        for (Publisher publisher : publishing.values()) {
            if (publisher.who.account().equals(account) && publisher.who.region().equals(region)
                    && publisher.who.stageId().equals(stageId)) {
                return;
            }
        }
        ObjectNode stage = store.get(account, key(region, "stage", stageId)).orElse(null);
        if (stage == null || !stage.hasNonNull("activeSessionId")) {
            return;
        }
        String sessionId = stage.path("activeSessionId").asText();
        String sessionKey = key(region, "stage-session", stageId + "/" + sessionId);
        store.get(account, sessionKey).ifPresent(session -> {
            session.put("endTime", IvsStore.now());
            store.put(account, sessionKey, session);
        });
        stage.remove("activeSessionId");
        store.put(account, key(region, "stage", stageId), stage);
    }

    private static String participantKey(Expected who, String sessionId) {
        return key(who.region(), "participant", who.stageId() + "/" + sessionId + "/" + who.participantId());
    }

    private ObjectNode participant(Expected who, String sessionId) {
        ObjectNode participant = store.get(who.account(), participantKey(who, sessionId)).orElse(null);
        if (participant != null) {
            return participant;
        }
        participant = store.mapper().createObjectNode();
        participant.put("participantId", who.participantId()).put("userId", who.userId()).put("state", "CONNECTED");
        participant.set("attributes", who.attributes() == null ? store.mapper().createObjectNode()
                : who.attributes().deepCopy());
        participant.put("published", false).put("protocol", who.protocol())
                .put("recordingState", recordingConfiguration(who.account(), who.region(), who.stageId()) == null
                        ? "DISABLED" : "STOPPED");
        if (who.replica() != null) {
            participant.put("replicationType", "REPLICA").put("replicationState", "ACTIVE")
                    .put("sourceStageArn", IvsStore.arnFor(who.account(), who.region(), "stage/"
                            + who.replica().sourceStageId()))
                    .put("sourceSessionId", who.replica().sourceSessionId());
        } else {
            participant.put("replicationType", "NONE");
        }
        if (who.ingestArn() != null) {
            participant.put("ingestConfigurationArn", who.ingestArn()).put("redundantIngest", false);
        }
        participant.putObject("_").put("kind", "participant").put("seq", System.nanoTime()).putArray("events");
        return participant;
    }

    private void saveParticipant(Expected who, String sessionId, ObjectNode participant) {
        store.put(who.account(), participantKey(who, sessionId), participant);
    }

    private void addEvent(ObjectNode participant, String name, String errorCode) {
        ArrayNode list = meta(participant).withArray("/events");
        ObjectNode event = list.addObject();
        event.put("name", name).put("participantId", participant.path("participantId").asText())
                .put("eventTime", IvsStore.now());
        if (errorCode != null) {
            event.put("errorCode", errorCode);
        }
        if ("REPLICA".equals(participant.path("replicationType").asText())) {
            event.put("replica", true);
        }
    }

    private void setIngestState(Expected who, String state) {
        String id = who.ingestArn().substring(who.ingestArn().lastIndexOf('/') + 1);
        String ingestKey = key(who.region(), "ingest-configuration", id);
        store.get(who.account(), ingestKey).ifPresent(configuration -> {
            configuration.put("state", state);
            store.put(who.account(), ingestKey, configuration);
        });
    }

    private ObjectNode stageDetail(Expected who, String sessionId, String eventName) {
        ObjectNode detail = store.mapper().createObjectNode();
        String now = IvsStore.now();
        detail.put("session_id", sessionId).put("event_name", eventName)
                .put("event_time", Instant.parse(now).truncatedTo(ChronoUnit.SECONDS).toString())
                .put("event_time_precise", now).put("user_id", who.userId())
                .put("participant_id", who.participantId());
        if (who.replica() != null) {
            detail.put("replica", true).put("source_stage_arn", IvsStore.arnFor(who.account(), who.region(),
                    "stage/" + who.replica().sourceStageId())).put("source_session_id",
                    who.replica().sourceSessionId());
        } else {
            detail.put("replica", false);
        }
        return detail;
    }

    private void publish(String account, String region, String resource, String detailType, ObjectNode detail) {
        String arn = IvsStore.arnFor(account, region, resource);
        try {
            events.get().putEvents(List.of(EventBridgeService.serviceEventEntry("aws.ivs", detailType,
                    detail.toString(), arn)), region, account);
        } catch (RuntimeException e) {
            LOG.debugv("Could not publish the IVS {0} event: {1}", detailType, e.getMessage());
        }
    }

    /** The publisher of a participant in a stage, or null when it is not publishing. */
    Publisher publisher(String account, String region, String stageId, String participantId) {
        for (Publisher publisher : publishing.values()) {
            Expected who = publisher.who;
            if (who.account().equals(account) && who.region().equals(region) && who.stageId().equals(stageId)
                    && who.participantId().equals(participantId)) {
                return publisher;
            }
        }
        return null;
    }

    /** Disconnects a participant: whether it was connected. */
    synchronized boolean disconnect(String account, String region, String stageId, String participantId,
                                    String reason) {
        boolean found = false;
        for (Map.Entry<String, Expected> entry : new ArrayList<>(expected.entrySet())) {
            Expected who = entry.getValue();
            if (who.account().equals(account) && who.region().equals(region) && who.stageId().equals(stageId)
                    && who.participantId().equals(participantId)) {
                expected.remove(entry.getKey());
                media.removeNamedPath(entry.getKey());
                found = true;
            }
        }
        Publisher publisher = publisher(account, region, stageId, participantId);
        if (publisher != null) {
            media.kick(publisher.path);
            found = true;
        }
        return found;
    }

    /** Disconnects a deleted stage's participants, ends its session, and fails its compositions. */
    synchronized void stageDeleted(String account, String region, String stageId, String stageArn) {
        for (Map.Entry<String, Expected> entry : new ArrayList<>(expected.entrySet())) {
            Expected who = entry.getValue();
            if (who.account().equals(account) && who.region().equals(region) && who.stageId().equals(stageId)) {
                expected.remove(entry.getKey());
                media.removeNamedPath(entry.getKey());
            }
        }
        for (Publisher publisher : new ArrayList<>(publishing.values())) {
            if (publisher.stageKey().equals(account + "|" + region + "|" + stageId)) {
                media.kick(publisher.path);
            }
        }
        for (ObjectNode composition : store.list(account, key(region, "composition", ""))) {
            String state = composition.path("state").asText();
            if (stageArn.equals(composition.path("stageArn").asText()) && !"STOPPED".equals(state)
                    && !"FAILED".equals(state)) {
                fail(account, region, meta(composition).path("id").asText(), "StageDeleted",
                        "Stage has been deleted");
            }
        }
    }

    // ---------------------------------------------------------------- individual participant recording

    /** A stage's participant recording configuration, or null when it records no one. */
    private ObjectNode recordingConfiguration(String account, String region, String stageId) {
        if (stageId == null) {
            return null;
        }
        ObjectNode stage = store.get(account, key(region, "stage", stageId)).orElse(null);
        JsonNode configuration = stage == null ? null : stage.get("autoParticipantRecordingConfiguration");
        return configuration == null || configuration.path("storageConfigurationArn").asText("").isEmpty() ? null
                : (ObjectNode) configuration;
    }

    private boolean records(Expected who) {
        ObjectNode configuration = recordingConfiguration(who.account(), who.region(), who.stageId());
        return configuration != null && (who.replica() == null
                || configuration.path("recordParticipantReplicas").asBoolean(true));
    }

    private void startRecording(Publisher publisher) {
        Expected who = publisher.who;
        ObjectNode configuration = recordingConfiguration(who.account(), who.region(), who.stageId());
        if (configuration == null || !records(who)) {
            return;
        }
        String recordingKey = who.account() + "|" + who.region() + "|" + who.stageId() + "|" + who.participantId();
        Recording recording = ending.remove(recordingKey);
        if (recording != null && recording.configuration.path("storageConfigurationArn").asText().equals(
                configuration.path("storageConfigurationArn").asText())) {
            if (recording.pending != null) {
                recording.pending.cancel(false);
            }
        } else {
            String storageArn = configuration.path("storageConfigurationArn").asText();
            ObjectNode storage = store.get(who.account(), key(who.region(), "storage-configuration",
                    storageArn.substring(storageArn.lastIndexOf('/') + 1))).orElse(null);
            String bucket = storage == null ? "" : storage.path("s3").path("bucketName").asText();
            String prefix = who.stageId() + "/" + publisher.sessionId + "/" + who.participantId() + "/"
                    + PREFIX_TIME.format(Instant.now());
            recording = new Recording(bucket, prefix, configuration.deepCopy());
            if (storage == null || !s3.get().bucketExists(bucket)) {
                recordingEvent(publisher, recording, "Recording Start Failure", "ResourceNotFoundException",
                        "S3 bucket not found. Please verify your bucket exists.", -1);
                setRecordingState(publisher, "FAILED", recording);
                return;
            }
            try {
                put(recording, "events/recording-started.json", metadata(publisher, recording, "RECORDING_STARTED",
                        null, -1).toString().getBytes(StandardCharsets.UTF_8), "application/json");
            } catch (AwsException e) {
                recordingEvent(publisher, recording, "Recording Start Failure", e.getErrorCode(), e.getMessage(),
                        -1);
                setRecordingState(publisher, "FAILED", recording);
                return;
            }
            recordingEvent(publisher, recording, "Recording Start", null, null, -1);
        }
        recording.paths.add(publisher.path);
        publisher.recording = recording;
        setRecordingState(publisher, "ACTIVE", recording);
    }

    private void setRecordingState(Publisher publisher, String state, Recording recording) {
        ObjectNode participant = participant(publisher.who, publisher.sessionId);
        participant.put("recordingState", state).put("recordingS3BucketName", recording.bucket)
                .put("recordingS3Prefix", recording.prefix);
        saveParticipant(publisher.who, publisher.sessionId, participant);
    }

    private void stopRecording(Publisher publisher) {
        Recording recording = publisher.recording;
        if (recording == null) {
            return;
        }
        Expected who = publisher.who;
        setRecordingState(publisher, "STOPPING", recording);
        String recordingKey = who.account() + "|" + who.region() + "|" + who.stageId() + "|" + who.participantId();
        int window = recording.configuration.path("recordingReconnectWindowSeconds").asInt(0);
        Runnable finish = () -> worker.execute(() -> finish(publisher, recording));
        if (window > 0) {
            ending.put(recordingKey, recording);
            recording.pending = scheduler.schedule(() -> {
                if (ending.remove(recordingKey, recording)) {
                    finish.run();
                }
            }, window, TimeUnit.SECONDS);
        } else {
            finish.run();
        }
    }

    /** Packages a recording's segments into its HLS, thumbnails and metadata in S3. */
    private void finish(Publisher publisher, Recording recording) {
        String work = "/tmp/rt-rec-" + IvsStore.id(8);
        List<String> segments = new ArrayList<>();
        for (String path : recording.paths) {
            segments.addAll(media.recordedSegments(path));
        }
        long durationMs = 0;
        String failure = null;
        JsonNode thumbnails = recording.configuration.path("thumbnailConfiguration");
        String mediaType = recording.configuration.path("mediaTypes").path(0).asText("AUDIO_VIDEO");
        boolean thumbs = "INTERVAL".equals(thumbnails.path("recordingMode").asText("INTERVAL"));
        Set<String> storage = new HashSet<>();
        thumbnails.path("storage").forEach(s -> storage.add(s.asText()));
        try {
            if (segments.isEmpty()) {
                throw new IllegalStateException("No media was recorded.");
            }
            int segmentSeconds = recording.configuration.path("hlsConfiguration")
                    .path("targetSegmentDurationSeconds").asInt(6);
            StringBuilder script = new StringBuilder("set -e; mkdir -p " + work + "/hls/high " + work
                    + "/thumbnails/high " + work + "/latest_thumbnail/high; cd " + work + "; : > list.txt; ");
            for (String segment : segments) {
                script.append("echo \"file '").append(segment).append("'\" >> list.txt; ");
            }
            if (!"NONE".equals(mediaType)) {
                script.append("ffmpeg -nostdin -loglevel error -f concat -safe 0 -i list.txt ")
                        .append("AUDIO_ONLY".equals(mediaType) ? "-vn " : "")
                        .append("-c copy -f hls -hls_time ").append(segmentSeconds)
                        .append(" -hls_playlist_type vod -hls_segment_type fmp4 -hls_fmp4_init_filename init-0.mp4 ")
                        .append("-hls_flags program_date_time -hls_segment_filename hls/high/%d.mp4 ")
                        .append("hls/high/playlist.m3u8; ");
            }
            if (thumbs && !"AUDIO_ONLY".equals(mediaType)) {
                int interval = Math.max(1, thumbnails.path("targetIntervalSeconds").asInt(60));
                script.append("ffmpeg -nostdin -loglevel error -f concat -safe 0 -i list.txt -vf fps=1/")
                        .append(interval).append(" -q:v 3 -start_number 1 thumbnails/high/%d.jpg || true; ");
                script.append("last=$(ls -1v thumbnails/high/*.jpg 2>/dev/null | tail -1); if [ -n \"$last\" ]; ")
                        .append("then cp \"$last\" latest_thumbnail/high/thumb.jpg; fi; ");
            }
            script.append("if [ -f hls/high/playlist.m3u8 ]; then awk -F: '/^#EXTINF/ {split($2, a, \",\"); ")
                    .append("s += a[1]} END {printf \"%.3f\\n\", s}' hls/high/playlist.m3u8; else ")
                    .append("ffprobe -v error -show_entries format=duration -of csv=p=0 -f concat -safe 0 ")
                    .append("-i list.txt; fi");
            IvsMediaServer.ExecResult result = media.exec(List.of("sh", "-c", script.toString()));
            if (result.code() != 0) {
                throw new IllegalStateException("The recording could not be packaged: " + result.output().trim());
            }
            String[] lines = result.output().trim().split("\n");
            try {
                durationMs = Math.round(Double.parseDouble(lines[lines.length - 1].trim()) * 1000);
            } catch (NumberFormatException e) {
                durationMs = 0;
            }
            for (Map.Entry<String, byte[]> file : media.copyOut(work).entrySet()) {
                String name = file.getKey();
                if (name.equals("list.txt") || name.startsWith("thumbnails/") && !storage.contains("SEQUENTIAL")
                        || name.startsWith("latest_thumbnail/") && !storage.contains("LATEST")) {
                    continue;
                }
                put(recording, "media/" + name, file.getValue(), contentType(name));
            }
            if (!"NONE".equals(mediaType)) {
                put(recording, "media/hls/multivariant.m3u8", ("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS"
                        + "\n#EXT-X-STREAM-INF:BANDWIDTH=2500000\nhigh/playlist.m3u8\n")
                        .getBytes(StandardCharsets.UTF_8), "application/x-mpegURL");
            }
        } catch (IllegalStateException | AwsException e) {
            failure = e.getMessage();
            LOG.warnv("The IVS participant recording {0} failed: {1}", recording.prefix, failure);
        } finally {
            StringBuilder cleanup = new StringBuilder("rm -rf " + work);
            segments.forEach(segment -> cleanup.append(" '").append(segment).append('\''));
            media.exec(List.of("sh", "-c", cleanup.toString()));
        }
        try {
            if (failure == null) {
                put(recording, "events/recording-ended.json", metadata(publisher, recording, "RECORDING_ENDED",
                        "Recording ended", durationMs).toString().getBytes(StandardCharsets.UTF_8),
                        "application/json");
                recordingEvent(publisher, recording, "Recording End", null, null, durationMs);
                setRecordingState(publisher, "STOPPED", recording);
            } else {
                put(recording, "events/recording-failed.json", metadata(publisher, recording,
                        "RECORDING_ENDED_WITH_FAILURE", failure, durationMs).toString()
                        .getBytes(StandardCharsets.UTF_8), "application/json");
                recordingEvent(publisher, recording, "Recording End Failure", "InternalServerException",
                        "Service internal error", durationMs);
                setRecordingState(publisher, "FAILED", recording);
            }
        } catch (AwsException e) {
            recordingEvent(publisher, recording, "Recording End Failure", e.getErrorCode(), e.getMessage(),
                    durationMs);
            setRecordingState(publisher, "FAILED", recording);
        }
    }

    private ObjectNode metadata(Publisher publisher, Recording recording, String status, String message,
                                long durationMs) {
        Expected who = publisher.who;
        ObjectNode metadata = store.mapper().createObjectNode();
        metadata.put("version", "v1").put("stage_arn", IvsStore.arnFor(who.account(), who.region(), "stage/"
                        + who.stageId())).put("session_id", publisher.sessionId)
                .put("participant_id", who.participantId())
                .put("recording_started_at", recording.started.truncatedTo(ChronoUnit.SECONDS).toString());
        if (durationMs >= 0) {
            metadata.put("recording_ended_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        metadata.put("recording_status", status);
        if (message != null) {
            metadata.put("recording_status_message", message);
        }
        ObjectNode mediaNode = metadata.putObject("media");
        ObjectNode hls = mediaNode.putObject("hls");
        if (durationMs >= 0) {
            hls.put("duration_ms", durationMs);
        }
        hls.put("path", "media/hls").put("playlist", "multivariant.m3u8");
        hls.putArray("renditions").addObject().put("path", "high").put("playlist", "playlist.m3u8");
        Set<String> storage = new HashSet<>();
        recording.configuration.path("thumbnailConfiguration").path("storage").forEach(s -> storage.add(s.asText()));
        boolean thumbs = "INTERVAL".equals(recording.configuration.path("thumbnailConfiguration")
                .path("recordingMode").asText("INTERVAL"));
        if (thumbs && storage.contains("SEQUENTIAL")) {
            mediaNode.putObject("thumbnails").put("path", "media/thumbnails").putArray("renditions").addObject()
                    .put("path", "high");
        }
        if (thumbs && storage.contains("LATEST")) {
            mediaNode.putObject("latest_thumbnail").put("path", "media/latest_thumbnail").putArray("renditions")
                    .addObject().put("path", "high");
        }
        return metadata;
    }

    private void recordingEvent(Publisher publisher, Recording recording, String name, String errorCode,
                                String reason, long durationMs) {
        ObjectNode detail = store.mapper().createObjectNode();
        detail.put("session_id", publisher.sessionId).put("event_name", name)
                .put("participant_id", publisher.who.participantId())
                .put("recording_s3_bucket_name", recording.bucket).put("recording_s3_key_prefix", recording.prefix);
        if (durationMs >= 0) {
            detail.put("recording_duration_ms", durationMs);
        }
        if (errorCode != null) {
            detail.put("error_code", errorCode).put("reason", reason);
        }
        publish(publisher.who.account(), publisher.who.region(), "stage/" + publisher.who.stageId(),
                "IVS Participant Recording State Change", detail);
    }

    private void put(Recording recording, String name, byte[] content, String type) {
        s3.get().putObject(recording.bucket, recording.prefix + "/" + name, content, type, Map.of());
    }

    private static String contentType(String name) {
        if (name.endsWith(".m3u8")) {
            return "application/x-mpegURL";
        }
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".ts")) {
            return "video/MP2T";
        }
        return name.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
    }

    // ---------------------------------------------------------------- replication

    private static String replicaKey(Expected who) {
        return key(who.region(), "replica", who.replica().sourceStageId() + "/" + who.participantId() + "/"
                + who.stageId());
    }

    /** Replicates a publisher into another stage: the destination path pulls the source's media. */
    synchronized void replicate(Publisher source, ObjectNode destinationStage, int reconnectWindowSeconds,
                                ObjectNode attributes) {
        Expected from = source.who;
        String destinationId = meta(destinationStage).path("id").asText();
        Replica replica = new Replica(from.stageId(), source.sessionId, source.path);
        Expected who = new Expected(from.account(), from.region(), destinationId, from.participantId(),
                from.userId(), attributes.isEmpty() ? from.attributes() : attributes, from.protocol(), null,
                replica);
        String path = STAGE_PATHS + destinationId + "/" + from.participantId();
        expected.put(path, who);
        ObjectNode record = store.mapper().createObjectNode();
        record.put("sourceStageArn", IvsStore.arnFor(from.account(), from.region(), "stage/" + from.stageId()))
                .put("participantId", from.participantId()).put("sourceSessionId", source.sessionId)
                .put("destinationStageArn", destinationStage.path("arn").asText())
                .put("destinationSessionId", "").put("replicationState", "ACTIVE");
        record.putObject("_").put("kind", "replica").put("seq", System.nanoTime())
                .put("reconnectWindowSeconds", reconnectWindowSeconds);
        store.put(from.account(), replicaKey(who), record);
        media.configureNamedPath(path, records(who), IvsMediaServer.RTSP + source.path);
        ObjectNode participant = participant(from, source.sessionId);
        participant.put("replicationType", "SOURCE").put("replicationState", "ACTIVE");
        ObjectNode event = meta(participant).withArray("/events").addObject();
        event.put("name", "REPLICATION_STARTED").put("participantId", from.participantId())
                .put("eventTime", IvsStore.now()).put("destinationStageArn", destinationStage.path("arn").asText())
                .put("destinationSessionId", destinationStage.path("activeSessionId").asText(""));
        saveParticipant(from, source.sessionId, participant);
        ObjectNode detail = stageDetail(from, source.sessionId, "Participant Replication Start");
        detail.remove("replica");
        detail.remove("event_time_precise");
        detail.put("destination_stage_arn", destinationStage.path("arn").asText())
                .put("destination_session_id", destinationStage.path("activeSessionId").asText(""));
        publish(from.account(), from.region(), "stage/" + from.stageId(), "IVS Stage Update", detail);
    }

    synchronized void stopReplication(String account, String region, String sourceStageId, String participantId,
                                      String destinationStageId) {
        String path = STAGE_PATHS + destinationStageId + "/" + participantId;
        Expected who = expected.remove(path);
        media.removeNamedPath(path);
        String replicaKey = key(region, "replica", sourceStageId + "/" + participantId + "/" + destinationStageId);
        ObjectNode replica = store.get(account, replicaKey).orElse(null);
        if (replica == null) {
            return;
        }
        replica.put("replicationState", "STOPPED");
        store.put(account, replicaKey, replica);
        Expected source = new Expected(account, region, sourceStageId, participantId,
                who == null ? "" : who.userId(), null, null, null, null);
        String sourceSession = replica.path("sourceSessionId").asText();
        ObjectNode participant = store.get(account, participantKey(source, sourceSession)).orElse(null);
        if (participant != null) {
            ObjectNode event = meta(participant).withArray("/events").addObject();
            event.put("name", "REPLICATION_STOPPED").put("participantId", participantId)
                    .put("eventTime", IvsStore.now()).put("destinationStageArn",
                            replica.path("destinationStageArn").asText())
                    .put("destinationSessionId", replica.path("destinationSessionId").asText());
            participant.put("replicationState", "STOPPED");
            store.put(account, participantKey(source, sourceSession), participant);
            source = new Expected(account, region, sourceStageId, participantId, participant.path("userId").asText(),
                    null, null, null, null);
        }
        ObjectNode detail = stageDetail(source, sourceSession, "Participant Replication End");
        detail.remove("replica");
        detail.remove("event_time_precise");
        detail.put("destination_stage_arn", replica.path("destinationStageArn").asText())
                .put("destination_session_id", replica.path("destinationSessionId").asText());
        publish(account, region, "stage/" + sourceStageId, "IVS Stage Update", detail);
    }

    /** Ends a stopped publisher's replications, after their reconnect window when it does not return. */
    private void endReplicationsOf(Publisher source) {
        Expected from = source.who;
        for (ObjectNode replica : store.list(from.account(), key(from.region(), "replica", from.stageId() + "/"
                + from.participantId() + "/"))) {
            if (!"ACTIVE".equals(replica.path("replicationState").asText())) {
                continue;
            }
            String destinationId = stageIdOf(replica.path("destinationStageArn").asText());
            int window = meta(replica).path("reconnectWindowSeconds").asInt(0);
            String endKey = from.account() + "|" + from.region() + "|" + from.stageId() + "|"
                    + from.participantId() + "|" + destinationId;
            Runnable end = () -> {
                replicationEnds.remove(endKey);
                if (publisher(from.account(), from.region(), from.stageId(), from.participantId()) == null) {
                    stopReplication(from.account(), from.region(), from.stageId(), from.participantId(),
                            destinationId);
                }
            };
            if (window > 0) {
                replicationEnds.put(endKey, scheduler.schedule(end, window, TimeUnit.SECONDS));
            } else {
                end.run();
            }
        }
    }

    // ---------------------------------------------------------------- compositions

    synchronized void compositionStarted(String account, String region, String compositionId) {
        Composition composition = new Composition(account, region, compositionId);
        compositions.put(account + "|" + region + "|" + compositionId, composition);
        if (mock() || media.endpoint() == null) {
            ensureMedia();
            if (mock()) {
                activate(composition);
            }
            return;
        }
        worker.execute(() -> restart(composition));
    }

    synchronized void compositionStopped(String account, String region, String compositionId) {
        Composition composition = compositions.remove(account + "|" + region + "|" + compositionId);
        if (composition == null) {
            finishComposition(account, region, compositionId, null);
            return;
        }
        worker.execute(() -> finishComposition(account, region, compositionId, composition));
    }

    private ObjectNode composition(Composition composition) {
        return store.get(composition.account, key(composition.region, "composition", composition.id)).orElse(null);
    }

    private void save(Composition composition, ObjectNode record) {
        store.put(composition.account, key(composition.region, "composition", composition.id), record);
    }

    /** Moves a composition on: idles it out, starts it once media is available, restarts it on new publishers. */
    private void refresh(Composition composition, long now, long idleMillis) {
        ObjectNode record = composition(composition);
        if (record == null) {
            compositions.remove(composition.account + "|" + composition.region + "|" + composition.id);
            return;
        }
        String stageId = stageIdOf(record.path("stageArn").asText());
        List<String> inputs = inputsOf(composition.account, composition.region, stageId, record);
        if (!inputs.isEmpty()) {
            composition.idleSince = now;
        } else if (now - composition.idleSince >= idleMillis) {
            compositionStopped(composition.account, composition.region, composition.id);
            record.put("state", "STOPPING");
            save(composition, record);
            return;
        }
        if (!inputs.equals(composition.inputs) || composition.processes.isEmpty()) {
            worker.execute(() -> restart(composition));
        } else {
            worker.execute(() -> sync(composition));
        }
    }

    /** The paths a composition mixes, in the layout's order. */
    private List<String> inputsOf(String account, String region, String stageId, ObjectNode record) {
        String orderAttribute = record.path("layout").path("grid").path("participantOrderAttribute").asText(
                record.path("layout").path("pip").path("participantOrderAttribute").asText(""));
        List<Publisher> found = new ArrayList<>();
        for (Publisher publisher : publishing.values()) {
            if (publisher.stageKey().equals(account + "|" + region + "|" + stageId)) {
                found.add(publisher);
            }
        }
        found.sort((a, b) -> {
            if (!orderAttribute.isEmpty()) {
                String x = a.who.attributes() == null ? "" : a.who.attributes().path(orderAttribute).asText("");
                String y = b.who.attributes() == null ? "" : b.who.attributes().path(orderAttribute).asText("");
                int order = x.compareTo(y);
                if (order != 0) {
                    return order;
                }
            }
            return a.joined.compareTo(b.joined);
        });
        String featured = record.path("layout").path("pip").path("pipParticipantAttribute").asText("");
        if (!featured.isEmpty()) {
            // The PiP participant is drawn last, over the others.
            found.sort((a, b) -> Boolean.compare(flag(a, featured), flag(b, featured)));
        }
        List<String> paths = new ArrayList<>();
        found.forEach(publisher -> paths.add(publisher.path));
        return paths;
    }

    private static boolean flag(Publisher publisher, String attribute) {
        return publisher.who.attributes() != null && "true".equals(publisher.who.attributes().path(attribute).asText());
    }

    private synchronized void activate(Composition composition) {
        ObjectNode record = composition(composition);
        if (record == null || !"STARTING".equals(record.path("state").asText())) {
            return;
        }
        record.put("state", "ACTIVE");
        for (JsonNode destination : record.path("destinations")) {
            if ("STARTING".equals(destination.path("state").asText())) {
                ((ObjectNode) destination).put("state", "ACTIVE").put("startTime", IvsStore.now());
                compositionEvent(composition, record, "Destination Start", destination.path("id").asText(), null,
                        null);
                if (destination.path("configuration").has("s3")) {
                    writeCompositionMetadata(composition, (ObjectNode) destination, "RECORDING_STARTED", null, -1);
                }
            }
        }
        save(composition, record);
        compositionEvent(composition, record, "Session Start", null, null, null);
    }

    /** (Re)starts a composition's ffmpeg processes for the stage's current publishers. */
    private void restart(Composition composition) {
        ObjectNode record;
        List<String> inputs;
        synchronized (this) {
            record = composition(composition);
            if (record == null || media.endpoint() == null) {
                return;
            }
            inputs = inputsOf(composition.account, composition.region, stageIdOf(record.path("stageArn").asText()),
                    record);
        }
        composition.processes.values().forEach(media::stopProcess);
        composition.processes.clear();
        composition.inputs = inputs;
        for (JsonNode destination : record.path("destinations")) {
            String state = destination.path("state").asText();
            if ("FAILED".equals(state) || "STOPPED".equals(state)) {
                continue;
            }
            String failure = startDestination(composition, record, (ObjectNode) destination, inputs);
            if (failure != null) {
                synchronized (this) {
                    ObjectNode current = composition(composition);
                    for (JsonNode d : current.path("destinations")) {
                        if (d.path("id").asText().equals(destination.path("id").asText())) {
                            ((ObjectNode) d).put("state", "FAILED").put("endTime", IvsStore.now());
                        }
                    }
                    save(composition, current);
                    String[] parts = failure.split("\\|", 2);
                    compositionEvent(composition, current, "Destination Failure", destination.path("id").asText(),
                            parts[0], parts.length > 1 ? parts[1] : parts[0]);
                    boolean allFailed = true;
                    for (JsonNode d : current.path("destinations")) {
                        allFailed &= "FAILED".equals(d.path("state").asText());
                    }
                    if (allFailed) {
                        compositions.remove(composition.account + "|" + composition.region + "|" + composition.id);
                        fail(composition.account, composition.region, composition.id, "DestinationFailure",
                                "One or more outputs failed");
                        return;
                    }
                }
            }
        }
        activate(composition);
    }

    /** The encoder settings of a destination: its encoder configuration, or IVS's defaults. */
    private JsonNode encoder(Composition composition, ObjectNode destination) {
        JsonNode configuration = destination.path("configuration");
        String arn = configuration.has("channel")
                ? configuration.path("channel").path("encoderConfigurationArn").asText("")
                : configuration.path("s3").path("encoderConfigurationArns").path(0).asText("");
        ObjectNode encoder = arn.isEmpty() ? null : store.get(composition.account, key(composition.region,
                "encoder-configuration", arn.substring(arn.lastIndexOf('/') + 1))).orElse(null);
        if (encoder != null) {
            ObjectNode video = (ObjectNode) encoder.path("video").deepCopy();
            video.put("id", meta(encoder).path("id").asText());
            return video;
        }
        return store.mapper().createObjectNode().put("width", 1280).put("height", 720).put("framerate", 30)
                .put("bitrate", 2500000).put("id", "default");
    }

    /** Starts a destination's ffmpeg: null, or {@code <error code>|<reason>} when it cannot start. */
    private String startDestination(Composition composition, ObjectNode record, ObjectNode destination,
                                     List<String> inputs) {
        JsonNode video = encoder(composition, destination);
        int width = video.path("width").asInt(1280);
        int height = video.path("height").asInt(720);
        int fps = Math.max(1, Math.round(video.path("framerate").floatValue()));
        String output;
        boolean thumbnailed = false;
        JsonNode configuration = destination.path("configuration");
        if (configuration.has("channel")) {
            String channelArn = configuration.path("channel").path("channelArn").asText();
            String channelId = channelArn.substring(channelArn.lastIndexOf('/') + 1);
            String streamKey = null;
            for (ObjectNode key : store.list(composition.account, key(composition.region, "stream-key", ""))) {
                if (channelId.equals(meta(key).path("channelId").asText())) {
                    streamKey = key.path("value").asText();
                }
            }
            if (streamKey == null) {
                return "ResourceNotFoundException|The channel has no stream key";
            }
            boolean ours = composition.processes.isEmpty();
            for (JsonNode path : media.livePaths()) {
                if (ours && IvsMediaServer.pathOf(streamKey).equals(path.path("name").asText())
                        && path.path("ready").asBoolean(false) && destination.path("state").asText().equals(
                        "STARTING")) {
                    return "ConflictException|Stream already exists";
                }
            }
            output = "-f flv rtmp://127.0.0.1:1935/" + IvsMediaServer.pathOf(streamKey);
        } else {
            String dir = compositionDir(composition, destination);
            String rendition = height + "p" + fps + "-" + video.path("id").asText();
            int segment = configuration.path("s3").path("recordingConfiguration").path("hlsConfiguration")
                    .path("targetSegmentDurationSeconds").asInt(2);
            StringBuilder s3Output = new StringBuilder("-f hls -hls_time " + segment
                    + " -hls_playlist_type event -hls_flags append_list+program_date_time -hls_segment_filename "
                    + dir + "/media/hls/" + rendition + "/%d.ts " + dir + "/media/hls/" + rendition
                    + "/playlist.m3u8");
            JsonNode thumbnails = configuration.path("s3").path("thumbnailConfigurations").path(0);
            if (!thumbnails.isMissingNode()) {
                thumbnailed = true;
                s3Output.append(" -map '[t]' -r 1/").append(Math.max(1, thumbnails.path("targetIntervalSeconds")
                        .asInt(60))).append(" -q:v 3 -start_number ").append(thumbnailCount(dir, width, height) + 1)
                        .append(' ').append(dir).append("/media/thumbnails/").append(width).append('x').append(height)
                        .append("/%d.jpg");
            }
            media.exec(List.of("sh", "-c", "mkdir -p " + dir + "/media/hls/" + rendition + " " + dir
                    + "/media/thumbnails/" + width + "x" + height + " " + dir + "/media/latest_thumbnail/" + width
                    + "x" + height));
            output = s3Output.toString();
            meta(record).put("rendition-" + destination.path("id").asText(), rendition);
        }
        String script = "ffmpeg -nostdin -loglevel error " + filterGraph(record, inputs, width, height, fps,
                thumbnailed) + " -map '[v]' -map '[a]' -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -b:v "
                + video.path("bitrate").asInt(2500000) + " -g " + (fps * 2) + " -c:a aac -b:a 128k -ar 48000 "
                + output;
        int pid = media.startProcess(script, "/tmp/composition-" + composition.id + "-"
                + destination.path("id").asText() + ".log");
        if (pid < 0) {
            return "InternalServerException|Service internal error";
        }
        composition.processes.put(destination.path("id").asText(), pid);
        return null;
    }

    private int thumbnailCount(String dir, int width, int height) {
        String out = media.exec(List.of("sh", "-c", "ls -1 " + dir + "/media/thumbnails/" + width + "x" + height
                + "/*.jpg 2>/dev/null | wc -l")).output().trim();
        try {
            return Integer.parseInt(out);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The inputs and filter graph of a composition: a black canvas of the encoder's size with each publisher laid
     * out on it (a grid, or picture-in-picture with the last publisher small in its corner) and their audio mixed.
     */
    private static String filterGraph(ObjectNode record, List<String> inputs, int width, int height, int fps,
                                      boolean thumbnails) {
        StringBuilder args = new StringBuilder("-f lavfi -i color=c=black:s=" + width + "x" + height + ":r=" + fps
                + " -f lavfi -i anullsrc=r=48000:cl=stereo");
        for (String input : inputs) {
            args.append(" -rtsp_transport tcp -i ").append(IvsMediaServer.RTSP).append(input);
        }
        StringBuilder graph = new StringBuilder();
        String last = "[0:v]";
        JsonNode pip = record.path("layout").path("pip");
        int count = inputs.size();
        String fill = pip.isMissingNode() ? record.path("layout").path("grid").path("videoFillMode").asText("COVER")
                : pip.path("videoFillMode").asText("COVER");
        for (int i = 0; i < count; i++) {
            int[] cell = cell(pip, i, count, width, height,
                    record.path("layout").path("grid").path("gridGap").asInt(2));
            graph.append('[').append(i + 2).append(":v]").append(scale(fill, cell[2], cell[3])).append("[p")
                    .append(i).append("];");
            graph.append(last).append("[p").append(i).append("]overlay=").append(cell[0]).append(':')
                    .append(cell[1]).append(":eof_action=pass[o").append(i).append("];");
            last = "[o" + i + "]";
        }
        graph.append(last).append(thumbnails ? "format=yuv420p,split=2[v][t];" : "format=yuv420p[v];");
        StringBuilder audio = new StringBuilder("[1:a]");
        for (int i = 0; i < count; i++) {
            audio.append('[').append(i + 2).append(":a]");
        }
        graph.append(audio).append("amix=inputs=").append(count + 1).append(":duration=first:normalize=0[a]");
        return args + " -filter_complex '" + graph + "'";
    }

    /** x, y, width and height of a participant's tile. */
    private static int[] cell(JsonNode pip, int index, int count, int width, int height, int gap) {
        if (!pip.isMissingNode() && count > 1 && index == count - 1) {
            int pipWidth = pip.path("pipWidth").asInt(width / 4);
            int pipHeight = pip.path("pipHeight").asInt(height / 4);
            int offset = pip.path("pipOffset").asInt(0);
            String position = pip.path("pipPosition").asText("BOTTOM_RIGHT");
            int x = position.endsWith("LEFT") ? offset : width - pipWidth - offset;
            int y = position.startsWith("TOP") ? offset : height - pipHeight - offset;
            return new int[] {even(x), even(y), even(pipWidth), even(pipHeight)};
        }
        int tiles = pip.isMissingNode() || count == 1 ? count : count - 1;
        if (!pip.isMissingNode() && index < tiles) {
            return new int[] {0, 0, even(width), even(height)};
        }
        int columns = (int) Math.ceil(Math.sqrt(tiles));
        int rows = (int) Math.ceil((double) tiles / columns);
        int tileWidth = (width - gap * (columns - 1)) / columns;
        int tileHeight = (height - gap * (rows - 1)) / rows;
        int column = index % columns;
        int row = index / columns;
        return new int[] {even(column * (tileWidth + gap)), even(row * (tileHeight + gap)), even(tileWidth),
            even(tileHeight)};
    }

    private static int even(int value) {
        return Math.max(2, value - value % 2);
    }

    private static String scale(String fill, int width, int height) {
        return switch (fill) {
            case "FILL" -> "scale=" + width + ":" + height;
            case "CONTAIN" -> "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,pad="
                    + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2";
            default -> "scale=" + width + ":" + height + ":force_original_aspect_ratio=increase,crop=" + width + ":"
                    + height;
        };
    }

    private static String compositionDir(Composition composition, JsonNode destination) {
        return "/compositions/" + composition.id + "/" + destination.path("id").asText();
    }

    /** Uploads what a composition's S3 destinations have written since the last time. */
    private void sync(Composition composition) {
        ObjectNode record = composition(composition);
        if (record == null) {
            return;
        }
        for (JsonNode destination : record.path("destinations")) {
            if (destination.path("configuration").has("s3") && "ACTIVE".equals(destination.path("state").asText())) {
                upload(composition, record, destination, false);
            }
        }
    }

    private void upload(Composition composition, ObjectNode record, JsonNode destination, boolean last) {
        String dir = compositionDir(composition, destination);
        String bucket = bucketOf(composition, destination);
        String prefix = destination.path("detail").path("s3").path("recordingPrefix").asText();
        Map<String, byte[]> files;
        try {
            files = media.copyOut(dir + "/media");
        } catch (IllegalStateException e) {
            return;
        }
        String latest = null;
        for (Map.Entry<String, byte[]> file : new LinkedHashMap<>(files).entrySet()) {
            String name = file.getKey();
            byte[] content = file.getValue();
            if (name.endsWith("playlist.m3u8") && last) {
                content = new String(content, StandardCharsets.UTF_8).replace("#EXT-X-PLAYLIST-TYPE:EVENT",
                        "#EXT-X-PLAYLIST-TYPE:VOD").getBytes(StandardCharsets.UTF_8);
            }
            String uploadKey = prefix + "/media/" + name;
            Long previous = composition.uploaded.get(uploadKey);
            if (previous != null && previous == content.length && !name.endsWith(".m3u8")) {
                continue;
            }
            try {
                s3.get().putObject(bucket, uploadKey, content, contentType(name), Map.of());
                composition.uploaded.put(uploadKey, (long) content.length);
            } catch (RuntimeException e) {
                LOG.debugv("Could not upload {0}: {1}", uploadKey, e.getMessage());
            }
            if (name.startsWith("thumbnails/") && name.endsWith(".jpg")) {
                latest = name;
            }
        }
        JsonNode thumbnails = destination.path("configuration").path("s3").path("thumbnailConfigurations").path(0);
        Set<String> storage = new HashSet<>();
        thumbnails.path("storage").forEach(s -> storage.add(s.asText()));
        if (latest != null && storage.contains("LATEST")) {
            String resolution = latest.split("/")[1];
            s3.get().putObject(bucket, prefix + "/media/latest_thumbnail/" + resolution + "/thumb.jpg",
                    files.get(latest), "image/jpeg", Map.of());
        }
        String rendition = meta(record).path("rendition-" + destination.path("id").asText()).asText("");
        if (!rendition.isEmpty()) {
            s3.get().putObject(bucket, prefix + "/media/hls/multivariant.m3u8", ("#EXTM3U\n#EXT-X-VERSION:3\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=2500000\n" + rendition + "/playlist.m3u8\n")
                    .getBytes(StandardCharsets.UTF_8), "application/x-mpegURL", Map.of());
        }
    }

    private String bucketOf(Composition composition, JsonNode destination) {
        String arn = destination.path("configuration").path("s3").path("storageConfigurationArn").asText();
        return store.get(composition.account, key(composition.region, "storage-configuration",
                arn.substring(arn.lastIndexOf('/') + 1))).map(c -> c.path("s3").path("bucketName").asText())
                .orElse("");
    }

    /** Writes a composite recording's metadata file. */
    private void writeCompositionMetadata(Composition composition, ObjectNode destination, String status,
                                          String message, long durationMs) {
        ObjectNode record = composition(composition);
        if (record == null) {
            return;
        }
        JsonNode video = encoder(composition, destination);
        int width = video.path("width").asInt(1280);
        int height = video.path("height").asInt(720);
        int fps = Math.max(1, Math.round(video.path("framerate").floatValue()));
        String rendition = height + "p" + fps + "-" + video.path("id").asText();
        ObjectNode metadata = store.mapper().createObjectNode();
        metadata.put("version", "v1").put("stage_arn", record.path("stageArn").asText())
                .put("recording_started_at", destination.path("startTime").asText(IvsStore.now()));
        if (durationMs >= 0) {
            metadata.put("recording_ended_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        metadata.put("recording_status", status);
        if (message != null) {
            metadata.put("recording_status_message", message);
        }
        ObjectNode hls = metadata.putObject("media").putObject("hls");
        if (durationMs >= 0) {
            hls.put("duration_ms", durationMs);
        }
        hls.put("path", "media/hls").put("playlist", "multivariant.m3u8");
        hls.putArray("renditions").addObject().put("path", rendition).put("playlist", "playlist.m3u8")
                .put("resolution_width", width).put("resolution_height", height);
        JsonNode thumbnails = destination.path("configuration").path("s3").path("thumbnailConfigurations").path(0);
        Set<String> storage = new HashSet<>();
        thumbnails.path("storage").forEach(s -> storage.add(s.asText()));
        for (String kind : List.of("thumbnails", "latest_thumbnail")) {
            if (!thumbnails.isMissingNode() && storage.contains("thumbnails".equals(kind) ? "SEQUENTIAL" : "LATEST")) {
                metadata.withObject("/media").putObject(kind).put("path", "media/" + kind).putArray("resolutions")
                        .addObject().put("path", width + "x" + height).put("resolution_width", width)
                        .put("resolution_height", height);
            }
        }
        String name = "RECORDING_STARTED".equals(status) ? "recording-started.json"
                : "RECORDING_ENDED".equals(status) ? "recording-ended.json" : "recording-failed.json";
        try {
            s3.get().putObject(bucketOf(composition, destination), destination.path("detail").path("s3")
                            .path("recordingPrefix").asText() + "/events/" + name,
                    metadata.toString().getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        } catch (RuntimeException e) {
            LOG.debugv("Could not write the composition metadata: {0}", e.getMessage());
        }
    }

    /** Stops a composition's processes, writes its outputs' last files, and marks it STOPPED. */
    private void finishComposition(String account, String region, String compositionId, Composition running) {
        Composition composition = running == null ? new Composition(account, region, compositionId) : running;
        composition.processes.values().forEach(media::stopProcess);
        composition.processes.clear();
        ObjectNode record = composition(composition);
        if (record == null) {
            return;
        }
        for (JsonNode destination : record.path("destinations")) {
            String state = destination.path("state").asText();
            if ("FAILED".equals(state)) {
                continue;
            }
            if (destination.path("configuration").has("s3") && media.endpoint() != null) {
                upload(composition, record, destination, true);
                long duration = playlistDuration(composition, record, destination);
                writeCompositionMetadata(composition, (ObjectNode) destination, "RECORDING_ENDED", "Recording ended",
                        duration);
            }
            ((ObjectNode) destination).put("state", "STOPPED").put("endTime", IvsStore.now());
            compositionEvent(composition, record, "Destination End", destination.path("id").asText(), null, null);
        }
        record.put("state", "STOPPED").put("endTime", IvsStore.now());
        save(composition, record);
        compositionEvent(composition, record, "Session End", null, null, null);
        if (media.endpoint() != null) {
            media.exec(List.of("sh", "-c", "rm -rf /compositions/" + compositionId));
        }
        scheduler.schedule(() -> store.get(account, key(region, "composition", compositionId)).ifPresent(c -> {
            if ("STOPPED".equals(c.path("state").asText()) || "FAILED".equals(c.path("state").asText())) {
                store.delete(account, key(region, "composition", compositionId));
            }
        }), STOPPED_COMPOSITION_MILLIS, TimeUnit.MILLISECONDS);
    }

    private long playlistDuration(Composition composition, ObjectNode record, JsonNode destination) {
        String rendition = meta(record).path("rendition-" + destination.path("id").asText()).asText("");
        String out = media.exec(List.of("sh", "-c", "awk -F: '/^#EXTINF/ {split($2, a, \",\"); s += a[1]} END "
                + "{printf \"%.3f\\n\", s}' " + compositionDir(composition, destination) + "/media/hls/" + rendition
                + "/playlist.m3u8 2>/dev/null")).output().trim();
        try {
            return Math.round(Double.parseDouble(out) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private synchronized void fail(String account, String region, String compositionId, String code, String reason) {
        Composition composition = compositions.remove(account + "|" + region + "|" + compositionId);
        Composition target = composition == null ? new Composition(account, region, compositionId) : composition;
        target.processes.values().forEach(media::stopProcess);
        ObjectNode record = composition(target);
        if (record == null) {
            return;
        }
        record.put("state", "FAILED").put("endTime", IvsStore.now());
        for (JsonNode destination : record.path("destinations")) {
            if (!"FAILED".equals(destination.path("state").asText())) {
                ((ObjectNode) destination).put("state", "FAILED").put("endTime", IvsStore.now());
            }
        }
        save(target, record);
        compositionEvent(target, record, "Session Failure", null, code, reason);
    }

    private void compositionEvent(Composition composition, ObjectNode record, String name, String destinationId,
                                  String errorCode, String reason) {
        ObjectNode detail = store.mapper().createObjectNode();
        detail.put("event_name", name).put("stage_arn", record.path("stageArn").asText());
        if (destinationId != null) {
            detail.put("id", destinationId);
        }
        if (errorCode != null) {
            detail.put("error_code", errorCode).put("reason", reason);
        }
        publish(composition.account, composition.region, "composition/" + composition.id,
                "IVS Composition State Change", detail);
    }
}
