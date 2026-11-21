package io.floci.aws.services.mediatailor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaTailor's ad insertion. A session starts at a playback configuration's HLS or DASH endpoint (implicitly) or its
 * session initialization endpoint (explicitly, with ad parameters and client- or server-side reporting). The session's
 * multivariant playlist names a personalized media playlist per track. Each one is the origin's playlist with ad avails
 * filled: an avail an ad marker starts ({@code EXT-X-CUE-OUT} or an {@code EXT-X-DATERANGE} with {@code SCTE35-OUT})
 * replaces its content with the ads the ad decision server answers that fit its duration, then the slate, then the
 * content again. Ads are conditioned for each track by {@link MediaTailorAds} and served from the playback host, which
 * fires their impressions and tracking events as their segments are requested (server-side reporting) or lists them
 * at the session's tracking URL (client-side reporting). A VOD playlist without markers gets the ad decision server's
 * VMAP breaks, or its VAST ads as a pre-roll; a live session gets the live pre-roll. Prefetch schedules retrieve ads
 * ahead of the avails they match. Logs go to CloudWatch Logs when the configuration enables them. DASH manifests are
 * served with their segment URLs resolved, without ads.
 */
@ApplicationScoped
public class MediaTailorPlayback implements Resettable {

    private static final Logger LOG = Logger.getLogger(MediaTailorPlayback.class);
    private static final Pattern VARIABLE = Pattern.compile("\\[([A-Za-z0-9_.:-]+)]");
    static final String ADS_LOG_GROUP = "MediaTailor/AdDecisionServerInteractions";
    static final String MANIFEST_LOG_GROUP = "MediaTailor/ManifestService";
    private static final double EPSILON = 0.001;
    /** How far a creative may run past an avail and still fit: transcoded ads end on a frame, not on the avail. */
    private static final double FIT_TOLERANCE = 0.25;

    // ================================================================ session model

    /** A track of a session: its origin playlist and the profile ads are conditioned to. */
    static final class Track {
        final String url;
        final MediaTailorHls.Track source;
        MediaTailorAds.Profile profile;
        final TreeMap<Long, long[]> emitted = new TreeMap<>();
        final Map<Long, Double> durations = new HashMap<>();
        final Map<Long, Double> times = new HashMap<>();
        final Map<Long, Double> lengths = new HashMap<>();
        final Set<Long> endsWithAd = new HashSet<>();
        Long baseDiscontinuity;
        String contentMap;

        Track(String url, MediaTailorHls.Track source) {
            this.url = url;
            this.source = source;
        }

        double emittedDuration(long sequence) {
            return lengths.getOrDefault(sequence, 0.0);
        }
    }

    /** An ad, slate or bumper placed in an avail, at a time from the avail's start. */
    static final class Placed {
        final String kind;
        final MediaTailorAds.Ad ad;
        final double start;
        final double duration;
        final Map<Integer, MediaTailorAds.Rendition> renditions;

        Placed(String kind, MediaTailorAds.Ad ad, double start, double duration,
               Map<Integer, MediaTailorAds.Rendition> renditions) {
            this.kind = kind;
            this.ad = ad;
            this.start = start;
            this.duration = duration;
            this.renditions = renditions;
        }
    }

    /** An ad avail of a session: content it replaces (from a marker) or a break it inserts (pre-roll, VMAP). */
    static final class Avail {
        final String id;
        final int index;
        final boolean insert;
        final double duration;
        long startSequence = -1;
        Long endSequence;
        boolean postRoll;
        double contentOffset = -1;
        final List<Placed> placed = new ArrayList<>();
        double shift;
        Double sessionTime;
        boolean ended;

        Avail(String id, int index, boolean insert, double duration) {
            this.id = id;
            this.index = index;
            this.insert = insert;
            this.duration = duration;
        }

        boolean filled() {
            return !placed.isEmpty();
        }

        double filledDuration() {
            return placed.isEmpty() ? 0 : placed.get(placed.size() - 1).start + placed.get(placed.size() - 1).duration;
        }
    }

    static final class Session {
        final String id;
        final String account;
        final String region;
        final String config;
        final String path;
        final boolean dash;
        final boolean clientReporting;
        final Map<String, String> playerParams;
        final Map<String, String> sessionParams;
        final String clientIp;
        final String userAgent;
        final String referer;
        final long startedAt = System.currentTimeMillis();
        final boolean logged;
        volatile long lastAccess = System.currentTimeMillis();
        final List<Track> tracks = new ArrayList<>();
        boolean multivariant;
        String originUrl;
        final Map<String, Avail> avails = new LinkedHashMap<>();
        final Set<String> fired = ConcurrentHashMap.newKeySet();
        boolean preRollPlanned;
        boolean vodPlanned;
        boolean firstManifest = true;

        Session(String id, String account, String region, String config, String path, boolean dash,
                boolean clientReporting, Map<String, String> playerParams, Map<String, String> sessionParams,
                String clientIp, String userAgent, String referer, boolean logged) {
            this.id = id;
            this.account = account;
            this.region = region;
            this.config = config;
            this.path = path;
            this.dash = dash;
            this.clientReporting = clientReporting;
            this.playerParams = playerParams;
            this.sessionParams = sessionParams;
            this.clientIp = clientIp;
            this.userAgent = userAgent;
            this.referer = referer;
            this.logged = logged;
        }
    }

    /** Ads a prefetch schedule retrieved, for the avails it matches. */
    record Prefetched(List<MediaTailorAds.Ad> ads, double retrievedAt, double expiresAt) {
    }

    /** What a data-plane request answers: a status, content type and body, with headers. */
    record Reply(int status, String contentType, byte[] body, Map<String, String> headers) {
        static Reply text(int status, String text) {
            return new Reply(status, "text/plain", text.getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    /** What a request knows about its client. */
    record Client(String ip, String userAgent, String referer, Map<String, List<String>> query, String host) {
    }

    private final MediaTailorStore store;
    private final ObjectMapper mapper;
    private final MediaTailorHttp http;
    private final MediaTailorAds ads;
    private final Provider<CloudWatchLogsService> logs;
    private final Vertx vertx;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Prefetched> prefetched = new ConcurrentHashMap<>();
    private final Map<String, Long> timers = new ConcurrentHashMap<>();

    @Inject
    public MediaTailorPlayback(MediaTailorStore store, MediaTailorHttp http, MediaTailorAds ads,
                               Provider<CloudWatchLogsService> logs, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.http = http;
        this.ads = ads;
        this.logs = logs;
        this.vertx = vertx;
    }

    @Override
    public void clear() {
        sessions.clear();
        prefetched.clear();
        timers.values().forEach(vertx::cancelTimer);
        timers.clear();
        ads.clear();
    }

    void configurationChanged(String account, String region, String name) {
        if (store.getForAccount(account, region, MediaTailorStore.CONFIG, name).isEmpty()) {
            sessions.values().removeIf(session -> session.account.equals(account) && session.region.equals(region)
                    && session.config.equals(name));
        }
    }

    private void expire() {
        long idle = store.config().sessionIdleSeconds() * 1000L;
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> now - session.lastAccess > idle);
    }

    // ================================================================ routing

    /**
     * Answers a request to an account's playback host: {@code /v1/session}, {@code /v1/master}, {@code /v1/dash},
     * {@code /v1/manifest}, {@code /v1/segment} and {@code /v1/tracking}.
     */
    Reply handle(String method, String account, String region, String path, byte[] body, Client client) {
        expire();
        String[] parts = path.replaceFirst("^/+", "").split("/", -1);
        if (parts.length < 4 || !"v1".equals(parts[0]) || !MediaTailorStore.hostId(account, region).equals(parts[2])) {
            return Reply.text(404, "Not found.");
        }
        String kind = parts[1];
        try {
            return switch (kind) {
                case "session" -> "POST".equals(method) ? initialize(account, region, parts, body, client, path)
                        : Reply.text(405, "Sessions are initialized with POST.");
                case "master", "dash" -> master(account, region, parts, client, path, "dash".equals(kind));
                case "manifest" -> manifest(parts, path);
                case "segment" -> segment(parts);
                case "tracking" -> tracking(parts);
                default -> Reply.text(404, "Not found.");
            };
        } catch (AwsException e) {
            return Reply.text(e.getHttpStatus(), e.getMessage());
        }
    }

    private ObjectNode configuration(String account, String region, String name) {
        return store.getForAccount(account, region, MediaTailorStore.CONFIG, name).orElseThrow(() ->
                new AwsException("NotFoundException", "Playback configuration " + name + " not found.", 404));
    }

    private static String rest(String[] parts, int from) {
        return String.join("/", Arrays.copyOfRange(parts, from, parts.length));
    }

    private Reply initialize(String account, String region, String[] parts, byte[] body, Client client, String path) {
        if (parts.length < 5) {
            return Reply.text(404, "Not found.");
        }
        String config = parts[3];
        ObjectNode configuration = configuration(account, region, config);
        String origin = rest(parts, 4);
        JsonNode request;
        try {
            request = body == null || body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException e) {
            return Reply.text(400, "The session initialization request isn't valid JSON.");
        }
        Map<String, String> player = new LinkedHashMap<>();
        request.path("adsParams").properties().forEach(entry -> player.put(entry.getKey(), entry.getValue().asText()));
        request.path("playerParams").properties().forEach(entry -> player.put(entry.getKey(),
                entry.getValue().asText()));
        Map<String, String> sessionParams = new LinkedHashMap<>();
        JsonNode suppression = request.path("availSuppression");
        if (suppression.isObject()) {
            putIf(sessionParams, "availSuppressionMode", suppression.path("mode").asText(null));
            putIf(sessionParams, "availSuppressionValue", suppression.path("value").asText(null));
            putIf(sessionParams, "availSuppressionFillPolicy", suppression.path("fillPolicy").asText(null));
        }
        putIf(sessionParams, "streamId", request.path("streamId").asText(null));
        boolean clientSide = "client".equalsIgnoreCase(request.path("reportingMode").asText("server"));
        boolean dash = origin.toLowerCase(Locale.ROOT).endsWith(".mpd");
        Session session = session(account, region, config, configuration, origin, dash, clientSide, player,
                sessionParams, client);
        String hostId = MediaTailorStore.hostId(account, region);
        ObjectNode out = mapper.createObjectNode();
        String manifestPath = "/v1/" + (dash ? "dash" : "master") + "/" + hostId + "/" + config + "/" + origin
                + "?aws.sessionId=" + session.id;
        String root = URI.create(store.hostPrefix(MediaTailorStore.PLAYBACK, account, region)).getRawPath();
        out.put("manifestUrl", root + manifestPath);
        out.put("trackingUrl", root + "/v1/tracking/" + hostId + "/" + config + "/" + session.id);
        manifestLog(session, configuration, "SESSION_INITIALIZED", Map.of());
        return json(out);
    }

    private static void putIf(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private Session session(String account, String region, String config, ObjectNode configuration, String origin,
                            boolean dash, boolean clientSide, Map<String, String> player,
                            Map<String, String> sessionParams, Client client) {
        int percent = configuration.path("LogConfiguration").path("PercentEnabled").asInt(0);
        boolean logged = percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent;
        Session session = new Session(UUID.randomUUID().toString(), account, region, config, origin, dash,
                clientSide, player, sessionParams, client.ip(), client.userAgent(), client.referer(), logged);
        aliases(configuration, session);
        sessions.put(session.id, session);
        return session;
    }

    /** Checks a session's player parameters against the configuration's aliases. */
    private static void aliases(ObjectNode configuration, Session session) {
        configuration.path("ConfigurationAliases").properties().forEach(alias -> {
            String variable = alias.getKey();
            if (!variable.startsWith("player_params.")) {
                return;
            }
            String value = session.playerParams.get(variable.substring("player_params.".length()));
            if (value != null && !alias.getValue().has(value)) {
                throw new AwsException("BadRequestException", "The player parameter " + variable + " value " + value
                        + " has no configuration alias.", 400);
            }
        });
    }

    private Session existing(String id) {
        Session session = id == null ? null : sessions.get(id);
        if (session == null) {
            throw new AwsException("NotFoundException", "Session " + id + " not found.", 404);
        }
        session.lastAccess = System.currentTimeMillis();
        return session;
    }

    private static String first(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private Reply master(String account, String region, String[] parts, Client client, String path, boolean dash) {
        if (parts.length < 5) {
            return Reply.text(404, "Not found.");
        }
        String config = parts[3];
        ObjectNode configuration = configuration(account, region, config);
        String origin = rest(parts, 4);
        String id = first(client.query(), "aws.sessionId");
        Session session;
        if (id != null) {
            session = existing(id);
        } else {
            Map<String, String> player = new LinkedHashMap<>();
            Map<String, String> sessionParams = new LinkedHashMap<>();
            client.query().forEach((key, values) -> {
                if (values.isEmpty()) {
                    return;
                }
                if (key.startsWith("ads.")) {
                    player.put(key.substring(4), values.get(0));
                } else if (key.startsWith("playerParams.")) {
                    player.put(key.substring("playerParams.".length()), values.get(0));
                } else if (key.startsWith("aws.")) {
                    sessionParams.put(key.substring(4), values.get(0));
                }
            });
            session = session(account, region, config, configuration, origin, dash, false, player, sessionParams,
                    client);
            manifestLog(session, configuration, "SESSION_INITIALIZED", Map.of());
        }
        String source = originUrl(configuration, session);
        MediaTailorHttp.Fetched fetched = origin(session, configuration, source);
        if (!fetched.ok()) {
            return Reply.text(fetched.status() == 404 ? 404 : 502, "The origin answered " + fetched.status()
                    + " for " + source + ".");
        }
        if (dash) {
            return new Reply(200, "application/dash+xml", dash(session, configuration, fetched.text(), source)
                    .getBytes(StandardCharsets.UTF_8), cors());
        }
        String text = fetched.text();
        synchronized (session) {
            session.originUrl = source;
            if (!MediaTailorHls.isMultivariant(text)) {
                session.multivariant = false;
                if (session.tracks.isEmpty()) {
                    session.tracks.add(new Track(source, null));
                }
                return playlist(media(session, configuration, 0, text));
            }
            session.multivariant = true;
            MediaTailorHls.Multivariant multivariant = MediaTailorHls.multivariant(text, source);
            if (session.tracks.isEmpty()) {
                multivariant.tracks.forEach(track -> session.tracks.add(new Track(track.uri, track)));
            }
        }
        String hostId = MediaTailorStore.hostId(account, region);
        MediaTailorHls.Multivariant multivariant = MediaTailorHls.multivariant(text, source);
        String out = MediaTailorHls.writeMultivariant(multivariant, index -> relative(path, "/v1/manifest/" + hostId
                + "/" + session.id + "/" + index + ".m3u8"));
        manifestLog(session, configuration, "ORIGIN_MANIFEST", Map.of("originManifestUrl", source));
        return playlist(out);
    }

    private MediaTailorHttp.Fetched origin(Session session, ObjectNode configuration, String url) {
        try {
            return http.get(url);
        } catch (MediaTailorHttp.Unreachable e) {
            manifestLog(session, configuration, e.timeout ? "TIMEOUT_ERROR" : "CONNECTION_ERROR",
                    Map.of("originManifestUrl", url, "message", e.getMessage()));
            throw new AwsException("BadGateway", e.getMessage(), 502);
        }
    }

    /** The origin URL of a session's manifest: the content source URL (with variables) and the request path. */
    private String originUrl(ObjectNode configuration, Session session) {
        String base = substitute(configuration.path("VideoContentSourceUrl").asText(""), session, null, configuration,
                "VideoContentSourceUrl");
        if (base.isEmpty()) {
            throw new AwsException("BadRequestException", "The playback configuration has no video content source.",
                    400);
        }
        return base.endsWith("/") ? base + session.path : base + "/" + session.path;
    }

    private Reply manifest(String[] parts, String path) {
        if (parts.length != 5 || !parts[4].endsWith(".m3u8")) {
            return Reply.text(404, "Not found.");
        }
        Session session = existing(parts[3]);
        ObjectNode configuration = configuration(session.account, session.region, session.config);
        int index;
        try {
            index = Integer.parseInt(parts[4].substring(0, parts[4].length() - ".m3u8".length()));
        } catch (NumberFormatException e) {
            return Reply.text(404, "Not found.");
        }
        Track track;
        synchronized (session) {
            if (index < 0 || index >= session.tracks.size()) {
                return Reply.text(404, "Not found.");
            }
            track = session.tracks.get(index);
        }
        MediaTailorHttp.Fetched fetched = origin(session, configuration, track.url);
        if (!fetched.ok()) {
            return Reply.text(fetched.status() == 404 ? 404 : 502, "The origin answered " + fetched.status()
                    + " for " + track.url + ".");
        }
        return playlist(media(session, configuration, index, fetched.text()));
    }

    private Reply playlist(String text) {
        return new Reply(200, "application/vnd.apple.mpegurl", text.getBytes(StandardCharsets.UTF_8), cors());
    }

    private Reply json(JsonNode node) {
        return new Reply(200, "application/json", node.toString().getBytes(StandardCharsets.UTF_8), cors());
    }

    private static Map<String, String> cors() {
        return Map.of("Access-Control-Allow-Origin", "*");
    }

    /** A path relative to a request's directory, so URLs work on whatever host the player reached. */
    static String relative(String from, String to) {
        String query = "";
        int mark = to.indexOf('?');
        if (mark >= 0) {
            query = to.substring(mark);
            to = to.substring(0, mark);
        }
        int v1 = from.indexOf("/v1/");
        String[] source = (v1 < 0 ? from : from.substring(v1)).replaceFirst("^/+", "").split("/");
        String[] target = to.replaceFirst("^/+", "").split("/");
        int common = 0;
        while (common < source.length - 1 && common < target.length - 1 && source[common].equals(target[common])) {
            common++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = common; i < source.length - 1; i++) {
            out.append("../");
        }
        for (int i = common; i < target.length; i++) {
            out.append(target[i]);
            if (i < target.length - 1) {
                out.append('/');
            }
        }
        return out + query;
    }

    // ================================================================ media playlists

    private String media(Session session, ObjectNode configuration, int index, String text) {
        Track track;
        synchronized (session) {
            track = session.tracks.get(index);
        }
        MediaTailorHls.Media media = MediaTailorHls.media(text, track.url);
        boolean vod = media.endList || "VOD".equals(media.playlistType);
        synchronized (session) {
            if (track.profile == null) {
                track.profile = profile(track, media);
            }
            plan(session, configuration, index, media, vod);
            String out = stitch(session, configuration, index, media);
            session.firstManifest = false;
            manifestLog(session, configuration, "GENERATED_MANIFEST", Map.of("originManifestUrl", track.url));
            return out;
        }
    }

    private static MediaTailorAds.Profile profile(Track track, MediaTailorHls.Media media) {
        MediaTailorHls.Track source = track.source;
        double target = media.targetDuration > 0 ? media.targetDuration : 6;
        if (source == null) {
            return new MediaTailorAds.Profile(0, 0, 0, false, false, media.fragmented(), target);
        }
        boolean videoOnly = "#EXT-X-STREAM-INF".equals(source.tag) && source.attributes.containsKey("AUDIO");
        return new MediaTailorAds.Profile(source.width(), source.height(), source.bandwidth(), source.audioOnly(),
                videoOnly && !source.audioOnly(), media.fragmented(), target);
    }

    /** Plans the avails a playlist shows a session for the first time: the markers', the pre-roll's, VMAP's. */
    private void plan(Session session, ObjectNode configuration, int index, MediaTailorHls.Media media, boolean vod) {
        double edge = media.duration();
        double time = 0;
        boolean markers = false;
        for (MediaTailorHls.Segment segment : media.segments) {
            MediaTailorHls.Marker marker = MediaTailorHls.marker(segment.tags);
            if (marker != null) {
                markers = true;
            }
            if (marker != null && MediaTailorHls.Marker.OUT.equals(marker.kind()) && marker.duration() > 0) {
                String id = marker.id() != null ? "d-" + marker.id() : "m-" + segment.sequence;
                if (!session.avails.containsKey(id)) {
                    Avail avail = new Avail(id, session.avails.size() + 1, false, marker.duration());
                    avail.startSequence = segment.sequence;
                    session.avails.put(id, avail);
                    if (!suppressed(session, configuration, avail, edge - time, vod)) {
                        fill(session, configuration, avail, Math.max(0, avail.duration - avail.shift), index);
                    }
                }
            }
            if (marker != null && MediaTailorHls.Marker.IN.equals(marker.kind())) {
                for (Avail avail : session.avails.values()) {
                    if (!avail.insert && avail.endSequence == null && avail.startSequence < segment.sequence
                            && (marker.id() == null || avail.id.equals("d-" + marker.id()))) {
                        avail.endSequence = segment.sequence;
                    }
                }
            }
            time += segment.duration;
        }
        if (media.segments.isEmpty()) {
            return;
        }
        if (vod && !markers && !session.vodPlanned) {
            session.vodPlanned = true;
            planVod(session, configuration, media, index);
        }
        if (!vod && !session.preRollPlanned) {
            session.preRollPlanned = true;
            JsonNode preRoll = configuration.path("LivePreRollConfiguration");
            String url = preRoll.path("AdDecisionServerUrl").asText("");
            int max = preRoll.path("MaxDurationSeconds").asInt(0);
            if (!url.isEmpty() && max > 0) {
                Avail avail = new Avail("preroll", 0, true, max);
                avail.startSequence = media.segments.get(Math.max(0, media.segments.size() - 3)).sequence;
                session.avails.put(avail.id, avail);
                fillFrom(session, configuration, avail, max, index, url, "PreRoll");
            }
        }
    }

    /** Whether avail suppression keeps an avail unfilled; with PARTIAL_AVAIL, shifts it to fill what's left. */
    private boolean suppressed(Session session, ObjectNode configuration, Avail avail, double behindEdge,
                               boolean vod) {
        if (vod) {
            return false;
        }
        JsonNode suppression = configuration.path("AvailSuppression");
        String mode = session.sessionParams.getOrDefault("availSuppressionMode", suppression.path("Mode")
                .asText("OFF"));
        String value = session.sessionParams.getOrDefault("availSuppressionValue", suppression.path("Value")
                .asText("00:00:00"));
        String policy = session.sessionParams.getOrDefault("availSuppressionFillPolicy", suppression.path("FillPolicy")
                .asText("FULL_AVAIL_ONLY"));
        double threshold = Math.max(0, MediaTailorAds.clock(value));
        boolean suppress;
        double sinceStart = (System.currentTimeMillis() - session.startedAt) / 1000.0;
        if ("BEHIND_LIVE_EDGE".equals(mode)) {
            suppress = session.firstManifest && behindEdge >= threshold;
        } else if ("AFTER_LIVE_EDGE".equals(mode)) {
            suppress = session.firstManifest || sinceStart - behindEdge < threshold;
        } else {
            return false;
        }
        if (suppress && "PARTIAL_AVAIL".equals(policy) && behindEdge < avail.duration) {
            avail.shift = behindEdge;
            return false;
        }
        return suppress;
    }

    private void planVod(Session session, ObjectNode configuration, MediaTailorHls.Media media, int index) {
        String url = substitute(configuration.path("AdDecisionServerUrl").asText(""), session, null, configuration,
                "AdDecisionServerUrl");
        if (url.isEmpty()) {
            return;
        }
        MediaTailorAds.Decision decision = request(session, configuration, url, null);
        double total = media.duration();
        if (!decision.breaks().isEmpty()) {
            int count = 0;
            for (MediaTailorAds.Break adBreak : decision.breaks()) {
                String offset = adBreak.timeOffset() == null ? "start" : adBreak.timeOffset().trim();
                double at;
                if ("start".equalsIgnoreCase(offset)) {
                    at = 0;
                } else if ("end".equalsIgnoreCase(offset)) {
                    at = total;
                } else if (offset.endsWith("%")) {
                    at = total * Double.parseDouble(offset.substring(0, offset.length() - 1)) / 100;
                } else {
                    at = MediaTailorAds.clock(offset);
                }
                if (at < 0) {
                    continue;
                }
                count++;
                Avail avail = new Avail("vmap-" + count, count, true, Double.MAX_VALUE);
                avail.contentOffset = at;
                avail.postRoll = at >= total - EPSILON;
                session.avails.put(avail.id, avail);
                place(session, configuration, avail, adBreak.ads(), Double.MAX_VALUE, index);
            }
        } else if (!decision.ads().isEmpty()) {
            Avail avail = new Avail("vod-preroll", 1, true, Double.MAX_VALUE);
            avail.contentOffset = 0;
            session.avails.put(avail.id, avail);
            place(session, configuration, avail, decision.ads(), Double.MAX_VALUE, index);
        }
    }

    // ================================================================ filling avails

    private void fill(Session session, ObjectNode configuration, Avail avail, double room, int index) {
        List<MediaTailorAds.Ad> chosen = prefetchedAds(session, configuration);
        if (chosen == null) {
            String url = substitute(configuration.path("AdDecisionServerUrl").asText(""), session, avail,
                    configuration, "AdDecisionServerUrl");
            if (url.isEmpty()) {
                fillSlate(session, configuration, avail, room, index, 0);
                return;
            }
            MediaTailorAds.Decision decision = request(session, configuration, url, avail);
            chosen = decision.ads();
            if (chosen.isEmpty() && !decision.breaks().isEmpty()) {
                chosen = decision.breaks().get(0).ads();
            }
        }
        place(session, configuration, avail, chosen, room, index);
    }

    private void fillFrom(Session session, ObjectNode configuration, Avail avail, double room, int index, String url,
                          String member) {
        MediaTailorAds.Decision decision = request(session, configuration, substitute(url, session, avail,
                configuration, member), avail);
        place(session, configuration, avail, decision.ads(), room, index);
    }

    private MediaTailorAds.Decision request(Session session, ObjectNode configuration, String url, Avail avail) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("adsRequestUrl", url);
        if (avail != null) {
            details.put("availId", avail.id);
        }
        adsLog(session, configuration, "MAKING_ADS_REQUEST", details);
        MediaTailorAds.Decision decision = ads.decide(url, redirect -> adsLog(session, configuration, "VAST_REDIRECT",
                Map.of("vastAdTagUri", redirect)));
        if (decision.error() != null && decision.ads().isEmpty() && decision.breaks().isEmpty()) {
            adsLog(session, configuration, decision.errorCode(), Map.of("adsRequestUrl", url, "message",
                    decision.error()));
        } else {
            adsLog(session, configuration, decision.breaks().isEmpty() ? "VAST_RESPONSE" : "VMAP_RESPONSE",
                    Map.of("adsRequestUrl", url, "ads", decision.ads().size()));
        }
        return decision;
    }

    private boolean conditioned(ObjectNode configuration) {
        return !"NONE".equals(configuration.path("AdConditioningConfiguration").path("StreamingMediaFileConditioning")
                .asText("TRANSCODE"));
    }

    /** The profiles of all a session's tracks, reading the playlists of tracks not yet requested. */
    private List<MediaTailorAds.Profile> profiles(Session session, int known) {
        List<MediaTailorAds.Profile> profiles = new ArrayList<>();
        MediaTailorAds.Profile fallback = session.tracks.get(known).profile;
        for (Track track : session.tracks) {
            if (track.profile == null) {
                try {
                    MediaTailorHttp.Fetched fetched = http.get(track.url);
                    if (fetched.ok()) {
                        track.profile = profile(track, MediaTailorHls.media(fetched.text(), track.url));
                    }
                } catch (MediaTailorHttp.Unreachable e) {
                    LOG.debugv("MediaTailor could not read the track {0}: {1}", track.url, e.getMessage());
                }
            }
            profiles.add(track.profile == null ? new MediaTailorAds.Profile(0, 0, 0, track.source != null
                    && track.source.audioOnly(), false, fallback.fragmented(), fallback.segmentSeconds())
                    : track.profile);
        }
        return profiles;
    }

    /** One creative's renditions for every track, when all are ready by the deadline. */
    private Map<Integer, MediaTailorAds.Rendition> renditions(Session session, ObjectNode configuration,
                                                             MediaTailorAds.Ad ad, String url,
                                                             List<MediaTailorAds.Profile> profiles, long deadline) {
        boolean conditioned = conditioned(configuration);
        List<CompletableFuture<MediaTailorAds.Rendition>> futures = new ArrayList<>();
        List<MediaTailorAds.MediaFile> files = new ArrayList<>();
        for (MediaTailorAds.Profile profile : profiles) {
            MediaTailorAds.MediaFile file = url != null ? new MediaTailorAds.MediaFile(url, "progressive",
                    url.toLowerCase(Locale.ROOT).contains(".m3u8") ? "application/x-mpegURL" : "video/mp4", 0, 0, 0)
                    : MediaTailorAds.choose(ad, profile, conditioned);
            if (file == null) {
                adsLog(session, configuration, conditioned ? "ERROR_VAST_MISSING_MEDIAFILES"
                        : "ERROR_VAST_INVALID_MEDIA_FILE", Map.of("adId", ad == null ? "" : ad.id, "message",
                        conditioned ? "The ad has no video media file." : "With ad conditioning NONE, an ad needs "
                                + "an HLS media file."));
                return null;
            }
            files.add(file);
            futures.add(ads.rendition(file, profile, conditioned));
        }
        Map<Integer, MediaTailorAds.Rendition> ready = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            Optional<MediaTailorAds.Rendition> rendition = MediaTailorAds.await(files.get(i), futures.get(i), deadline,
                    message -> adsLog(session, configuration, "WARNING_NO_ADVERTISEMENTS", Map.of("message",
                            message)));
            if (rendition.isEmpty()) {
                return null;
            }
            ready.put(i, rendition.get());
        }
        return ready;
    }

    /** Places the ads that fit an avail (with bumpers), then fills the rest with the slate. */
    private void place(Session session, ObjectNode configuration, Avail avail, List<MediaTailorAds.Ad> candidates,
                       double room, int index) {
        List<MediaTailorAds.Profile> profiles = profiles(session, index);
        long deadline = System.currentTimeMillis() + ads.waitMillis();
        double time = 0;
        JsonNode bumper = configuration.path("Bumper");
        Placed start = null;
        Placed end = null;
        if (!bumper.path("StartUrl").asText("").isEmpty()) {
            start = clip(session, configuration, "bumper", substitute(bumper.path("StartUrl").asText(), session, avail,
                    configuration, "Bumper.StartUrl"), profiles, deadline, 0);
        }
        if (!bumper.path("EndUrl").asText("").isEmpty()) {
            end = clip(session, configuration, "bumper", substitute(bumper.path("EndUrl").asText(), session, avail,
                    configuration, "Bumper.EndUrl"), profiles, deadline, 0);
        }
        double reserved = (start == null ? 0 : start.duration) + (end == null ? 0 : end.duration);
        List<Placed> chosen = new ArrayList<>();
        double adTime = 0;
        for (MediaTailorAds.Ad ad : candidates) {
            Map<Integer, MediaTailorAds.Rendition> renditions = renditions(session, configuration, ad, null, profiles,
                    deadline);
            if (renditions == null) {
                continue;
            }
            double length = renditions.get(0).duration();
            if (adTime + length + reserved > room + FIT_TOLERANCE) {
                continue;
            }
            chosen.add(new Placed("ad", ad, 0, length, renditions));
            adTime += length;
        }
        if (chosen.isEmpty()) {
            adsLog(session, configuration, "WARNING_NO_ADVERTISEMENTS", Map.of("availId", avail.id));
            if (!avail.insert) {
                fillSlate(session, configuration, avail, room, index, 0);
            }
            return;
        }
        double unfilled = avail.insert ? 0 : room - adTime - reserved;
        String slate = configuration.path("SlateAdUrl").asText("");
        if (!avail.insert && slate.isEmpty() && configuration.hasNonNull("PersonalizationThresholdSeconds")
                && unfilled > configuration.get("PersonalizationThresholdSeconds").asDouble() + EPSILON) {
            adsLog(session, configuration, "WARNING_NO_ADVERTISEMENTS", Map.of("availId", avail.id, "message",
                    "The ads leave more of the avail unfilled than the personalization threshold."));
            return;
        }
        if (start != null) {
            avail.placed.add(new Placed(start.kind, null, time, start.duration, start.renditions));
            time += start.duration;
        }
        for (Placed ad : chosen) {
            avail.placed.add(new Placed(ad.kind, ad.ad, time, ad.duration, ad.renditions));
            time += ad.duration;
        }
        if (end != null) {
            avail.placed.add(new Placed(end.kind, null, time, end.duration, end.renditions));
            time += end.duration;
        }
        adsLog(session, configuration, "FILLED_AVAIL", Map.of("availId", avail.id, "filledDuration", time,
                "originAvailDuration", avail.insert ? time : room));
        if (!avail.insert) {
            fillSlate(session, configuration, avail, room, index, time);
        }
    }

    private void fillSlate(Session session, ObjectNode configuration, Avail avail, double room, int index,
                           double from) {
        String slate = configuration.path("SlateAdUrl").asText("");
        if (slate.isEmpty() || room - from < EPSILON) {
            return;
        }
        List<MediaTailorAds.Profile> profiles = profiles(session, index);
        Placed clip = clip(session, configuration, "slate", substitute(slate, session, avail, configuration,
                "SlateAdUrl"), profiles, System.currentTimeMillis() + ads.waitMillis(), 0);
        if (clip == null || clip.duration <= EPSILON) {
            return;
        }
        double time = from;
        while (room - time > EPSILON) {
            double length = Math.min(clip.duration, room - time);
            avail.placed.add(new Placed("slate", null, time, length, clip.renditions));
            time += length;
        }
    }

    private Placed clip(Session session, ObjectNode configuration, String kind, String url,
                        List<MediaTailorAds.Profile> profiles, long deadline, double at) {
        Map<Integer, MediaTailorAds.Rendition> renditions = renditions(session, configuration, null, url, profiles,
                deadline);
        return renditions == null ? null : new Placed(kind, null, at, renditions.get(0).duration(), renditions);
    }

    // ================================================================ stitching

    /** An avail-relative segment of what's placed in an avail. */
    private record Piece(int placed, int segment, double start, double duration) {
    }

    private static List<Piece> pieces(Avail avail, int track) {
        List<Piece> pieces = new ArrayList<>();
        for (int p = 0; p < avail.placed.size(); p++) {
            Placed placed = avail.placed.get(p);
            MediaTailorAds.Rendition rendition = placed.renditions.get(track);
            if (rendition == null) {
                continue;
            }
            double time = placed.start;
            for (int s = 0; s < rendition.durations.size(); s++) {
                double length = rendition.durations.get(s);
                if (time - placed.start + EPSILON >= placed.duration) {
                    break;
                }
                pieces.add(new Piece(p, s, time - avail.shift, length));
                time += length;
            }
        }
        return pieces;
    }

    /** The replacing avail a content segment falls in, with its offset into the avail. */
    private Object[] covering(Session session, Track track, long sequence) {
        Avail found = null;
        for (Avail avail : session.avails.values()) {
            if (!avail.insert && avail.startSequence >= 0 && avail.startSequence <= sequence
                    && (found == null || avail.startSequence > found.startSequence)) {
                found = avail;
            }
        }
        if (found == null || found.endSequence != null && sequence >= found.endSequence) {
            return null;
        }
        double offset = 0;
        double target = track.profile == null ? 6 : track.profile.segmentSeconds();
        for (long s = found.startSequence; s < sequence; s++) {
            offset += track.durations.getOrDefault(s, target);
        }
        if (offset + EPSILON >= found.duration) {
            return null;
        }
        return new Object[] {found, offset};
    }

    private static final class Out {
        final List<String> tags = new ArrayList<>();
        final double duration;
        final String title;
        final String uri;
        final boolean ad;

        Out(double duration, String title, String uri, boolean ad) {
            this.duration = duration;
            this.title = title;
            this.uri = uri;
            this.ad = ad;
        }
    }

    private String stitch(Session session, ObjectNode configuration, int index, MediaTailorHls.Media media) {
        Track track = session.tracks.get(index);
        boolean passthrough = configuration.path("ManifestProcessingRules").path("AdMarkerPassthrough")
                .path("Enabled").asBoolean(false);
        if (track.baseDiscontinuity == null) {
            track.baseDiscontinuity = media.discontinuitySequence;
        }
        double maxDuration = media.targetDuration;
        List<Out> outs = new ArrayList<>();
        Long firstSequence = null;
        long firstDiscontinuities = 0;
        for (int i = 0; i < media.segments.size(); i++) {
            MediaTailorHls.Segment segment = media.segments.get(i);
            track.durations.put(segment.sequence, segment.duration);
            if (segment.map != null) {
                track.contentMap = segment.map;
            }
            List<Out> emitted = emit(session, configuration, track, index, media, segment, i, passthrough);
            long[] mapping = track.emitted.get(segment.sequence);
            if (mapping == null) {
                Map.Entry<Long, long[]> previous = track.emitted.lowerEntry(segment.sequence);
                long sequence = previous == null ? segment.sequence : previous.getValue()[0] + previous.getValue()[1]
                        + (segment.sequence - previous.getKey() - 1);
                long discontinuities = previous == null ? 0 : previous.getValue()[2] + previous.getValue()[3];
                long count = emitted.stream().filter(out -> out.tags.contains("#EXT-X-DISCONTINUITY")).count();
                double time = previous == null ? 0 : track.times.getOrDefault(previous.getKey(), 0.0)
                        + track.emittedDuration(previous.getKey());
                mapping = new long[] {sequence, emitted.size(), discontinuities, count};
                track.emitted.put(segment.sequence, mapping);
                track.times.put(segment.sequence, time);
                track.lengths.put(segment.sequence, emitted.stream().mapToDouble(out -> out.duration).sum());
                while (track.emitted.size() > 4096) {
                    Long oldest = track.emitted.firstKey();
                    track.emitted.remove(oldest);
                    track.times.remove(oldest);
                    track.lengths.remove(oldest);
                    track.durations.remove(oldest);
                }
            }
            if (!emitted.isEmpty() && firstSequence == null) {
                firstSequence = mapping[0];
                firstDiscontinuities = mapping[2];
            }
            recordSessionTimes(session, track, segment.sequence, emitted, index);
            boolean endsWithAd = emitted.isEmpty() ? previousEndsWithAd(track, segment.sequence)
                    : emitted.get(emitted.size() - 1).ad;
            if (endsWithAd) {
                track.endsWithAd.add(segment.sequence);
            } else {
                track.endsWithAd.remove(segment.sequence);
            }
            for (Out out : emitted) {
                maxDuration = Math.max(maxDuration, out.duration);
            }
            outs.addAll(emitted);
        }
        if (media.endList) {
            for (Avail avail : session.avails.values()) {
                if (avail.insert && avail.postRoll && avail.filled()) {
                    outs.addAll(insertion(session, configuration, avail, index, true));
                }
            }
        }
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        boolean fragmented = media.fragmented() || outs.stream().anyMatch(o -> o.tags.stream().anyMatch(
                tag -> tag.startsWith("#EXT-X-MAP")));
        out.append("#EXT-X-VERSION:").append(Math.max(media.version, fragmented ? 6 : 3)).append('\n');
        out.append("#EXT-X-TARGETDURATION:").append((long) Math.ceil(maxDuration - EPSILON)).append('\n');
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequence == null ? media.mediaSequence : firstSequence)
                .append('\n');
        out.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(track.baseDiscontinuity + firstDiscontinuities)
                .append('\n');
        if (media.playlistType != null) {
            out.append("#EXT-X-PLAYLIST-TYPE:").append(media.playlistType).append('\n');
        }
        media.header.forEach(line -> out.append(line).append('\n'));
        for (Out segment : outs) {
            segment.tags.forEach(tag -> out.append(tag).append('\n'));
            out.append("#EXTINF:").append(MediaTailorHls.seconds(segment.duration)).append(',')
                    .append(segment.title == null ? "" : segment.title).append('\n');
            out.append(segment.uri).append('\n');
        }
        if (media.endList) {
            out.append("#EXT-X-ENDLIST\n");
        }
        return out.toString();
    }

    private List<Out> emit(Session session, ObjectNode configuration, Track track, int index,
                           MediaTailorHls.Media media, MediaTailorHls.Segment segment, int position,
                           boolean passthrough) {
        List<Out> outs = new ArrayList<>();
        for (Avail avail : session.avails.values()) {
            if (avail.insert && !avail.postRoll && avail.filled() && inserts(avail, track, media, segment, position)) {
                outs.addAll(insertion(session, configuration, avail, index, false));
            }
        }
        List<String> markers = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String tag : segment.tags) {
            if (MediaTailorHls.adMarker(tag)) {
                markers.add(tag);
            } else if (!tag.startsWith("#EXT-X-MAP")) {
                others.add(tag);
            }
        }
        Object[] covered = covering(session, track, segment.sequence);
        if (covered != null && ((Avail) covered[0]).filled()) {
            Avail avail = (Avail) covered[0];
            double offset = (double) covered[1];
            double end = offset + segment.duration;
            double filled = avail.filledDuration() - avail.shift;
            List<Piece> pieces = pieces(avail, index);
            for (int p = 0; p < pieces.size(); p++) {
                Piece piece = pieces.get(p);
                if (piece.start() < offset - EPSILON || piece.start() >= end - EPSILON) {
                    continue;
                }
                Out out = adSegment(session, configuration, avail, piece, index);
                Piece before = p == 0 ? null : pieces.get(p - 1);
                boolean opens = before == null || before.placed() != piece.placed() || before.start() < -EPSILON;
                if (opens) {
                    out.tags.add("#EXT-X-DISCONTINUITY");
                    MediaTailorAds.Rendition rendition = avail.placed.get(piece.placed()).renditions.get(index);
                    if (rendition.init != null || rendition.initUrl != null) {
                        out.tags.add("#EXT-X-MAP:URI=\"" + adUri(session, configuration, avail, piece.placed(),
                                index, "init.mp4") + "\"");
                    }
                }
                outs.add(out);
            }
            if (offset + EPSILON >= filled) {
                // the ads ended within the avail: its content plays out
                boolean afterAd = !outs.isEmpty() || previousEndsWithAd(track, segment.sequence);
                outs.add(content(configuration, track, segment, others, passthrough ? markers : List.of(), afterAd));
            } else if (passthrough && !outs.isEmpty()) {
                outs.get(0).tags.addAll(markers);
            }
            return outs;
        }
        boolean afterAd = !outs.isEmpty() && outs.get(outs.size() - 1).ad || previousEndsWithAd(track,
                segment.sequence);
        outs.add(content(configuration, track, segment, others, passthrough ? markers : List.of(), afterAd));
        return outs;
    }

    private boolean previousEndsWithAd(Track track, long sequence) {
        return track.endsWithAd.contains(sequence - 1);
    }

    private Out content(ObjectNode configuration, Track track, MediaTailorHls.Segment segment, List<String> tags,
                        List<String> markers, boolean afterAd) {
        Out out = new Out(segment.duration, segment.title, contentUri(configuration, track, segment.uri), false);
        boolean discontinuity = tags.contains("#EXT-X-DISCONTINUITY");
        if (afterAd && !discontinuity) {
            out.tags.add("#EXT-X-DISCONTINUITY");
        }
        out.tags.addAll(tags);
        out.tags.addAll(markers);
        boolean mapped = segment.tags.stream().anyMatch(tag -> tag.startsWith("#EXT-X-MAP"));
        if (segment.map != null && (mapped || afterAd)) {
            out.tags.add("#EXT-X-MAP:URI=\"" + contentUri(configuration, track, segment.map) + "\"");
        }
        return out;
    }

    private boolean inserts(Avail avail, Track track, MediaTailorHls.Media media, MediaTailorHls.Segment segment,
                            int position) {
        if (avail.startSequence >= 0) {
            return avail.startSequence == segment.sequence;
        }
        double time = 0;
        for (int i = 0; i < position; i++) {
            time += media.segments.get(i).duration;
        }
        double previous = position == 0 ? -1 : time - media.segments.get(position - 1).duration;
        return avail.contentOffset <= time + EPSILON && avail.contentOffset > previous + EPSILON;
    }

    private List<Out> insertion(Session session, ObjectNode configuration, Avail avail, int index, boolean postRoll) {
        List<Out> outs = new ArrayList<>();
        for (Piece piece : pieces(avail, index)) {
            Out out = adSegment(session, configuration, avail, piece, index);
            if (piece.segment() == 0) {
                out.tags.add("#EXT-X-DISCONTINUITY");
                MediaTailorAds.Rendition rendition = avail.placed.get(piece.placed()).renditions.get(index);
                if (rendition.init != null || rendition.initUrl != null) {
                    out.tags.add("#EXT-X-MAP:URI=\"" + adUri(session, configuration, avail, piece.placed(), index,
                            "init.mp4") + "\"");
                }
            }
            outs.add(out);
        }
        return outs;
    }

    private Out adSegment(Session session, ObjectNode configuration, Avail avail, Piece piece, int index) {
        MediaTailorAds.Rendition rendition = avail.placed.get(piece.placed()).renditions.get(index);
        return new Out(piece.duration(), "", adUri(session, configuration, avail, piece.placed(), index,
                piece.segment() + "." + rendition.extension), true);
    }

    private String adUri(Session session, ObjectNode configuration, Avail avail, int placed, int track, String file) {
        String path = "/v1/segment/" + MediaTailorStore.hostId(session.account, session.region) + "/"
                + session.config + "/" + session.id + "/" + URLEncoder.encode(avail.id, StandardCharsets.UTF_8) + "/"
                + placed + "/" + track + "/" + file;
        String cdn = configuration.path("CdnConfiguration").path("AdSegmentUrlPrefix").asText("");
        if (!cdn.isEmpty()) {
            return cdn.replaceAll("/+$", "") + path;
        }
        return store.hostPrefix(MediaTailorStore.PLAYBACK, session.account, session.region) + path;
    }

    private String contentUri(ObjectNode configuration, Track track, String uri) {
        String cdn = configuration.path("CdnConfiguration").path("ContentSegmentUrlPrefix").asText("");
        if (cdn.isEmpty()) {
            return uri;
        }
        String origin = configuration.path("VideoContentSourceUrl").asText("").replaceAll("/+$", "");
        if (!origin.isEmpty() && uri.startsWith(origin)) {
            return cdn.replaceAll("/+$", "") + uri.substring(origin.length());
        }
        URI parsed = URI.create(uri);
        return cdn.replaceAll("/+$", "") + parsed.getRawPath() + (parsed.getRawQuery() == null ? ""
                : "?" + parsed.getRawQuery());
    }

    /** Records where on the session's timeline each avail starts, for the tracking response. */
    private void recordSessionTimes(Session session, Track track, long sequence, List<Out> emitted, int index) {
        if (index != 0) {
            return;
        }
        double time = track.times.getOrDefault(sequence, 0.0);
        for (Out out : emitted) {
            if (out.ad) {
                for (Avail avail : session.avails.values()) {
                    if (avail.sessionTime == null && out.uri.contains("/" + URLEncoder.encode(avail.id,
                            StandardCharsets.UTF_8) + "/")) {
                        avail.sessionTime = time;
                    }
                }
            }
            time += out.duration;
        }
        for (Avail avail : session.avails.values()) {
            if (!avail.ended && !avail.insert && (avail.endSequence != null && sequence >= avail.endSequence
                    || covering(session, track, sequence) == null && avail.startSequence >= 0
                    && sequence > avail.startSequence)) {
                avail.ended = true;
                recurring(session);
            }
        }
    }

    // ================================================================ segments and beacons

    private Reply segment(String[] parts) {
        if (parts.length != 9) {
            return Reply.text(404, "Not found.");
        }
        Session session = existing(parts[4]);
        ObjectNode configuration = configuration(session.account, session.region, session.config);
        String availId = URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
        Placed placed;
        int track;
        synchronized (session) {
            Avail avail = session.avails.get(availId);
            if (avail == null) {
                return Reply.text(404, "Not found.");
            }
            try {
                int p = Integer.parseInt(parts[6]);
                track = Integer.parseInt(parts[7]);
                if (p < 0 || p >= avail.placed.size()) {
                    return Reply.text(404, "Not found.");
                }
                placed = avail.placed.get(p);
            } catch (NumberFormatException e) {
                return Reply.text(404, "Not found.");
            }
        }
        MediaTailorAds.Rendition rendition = placed.renditions.get(track);
        if (rendition == null) {
            return Reply.text(404, "Not found.");
        }
        String file = parts[8];
        if ("init.mp4".equals(file)) {
            byte[] init = rendition.init != null ? rendition.init : rendition.initUrl != null
                    ? proxy(rendition.initUrl) : null;
            return init == null ? Reply.text(404, "Not found.") : new Reply(200, "video/mp4", init, cors());
        }
        int dot = file.indexOf('.');
        int index;
        try {
            index = Integer.parseInt(dot < 0 ? file : file.substring(0, dot));
        } catch (NumberFormatException e) {
            return Reply.text(404, "Not found.");
        }
        if (index < 0 || index >= rendition.durations.size()) {
            return Reply.text(404, "Not found.");
        }
        byte[] bytes = index < rendition.segments.size() ? rendition.segments.get(index)
                : proxy(rendition.urls.get(index));
        if (bytes == null) {
            return Reply.text(502, "The ad segment couldn't be read.");
        }
        if ("ad".equals(placed.kind) && !session.clientReporting) {
            beacons(session, configuration, availId, placed, rendition, index);
        }
        return new Reply(200, "ts".equals(rendition.extension) ? "video/MP2T" : "video/mp4", bytes, cors());
    }

    private byte[] proxy(String url) {
        try {
            MediaTailorHttp.Fetched fetched = http.get(url);
            return fetched.ok() ? fetched.body() : null;
        } catch (MediaTailorHttp.Unreachable e) {
            return null;
        }
    }

    /** Fires an ad's beacons for the part of it a segment request reaches: impressions, start, quartiles. */
    private void beacons(Session session, ObjectNode configuration, String availId, Placed placed,
                         MediaTailorAds.Rendition rendition, int index) {
        double start = 0;
        for (int i = 0; i < index; i++) {
            start += rendition.durations.get(i);
        }
        double end = start + rendition.durations.get(index);
        boolean last = index == rendition.durations.size() - 1;
        double length = placed.duration;
        List<String> events = new ArrayList<>();
        if (index == 0) {
            events.add("impression");
            events.add("creativeView");
            events.add("start");
        }
        for (Map.Entry<String, Double> quartile : List.of(Map.entry("firstQuartile", 0.25), Map.entry("midpoint",
                0.5), Map.entry("thirdQuartile", 0.75))) {
            double at = length * quartile.getValue();
            if (at >= start - EPSILON && at < end - EPSILON) {
                events.add(quartile.getKey());
            }
        }
        if (last) {
            events.add("complete");
        }
        for (String event : events) {
            String key = availId + "/" + placed.start + "/" + event;
            if (!session.fired.add(key)) {
                continue;
            }
            List<String> urls = "impression".equals(event) ? placed.ad.impressions
                    : placed.ad.tracking.getOrDefault(event, List.of());
            for (String url : urls) {
                String target = substitute(url, session, null, configuration, "beacon");
                http.fire(target, failure -> adsLog(session, configuration, "ERROR_FIRING_BEACON_FAILED", Map.of(
                        "beaconInfo", Map.of("beaconUri", target, "trackingEvent", event), "message",
                        String.valueOf(failure.getMessage()))));
                adsLog(session, configuration, "BEACON_FIRED", Map.of("beaconInfo", Map.of("beaconUri", target,
                        "trackingEvent", event), "adId", placed.ad.id));
            }
        }
    }

    // ================================================================ tracking

    private Reply tracking(String[] parts) {
        if (parts.length != 5) {
            return Reply.text(404, "Not found.");
        }
        Session session = existing(parts[4]);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode avails = out.putArray("avails");
        synchronized (session) {
            for (Avail avail : session.avails.values()) {
                if (!avail.filled()) {
                    continue;
                }
                double availStart = avail.sessionTime == null ? 0 : avail.sessionTime;
                ObjectNode node = avails.addObject();
                node.put("availId", avail.id);
                node.put("availProgramDateTime", (String) null);
                node.put("duration", iso(avail.filledDuration()));
                node.put("durationInSeconds", round(avail.filledDuration()));
                node.put("startTime", iso(availStart));
                node.put("startTimeInSeconds", round(availStart));
                node.putArray("adBreakTrackingEvents");
                node.putArray("adMarkerDuration");
                ArrayNode list = node.putArray("ads");
                for (Placed placed : avail.placed) {
                    if (!"ad".equals(placed.kind)) {
                        continue;
                    }
                    double adStart = availStart + placed.start;
                    ObjectNode ad = list.addObject();
                    ad.put("adId", placed.ad.id);
                    ad.put("adSystem", placed.ad.adSystem);
                    ad.put("adTitle", placed.ad.adTitle);
                    ad.put("creativeId", placed.ad.creativeId);
                    ad.put("creativeSequence", placed.ad.sequence == null ? "" : placed.ad.sequence);
                    ad.put("vastAdId", placed.ad.id);
                    ad.put("duration", iso(placed.duration));
                    ad.put("durationInSeconds", round(placed.duration));
                    ad.put("startTime", iso(adStart));
                    ad.put("startTimeInSeconds", round(adStart));
                    ad.putArray("adVerifications");
                    ad.putArray("companionAds");
                    ad.putArray("extensions");
                    ArrayNode events = ad.putArray("trackingEvents");
                    tracking(events, "impression", placed.ad.impressions, adStart, 0, session, avail);
                    for (Map.Entry<String, List<String>> event : placed.ad.tracking.entrySet()) {
                        double at = switch (event.getKey()) {
                            case "firstQuartile" -> placed.duration * 0.25;
                            case "midpoint" -> placed.duration * 0.5;
                            case "thirdQuartile" -> placed.duration * 0.75;
                            case "complete" -> placed.duration;
                            default -> 0;
                        };
                        tracking(events, event.getKey(), event.getValue(), adStart + at, 0, session, avail);
                    }
                    if (placed.ad.clickThrough != null) {
                        ad.putObject("videoClicks").put("clickThrough", placed.ad.clickThrough);
                    }
                }
            }
        }
        out.putArray("nonLinearAvails");
        out.putNull("nextToken");
        return json(out);
    }

    private void tracking(ArrayNode events, String type, List<String> urls, double at, double duration,
                          Session session, Avail avail) {
        if (urls.isEmpty()) {
            return;
        }
        ObjectNode event = events.addObject();
        event.put("eventId", avail.id + "_" + type + "_" + Math.round(at * 1000));
        event.put("eventType", type);
        event.put("startTime", iso(at));
        event.put("startTimeInSeconds", round(at));
        event.put("duration", iso(duration));
        event.put("durationInSeconds", round(duration));
        ArrayNode beacons = event.putArray("beaconUrls");
        ObjectNode configuration = store.getForAccount(session.account, session.region, MediaTailorStore.CONFIG,
                session.config).orElse(mapper.createObjectNode());
        urls.forEach(url -> beacons.add(substitute(url, session, avail, configuration, "beacon")));
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }

    private static String iso(double seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000)).toString();
    }

    // ================================================================ DASH

    private String dash(Session session, ObjectNode configuration, String mpd, String source) {
        String base = source.substring(0, source.lastIndexOf('/') + 1);
        String out = mpd;
        if (!mpd.contains("<BaseURL")) {
            Matcher period = Pattern.compile("<(Location|Period)[\\s>]").matcher(mpd);
            int at = period.find() ? period.start() : -1;
            if (at > 0) {
                out = mpd.substring(0, at) + "<BaseURL>" + xml(base) + "</BaseURL>\n  " + mpd.substring(at);
            }
        }
        if (mpd.contains("type=\"dynamic\"") && !"DISABLED".equals(configuration.path("DashConfiguration")
                .path("MpdLocation").asText("EMT_DEFAULT")) && !out.contains("<Location")) {
            String location = store.hostPrefix(MediaTailorStore.PLAYBACK, session.account, session.region) + "/v1/dash/"
                    + MediaTailorStore.hostId(session.account, session.region) + "/" + session.config + "/"
                    + session.path + "?aws.sessionId=" + session.id;
            Matcher period = Pattern.compile("<Period[\\s>]").matcher(out);
            if (period.find()) {
                out = out.substring(0, period.start()) + "<Location>" + xml(location) + "</Location>\n  "
                        + out.substring(period.start());
            }
        }
        manifestLog(session, configuration, "GENERATED_MANIFEST", Map.of("originManifestUrl", source));
        return out;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ================================================================ variables

    /**
     * Fills a URL template's dynamic variables: session data ({@code [session.id]}, {@code [session.avail_*]},
     * {@code [session.client_ip]}, ...), avail data ({@code [avail.index]}, {@code [avail.random]}) and player
     * parameters ({@code [player_params.*]}), through the configuration's aliases.
     */
    String substitute(String template, Session session, Avail avail, ObjectNode configuration, String member) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variable(name, session, avail);
            JsonNode alias = configuration.path("ConfigurationAliases").path(name);
            if (alias.isObject() && value != null && alias.has(value)) {
                value = alias.get(value).asText();
            }
            if (value == null) {
                adsLog(session, configuration, "WARNING_URL_VARIABLE_SUBSTITUTION_FAILED", Map.of("variable", name,
                        "member", member));
                value = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(encode(value, alias.isObject())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String encode(String value, boolean aliased) {
        return aliased ? value : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String variable(String name, Session session, Avail avail) {
        if (name.startsWith("player_params.")) {
            return session.playerParams.get(name.substring("player_params.".length()));
        }
        double duration = avail == null ? 0 : avail.insert && avail.duration == Double.MAX_VALUE ? 0
                : avail.duration;
        return switch (name) {
            case "session.id", "session.uuid" -> session.id;
            case "session.avail_duration_secs" -> String.valueOf(Math.round(duration));
            case "session.avail_duration_ms" -> String.valueOf(Math.round(duration * 1000));
            case "session.avail_num" -> String.valueOf(avail == null ? 0 : avail.index);
            case "avail.index" -> String.valueOf(avail == null ? 0 : avail.index);
            case "avail.random" -> String.valueOf(ThreadLocalRandom.current().nextLong(0, 10_000_000_000L));
            case "session.client_ip" -> session.clientIp == null ? "" : session.clientIp;
            case "session.user_agent" -> session.userAgent == null ? "" : session.userAgent;
            case "session.referer" -> session.referer == null ? "" : session.referer;
            case "session.start_time" -> Instant.ofEpochMilli(session.startedAt).toString();
            case "scte.event_id", "scte.segmentation_event_id", "scte.unique_program_id", "scte.avail_num",
                 "scte.avails_expected", "scte.segmentation_type_id", "scte.segmentation_upid" -> "";
            default -> null;
        };
    }

    private static String sessionValue(Session session, String variable) {
        if (variable.startsWith("player_params.")) {
            return session.playerParams.get(variable.substring("player_params.".length()));
        }
        return variable(variable, session, null);
    }

    // ================================================================ prefetch

    private static String prefetchKey(String account, String region, String config, String name) {
        return account + "/" + region + "/" + config + "/" + name;
    }

    /** Schedules a prefetch schedule's retrieval at the start of its retrieval window. */
    void schedulePrefetch(String account, String region, String config, String name) {
        ObjectNode schedule = store.getForAccount(account, region, MediaTailorStore.PREFETCH,
                MediaTailorService.prefetchKey(config, name)).orElse(null);
        if (schedule == null || !"SINGLE".equals(schedule.path("ScheduleType").asText("SINGLE"))) {
            return;
        }
        double start = schedule.path("Retrieval").path("StartTime").asDouble(MediaTailorStore.epoch());
        long delay = Math.max(1, Math.round((start - MediaTailorStore.epoch()) * 1000));
        String key = prefetchKey(account, region, config, name);
        Long previous = timers.remove(key);
        if (previous != null) {
            vertx.cancelTimer(previous);
        }
        timers.put(key, vertx.setTimer(delay, id -> vertx.executeBlocking(() -> {
            timers.remove(key);
            retrieve(account, region, config, name, schedule.path("Retrieval").path("DynamicVariables"),
                    schedule.path("Consumption").path("EndTime").asDouble(Double.MAX_VALUE));
            return null;
        }, false)));
    }

    void forgetPrefetch(String account, String region, String config, String name) {
        String key = prefetchKey(account, region, config, name);
        prefetched.remove(key);
        Long timer = timers.remove(key);
        if (timer != null) {
            vertx.cancelTimer(timer);
        }
    }

    private void retrieve(String account, String region, String config, String name, JsonNode variables,
                          double expires) {
        ObjectNode configuration = store.getForAccount(account, region, MediaTailorStore.CONFIG, config).orElse(null);
        if (configuration == null) {
            return;
        }
        Map<String, String> player = new LinkedHashMap<>();
        variables.properties().forEach(entry -> {
            if (entry.getKey().startsWith("player_params.")) {
                player.put(entry.getKey().substring("player_params.".length()), entry.getValue().asText());
            }
        });
        Session probe = new Session("prefetch-" + name, account, region, config, "", false, true, player, Map.of(),
                null, "Floci MediaTailor", null, false);
        String url = substitute(configuration.path("AdDecisionServerUrl").asText(""), probe, null, configuration,
                "AdDecisionServerUrl");
        if (url.isEmpty()) {
            return;
        }
        MediaTailorAds.Decision decision = ads.decide(url, redirect -> { });
        List<MediaTailorAds.Ad> found = decision.ads().isEmpty() && !decision.breaks().isEmpty()
                ? decision.breaks().get(0).ads() : decision.ads();
        prefetched.put(prefetchKey(account, region, config, name), new Prefetched(found, MediaTailorStore.epoch(),
                expires));
        LOG.debugv("MediaTailor prefetch schedule {0} retrieved {1} ads", name, found.size());
    }

    /** The ads a prefetch schedule retrieved for an avail starting now that the session matches, if any. */
    private List<MediaTailorAds.Ad> prefetchedAds(Session session, ObjectNode configuration) {
        double now = MediaTailorStore.epoch();
        for (ObjectNode schedule : store.listForAccount(session.account, session.region, MediaTailorStore.PREFETCH)) {
            if (!session.config.equals(schedule.path("PlaybackConfigurationName").asText())
                    || MediaTailorService.expired(schedule)) {
                continue;
            }
            String stream = schedule.path("StreamId").asText(null);
            if (stream != null && !stream.equals(session.sessionParams.get("streamId"))) {
                continue;
            }
            boolean recurring = "RECURRING".equals(schedule.path("ScheduleType").asText());
            JsonNode consumption = recurring ? schedule.path("RecurringPrefetchConfiguration")
                    .path("RecurringConsumption") : schedule.path("Consumption");
            JsonNode variables = recurring ? schedule.path("RecurringPrefetchConfiguration")
                    .path("RecurringRetrieval").path("DynamicVariables")
                    : schedule.path("Retrieval").path("DynamicVariables");
            if (!recurring && (now < consumption.path("StartTime").asDouble(0)
                    || now > consumption.path("EndTime").asDouble(Double.MAX_VALUE))) {
                continue;
            }
            boolean matches = true;
            for (JsonNode criterion : consumption.path("AvailMatchingCriteria")) {
                String variable = criterion.path("DynamicVariable").asText();
                String expected = variables.path(variable).asText(null);
                if (expected == null || !expected.equals(sessionValue(session, variable))) {
                    matches = false;
                }
            }
            if (!matches) {
                continue;
            }
            String key = prefetchKey(session.account, session.region, session.config, schedule.path("Name").asText());
            Prefetched found = recurring ? prefetched.remove(key) : prefetched.get(key);
            if (found != null && found.expiresAt() >= now && !found.ads().isEmpty()) {
                adsLog(session, configuration, "FILLED_AVAIL", Map.of("prefetchScheduleName",
                        schedule.path("Name").asText()));
                return found.ads();
            }
        }
        return null;
    }

    /** After an avail ends, retrieves ads for the next one by the configuration's active recurring schedules. */
    private void recurring(Session session) {
        double now = MediaTailorStore.epoch();
        for (ObjectNode schedule : store.listForAccount(session.account, session.region, MediaTailorStore.PREFETCH)) {
            if (!session.config.equals(schedule.path("PlaybackConfigurationName").asText())
                    || !"RECURRING".equals(schedule.path("ScheduleType").asText())) {
                continue;
            }
            JsonNode recurring = schedule.path("RecurringPrefetchConfiguration");
            if (now < recurring.path("StartTime").asDouble(0) || now > recurring.path("EndTime").asDouble(0)) {
                continue;
            }
            String stream = schedule.path("StreamId").asText(null);
            if (stream != null && !stream.equals(session.sessionParams.get("streamId"))) {
                continue;
            }
            JsonNode retrieval = recurring.path("RecurringRetrieval");
            long delay = Math.max(1, retrieval.path("DelayAfterAvailEndSeconds").asLong(0) * 1000);
            int lifetime = recurring.path("RecurringConsumption").path("RetrievedAdExpirationSeconds").asInt(3600);
            String name = schedule.path("Name").asText();
            vertx.setTimer(delay, id -> vertx.executeBlocking(() -> {
                retrieve(session.account, session.region, session.config, name, retrieval.path("DynamicVariables"),
                        MediaTailorStore.epoch() + lifetime);
                return null;
            }, false));
        }
    }

    // ================================================================ logs

    private void adsLog(Session session, ObjectNode configuration, String type, Map<String, ?> details) {
        log(session, configuration, ADS_LOG_GROUP, "AdsInteractionLog", type, details);
    }

    private void manifestLog(Session session, ObjectNode configuration, String type, Map<String, ?> details) {
        log(session, configuration, MANIFEST_LOG_GROUP, "ManifestServiceInteractionLog", type, details);
    }

    private void log(Session session, ObjectNode configuration, String group, String member, String type,
                     Map<String, ?> details) {
        if (!session.logged || type == null) {
            return;
        }
        for (JsonNode excluded : configuration.path("LogConfiguration").path(member).path("ExcludeEventTypes")) {
            if (type.equals(excluded.asText())) {
                return;
            }
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("awsAccountId", session.account);
        event.put("customerId", session.account);
        event.put("eventDescription", type.toLowerCase(Locale.ROOT).replace('_', ' '));
        event.put("eventTimestamp", Instant.now().toString());
        event.put("eventType", type);
        event.put("originId", session.config);
        event.put("sessionId", session.id);
        event.put("sessionType", session.dash ? "DASH" : "HLS");
        details.forEach((key, value) -> event.set(key, mapper.valueToTree(value)));
        String stream = session.config;
        try {
            RequestScopes.runAs(session.account, session.region, () -> {
                try {
                    logs.get().createLogGroup(group, null, Map.of(), session.region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                try {
                    logs.get().createLogStream(group, stream, session.region);
                } catch (AwsException e) {
                    if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
                logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", System.currentTimeMillis(),
                        "message", event.toString())), session.region);
            });
        } catch (RuntimeException e) {
            LOG.debugv("MediaTailor could not log {0} to {1}: {2}", type, group, e.getMessage());
        }
    }
}
