package io.floci.aws.services.mediapackagev2;

import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.floci.aws.services.medialive.MediaLiveIntegrationTest;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A live workflow end to end: a MediaLive channel pushes HLS to a MediaPackage v2 channel's ingest endpoint, and its
 * TS and CMAF origin endpoints serve it; ffprobe reads what they serve.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MediaPackageV2DockerIntegrationTest {

    @Inject
    KvsTranscoder ffmpeg;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String s3() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/aws4_request";
    }

    private String get(String manifestUrl, String file) {
        String base = MediaPackageV2IntegrationTest.path(manifestUrl);
        base = base.substring(0, base.lastIndexOf('/') + 1);
        return MediaPackageV2IntegrationTest.at(manifestUrl).get(base + file).then().statusCode(200).extract()
                .asString();
    }

    private byte[] bytes(String manifestUrl, String file) {
        String base = MediaPackageV2IntegrationTest.path(manifestUrl);
        base = base.substring(0, base.lastIndexOf('/') + 1);
        return MediaPackageV2IntegrationTest.at(manifestUrl).get(base + file).then().statusCode(200).extract()
                .asByteArray();
    }

    private static String last(String playlist, String suffix) {
        String found = null;
        for (String line : playlist.split("\n")) {
            if (line.endsWith(suffix) && !line.startsWith("#")) {
                found = line.trim();
            }
        }
        return found;
    }

    @Test
    void mediaLivePushesToMediaPackageWhichServesTsAndCmaf() {
        MediaPackageV2IntegrationTest.mp().body("{\"ChannelGroupName\":\"e2e\"}").post("/channelGroup").then()
                .statusCode(200);
        String ingest = MediaPackageV2IntegrationTest.mp().body("{\"ChannelName\":\"live\"}")
                .post("/channelGroup/e2e/channel").then().statusCode(200).extract().path("IngestEndpoints[0].Url");
        String ts = MediaPackageV2IntegrationTest.mp().body("{\"OriginEndpointName\":\"ts\",\"ContainerType\":\"TS\","
                        + "\"HlsManifests\":[{\"ManifestName\":\"index\"}]}")
                .post("/channelGroup/e2e/channel/live/originEndpoint").then().statusCode(200).extract()
                .path("HlsManifests[0].Url");
        Map<String, Object> cmaf = MediaPackageV2IntegrationTest.mp().body("{\"OriginEndpointName\":\"cmaf\","
                        + "\"ContainerType\":\"CMAF\",\"HlsManifests\":[{\"ManifestName\":\"index\"}],"
                        + "\"DashManifests\":[{\"ManifestName\":\"dash\"}]}")
                .post("/channelGroup/e2e/channel/live/originEndpoint").then().statusCode(200).extract().path("$");
        String cmafHls = (String) ((Map<?, ?>) ((List<?>) cmaf.get("HlsManifests")).get(0)).get("Url");
        String dash = (String) ((Map<?, ?>) ((List<?>) cmaf.get("DashManifests")).get(0)).get("Url");

        byte[] clip = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 4 "
                + "-c:v libx264 -g 25 -pix_fmt yuv420p -c:a aac -movflags +faststart out.mp4").get("out.mp4");
        given().header("Authorization", s3()).put("/mp-e2e").then().statusCode(200);
        given().header("Authorization", s3()).contentType("video/mp4").body(clip).put("/mp-e2e/clip.mp4").then()
                .statusCode(200);
        String input = MediaLiveIntegrationTest.pullInput("e2e", "s3://mp-e2e/clip.mp4");
        String encoder = "{\"audioDescriptions\":[{\"audioSelectorName\":\"default\",\"name\":\"a\","
                + "\"codecSettings\":{\"aacSettings\":{\"bitrate\":64000,\"sampleRate\":48000}}}],"
                + "\"videoDescriptions\":[{\"name\":\"v\",\"width\":640,\"height\":360,\"codecSettings\":"
                + "{\"h264Settings\":{\"bitrate\":600000,\"gopSize\":1,\"gopSizeUnits\":\"SECONDS\"}}}],"
                + "\"outputGroups\":[{\"name\":\"hls\",\"outputGroupSettings\":{\"hlsGroupSettings\":{\"destination\":"
                + "{\"destinationRefId\":\"mp\"},\"segmentLength\":1}},\"outputs\":[{\"outputName\":\"o\","
                + "\"videoDescriptionName\":\"v\",\"audioDescriptionNames\":[\"a\"],\"outputSettings\":"
                + "{\"hlsOutputSettings\":{\"nameModifier\":\"_360\",\"hlsSettings\":{\"standardHlsSettings\":"
                + "{\"m3u8Settings\":{}}}}}}]}],\"timecodeConfig\":{\"source\":\"SYSTEMCLOCK\"}}";
        String channel = MediaLiveIntegrationTest.ml().body("{\"name\":\"e2e\",\"channelClass\":\"SINGLE_PIPELINE\","
                        + "\"inputAttachments\":[{\"inputAttachmentName\":\"clip\",\"inputId\":\"" + input
                        + "\",\"inputSettings\":{\"sourceEndBehavior\":\"LOOP\"}}],\"destinations\":[{\"id\":\"mp\","
                        + "\"settings\":[{\"url\":\"" + ingest + "index\"}]}],\"encoderSettings\":" + encoder + "}")
                .post("/prod/channels").then().statusCode(201).extract().path("channel.id");
        MediaLiveIntegrationTest.awaitState(channel, "IDLE");
        MediaLiveIntegrationTest.ml().post("/prod/channels/" + channel + "/start").then().statusCode(200);
        MediaLiveIntegrationTest.awaitState(channel, "RUNNING");
        try {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
                Response response = MediaPackageV2IntegrationTest.at(ts)
                        .get(MediaPackageV2IntegrationTest.path(ts));
                assertEquals(200, response.statusCode(), response.asString());
            });
            String multivariant = get(ts, "index.m3u8");
            assertTrue(multivariant.contains("RESOLUTION=640x360"), multivariant);
            String child = get(ts, last(multivariant, ".m3u8"));
            String segment = last(child, ".ts");
            Map<String, byte[]> probed = ffmpeg.run(new LinkedHashMap<>(Map.of("seg.ts", bytes(ts, segment))),
                    "ffprobe -v error -show_entries stream=codec_name,width -of csv=p=0 seg.ts > probe.txt");
            String streams = new String(probed.get("probe.txt"));
            assertTrue(streams.contains("h264,640"), streams);
            assertTrue(streams.contains("aac"), streams);

            String cmafChild = get(cmafHls, last(get(cmafHls, "index.m3u8"), ".m3u8"));
            assertTrue(cmafChild.contains("#EXT-X-MAP:URI="), cmafChild);
            String init = cmafChild.replaceAll("(?s).*#EXT-X-MAP:URI=\"([^\"]+)\".*", "$1");
            String fragment = last(cmafChild, ".mp4");
            Map<String, byte[]> joined = ffmpeg.run(new LinkedHashMap<>(Map.of("init.mp4", bytes(cmafHls, init),
                    "frag.mp4", bytes(cmafHls, fragment))), "cat init.mp4 frag.mp4 > whole.mp4 && ffprobe -v error "
                    + "-show_entries stream=codec_name -of csv=p=0 whole.mp4 > probe.txt");
            String cmafStreams = new String(joined.get("probe.txt"));
            assertTrue(cmafStreams.contains("h264") && cmafStreams.contains("aac"), cmafStreams);
            String mpd = MediaPackageV2IntegrationTest.at(dash).get(MediaPackageV2IntegrationTest.path(dash)).then()
                    .statusCode(200).extract().asString();
            assertTrue(mpd.contains("<SegmentTimeline>") && mpd.contains("segment_1_init.mp4"), mpd);
        } finally {
            MediaLiveIntegrationTest.ml().post("/prod/channels/" + channel + "/stop").then().statusCode(200);
            MediaLiveIntegrationTest.awaitState(channel, "IDLE");
            MediaLiveIntegrationTest.ml().delete("/prod/channels/" + channel).then().statusCode(200);
        }
        assertEquals(200, MediaPackageV2IntegrationTest.mp().post("/channelGroup/e2e/channel/live/reset")
                .statusCode());
    }
}
