package io.floci.aws.services.mediaconnect;

import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flow carrying real media: an H.264 and AAC transport stream ffmpeg makes, read for its picture size and audio
 * settings, with a thumbnail of its video.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class MediaConnectDockerIntegrationTest {

    @Inject
    KvsTranscoder ffmpeg;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFlowsSourceIsReadAndThumbnailed() throws Exception {
        byte[] stream = ffmpeg.run(new LinkedHashMap<>(), "ffmpeg -hide_banner -loglevel error -f lavfi "
                + "-i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 3 "
                + "-c:v libx264 -g 25 -pix_fmt yuv420p -c:a aac -ac 2 -f mpegts out.ts").get("out.ts");
        String arn = MediaConnectIntegrationTest.createFlow("media", 25050,
                ",\"sourceMonitoringConfig\":{\"thumbnailState\":\"ENABLED\"}");
        MediaConnectIntegrationTest.mc().post("/v1/flows/start/" + arn).then().statusCode(202);
        MediaConnectIntegrationTest.awaitStatus(arn, "ACTIVE");
        try (DatagramSocket sender = new DatagramSocket()) {
            for (int offset = 0; offset < stream.length; offset += 188 * 7) {
                int length = Math.min(188 * 7, stream.length - offset);
                sender.send(new DatagramPacket(stream, offset, length, InetAddress.getLoopbackAddress(), 25050));
                if (offset % (188 * 7 * 50) == 0) {
                    Thread.sleep(2);
                }
            }
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> MediaConnectIntegrationTest.mc()
                .get("/v1/flows/" + arn + "/source-metadata").then().statusCode(200)
                .body("transportMediaInfo.programs[0].streams.find { it.codec == 'h264' }.frameResolution"
                        + ".frameWidth", equalTo(640))
                .body("transportMediaInfo.programs[0].streams.find { it.codec == 'h264' }.frameResolution"
                        + ".frameHeight", equalTo(360))
                .body("transportMediaInfo.programs[0].streams.find { it.codec == 'aac' }.sampleRate",
                        equalTo(48000))
                .body("transportMediaInfo.programs[0].streams.find { it.codec == 'aac' }.channels", equalTo(2))
                .body("transportMediaInfo.programs[0].streams.streamType", hasItem("Audio")));
        String thumbnail = MediaConnectIntegrationTest.mc().get("/v1/flows/" + arn + "/source-thumbnail").then()
                .statusCode(200).extract().path("thumbnailDetails.thumbnail");
        byte[] jpeg = Base64.getDecoder().decode(thumbnail);
        assertEquals((byte) 0xff, jpeg[0]);
        assertEquals((byte) 0xd8, jpeg[1]);
        assertTrue(jpeg.length > 1000);
        MediaConnectIntegrationTest.mc().post("/v1/flows/stop/" + arn).then().statusCode(202);
        MediaConnectIntegrationTest.awaitStatus(arn, "STANDBY");
        MediaConnectIntegrationTest.mc().delete("/v1/flows/" + arn).then().statusCode(202);
    }
}
