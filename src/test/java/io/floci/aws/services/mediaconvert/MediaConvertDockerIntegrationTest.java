package io.floci.aws.services.mediaconvert;

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
import static org.hamcrest.Matchers.hasItem;

/**
 * MediaConvert jobs transcoding with ffmpeg: an MP4 read from S3 into an MP4 at another size, an HLS group with a
 * main playlist, and frame captures; and Probe reading the input's container and tracks.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MediaConvertDockerIntegrationTest {

    @Inject
    KvsTranscoder ffmpeg;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String s3() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/aws4_request";
    }

    @Test
    void aJobTranscodesItsInputIntoEveryOutputGroup() {
        byte[] input = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 3 "
                + "-c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart out.mp4").get("out.mp4");
        given().header("Authorization", s3()).put("/mediaconvert-media").then().statusCode(200);
        given().header("Authorization", s3()).contentType("video/mp4").body(input)
                .put("/mediaconvert-media/in/clip.mp4").then().statusCode(200);
        MediaConvertIntegrationTest.role("mediaconvert");

        MediaConvertIntegrationTest.mc().body("{\"inputFiles\":[{\"fileUrl\":\"s3://mediaconvert-media/in/clip.mp4\"}]}")
                .post(MediaConvertIntegrationTest.PREFIX + "/probe").then().statusCode(200)
                .body("probeResults[0].container.format", equalTo("mp4"))
                .body("probeResults[0].container.tracks.codec", hasItem("H_264"))
                .body("probeResults[0].container.tracks.find { it.trackType == 'video' }.videoProperties.width",
                        equalTo(640))
                .body("probeResults[0].container.tracks.find { it.trackType == 'audio' }.audioProperties"
                        + ".sampleRate", equalTo(48000))
                .body("probeResults[0].metadata.fileSize", equalTo(input.length));

        String id = MediaConvertIntegrationTest.mc().body("{\"role\":\"arn:aws:iam::000000000000:role/mediaconvert\","
                        + "\"settings\":{\"inputs\":[{\"fileInput\":\"s3://mediaconvert-media/in/clip.mp4\"}],"
                        + "\"outputGroups\":[{\"name\":\"File Group\",\"outputGroupSettings\":{\"type\":"
                        + "\"FILE_GROUP_SETTINGS\",\"fileGroupSettings\":{\"destination\":"
                        + "\"s3://mediaconvert-media/out/\"}},\"outputs\":[" + MediaConvertIntegrationTest.PRESET
                        .replaceFirst("\\{", "{\"nameModifier\":\"_small\",") + ",{\"nameModifier\":\"_thumb\","
                        + "\"containerSettings\":{\"container\":\"RAW\"},\"videoDescription\":{\"width\":160,"
                        + "\"height\":90,\"codecSettings\":{\"codec\":\"FRAME_CAPTURE\",\"frameCaptureSettings\":"
                        + "{\"framerateNumerator\":1,\"framerateDenominator\":1,\"maxCaptures\":2,\"quality\":80}}}}]},"
                        + "{\"name\":\"Apple HLS\",\"outputGroupSettings\":{\"type\":\"HLS_GROUP_SETTINGS\","
                        + "\"hlsGroupSettings\":{\"destination\":\"s3://mediaconvert-media/hls/stream\","
                        + "\"segmentLength\":1}},\"outputs\":[{\"nameModifier\":\"_360\",\"containerSettings\":"
                        + "{\"container\":\"M3U8\"},\"videoDescription\":{\"width\":640,\"height\":360,"
                        + "\"codecSettings\":{\"codec\":\"H_264\",\"h264Settings\":{\"rateControlMode\":\"CBR\","
                        + "\"bitrate\":800000}}},\"audioDescriptions\":[{\"codecSettings\":{\"codec\":\"AAC\","
                        + "\"aacSettings\":{\"bitrate\":64000,\"codingMode\":\"CODING_MODE_2_0\",\"sampleRate\":"
                        + "48000}}}]}]}]}}").post(MediaConvertIntegrationTest.PREFIX + "/jobs").then().statusCode(201)
                .extract().path("job.id");
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            String status = MediaConvertIntegrationTest.getJob(id).extract().path("job.status");
            return "COMPLETE".equals(status) || "ERROR".equals(status);
        });
        MediaConvertIntegrationTest.getJob(id).body("job.status", equalTo("COMPLETE"))
                .body("job.jobPercentComplete", equalTo(100))
                .body("job.outputGroupDetails[0].outputDetails[0].videoDetails.widthInPx", equalTo(320))
                .body("job.outputGroupDetails[0].outputDetails[0].videoDetails.heightInPx", equalTo(180))
                .body("job.outputGroupDetails[0].outputDetails[0].durationInMs", greaterThan(2500))
                .body("job.outputGroupDetails[1].outputDetails[0].videoDetails.widthInPx", equalTo(640));

        given().header("Authorization", s3()).get("/mediaconvert-media?list-type=2&prefix=out/").then()
                .statusCode(200).body(containsString("<Key>out/clip_small.mp4</Key>"))
                .body(containsString("<Key>out/clip_thumb.0000000.jpg</Key>"))
                .body(containsString("<Key>out/clip_thumb.0000001.jpg</Key>"));
        given().header("Authorization", s3()).get("/mediaconvert-media?list-type=2&prefix=hls/").then()
                .statusCode(200).body(containsString("<Key>hls/stream.m3u8</Key>"))
                .body(containsString("<Key>hls/stream_360.m3u8</Key>"))
                .body(containsString("<Key>hls/stream_360_00000.ts</Key>"));
        given().header("Authorization", s3()).get("/mediaconvert-media/hls/stream.m3u8").then().statusCode(200)
                .body(containsString("#EXT-X-STREAM-INF:BANDWIDTH=")).body(containsString("RESOLUTION=640x360"))
                .body(containsString("stream_360.m3u8"));
    }
}
