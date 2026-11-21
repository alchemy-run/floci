package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.kinesisvideo.KvsStore.invalid;
import static io.floci.aws.services.kinesisvideo.KvsStore.meta;
import static io.floci.aws.services.kinesisvideo.KvsStore.text;

/**
 * Kinesis Video Streams' archived media: listing fragments, fragment media, MP4 clips, images, and HLS and DASH
 * playback sessions. Fragments are selected by server or producer timestamp. Clips, playback segments and images are
 * transcoded by {@link KvsTranscoder} from H.264 or H.265 fragments (with AAC, Opus or G.711 audio), which need their
 * codec private data; playback sessions are served by Floci, at the URL their session's response gives, until they
 * expire. Streams with an image generation configuration write images of their new fragments to S3 at the sampling
 * interval, and notify the notification configuration's SNS topic.
 */
@ApplicationScoped
public class KvsArchive {

    private static final Logger LOG = Logger.getLogger(KvsArchive.class);
    static final String PLAYBACK = "/_floci/kinesisvideo/playback";
    static final Set<String> VIDEO = Set.of("V_MPEG4/ISO/AVC", "V_MPEGH/ISO/HEVC");
    static final Set<String> AUDIO = Set.of("A_AAC", "A_OPUS", "A_MS/ACM", "A_PCM/INT/LIT");
    static final int MAX_CLIP_FRAGMENTS = 1000;

    /** A playback session: its stream and how it selects fragments. */
    record Session(String account, String region, String stream, String kind, String mode, String selectorType,
                   double start, double end, String container, long max, long expires, String discontinuity,
                   boolean displayTimestamp) {
    }

    private final KvsStore store;
    private final KvsService service;
    private final KvsMedia media;
    private final KvsTranscoder transcoder;
    private final Instance<S3Service> s3;
    private final Instance<SnsService> sns;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kinesisvideo-images");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public KvsArchive(KvsStore store, KvsService service, KvsMedia media, KvsTranscoder transcoder,
                      Instance<S3Service> s3, Instance<SnsService> sns) {
        this.store = store;
        this.service = service;
        this.media = media;
        this.transcoder = transcoder;
        this.s3 = s3;
        this.sns = sns;
        media.onFragment(this::generateImages);
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private ObjectNode stream(String account, String region, JsonNode in) {
        ObjectNode stream = service.activeStream(account, region, in);
        return stream;
    }

    private static void requireRetention(ObjectNode stream) {
        if (stream.path("DataRetentionInHours").asInt() == 0) {
            throw new AwsException("NoDataRetentionException", "The stream " + stream.path("StreamName").asText()
                    + " has no data retention.", 400);
        }
    }

    /** The fragments in a timestamp range, by server or producer time, in time order. */
    private List<ObjectNode> select(String account, String region, ObjectNode stream, String type, double start,
                                    double end) {
        String field = "PRODUCER_TIMESTAMP".equals(type) ? "ProducerTimestamp" : "ServerTimestamp";
        List<ObjectNode> selected = new ArrayList<>();
        for (ObjectNode fragment : media.fragments(account, region, stream)) {
            double t = fragment.path(field).asDouble();
            if (t >= start && t <= end) {
                selected.add(fragment);
            }
        }
        selected.sort((a, b) -> Double.compare(a.path(field).asDouble(), b.path(field).asDouble()));
        return selected;
    }

    // ---------------------------------------------------------------- ListFragments

    public ObjectNode listFragments(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        List<ObjectNode> fragments;
        JsonNode selector = in.get("FragmentSelector");
        if (selector == null || selector.isNull()) {
            fragments = media.fragments(account, region, stream);
        } else {
            double start = selector.path("TimestampRange").path("StartTimestamp").asDouble();
            double end = selector.path("TimestampRange").path("EndTimestamp").asDouble();
            if (end < start) {
                throw invalid("The EndTimestamp is before the StartTimestamp.");
            }
            fragments = select(account, region, stream, selector.path("FragmentSelectorType").asText(), start, end);
        }
        int offset = 0;
        String token = text(in, "NextToken");
        if (token != null) {
            try {
                offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        long max = in.hasNonNull("MaxResults") ? in.get("MaxResults").asLong() : 1000;
        int end = (int) Math.min(fragments.size(), offset + max);
        ObjectNode out = mapper().createObjectNode();
        ArrayNode list = out.putArray("Fragments");
        for (ObjectNode fragment : fragments.subList(Math.min(offset, fragments.size()), end)) {
            list.add(KvsStore.view(fragment));
        }
        if (end < fragments.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    // ---------------------------------------------------------------- fragment media and clips

    /** The Matroska of a list of fragments, in the order given. */
    public byte[] getMediaForFragmentList(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        JsonNode numbers = in.path("Fragments");
        if (numbers.isEmpty() || numbers.size() > 1000) {
            throw invalid("Fragments must list between 1 and 1000 fragment numbers.");
        }
        List<ObjectNode> found = new ArrayList<>();
        Map<String, ObjectNode> byNumber = new HashMap<>();
        media.fragments(account, region, stream).forEach(f -> byNumber.put(f.path("FragmentNumber").asText(), f));
        for (JsonNode number : numbers) {
            ObjectNode fragment = byNumber.get(number.asText());
            if (fragment == null) {
                throw invalid("Fragment numbers are invalid: " + number.asText() + " is not in the stream.");
            }
            found.add(fragment);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        found.forEach(f -> out.writeBytes(KvsMedia.document(f, null)));
        return out.toByteArray();
    }

    private static void checkCodecs(List<ObjectNode> fragments) {
        for (ObjectNode fragment : fragments) {
            List<String> codecs = new ArrayList<>();
            meta(fragment).path("codecs").forEach(c -> codecs.add(c.asText()));
            if (codecs.stream().noneMatch(VIDEO::contains)) {
                throw new AwsException("UnsupportedStreamMediaTypeException", "The stream's media is " + codecs
                        + "; clips and playback need H.264 or H.265 video.", 400);
            }
            for (String codec : codecs) {
                if (!VIDEO.contains(codec) && !AUDIO.contains(codec)) {
                    throw new AwsException("UnsupportedStreamMediaTypeException", "The codec " + codec
                            + " is not supported for clips and playback.", 400);
                }
            }
            if (!meta(fragment).path("codecPrivate").asBoolean(false)) {
                throw new AwsException("MissingCodecPrivateDataException", "The stream's tracks have no codec "
                        + "private data.", 400);
            }
        }
    }

    /** Fragments joined into one Matroska file with continuous timecodes, for ffmpeg. */
    private static byte[] joined(List<ObjectNode> fragments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean first = true;
        for (ObjectNode fragment : fragments) {
            byte[] plain = KvsMedia.plain(fragment);
            if (first) {
                out.writeBytes(plain);
                first = false;
            } else {
                out.writeBytes(Base64.getDecoder().decode(meta(fragment).path("cluster").asText()));
            }
        }
        return out.toByteArray();
    }

    public byte[] getClip(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        requireRetention(stream);
        JsonNode selector = in.path("ClipFragmentSelector");
        double start = selector.path("TimestampRange").path("StartTimestamp").asDouble();
        double end = selector.path("TimestampRange").path("EndTimestamp").asDouble();
        if (end <= start) {
            throw invalid("The clip's EndTimestamp must be after its StartTimestamp.");
        }
        if (end - start > 24 * 3600) {
            throw invalid("A clip can span at most 24 hours.");
        }
        List<ObjectNode> fragments = select(account, region, stream, selector.path("FragmentSelectorType").asText(),
                start, end);
        if (fragments.isEmpty()) {
            throw KvsMedia.noFragments("clip");
        }
        if (fragments.size() > MAX_CLIP_FRAGMENTS) {
            fragments = fragments.subList(0, MAX_CLIP_FRAGMENTS);
        }
        checkCodecs(fragments);
        try {
            return transcoder.run(Map.of("in.mkv", joined(fragments)), "ffmpeg -nostdin -loglevel error -i in.mkv "
                    + "-c copy -movflags +faststart -f mp4 out.mp4").get("out.mp4");
        } catch (KvsTranscoder.TranscodeException e) {
            throw new AwsException("InvalidMediaFrameException", "The stream's media can't be read: "
                    + e.getMessage(), 400);
        }
    }

    // ---------------------------------------------------------------- images

    public ObjectNode getImages(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        requireRetention(stream);
        double start = in.path("StartTimestamp").asDouble();
        double end = in.path("EndTimestamp").asDouble();
        if (end <= start) {
            throw invalid("The EndTimestamp must be after the StartTimestamp.");
        }
        int interval = in.path("SamplingInterval").asInt(3000);
        if (interval < 200 || interval > 20_000) {
            throw invalid("SamplingInterval must be between 200 and 20000 milliseconds.");
        }
        if (in.has("FormatConfig") && "PNG".equals(text(in, "Format"))) {
            throw invalid("FormatConfig is only for JPEG images.");
        }
        List<ObjectNode> fragments = select(account, region, stream, text(in, "ImageSelectorType"), start, end);
        if (fragments.isEmpty()) {
            throw KvsMedia.noFragments("images");
        }
        checkCodecs(fragments);
        long max = in.hasNonNull("MaxResults") ? in.get("MaxResults").asLong() : 25;
        double from = start;
        String token = text(in, "NextToken");
        if (token != null) {
            try {
                from = Double.parseDouble(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        List<Double> times = new ArrayList<>();
        for (double t = from; t <= end && times.size() < max; t += interval / 1000.0) {
            times.add(t);
        }
        ObjectNode out = mapper().createObjectNode();
        ArrayNode images = out.putArray("Images");
        String field = "PRODUCER_TIMESTAMP".equals(text(in, "ImageSelectorType")) ? "ProducerTimestamp"
                : "ServerTimestamp";
        Map<String, String> rendered = render(fragments, field, times, text(in, "Format"), in.path("WidthPixels")
                .asInt(0), in.path("HeightPixels").asInt(0), in.path("FormatConfig").path("JPEGQuality").asInt(80));
        for (int i = 0; i < times.size(); i++) {
            ObjectNode image = images.addObject().put("TimeStamp", times.get(i));
            String content = rendered.get(String.valueOf(i));
            if (content == null) {
                image.put("Error", "NO_MEDIA");
            } else {
                image.put("ImageContent", content);
            }
        }
        double next = times.isEmpty() ? end : times.get(times.size() - 1) + interval / 1000.0;
        if (next <= end && times.size() == max) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(next)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    /** Images at each time, by index, base64; a time no fragment covers has none. */
    private Map<String, String> render(List<ObjectNode> fragments, String field, List<Double> times, String format,
                                       int width, int height, int quality) {
        Map<String, String> out = new HashMap<>();
        StringBuilder script = new StringBuilder("set -e; ");
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        String ext = "PNG".equals(format) ? "png" : "jpg";
        String scale = width > 0 || height > 0 ? " -vf scale=" + (width > 0 ? width : -2) + ":"
                + (height > 0 ? height : -2) : "";
        int q = Math.max(2, 31 - (int) Math.round(quality * 29 / 100.0));
        for (int i = 0; i < times.size(); i++) {
            double t = times.get(i);
            ObjectNode covering = null;
            for (ObjectNode fragment : fragments) {
                double s = fragment.path(field).asDouble();
                double e = s + fragment.path("FragmentLengthInMilliseconds").asLong() / 1000.0;
                if (t >= s - 0.001 && t <= Math.max(e, s + 0.001)) {
                    covering = fragment;
                }
            }
            if (covering == null) {
                continue;
            }
            String file = "f" + covering.path("FragmentNumber").asText() + ".mkv";
            inputs.putIfAbsent(file, KvsMedia.plain(covering));
            double offset = Math.max(0, t - covering.path(field).asDouble());
            script.append("ffmpeg -nostdin -loglevel error -ss ").append(String.format(Locale.ROOT, "%.3f", offset))
                    .append(" -i ").append(file).append(" -frames:v 1").append(scale)
                    .append("jpg".equals(ext) ? " -q:v " + q : "").append(" -y img").append(i).append('.')
                    .append(ext).append(" || true; ");
        }
        if (inputs.isEmpty()) {
            return out;
        }
        try {
            Map<String, byte[]> files = transcoder.run(inputs, script.toString());
            files.forEach((name, bytes) -> {
                if (name.startsWith("img") && bytes.length > 0) {
                    out.put(name.substring(3, name.indexOf('.')), Base64.getEncoder().encodeToString(bytes));
                }
            });
        } catch (KvsTranscoder.TranscodeException e) {
            LOG.debugf("Kinesis Video images failed: %s", e.getMessage());
        }
        return out;
    }

    /** Writes images of a stream's new fragments to S3, as its image generation configuration asks. */
    private void generateImages(String key) {
        String[] parts = key.split("/", 3);
        imageWorker.submit(() -> RequestScopes.runAs(parts[0], parts[1], () -> {
            try {
                ObjectNode stream = store.get(parts[0], parts[1], KvsStore.STREAM, parts[2]).orElse(null);
                if (stream == null) {
                    return;
                }
                JsonNode config = meta(stream).get("images");
                if (config == null || !"ENABLED".equals(config.path("Status").asText())) {
                    return;
                }
                List<ObjectNode> fragments = media.fragments(parts[0], parts[1], stream);
                if (fragments.isEmpty()) {
                    return;
                }
                ObjectNode fragment = fragments.get(fragments.size() - 1);
                String field = "PRODUCER_TIMESTAMP".equals(config.path("ImageSelectorType").asText())
                        ? "ProducerTimestamp" : "ServerTimestamp";
                double startTime = fragment.path(field).asDouble();
                double endTime = startTime + fragment.path("FragmentLengthInMilliseconds").asLong() / 1000.0;
                double since = meta(stream).path("lastImage").asDouble(0);
                double interval = config.path("SamplingInterval").asInt(3000) / 1000.0;
                List<Double> times = new ArrayList<>();
                for (double t = Math.max(startTime, since + interval); t <= endTime; t += interval) {
                    times.add(t);
                }
                if (times.isEmpty()) {
                    return;
                }
                String format = config.path("Format").asText("JPEG");
                Map<String, String> images = render(List.of(fragment), field, times, format,
                        config.path("WidthPixels").asInt(0), config.path("HeightPixels").asInt(0),
                        config.path("FormatConfig").path("JPEGQuality").asInt(80));
                String uri = config.path("DestinationConfig").path("Uri").asText();
                String bucket = uri.substring(5).split("/", 2)[0];
                String prefix = uri.substring(5).contains("/") ? uri.substring(5).split("/", 2)[1] : "";
                prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
                String ext = "PNG".equals(format) ? "png" : "jpg";
                for (Map.Entry<String, String> image : images.entrySet()) {
                    double t = times.get(Integer.parseInt(image.getKey()));
                    String objectKey = prefix + parts[2] + "/" + fragment.path("FragmentNumber").asText() + "/"
                            + Instant.ofEpochMilli((long) (t * 1000)) + "." + ext;
                    s3.get().putObject(bucket, objectKey, Base64.getDecoder().decode(image.getValue()),
                            "PNG".equals(format) ? "image/png" : "image/jpeg", Map.of());
                    notify(parts[0], parts[1], stream, bucket, objectKey, fragment, t);
                }
                meta(stream).put("lastImage", times.get(times.size() - 1));
                store.put(parts[0], parts[1], KvsStore.STREAM, parts[2], stream);
            } catch (RuntimeException e) {
                LOG.debugf("Kinesis Video image generation failed: %s", e.getMessage());
            }
        }));
    }

    private void notify(String account, String region, ObjectNode stream, String bucket, String key,
                        ObjectNode fragment, double time) {
        JsonNode notification = meta(stream).get("notification");
        if (notification == null || !"ENABLED".equals(notification.path("Status").asText())) {
            return;
        }
        ObjectNode message = mapper().createObjectNode();
        message.put("StreamArn", stream.path("StreamARN").asText()).put("FragmentNumber",
                fragment.path("FragmentNumber").asText()).put("FragmentStartProducerTimestamp",
                (long) (fragment.path("ProducerTimestamp").asDouble() * 1000)).put("FragmentStartServerTimestamp",
                (long) (fragment.path("ServerTimestamp").asDouble() * 1000)).put("NotificationType",
                "IMAGE_GENERATED");
        message.putObject("S3Object").put("Bucket", bucket).put("Key", key).put("ImageTimestamp",
                (long) (time * 1000));
        try {
            sns.get().publish(notification.path("DestinationConfig").path("Uri").asText(), null, message.toString(),
                    null, region);
        } catch (RuntimeException e) {
            LOG.debugf("Kinesis Video notification failed: %s", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- playback sessions

    public ObjectNode getHlsStreamingSessionUrl(JsonNode in, String region) {
        return session(in, region, "hls");
    }

    public ObjectNode getDashStreamingSessionUrl(JsonNode in, String region) {
        return session(in, region, "dash");
    }

    private ObjectNode session(JsonNode in, String region, String kind) {
        String account = store.account();
        ObjectNode stream = stream(account, region, in);
        String mode = in.hasNonNull("PlaybackMode") ? text(in, "PlaybackMode") : "LIVE";
        JsonNode selector = in.path("hls".equals(kind) ? "HLSFragmentSelector" : "DASHFragmentSelector");
        String type = selector.path("FragmentSelectorType").asText("SERVER_TIMESTAMP");
        JsonNode range = selector.path("TimestampRange");
        double start = range.path("StartTimestamp").asDouble(0);
        double end = range.path("EndTimestamp").asDouble(Double.MAX_VALUE);
        if ("LIVE".equals(mode) && range.has("StartTimestamp")) {
            throw invalid("LIVE playback takes no TimestampRange.");
        }
        if ("ON_DEMAND".equals(mode) && (!range.has("StartTimestamp") || !range.has("EndTimestamp"))) {
            throw invalid("ON_DEMAND playback needs a StartTimestamp and an EndTimestamp.");
        }
        if ("LIVE_REPLAY".equals(mode) && !range.has("StartTimestamp")) {
            throw invalid("LIVE_REPLAY playback needs a StartTimestamp.");
        }
        if (!"LIVE".equals(mode)) {
            requireRetention(stream);
        }
        List<ObjectNode> fragments = "LIVE".equals(mode) ? media.fragments(account, region, stream)
                : select(account, region, stream, type, start, end);
        if ("LIVE".equals(mode)) {
            double recent = System.currentTimeMillis() / 1000.0 - 30;
            fragments = fragments.stream().filter(f -> f.path("ServerTimestamp").asDouble() >= recent).toList();
        }
        if (fragments.isEmpty()) {
            throw KvsMedia.noFragments("streaming");
        }
        checkCodecs(fragments);
        int expires = in.path("Expires").asInt(300);
        long max = in.path("hls".equals(kind) ? "MaxMediaPlaylistFragmentResults" : "MaxManifestFragmentResults")
                .asLong("LIVE".equals(mode) ? 5 : 1000);
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(account, region, stream.path("StreamName").asText(), kind, mode, type, start,
                end, "MPEG_TS".equals(text(in, "ContainerFormat")) ? "ts" : "mp4", max,
                System.currentTimeMillis() + expires * 1000L, text(in, "DiscontinuityMode"),
                "ALWAYS".equals(text(in, "DisplayFragmentTimestamp"))));
        String base = service.endpoint() + PLAYBACK + "/" + kind + "/" + token + "/";
        ObjectNode out = mapper().createObjectNode();
        if ("hls".equals(kind)) {
            out.put("HLSStreamingSessionURL", base + "getHLSMasterPlaylist.m3u8");
        } else {
            out.put("DASHStreamingSessionURL", base + "getDASHManifest.mpd");
        }
        return out;
    }

    Session session(String token) {
        Session session = sessions.get(token);
        if (session == null || session.expires() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    /** The fragments a session plays now. */
    List<ObjectNode> playlist(Session s) {
        ObjectNode stream = store.get(s.account(), s.region(), KvsStore.STREAM, s.stream()).orElse(null);
        if (stream == null) {
            return List.of();
        }
        List<ObjectNode> fragments = "LIVE".equals(s.mode()) ? media.fragments(s.account(), s.region(), stream)
                : select(s.account(), s.region(), stream, s.selectorType(), s.start(), s.end());
        if ("LIVE".equals(s.mode()) && fragments.size() > s.max()) {
            fragments = fragments.subList(fragments.size() - (int) s.max(), fragments.size());
        } else if (fragments.size() > s.max()) {
            fragments = fragments.subList(0, (int) s.max());
        }
        return fragments;
    }

    ObjectNode fragment(Session s, String number) {
        return store.fragment(s.account(), s.region(), s.stream(), number).orElse(null);
    }

    String hlsMaster(String token) {
        return "#EXTM3U\n#EXT-X-VERSION:" + ("ts".equals(sessions.get(token).container()) ? 3 : 7)
                + "\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\ngetHLSMediaPlaylist.m3u8\n";
    }

    String hlsMedia(Session s) {
        List<ObjectNode> fragments = playlist(s);
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append("ts".equals(s.container()) ? 3 : 7)
                .append('\n');
        long target = 1;
        for (ObjectNode f : fragments) {
            target = Math.max(target, (f.path("FragmentLengthInMilliseconds").asLong() + 999) / 1000);
        }
        out.append("#EXT-X-TARGETDURATION:").append(target).append('\n');
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(fragments.isEmpty() ? 0
                : Math.abs(fragments.get(0).path("FragmentNumber").asText().hashCode() % 100_000)).append('\n');
        if (!"LIVE".equals(s.mode())) {
            out.append("#EXT-X-PLAYLIST-TYPE:").append("ON_DEMAND".equals(s.mode()) ? "VOD" : "EVENT").append('\n');
        }
        if ("mp4".equals(s.container()) && !fragments.isEmpty()) {
            out.append("#EXT-X-MAP:URI=\"getMP4InitFragment.mp4?FragmentNumber=")
                    .append(fragments.get(0).path("FragmentNumber").asText()).append("\"\n");
        }
        for (ObjectNode f : fragments) {
            if ("ALWAYS".equals(s.discontinuity())) {
                out.append("#EXT-X-DISCONTINUITY\n");
            }
            if (s.displayTimestamp()) {
                out.append("#EXT-X-PROGRAM-DATE-TIME:").append(Instant.ofEpochMilli((long) (f.path(
                        "PRODUCER_TIMESTAMP".equals(s.selectorType()) ? "ProducerTimestamp" : "ServerTimestamp")
                        .asDouble() * 1000))).append('\n');
            }
            out.append(String.format(Locale.ROOT, "#EXTINF:%.3f,%n", f.path("FragmentLengthInMilliseconds")
                    .asLong() / 1000.0));
            out.append("ts".equals(s.container()) ? "getTSFragment.ts?FragmentNumber="
                    : "getMP4MediaFragment.mp4?FragmentNumber=").append(f.path("FragmentNumber").asText())
                    .append('\n');
        }
        if ("ON_DEMAND".equals(s.mode())) {
            out.append("#EXT-X-ENDLIST\n");
        }
        return out.toString();
    }

    String dashManifest(Session s) {
        List<ObjectNode> fragments = playlist(s);
        double total = 0;
        for (ObjectNode f : fragments) {
            total += f.path("FragmentLengthInMilliseconds").asLong() / 1000.0;
        }
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" ")
                .append("LIVE".equals(s.mode()) ? "type=\"dynamic\" minimumUpdatePeriod=\"PT2S\" availabilityStartTime=\""
                        + Instant.ofEpochMilli(0) + "\"" : "type=\"static\" mediaPresentationDuration=\"PT"
                        + String.format(Locale.ROOT, "%.3f", total) + "S\"")
                .append(" minBufferTime=\"PT2S\">\n<Period id=\"0\" start=\"PT0S\">\n")
                .append("<AdaptationSet mimeType=\"video/mp4\" segmentAlignment=\"true\">\n")
                .append("<Representation id=\"0\" bandwidth=\"2000000\">\n");
        long average = fragments.isEmpty() ? 1000 : Math.max(1, Math.round(total * 1000 / fragments.size()));
        out.append("<SegmentList timescale=\"1000\" duration=\"").append(average).append("\">\n");
        if (!fragments.isEmpty()) {
            out.append("<Initialization sourceURL=\"getMP4InitFragment.mp4?FragmentNumber=")
                    .append(fragments.get(0).path("FragmentNumber").asText()).append("\"/>\n");
        }
        out.append("<SegmentTimeline>\n");
        long t = 0;
        for (ObjectNode f : fragments) {
            long d = Math.max(1, f.path("FragmentLengthInMilliseconds").asLong());
            out.append("<S t=\"").append(t).append("\" d=\"").append(d).append("\"/>\n");
            t += d;
        }
        out.append("</SegmentTimeline>\n");
        for (ObjectNode f : fragments) {
            out.append("<SegmentURL media=\"getMP4MediaFragment.mp4?FragmentNumber=")
                    .append(f.path("FragmentNumber").asText()).append("\"/>\n");
        }
        out.append("</SegmentList>\n</Representation>\n</AdaptationSet>\n</Period>\n</MPD>\n");
        return out.toString();
    }

    /** A fragment as a playback segment: {@code init} (ftyp and moov), {@code media} (moof and mdat) or {@code ts}. */
    byte[] segment(Session s, String number, String part) {
        ObjectNode fragment = fragment(s, number);
        if (fragment == null) {
            return null;
        }
        String cacheKey = s.account() + "/" + s.region() + "/" + s.stream() + "/" + number + "/" + part;
        byte[] cached = segments.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        byte[] result;
        // Each fragment is transcoded alone; its timecode in the producer's session places it on one timeline.
        String offset = String.format(Locale.ROOT, "%.6f", meta(fragment).path("timecode").asLong()
                * meta(fragment).path("scale").asLong(1_000_000) / 1e9);
        if ("ts".equals(part)) {
            result = transcoder.run(Map.of("in.mkv", KvsMedia.plain(fragment)), "ffmpeg -nostdin -loglevel error "
                    + "-i in.mkv -c copy -output_ts_offset " + offset + " -f mpegts out.ts").get("out.ts");
        } else {
            byte[] mp4 = transcoder.run(Map.of("in.mkv", KvsMedia.plain(fragment)), "ffmpeg -nostdin -loglevel "
                    + "error -i in.mkv -c copy -output_ts_offset " + offset + " -movflags "
                    + "frag_keyframe+empty_moov+default_base_moof -f mp4 out.mp4").get("out.mp4");
            result = split(mp4, "init".equals(part));
            if ("media".equals(part)) {
                long timescale = timescale(split(mp4, true));
                shiftDecodeTime(result, Math.round(Double.parseDouble(offset) * timescale));
            }
        }
        if (segments.size() > 500) {
            segments.clear();
        }
        segments.put(cacheKey, result);
        return result;
    }

    /** The first track's timescale, from its {@code mdhd} box. */
    static long timescale(byte[] init) {
        int at = indexOf(init, "mdhd", 0);
        if (at < 0) {
            return 1000;
        }
        int version = init[at + 4] & 0xff;
        int offset = at + 4 + 4 + (version == 1 ? 16 : 8);
        return ByteBuffer.wrap(init, offset, 4).getInt() & 0xffffffffL;
    }

    private static int indexOf(byte[] data, String type, int from) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = Math.max(4, from); i + 4 <= data.length; i++) {
            for (int k = 0; k < 4; k++) {
                if (data[i + k] != t[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Adds to the base media decode time ({@code tfdt}) of each track fragment of the movie fragments. */
    static void shiftDecodeTime(byte[] media, long delta) {
        int at = 0;
        while (at + 8 <= media.length) {
            int size = ByteBuffer.wrap(media, at, 4).getInt();
            String type = new String(media, at + 4, 4, StandardCharsets.US_ASCII);
            if (size < 8) {
                return;
            }
            if ("moof".equals(type)) {
                int end = at + size;
                int tfdt = indexOf(media, "tfdt", at + 8);
                while (tfdt > 0 && tfdt < end) {
                    int version = media[tfdt + 4] & 0xff;
                    ByteBuffer b = ByteBuffer.wrap(media, tfdt + 8, version == 1 ? 8 : 4);
                    if (version == 1) {
                        long value = b.getLong(tfdt + 8);
                        ByteBuffer.wrap(media).putLong(tfdt + 8, value + delta);
                    } else {
                        long value = b.getInt(tfdt + 8) & 0xffffffffL;
                        ByteBuffer.wrap(media).putInt(tfdt + 8, (int) (value + delta));
                    }
                    tfdt = indexOf(media, "tfdt", tfdt + 4);
                }
            }
            at += size;
        }
    }

    /** The ftyp and moov boxes of a fragmented MP4 ({@code init}), or the boxes after them. */
    static byte[] split(byte[] mp4, boolean init) {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        int at = 0;
        while (at + 8 <= mp4.length) {
            long size = ByteBuffer.wrap(mp4, at, 4).getInt() & 0xffffffffL;
            String type = new String(mp4, at + 4, 4, StandardCharsets.US_ASCII);
            if (size == 1) {
                size = ByteBuffer.wrap(mp4, at + 8, 8).getLong();
            } else if (size == 0) {
                size = mp4.length - at;
            }
            int length = (int) Math.min(size, mp4.length - at);
            ("ftyp".equals(type) || "moov".equals(type) ? head : rest).write(mp4, at, length);
            at += length;
        }
        return init ? head.toByteArray() : rest.toByteArray();
    }
}
