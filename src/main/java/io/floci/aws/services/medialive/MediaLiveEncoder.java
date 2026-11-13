package io.floci.aws.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a running channel's encoder: one ffmpeg container per channel, in the media image Kinesis Video Streams
 * transcodes with. It reads the active input attachment's input: a URL, RTMP or MP4 source (an {@code s3://} one
 * through Floci, looped when its input settings say so), or, for RTMP, UDP and RTP push inputs, what is pushed to
 * the input's destination port, which the container publishes. Each output group encodes its outputs' video and audio
 * descriptions: HLS groups a main playlist and a playlist and segments per output, archive groups rolling TS
 * files, frame capture groups JPEGs at their interval, RTMP, UDP and SRT groups streams to their destinations.
 * {@code s3://} destinations are written in the container and copied to S3 as they appear; HTTP(S) destinations (a
 * MediaPackage ingest endpoint) are written with PUT. When ffmpeg stops, the channel gets an alert. MediaPackage,
 * Microsoft Smooth, CMAF ingest, multiplex and MediaConnect router groups, and MediaConnect inputs, aren't carried:
 * each gets an alert.
 */
@ApplicationScoped
public class MediaLiveEncoder implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(MediaLiveEncoder.class);
    private static final int RTMP_PORT = 1935;
    private static final int UDP_PORT = 5000;
    /** A MediaPackage v2 ingest or egress host, which its path-style URL reaches without a Host header. */
    private static final Pattern MEDIAPACKAGE_HOST = Pattern.compile(
            "(?i)([a-z0-9-]+)\\.(ingest|egress)\\.mediapackagev2\\.([a-z0-9-]+)\\..*");

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;
    private final MediaLiveStore store;
    private final Provider<S3Service> s3;
    private final Provider<MediaLiveChannels> channels;
    private final LaunchedContainerAwsEnv awsEnv;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, String[]> tokens = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "medialive-encoder");
        thread.setDaemon(true);
        return thread;
    });

    /** A running encoder: its container, and where each output group's files go. */
    private static final class Running {
        final String account;
        final String region;
        final String channelId;
        final String containerId;
        final List<String[]> uploads;
        final Map<String, String> seen = new HashMap<>();
        volatile boolean failed;
        volatile String lastLog;

        Running(String account, String region, String channelId, String containerId, List<String[]> uploads) {
            this.account = account;
            this.region = region;
            this.channelId = channelId;
            this.containerId = containerId;
            this.uploads = uploads;
        }
    }

    @Inject
    public MediaLiveEncoder(ContainerBuilder containers, ContainerLifecycleManager lifecycle, EmulatorConfig config,
                            MediaLiveStore store, Provider<S3Service> s3, Provider<MediaLiveChannels> channels,
                            LaunchedContainerAwsEnv awsEnv) {
        this.awsEnv = awsEnv;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.config = config;
        this.store = store;
        this.s3 = s3;
        this.channels = channels;
        poller.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
        stopManagedContainers();
    }

    @Override
    public void stopManagedContainers() {
        for (String arn : List.copyOf(running.keySet())) {
            stop(arn);
        }
    }

    /** The account, Region and channel a token was issued for, reading S3 inputs. */
    String[] token(String token) {
        return tokens.get(token);
    }

    // ================================================================ starting

    synchronized void start(String account, String region, ObjectNode channel) {
        String arn = channel.path("Arn").asText();
        stop(arn);
        String channelId = channel.path("Id").asText();
        String token = UUID.randomUUID().toString();
        tokens.put(token, new String[] {account, region, channelId});
        Plan plan;
        try {
            plan = plan(account, region, channel, token);
        } catch (RuntimeException e) {
            channels.get().alert(account, region, channelId, "Pipeline Failure", e.getMessage(), true);
            return;
        }
        plan.alerts().forEach(message -> channels.get().alert(account, region, channelId, "Output Group Not "
                + "Supported", message, true));
        String image = config.services().kinesisVideo().ffmpegImage();
        String name = "floci-medialive-" + channelId;
        lifecycle.removeIfExists(name);
        ContainerBuilder.Builder builder = containers.newContainer(image).withName(name)
                .withEntrypoint(List.of("sh", "-c", plan.script()))
                .withDockerNetwork(store.config().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(MediaLiveStore.SERVICE, "channel", account,
                        channelId));
        if (plan.rtmpPort() > 0) {
            builder.withPortBinding(RTMP_PORT, plan.rtmpPort());
        }
        if (plan.udpPort() > 0) {
            builder.withUdpPortBinding(UDP_PORT, plan.udpPort());
        }
        ContainerSpec spec = builder.build();
        LOG.debugv("MediaLive channel {0} encodes with: {1}", channelId, plan.script());
        try {
            String id = lifecycle.create(spec);
            lifecycle.startCreated(id, spec);
            running.put(arn, new Running(account, region, channelId, id, plan.uploads()));
            channels.get().alert(account, region, channelId, "Pipeline Failure", "", false);
        } catch (RuntimeException e) {
            LOG.warnv(e, "MediaLive could not start the encoder of channel {0}", channelId);
            lifecycle.removeIfExists(name);
            channels.get().alert(account, region, channelId, "Pipeline Failure", "The encoder couldn't start: "
                    + e.getMessage(), true);
        }
    }

    synchronized void stop(String channelArn) {
        Running encoder = running.remove(channelArn);
        if (encoder == null) {
            return;
        }
        try {
            upload(encoder, true);
        } catch (RuntimeException e) {
            LOG.debugv("MediaLive could not copy the last outputs of channel {0}: {1}", encoder.channelId,
                    e.getMessage());
        }
        tokens.values().removeIf(issued -> encoder.channelId.equals(issued[2]) && encoder.account.equals(issued[0]));
        lifecycle.stopAndRemove(encoder.containerId, null);
    }

    /** The newest thumbnail of a running channel, base64, or null. */
    String thumbnail(String channelArn) {
        Running encoder = running.get(channelArn);
        if (encoder == null) {
            return null;
        }
        byte[] image = copy(encoder.containerId, "/out/.thumb.jpg");
        return image == null || image.length == 0 ? null : Base64.getEncoder().encodeToString(image);
    }

    // ================================================================ the command

    private record Plan(String script, int rtmpPort, int udpPort, List<String[]> uploads, List<String> alerts) {
    }

    /** The base URL containers reach Floci at. */
    private String flociUrl() {
        return awsEnv.flociEndpoint().replaceAll("/+$", "");
    }

    /**
     * A URL as the encoder's container reaches it, and the Host header to send with it: a Floci-hosted URL (a
     * {@code *.localhost.floci.io} or localhost host, such as a MediaPackage ingest endpoint) goes to Floci's
     * container-reachable endpoint with its own host in the header, which Floci routes by; any other URL is as is.
     */
    String[] reachable(String url) {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new String[] {url, null};
        }
        String host = parsed.getHost();
        String scheme = parsed.getScheme();
        if (host == null || scheme == null || !scheme.toLowerCase(Locale.ROOT).startsWith("http")
                || !(host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(
                ".localhost.floci.io") || host.equalsIgnoreCase("localhost.floci.io") || "127.0.0.1".equals(host))) {
            return new String[] {url, null};
        }
        String original = host + (parsed.getPort() < 0 ? "" : ":" + parsed.getPort());
        String path = parsed.getRawPath() + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        Matcher packaging = MEDIAPACKAGE_HOST.matcher(host);
        if (packaging.matches()) {
            return new String[] {flociUrl() + "/_floci/mediapackagev2/" + packaging.group(2).toLowerCase(Locale.ROOT)
                    + "/" + packaging.group(3) + "/" + packaging.group(1) + path, null};
        }
        return new String[] {flociUrl() + path, original};
    }

    /**
     * Custom headers that set the Host: ffmpeg keeps its own Host header unless a custom one follows a line break,
     * so another header goes first.
     */
    private static String hostHeader(String host) {
        return "X-Floci-Route: medialive\r\nHost: " + host + "\r\n";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Plan plan(String account, String region, ObjectNode channel, String token) {
        JsonNode attachments = channel.path("InputAttachments");
        String active = channel.path("PipelineDetails").path(0).path("ActiveInputAttachmentName").asText("");
        JsonNode chosen = attachments.path(0);
        for (JsonNode candidate : attachments) {
            if (active.equals(candidate.path("InputAttachmentName").asText())) {
                chosen = candidate;
            }
        }
        JsonNode attachment = chosen;
        ObjectNode input = store.getForAccount(account, region, MediaLiveStore.INPUT,
                attachment.path("InputId").asText()).orElseThrow(() -> MediaLiveStore.notFound(
                "The channel's input " + attachment.path("InputId").asText() + " doesn't exist."));
        StringBuilder args = new StringBuilder("ffmpeg -hide_banner -loglevel error -nostdin -y");
        int rtmpPort = 0;
        int udpPort = 0;
        String type = input.path("Type").asText();
        JsonNode inputSettings = attachment.path("InputSettings");
        switch (type) {
            case "URL_PULL", "MP4_FILE", "RTMP_PULL" -> {
                String url = input.path("Sources").path(0).path("Url").asText();
                boolean file = "MP4_FILE".equals(type) || url.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov|ts|mkv)"
                        + "(\\?.*)?$");
                if (url.startsWith("s3://") || url.startsWith("s3ssl://")) {
                    url = flociUrl() + MediaLiveFilesController.PATH + token + "/" + url.substring(url.indexOf("://")
                            + 3);
                }
                if (file && "LOOP".equals(inputSettings.path("SourceEndBehavior").asText())) {
                    args.append(" -stream_loop -1");
                }
                if (file) {
                    args.append(" -re");
                }
                String[] reachable = reachable(url);
                if (reachable[1] != null) {
                    args.append(" -headers ").append(quote(hostHeader(reachable[1])));
                }
                args.append(" -i ").append(quote(reachable[0]));
            }
            case "RTMP_PUSH" -> {
                rtmpPort = input.path("Destinations").path(0).path("Port").asInt();
                String stream = input.path("Destinations").path(0).path("Url").asText().replaceFirst(
                        "^rtmp://[^/]+/", "");
                args.append(" -listen 1 -timeout -1 -i ").append(quote("rtmp://0.0.0.0:" + RTMP_PORT + "/"
                        + stream));
            }
            case "UDP_PUSH" -> {
                udpPort = input.path("Destinations").path(0).path("Port").asInt();
                args.append(" -i ").append(quote("udp://0.0.0.0:" + UDP_PORT + "?fifo_size=1000000"
                        + "&overrun_nonfatal=1"));
            }
            case "RTP_PUSH" -> {
                udpPort = input.path("Destinations").path(0).path("Port").asInt();
                args.append(" -i ").append(quote("rtp://0.0.0.0:" + UDP_PORT));
            }
            default -> throw MediaLiveStore.unprocessable("Floci's encoder doesn't read " + type + " inputs.");
        }
        JsonNode settings = channel.path("EncoderSettings");
        Map<String, JsonNode> videos = new HashMap<>();
        Map<String, JsonNode> audios = new HashMap<>();
        settings.path("VideoDescriptions").forEach(video -> videos.put(video.path("Name").asText(), video));
        settings.path("AudioDescriptions").forEach(audio -> audios.put(audio.path("Name").asText(), audio));
        Map<String, JsonNode> destinations = new HashMap<>();
        channel.path("Destinations").forEach(destination -> destinations.put(destination.path("Id").asText(),
                destination));
        List<String[]> uploads = new ArrayList<>();
        List<String> alerts = new ArrayList<>();
        StringBuilder mkdirs = new StringBuilder("mkdir -p /out");
        int videoCount = 0;
        for (JsonNode group : settings.path("OutputGroups")) {
            for (JsonNode output : group.path("Outputs")) {
                if (output.hasNonNull("VideoDescriptionName")) {
                    videoCount++;
                }
            }
        }
        StringBuilder graph = new StringBuilder("[0:v:0]split=" + (videoCount + 1));
        for (int i = 0; i <= videoCount; i++) {
            graph.append("[s").append(i).append(']');
        }
        graph.append(";[s").append(videoCount).append("]fps=1/2,scale=-2:360[thumb]");
        StringBuilder outputs = new StringBuilder();
        int videoIndex = 0;
        int groupIndex = 0;
        for (JsonNode group : settings.path("OutputGroups")) {
            JsonNode groupSettings = group.path("OutputGroupSettings");
            String kind = groupSettings.fieldNames().hasNext() ? groupSettings.fieldNames().next() : "";
            JsonNode specific = groupSettings.path(kind);
            String dir = "/out/g" + groupIndex;
            switch (kind) {
                case "HlsGroupSettings", "ArchiveGroupSettings", "FrameCaptureGroupSettings" -> {
                    String url = url(destinations, specific.path("Destination").path("DestinationRefId").asText());
                    if (url == null) {
                        alerts.add("The " + group.path("Name").asText(kind) + " group has no destination URL.");
                        break;
                    }
                    boolean s3 = url.startsWith("s3://") || url.startsWith("s3ssl://");
                    String[] reachable = s3 ? new String[] {url, null} : reachable(url);
                    url = reachable[0];
                    String location = s3 ? url.substring(url.indexOf("://") + 3) : url;
                    String base = location.substring(location.lastIndexOf('/') + 1);
                    String folder = location.substring(0, location.lastIndexOf('/') + 1);
                    String prefix = s3 ? dir + "/" : folder;
                    if (s3) {
                        mkdirs.append(' ').append(dir);
                        uploads.add(new String[] {"g" + groupIndex, folder});
                    }
                    String method = s3 ? "" : " -method PUT" + (reachable[1] == null ? "" : " -headers "
                            + quote(hostHeader(reachable[1])));
                    if ("HlsGroupSettings".equals(kind)) {
                        StringBuilder maps = new StringBuilder();
                        StringBuilder streamMap = new StringBuilder();
                        int videoInGroup = 0;
                        int audioInGroup = 0;
                        int variant = 0;
                        for (JsonNode output : group.path("Outputs")) {
                            String modifier = output.path("OutputSettings").path("HlsOutputSettings")
                                    .path("NameModifier").asText("_" + variant);
                            StringBuilder entry = new StringBuilder();
                            if (output.hasNonNull("VideoDescriptionName")) {
                                JsonNode video = videos.get(output.get("VideoDescriptionName").asText());
                                graph.append(";[s").append(videoIndex).append(']').append(scale(video)).append("[v")
                                        .append(videoIndex).append(']');
                                maps.append(" -map '[v").append(videoIndex).append("]'")
                                        .append(videoCodec(video, ":v:" + videoInGroup));
                                entry.append("v:").append(videoInGroup);
                                videoIndex++;
                                videoInGroup++;
                            }
                            for (JsonNode audioName : output.path("AudioDescriptionNames")) {
                                JsonNode audio = audios.get(audioName.asText());
                                maps.append(" -map 0:a:").append(audioTrack(audio, inputSettings)).append('?')
                                        .append(audioCodec(audio, ":a:" + audioInGroup));
                                entry.append(entry.isEmpty() ? "" : ",").append("a:").append(audioInGroup);
                                audioInGroup++;
                            }
                            if (!entry.isEmpty()) {
                                streamMap.append(streamMap.isEmpty() ? "" : " ").append(entry).append(",name:")
                                        .append(modifier.replaceAll("[^A-Za-z0-9_\\-]", "_"));
                                variant++;
                            }
                        }
                        int segment = specific.path("SegmentLength").asInt(6);
                        int size = specific.path("IndexNSegments").asInt(10);
                        outputs.append(maps).append(" -f hls -hls_time ").append(segment).append(" -hls_list_size ")
                                .append(size).append(" -hls_flags delete_segments+independent_segments")
                                .append(method).append(" -master_pl_name ").append(quote(base + ".m3u8"))
                                .append(" -var_stream_map ").append(quote(streamMap.toString()))
                                .append(" -hls_segment_filename ").append(quote(prefix + base + "%v_%05d.ts"))
                                .append(' ').append(quote(prefix + base + "%v.m3u8"));
                    } else {
                        for (JsonNode output : group.path("Outputs")) {
                            String modifier = output.path("OutputSettings").findPath("NameModifier").asText("");
                            String stem = prefix + base + modifier;
                            if ("FrameCaptureGroupSettings".equals(kind)) {
                                JsonNode video = videos.get(output.path("VideoDescriptionName").asText());
                                JsonNode capture = video == null ? null : video.path("CodecSettings")
                                        .path("FrameCaptureSettings");
                                double interval = capture == null ? 5 : capture.path("CaptureInterval").asDouble(5);
                                if (capture != null && "MILLISECONDS".equals(capture.path("CaptureIntervalUnits")
                                        .asText())) {
                                    interval /= 1000;
                                }
                                String resize = video == null ? "" : scale(video).replaceFirst("^null,?", "");
                                graph.append(";[s").append(videoIndex).append("]fps=1/").append(interval)
                                        .append(resize.isEmpty() ? "" : "," + resize)
                                        .append("[v").append(videoIndex).append(']');
                                outputs.append(" -map '[v").append(videoIndex).append("]' -q:v 3 -f image2")
                                        .append(method).append(' ').append(quote(stem + ".%06d.jpg"));
                                videoIndex++;
                            } else {
                                outputs.append(streams(output, videos, audios, graph, inputSettings, videoIndex));
                                videoIndex += output.hasNonNull("VideoDescriptionName") ? 1 : 0;
                                int rollover = specific.path("RolloverInterval").asInt(300);
                                outputs.append(" -f segment -segment_time ").append(rollover)
                                        .append(" -segment_format mpegts -reset_timestamps 1").append(method)
                                        .append(' ').append(quote(stem + ".%06d.ts"));
                            }
                        }
                    }
                }
                case "RtmpGroupSettings", "UdpGroupSettings", "SrtGroupSettings" -> {
                    for (JsonNode output : group.path("Outputs")) {
                        JsonNode outputSettings = output.path("OutputSettings");
                        String reference = outputSettings.findPath("DestinationRefId").asText();
                        JsonNode destination = destinations.get(reference);
                        String url = url(destinations, reference);
                        if (url == null) {
                            alerts.add("The output " + output.path("OutputName").asText("") + " has no destination "
                                    + "URL.");
                            continue;
                        }
                        String muxer;
                        if ("RtmpGroupSettings".equals(kind)) {
                            String stream = destination.path("Settings").path(0).path("StreamName").asText("");
                            url = stream.isEmpty() ? url : url.replaceAll("/+$", "") + "/" + stream;
                            muxer = "flv";
                        } else if (url.startsWith("rtp://")) {
                            muxer = "rtp_mpegts";
                        } else {
                            muxer = "mpegts";
                            if ("SrtGroupSettings".equals(kind) && !url.startsWith("srt://")) {
                                url = "srt://" + url.replaceFirst("^[a-z]+://", "");
                            }
                        }
                        outputs.append(streams(output, videos, audios, graph, inputSettings, videoIndex))
                                .append(" -f ").append(muxer).append(' ').append(quote(url));
                        videoIndex += output.hasNonNull("VideoDescriptionName") ? 1 : 0;
                    }
                }
                default -> {
                    alerts.add("Floci's encoder doesn't carry " + kind.replace("GroupSettings", "") + " output "
                            + "groups; the group " + group.path("Name").asText("") + " sends nothing.");
                    for (JsonNode output : group.path("Outputs")) {
                        if (output.hasNonNull("VideoDescriptionName")) {
                            graph.append(";[s").append(videoIndex).append("]nullsink");
                            videoIndex++;
                        }
                    }
                }
            }
            groupIndex++;
        }
        for (; videoIndex < videoCount; videoIndex++) {
            graph.append(";[s").append(videoIndex).append("]nullsink");
        }
        args.append(" -filter_complex ").append(quote(graph.toString())).append(outputs)
                .append(" -map '[thumb]' -q:v 5 -update 1 -f image2 /out/.thumb.jpg");
        return new Plan(mkdirs + " && exec " + args + " 2>/out/.ffmpeg.log", rtmpPort, udpPort, uploads, alerts);
    }

    private static String url(Map<String, JsonNode> destinations, String reference) {
        JsonNode destination = destinations.get(reference);
        if (destination == null) {
            return null;
        }
        String url = destination.path("Settings").path(0).path("Url").asText("");
        return url.isEmpty() ? null : url;
    }

    /** An output's video and audio streams with their codecs, mapped for one muxer. */
    private static String streams(JsonNode output, Map<String, JsonNode> videos, Map<String, JsonNode> audios,
                                  StringBuilder graph, JsonNode inputSettings, int videoIndex) {
        StringBuilder args = new StringBuilder();
        if (output.hasNonNull("VideoDescriptionName")) {
            JsonNode video = videos.get(output.get("VideoDescriptionName").asText());
            graph.append(";[s").append(videoIndex).append(']').append(scale(video)).append("[v").append(videoIndex)
                    .append(']');
            args.append(" -map '[v").append(videoIndex).append("]'").append(videoCodec(video, ":v:0"));
        }
        int audioIndex = 0;
        for (JsonNode audioName : output.path("AudioDescriptionNames")) {
            JsonNode audio = audios.get(audioName.asText());
            args.append(" -map 0:a:").append(audioTrack(audio, inputSettings)).append('?')
                    .append(audioCodec(audio, ":a:" + audioIndex++));
        }
        return args.toString();
    }

    private static String scale(JsonNode video) {
        int width = video.path("Width").asInt(0);
        int height = video.path("Height").asInt(0);
        StringBuilder filter = new StringBuilder("null");
        if (width > 0 || height > 0) {
            String w = width > 0 ? String.valueOf(width) : "-2";
            String h = height > 0 ? String.valueOf(height) : "-2";
            if ("STRETCH_TO_OUTPUT".equals(video.path("ScalingBehavior").asText()) || width == 0 || height == 0) {
                filter.append(",scale=").append(w).append(':').append(h);
            } else {
                filter.append(",scale=").append(w).append(':').append(h).append(":force_original_aspect_ratio=decrease,"
                        + "pad=").append(w).append(':').append(h).append(":(ow-iw)/2:(oh-ih)/2");
            }
            filter.append(",setsar=1");
        }
        JsonNode codec = codecSettings(video.path("CodecSettings"));
        if ("SPECIFIED".equals(codec.path("FramerateControl").asText()) && codec.has("FramerateNumerator")) {
            filter.append(",fps=").append(codec.path("FramerateNumerator").asInt()).append('/')
                    .append(codec.path("FramerateDenominator").asInt(1));
        }
        return filter.toString();
    }

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
        JsonNode settings = codecSettings(codecSettings);
        StringBuilder args = new StringBuilder();
        if (codecSettings.has("H265Settings")) {
            args.append(" -c").append(spec).append(" libx265 -tag").append(spec).append(" hvc1");
        } else if (codecSettings.has("Mpeg2Settings")) {
            args.append(" -c").append(spec).append(" mpeg2video");
        } else if (codecSettings.has("Av1Settings")) {
            args.append(" -c").append(spec).append(" libsvtav1 -preset").append(spec).append(" 12");
        } else {
            args.append(" -c").append(spec).append(" libx264 -preset").append(spec).append(" veryfast -tune")
                    .append(spec).append(" zerolatency -pix_fmt").append(spec).append(" yuv420p");
            String profile = settings.path("Profile").asText("MAIN");
            args.append(" -profile").append(spec).append(' ').append(switch (profile) {
                case "BASELINE" -> "baseline";
                case "HIGH", "HIGH_10BIT", "HIGH_422", "HIGH_422_10BIT" -> "high";
                default -> "main";
            });
        }
        String mode = settings.path("RateControlMode").asText("CBR");
        long bitrate = settings.path("Bitrate").asLong(0);
        long max = settings.path("MaxBitrate").asLong(0);
        if ("QVBR".equals(mode) && !codecSettings.has("Mpeg2Settings")) {
            args.append(" -crf").append(spec).append(' ').append(Math.max(0, 51 - settings.path("QvbrQualityLevel")
                    .asInt(7) * 4));
            if (max > 0) {
                args.append(" -maxrate").append(spec).append(' ').append(max).append(" -bufsize").append(spec)
                        .append(' ').append(max * 2);
            }
        } else if (bitrate > 0) {
            args.append(" -b").append(spec).append(' ').append(bitrate);
            if ("CBR".equals(mode)) {
                args.append(" -maxrate").append(spec).append(' ').append(bitrate).append(" -bufsize").append(spec)
                        .append(' ').append(bitrate * 2);
            }
        }
        if (settings.has("GopSize")) {
            double gop = settings.get("GopSize").asDouble();
            if ("FRAMES".equals(settings.path("GopSizeUnits").asText())) {
                args.append(" -g").append(spec).append(' ').append(Math.round(gop));
            } else {
                args.append(" -force_key_frames").append(spec).append(" 'expr:gte(t,n_forced*").append(gop)
                        .append(")'");
            }
        }
        return args.toString();
    }

    private static int audioTrack(JsonNode audio, JsonNode inputSettings) {
        if (audio == null) {
            return 0;
        }
        String selector = audio.path("AudioSelectorName").asText();
        for (JsonNode candidate : inputSettings.path("AudioSelectors")) {
            if (selector.equals(candidate.path("Name").asText())) {
                JsonNode track = candidate.path("SelectorSettings").path("AudioTrackSelection").path("Tracks")
                        .path(0).path("Track");
                if (track.isNumber()) {
                    return Math.max(0, track.asInt() - 1);
                }
            }
        }
        return 0;
    }

    private static String audioCodec(JsonNode audio, String spec) {
        if (audio == null) {
            return " -c" + spec + " aac";
        }
        JsonNode codecSettings = audio.path("CodecSettings");
        JsonNode settings = codecSettings(codecSettings);
        String codec;
        if (codecSettings.has("Ac3Settings")) {
            codec = "ac3";
        } else if (codecSettings.has("Eac3Settings") || codecSettings.has("Eac3AtmosSettings")) {
            codec = "eac3";
        } else if (codecSettings.has("Mp2Settings")) {
            codec = "mp2";
        } else if (codecSettings.has("PassThroughSettings")) {
            return " -c" + spec + " copy";
        } else if (codecSettings.has("WavSettings")) {
            codec = "pcm_s16le";
        } else {
            codec = "aac";
        }
        StringBuilder args = new StringBuilder(" -c").append(spec).append(' ').append(codec);
        if (settings.has("Bitrate") && !"pcm_s16le".equals(codec)) {
            args.append(" -b").append(spec).append(' ').append(Math.round(settings.get("Bitrate").asDouble()));
        }
        if (settings.has("SampleRate")) {
            args.append(" -ar").append(spec).append(' ').append(Math.round(settings.get("SampleRate").asDouble()));
        }
        String mode = settings.path("CodingMode").asText("");
        if (mode.contains("1_0")) {
            args.append(" -ac").append(spec).append(" 1");
        } else if (mode.contains("2_0") || mode.contains("1_1")) {
            args.append(" -ac").append(spec).append(" 2");
        } else if (mode.contains("5_1") || mode.contains("3_2")) {
            args.append(" -ac").append(spec).append(" 6");
        }
        return args.toString();
    }

    // ================================================================ copying outputs to S3

    private void poll() {
        for (Map.Entry<String, Running> entry : running.entrySet()) {
            Running encoder = entry.getValue();
            try {
                RequestScopes.runAs(encoder.account, encoder.region, () -> {
                    if (!checkAlive(entry.getKey(), encoder)) {
                        return;
                    }
                    upload(encoder, false);
                    String log = ffmpegLog(entry.getKey());
                    if (!log.isEmpty() && !log.equals(encoder.lastLog)) {
                        encoder.lastLog = log;
                        LOG.debugv("MediaLive channel {0} encoder log: {1}", encoder.channelId, log);
                    }
                });
            } catch (RuntimeException e) {
                LOG.debugv("MediaLive could not copy the outputs of channel {0}: {1}", encoder.channelId,
                        e.getMessage());
            }
        }
    }

    /** Whether the encoder runs; when it stopped, alerts once with what ffmpeg logged. */
    private boolean checkAlive(String arn, Running encoder) {
        try {
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient()
                    .inspectContainerCmd(encoder.containerId).exec().getState();
            if (state != null && !Boolean.TRUE.equals(state.getRunning()) && running.get(arn) == encoder
                    && !encoder.failed) {
                encoder.failed = true;
                String log = logTail(encoder.containerId);
                LOG.infov("The encoder of MediaLive channel {0} stopped (exit code {1}): {2}", encoder.channelId,
                        state.getExitCodeLong(), log);
                channels.get().alert(encoder.account, encoder.region, encoder.channelId, "Pipeline Failure",
                        "The encoder stopped (exit code " + state.getExitCodeLong() + "): " + (log.isBlank()
                                ? "the input ended or couldn't be read." : log), true);
            }
            return state == null || Boolean.TRUE.equals(state.getRunning());
        } catch (RuntimeException e) {
            LOG.debugv("MediaLive could not inspect the encoder of channel {0}: {1}", encoder.channelId,
                    e.getMessage());
            return false;
        }
    }

    /** Copies new and changed output files to S3; archives and frame captures are removed once copied. */
    private void upload(Running encoder, boolean all) {
        if (encoder.uploads.isEmpty()) {
            return;
        }
        String listing = exec(encoder.containerId, "cd /out && find . -type f ! -name '.*' "
                + "-exec stat -c '%Y %s %n' {} +");
        LOG.tracev("MediaLive channel {0} outputs: {1}", encoder.channelId, listing);
        long now = System.currentTimeMillis() / 1000;
        List<String> files = new ArrayList<>();
        for (String line : listing.split("\n")) {
            String[] parts = line.trim().split(" ", 3);
            if (parts.length < 3 || !parts[2].startsWith("./g")) {
                continue;
            }
            String path = parts[2].substring(2);
            String signature = parts[0] + ":" + parts[1];
            boolean settled = all || path.endsWith(".m3u8") || now - Long.parseLong(parts[0]) >= 1;
            if (settled && !signature.equals(encoder.seen.get(path))) {
                encoder.seen.put(path, signature);
                files.add(path);
            }
        }
        files.sort((a, b) -> Boolean.compare(a.endsWith(".m3u8"), b.endsWith(".m3u8")));
        List<String> copied = new ArrayList<>();
        for (String path : files) {
            String group = path.substring(0, path.indexOf('/'));
            String relative = path.substring(path.indexOf('/') + 1);
            for (String[] upload : encoder.uploads) {
                if (!upload[0].equals(group)) {
                    continue;
                }
                byte[] data = copy(encoder.containerId, "/out/" + path);
                if (data == null) {
                    continue;
                }
                String location = upload[1];
                String bucket = location.substring(0, location.indexOf('/'));
                String key = location.substring(location.indexOf('/') + 1) + relative;
                s3.get().putObject(bucket, key, data, contentType(key), Map.of());
                if (key.endsWith(".jpg") || (key.endsWith(".ts") && !all && isArchive(relative))) {
                    copied.add("/out/" + path);
                }
            }
        }
        if (!copied.isEmpty()) {
            StringBuilder remove = new StringBuilder("rm -f");
            copied.forEach(path -> remove.append(' ').append(quote(path)));
            exec(encoder.containerId, remove.toString());
            copied.forEach(path -> encoder.seen.remove(path.substring(5)));
        }
    }

    private static boolean isArchive(String relative) {
        return relative.matches(".*\\.\\d{6}\\.ts$");
    }

    private static String contentType(String key) {
        if (key.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (key.endsWith(".ts")) {
            return "video/MP2T";
        }
        if (key.endsWith(".jpg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private String exec(String containerId, String command) {
        ExecCreateCmdResponse created = lifecycle.getDockerClient().execCreateCmd(containerId)
                .withCmd("sh", "-c", command).withAttachStdout(true).withAttachStderr(true).exec();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            lifecycle.getDockerClient().execStartCmd(created.getId()).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    output.writeBytes(frame.getPayload());
                }
            }).awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** What ffmpeg logged, its last lines; read from the container while it runs, or its logs after. */
    String ffmpegLog(String channelArn) {
        Running encoder = running.get(channelArn);
        if (encoder == null) {
            return "";
        }
        byte[] log = copy(encoder.containerId, "/out/.ffmpeg.log");
        String text = log == null ? "" : new String(log, StandardCharsets.UTF_8).strip();
        return text.length() > 1000 ? text.substring(text.length() - 1000) : text;
    }

    /** The last lines ffmpeg logged. */
    private String logTail(String containerId) {
        byte[] log = copy(containerId, "/out/.ffmpeg.log");
        if (log != null && log.length > 0) {
            String text = new String(log, StandardCharsets.UTF_8).strip();
            return text.length() > 1000 ? text.substring(text.length() - 1000) : text;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            lifecycle.getDockerClient().logContainerCmd(containerId).withStdOut(true).withStdErr(true).withTail(10)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.writeBytes(frame.getPayload());
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            return "";
        }
        String text = output.toString(StandardCharsets.UTF_8).strip();
        return text.length() > 1000 ? text.substring(text.length() - 1000) : text;
    }

    private byte[] copy(String containerId, String path) {
        try (InputStream archive = lifecycle.getDockerClient().copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry = tar.getNextEntry();
            return entry == null ? null : tar.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
