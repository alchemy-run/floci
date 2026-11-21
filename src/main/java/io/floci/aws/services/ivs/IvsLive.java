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
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
 * What is live on IVS channels: the media server's publishing paths read every poll interval and turned into stream
 * sessions with their ingest configuration and events, stream health (a stream is STARVING once no bytes have
 * arrived for three seconds), viewer counts, and the {@code aws.ivs} events EventBridge receives. A channel with a
 * recording configuration is recorded: its segments become the HLS recording, thumbnails and metadata files of the
 * recording's S3 prefix when the recording ends, a reconnect window after the stream ends.
 */
@ApplicationScoped
public class IvsLive implements Resettable {

    private static final Logger LOG = Logger.getLogger(IvsLive.class);
    private static final long STARVATION_MILLIS = 3000;
    private static final int TRUNCATED_EVENTS = 500;

    /** A live stream: its session, path, health and counters. */
    static final class Live {
        final String account;
        final String region;
        final String channelId;
        final String streamId;
        final String path;
        final Instant started;
        long bytes;
        long bytesAt;
        long firstBytes;
        String health = "HEALTHY";
        int viewers;
        Recording recording;

        Live(String account, String region, String channelId, String streamId, String path, long bytes) {
            this.account = account;
            this.region = region;
            this.channelId = channelId;
            this.streamId = streamId;
            this.path = path;
            this.started = Instant.now();
            this.bytes = bytes;
            this.firstBytes = bytes;
            this.bytesAt = System.currentTimeMillis();
        }
    }

    /** A recording: its S3 prefix, the streams merged into it, and the finalization a reconnect window delays. */
    static final class Recording {
        final String id;
        final String bucket;
        final String prefix;
        final Instant started;
        final ObjectNode configuration;
        final String rendition;
        final int width;
        final int height;
        final List<String> streamIds = new ArrayList<>();
        ScheduledFuture<?> pending;

        Recording(String id, String bucket, String prefix, ObjectNode configuration, String rendition, int width,
                  int height) {
            this.id = id;
            this.bucket = bucket;
            this.prefix = prefix;
            this.started = Instant.now();
            this.configuration = configuration;
            this.rendition = rendition;
            this.width = width;
            this.height = height;
        }
    }

    private final IvsStore store;
    private final IvsMediaServer media;
    private final Instance<EventBridgeService> events;
    private final Instance<S3Service> s3;
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    /** The recording of each channel that has ended but may still take a reconnecting stream. */
    private final Map<String, Recording> ending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivs-live");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService finalizer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ivs-recordings");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean polling;

    @Inject
    public IvsLive(IvsStore store, IvsMediaServer media, Instance<EventBridgeService> events, Instance<S3Service> s3) {
        this.store = store;
        this.media = media;
        this.events = events;
        this.s3 = s3;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        finalizer.shutdownNow();
    }

    private static String channelKey(String account, String region, String channelId) {
        return account + "|" + region + "|" + channelId;
    }

    /** Starts polling the media server, once. */
    synchronized void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        long interval = Math.max(200, store.config().services().ivs().pollMillis());
        scheduler.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            LOG.debugv("IVS live poll failed: {0}", e.getMessage());
        }
    }

    /** The live stream of a channel, or null when it is not broadcasting. */
    Live stream(String account, String region, String channelId) {
        return live.get(channelKey(account, region, channelId));
    }

    List<Live> streams(String account, String region) {
        List<Live> found = new ArrayList<>();
        live.values().forEach(stream -> {
            if (stream.account.equals(account) && stream.region.equals(region)) {
                found.add(stream);
            }
        });
        found.sort((a, b) -> a.started.compareTo(b.started));
        return found;
    }

    /** Reads the media server and moves every channel's stream on: sessions start and end, health changes. */
    synchronized void poll() {
        if (media.endpoint() == null) {
            return;
        }
        Map<String, String[]> keys = new LinkedHashMap<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(key -> key.contains(
                "/stream-key/"))) {
            ObjectNode streamKey = entry.value();
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            keys.put(IvsMediaServer.pathOf(streamKey.path("value").asText()), new String[] {entry.accountId(),
                region, meta(streamKey).path("channelId").asText()});
        }
        Map<String, Integer> viewers = media.viewers();
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (JsonNode path : media.livePaths()) {
            String name = path.path("name").asText();
            String[] owner = keys.get(name);
            if (owner == null || !path.path("ready").asBoolean(false)) {
                continue;
            }
            String channelKey = channelKey(owner[0], owner[1], owner[2]);
            seen.add(channelKey);
            long bytes = path.path("bytesReceived").asLong(path.path("inboundBytes").asLong());
            Live stream = live.get(channelKey);
            if (stream == null) {
                stream = begin(owner[0], owner[1], owner[2], name, path, bytes);
                if (stream == null) {
                    continue;
                }
                live.put(channelKey, stream);
            }
            stream.viewers = viewers.getOrDefault(name, 0);
            if (bytes > stream.bytes) {
                stream.bytes = bytes;
                stream.bytesAt = now;
                if ("STARVING".equals(stream.health)) {
                    stream.health = "HEALTHY";
                    event(stream, "IVS Stream Health Change", "Starvation End", null);
                }
            } else if (now - stream.bytesAt >= STARVATION_MILLIS && !"STARVING".equals(stream.health)) {
                stream.health = "STARVING";
                event(stream, "IVS Stream Health Change", "Starvation Start", null);
            }
        }
        for (Map.Entry<String, Live> entry : new ArrayList<>(live.entrySet())) {
            if (!seen.contains(entry.getKey())) {
                live.remove(entry.getKey());
                end(entry.getValue());
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    private Live begin(String account, String region, String channelId, String path, JsonNode media, long bytes) {
        ObjectNode channel = store.get(account, key(region, "channel", channelId)).orElse(null);
        if (channel == null) {
            return null;
        }
        String streamId = "st-" + IvsStore.id(22);
        Live stream = new Live(account, region, channelId, streamId, path, bytes);
        ObjectNode session = store.mapper().createObjectNode();
        session.put("streamId", streamId).put("startTime", IvsStore.now());
        ObjectNode snapshot = store.view(channel);
        session.set("channel", snapshot);
        ObjectNode ingest = ingestConfiguration(media);
        session.set("ingestConfiguration", ingest);
        ObjectNode configurations = session.putObject("ingestConfigurations");
        configurations.putArray("videoConfigurations").add(ingest.path("video").deepCopy());
        configurations.putArray("audioConfigurations").add(ingest.path("audio").deepCopy());
        session.putArray("truncatedEvents");
        session.putObject("_").put("kind", "session").put("seq", System.nanoTime());
        String recordingArn = channel.path("recordingConfigurationArn").asText("");
        ObjectNode recordingConfiguration = recordingArn.isEmpty() ? null
                : store.get(account, key(region, "recording-configuration", recordingArn.substring(
                        recordingArn.lastIndexOf('/') + 1))).orElse(null);
        if (recordingConfiguration != null) {
            session.set("recordingConfiguration", store.view(recordingConfiguration));
        }
        save(stream, session);
        sessionEvent(stream, session, "Session Created", "IVS Stream State Change");
        sessionEvent(stream, session, "Stream Start", "IVS Stream State Change");
        if (recordingConfiguration != null && "ACTIVE".equals(recordingConfiguration.path("state").asText())) {
            startRecording(stream, session, channel, recordingConfiguration, ingest);
        }
        save(stream, session);
        return stream;
    }

    private void end(Live stream) {
        ObjectNode session = store.get(stream.account, sessionKey(stream)).orElse(null);
        if (session != null) {
            session.put("endTime", IvsStore.now());
            sessionEvent(stream, session, "Session Ended", "IVS Stream State Change");
            sessionEvent(stream, session, "Stream End", "IVS Stream State Change");
            long seconds = Math.max(1, (System.currentTimeMillis() - stream.started.toEpochMilli()) / 1000);
            long bitrate = (stream.bytes - stream.firstBytes) * 8 / seconds;
            session.withObject("/ingestConfiguration").withObject("/video").put("targetBitrate", bitrate);
            meta(session).put("bytes", stream.bytes - stream.firstBytes);
            save(stream, session);
        }
        Recording recording = stream.recording;
        if (recording != null) {
            int window = recording.configuration.path("recordingReconnectWindowSeconds").asInt(0);
            String channelKey = channelKey(stream.account, stream.region, stream.channelId);
            if (window > 0) {
                ending.put(channelKey, recording);
                recording.pending = scheduler.schedule(() -> {
                    if (ending.remove(channelKey, recording)) {
                        finalizer.execute(() -> finish(stream, recording));
                    }
                }, window, TimeUnit.SECONDS);
            } else {
                finalizer.execute(() -> finish(stream, recording));
            }
        }
    }

    private static String sessionKey(Live stream) {
        return key(stream.region, "session", stream.channelId + "/" + stream.streamId);
    }

    private void save(Live stream, ObjectNode session) {
        store.put(stream.account, sessionKey(stream), session);
    }

    /** Records a session event and publishes it. */
    private void sessionEvent(Live stream, ObjectNode session, String name, String type) {
        ArrayNode events = session.withArray("/truncatedEvents");
        if (events.size() < TRUNCATED_EVENTS) {
            events.addObject().put("name", name).put("type", type).put("eventTime", IvsStore.now());
        }
        event(stream, type, name, null);
    }

    /** The ingest configuration a stream's tracks show. */
    private ObjectNode ingestConfiguration(JsonNode path) {
        ObjectNode ingest = store.mapper().createObjectNode();
        ObjectNode video = ingest.putObject("video");
        ObjectNode audio = ingest.putObject("audio");
        for (JsonNode track : path.path("tracks2")) {
            String codec = track.path("codec").asText();
            JsonNode props = track.path("codecProps");
            switch (codec) {
                case "H264", "H265", "AV1", "VP9" -> {
                    video.put("codec", switch (codec) {
                        case "H264" -> "avc1";
                        case "H265" -> "hvc1";
                        case "AV1" -> "av01";
                        default -> "vp09";
                    }).put("encoder", "").put("videoWidth", props.path("width").asLong())
                            .put("videoHeight", props.path("height").asLong()).put("track", "Track0");
                    if (props.hasNonNull("profile")) {
                        video.put("avcProfile", props.get("profile").asText()).put("profile",
                                props.get("profile").asText());
                    }
                    if (props.hasNonNull("level")) {
                        video.put("avcLevel", props.get("level").asText()).put("level", props.get("level").asText());
                    }
                }
                case "MPEG-4 Audio", "Opus", "MPEG-1/2 Audio" -> audio.put("codec", switch (codec) {
                    case "MPEG-4 Audio" -> "mp4a.40.2";
                    case "Opus" -> "opus";
                    default -> "mp3";
                }).put("sampleRate", props.path("sampleRate").asLong()).put("channels",
                        props.path("channelCount").asLong()).put("track", "Track0");
                default -> {
                }
            }
        }
        return ingest;
    }

    /** Lists a channel's sessions, newest first. */
    List<ObjectNode> sessions(String region, String channelId) {
        List<ObjectNode> sessions = store.list(key(region, "session", channelId + "/"));
        sessions.sort((a, b) -> b.path("startTime").asText().compareTo(a.path("startTime").asText()));
        return sessions;
    }

    /** Records an event on a live stream's session: metadata, an ad break. */
    void sessionEvent(Live stream, String name, String type) {
        store.get(stream.account, sessionKey(stream)).ifPresent(session -> {
            ArrayNode events = session.withArray("/truncatedEvents");
            if (events.size() < TRUNCATED_EVENTS) {
                events.addObject().put("name", name).put("type", type).put("eventTime", IvsStore.now());
            }
            save(stream, session);
        });
    }

    /** Disconnects a channel's broadcaster. */
    boolean stop(Live stream) {
        boolean kicked = media.kick(stream.path);
        poll();
        return kicked;
    }

    // ---------------------------------------------------------------- events

    void event(Live stream, String detailType, String eventName, ObjectNode extra) {
        ObjectNode detail = store.mapper().createObjectNode();
        detail.put("event_name", eventName);
        ObjectNode channel = store.get(stream.account, key(stream.region, "channel", stream.channelId)).orElse(null);
        detail.put("channel_name", channel == null ? "" : channel.path("name").asText(""))
                .put("stream_id", stream.streamId);
        if (extra != null) {
            detail.setAll(extra);
        }
        publish(stream, detailType, detail);
    }

    private void publish(Live stream, String detailType, ObjectNode detail) {
        String arn = IvsStore.arnFor(stream.account, stream.region, "channel/" + stream.channelId);
        try {
            events.get().putEvents(List.of(EventBridgeService.serviceEventEntry("aws.ivs", detailType,
                    detail.toString(), arn)), stream.region, stream.account);
        } catch (RuntimeException e) {
            LOG.debugv("Could not publish the IVS {0} event: {1}", detailType, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- recordings

    private void startRecording(Live stream, ObjectNode session, ObjectNode channel, ObjectNode configuration,
                                ObjectNode ingest) {
        String channelKey = channelKey(stream.account, stream.region, stream.channelId);
        Recording previous = ending.remove(channelKey);
        if (previous != null && previous.pending != null && previous.pending.cancel(false)
                && previous.streamIds.size() < 20
                && previous.width == ingest.path("video").path("videoWidth").asInt()
                && previous.height == ingest.path("video").path("videoHeight").asInt()) {
            previous.streamIds.add(stream.streamId);
            stream.recording = previous;
            sessionEvent(stream, session, "Recording Start", "IVS Recording State Change");
            recordingEvent(stream, previous, "Recording Start", "", 0);
            return;
        }
        if (previous != null) {
            Recording finished = previous;
            finalizer.execute(() -> finish(stream, finished));
        }
        String bucket = configuration.path("destinationConfiguration").path("s3").path("bucketName").asText();
        String recordingId = IvsStore.id(12);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String prefix = "ivs/v1/" + stream.account + "/" + stream.channelId + "/" + now.getYear() + "/"
                + now.getMonthValue() + "/" + now.getDayOfMonth() + "/" + now.getHour() + "/" + now.getMinute()
                + "/" + recordingId;
        int width = ingest.path("video").path("videoWidth").asInt();
        int height = ingest.path("video").path("videoHeight").asInt();
        Recording recording = new Recording(recordingId, bucket, prefix, store.view(configuration).deepCopy(),
                height + "p30", width, height);
        recording.streamIds.add(stream.streamId);
        if (!s3.get().bucketExists(bucket)) {
            recordingEvent(stream, recording, "Recording Start Failure", "ValidationException", 0);
            return;
        }
        media.configurePath(stream.path.substring("app/".length()), true,
                channel.path("srt").path("passphrase").asText(null));
        stream.recording = recording;
        try {
            put(recording, "events/recording-started.json", metadata(stream, recording, "RECORDING_STARTED", null,
                    -1).toString());
        } catch (AwsException e) {
            recordingEvent(stream, recording, "Recording Start Failure", e.getErrorCode(), 0);
            stream.recording = null;
            return;
        }
        sessionEvent(stream, session, "Recording Start", "IVS Recording State Change");
        recordingEvent(stream, recording, "Recording Start", "", 0);
    }

    private void recordingEvent(Live stream, Recording recording, String status, String reason, long durationMs) {
        ObjectNode detail = store.mapper().createObjectNode();
        ObjectNode channel = store.get(stream.account, key(stream.region, "channel", stream.channelId)).orElse(null);
        detail.put("channel_name", channel == null ? "" : channel.path("name").asText(""))
                .put("stream_id", stream.streamId).put("recording_status", status)
                .put("recording_status_reason", reason).put("recording_s3_bucket_name", recording.bucket)
                .put("recording_s3_key_prefix", status.endsWith("Start Failure") ? "" : recording.prefix)
                .put("recording_duration_ms", durationMs).put("recording_session_id", recording.id);
        if ("Recording End".equals(status)) {
            ArrayNode ids = detail.putArray("recording_session_stream_ids");
            recording.streamIds.forEach(ids::add);
        }
        publish(stream, "IVS Recording State Change", detail);
    }

    /** The JSON metadata file of a recording, as IVS writes it to {@code events/}. */
    private ObjectNode metadata(Live stream, Recording recording, String status, String message, long durationMs) {
        ObjectNode metadata = store.mapper().createObjectNode();
        metadata.put("version", "v1").put("channel_arn", IvsStore.arnFor(stream.account, stream.region,
                "channel/" + stream.channelId));
        if (!"RECORDING_STARTED".equals(status)) {
            metadata.put("recording_ended_at", IvsStore.now());
        }
        metadata.put("recording_started_at", recording.started.toString()).put("recording_status", status);
        if (message != null) {
            metadata.put("recording_status_message", message);
        }
        ObjectNode media = metadata.putObject("media");
        ObjectNode hls = media.putObject("hls");
        if (durationMs >= 0) {
            hls.put("duration_ms", durationMs);
        }
        hls.put("path", "media/hls").put("playlist", "master.m3u8")
                .put("byte_range_playlist", "byte-range-multivariant.m3u8");
        hls.putArray("renditions").addObject().put("path", recording.rendition).put("playlist", "playlist.m3u8")
                .put("byte_range_playlist", "byte-range-variant.m3u8")
                .put("resolution_height", recording.height).put("resolution_width", recording.width);
        JsonNode thumbnails = recording.configuration.path("thumbnailConfiguration");
        int thumbnailHeight = thumbnailHeight(thumbnails, recording.height);
        int thumbnailWidth = recording.height == 0 ? 0 : recording.width * thumbnailHeight / recording.height;
        if ("INTERVAL".equals(thumbnails.path("recordingMode").asText("INTERVAL"))) {
            if (storage(thumbnails).contains("SEQUENTIAL")) {
                media.putObject("thumbnails").put("path", "media/thumbnails")
                        .put("resolution_height", thumbnailHeight).put("resolution_width", thumbnailWidth);
            }
            if (storage(thumbnails).contains("LATEST")) {
                media.putObject("latest_thumbnail").put("path", "media/latest_thumbnail/thumb.jpg")
                        .put("resolution_height", thumbnailHeight).put("resolution_width", thumbnailWidth);
            }
        }
        return metadata;
    }

    private static Set<String> storage(JsonNode thumbnails) {
        Set<String> storage = new HashSet<>();
        thumbnails.path("storage").forEach(entry -> storage.add(entry.asText()));
        if (storage.isEmpty()) {
            storage.add("SEQUENTIAL");
        }
        return storage;
    }

    private static int thumbnailHeight(JsonNode thumbnails, int source) {
        int cap = switch (thumbnails.path("resolution").asText("")) {
            case "LOWEST_RESOLUTION" -> 360;
            case "SD" -> 480;
            case "HD" -> 720;
            case "FULL_HD" -> 1080;
            default -> source;
        };
        return Math.min(source, cap) - Math.min(source, cap) % 2;
    }

    /**
     * Ends a recording: its segments are joined into the rendition's HLS playlists (segmented and byte-range),
     * thumbnails are taken at the configured interval, everything is written under the recording's prefix with the
     * master playlists and {@code events/recording-ended.json}, and the segments are removed.
     */
    private void finish(Live stream, Recording recording) {
        String work = "/tmp/rec-" + recording.id;
        long durationMs = 0;
        String failure = null;
        List<String> segments = media.recordedSegments(stream.path);
        try {
            if (segments.isEmpty()) {
                throw new IllegalStateException("No media was recorded.");
            }
            JsonNode thumbnails = recording.configuration.path("thumbnailConfiguration");
            int interval = Math.max(1, thumbnails.path("targetIntervalSeconds").asInt(60));
            int height = thumbnailHeight(thumbnails, recording.height);
            StringBuilder script = new StringBuilder("set -e; mkdir -p " + work + "/hls/" + recording.rendition
                    + " " + work + "/thumbnails " + work + "/latest_thumbnail; cd " + work + "; : > list.txt; ");
            for (String segment : segments) {
                script.append("echo \"file '").append(segment).append("'\" >> list.txt; ");
            }
            String hls = "hls/" + recording.rendition;
            script.append("ffmpeg -nostdin -loglevel error -f concat -safe 0 -i list.txt -c copy -f hls -hls_time 10 "
                    + "-hls_playlist_type vod -hls_segment_filename " + hls + "/%d.ts " + hls + "/playlist.m3u8; ");
            script.append("ffmpeg -nostdin -loglevel error -f concat -safe 0 -i list.txt -c copy -f hls -hls_time 10 "
                    + "-hls_playlist_type vod -hls_flags single_file " + hls + "/byte-range-variant.m3u8; ");
            if ("INTERVAL".equals(thumbnails.path("recordingMode").asText("INTERVAL")) && height > 0) {
                script.append("ffmpeg -nostdin -loglevel error -f concat -safe 0 -i list.txt -vf fps=1/")
                        .append(interval).append(",scale=-2:").append(height)
                        .append(" -q:v 3 thumbnails/thumb%d.jpg; ");
                script.append("last=$(ls -1v thumbnails/*.jpg 2>/dev/null | tail -1); "
                        + "if [ -n \"$last\" ]; then cp \"$last\" latest_thumbnail/thumb.jpg; fi; ");
            }
            // The recording lasts as long as its playlist's segments: their EXTINF durations summed.
            script.append("awk -F: '/^#EXTINF/ {split($2, a, \",\"); s += a[1]} END {printf \"%.3f\\n\", s}' ")
                    .append(hls).append("/playlist.m3u8");
            IvsMediaServer.ExecResult result = media.exec(List.of("sh", "-c", script.toString()));
            if (result.code() != 0) {
                throw new IllegalStateException("The recording could not be packaged: " + result.output().trim());
            }
            String[] lines = result.output().trim().split("\n");
            durationMs = Math.round(Double.parseDouble(lines[lines.length - 1].trim()) * 1000);
            Map<String, byte[]> files = media.copyOut(work);
            Set<String> storage = storage(thumbnails);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                String name = file.getKey();
                if (name.equals("list.txt") || (name.startsWith("thumbnails/") && !storage.contains("SEQUENTIAL"))
                        || (name.startsWith("latest_thumbnail/") && !storage.contains("LATEST"))) {
                    continue;
                }
                put(recording, "media/" + name, file.getValue(), contentType(name));
            }
            String variant = "#EXT-X-STREAM-INF:BANDWIDTH=" + bandwidth(stream) + ",RESOLUTION=" + recording.width
                    + "x" + recording.height + "\n";
            put(recording, "media/hls/master.m3u8", ("#EXTM3U\n#EXT-X-VERSION:3\n" + variant + recording.rendition
                    + "/playlist.m3u8\n").getBytes(StandardCharsets.UTF_8), "application/x-mpegURL");
            put(recording, "media/hls/byte-range-multivariant.m3u8", ("#EXTM3U\n#EXT-X-VERSION:4\n" + variant
                    + recording.rendition + "/byte-range-variant.m3u8\n").getBytes(StandardCharsets.UTF_8),
                    "application/x-mpegURL");
        } catch (IllegalStateException | AwsException | NumberFormatException e) {
            failure = e.getMessage();
            LOG.warnv("The IVS recording {0} failed: {1}", recording.id, failure);
        } finally {
            StringBuilder cleanup = new StringBuilder("rm -rf " + work);
            segments.forEach(segment -> cleanup.append(" '").append(segment).append('\''));
            media.exec(List.of("sh", "-c", cleanup.toString()));
        }
        try {
            if (failure == null) {
                put(recording, "events/recording-ended.json", metadata(stream, recording, "RECORDING_ENDED", null,
                        durationMs).toString());
                recordingEvent(stream, recording, "Recording End", "", durationMs);
            } else {
                put(recording, "events/recording-failed.json", metadata(stream, recording,
                        "RECORDING_ENDED_WITH_FAILURE", failure, durationMs).toString());
                recordingEvent(stream, recording, "Recording End Failure", "InternalServerException", durationMs);
            }
        } catch (AwsException e) {
            recordingEvent(stream, recording, "Recording End Failure", e.getErrorCode(), durationMs);
        }
    }

    private static long bandwidth(Live stream) {
        long seconds = Math.max(1, (System.currentTimeMillis() - stream.started.toEpochMilli()) / 1000);
        return Math.max(1, (stream.bytes - stream.firstBytes) * 8 / seconds);
    }

    private static String contentType(String name) {
        if (name.endsWith(".m3u8")) {
            return "application/x-mpegURL";
        }
        if (name.endsWith(".ts")) {
            return "video/MP2T";
        }
        return name.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
    }

    private void put(Recording recording, String name, String content) {
        put(recording, name, content.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void put(Recording recording, String name, byte[] content, String type) {
        s3.get().putObject(recording.bucket, recording.prefix + "/" + name, content, type, Map.of());
    }

    /** Forgets the live state of a deleted channel. */
    void forget(String account, String region, String channelId) {
        live.remove(channelKey(account, region, channelId));
        Recording recording = ending.remove(channelKey(account, region, channelId));
        if (recording != null && recording.pending != null) {
            recording.pending.cancel(false);
        }
    }

    @Override
    public void clear() {
        live.clear();
        ending.clear();
        try {
            media.stopManagedContainers();
        } catch (RuntimeException e) {
            LOG.warnv("Failed to stop the IVS media server during reset: {0}", e.getMessage());
        }
    }
}
