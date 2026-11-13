package io.floci.aws.services.mediaconvert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The system presets and job templates every Region has: common file, HLS and audio outputs, named as MediaConvert
 * names its own ({@code System-...}), read-only.
 */
final class MediaConvertSystem {

    private MediaConvertSystem() {
    }

    private static String video(int width, int height, int bitrate) {
        return "\"VideoDescription\":{\"Width\":" + width + ",\"Height\":" + height + ",\"CodecSettings\":{\"Codec\":"
                + "\"H_264\",\"H264Settings\":{\"RateControlMode\":\"QVBR\",\"MaxBitrate\":" + bitrate
                + ",\"CodecProfile\":\"HIGH\"}}}";
    }

    private static final String AAC = "\"AudioDescriptions\":[{\"CodecSettings\":{\"Codec\":\"AAC\",\"AacSettings\":"
            + "{\"Bitrate\":96000,\"CodingMode\":\"CODING_MODE_2_0\",\"SampleRate\":48000}}}]";

    /** System presets by name: their category, description and settings. */
    static final Map<String, String[]> PRESETS = new LinkedHashMap<>();
    /** System job templates by name: their category, description and settings. */
    static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();

    static {
        PRESETS.put("System-Generic_Hd_Mp4_Avc_Aac_16x9_1920x1080p_24Hz_6Mbps", new String[] {"GENERIC",
            "Wifi, 1920x1080, 16:9, 24fps, 6000kbps", "{\"ContainerSettings\":{\"Container\":\"MP4\","
            + "\"Mp4Settings\":{}}," + video(1920, 1080, 6000000) + "," + AAC + "}"});
        PRESETS.put("System-Generic_Hd_Mp4_Avc_Aac_16x9_1280x720p_24Hz_4.5Mbps", new String[] {"GENERIC",
            "Wifi, 1280x720, 16:9, 24fps, 4500kbps", "{\"ContainerSettings\":{\"Container\":\"MP4\","
            + "\"Mp4Settings\":{}}," + video(1280, 720, 4500000) + "," + AAC + "}"});
        PRESETS.put("System-Avc_16x9_720p_29_97fps_3500kbps", new String[] {"HLS",
            "AVC 16x9 720p, 29.97fps, 3500kbps, for HLS", "{\"ContainerSettings\":{\"Container\":\"M3U8\","
            + "\"M3u8Settings\":{}}," + video(1280, 720, 3500000) + "}"});
        PRESETS.put("System-Ott_Hls_Ts_Avc_Aac_16x9_640x360p_30Hz_1.2Mbps", new String[] {"OTT-HLS",
            "Wifi, 640x360, 16:9, 30fps, 1200kbps", "{\"ContainerSettings\":{\"Container\":\"M3U8\","
            + "\"M3u8Settings\":{}}," + video(640, 360, 1200000) + "," + AAC + "}"});
        PRESETS.put("System-Generic_Audio_Mp3_Stereo_128kbps", new String[] {"GENERIC",
            "MP3 stereo audio, 128kbps", "{\"ContainerSettings\":{\"Container\":\"RAW\"},\"AudioDescriptions\":"
            + "[{\"CodecSettings\":{\"Codec\":\"MP3\",\"Mp3Settings\":{\"Bitrate\":128000,\"Channels\":2,"
            + "\"SampleRate\":48000,\"RateControlMode\":\"CBR\"}}}]}"});
        TEMPLATES.put("System-Generic_Mp4_Hev1_Avc_Aac_Sdr_Qvbr", new String[] {"GENERIC",
            "An MP4 file group with an H.264 rendition", "{\"OutputGroups\":[{\"Name\":\"File Group\","
            + "\"OutputGroupSettings\":{\"Type\":\"FILE_GROUP_SETTINGS\",\"FileGroupSettings\":{}},\"Outputs\":"
            + "[{\"Preset\":\"System-Generic_Hd_Mp4_Avc_Aac_16x9_1280x720p_24Hz_4.5Mbps\",\"NameModifier\":"
            + "\"_720p\"}]}],\"Inputs\":[{\"AudioSelectors\":{\"Audio Selector 1\":{\"DefaultSelection\":"
            + "\"DEFAULT\"}},\"VideoSelector\":{},\"TimecodeSource\":\"ZEROBASED\"}]}"});
        TEMPLATES.put("System-Ott_Hls_Ts_Avc_Aac", new String[] {"OTT-HLS",
            "An HLS group with a 360p rendition", "{\"OutputGroups\":[{\"Name\":\"Apple HLS\","
            + "\"OutputGroupSettings\":{\"Type\":\"HLS_GROUP_SETTINGS\",\"HlsGroupSettings\":{\"SegmentLength\":"
            + "6,\"MinSegmentLength\":0}},\"Outputs\":[{\"Preset\":"
            + "\"System-Ott_Hls_Ts_Avc_Aac_16x9_640x360p_30Hz_1.2Mbps\",\"NameModifier\":\"_360\"}]}],"
            + "\"Inputs\":[{\"AudioSelectors\":{\"Audio Selector 1\":{\"DefaultSelection\":\"DEFAULT\"}},"
            + "\"VideoSelector\":{},\"TimecodeSource\":\"ZEROBASED\"}]}"});
    }

    static ObjectNode preset(ObjectMapper mapper, String arn, String name) {
        String[] preset = PRESETS.get(name);
        if (preset == null) {
            return null;
        }
        ObjectNode record = mapper.createObjectNode();
        record.put("Name", name);
        record.put("Arn", arn);
        record.put("Type", "SYSTEM");
        record.put("Category", preset[0]);
        record.put("Description", preset[1]);
        record.set("Settings", parse(mapper, preset[2]));
        record.put("CreatedAt", 1514764800.0);
        record.put("LastUpdated", 1514764800.0);
        return record;
    }

    static ObjectNode template(ObjectMapper mapper, String arn, String name, String queueArn) {
        String[] template = TEMPLATES.get(name);
        if (template == null) {
            return null;
        }
        ObjectNode record = mapper.createObjectNode();
        record.put("Name", name);
        record.put("Arn", arn);
        record.put("Type", "SYSTEM");
        record.put("Category", template[0]);
        record.put("Description", template[1]);
        record.put("Priority", 0);
        record.put("Queue", queueArn);
        record.put("StatusUpdateInterval", "SECONDS_60");
        record.set("Settings", parse(mapper, template[2]));
        record.put("CreatedAt", 1514764800.0);
        record.put("LastUpdated", 1514764800.0);
        return record;
    }

    private static ObjectNode parse(ObjectMapper mapper, String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A system preset's settings don't parse: " + e.getMessage(), e);
        }
    }
}
