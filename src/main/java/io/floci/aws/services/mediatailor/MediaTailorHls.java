package io.floci.aws.services.mediatailor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS playlists as MediaTailor reads them: a multivariant playlist's variant streams and renditions, and a media
 * playlist's segments with the tags before each, including the ad markers ({@code EXT-X-CUE-OUT},
 * {@code EXT-X-CUE-OUT-CONT}, {@code EXT-X-CUE-IN}, and {@code EXT-X-DATERANGE} with {@code SCTE35-OUT} or
 * {@code SCTE35-IN}) that mark ad avails.
 */
final class MediaTailorHls {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");

    private MediaTailorHls() {
    }

    /** A track of a multivariant playlist: a variant stream, or a rendition with its own playlist. */
    static final class Track {
        final String tag;
        final String uri;
        final Map<String, String> attributes;

        Track(String tag, String uri, Map<String, String> attributes) {
            this.tag = tag;
            this.uri = uri;
            this.attributes = attributes;
        }

        boolean audioOnly() {
            if ("#EXT-X-MEDIA".equals(tag)) {
                return "AUDIO".equals(attributes.get("TYPE"));
            }
            String codecs = attributes.getOrDefault("CODECS", "");
            return !attributes.containsKey("RESOLUTION") && !codecs.isEmpty() && !codecs.contains("avc")
                    && !codecs.contains("hvc") && !codecs.contains("hev") && !codecs.contains("av01");
        }

        int width() {
            String resolution = attributes.getOrDefault("RESOLUTION", "");
            return resolution.contains("x") ? parse(resolution.substring(0, resolution.indexOf('x'))) : 0;
        }

        int height() {
            String resolution = attributes.getOrDefault("RESOLUTION", "");
            return resolution.contains("x") ? parse(resolution.substring(resolution.indexOf('x') + 1)) : 0;
        }

        long bandwidth() {
            try {
                return Long.parseLong(attributes.getOrDefault("BANDWIDTH", "0"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static int parse(String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** A multivariant playlist: its lines, with the tracks it lists. */
    static final class Multivariant {
        final List<String> lines = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
    }

    /** A media segment: its duration and URI, the tags before it and its media sequence number. */
    static final class Segment {
        final double duration;
        final String title;
        final String uri;
        final List<String> tags;
        final long sequence;
        final String map;

        Segment(double duration, String title, String uri, List<String> tags, long sequence, String map) {
            this.duration = duration;
            this.title = title;
            this.uri = uri;
            this.tags = tags;
            this.sequence = sequence;
            this.map = map;
        }
    }

    /** A media playlist. */
    static final class Media {
        int version = 3;
        double targetDuration;
        long mediaSequence;
        long discontinuitySequence;
        boolean endList;
        String playlistType;
        final List<String> header = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();

        double duration() {
            return segments.stream().mapToDouble(segment -> segment.duration).sum();
        }

        boolean fragmented() {
            return segments.stream().anyMatch(segment -> segment.map != null);
        }
    }

    static boolean isMultivariant(String text) {
        return text.contains("#EXT-X-STREAM-INF") || !text.contains("#EXTINF") && text.contains("#EXT-X-MEDIA:");
    }

    static Map<String, String> attributes(String list) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(list);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            found.put(matcher.group(1), value);
        }
        return found;
    }

    private static List<String> lines(String text) {
        List<String> found = new ArrayList<>();
        for (String line : text.replace("\r", "").split("\n")) {
            if (!line.isBlank()) {
                found.add(line.strip());
            }
        }
        return found;
    }

    /** Resolves a playlist URI against the playlist's URL. */
    static String resolve(String base, String uri) {
        try {
            return URI.create(base).resolve(uri.replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }

    static Multivariant multivariant(String text, String url) {
        Multivariant playlist = new Multivariant();
        List<String> lines = lines(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String uri = null;
                int j = i + 1;
                while (j < lines.size() && lines.get(j).startsWith("#")) {
                    j++;
                }
                if (j < lines.size()) {
                    uri = lines.get(j);
                }
                playlist.tracks.add(new Track("#EXT-X-STREAM-INF", uri == null ? null : resolve(url, uri),
                        attributes(line.substring(line.indexOf(':') + 1))));
                playlist.lines.add(line);
                playlist.lines.add("\u0000" + (playlist.tracks.size() - 1));
                i = j;
            } else if (line.startsWith("#EXT-X-MEDIA:") && URI_ATTRIBUTE.matcher(line).find()) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                matcher.find();
                playlist.tracks.add(new Track("#EXT-X-MEDIA", resolve(url, matcher.group(1)),
                        attributes(line.substring(line.indexOf(':') + 1))));
                playlist.lines.add("\u0001" + (playlist.tracks.size() - 1) + "\u0001" + line);
            } else if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                // I-frame playlists aren't personalized; players fall back to the variant streams.
                continue;
            } else {
                playlist.lines.add(line);
            }
        }
        return playlist;
    }

    /** Writes a multivariant playlist with each track's URI replaced. */
    static String writeMultivariant(Multivariant playlist, IntFunction<String> uri) {
        StringBuilder out = new StringBuilder();
        for (String line : playlist.lines) {
            if (line.startsWith("\u0000")) {
                out.append(uri.apply(Integer.parseInt(line.substring(1)))).append('\n');
            } else if (line.startsWith("\u0001")) {
                int second = line.indexOf('\u0001', 1);
                int index = Integer.parseInt(line.substring(1, second));
                String tag = line.substring(second + 1);
                out.append(URI_ATTRIBUTE.matcher(tag).replaceFirst(Matcher.quoteReplacement("URI=\""
                        + uri.apply(index) + "\""))).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    static Media media(String text, String url) {
        Media playlist = new Media();
        List<String> pending = new ArrayList<>();
        String map = null;
        double duration = -1;
        String title = "";
        long sequence = -1;
        boolean started = false;
        for (String line : lines(text)) {
            if (line.equals("#EXTM3U")) {
                continue;
            }
            if (line.startsWith("#EXT-X-VERSION:")) {
                playlist.version = (int) number(line);
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                playlist.targetDuration = number(line);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                playlist.mediaSequence = (long) number(line);
            } else if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                playlist.discontinuitySequence = (long) number(line);
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                playlist.playlistType = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.equals("#EXT-X-ENDLIST")) {
                playlist.endList = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                map = matcher.find() ? resolve(url, matcher.group(1)) : null;
                started = true;
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring("#EXTINF:".length());
                int comma = value.indexOf(',');
                try {
                    duration = Double.parseDouble((comma < 0 ? value : value.substring(0, comma)).trim());
                } catch (NumberFormatException e) {
                    duration = 0;
                }
                title = comma < 0 ? "" : value.substring(comma + 1);
                started = true;
            } else if (line.startsWith("#")) {
                if (!started && (line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || line.startsWith("#EXT-X-START")
                        || line.startsWith("#EXT-X-SERVER-CONTROL") || line.startsWith("#EXT-X-PART-INF")
                        || line.startsWith("#EXT-X-ALLOW-CACHE"))) {
                    playlist.header.add(line);
                } else if (!line.startsWith("#EXT-X-PART:") && !line.startsWith("#EXT-X-PRELOAD-HINT")
                        && !line.startsWith("#EXT-X-RENDITION-REPORT") && !line.startsWith("#EXT-X-SKIP")) {
                    pending.add(line);
                    started = true;
                }
            } else if (duration >= 0) {
                sequence = sequence < 0 ? playlist.mediaSequence : sequence + 1;
                playlist.segments.add(new Segment(duration, title, resolve(url, line), pending, sequence, map));
                pending = new ArrayList<>();
                duration = -1;
                title = "";
            }
        }
        return playlist;
    }

    private static double number(String line) {
        try {
            return Double.parseDouble(line.substring(line.indexOf(':') + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================ ad markers

    /** An ad marker on a segment: an avail starting (with its duration), continuing, or ending. */
    record Marker(String kind, double duration, double elapsed, String id) {
        static final String OUT = "out";
        static final String CONT = "cont";
        static final String IN = "in";
    }

    static boolean adMarker(String tag) {
        return tag.startsWith("#EXT-X-CUE-OUT") || tag.startsWith("#EXT-X-CUE-IN") || tag.startsWith("#EXT-OATCLS")
                || tag.startsWith("#EXT-X-SPLICEPOINT") || tag.startsWith("#EXT-X-SCTE35")
                || tag.startsWith("#EXT-X-DATERANGE") && tag.contains("SCTE35-");
    }

    /** The ad marker among a segment's tags, if it has one. */
    static Marker marker(List<String> tags) {
        Marker found = null;
        for (String tag : tags) {
            String upper = tag.toUpperCase(Locale.ROOT);
            if (upper.startsWith("#EXT-X-CUE-OUT-CONT")) {
                String value = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : "";
                double elapsed = 0;
                double total = 0;
                if (value.contains("/")) {
                    elapsed = decimal(value.substring(0, value.indexOf('/')));
                    total = decimal(value.substring(value.indexOf('/') + 1));
                } else {
                    Map<String, String> attributes = attributes(value.toUpperCase(Locale.ROOT));
                    elapsed = decimal(attributes.getOrDefault("ELAPSEDTIME", "0"));
                    total = decimal(attributes.getOrDefault("DURATION", "0"));
                }
                if (found == null) {
                    found = new Marker(Marker.CONT, total, elapsed, null);
                }
            } else if (upper.startsWith("#EXT-X-CUE-OUT")) {
                String value = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : "";
                double total = value.toUpperCase(Locale.ROOT).contains("DURATION=")
                        ? decimal(attributes(value.toUpperCase(Locale.ROOT)).getOrDefault("DURATION", "0"))
                        : decimal(value);
                found = new Marker(Marker.OUT, total, 0, null);
            } else if (upper.startsWith("#EXT-X-CUE-IN")) {
                found = new Marker(Marker.IN, 0, 0, null);
            } else if (upper.startsWith("#EXT-X-DATERANGE:")) {
                Map<String, String> attributes = attributes(tag.substring(tag.indexOf(':') + 1));
                if (attributes.containsKey("SCTE35-OUT")) {
                    double total = decimal(attributes.getOrDefault("DURATION",
                            attributes.getOrDefault("PLANNED-DURATION", "0")));
                    found = new Marker(Marker.OUT, total, 0, attributes.get("ID"));
                } else if (attributes.containsKey("SCTE35-IN")) {
                    found = new Marker(Marker.IN, 0, 0, attributes.get("ID"));
                }
            }
        }
        return found;
    }

    private static double decimal(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
