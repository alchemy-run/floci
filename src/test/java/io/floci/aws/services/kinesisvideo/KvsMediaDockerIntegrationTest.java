package io.floci.aws.services.kinesisvideo;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kinesis Video media transcoded by ffmpeg in its container: an MP4 clip, images, and HLS playback. */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class KvsMediaDockerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/kinesisvideo/aws4_request";
    }

    private static ValidatableResponse kv(String path, String body) {
        return given().header("Authorization", auth()).contentType("application/json").body(body).post(path).then();
    }

    @Test
    void transcodesClipsImagesAndPlayback() throws IOException {
        kv("/createStream", "{\"StreamName\":\"clips\",\"DataRetentionInHours\":1}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kv("/describeStream", "{\"StreamName\":\"clips\"}")
                .statusCode(200).body("StreamInfo.Status", equalTo("ACTIVE")));
        byte[] sample;
        try (InputStream in = getClass().getResourceAsStream("/kinesisvideo/sample.mkv")) {
            sample = in.readAllBytes();
        }
        given().header("Authorization", auth()).header("x-amzn-stream-name", "clips")
                .contentType("application/octet-stream").body(sample).post("/putMedia").then().statusCode(200);
        kv("/listFragments", "{\"StreamName\":\"clips\"}").statusCode(200).body("Fragments", hasSize(3));
        long now = System.currentTimeMillis() / 1000;
        long start = now - 3600;
        long end = now + 3600;

        byte[] clip = given().header("Authorization", auth()).contentType("application/json")
                .body("{\"StreamName\":\"clips\",\"ClipFragmentSelector\":{\"FragmentSelectorType\":\"SERVER_TIMESTAMP\","
                        + "\"TimestampRange\":{\"StartTimestamp\":" + start
                        + ",\"EndTimestamp\":" + end + "}}}")
                .post("/getClip").then().log().ifValidationFails().statusCode(200).header("Content-Type", "video/mp4").extract().asByteArray();
        assertEquals("ftyp", new String(Arrays.copyOfRange(clip, 4, 8), StandardCharsets.US_ASCII));

        List<String> images = kv("/getImages", "{\"StreamName\":\"clips\",\"ImageSelectorType\":\"PRODUCER_TIMESTAMP\","
                + "\"StartTimestamp\":0,\"EndTimestamp\":2,\"SamplingInterval\":1000,\"Format\":\"JPEG\"}")
                .statusCode(200).body("Images", hasSize(3)).extract().path("Images.ImageContent");
        assertTrue(images.stream().allMatch(i -> i != null && !i.isEmpty()), images.toString());

        String url = kv("/getHLSStreamingSessionURL", "{\"StreamName\":\"clips\",\"PlaybackMode\":\"LIVE\"}")
                .statusCode(200).extract().path("HLSStreamingSessionURL");
        String path = URI.create(url).getPath();
        String media = given().get(path.replace("getHLSMasterPlaylist", "getHLSMediaPlaylist")).then()
                .statusCode(200).extract().asString();
        String segment = media.lines().filter(l -> l.startsWith("getMP4MediaFragment")).findFirst().orElseThrow();
        String init = media.lines().filter(l -> l.startsWith("#EXT-X-MAP")).findFirst().orElseThrow()
                .replaceAll(".*URI=\"([^\"]+)\".*", "$1");
        String base = path.substring(0, path.lastIndexOf('/') + 1);
        byte[] initBytes = given().get(base + init).then().statusCode(200).extract().asByteArray();
        byte[] segmentBytes = given().get(base + segment).then().statusCode(200).extract().asByteArray();
        assertEquals("ftyp", new String(Arrays.copyOfRange(initBytes, 4, 8), StandardCharsets.US_ASCII));
        assertEquals("moof", new String(Arrays.copyOfRange(segmentBytes, 4, 8), StandardCharsets.US_ASCII));
    }
}
