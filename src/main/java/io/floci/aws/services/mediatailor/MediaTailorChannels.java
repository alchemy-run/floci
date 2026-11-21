package io.floci.aws.services.mediatailor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Channel assembly. A channel's programs play its sources in schedule order: a LOOP channel repeats its programs from
 * when it started; a LINEAR channel plays them at their scheduled times, with the filler slate between them. A
 * program's ad breaks play their slate at their offsets, marked with {@code EXT-X-DATERANGE} SCTE-35 tags or
 * {@code EXT-X-CUE-OUT}/{@code EXT-X-CUE-IN} as each output's ad markup asks, so a playback configuration can fill
 * them. While the channel runs, each HLS output serves a live sliding window of the schedule with program date times;
 * {@code aws.time_delay} shifts it back on channels with time-shifted viewing. Sources that can't be read raise
 * alerts, and as-run logs go to CloudWatch Logs when the channel enables them.
 */
@ApplicationScoped
public class MediaTailorChannels implements Resettable {

    private static final Logger LOG = Logger.getLogger(MediaTailorChannels.class);
    private static final long CACHE_MILLIS = 10_000;
    private static final Pattern MPD_DURATION = Pattern.compile("mediaPresentationDuration=\"([^\"]+)\"");
    static final String AS_RUN_LOG_GROUP = "MediaTailor/Channel/AsRunLog";

    /** A source as its manifest shows it: its tracks' media playlists. */
    static final class Source {
        final String url;
        final MediaTailorHls.Multivariant multivariant;
        final List<MediaTailorHls.Media> tracks = new ArrayList<>();
        final long loadedAt = System.currentTimeMillis();

        Source(String url, MediaTailorHls.Multivariant multivariant) {
            this.url = url;
            this.multivariant = multivariant;
        }

        long millis() {
            return tracks.isEmpty() ? 0 : Math.round(tracks.get(0).duration() * 1000);
        }
    }

    /** A segment of a channel's timeline: its URI, length, start and tags. */
    record Piece(String uri, long millis, String map, List<String> tags, boolean discontinuity) {
    }

    /** What plays over a span of a channel's timeline: a program (with its ad breaks) or the filler slate. */
    static final class Item {
        final String type;
        final ObjectNode program;
        final String location;
        final String vod;
        final String live;
        long start;
        long millis;
        final List<long[]> breaks = new ArrayList<>();
        final List<JsonNode> breakNodes = new ArrayList<>();

        Item(String type, ObjectNode program, String location, String vod, String live) {
            this.type = type;
            this.program = program;
            this.location = location;
            this.vod = vod;
            this.live = live;
        }

        String name() {
            return program == null ? "FillerSlate" : program.path("ProgramName").asText();
        }
    }

    private final MediaTailorStore store;
    private final ObjectMapper mapper;
    private final MediaTailorHttp http;
    private final Provider<CloudWatchLogsService> logs;
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final Map<String, Long> asRun = new ConcurrentHashMap<>();

    @Inject
    public MediaTailorChannels(MediaTailorStore store, MediaTailorHttp http, Provider<CloudWatchLogsService> logs) {
        this.store = store;
        this.mapper = store.mapper();
        this.http = http;
        this.logs = logs;
    }

    @Override
    public void clear() {
        sources.clear();
        asRun.clear();
    }

    void sourceChanged(String account, String region, String location) {
        sources.clear();
    }

    void started(String account, String region, String channel) {
        asRun.remove(account + "/" + region + "/" + channel);
    }

    void forget(String account, String region, String channel) {
        asRun.remove(account + "/" + region + "/" + channel);
    }

    // ================================================================ sources

    private static String join(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceFirst("^/+", "");
    }

    /** The source's package for a source group, preferring HLS. */
    private static JsonNode packageFor(JsonNode source, String group) {
        JsonNode fallback = null;
        for (JsonNode config : source.path("HttpPackageConfigurations")) {
            boolean matches = group == null || group.equals(config.path("SourceGroup").asText());
            if (matches && "HLS".equals(config.path("Type").asText())) {
                return config;
            }
            if (matches && fallback == null) {
                fallback = config;
            }
        }
        if (fallback == null && group != null) {
            return packageFor(source, null);
        }
        return fallback;
    }

    private static String groupOf(JsonNode channel) {
        for (JsonNode output : channel.path("Outputs")) {
            if (output.has("HlsPlaylistSettings")) {
                return output.path("SourceGroup").asText();
            }
        }
        JsonNode first = channel.path("Outputs").path(0);
        return first.isMissingNode() ? null : first.path("SourceGroup").asText(null);
    }

    /** Reads a source's manifest for a source group: its tracks' playlists, cached for a few seconds. */
    Source read(JsonNode location, JsonNode source, String group) {
        JsonNode config = packageFor(source, group);
        if (config == null) {
            throw new IllegalStateException("The source has no HTTP package configuration.");
        }
        String url = join(location.path("HttpConfiguration").path("BaseUrl").asText(), config.path("Path").asText());
        Source cached = sources.get(url);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_MILLIS) {
            return cached;
        }
        MediaTailorHttp.Fetched fetched = http.get(url);
        if (!fetched.ok()) {
            throw new IllegalStateException(url + " answered " + fetched.status() + ".");
        }
        Source read;
        if ("DASH".equals(config.path("Type").asText())) {
            read = new Source(url, null);
            Matcher matcher = MPD_DURATION.matcher(fetched.text());
            if (matcher.find()) {
                MediaTailorHls.Media media = new MediaTailorHls.Media();
                double seconds = Duration.parse(matcher.group(1)).toMillis() / 1000.0;
                media.segments.add(new MediaTailorHls.Segment(seconds, "", url, List.of(), 0, null));
                read.tracks.add(media);
            }
        } else if (MediaTailorHls.isMultivariant(fetched.text())) {
            MediaTailorHls.Multivariant multivariant = MediaTailorHls.multivariant(fetched.text(), url);
            read = new Source(url, multivariant);
            for (MediaTailorHls.Track track : multivariant.tracks) {
                MediaTailorHttp.Fetched media = http.get(track.uri);
                if (!media.ok()) {
                    throw new IllegalStateException(track.uri + " answered " + media.status() + ".");
                }
                read.tracks.add(MediaTailorHls.media(media.text(), track.uri));
            }
        } else {
            read = new Source(url, null);
            read.tracks.add(MediaTailorHls.media(fetched.text(), url));
        }
        if (read.tracks.isEmpty()) {
            throw new IllegalStateException(url + " has no media playlists.");
        }
        sources.put(url, read);
        return read;
    }

    /** How long a VOD source plays, from its manifest; a source that can't be read fails the request. */
    long sourceDuration(String account, String region, ObjectNode location, ObjectNode source, ObjectNode channel) {
        try {
            long millis = read(location, source, groupOf(channel)).millis();
            if (millis <= 0) {
                throw new IllegalStateException("its manifest has no segments");
            }
            return millis;
        } catch (IllegalStateException | MediaTailorHttp.Unreachable e) {
            throw MediaTailorStore.badRequest("MediaTailor couldn't read the source "
                    + source.path("VodSourceName").asText() + "'s manifest: " + e.getMessage());
        }
    }

    /** Where a VOD source's ad markers are, in milliseconds from its start. */
    List<Long> adBreakOpportunities(String account, String region, ObjectNode source) {
        List<Long> offsets = new ArrayList<>();
        ObjectNode location = store.getForAccount(account, region, MediaTailorStore.LOCATION,
                source.path("SourceLocationName").asText()).orElse(null);
        if (location == null) {
            return offsets;
        }
        try {
            MediaTailorHls.Media media = read(location, source, null).tracks.get(0);
            double time = 0;
            for (MediaTailorHls.Segment segment : media.segments) {
                MediaTailorHls.Marker marker = MediaTailorHls.marker(segment.tags);
                if (marker != null && MediaTailorHls.Marker.OUT.equals(marker.kind())) {
                    offsets.add(Math.round(time * 1000));
                }
                time += segment.duration;
            }
        } catch (IllegalStateException | MediaTailorHttp.Unreachable e) {
            LOG.debugv("MediaTailor could not read the source {0}: {1}", source.path("VodSourceName").asText(),
                    e.getMessage());
        }
        return offsets;
    }

    // ================================================================ schedule

    /** Puts a program into its channel's order, by its relative transition. */
    void place(String account, String region, ObjectNode channel, ObjectNode program) {
        ObjectNode meta = MediaTailorStore.meta(channel);
        ArrayNode order = meta.has("order") ? (ArrayNode) meta.get("order") : meta.putArray("order");
        String name = program.path("ProgramName").asText();
        JsonNode transition = program.path("ScheduleConfiguration").path("Transition");
        String relative = transition.path("RelativeProgram").asText("");
        int at = order.size();
        if ("RELATIVE".equals(transition.path("Type").asText())) {
            int target = -1;
            for (int i = 0; i < order.size(); i++) {
                if (relative.equals(order.get(i).asText())) {
                    target = i;
                }
            }
            boolean before = "BEFORE_PROGRAM".equals(transition.path("RelativePosition").asText());
            if (target >= 0) {
                at = before ? target : target + 1;
            } else if (before) {
                at = 0;
            }
        }
        order.insert(at, name);
    }

    void unplace(ObjectNode channel, String name) {
        JsonNode order = MediaTailorStore.meta(channel).path("order");
        if (order.isArray()) {
            for (int i = order.size() - 1; i >= 0; i--) {
                if (name.equals(order.get(i).asText())) {
                    ((ArrayNode) order).remove(i);
                }
            }
        }
    }

    private List<ObjectNode> programs(String account, String region, ObjectNode channel) {
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        String name = channel.path("ChannelName").asText();
        for (ObjectNode program : store.listForAccount(account, region, MediaTailorStore.PROGRAM)) {
            if (name.equals(program.path("ChannelName").asText())) {
                byName.put(program.path("ProgramName").asText(), program);
            }
        }
        List<ObjectNode> ordered = new ArrayList<>();
        for (JsonNode entry : MediaTailorStore.meta(channel).path("order")) {
            ObjectNode program = byName.remove(entry.asText());
            if (program != null) {
                ordered.add(program);
            }
        }
        ordered.addAll(byName.values());
        return ordered;
    }

    private long slateMillis(String account, String region, JsonNode slate, ObjectNode channel) {
        if (slate == null || !slate.isObject() || slate.isEmpty()) {
            return 0;
        }
        ObjectNode location = store.getForAccount(account, region, MediaTailorStore.LOCATION,
                slate.path("SourceLocationName").asText()).orElse(null);
        ObjectNode source = location == null ? null : store.getForAccount(account, region, MediaTailorStore.VOD,
                MediaTailorService.sourceKey(slate.path("SourceLocationName").asText(),
                        slate.path("VodSourceName").asText())).orElse(null);
        if (source == null) {
            return 0;
        }
        try {
            return read(location, source, groupOf(channel)).millis();
        } catch (IllegalStateException | MediaTailorHttp.Unreachable e) {
            return 0;
        }
    }

    private Item item(String account, String region, ObjectNode channel, ObjectNode program) {
        Item item = new Item("PROGRAM", program, program.path("SourceLocationName").asText(),
                program.path("VodSourceName").asText(null), program.path("LiveSourceName").asText(null));
        long added = 0;
        List<JsonNode> breaks = new ArrayList<>();
        program.path("AdBreaks").forEach(breaks::add);
        breaks.sort(Comparator.comparingLong(adBreak -> adBreak.path("OffsetMillis").asLong()));
        for (JsonNode adBreak : breaks) {
            long length = slateMillis(account, region, adBreak.path("Slate"), channel);
            item.breaks.add(new long[] {adBreak.path("OffsetMillis").asLong() + added, length});
            item.breakNodes.add(adBreak);
            added += length;
        }
        item.millis = program.path("DurationMillis").asLong() + added;
        return item;
    }

    /** The channel's timeline items that overlap a span of epoch milliseconds. */
    List<Item> timeline(String account, String region, ObjectNode channel, long from, long to) {
        long started = MediaTailorStore.meta(channel).path("startedAt").asLong(System.currentTimeMillis());
        List<ObjectNode> programs = programs(account, region, channel);
        List<Item> items = new ArrayList<>();
        if (programs.isEmpty()) {
            return items;
        }
        if ("LOOP".equals(channel.path("PlaybackMode").asText())) {
            List<Item> cycle = new ArrayList<>();
            long length = 0;
            for (ObjectNode program : programs) {
                Item item = item(account, region, channel, program);
                if (item.millis <= 0) {
                    continue;
                }
                item.start = length;
                length += item.millis;
                cycle.add(item);
            }
            if (length <= 0) {
                return items;
            }
            long first = Math.max(0, (from - started) / length);
            for (long n = first; started + n * length < to; n++) {
                for (Item template : cycle) {
                    long start = started + n * length + template.start;
                    if (start + template.millis <= from || start >= to) {
                        continue;
                    }
                    Item item = item(account, region, channel, template.program);
                    item.start = start;
                    items.add(item);
                }
                if (n - first > 10_000) {
                    break;
                }
            }
            return items;
        }
        List<Item> placed = new ArrayList<>();
        Long cursor = null;
        for (int i = 0; i < programs.size(); i++) {
            ObjectNode program = programs.get(i);
            Item item = item(account, region, channel, program);
            JsonNode transition = program.path("ScheduleConfiguration").path("Transition");
            if ("ABSOLUTE".equals(transition.path("Type").asText())) {
                item.start = transition.path("ScheduledStartTimeMillis").asLong();
            } else if (cursor != null) {
                item.start = cursor;
            } else {
                long before = 0;
                Long anchor = null;
                for (int j = i; j < programs.size(); j++) {
                    JsonNode next = programs.get(j).path("ScheduleConfiguration").path("Transition");
                    if ("ABSOLUTE".equals(next.path("Type").asText())) {
                        anchor = next.path("ScheduledStartTimeMillis").asLong();
                        break;
                    }
                    before += item(account, region, channel, programs.get(j)).millis;
                }
                item.start = anchor == null ? started : anchor - before;
            }
            cursor = item.start + item.millis;
            placed.add(item);
        }
        placed.sort(Comparator.comparingLong(item -> item.start));
        for (int i = 0; i + 1 < placed.size(); i++) {
            Item item = placed.get(i);
            item.millis = Math.max(0, Math.min(item.millis, placed.get(i + 1).start - item.start));
        }
        JsonNode filler = channel.path("FillerSlate");
        boolean hasFiller = filler.isObject() && !filler.isEmpty();
        long previousEnd = Math.min(from, placed.get(0).start);
        for (Item item : placed) {
            if (hasFiller && item.start > previousEnd && item.start > from && previousEnd < to) {
                items.add(filler(filler, Math.max(previousEnd, Math.min(from, item.start)), item.start));
            }
            if (item.millis > 0 && item.start < to && item.start + item.millis > from) {
                items.add(item);
            }
            previousEnd = Math.max(previousEnd, item.start + item.millis);
        }
        if (hasFiller && previousEnd < to) {
            items.add(filler(filler, Math.max(previousEnd, from), to));
        }
        return items;
    }

    private static Item filler(JsonNode slate, long start, long end) {
        Item item = new Item("FILLER_SLATE", null, slate.path("SourceLocationName").asText(),
                slate.path("VodSourceName").asText(), null);
        item.start = start;
        item.millis = end - start;
        return item;
    }

    Double scheduledStart(String account, String region, ObjectNode channel, ObjectNode program) {
        JsonNode transition = program.path("ScheduleConfiguration").path("Transition");
        if ("ABSOLUTE".equals(transition.path("Type").asText())) {
            return transition.path("ScheduledStartTimeMillis").asLong() / 1000.0;
        }
        long now = System.currentTimeMillis();
        for (Item item : timeline(account, region, channel, now, now + 7L * 24 * 3600 * 1000)) {
            if (item.program != null && item.name().equals(program.path("ProgramName").asText())) {
                return item.start / 1000.0;
            }
        }
        return null;
    }

    List<ObjectNode> schedule(String account, String region, ObjectNode channel, int minutes, String audience) {
        long now = System.currentTimeMillis();
        List<ObjectNode> entries = new ArrayList<>();
        String channelName = channel.path("ChannelName").asText();
        for (Item item : timeline(account, region, channel, now, now + minutes * 60_000L)) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("ApproximateDurationSeconds", Math.round(item.millis / 1000.0));
            entry.put("ApproximateStartTime", item.start / 1000.0);
            entry.put("ChannelName", channelName);
            entry.put("SourceLocationName", item.location);
            if (item.program == null) {
                entry.put("Arn", channel.path("Arn").asText());
                entry.put("ProgramName", "");
                entry.put("VodSourceName", item.vod);
                entry.put("ScheduleEntryType", "FILLER_SLATE");
                entries.add(entry);
                continue;
            }
            entry.put("Arn", item.program.path("Arn").asText());
            entry.put("ProgramName", item.name());
            entry.put("ScheduleEntryType", "PROGRAM");
            if (item.vod != null) {
                entry.put("VodSourceName", item.vod);
            }
            if (item.live != null) {
                entry.put("LiveSourceName", item.live);
            }
            if (audience != null) {
                for (JsonNode media : item.program.path("AudienceMedia")) {
                    if (audience.equals(media.path("Audience").asText()) && media.path("AlternateMedia").size() > 0) {
                        JsonNode alternate = media.path("AlternateMedia").path(0);
                        entry.put("ScheduleEntryType", "ALTERNATE_MEDIA");
                        entry.put("SourceLocationName", alternate.path("SourceLocationName").asText());
                        entry.remove(List.of("VodSourceName", "LiveSourceName"));
                        MediaTailorStore.copy(alternate, entry, "VodSourceName", "LiveSourceName");
                        entry.putArray("Audiences").add(audience);
                    }
                }
            }
            ArrayNode breaks = entry.putArray("ScheduleAdBreaks");
            for (int i = 0; i < item.breaks.size(); i++) {
                JsonNode slate = item.breakNodes.get(i).path("Slate");
                ObjectNode adBreak = breaks.addObject();
                adBreak.put("ApproximateDurationSeconds", Math.round(item.breaks.get(i)[1] / 1000.0));
                adBreak.put("ApproximateStartTime", (item.start + item.breaks.get(i)[0]) / 1000.0);
                MediaTailorStore.copy(slate, adBreak, "SourceLocationName", "VodSourceName");
            }
            entries.add(entry);
        }
        return entries;
    }

    // ================================================================ playback

    /** Answers a request to an account's channel assembly host: {@code /v1/channel/<channel>/<output>...}. */
    MediaTailorPlayback.Reply handle(String account, String region, String path, Map<String, List<String>> query) {
        String[] parts = path.replaceFirst("^/+", "").split("/");
        if (parts.length != 4 || !"v1".equals(parts[0]) || !"channel".equals(parts[1])) {
            return MediaTailorPlayback.Reply.text(404, "Not found.");
        }
        ObjectNode channel = store.getForAccount(account, region, MediaTailorStore.CHANNEL, parts[2]).orElse(null);
        if (channel == null) {
            return MediaTailorPlayback.Reply.text(404, "Channel " + parts[2] + " not found.");
        }
        if (!"RUNNING".equals(channel.path("ChannelState").asText())) {
            return MediaTailorPlayback.Reply.text(404, "Channel " + parts[2] + " isn't running.");
        }
        String file = parts[3];
        String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        JsonNode output = null;
        int track = -1;
        for (JsonNode candidate : channel.path("Outputs")) {
            String manifest = candidate.path("ManifestName").asText();
            if (stem.equals(manifest)) {
                output = candidate;
            } else if (stem.startsWith(manifest + "_") && output == null) {
                try {
                    track = Integer.parseInt(stem.substring(manifest.length() + 1));
                    output = candidate;
                } catch (NumberFormatException e) {
                    // not a track playlist of this output
                }
            }
        }
        if (output == null) {
            return MediaTailorPlayback.Reply.text(404, "Not found.");
        }
        if (output.has("DashPlaylistSettings")) {
            return MediaTailorPlayback.Reply.text(501, "Floci serves channel outputs as HLS.");
        }
        long delay = 0;
        List<String> delays = query.get("aws.time_delay");
        int maxDelay = channel.path("TimeShiftConfiguration").path("MaxTimeDelaySeconds").asInt(0);
        if (delays != null && !delays.isEmpty() && maxDelay > 0) {
            try {
                delay = Math.min(maxDelay, Math.max(0, Long.parseLong(delays.get(0)))) * 1000L;
            } catch (NumberFormatException e) {
                return MediaTailorPlayback.Reply.text(400, "aws.time_delay must be a whole number of seconds.");
            }
        }
        long now = System.currentTimeMillis() - delay;
        String suffix = delay > 0 ? "?aws.time_delay=" + delay / 1000 : "";
        if (track < 0) {
            Source first = firstSource(account, region, channel, output, now);
            if (first != null && first.multivariant != null) {
                String manifest = output.path("ManifestName").asText();
                String text = MediaTailorHls.writeMultivariant(first.multivariant, index -> manifest + "_" + index
                        + ".m3u8" + suffix);
                return reply(text);
            }
            track = 0;
        }
        return reply(media(account, region, channel, output, track, now));
    }

    private static MediaTailorPlayback.Reply reply(String text) {
        return new MediaTailorPlayback.Reply(200, "application/vnd.apple.mpegurl",
                text.getBytes(StandardCharsets.UTF_8), Map.of("Access-Control-Allow-Origin", "*"));
    }

    private Source firstSource(String account, String region, ObjectNode channel, JsonNode output, long now) {
        List<Item> items = timeline(account, region, channel, now, now + 1);
        if (items.isEmpty()) {
            items = timeline(account, region, channel, now, now + 24L * 3600 * 1000);
        }
        for (Item item : items) {
            Source source = source(account, region, channel, item, output.path("SourceGroup").asText());
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private Source source(String account, String region, ObjectNode channel, Item item, String group) {
        String kind = item.live != null ? MediaTailorStore.LIVE : MediaTailorStore.VOD;
        String name = item.live != null ? item.live : item.vod;
        ObjectNode location = store.getForAccount(account, region, MediaTailorStore.LOCATION, item.location)
                .orElse(null);
        ObjectNode source = location == null ? null : store.getForAccount(account, region, kind,
                MediaTailorService.sourceKey(item.location, name)).orElse(null);
        if (source == null) {
            alert(account, region, channel, item, "MISSING_SOURCE", "The source " + name + " doesn't exist.");
            return null;
        }
        try {
            if (item.live != null) {
                sources.remove(join(location.path("HttpConfiguration").path("BaseUrl").asText(),
                        packageFor(source, group) == null ? "" : packageFor(source, group).path("Path").asText()));
            }
            return read(location, source, group);
        } catch (IllegalStateException | MediaTailorHttp.Unreachable e) {
            alert(account, region, channel, item, "SOURCE_UNAVAILABLE", "MediaTailor couldn't read the source "
                    + name + ": " + e.getMessage());
            return null;
        }
    }

    private String segmentUri(String account, String region, String locationName, String uri) {
        ObjectNode location = store.getForAccount(account, region, MediaTailorStore.LOCATION, locationName)
                .orElse(null);
        if (location == null) {
            return uri;
        }
        String delivery = location.path("DefaultSegmentDeliveryConfiguration").path("BaseUrl").asText("");
        String base = location.path("HttpConfiguration").path("BaseUrl").asText("").replaceAll("/+$", "");
        if (!delivery.isEmpty() && uri.startsWith(base)) {
            return delivery.replaceAll("/+$", "") + uri.substring(base.length());
        }
        return uri;
    }

    /** An item's pieces for a track: its content with its ad breaks' slates, from the item's start. */
    private List<Piece> pieces(String account, String region, ObjectNode channel, Item item, int track,
                               String group, List<String> markup) {
        List<Piece> pieces = new ArrayList<>();
        Source source = source(account, region, channel, item, group);
        if (source == null) {
            return pieces;
        }
        MediaTailorHls.Media media = source.tracks.get(Math.min(track, source.tracks.size() - 1));
        long clipStart = 0;
        long clipEnd = Long.MAX_VALUE;
        if (item.program != null && item.vod != null) {
            JsonNode clip = item.program.path("ClipRange");
            clipStart = clip.path("StartOffsetMillis").asLong(0);
            clipEnd = clip.hasNonNull("EndOffsetMillis") ? clip.get("EndOffsetMillis").asLong() : Long.MAX_VALUE;
        }
        List<Piece> content = new ArrayList<>();
        long time = 0;
        boolean first = true;
        for (MediaTailorHls.Segment segment : media.segments) {
            long length = Math.round(segment.duration * 1000);
            if (time >= clipStart && time < clipEnd) {
                List<String> tags = new ArrayList<>();
                for (String tag : segment.tags) {
                    if (!MediaTailorHls.adMarker(tag) && !tag.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                            && !tag.equals("#EXT-X-DISCONTINUITY")) {
                        tags.add(tag);
                    }
                }
                content.add(new Piece(segmentUri(account, region, item.location, segment.uri), length,
                        segment.map == null ? null : segmentUri(account, region, item.location, segment.map), tags,
                        first));
                first = false;
            }
            time += length;
        }
        if (item.live != null) {
            // a live source plays its latest segments
            long wanted = item.millis;
            long total = 0;
            int from = content.size();
            while (from > 0 && total < wanted) {
                from--;
                total += content.get(from).millis();
            }
            content = new ArrayList<>(content.subList(from, content.size()));
        }
        int next = 0;
        long elapsed = 0;
        boolean afterBreak = false;
        for (int b = 0; b <= item.breaks.size(); b++) {
            long until = b < item.breaks.size() ? item.breaks.get(b)[0] : Long.MAX_VALUE;
            while (next < content.size() && elapsed < until) {
                Piece piece = content.get(next++);
                pieces.add(new Piece(piece.uri(), piece.millis(), piece.map(), piece.tags(),
                        piece.discontinuity() || pieces.isEmpty() || afterBreak));
                afterBreak = false;
                elapsed += piece.millis();
            }
            if (b < item.breaks.size()) {
                long played = slate(account, region, channel, item, b, track, group, markup, pieces);
                elapsed += played;
                afterBreak = played > 0;
            }
        }
        return pieces;
    }

    /** Adds an ad break's slate segments with their markers, returning how long they play. */
    private long slate(String account, String region, ObjectNode channel, Item item, int index, int track,
                       String group, List<String> markup, List<Piece> pieces) {
        long length = item.breaks.get(index)[1];
        JsonNode slate = item.breakNodes.get(index).path("Slate");
        if (length <= 0) {
            return 0;
        }
        Item slateItem = new Item("SLATE", null, slate.path("SourceLocationName").asText(),
                slate.path("VodSourceName").asText(), null);
        Source source = source(account, region, channel, slateItem, group);
        if (source == null) {
            return 0;
        }
        MediaTailorHls.Media media = source.tracks.get(Math.min(track, source.tracks.size() - 1));
        double seconds = length / 1000.0;
        long event = Math.abs((item.name() + "/" + index + "/" + item.start).hashCode());
        long played = 0;
        int count = 0;
        for (MediaTailorHls.Segment segment : media.segments) {
            long segmentMillis = Math.round(segment.duration * 1000);
            List<String> tags = new ArrayList<>();
            if (count == 0) {
                if (markup.contains("DATERANGE")) {
                    tags.add("#EXT-X-DATERANGE:ID=\"" + event + "\",START-DATE=\"\u0000START\u0000\","
                            + "PLANNED-DURATION=" + MediaTailorHls.seconds(seconds) + ",SCTE35-OUT=0x"
                            + HexFormat.of().formatHex(Scte35.spliceInsert(event, seconds, true)).toUpperCase());
                }
                if (markup.contains("SCTE35_ENHANCED")) {
                    tags.add("#EXT-OATCLS-SCTE35:" + Base64.getEncoder().encodeToString(Scte35.spliceInsert(event,
                            seconds, true)));
                    tags.add("#EXT-X-CUE-OUT:" + MediaTailorHls.seconds(seconds));
                }
            } else if (markup.contains("SCTE35_ENHANCED")) {
                tags.add("#EXT-X-CUE-OUT-CONT:ElapsedTime=" + MediaTailorHls.seconds(played / 1000.0) + ",Duration="
                        + MediaTailorHls.seconds(seconds));
            }
            for (String tag : segment.tags) {
                if (!MediaTailorHls.adMarker(tag) && !tag.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                        && !tag.equals("#EXT-X-DISCONTINUITY")) {
                    tags.add(tag);
                }
            }
            pieces.add(new Piece(segmentUri(account, region, slateItem.location, segment.uri), segmentMillis,
                    segment.map == null ? null : segmentUri(account, region, slateItem.location, segment.map), tags,
                    count == 0));
            played += segmentMillis;
            count++;
            if (played >= length) {
                break;
            }
        }
        return played;
    }

    /** Markers for the first segment after an ad break. */
    private static List<String> cueIn(Piece previous, List<String> markup, long event) {
        List<String> tags = new ArrayList<>();
        if (markup.contains("DATERANGE")) {
            tags.add("#EXT-X-DATERANGE:ID=\"" + event + "\",START-DATE=\"\u0000START\u0000\",SCTE35-IN=0x"
                    + HexFormat.of().formatHex(Scte35.spliceInsert(event, 0, false)).toUpperCase());
        }
        if (markup.contains("SCTE35_ENHANCED")) {
            tags.add("#EXT-X-CUE-IN");
        }
        return tags;
    }

    private String media(String account, String region, ObjectNode channel, JsonNode output, int track, long now) {
        int windowSeconds = output.path("HlsPlaylistSettings").path("ManifestWindowSeconds").asInt(60);
        List<String> markup = new ArrayList<>();
        output.path("HlsPlaylistSettings").path("AdMarkupType").forEach(type -> markup.add(type.asText()));
        if (markup.isEmpty()) {
            markup.add("DATERANGE");
        }
        String group = output.path("SourceGroup").asText();
        long started = MediaTailorStore.meta(channel).path("startedAt").asLong(now);
        long windowStart = Math.max(started, now - windowSeconds * 1000L);
        long sequence = 0;
        long discontinuities = 0;
        double target = 1;
        boolean fragmented = false;
        StringBuilder body = new StringBuilder();
        boolean inBreak = false;
        long breakEvent = 0;
        String lastMap = null;
        boolean firstListed = true;
        for (Item item : timeline(account, region, channel, started, now + 1)) {
            List<Piece> pieces = item.program == null ? filler(account, region, channel, item, track, group)
                    : pieces(account, region, channel, item, track, group, markup);
            long time = item.start;
            long end = item.start + item.millis;
            for (Piece piece : pieces) {
                if (time >= end || time > now) {
                    break;
                }
                boolean listed = time + piece.millis() > windowStart;
                boolean opensBreak = piece.tags().stream().anyMatch(tag -> tag.contains("SCTE35-OUT")
                        || tag.startsWith("#EXT-X-CUE-OUT:"));
                boolean slate = opensBreak || inBreak && !piece.discontinuity();
                List<String> extra = new ArrayList<>();
                if (inBreak && !slate) {
                    extra.addAll(cueIn(piece, markup, breakEvent));
                }
                if (!listed) {
                    sequence++;
                    if (piece.discontinuity()) {
                        discontinuities++;
                    }
                } else {
                    target = Math.max(target, piece.millis() / 1000.0);
                    if (piece.discontinuity() && !firstListed) {
                        body.append("#EXT-X-DISCONTINUITY\n");
                    }
                    String pdt = Instant.ofEpochMilli(time).toString();
                    body.append("#EXT-X-PROGRAM-DATE-TIME:").append(pdt).append('\n');
                    if (piece.map() != null && (piece.discontinuity() || firstListed || !piece.map().equals(lastMap))) {
                        body.append("#EXT-X-MAP:URI=\"").append(piece.map()).append("\"\n");
                        fragmented = true;
                    }
                    for (String tag : extra) {
                        body.append(tag.replace("\u0000START\u0000", pdt)).append('\n');
                    }
                    for (String tag : piece.tags()) {
                        body.append(tag.replace("\u0000START\u0000", pdt)).append('\n');
                    }
                    body.append("#EXTINF:").append(MediaTailorHls.seconds(piece.millis() / 1000.0)).append(",\n");
                    body.append(piece.uri()).append('\n');
                    lastMap = piece.map();
                    if (firstListed && piece.discontinuity() && sequence > 0) {
                        discontinuities++;
                    }
                    firstListed = false;
                }
                if (opensBreak) {
                    inBreak = true;
                    breakEvent = Math.abs((item.name() + "/" + breakIndex(item, time) + "/" + item.start).hashCode());
                } else if (inBreak && !slate) {
                    inBreak = false;
                }
                time += piece.millis();
            }
            logAsRun(account, region, channel, item, now);
        }
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        out.append("#EXT-X-VERSION:").append(fragmented ? 6 : 3).append('\n');
        out.append("#EXT-X-TARGETDURATION:").append((long) Math.ceil(target - 0.001)).append('\n');
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n');
        out.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuities).append('\n');
        return out.append(body).toString();
    }

    private static int breakIndex(Item item, long time) {
        for (int i = 0; i < item.breaks.size(); i++) {
            if (item.start + item.breaks.get(i)[0] <= time) {
                continue;
            }
            return Math.max(0, i - 1);
        }
        return Math.max(0, item.breaks.size() - 1);
    }

    /** The filler slate's pieces, looped for as long as the gap lasts. */
    private List<Piece> filler(String account, String region, ObjectNode channel, Item item, int track,
                               String group) {
        List<Piece> pieces = new ArrayList<>();
        Source source = source(account, region, channel, item, group);
        if (source == null) {
            return pieces;
        }
        MediaTailorHls.Media media = source.tracks.get(Math.min(track, source.tracks.size() - 1));
        if (media.segments.isEmpty() || media.duration() <= 0) {
            return pieces;
        }
        long played = 0;
        while (played < item.millis) {
            boolean first = true;
            for (MediaTailorHls.Segment segment : media.segments) {
                long length = Math.round(segment.duration * 1000);
                pieces.add(new Piece(segmentUri(account, region, item.location, segment.uri), length,
                        segment.map == null ? null : segmentUri(account, region, item.location, segment.map),
                        List.of(), first));
                first = false;
                played += length;
                if (played >= item.millis) {
                    break;
                }
            }
        }
        return pieces;
    }

    // ================================================================ alerts and logs

    private void alert(String account, String region, ObjectNode channel, Item item, String code, String message) {
        String resource = item.program != null ? item.program.path("Arn").asText() : channel.path("Arn").asText();
        String key = resource + "/" + code;
        ObjectNode alert = store.getForAccount(account, region, MediaTailorStore.ALERT, key).orElse(null);
        if (alert == null) {
            alert = store.record();
        }
        alert.put("AlertCode", code);
        alert.put("AlertMessage", message);
        alert.put("LastModifiedTime", MediaTailorStore.epoch());
        alert.put("ResourceArn", resource);
        alert.put("Category", "PLAYBACK_WARNING");
        ArrayNode related = alert.putArray("RelatedResourceArns");
        related.add(channel.path("Arn").asText());
        String sourceKind = item.live != null ? "liveSource" : "vodSource";
        related.add(store.arnFor(account, region, sourceKind + "/" + item.location + "/" + (item.live != null
                ? item.live : item.vod)));
        store.putForAccount(account, region, MediaTailorStore.ALERT, key, alert);
    }

    private void logAsRun(String account, String region, ObjectNode channel, Item item, long now) {
        boolean enabled = false;
        for (JsonNode type : channel.path("LogConfiguration").path("LogTypes")) {
            enabled |= "AS_RUN".equals(type.asText());
        }
        String key = account + "/" + region + "/" + channel.path("ChannelName").asText();
        if (!enabled || item.start > now || asRun.getOrDefault(key, Long.MIN_VALUE) >= item.start) {
            return;
        }
        asRun.put(key, item.start);
        ObjectNode event = mapper.createObjectNode();
        event.put("eventType", "AS_RUN");
        event.put("eventDescription", item.program == null ? "filler slate started" : "program started");
        event.put("eventTimestamp", Instant.ofEpochMilli(item.start).toString());
        event.put("channelName", channel.path("ChannelName").asText());
        event.put("channelArn", channel.path("Arn").asText());
        event.put("programName", item.name());
        event.put("scheduleEntryType", item.type);
        event.put("sourceLocationName", item.location);
        if (item.vod != null) {
            event.put("vodSourceName", item.vod);
        }
        if (item.live != null) {
            event.put("liveSourceName", item.live);
        }
        event.put("durationMillis", item.millis);
        String stream = channel.path("ChannelName").asText();
        try {
            RequestScopes.runAs(account, region, () -> {
                try {
                    logs.get().createLogGroup(AS_RUN_LOG_GROUP, null, Map.of(), region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                try {
                    logs.get().createLogStream(AS_RUN_LOG_GROUP, stream, region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                logs.get().putLogEvents(AS_RUN_LOG_GROUP, stream, List.of(Map.of("timestamp", item.start, "message",
                        event.toString())), region);
            });
        } catch (RuntimeException e) {
            LOG.debugv("MediaTailor could not write the as-run log of {0}: {1}", stream, e.getMessage());
        }
    }

    // ================================================================ SCTE-35

    /** SCTE-35 splice_info_section messages for the ad markers channel outputs carry. */
    static final class Scte35 {
        private Scte35() {
        }

        /** A splice_insert, immediate: out of network with a break duration, or back in. */
        static byte[] spliceInsert(long eventId, double seconds, boolean out) {
            Bits command = new Bits();
            command.put(eventId & 0xFFFFFFFFL, 32);
            command.put(0, 1);
            command.put(0x7F, 7);
            command.put(out ? 1 : 0, 1);
            command.put(1, 1);
            command.put(out ? 1 : 0, 1);
            command.put(1, 1);
            command.put(1, 1);
            command.put(0x7, 3);
            if (out) {
                command.put(1, 1);
                command.put(0x3F, 6);
                command.put(Math.round(seconds * 90000) & 0x1FFFFFFFFL, 33);
            }
            command.put(1, 16);
            command.put(0, 8);
            command.put(0, 8);
            byte[] body = command.bytes();
            Bits section = new Bits();
            section.put(0xFC, 8);
            section.put(0, 1);
            section.put(0, 1);
            section.put(3, 2);
            section.put(body.length + 17, 12);
            section.put(0, 8);
            section.put(0, 1);
            section.put(0, 6);
            section.put(0, 33);
            section.put(0, 8);
            section.put(0xFFF, 12);
            section.put(body.length, 12);
            section.put(5, 8);
            for (byte b : body) {
                section.put(b & 0xFF, 8);
            }
            section.put(0, 16);
            byte[] withoutCrc = section.bytes();
            long crc = crc32(withoutCrc);
            byte[] out2 = Arrays.copyOf(withoutCrc, withoutCrc.length + 4);
            out2[withoutCrc.length] = (byte) (crc >>> 24);
            out2[withoutCrc.length + 1] = (byte) (crc >>> 16);
            out2[withoutCrc.length + 2] = (byte) (crc >>> 8);
            out2[withoutCrc.length + 3] = (byte) crc;
            return out2;
        }

        /** CRC-32/MPEG-2: polynomial 0x04C11DB7, initial 0xFFFFFFFF, no reflection or final XOR. */
        static long crc32(byte[] data) {
            long crc = 0xFFFFFFFFL;
            for (byte b : data) {
                crc ^= (b & 0xFFL) << 24;
                for (int i = 0; i < 8; i++) {
                    crc = (crc & 0x80000000L) != 0 ? ((crc << 1) ^ 0x04C11DB7L) & 0xFFFFFFFFL : (crc << 1) & 0xFFFFFFFFL;
                }
            }
            return crc;
        }

        private static final class Bits {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();
            private int current;
            private int used;

            void put(long value, int width) {
                for (int i = width - 1; i >= 0; i--) {
                    current = (current << 1) | (int) ((value >>> i) & 1);
                    if (++used == 8) {
                        out.write(current);
                        current = 0;
                        used = 0;
                    }
                }
            }

            byte[] bytes() {
                return out.toByteArray();
            }
        }
    }
}
