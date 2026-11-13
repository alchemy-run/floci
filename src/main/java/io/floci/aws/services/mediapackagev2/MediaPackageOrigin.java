package io.floci.aws.services.mediapackagev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaPackage v2's origin. A channel's two ingest endpoints take HLS by {@code PUT}: segments and playlists, as an
 * encoder (MediaLive's HLS group to the ingest URL) writes them. The renditions the media playlists list are kept for
 * the longest startover window of the channel's origin endpoints (or the configured minimum), the newer pipeline's
 * content served. Origin endpoints serve what was ingested: a TS endpoint's HLS manifests repackage the TS segments as
 * they are; a CMAF endpoint's HLS and DASH manifests serve them remuxed to fragmented MP4 with ffmpeg (renditions stay
 * muxed). Manifests take their window, filter times and start tag; content is served from the endpoint's reset time
 * on. ISM (Microsoft Smooth) endpoints aren't packaged. Harvest jobs write a window's segments and manifests to S3.
 */
@ApplicationScoped
public class MediaPackageOrigin {

    private static final Logger LOG = Logger.getLogger(MediaPackageOrigin.class);
    static final String DNS_SUFFIX = "localhost.floci.io";
    static final String PATH = "/_floci/mediapackagev2";
    private static final Pattern EXTINF = Pattern.compile("#EXTINF:([0-9.]+)");
    private static final Pattern MAP = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"");
    private static final Pattern STREAM = Pattern.compile("#EXT-X-STREAM-INF:(.*)");

    private final MediaPackageStore store;
    private final EmulatorConfig config;
    private final Provider<KvsTranscoder> ffmpeg;
    private final Provider<S3Service> s3;
    private final Map<String, Pipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<String, byte[][]> remuxed = new ConcurrentHashMap<>();

    /** One ingested segment. */
    record Segment(String uri, double duration, long sequence, double end, byte[] data, String map) {
        double start() {
            return end - duration;
        }
    }

    /** One rendition: its segments in order and the attributes its multivariant entry gave. */
    static final class Rendition {
        final String name;
        final List<Segment> segments = new ArrayList<>();
        String attributes = "";
        long next;

        Rendition(String name) {
            this.name = name;
        }
    }

    /** One ingest pipeline of a channel: the files written to it and the renditions they make. */
    static final class Pipeline {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, Rendition> renditions = new LinkedHashMap<>();
        volatile double updated;
    }

    @Inject
    public MediaPackageOrigin(MediaPackageStore store, EmulatorConfig config, Provider<KvsTranscoder> ffmpeg,
                              Provider<S3Service> s3) {
        this.store = store;
        this.config = config;
        this.ffmpeg = ffmpeg;
        this.s3 = s3;
    }

    // ================================================================ URLs

    private String base() {
        return config.effectiveBaseUrl().replaceAll("/+$", "");
    }

    /** Whether Floci is reached on localhost, so the wildcard DNS name of a host reaches it too. */
    private boolean wildcard() {
        URI endpoint = URI.create(base());
        return List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())
                && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()));
    }

    private String hostUrl(String host) {
        URI endpoint = URI.create(base());
        return endpoint.getScheme() + "://" + host + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
    }

    String ingestUrl(String region, ObjectNode channel, int index) {
        String id = MediaPackageStore.meta(channel).path("ingestId").asText();
        String path = "/in/v1/" + channel.path("ChannelGroupName").asText() + "/" + index + "/"
                + channel.path("ChannelName").asText() + "/";
        if (wildcard()) {
            return hostUrl(id + "-" + index + ".ingest.mediapackagev2." + region + "." + DNS_SUFFIX) + path;
        }
        return base() + PATH + "/ingest/" + region + "/" + id + "-" + index + path;
    }

    /** The URL an origin endpoint's manifests are under, ending in a slash. */
    String egressBase(String region, ObjectNode endpoint) {
        String path = "/out/v1/" + endpoint.path("ChannelGroupName").asText() + "/" + endpoint.path("ChannelName")
                .asText() + "/" + endpoint.path("OriginEndpointName").asText() + "/";
        ObjectNode group = store.get(region, MediaPackageStore.GROUP, endpoint.path("ChannelGroupName").asText())
                .orElse(null);
        String host = group == null ? "" : group.path("EgressDomain").asText();
        if (wildcard()) {
            return hostUrl(host) + path;
        }
        return base() + PATH + "/egress/" + region + "/" + host.substring(0, Math.max(0, host.indexOf('.'))) + path;
    }

    // ================================================================ ingest

    private static String key(String account, String region, String group, String channel, int index) {
        return account + "/" + region + "/" + group + "/" + channel + "/" + index;
    }

    void forget(String account, String region, String group, String channel) {
        for (int index = 1; index <= 2; index++) {
            pipelines.remove(key(account, region, group, channel, index));
        }
        String prefix = account + "/" + region + "/" + group + "/" + channel + "/";
        remuxed.keySet().removeIf(cached -> cached.startsWith(prefix));
    }

    /** Takes a file written to an ingest endpoint; a media playlist adds its new segments to its rendition. */
    void ingest(String account, String region, String group, String channel, int index, String path, byte[] body) {
        ObjectNode record = store.getForAccount(account, region, MediaPackageStore.CHANNEL,
                MediaPackageService.channelKey(group, channel)).orElseThrow(() -> MediaPackageStore.notFound(
                "CHANNEL", "The channel " + channel + " doesn't exist."));
        if ("MULTIVIEW".equals(record.path("InputType").asText())) {
            throw MediaPackageStore.validation("MEMBER_INVALID", "A MULTIVIEW channel takes no ingest.");
        }
        Pipeline pipeline = pipelines.computeIfAbsent(key(account, region, group, channel, index), k ->
                new Pipeline());
        synchronized (pipeline) {
            pipeline.files.put(path, body);
            String text = path.endsWith(".m3u8") ? new String(body, StandardCharsets.UTF_8) : null;
            String root = "/in/v1/" + group + "/" + index + "/" + channel + "/";
            if (text != null && text.contains("#EXTINF")) {
                playlist(pipeline, path, text, root);
            } else if (text != null && text.contains("#EXT-X-STREAM-INF")) {
                variants(pipeline, path, text, root);
            } else if (text != null && text.contains("#EXT-X-TARGETDURATION")) {
                pipeline.renditions.computeIfAbsent(path, Rendition::new);
            }
            pipeline.updated = MediaPackageStore.epoch();
            trim(account, region, group, channel, pipeline);
            if (text != null && LOG.isDebugEnabled()) {
                StringBuilder counts = new StringBuilder();
                pipeline.renditions.forEach((name, rendition) -> counts.append(name).append('=')
                        .append(rendition.segments.size()).append(' '));
                LOG.debugv("MediaPackage v2 ingested {0} of {1}/{2} pipeline {3}: {4}; renditions {5}", path, group,
                        channel, index, text.replace('\n', '|'), counts);
            }
        }
    }

    void remove(String account, String region, String group, String channel, int index, String path) {
        Pipeline pipeline = pipelines.get(key(account, region, group, channel, index));
        if (pipeline != null) {
            pipeline.files.remove(path);
        }
    }

    /**
     * A playlist's URI as a file of the pipeline: relative to the playlist, or, for an absolute URL or path (an
     * encoder writing to the ingest URL lists its segments by it), relative to the channel's ingest root
     * ({@code /in/v1/<group>/<n>/<channel>/}).
     */
    private static String resolve(String playlist, String uri, String root) {
        String path = uri;
        if (uri.contains("://")) {
            path = URI.create(uri).getRawPath();
        }
        if (path.startsWith("/")) {
            int at = path.indexOf(root);
            return at >= 0 ? path.substring(at + root.length()) : path.substring(path.lastIndexOf('/') + 1);
        }
        String dir = playlist.contains("/") ? playlist.substring(0, playlist.lastIndexOf('/') + 1) : "";
        return dir + path;
    }

    private void playlist(Pipeline pipeline, String path, String text, String root) {
        Rendition rendition = pipeline.renditions.computeIfAbsent(path, Rendition::new);
        double now = MediaPackageStore.epoch();
        String map = null;
        double duration = 0;
        List<String[]> listed = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher extinf = EXTINF.matcher(line);
            Matcher initMap = MAP.matcher(line);
            if (initMap.find()) {
                map = resolve(path, initMap.group(1), root);
            } else if (extinf.find()) {
                duration = Double.parseDouble(extinf.group(1));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                listed.add(new String[] {resolve(path, line.trim(), root), String.valueOf(duration), map});
            }
        }
        List<String> known = new ArrayList<>();
        rendition.segments.forEach(segment -> known.add(segment.uri()));
        int fresh = 0;
        for (String[] entry : listed) {
            if (!known.contains(entry[0])) {
                fresh++;
            }
        }
        double pending = 0;
        for (String[] entry : listed) {
            if (!known.contains(entry[0])) {
                pending += Double.parseDouble(entry[1]);
            }
        }
        double cursor = now - pending;
        for (String[] entry : listed) {
            if (known.contains(entry[0])) {
                continue;
            }
            byte[] data = pipeline.files.get(entry[0]);
            if (data == null) {
                continue;
            }
            double length = Double.parseDouble(entry[1]);
            cursor += length;
            rendition.segments.add(new Segment(entry[0], length, rendition.next++, cursor, data, entry[2]));
        }
        if (fresh > 0) {
            pipeline.updated = now;
        }
    }

    private static void variants(Pipeline pipeline, String path, String text, String root) {
        String attributes = null;
        for (String line : text.split("\\r?\\n")) {
            Matcher stream = STREAM.matcher(line);
            if (stream.find()) {
                attributes = stream.group(1);
            } else if (attributes != null && !line.isBlank() && !line.startsWith("#")) {
                pipeline.renditions.computeIfAbsent(resolve(path, line.trim(), root), Rendition::new).attributes =
                        attributes;
                attributes = null;
            }
        }
    }

    /** Drops segments older than the channel's longest startover window (or the configured minimum). */
    private void trim(String account, String region, String group, String channel, Pipeline pipeline) {
        int keep = store.config().minimumRetentionSeconds();
        for (ObjectNode endpoint : store.listForAccount(account, region, MediaPackageStore.ENDPOINT)) {
            if (group.equals(endpoint.path("ChannelGroupName").asText()) && channel.equals(endpoint.path(
                    "ChannelName").asText())) {
                keep = Math.max(keep, endpoint.path("StartoverWindowSeconds").asInt(0));
                for (JsonNode manifest : endpoint.findValues("ManifestWindowSeconds")) {
                    keep = Math.max(keep, manifest.asInt());
                }
            }
        }
        double cutoff = MediaPackageStore.epoch() - keep;
        for (Rendition rendition : pipeline.renditions.values()) {
            while (!rendition.segments.isEmpty() && rendition.segments.getFirst().end() < cutoff) {
                Segment dropped = rendition.segments.removeFirst();
                pipeline.files.remove(dropped.uri());
                remuxed.remove(dropped.uri() + "@" + System.identityHashCode(pipeline));
            }
        }
    }

    /** The pipeline whose content is newest, of a channel. */
    private Pipeline current(String account, String region, String group, String channel) {
        Pipeline best = null;
        for (int index = 1; index <= 2; index++) {
            Pipeline candidate = pipelines.get(key(account, region, group, channel, index));
            if (candidate != null && !candidate.renditions.isEmpty() && (best == null
                    || candidate.updated > best.updated)) {
                best = candidate;
            }
        }
        return best;
    }

    // ================================================================ egress

    /** A served file: its bytes and content type. */
    record Served(byte[] body, String type) {
    }

    private static AwsException missing(String what) {
        return MediaPackageStore.notFound("ORIGIN_ENDPOINT", what);
    }

    /** The segments of a rendition an origin endpoint serves for a manifest, in its window and filter. */
    private static List<Segment> window(Rendition rendition, ObjectNode endpoint, JsonNode manifest, boolean harvest,
                                        double from, double to) {
        double now = MediaPackageStore.epoch();
        double reset = endpoint.path("ResetAt").asDouble(0);
        JsonNode filter = manifest.path("FilterConfiguration");
        double delay = filter.path("TimeDelaySeconds").asDouble(0);
        double start = filter.has("Start") ? filter.get("Start").asDouble() : Double.NEGATIVE_INFINITY;
        double end = filter.has("End") ? filter.get("End").asDouble() : Double.POSITIVE_INFINITY;
        double windowStart = harvest ? from : now - delay - manifest.path("ManifestWindowSeconds").asDouble(60);
        double windowEnd = harvest ? to : now - delay;
        List<Segment> chosen = new ArrayList<>();
        for (Segment segment : rendition.segments) {
            if (segment.start() >= reset && segment.end() > windowStart && segment.start() < windowEnd
                    && segment.end() > start && segment.start() < end) {
                chosen.add(segment);
            }
        }
        return chosen;
    }

    private static JsonNode manifest(ObjectNode endpoint, String name, String extension) {
        List<String> kinds = switch (extension) {
            case "m3u8" -> List.of("HlsManifests", "LowLatencyHlsManifests");
            case "mpd" -> List.of("DashManifests");
            default -> List.of("MssManifests");
        };
        for (String kind : kinds) {
            for (JsonNode manifest : endpoint.path(kind)) {
                if (name.equals(manifest.path("ManifestName").asText())) {
                    return manifest;
                }
            }
        }
        return null;
    }

    /** Serves a file of an origin endpoint: a manifest, child manifest, init section or segment. */
    Served egress(String account, String region, String group, String channel, String endpointName, String file) {
        ObjectNode endpoint = store.getForAccount(account, region, MediaPackageStore.ENDPOINT,
                MediaPackageService.endpointKey(group, channel, endpointName)).orElseThrow(() -> missing(
                "The origin endpoint " + endpointName + " doesn't exist."));
        String container = endpoint.path("ContainerType").asText();
        for (JsonNode condition : endpoint.path("ForceEndpointErrorConfiguration").path("EndpointErrorConditions")) {
            if ("STALE_MANIFEST".equals(condition.asText()) || "INCOMPLETE_MANIFEST".equals(condition.asText())) {
                Pipeline pipeline = current(account, region, group, channel);
                if (pipeline == null || MediaPackageStore.epoch() - pipeline.updated > 30) {
                    throw missing("The channel's input is stale.");
                }
            }
        }
        if ("ISM".equals(container)) {
            throw new AwsException("NotImplemented", "Floci doesn't package Microsoft Smooth (ISM) endpoints.", 501);
        }
        Pipeline pipeline = current(account, region, group, channel);
        if (pipeline == null) {
            throw missing("The channel has no content.");
        }
        List<Rendition> renditions = new ArrayList<>(pipeline.renditions.values());
        renditions.removeIf(rendition -> rendition.segments.isEmpty());
        if (renditions.isEmpty()) {
            throw missing("The channel has no content: " + pipeline.files.size() + " files were ingested, in "
                    + pipeline.renditions.size() + " renditions " + pipeline.renditions.keySet() + " with no "
                    + "segments.");
        }
        String segmentName = endpoint.path("Segment").path("SegmentName").asText("segment");
        String separator = "HYPHEN".equals(endpoint.path("UriSeparator").asText()) ? "-" : "_";
        int dot = file.lastIndexOf('.');
        String stem = dot < 0 ? file : file.substring(0, dot);
        String extension = dot < 0 ? "" : file.substring(dot + 1);
        JsonNode multivariant = manifest(endpoint, stem, extension);
        if (multivariant != null && "m3u8".equals(extension)) {
            return new Served(multivariant(endpoint, multivariant, renditions, separator).getBytes(
                    StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
        }
        if (multivariant != null && "mpd".equals(extension)) {
            if (!"CMAF".equals(container)) {
                throw missing("DASH manifests need a CMAF origin endpoint.");
            }
            return new Served(dash(pipeline, endpoint, multivariant, renditions, segmentName, separator)
                    .getBytes(StandardCharsets.UTF_8), "application/dash+xml");
        }
        if ("m3u8".equals(extension)) {
            for (String kind : List.of("HlsManifests", "LowLatencyHlsManifests")) {
                for (JsonNode manifest : endpoint.path(kind)) {
                    String child = manifest.path("ChildManifestName").asText(manifest.path("ManifestName").asText());
                    if (stem.startsWith(child + separator)) {
                        int index = Integer.parseInt(stem.substring(child.length() + 1)) - 1;
                        if (index < 0 || index >= renditions.size()) {
                            throw missing("The rendition " + (index + 1) + " doesn't exist.");
                        }
                        return new Served(media(pipeline, endpoint, manifest, renditions.get(index), index,
                                segmentName, separator).getBytes(StandardCharsets.UTF_8),
                                "application/vnd.apple.mpegurl");
                    }
                }
            }
        }
        if (stem.startsWith(segmentName + separator)) {
            String[] parts = stem.substring(segmentName.length() + 1).split(Pattern.quote(separator));
            int index = Integer.parseInt(parts[0]) - 1;
            if (index < 0 || index >= renditions.size()) {
                throw missing("The rendition " + (index + 1) + " doesn't exist.");
            }
            Rendition rendition = renditions.get(index);
            if ("init".equals(parts[1])) {
                return new Served(remux(pipeline, rendition.segments.getLast())[0], "video/mp4");
            }
            long sequence = Long.parseLong(parts[1]);
            for (Segment segment : rendition.segments) {
                if (segment.sequence() == sequence) {
                    return "CMAF".equals(container) ? new Served(remux(pipeline, segment)[1], "video/mp4")
                            : new Served(segment.data(), "video/MP2T");
                }
            }
            throw missing("The segment " + file + " is outside the window.");
        }
        throw missing("The origin endpoint has no file " + file + ".");
    }

    private static String multivariant(ObjectNode endpoint, JsonNode manifest, List<Rendition> renditions,
                                       String separator) {
        boolean cmaf = "CMAF".equals(endpoint.path("ContainerType").asText());
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(cmaf ? 6 : 3)
                .append("\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        if (manifest.has("StartTag")) {
            out.append("#EXT-X-START:TIME-OFFSET=").append(manifest.path("StartTag").path("TimeOffset").asDouble())
                    .append(manifest.path("StartTag").path("Precise").asBoolean(false) ? ",PRECISE=YES" : "")
                    .append('\n');
        }
        String child = manifest.path("ChildManifestName").asText(manifest.path("ManifestName").asText());
        for (int i = 0; i < renditions.size(); i++) {
            Rendition rendition = renditions.get(i);
            String attributes = rendition.attributes;
            if (attributes.isEmpty()) {
                long bytes = 0;
                double seconds = 0;
                for (Segment segment : rendition.segments) {
                    bytes += segment.data().length;
                    seconds += segment.duration();
                }
                attributes = "BANDWIDTH=" + Math.max(1, Math.round(bytes * 8 / Math.max(seconds, 0.001)));
            }
            out.append("#EXT-X-STREAM-INF:").append(attributes).append('\n').append(child).append(separator)
                    .append(i + 1).append(".m3u8\n");
        }
        return out.toString();
    }

    private String media(Pipeline pipeline, ObjectNode endpoint, JsonNode manifest, Rendition rendition, int index,
                         String segmentName, String separator) {
        List<Segment> segments = window(rendition, endpoint, manifest, false, 0, 0);
        return mediaPlaylist(pipeline, endpoint, manifest, segments, index, segmentName, separator, false);
    }

    private String mediaPlaylist(Pipeline pipeline, ObjectNode endpoint, JsonNode manifest, List<Segment> segments,
                                 int index, String segmentName, String separator, boolean ended) {
        if (segments.isEmpty()) {
            throw missing("The manifest's window has no content.");
        }
        boolean cmaf = "CMAF".equals(endpoint.path("ContainerType").asText());
        double target = 0;
        for (Segment segment : segments) {
            target = Math.max(target, segment.duration());
        }
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(cmaf ? 6 : 3)
                .append("\n#EXT-X-TARGETDURATION:").append((long) Math.ceil(target))
                .append("\n#EXT-X-MEDIA-SEQUENCE:").append(segments.getFirst().sequence()).append('\n');
        if (ended) {
            out.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        }
        if (cmaf) {
            out.append("#EXT-X-MAP:URI=\"").append(segmentName).append(separator).append(index + 1).append(separator)
                    .append("init.mp4\"\n");
        }
        int interval = manifest.path("ProgramDateTimeIntervalSeconds").asInt(0);
        double lastStamp = Double.NEGATIVE_INFINITY;
        for (Segment segment : segments) {
            if (interval > 0 && segment.start() - lastStamp >= interval) {
                out.append("#EXT-X-PROGRAM-DATE-TIME:").append(DateTimeFormatter.ISO_INSTANT.format(Instant
                        .ofEpochMilli((long) (segment.start() * 1000)))).append('\n');
                lastStamp = segment.start();
            }
            out.append(String.format(Locale.ROOT, "#EXTINF:%.3f,%n", segment.duration())).append(segmentName)
                    .append(separator).append(index + 1).append(separator).append(segment.sequence())
                    .append(cmaf ? ".mp4" : ".ts").append('\n');
        }
        if (ended) {
            out.append("#EXT-X-ENDLIST\n");
        }
        return out.toString();
    }

    private String dash(Pipeline pipeline, ObjectNode endpoint, JsonNode manifest, List<Rendition> renditions,
                        String segmentName, String separator) {
        StringBuilder sets = new StringBuilder();
        double availability = Double.POSITIVE_INFINITY;
        for (int i = 0; i < renditions.size(); i++) {
            List<Segment> segments = window(renditions.get(i), endpoint, manifest, false, 0, 0);
            if (segments.isEmpty()) {
                continue;
            }
            availability = Math.min(availability, segments.getFirst().start());
            StringBuilder timeline = new StringBuilder();
            long t = Math.round(segments.getFirst().start() * 90000);
            for (Segment segment : segments) {
                long d = Math.round(segment.duration() * 90000);
                timeline.append("<S t=\"").append(t).append("\" d=\"").append(d).append("\"/>");
                t += d;
            }
            String bandwidth = renditions.get(i).attributes.replaceAll(".*BANDWIDTH=(\\d+).*", "$1");
            sets.append("<AdaptationSet id=\"").append(i + 1).append("\" segmentAlignment=\"true\" mimeType=")
                    .append("\"video/mp4\"><Representation id=\"").append(i + 1).append("\" bandwidth=\"")
                    .append(bandwidth.matches("\\d+") ? bandwidth : "1000000").append("\"><SegmentTemplate ")
                    .append("timescale=\"90000\" initialization=\"").append(segmentName).append(separator)
                    .append(i + 1).append(separator).append("init.mp4\" media=\"").append(segmentName)
                    .append(separator).append(i + 1).append(separator).append("$Number$.mp4\" startNumber=\"")
                    .append(segments.getFirst().sequence()).append("\" presentationTimeOffset=\"0\">")
                    .append("<SegmentTimeline>").append(timeline).append("</SegmentTimeline></SegmentTemplate>")
                    .append("</Representation></AdaptationSet>");
        }
        if (sets.isEmpty()) {
            throw missing("The manifest's window has no content.");
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" "
                + "profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" type=\"dynamic\" availabilityStartTime=\""
                + DateTimeFormatter.ISO_INSTANT.format(Instant.EPOCH) + "\" publishTime=\"" + now
                + "\" minimumUpdatePeriod=\"PT" + manifest.path("MinUpdatePeriodSeconds").asInt(2)
                + "S\" minBufferTime=\"PT" + manifest.path("MinBufferTimeSeconds").asInt(2)
                + "S\" timeShiftBufferDepth=\"PT" + manifest.path("ManifestWindowSeconds").asInt(60)
                + "S\" suggestedPresentationDelay=\"PT" + manifest.path("SuggestedPresentationDelaySeconds").asInt(10)
                + "S\"><Period id=\"0\" start=\"PT0S\">" + sets + "</Period></MPD>\n";
    }

    /** A segment remuxed to fragmented MP4: its init section and its fragment; cached. */
    private byte[][] remux(Pipeline pipeline, Segment segment) {
        String key = segment.uri() + "@" + System.identityHashCode(pipeline);
        byte[][] cached = remuxed.get(key);
        if (cached != null) {
            return cached;
        }
        boolean ts = segment.data().length > 0 && segment.data()[0] == 0x47;
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        String script;
        if (ts) {
            inputs.put("in.ts", segment.data());
            script = "ffmpeg -hide_banner -loglevel error -copyts -i in.ts -map 0 -c copy -bsf:a aac_adtstoasc "
                    + "-video_track_timescale "
                    + "90000 -f hls -hls_time 100000 -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 "
                    + "-hls_segment_filename 'frag%d.m4s' out.m3u8";
        } else {
            byte[] init = segment.map() == null ? new byte[0] : pipeline.files.getOrDefault(segment.map(),
                    new byte[0]);
            byte[][] passthrough = {init, segment.data()};
            remuxed.put(key, passthrough);
            return passthrough;
        }
        Map<String, byte[]> files;
        try {
            files = ffmpeg.get().run(inputs, script);
        } catch (KvsTranscoder.TranscodeException e) {
            throw new AwsException("InternalServerException", "The segment couldn't be packaged: "
                    + e.getMessage(), 500);
        }
        byte[] fragment = files.getOrDefault("frag0.m4s", new byte[0]);
        byte[][] result = {files.getOrDefault("init.mp4", new byte[0]), fragment};
        remuxed.put(key, result);
        return result;
    }

    // ================================================================ harvest

    /** Writes a harvest job's window to its S3 destination: each manifest's renditions and segments. */
    void harvest(String account, String region, ObjectNode endpoint, ObjectNode job) {
        String group = endpoint.path("ChannelGroupName").asText();
        String channel = endpoint.path("ChannelName").asText();
        Pipeline pipeline = current(account, region, group, channel);
        double from = job.path("ScheduleConfiguration").path("StartTime").asDouble();
        double to = job.path("ScheduleConfiguration").path("EndTime").asDouble();
        if (pipeline == null) {
            throw new IllegalStateException("The channel received no content in the harvest window.");
        }
        String bucket = job.path("Destination").path("S3Destination").path("BucketName").asText();
        String prefix = job.path("Destination").path("S3Destination").path("DestinationPath").asText("");
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        String segmentName = endpoint.path("Segment").path("SegmentName").asText("segment");
        String separator = "HYPHEN".equals(endpoint.path("UriSeparator").asText()) ? "-" : "_";
        boolean cmaf = "CMAF".equals(endpoint.path("ContainerType").asText());
        List<Rendition> renditions = new ArrayList<>(pipeline.renditions.values());
        renditions.removeIf(rendition -> rendition.segments.isEmpty());
        boolean any = false;
        synchronized (pipeline) {
            for (JsonNode harvested : job.path("HarvestedManifests").path("HlsManifests")) {
                JsonNode manifest = manifest(endpoint, harvested.path("ManifestName").asText(), "m3u8");
                if (manifest == null) {
                    continue;
                }
                String name = manifest.path("ManifestName").asText();
                String child = manifest.path("ChildManifestName").asText(name);
                for (int i = 0; i < renditions.size(); i++) {
                    List<Segment> segments = window(renditions.get(i), endpoint, manifest, true, from, to);
                    if (segments.isEmpty()) {
                        continue;
                    }
                    any = true;
                    String playlist = mediaPlaylist(pipeline, endpoint, manifest, segments, i, segmentName,
                            separator, true);
                    put(bucket, prefix + child + separator + (i + 1) + ".m3u8", playlist.getBytes(
                            StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
                    if (cmaf) {
                        put(bucket, prefix + segmentName + separator + (i + 1) + separator + "init.mp4",
                                remux(pipeline, segments.getFirst())[0], "video/mp4");
                    }
                    for (Segment segment : segments) {
                        put(bucket, prefix + segmentName + separator + (i + 1) + separator + segment.sequence()
                                + (cmaf ? ".mp4" : ".ts"), cmaf ? remux(pipeline, segment)[1] : segment.data(),
                                cmaf ? "video/mp4" : "video/MP2T");
                    }
                }
                put(bucket, prefix + name + ".m3u8", multivariant(endpoint, manifest, renditions, separator)
                        .getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
            }
        }
        if (!any) {
            throw new IllegalStateException("The channel received no content in the harvest window.");
        }
    }

    private void put(String bucket, String key, byte[] data, String type) {
        try {
            s3.get().putObject(bucket, key, data, type, Map.of());
        } catch (AwsException e) {
            throw new IllegalStateException("Unable to write s3://" + bucket + "/" + key + ": " + e.getMessage());
        }
    }
}
