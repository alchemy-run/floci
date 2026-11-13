package io.floci.aws.services.mediaconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a job's settings with ffmpeg, in the media container Kinesis Video Streams transcodes in. Inputs are
 * stitched in order (scaled to the first, when there are several) and clipped by their input clippings; each output
 * group becomes its files: a file group one file per output (frame captures a JPEG sequence), an HLS group a playlist
 * and segments per output and a main playlist, a DASH ISO group an MPD with each output as a representation, a CMAF
 * group fragmented MP4 renditions with HLS and DASH manifests, and a Microsoft Smooth group an ISM. Video is scaled
 * by its width, height and scaling behavior, cropped, and set to its frame rate; codecs map to ffmpeg's encoders
 * (libx264, libx265, libsvtav1, libvpx, libvpx-vp9, mpeg2video, prores_ks, dnxhd, gif, and aac, libmp3lame, mp2,
 * ac3, eac3, libopus, libvorbis, flac, PCM), with their bitrates, rate control (QVBR as a constant rate factor),
 * profiles, GOP and B-frames. Captions, watermarks, image inserters and encryption are not applied.
 */
@ApplicationScoped
public class MediaConvertTranscoder {

    static final Set<String> VIDEO_CODECS = Set.of("AV1", "AVC_INTRA", "FRAME_CAPTURE", "GIF", "H_264", "H_265",
            "MPEG2", "PASSTHROUGH", "PRORES", "UNCOMPRESSED", "VC3", "VP8", "VP9", "XAVC");
    static final Set<String> AUDIO_CODECS = Set.of("AAC", "MP2", "MP3", "WAV", "AIFF", "AC3", "EAC3", "EAC3_ATMOS",
            "VORBIS", "OPUS", "PASSTHROUGH", "FLAC", "AC4");

    private final Provider<KvsTranscoder> ffmpeg;
    private final ObjectMapper mapper;

    /** A produced output: the files it is (relative to the work directory), its playlist, and its details. */
    record Produced(int group, String type, List<String> files, String playlist, long durationMs, int width,
                    int height) {
    }

    /** What a run made: every file by its relative path, and each output. */
    record Result(Map<String, byte[]> files, List<Produced> outputs) {
    }

    /** A failed run: the MediaConvert error code and message. */
    static final class TranscodeFailure extends RuntimeException {
        final int code;

        TranscodeFailure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    @Inject
    public MediaConvertTranscoder(Provider<KvsTranscoder> ffmpeg, ObjectMapper mapper) {
        this.ffmpeg = ffmpeg;
        this.mapper = mapper;
    }

    /** Checks an output's (or a preset's) codecs and container. */
    static void checkOutput(JsonNode output) {
        JsonNode video = output.path("VideoDescription");
        if (video.isObject()) {
            String codec = video.path("CodecSettings").path("Codec").asText(null);
            if (codec == null || !VIDEO_CODECS.contains(codec)) {
                throw MediaConvertStore.badRequest("/outputs: VideoDescription needs a CodecSettings.Codec, one of "
                        + VIDEO_CODECS + ".");
            }
        }
        for (JsonNode audio : output.path("AudioDescriptions")) {
            String codec = audio.path("CodecSettings").path("Codec").asText(null);
            if (codec == null || !AUDIO_CODECS.contains(codec)) {
                throw MediaConvertStore.badRequest("/outputs: each AudioDescription needs a CodecSettings.Codec, one "
                        + "of " + AUDIO_CODECS + ".");
            }
        }
    }

    // ================================================================ probing

    /** ffprobe's JSON for each input, by its name in the work directory. */
    Map<String, JsonNode> probe(Map<String, byte[]> inputs) {
        StringBuilder script = new StringBuilder("set -e");
        for (String name : inputs.keySet()) {
            script.append(" && ffprobe -v error -print_format json -show_format -show_streams '").append(name)
                    .append("' > '").append(name).append(".probe'");
        }
        Map<String, byte[]> files;
        try {
            files = ffmpeg.get().run(new LinkedHashMap<>(inputs), script.toString());
        } catch (KvsTranscoder.TranscodeException e) {
            throw new TranscodeFailure(1030, "The input couldn't be read: " + e.getMessage());
        }
        Map<String, JsonNode> probes = new LinkedHashMap<>();
        for (String name : inputs.keySet()) {
            probes.put(name, parse(files.get(name + ".probe")));
        }
        return probes;
    }

    private JsonNode parse(byte[] json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    static JsonNode stream(JsonNode probe, String type) {
        for (JsonNode stream : probe.path("streams")) {
            if (type.equals(stream.path("codec_type").asText())) {
                return stream;
            }
        }
        return null;
    }

    static double fps(JsonNode probe) {
        JsonNode video = stream(probe, "video");
        if (video == null) {
            return 25;
        }
        String[] rate = video.path("avg_frame_rate").asText("25/1").split("/");
        try {
            double value = Double.parseDouble(rate[0]) / (rate.length > 1 ? Double.parseDouble(rate[1]) : 1);
            return value > 0 && !Double.isNaN(value) ? value : 25;
        } catch (NumberFormatException e) {
            return 25;
        }
    }

    static double duration(JsonNode probe) {
        return probe.path("format").path("duration").asDouble(0);
    }

    /** A timecode, HH:MM:SS:FF, in seconds at a frame rate. */
    static double seconds(String timecode, double fps) {
        String[] parts = timecode.split("[:;]");
        if (parts.length != 4) {
            throw new TranscodeFailure(1040, "The timecode " + timecode + " isn't HH:MM:SS:FF.");
        }
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2])
                + Integer.parseInt(parts[3]) / fps;
    }

    // ================================================================ running

    /**
     * Runs the outputs over the inputs. {@code inputs} are the input files by work-directory name, in order, with
     * {@code settingsInputs} their settings; {@code bases} name each group's outputs.
     */
    Result run(Map<String, byte[]> inputs, List<JsonNode> settingsInputs, Map<String, JsonNode> probes,
               JsonNode outputGroups, List<String> bases) {
        Plan plan = new Plan(new ArrayList<>(inputs.keySet()), settingsInputs, probes);
        StringBuilder script = new StringBuilder("set -e");
        List<Produced> produced = new ArrayList<>();
        int groupIndex = 0;
        for (JsonNode group : outputGroups) {
            String dir = "g" + groupIndex;
            String base = bases.get(groupIndex);
            script.append(" && mkdir -p ").append(dir);
            String type = group.path("OutputGroupSettings").path("Type").asText("FILE_GROUP_SETTINGS");
            JsonNode settings = group.path("OutputGroupSettings");
            switch (type) {
                case "FILE_GROUP_SETTINGS" -> {
                    for (JsonNode output : group.path("Outputs")) {
                        String name = base + modifier(output);
                        if ("FRAME_CAPTURE".equals(output.path("VideoDescription").path("CodecSettings")
                                .path("Codec").asText())) {
                            script.append(" && ").append(plan.frameCapture(output, dir + "/" + name));
                            produced.add(new Produced(groupIndex, type, List.of(), null, plan.duration(),
                                    output.path("VideoDescription").path("Width").asInt(0),
                                    output.path("VideoDescription").path("Height").asInt(0)));
                            continue;
                        }
                        String file = dir + "/" + name + "." + extension(output);
                        script.append(" && ").append(plan.encode(List.of(output), muxer(output), file, ""));
                        produced.add(new Produced(groupIndex, type, List.of(file), null, 0, 0, 0));
                    }
                }
                case "HLS_GROUP_SETTINGS", "CMAF_GROUP_SETTINGS" -> {
                    boolean cmaf = "CMAF_GROUP_SETTINGS".equals(type);
                    JsonNode hls = settings.path(cmaf ? "CmafGroupSettings" : "HlsGroupSettings");
                    int segment = hls.path("SegmentLength").asInt(cmaf ? 2 : 10);
                    for (JsonNode output : group.path("Outputs")) {
                        String name = base + modifier(output);
                        String playlist = dir + "/" + name + ".m3u8";
                        String options = "-f hls -hls_time " + segment + " -hls_playlist_type vod "
                                + (cmaf ? "-hls_segment_type fmp4 -hls_fmp4_init_filename '" + name + "_init.mp4' "
                                + "-hls_segment_filename '" + dir + "/" + name + "_%05d.mp4'"
                                : "-hls_segment_filename '" + dir + "/" + name + "_%05d.ts'");
                        script.append(" && ").append(plan.encode(List.of(output), options, playlist, ""));
                        produced.add(new Produced(groupIndex, type, List.of(playlist), playlist, 0, 0, 0));
                    }
                    if (cmaf && !"DISABLED".equals(hls.path("WriteDashManifest").asText("ENABLED"))) {
                        String mpd = dir + "/" + base + ".mpd";
                        script.append(" && ").append(plan.encode(list(group.path("Outputs")), "-f dash -seg_duration "
                                + segment + " -use_template 1 -use_timeline 1 -init_seg_name 'dash_" + base
                                + "_init_$RepresentationID$.mp4' -media_seg_name 'dash_" + base
                                + "_$RepresentationID$_$Number%05d$.mp4'", mpd, ""));
                        produced.add(new Produced(groupIndex, "CMAF_DASH", List.of(mpd), mpd, 0, 0, 0));
                    }
                }
                case "DASH_ISO_GROUP_SETTINGS" -> {
                    int segment = settings.path("DashIsoGroupSettings").path("SegmentLength").asInt(30);
                    String mpd = dir + "/" + base + ".mpd";
                    script.append(" && ").append(plan.encode(list(group.path("Outputs")), "-f dash -seg_duration "
                            + segment + " -use_template 1 -use_timeline 1 -init_seg_name '" + base
                            + "_init_$RepresentationID$.mp4' -media_seg_name '" + base
                            + "_$RepresentationID$_$Number%05d$.mp4'", mpd, ""));
                    produced.add(new Produced(groupIndex, type, List.of(mpd), mpd, 0, 0, 0));
                }
                case "MS_SMOOTH_GROUP_SETTINGS" -> {
                    String ism = dir + "/" + base;
                    script.append(" && mkdir -p '").append(ism).append("' && ").append(plan.encode(
                            list(group.path("Outputs")), "-f smoothstreaming -window_size 0", ism, ""));
                    produced.add(new Produced(groupIndex, type, List.of(ism + "/Manifest"), ism + "/Manifest", 0,
                            0, 0));
                }
                default -> throw new TranscodeFailure(1050, "The output group type " + type + " isn't known.");
            }
            groupIndex++;
        }
        for (Produced output : produced) {
            for (String file : output.files()) {
                script.append(" && (ffprobe -v error -print_format json -show_format -show_streams '").append(file)
                        .append("' > '").append(file).append(".probe' 2>/dev/null || true)");
            }
        }
        Map<String, byte[]> files;
        try {
            files = ffmpeg.get().run(new LinkedHashMap<>(inputs), script.toString());
        } catch (KvsTranscoder.TranscodeException e) {
            throw new TranscodeFailure(1010, "Transcoding failed: " + e.getMessage());
        }
        List<Produced> detailed = new ArrayList<>();
        for (Produced output : produced) {
            long duration = output.durationMs();
            int width = output.width();
            int height = output.height();
            for (String file : output.files()) {
                JsonNode probe = parse(files.remove(file + ".probe"));
                JsonNode video = stream(probe, "video");
                duration = Math.round(duration(probe) * 1000) > 0 ? Math.round(duration(probe) * 1000) : duration;
                if (video != null) {
                    width = video.path("width").asInt(width);
                    height = video.path("height").asInt(height);
                }
            }
            List<String> outputFiles = new ArrayList<>(output.files());
            if (output.files().isEmpty()) {
                String prefix = "g" + output.group() + "/";
                files.keySet().stream().filter(name -> name.startsWith(prefix) && name.endsWith(".jpg"))
                        .sorted().forEach(outputFiles::add);
            }
            detailed.add(new Produced(output.group(), output.type(), outputFiles, output.playlist(),
                    duration > 0 ? duration : plan.duration(), width, height));
        }
        writeMainPlaylists(files, detailed, outputGroups, bases);
        return new Result(files, detailed);
    }

    private static List<JsonNode> list(JsonNode outputs) {
        List<JsonNode> list = new ArrayList<>();
        outputs.forEach(list::add);
        return list;
    }

    /** Writes an HLS group's main playlist (and a CMAF group's, unless disabled) over its renditions. */
    private void writeMainPlaylists(Map<String, byte[]> files, List<Produced> outputs, JsonNode groups,
                                    List<String> bases) {
        int index = 0;
        for (JsonNode group : groups) {
            String base = bases.get(index);
            String type = group.path("OutputGroupSettings").path("Type").asText();
            boolean cmaf = "CMAF_GROUP_SETTINGS".equals(type);
            if (!"HLS_GROUP_SETTINGS".equals(type) && !(cmaf && !"DISABLED".equals(group.path("OutputGroupSettings")
                    .path("CmafGroupSettings").path("WriteHlsManifest").asText("ENABLED")))) {
                index++;
                continue;
            }
            StringBuilder main = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(cmaf ? 7 : 3).append('\n');
            int at = 0;
            for (Produced output : outputs) {
                if (output.group() != index || output.playlist() == null || "CMAF_DASH".equals(output.type())) {
                    continue;
                }
                JsonNode settings = group.path("Outputs").get(at++);
                long bitrate = bitrate(settings);
                if (bitrate == 0 && output.durationMs() > 0) {
                    long bytes = 0;
                    for (Map.Entry<String, byte[]> file : files.entrySet()) {
                        String stem = output.playlist().substring(0, output.playlist().length() - ".m3u8".length());
                        if (file.getKey().startsWith(stem + "_")) {
                            bytes += file.getValue().length;
                        }
                    }
                    bitrate = bytes * 8000 / output.durationMs();
                }
                main.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(bitrate, 1));
                if (output.width() > 0) {
                    main.append(",RESOLUTION=").append(output.width()).append('x').append(output.height());
                }
                main.append('\n').append(output.playlist().substring(output.playlist().indexOf('/') + 1))
                        .append('\n');
            }
            files.put("g" + index + "/" + base + ".m3u8", main.toString().getBytes(StandardCharsets.UTF_8));
            index++;
        }
    }

    private static long bitrate(JsonNode output) {
        long total = 0;
        JsonNode codec = output.path("VideoDescription").path("CodecSettings");
        for (JsonNode settings : codec) {
            if (settings.isObject()) {
                total += settings.path("Bitrate").asLong(settings.path("MaxBitrate").asLong(0));
            }
        }
        for (JsonNode audio : output.path("AudioDescriptions")) {
            for (JsonNode settings : audio.path("CodecSettings")) {
                if (settings.isObject()) {
                    total += settings.path("Bitrate").asLong(0);
                }
            }
        }
        return total;
    }

    private static String modifier(JsonNode output) {
        return output.path("NameModifier").asText("").replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    /** The extension of a file group output's file. */
    static String extension(JsonNode output) {
        if (output.hasNonNull("Extension")) {
            return output.get("Extension").asText().replaceAll("[^A-Za-z0-9]", "");
        }
        String container = output.path("ContainerSettings").path("Container").asText("MP4");
        return switch (container) {
            case "MOV" -> "mov";
            case "MXF" -> "mxf";
            case "M2TS" -> "m2ts";
            case "WEBM" -> "webm";
            case "MKV" -> "mkv";
            case "OGG" -> "ogg";
            case "F4V" -> "f4v";
            case "ISMV" -> "ismv";
            case "GIF" -> "gif";
            case "Y4M" -> "y4m";
            case "RAW" -> rawExtension(output);
            default -> "mp4";
        };
    }

    private static String rawExtension(JsonNode output) {
        if (output.path("VideoDescription").isObject()) {
            return switch (output.path("VideoDescription").path("CodecSettings").path("Codec").asText()) {
                case "H_265" -> "h265";
                case "MPEG2" -> "m2v";
                default -> "h264";
            };
        }
        String codec = output.path("AudioDescriptions").path(0).path("CodecSettings").path("Codec").asText("AAC");
        return switch (codec) {
            case "MP3" -> "mp3";
            case "MP2" -> "mp2";
            case "AC3" -> "ac3";
            case "EAC3", "EAC3_ATMOS" -> "eac3";
            case "FLAC" -> "flac";
            case "WAV" -> "wav";
            case "AIFF" -> "aiff";
            case "OPUS" -> "opus";
            case "VORBIS" -> "ogg";
            default -> "aac";
        };
    }

    private static String muxer(JsonNode output) {
        return switch (extension(output)) {
            case "mov" -> "-f mov";
            case "mxf" -> "-f mxf";
            case "m2ts" -> "-f mpegts";
            case "webm" -> "-f webm";
            case "mkv" -> "-f matroska";
            case "ogg", "opus" -> "-f ogg";
            case "f4v" -> "-f f4v";
            case "ismv" -> "-f ismv";
            case "gif" -> "-f gif";
            case "y4m" -> "-f yuv4mpegpipe";
            case "h264", "h265" -> "-f " + ("h265".equals(extension(output)) ? "hevc" : "h264");
            case "m2v" -> "-f mpeg2video";
            case "mp3" -> "-f mp3";
            case "mp2" -> "-f mp2";
            case "ac3" -> "-f ac3";
            case "eac3" -> "-f eac3";
            case "flac" -> "-f flac";
            case "wav" -> "-f wav";
            case "aiff" -> "-f aiff";
            case "aac" -> "-f adts";
            default -> "-f mp4 -movflags +faststart";
        };
    }

    // ================================================================ the ffmpeg command

    /** The inputs as one source: their clipping, and (with several) their stitching. */
    private static final class Plan {
        final List<String> names;
        final List<JsonNode> settings;
        final Map<String, JsonNode> probes;

        Plan(List<String> names, List<JsonNode> settings, Map<String, JsonNode> probes) {
            this.names = names;
            this.settings = settings;
            this.probes = probes;
        }

        long duration() {
            double total = 0;
            for (String name : names) {
                total += MediaConvertTranscoder.duration(probes.get(name));
            }
            return Math.round(total * 1000);
        }

        private String inputArgs() {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                JsonNode clipping = settings.get(i).path("InputClippings").path(0);
                double fps = MediaConvertTranscoder.fps(probes.get(names.get(i)));
                if (clipping.hasNonNull("StartTimecode")) {
                    args.append(" -ss ").append(seconds(clipping.get("StartTimecode").asText(), fps));
                }
                if (clipping.hasNonNull("EndTimecode")) {
                    args.append(" -to ").append(seconds(clipping.get("EndTimecode").asText(), fps));
                }
                args.append(" -i '").append(names.get(i)).append('\'');
            }
            return args.toString();
        }

        boolean hasAudio() {
            for (String name : names) {
                if (stream(probes.get(name), "audio") == null) {
                    return false;
                }
            }
            return true;
        }

        boolean hasVideo() {
            return stream(probes.get(names.get(0)), "video") != null;
        }

        /** The filter graph that stitches several inputs into [cv] and [ca], or "" for one input. */
        private String stitch() {
            if (names.size() == 1) {
                return "";
            }
            JsonNode first = stream(probes.get(names.get(0)), "video");
            int width = first == null ? 1280 : first.path("width").asInt(1280);
            int height = first == null ? 720 : first.path("height").asInt(720);
            boolean audio = hasAudio();
            StringBuilder graph = new StringBuilder();
            StringBuilder pads = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                graph.append("[").append(i).append(":v:0]scale=").append(width).append(':').append(height)
                        .append(":force_original_aspect_ratio=decrease,pad=").append(width).append(':')
                        .append(height).append(":(ow-iw)/2:(oh-ih)/2,setsar=1[iv").append(i).append("];");
                pads.append("[iv").append(i).append(']');
                if (audio) {
                    graph.append("[").append(i).append(":a:0]aresample=48000,aformat=channel_layouts=stereo[ia")
                            .append(i).append("];");
                    pads.append("[ia").append(i).append(']');
                }
            }
            graph.append(pads).append("concat=n=").append(names.size()).append(":v=1:a=").append(audio ? 1 : 0)
                    .append("[cv]").append(audio ? "[ca]" : "").append(';');
            return graph.toString();
        }

        /** The ffmpeg command encoding outputs into one muxer. */
        String encode(List<JsonNode> outputs, String muxer, String target, String extra) {
            StringBuilder graph = new StringBuilder(stitch());
            boolean stitched = names.size() > 1;
            List<JsonNode> videos = new ArrayList<>();
            int audios = 0;
            for (JsonNode output : outputs) {
                if (output.path("VideoDescription").isObject() && hasVideo()) {
                    videos.add(output);
                }
                audios += output.path("AudioDescriptions").size();
            }
            String videoSource = stitched ? "[cv]" : "[0:v:0]";
            if (videos.size() > 1) {
                graph.append(videoSource).append("split=").append(videos.size());
                for (int i = 0; i < videos.size(); i++) {
                    graph.append("[vs").append(i).append(']');
                }
                graph.append(';');
            }
            String audioSource = stitched ? "[ca]" : null;
            if (stitched && audios > 1 && hasAudio()) {
                graph.append("[ca]asplit=").append(audios);
                for (int i = 0; i < audios; i++) {
                    graph.append("[as").append(i).append(']');
                }
                graph.append(';');
            }
            StringBuilder maps = new StringBuilder();
            StringBuilder codecs = new StringBuilder();
            int videoIndex = 0;
            int audioIndex = 0;
            for (JsonNode output : outputs) {
                JsonNode video = output.path("VideoDescription");
                if (video.isObject() && hasVideo()) {
                    String source = videos.size() > 1 ? "[vs" + videoIndex + "]" : videoSource;
                    String codec = video.path("CodecSettings").path("Codec").asText();
                    if ("PASSTHROUGH".equals(codec) && !stitched) {
                        maps.append(" -map 0:v:0");
                    } else {
                        graph.append(source).append(videoFilter(video)).append("[v").append(videoIndex).append("];");
                        maps.append(" -map '[v").append(videoIndex).append("]'");
                    }
                    codecs.append(videoCodec(video, ":v:" + videoIndex));
                    videoIndex++;
                }
                for (JsonNode audio : output.path("AudioDescriptions")) {
                    if (stitched) {
                        if (!hasAudio()) {
                            continue;
                        }
                        maps.append(" -map '").append(audios > 1 ? "[as" + audioIndex + "]" : audioSource)
                                .append('\'');
                    } else {
                        maps.append(" -map 0:a:0?");
                    }
                    codecs.append(audioCodec(audio, ":a:" + audioIndex));
                    audioIndex++;
                }
            }
            String filter = graph.isEmpty() ? "" : " -filter_complex '" + trimSemicolon(graph) + "'";
            return "ffmpeg -y -hide_banner -loglevel error" + inputArgs() + filter + maps + codecs + " " + muxer
                    + extra + " '" + target + "'";
        }

        /** A JPEG frame capture: frames at its rate, up to its maximum, at its quality. */
        String frameCapture(JsonNode output, String stem) {
            JsonNode video = output.path("VideoDescription");
            JsonNode capture = video.path("CodecSettings").path("FrameCaptureSettings");
            int numerator = capture.path("FramerateNumerator").asInt(1);
            int denominator = capture.path("FramerateDenominator").asInt(5);
            int max = capture.path("MaxCaptures").asInt(10000000);
            int quality = capture.path("Quality").asInt(80);
            String source = names.size() > 1 ? "[cv]" : "[0:v:0]";
            String graph = stitch() + source + "fps=" + numerator + "/" + denominator + scaleFilter(video) + "[f]";
            int q = Math.max(2, Math.min(31, 31 - quality * 29 / 100));
            return "ffmpeg -y -hide_banner -loglevel error" + inputArgs() + " -filter_complex '" + graph
                    + "' -map '[f]' -frames:v " + max + " -q:v " + q + " -start_number 0 -f image2 '" + stem + ".%07d.jpg'";
        }
    }

    private static String trimSemicolon(StringBuilder graph) {
        String text = graph.toString();
        return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
    }

    private static String scaleFilter(JsonNode video) {
        int width = video.path("Width").asInt(0);
        int height = video.path("Height").asInt(0);
        if (width == 0 && height == 0) {
            return "";
        }
        String w = width == 0 ? "-2" : String.valueOf(width);
        String h = height == 0 ? "-2" : String.valueOf(height);
        if (width == 0 || height == 0) {
            return ",scale=" + w + ":" + h;
        }
        return switch (video.path("ScalingBehavior").asText("DEFAULT")) {
            case "STRETCH_TO_OUTPUT" -> ",scale=" + w + ":" + h + ",setsar=1";
            case "FIT" -> ",scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:force_divisible_by=2";
            case "FIT_NO_UPSCALE" -> ",scale='min(" + w + ",iw)':'min(" + h + ",ih)':force_original_aspect_ratio="
                    + "decrease:force_divisible_by=2";
            case "FILL" -> ",scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h;
            default -> ",scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h
                    + ":(ow-iw)/2:(oh-ih)/2,setsar=1";
        };
    }

    private static String videoFilter(JsonNode video) {
        StringBuilder filter = new StringBuilder("null");
        JsonNode crop = video.path("Crop");
        if (crop.isObject()) {
            filter.append(",crop=").append(crop.path("Width").asInt()).append(':').append(crop.path("Height")
                    .asInt()).append(':').append(crop.path("X").asInt(0)).append(':').append(crop.path("Y")
                    .asInt(0));
        }
        filter.append(scaleFilter(video));
        JsonNode settings = codecSettings(video.path("CodecSettings"));
        if ("SPECIFIED".equals(settings.path("FramerateControl").asText()) && settings.has("FramerateNumerator")) {
            filter.append(",fps=").append(settings.path("FramerateNumerator").asInt()).append('/')
                    .append(settings.path("FramerateDenominator").asInt(1));
        }
        return filter.toString();
    }

    /** The codec's own settings object (H264Settings and so on). */
    private static JsonNode codecSettings(JsonNode codecSettings) {
        for (JsonNode value : codecSettings) {
            if (value.isObject()) {
                return value;
            }
        }
        return codecSettings.path("none");
    }

    private static String videoCodec(JsonNode video, String spec) {
        JsonNode codecSettings = video.path("CodecSettings");
        String codec = codecSettings.path("Codec").asText();
        JsonNode settings = codecSettings(codecSettings);
        StringBuilder args = new StringBuilder();
        switch (codec) {
            case "H_264", "AVC_INTRA", "XAVC" -> {
                args.append(" -c").append(spec).append(" libx264 -preset:v").append(spec.substring(2)).append(' ')
                        .append(preset(settings));
                String profile = settings.path("CodecProfile").asText("MAIN");
                args.append(" -profile").append(spec).append(' ').append(switch (profile) {
                    case "BASELINE" -> "baseline";
                    case "HIGH" -> "high";
                    case "HIGH_10BIT" -> "high10";
                    case "HIGH_422", "HIGH_422_10BIT" -> "high422";
                    default -> "main";
                });
                args.append(" -pix_fmt").append(spec).append(' ').append(switch (profile) {
                    case "HIGH_10BIT" -> "yuv420p10le";
                    case "HIGH_422" -> "yuv422p";
                    case "HIGH_422_10BIT" -> "yuv422p10le";
                    default -> "yuv420p";
                });
                if ("AVC_INTRA".equals(codec)) {
                    args.append(" -g").append(spec).append(" 1");
                }
                rate(args, settings, spec);
                gop(args, settings, spec);
            }
            case "H_265" -> {
                args.append(" -c").append(spec).append(" libx265 -preset:v").append(spec.substring(2)).append(' ')
                        .append(preset(settings)).append(" -tag").append(spec).append(' ')
                        .append("HEV1".equals(settings.path("WriteMp4PackagingType").asText()) ? "hev1" : "hvc1");
                args.append(" -pix_fmt").append(spec).append(' ').append(settings.path("CodecProfile")
                        .asText("MAIN_MAIN").startsWith("MAIN10") ? "yuv420p10le" : "yuv420p");
                rate(args, settings, spec);
                gop(args, settings, spec);
            }
            case "AV1" -> {
                args.append(" -c").append(spec).append(" libsvtav1 -preset").append(spec).append(" 10");
                args.append(" -pix_fmt").append(spec).append(' ').append("BIT_10".equals(settings.path("BitDepth")
                        .asText()) ? "yuv420p10le" : "yuv420p");
                rate(args, settings, spec);
                gop(args, settings, spec);
            }
            case "VP8", "VP9" -> {
                args.append(" -c").append(spec).append("VP8".equals(codec) ? " libvpx" : " libvpx-vp9")
                        .append(" -deadline").append(spec).append(" realtime -cpu-used").append(spec).append(" 8");
                rate(args, settings, spec);
                gop(args, settings, spec);
            }
            case "MPEG2" -> {
                args.append(" -c").append(spec).append(" mpeg2video");
                rate(args, settings, spec);
                gop(args, settings, spec);
            }
            case "PRORES" -> args.append(" -c").append(spec).append(" prores_ks -profile").append(spec).append(' ')
                    .append(switch (settings.path("CodecProfile").asText("APPLE_PRORES_422")) {
                        case "APPLE_PRORES_422_PROXY" -> 0;
                        case "APPLE_PRORES_422_LT" -> 1;
                        case "APPLE_PRORES_422_HQ" -> 3;
                        case "APPLE_PRORES_4444" -> 4;
                        case "APPLE_PRORES_4444_XQ" -> 5;
                        default -> 2;
                    });
            case "VC3" -> args.append(" -c").append(spec).append(" dnxhd -profile").append(spec)
                    .append(" dnxhr_hq -pix_fmt").append(spec).append(" yuv422p");
            case "GIF" -> args.append(" -c").append(spec).append(" gif");
            case "UNCOMPRESSED" -> args.append(" -c").append(spec).append(" rawvideo");
            case "PASSTHROUGH" -> args.append(" -c").append(spec).append(" copy");
            default -> throw new TranscodeFailure(1050, "The video codec " + codec + " isn't known.");
        }
        return args.toString();
    }

    private static String preset(JsonNode settings) {
        return switch (settings.path("QualityTuningLevel").asText("SINGLE_PASS")) {
            case "SINGLE_PASS_HQ" -> "fast";
            case "MULTI_PASS_HQ" -> "medium";
            default -> "veryfast";
        };
    }

    private static void rate(StringBuilder args, JsonNode settings, String spec) {
        String mode = settings.path("RateControlMode").asText(settings.has("Bitrate") ? "CBR" : "QVBR");
        long bitrate = settings.path("Bitrate").asLong(0);
        long max = settings.path("MaxBitrate").asLong(0);
        switch (mode) {
            case "QVBR" -> {
                int level = settings.path("QvbrSettings").path("QvbrQualityLevel").asInt(7);
                args.append(" -crf").append(spec).append(' ').append(Math.max(0, 51 - level * 4));
                if (max > 0) {
                    args.append(" -maxrate").append(spec).append(' ').append(max).append(" -bufsize").append(spec)
                            .append(' ').append(max * 2);
                }
            }
            case "CBR" -> {
                if (bitrate > 0) {
                    args.append(" -b").append(spec).append(' ').append(bitrate).append(" -minrate").append(spec)
                            .append(' ').append(bitrate).append(" -maxrate").append(spec).append(' ').append(bitrate)
                            .append(" -bufsize").append(spec).append(' ').append(bitrate * 2);
                }
            }
            default -> {
                if (bitrate > 0) {
                    args.append(" -b").append(spec).append(' ').append(bitrate);
                }
                if (max > 0) {
                    args.append(" -maxrate").append(spec).append(' ').append(max).append(" -bufsize").append(spec)
                            .append(' ').append(max * 2);
                }
            }
        }
    }

    private static void gop(StringBuilder args, JsonNode settings, String spec) {
        if (settings.has("GopSize")) {
            double size = settings.get("GopSize").asDouble();
            if ("SECONDS".equals(settings.path("GopSizeUnits").asText())) {
                args.append(" -force_key_frames").append(spec).append(" 'expr:gte(t,n_forced*").append(size)
                        .append(")'");
            } else if (size >= 1) {
                args.append(" -g").append(spec).append(' ').append(Math.round(size));
            }
        }
        if (settings.has("NumberBFramesBetweenReferenceFrames")) {
            args.append(" -bf").append(spec).append(' ').append(settings.get(
                    "NumberBFramesBetweenReferenceFrames").asInt());
        }
    }

    private static String audioCodec(JsonNode audio, String spec) {
        JsonNode codecSettings = audio.path("CodecSettings");
        String codec = codecSettings.path("Codec").asText("AAC");
        JsonNode settings = codecSettings(codecSettings);
        StringBuilder args = new StringBuilder(" -c").append(spec).append(' ').append(switch (codec) {
            case "MP3" -> "libmp3lame";
            case "MP2" -> "mp2";
            case "AC3" -> "ac3";
            case "EAC3", "EAC3_ATMOS" -> "eac3";
            case "OPUS" -> "libopus";
            case "VORBIS" -> "libvorbis";
            case "FLAC" -> "flac";
            case "WAV" -> settings.path("BitDepth").asInt(16) == 24 ? "pcm_s24le" : "pcm_s16le";
            case "AIFF" -> settings.path("BitDepth").asInt(16) == 24 ? "pcm_s24be" : "pcm_s16be";
            case "PASSTHROUGH" -> "copy";
            case "AC4" -> throw new TranscodeFailure(1050, "Floci's ffmpeg has no AC-4 encoder.");
            default -> "aac";
        });
        if ("PASSTHROUGH".equals(codec)) {
            return args.toString();
        }
        if (settings.has("Bitrate") && !"FLAC".equals(codec) && !"WAV".equals(codec) && !"AIFF".equals(codec)) {
            args.append(" -b").append(spec).append(' ').append(settings.get("Bitrate").asLong());
        }
        if (settings.has("SampleRate")) {
            args.append(" -ar").append(spec).append(' ').append(settings.get("SampleRate").asInt());
        }
        int channels = settings.path("Channels").asInt(switch (settings.path("CodingMode").asText("")) {
            case "CODING_MODE_1_0" -> 1;
            case "CODING_MODE_5_1", "CODING_MODE_3_2_LFE" -> 6;
            case "" -> 0;
            default -> 2;
        });
        if (channels > 0) {
            args.append(" -ac").append(spec).append(' ').append(channels);
        }
        return args.toString();
    }

    // ================================================================ probe results

    /** A probe result as the API shows it: the container, its tracks and the file's metadata. */
    ObjectNode probeResult(JsonNode probe, long size, String etag, double lastModified, String mimeType) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode container = result.putObject("Container");
        JsonNode format = probe.path("format");
        container.put("Format", format(format.path("format_name").asText(""), probe));
        if (format.has("duration")) {
            container.put("Duration", format.path("duration").asDouble());
        }
        if (format.has("bit_rate")) {
            container.put("BitRate", format.path("bit_rate").asLong());
        }
        ArrayNode tracks = container.putArray("Tracks");
        ObjectNode mapping = result.putArray("TrackMappings").addObject();
        ArrayNode videoIndexes = mapping.putArray("VideoTrackIndexes");
        ArrayNode audioIndexes = mapping.putArray("AudioTrackIndexes");
        ArrayNode dataIndexes = mapping.putArray("DataTrackIndexes");
        for (JsonNode stream : probe.path("streams")) {
            String type = stream.path("codec_type").asText();
            int index = stream.path("index").asInt() + 1;
            ObjectNode track = tracks.addObject();
            track.put("Index", index);
            track.put("Codec", codec(stream.path("codec_name").asText("")));
            if (stream.has("duration")) {
                track.put("Duration", stream.path("duration").asDouble());
            }
            switch (type) {
                case "video" -> {
                    track.put("TrackType", "video");
                    videoIndexes.add(index);
                    ObjectNode properties = track.putObject("VideoProperties");
                    properties.put("Width", stream.path("width").asInt());
                    properties.put("Height", stream.path("height").asInt());
                    String[] rate = stream.path("avg_frame_rate").asText("0/1").split("/");
                    properties.putObject("FrameRate").put("Numerator", Integer.parseInt(rate[0]))
                            .put("Denominator", rate.length > 1 ? Integer.parseInt(rate[1]) : 1);
                    if (stream.has("bit_rate")) {
                        properties.put("BitRate", stream.path("bit_rate").asLong());
                    }
                    properties.put("BitDepth", stream.path("pix_fmt").asText("").contains("10") ? 10 : 8);
                    properties.put("ColorPrimaries", color(stream.path("color_primaries").asText("")));
                    properties.put("TransferCharacteristics", color(stream.path("color_transfer").asText("")));
                    properties.put("MatrixCoefficients", color(stream.path("color_space").asText("")));
                }
                case "audio" -> {
                    track.put("TrackType", "audio");
                    audioIndexes.add(index);
                    ObjectNode properties = track.putObject("AudioProperties");
                    properties.put("Channels", stream.path("channels").asInt());
                    properties.put("SampleRate", stream.path("sample_rate").asInt());
                    if (stream.has("channel_layout")) {
                        properties.put("ChannelLayout", stream.path("channel_layout").asText());
                    }
                    if (stream.has("bit_rate")) {
                        properties.put("BitRate", stream.path("bit_rate").asLong());
                    }
                    properties.put("BitDepth", stream.path("bits_per_raw_sample").asInt(16));
                    String language = stream.path("tags").path("language").asText(null);
                    if (language != null) {
                        properties.put("LanguageCode", language.toUpperCase(Locale.ROOT));
                    }
                }
                default -> {
                    track.put("TrackType", "data");
                    dataIndexes.add(index);
                }
            }
        }
        ObjectNode metadata = result.putObject("Metadata");
        metadata.put("FileSize", size);
        metadata.put("ETag", etag);
        metadata.put("LastModified", lastModified);
        metadata.put("MimeType", mimeType);
        return result;
    }

    private static String format(String name, JsonNode probe) {
        if (name.contains("mp4") || name.contains("mov")) {
            String brand = probe.path("format").path("tags").path("major_brand").asText("");
            return brand.startsWith("qt") ? "quicktime" : "mp4";
        }
        if (name.contains("matroska") || name.contains("webm")) {
            JsonNode video = stream(probe, "video");
            String codec = video == null ? "" : video.path("codec_name").asText();
            return Set.of("vp8", "vp9", "av1").contains(codec) ? "webm" : "matroska";
        }
        return switch (name) {
            case "mpegts" -> "mpeg-ts";
            case "mxf" -> "mxf";
            case "wav" -> "wave";
            case "mp3" -> "mp3";
            case "flac" -> "flac";
            case "aac" -> "aac";
            case "ogg" -> "ogg";
            case "avi" -> "avi";
            case "gif" -> "gif";
            case "image2", "jpeg_pipe", "png_pipe" -> "png";
            default -> "UNKNOWN";
        };
    }

    private static String codec(String name) {
        return switch (name) {
            case "h264" -> "H_264";
            case "hevc" -> "H_265";
            case "av1" -> "AV1";
            case "vp8" -> "VP8";
            case "vp9" -> "VP9";
            case "mpeg2video" -> "MPEG2";
            case "mpeg1video" -> "MPEG1";
            case "prores" -> "PRORES";
            case "dnxhd" -> "VC3";
            case "mjpeg" -> "JPEG2000";
            case "aac" -> "AAC";
            case "ac3" -> "AC3";
            case "eac3" -> "EAC3";
            case "mp3" -> "MP3";
            case "mp2" -> "MP2";
            case "opus" -> "OPUS";
            case "vorbis" -> "VORBIS";
            case "flac" -> "FLAC";
            case "pcm_s16le", "pcm_s24le", "pcm_s16be", "pcm_s24be" -> "PCM";
            default -> "UNKNOWN";
        };
    }

    private static String color(String value) {
        return switch (value) {
            case "bt709" -> "ITU_709";
            case "bt2020", "bt2020nc", "bt2020c" -> "ITU_2020";
            case "smpte170m" -> "SMPTE_170M";
            case "bt470bg" -> "ITU_470BG";
            case "smpte2084" -> "SMPTE_2084";
            case "arib-std-b67" -> "HLG";
            default -> "UNSPECIFIED";
        };
    }
}
