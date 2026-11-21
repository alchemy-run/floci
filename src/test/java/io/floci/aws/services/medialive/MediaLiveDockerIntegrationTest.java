package io.floci.aws.services.medialive;

import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A running channel's encoder: an MP4 in S3, looped, encoded into an HLS group with a main playlist, frame captures
 * and a rolling archive in S3, with thumbnails while it runs.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MediaLiveDockerIntegrationTest {

    @Inject
    KvsTranscoder ffmpeg;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String s3() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/aws4_request";
    }

    static String listing(String prefix) {
        return given().header("Authorization", s3()).get("/medialive-media?list-type=2&prefix=" + prefix).then()
                .statusCode(200).extract().asString();
    }

    @Test
    void aRunningChannelEncodesItsInputIntoS3() {
        byte[] clip = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 4 "
                + "-c:v libx264 -g 25 -pix_fmt yuv420p -c:a aac -movflags +faststart out.mp4").get("out.mp4");
        given().header("Authorization", s3()).put("/medialive-media").then().statusCode(200);
        given().header("Authorization", s3()).contentType("video/mp4").body(clip).put("/medialive-media/in/clip.mp4")
                .then().statusCode(200);
        String input = MediaLiveIntegrationTest.pullInput("clip", "s3://medialive-media/in/clip.mp4");
        String encoder = "{\"audioDescriptions\":[{\"audioSelectorName\":\"default\",\"name\":\"audio1\","
                + "\"codecSettings\":{\"aacSettings\":{\"bitrate\":64000,\"sampleRate\":48000}}}],"
                + "\"videoDescriptions\":[{\"name\":\"v360\",\"width\":640,\"height\":360,\"codecSettings\":"
                + "{\"h264Settings\":{\"bitrate\":600000,\"rateControlMode\":\"CBR\",\"gopSize\":1,"
                + "\"gopSizeUnits\":\"SECONDS\"}}},{\"name\":\"v180\",\"width\":320,\"height\":180,"
                + "\"codecSettings\":{\"h264Settings\":{\"bitrate\":300000}}},{\"name\":\"capture\",\"width\":320,"
                + "\"height\":180,\"codecSettings\":{\"frameCaptureSettings\":{\"captureInterval\":1,"
                + "\"captureIntervalUnits\":\"SECONDS\"}}}],\"outputGroups\":[{\"name\":\"hls\","
                + "\"outputGroupSettings\":{\"hlsGroupSettings\":{\"destination\":{\"destinationRefId\":\"hls\"},"
                + "\"segmentLength\":1}},\"outputs\":[{\"outputName\":\"high\",\"videoDescriptionName\":\"v360\","
                + "\"audioDescriptionNames\":[\"audio1\"],\"outputSettings\":{\"hlsOutputSettings\":{"
                + "\"nameModifier\":\"_360\",\"hlsSettings\":{\"standardHlsSettings\":{\"m3u8Settings\":{}}}}}},"
                + "{\"outputName\":\"low\",\"videoDescriptionName\":\"v180\",\"audioDescriptionNames\":[\"audio1\"],"
                + "\"outputSettings\":{\"hlsOutputSettings\":{\"nameModifier\":\"_180\",\"hlsSettings\":"
                + "{\"standardHlsSettings\":{\"m3u8Settings\":{}}}}}}]},{\"name\":\"frames\",\"outputGroupSettings\":"
                + "{\"frameCaptureGroupSettings\":{\"destination\":{\"destinationRefId\":\"frames\"}}},\"outputs\":"
                + "[{\"outputName\":\"capture\",\"videoDescriptionName\":\"capture\",\"outputSettings\":"
                + "{\"frameCaptureOutputSettings\":{\"nameModifier\":\"_frame\"}}}]},{\"name\":\"archive\","
                + "\"outputGroupSettings\":{\"archiveGroupSettings\":{\"destination\":{\"destinationRefId\":"
                + "\"archive\"},\"rolloverInterval\":2}},\"outputs\":[{\"outputName\":\"ts\",\"videoDescriptionName\":"
                + "\"v180\",\"audioDescriptionNames\":[\"audio1\"],\"outputSettings\":{\"archiveOutputSettings\":"
                + "{\"nameModifier\":\"_archive\",\"containerSettings\":{\"m2tsSettings\":{}}}}}]}],"
                + "\"timecodeConfig\":{\"source\":\"SYSTEMCLOCK\"}}";
        String channel = MediaLiveIntegrationTest.ml().body("{\"name\":\"encode\",\"channelClass\":"
                        + "\"SINGLE_PIPELINE\",\"inputAttachments\":[{\"inputAttachmentName\":\"clip\",\"inputId\":\""
                        + input + "\",\"inputSettings\":{\"sourceEndBehavior\":\"LOOP\"}}],\"destinations\":[{\"id\":"
                        + "\"hls\",\"settings\":[{\"url\":\"s3://medialive-media/live/index\"}]},{\"id\":\"frames\","
                        + "\"settings\":[{\"url\":\"s3://medialive-media/frames/shot\"}]},{\"id\":\"archive\","
                        + "\"settings\":[{\"url\":\"s3://medialive-media/archive/rec\"}]}],\"encoderSettings\":"
                        + encoder + "}").post("/prod/channels").then().statusCode(201).extract().path("channel.id");
        MediaLiveIntegrationTest.awaitState(channel, "IDLE");
        MediaLiveIntegrationTest.ml().post("/prod/channels/" + channel + "/start").then().statusCode(200)
                .body("state", equalTo("STARTING"));
        MediaLiveIntegrationTest.awaitState(channel, "RUNNING");
        MediaLiveIntegrationTest.channel(channel).body("pipelinesRunningCount", equalTo(1))
                .body("pipelineDetails[0].activeInputAttachmentName", equalTo("clip"));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            String live = listing("live/");
            assertTrue(live.contains("<Key>live/index.m3u8</Key>"), live);
            assertTrue(live.contains("<Key>live/index_360.m3u8</Key>"), live);
            assertTrue(live.contains("<Key>live/index_180_"), live);
            assertTrue(listing("frames/").contains("<Key>frames/shot_frame."));
            assertTrue(listing("archive/").contains("<Key>archive/rec_archive."));
        });
        given().header("Authorization", s3()).get("/medialive-media/live/index.m3u8").then().statusCode(200)
                .body(containsString("index_360.m3u8")).body(containsString("index_180.m3u8"))
                .body(containsString("RESOLUTION=640x360"));
        MediaLiveIntegrationTest.ml().get("/prod/channels/" + channel + "/thumbnails?pipelineId=0&thumbnailType="
                        + "CURRENT_ACTIVE").then().statusCode(200).body("thumbnailDetails", hasSize(1))
                .body("thumbnailDetails[0].thumbnails[0].contentType", equalTo("image/jpeg"))
                .body("thumbnailDetails[0].thumbnails[0].body.length()", greaterThan(1000));
        MediaLiveIntegrationTest.ml().post("/prod/channels/" + channel + "/stop").then().statusCode(200);
        MediaLiveIntegrationTest.awaitState(channel, "IDLE");
        MediaLiveIntegrationTest.ml().delete("/prod/channels/" + channel).then().statusCode(200);
    }
}
