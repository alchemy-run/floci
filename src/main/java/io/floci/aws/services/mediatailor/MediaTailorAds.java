package io.floci.aws.services.mediatailor;

import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Ad decisioning as MediaTailor does it: VAST (2, 3 and 4) and VMAP responses from an ad decision server, with VAST
 * wrappers followed, and each linear creative's media prepared for a content track. With ad conditioning
 * {@code TRANSCODE} (the default), the best progressive media file is transcoded with ffmpeg to the track's size,
 * bitrate and segment duration, as TS or fragmented MP4 like the content; with {@code NONE}, an HLS media file is used
 * as it is. Renditions are cached by media file and track profile.
 */
@ApplicationScoped
public class MediaTailorAds {

    private static final Logger LOG = Logger.getLogger(MediaTailorAds.class);
    private static final int MAX_WRAPPERS = 5;
    private static final int MAX_RENDITIONS = 256;

    /** A media file of a linear creative. */
    record MediaFile(String url, String delivery, String type, int width, int height, long bitrate) {
        boolean hls() {
            String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
            return lower.contains("mpegurl") || url.toLowerCase(Locale.ROOT).contains(".m3u8");
        }

        boolean video() {
            String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
            return lower.startsWith("video/") && !hls();
        }
    }

    /** A linear ad: its creative's duration and media, and the beacons it fires. */
    static final class Ad {
        String id = "";
        String sequence;
        String adSystem = "";
        String adTitle = "";
        String creativeId = "";
        String creativeAdId = "";
        double duration;
        final List<MediaFile> mediaFiles = new ArrayList<>();
        final List<String> impressions = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Map<String, List<String>> tracking = new LinkedHashMap<>();
        String clickThrough;
        final List<String> clickTracking = new ArrayList<>();

        Ad copy() {
            Ad copy = new Ad();
            copy.id = id;
            copy.sequence = sequence;
            copy.adSystem = adSystem;
            copy.adTitle = adTitle;
            copy.creativeId = creativeId;
            copy.creativeAdId = creativeAdId;
            copy.duration = duration;
            copy.mediaFiles.addAll(mediaFiles);
            copy.impressions.addAll(impressions);
            copy.errors.addAll(errors);
            tracking.forEach((event, urls) -> copy.tracking.put(event, new ArrayList<>(urls)));
            copy.clickThrough = clickThrough;
            copy.clickTracking.addAll(clickTracking);
            return copy;
        }
    }

    /** A VMAP ad break: where it plays ({@code start}, {@code end}, a time or a percentage) and its ads. */
    record Break(String timeOffset, List<Ad> ads) {
    }

    /** What an ad decision server answered: VAST ads, VMAP breaks, or why it failed. */
    record Decision(List<Ad> ads, List<Break> breaks, String error, String errorCode) {
        static Decision failed(String code, String message) {
            return new Decision(List.of(), List.of(), message, code);
        }
    }

    /** The profile of a content track an ad is conditioned for. */
    record Profile(int width, int height, long bandwidth, boolean audioOnly, boolean videoOnly, boolean fragmented,
                   double segmentSeconds) {
        String key() {
            return width + "x" + height + "/" + bandwidth + "/" + audioOnly + "/" + videoOnly + "/" + fragmented + "/"
                    + Math.round(segmentSeconds * 1000);
        }
    }

    /** Ad media ready to stitch: its segments' durations and their bytes, or their URLs when used as is. */
    static final class Rendition {
        final List<Double> durations = new ArrayList<>();
        final List<byte[]> segments = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        byte[] init;
        String initUrl;
        String extension = "ts";

        double duration() {
            return durations.stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    private final MediaTailorHttp http;
    private final Provider<KvsTranscoder> ffmpeg;
    private final MediaTailorStore store;
    private final ExecutorService transcodes = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mediatailor-transcode");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<Rendition>> renditions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<Rendition>> eldest) {
                    return size() > MAX_RENDITIONS;
                }
            });

    @Inject
    public MediaTailorAds(MediaTailorHttp http, Provider<KvsTranscoder> ffmpeg, MediaTailorStore store) {
        this.http = http;
        this.ffmpeg = ffmpeg;
        this.store = store;
    }

    @PreDestroy
    void stop() {
        transcodes.shutdownNow();
    }

    void clear() {
        renditions.clear();
    }

    // ================================================================ decisions

    /** Requests ads from an ad decision server URL, following VAST wrappers. */
    Decision decide(String url, Consumer<String> redirected) {
        return decide(url, 0, List.of(), redirected);
    }

    private Decision decide(String url, int depth, List<Ad> wrappers, Consumer<String> redirected) {
        MediaTailorHttp.Fetched fetched;
        try {
            fetched = http.get(url);
        } catch (MediaTailorHttp.Unreachable e) {
            return Decision.failed(e.timeout ? "ERROR_ADS_TIMEOUT" : "ERROR_ADS_IO", e.getMessage());
        }
        if (!fetched.ok()) {
            return Decision.failed("ERROR_ADS_INVALID_RESPONSE", "The ad decision server answered " + fetched.status()
                    + ".");
        }
        String text = fetched.text().strip();
        if (text.isEmpty()) {
            return depth == 0 ? Decision.failed("EMPTY_VAST_RESPONSE", "The ad decision server answered nothing.")
                    : Decision.failed("ERROR_VAST_REDIRECT_EMPTY_RESPONSE", "A VAST wrapper's ad tag answered "
                    + "nothing.");
        }
        Document document;
        try {
            document = parse(text);
        } catch (Exception e) {
            return Decision.failed("ERROR_ADS_RESPONSE_PARSE", "The ad decision server's response isn't XML: "
                    + e.getMessage());
        }
        Element root = document.getDocumentElement();
        String name = local(root);
        if ("VMAP".equals(name)) {
            return vmap(root, depth, redirected);
        }
        if (!"VAST".equals(name)) {
            return Decision.failed("ERROR_ADS_RESPONSE_UNKNOWN_ROOT_ELEMENT", "The response's root element is "
                    + name + ", not VAST or VMAP.");
        }
        return vast(root, depth, wrappers, redirected);
    }

    private Decision vast(Element root, int depth, List<Ad> wrappers, Consumer<String> redirected) {
        List<Ad> ads = new ArrayList<>();
        for (Element adElement : children(root, "Ad")) {
            Element inline = child(adElement, "InLine");
            Element wrapper = child(adElement, "Wrapper");
            if (inline != null) {
                Ad ad = new Ad();
                ad.id = adElement.getAttribute("id");
                ad.sequence = adElement.hasAttribute("sequence") ? adElement.getAttribute("sequence") : null;
                read(inline, ad);
                if (ad.mediaFiles.isEmpty() && ad.duration <= 0) {
                    continue;
                }
                for (Ad outer : wrappers) {
                    merge(outer, ad);
                }
                ads.add(ad);
            } else if (wrapper != null) {
                if (depth >= MAX_WRAPPERS) {
                    continue;
                }
                String tag = text(child(wrapper, "VASTAdTagURI"));
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                Ad outer = new Ad();
                read(wrapper, outer);
                List<Ad> chain = new ArrayList<>(wrappers);
                chain.add(outer);
                redirected.accept(tag.strip());
                Decision inner = decide(tag.strip(), depth + 1, chain, redirected);
                for (Ad ad : inner.ads()) {
                    if (adElement.hasAttribute("sequence") && ad.sequence == null) {
                        ad.sequence = adElement.getAttribute("sequence");
                    }
                    ads.add(ad);
                }
            }
        }
        ads = pod(ads);
        if (ads.isEmpty()) {
            return new Decision(List.of(), List.of(), "The VAST response has no linear ads.", "EMPTY_VAST_RESPONSE");
        }
        return new Decision(ads, List.of(), null, null);
    }

    /** The ad pod (ads with a sequence, in order) when there is one, else the stand-alone ads. */
    private static List<Ad> pod(List<Ad> ads) {
        List<Ad> sequenced = new ArrayList<>();
        for (Ad ad : ads) {
            if (ad.sequence != null) {
                sequenced.add(ad);
            }
        }
        if (sequenced.isEmpty()) {
            return ads;
        }
        sequenced.sort(Comparator.comparingInt(ad -> {
            try {
                return Integer.parseInt(ad.sequence.trim());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }));
        return sequenced;
    }

    private Decision vmap(Element root, int depth, Consumer<String> redirected) {
        List<Break> breaks = new ArrayList<>();
        for (Element adBreak : children(root, "AdBreak")) {
            if (!"linear".equalsIgnoreCase(adBreak.getAttribute("breakType").isEmpty() ? "linear"
                    : adBreak.getAttribute("breakType"))) {
                continue;
            }
            Element source = child(adBreak, "AdSource");
            if (source == null) {
                continue;
            }
            List<Ad> ads = List.of();
            Element data = child(source, "VASTAdData");
            Element tag = child(source, "AdTagURI");
            if (data != null) {
                Element vast = child(data, "VAST");
                if (vast != null) {
                    ads = vast(vast, depth, List.of(), redirected).ads();
                }
            } else if (tag != null && text(tag) != null) {
                redirected.accept(text(tag).strip());
                ads = decide(text(tag).strip(), depth + 1, List.of(), redirected).ads();
            }
            if (!ads.isEmpty()) {
                breaks.add(new Break(adBreak.getAttribute("timeOffset"), ads));
            }
        }
        if (breaks.isEmpty()) {
            return new Decision(List.of(), List.of(), "The VMAP response has no linear ad breaks.",
                    "EMPTY_VMAP_RESPONSE");
        }
        return new Decision(List.of(), breaks, null, null);
    }

    /** Reads an InLine's or Wrapper's impressions, errors and its linear creative. */
    private static void read(Element container, Ad ad) {
        String system = text(child(container, "AdSystem"));
        if (system != null) {
            ad.adSystem = system.strip();
        }
        String title = text(child(container, "AdTitle"));
        if (title != null) {
            ad.adTitle = title.strip();
        }
        for (Element impression : children(container, "Impression")) {
            add(ad.impressions, text(impression));
        }
        for (Element error : children(container, "Error")) {
            add(ad.errors, text(error));
        }
        Element creatives = child(container, "Creatives");
        if (creatives == null) {
            return;
        }
        for (Element creative : children(creatives, "Creative")) {
            Element linear = child(creative, "Linear");
            if (linear == null) {
                continue;
            }
            if (ad.creativeId.isEmpty()) {
                ad.creativeId = creative.getAttribute("id");
                ad.creativeAdId = creative.getAttribute("adId");
            }
            String duration = text(child(linear, "Duration"));
            if (duration != null && ad.duration <= 0) {
                ad.duration = clock(duration.strip());
            }
            Element files = child(linear, "MediaFiles");
            if (files != null) {
                for (Element file : children(files, "MediaFile")) {
                    String url = text(file);
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    ad.mediaFiles.add(new MediaFile(url.strip(), file.getAttribute("delivery"),
                            file.getAttribute("type"), integer(file.getAttribute("width")),
                            integer(file.getAttribute("height")), integer(file.getAttribute("bitrate")) * 1000L));
                }
            }
            Element events = child(linear, "TrackingEvents");
            if (events != null) {
                for (Element tracking : children(events, "Tracking")) {
                    String event = tracking.getAttribute("event");
                    String url = text(tracking);
                    if (!event.isEmpty() && url != null && !url.isBlank()) {
                        ad.tracking.computeIfAbsent(event, key -> new ArrayList<>()).add(url.strip());
                    }
                }
            }
            Element clicks = child(linear, "VideoClicks");
            if (clicks != null) {
                String through = text(child(clicks, "ClickThrough"));
                if (through != null && !through.isBlank()) {
                    ad.clickThrough = through.strip();
                }
                for (Element click : children(clicks, "ClickTracking")) {
                    add(ad.clickTracking, text(click));
                }
            }
            break;
        }
    }

    private static void merge(Ad wrapper, Ad ad) {
        ad.impressions.addAll(0, wrapper.impressions);
        ad.errors.addAll(0, wrapper.errors);
        wrapper.tracking.forEach((event, urls) -> ad.tracking.computeIfAbsent(event, key -> new ArrayList<>())
                .addAll(0, urls));
        ad.clickTracking.addAll(0, wrapper.clickTracking);
    }

    private static void add(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value.strip());
        }
    }

    private static int integer(String value) {
        try {
            return value == null || value.isBlank() ? 0 : (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A VAST clock value, {@code HH:MM:SS} or {@code HH:MM:SS.mmm}, in seconds. */
    static double clock(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String local(Node node) {
        String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(local(element))) {
                found.add(element);
            }
        }
        return found;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String text(Element element) {
        return element == null ? null : element.getTextContent();
    }

    // ================================================================ renditions

    /** The media file that suits a profile best: an HLS one with conditioning NONE, else a progressive video. */
    static MediaFile choose(Ad ad, Profile profile, boolean conditioned) {
        MediaFile best = null;
        for (MediaFile file : ad.mediaFiles) {
            if (conditioned ? !(file.video() || file.hls()) : !file.hls()) {
                continue;
            }
            if (best == null) {
                best = file;
            } else if (conditioned && best.hls() != file.hls()) {
                // a progressive file transcodes from one download; an HLS one is joined first
                if (best.hls()) {
                    best = file;
                }
            } else if (distance(file, profile) < distance(best, profile)) {
                best = file;
            }
        }
        return best;
    }

    private static long distance(MediaFile file, Profile profile) {
        long size = profile.height() > 0 && file.height() > 0 ? Math.abs(file.height() - profile.height()) * 10000L
                : 0;
        long rate = profile.bandwidth() > 0 && file.bitrate() > 0 ? Math.abs(file.bitrate() - profile.bandwidth())
                : 0;
        return size + rate / 1000;
    }

    /** Starts preparing (or finds) the rendition of a media file for a profile. */
    CompletableFuture<Rendition> rendition(MediaFile file, Profile profile, boolean conditioned) {
        String key = file.url() + "|" + profile.key() + "|" + conditioned;
        synchronized (renditions) {
            CompletableFuture<Rendition> future = renditions.get(key);
            if (future == null || future.isCompletedExceptionally()) {
                future = CompletableFuture.supplyAsync(() -> prepare(file, profile, conditioned), transcodes);
                renditions.put(key, future);
            }
            return future;
        }
    }

    /** How long a manifest request waits for its ads to be prepared. */
    long waitMillis() {
        return store.config().transcodeWaitMillis();
    }

    /**
     * A rendition once it's prepared, waiting until a deadline; empty when it isn't ready by then (it keeps
     * preparing, for later sessions) or couldn't be prepared.
     */
    static Optional<Rendition> await(MediaFile file, CompletableFuture<Rendition> future, long deadline,
                                               Consumer<String> failed) {
        try {
            long left = Math.max(0, deadline - System.currentTimeMillis());
            return Optional.of(future.get(left, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            failed.accept("The ad " + file.url() + " is still being prepared.");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            failed.accept("The ad " + file.url() + " couldn't be prepared: " + cause.getMessage());
            return Optional.empty();
        }
    }

    private Rendition prepare(MediaFile file, Profile profile, boolean conditioned) {
        if (file.hls() && !conditioned) {
            return hls(file, profile);
        }
        byte[] input;
        String inputName;
        if (file.hls()) {
            Rendition source = hls(file, profile);
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            if (source.initUrl != null) {
                joined.writeBytes(fetch(source.initUrl));
            }
            source.urls.forEach(url -> joined.writeBytes(fetch(url)));
            input = joined.toByteArray();
            inputName = source.initUrl != null ? "in.mp4" : "in.ts";
        } else {
            input = fetch(file.url());
            inputName = "in.mp4";
        }
        return transcode(input, inputName, profile);
    }

    private byte[] fetch(String url) {
        MediaTailorHttp.Fetched fetched = http.get(url);
        if (!fetched.ok()) {
            throw new IllegalStateException(url + " answered " + fetched.status() + ".");
        }
        return fetched.body();
    }

    /** An HLS media file's segments for a profile, used as they are. */
    private Rendition hls(MediaFile file, Profile profile) {
        MediaTailorHttp.Fetched fetched = http.get(file.url());
        if (!fetched.ok()) {
            throw new IllegalStateException(file.url() + " answered " + fetched.status() + ".");
        }
        String url = file.url();
        String text = fetched.text();
        if (MediaTailorHls.isMultivariant(text)) {
            MediaTailorHls.Multivariant variants = MediaTailorHls.multivariant(text, url);
            MediaTailorHls.Track best = null;
            for (MediaTailorHls.Track track : variants.tracks) {
                if (track.uri == null || track.audioOnly() != profile.audioOnly()) {
                    continue;
                }
                if (best == null || Math.abs(track.bandwidth() - profile.bandwidth())
                        < Math.abs(best.bandwidth() - profile.bandwidth())) {
                    best = track;
                }
            }
            if (best == null) {
                throw new IllegalStateException("The HLS ad " + url + " has no matching track.");
            }
            url = best.uri;
            MediaTailorHttp.Fetched media = http.get(url);
            if (!media.ok()) {
                throw new IllegalStateException(url + " answered " + media.status() + ".");
            }
            text = media.text();
        }
        MediaTailorHls.Media media = MediaTailorHls.media(text, url);
        Rendition rendition = new Rendition();
        for (MediaTailorHls.Segment segment : media.segments) {
            rendition.durations.add(segment.duration);
            rendition.urls.add(segment.uri);
            if (segment.map != null) {
                rendition.initUrl = segment.map;
                rendition.extension = "mp4";
            }
        }
        if (rendition.durations.isEmpty()) {
            throw new IllegalStateException("The HLS ad " + file.url() + " has no segments.");
        }
        return rendition;
    }

    /** Transcodes an ad to a profile: its size, bitrate, segment duration and container. */
    private Rendition transcode(byte[] input, String inputName, Profile profile) {
        double target = profile.segmentSeconds() > 0 ? profile.segmentSeconds() : 6;
        String time = MediaTailorHls.seconds(target);
        StringBuilder script = new StringBuilder();
        if (!profile.videoOnly()) {
            script.append("if ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 ")
                    .append(inputName).append(" | grep -q .; then EXTRA=''; AMAP='-map 0:a:0'; else ")
                    .append("EXTRA='-f lavfi -i anullsrc=r=48000:cl=stereo'; AMAP='-map 1:a:0 -shortest'; fi; ");
        }
        script.append("ffmpeg -hide_banner -loglevel error -nostdin -y -i ").append(inputName);
        if (!profile.videoOnly()) {
            script.append(" $EXTRA");
        }
        if (!profile.audioOnly()) {
            script.append(" -map 0:v:0");
            String scale = profile.width() > 0 && profile.height() > 0
                    ? "scale=" + profile.width() + ":" + profile.height() + ":force_original_aspect_ratio=decrease,"
                    + "pad=" + profile.width() + ":" + profile.height() + ":(ow-iw)/2:(oh-ih)/2,setsar=1"
                    : "scale=trunc(iw/2)*2:trunc(ih/2)*2";
            long video = profile.bandwidth() > 0 ? Math.max(150_000, profile.bandwidth() * 85 / 100
                    - (profile.videoOnly() ? 0 : 128_000)) : 1_500_000;
            script.append(" -vf '").append(scale).append("' -c:v libx264 -preset veryfast -pix_fmt yuv420p")
                    .append(" -b:v ").append(video).append(" -maxrate ").append(video).append(" -bufsize ")
                    .append(video * 2).append(" -force_key_frames 'expr:gte(t,n_forced*").append(time).append(")'")
                    .append(" -sc_threshold 0");
        }
        if (!profile.videoOnly()) {
            script.append(" $AMAP -c:a aac -b:a 128k -ar 48000 -ac 2");
        }
        script.append(" -f hls -hls_time ").append(time).append(" -hls_list_size 0 -hls_playlist_type vod");
        if (profile.fragmented()) {
            script.append(" -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_filename 's%d.m4s'");
        } else {
            script.append(" -hls_segment_filename 's%d.ts'");
        }
        script.append(" out.m3u8");
        Map<String, byte[]> files = ffmpeg.get().run(new LinkedHashMap<>(Map.of(inputName, input)),
                script.toString());
        byte[] playlist = files.get("out.m3u8");
        if (playlist == null) {
            throw new IllegalStateException("ffmpeg made no playlist.");
        }
        MediaTailorHls.Media media = MediaTailorHls.media(new String(playlist, StandardCharsets.UTF_8),
                "http://ad.invalid/out.m3u8");
        Rendition rendition = new Rendition();
        rendition.extension = profile.fragmented() ? "m4s" : "ts";
        rendition.init = files.get("init.mp4");
        for (MediaTailorHls.Segment segment : media.segments) {
            String name = segment.uri.substring(segment.uri.lastIndexOf('/') + 1);
            byte[] bytes = files.get(name);
            if (bytes == null) {
                throw new IllegalStateException("ffmpeg's playlist names a missing segment " + name + ".");
            }
            rendition.durations.add(segment.duration);
            rendition.segments.add(bytes);
        }
        if (rendition.durations.isEmpty()) {
            throw new IllegalStateException("ffmpeg made no segments.");
        }
        LOG.debugv("MediaTailor prepared an ad rendition {0} of {1} segments", profile.key(),
                rendition.durations.size());
        return rendition;
    }
}
