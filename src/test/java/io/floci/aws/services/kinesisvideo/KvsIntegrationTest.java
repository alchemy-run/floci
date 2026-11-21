package io.floci.aws.services.kinesisvideo;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kinesis Video Streams: streams and signaling channels, a producer's Matroska ingested by PutMedia and read back by
 * GetMedia, ListFragments and GetMediaForFragmentList, and WebRTC signaling between a master and a viewer.
 */
@QuarkusTest
class KvsIntegrationTest {

    @TestHTTPResource("/")
    URI base;

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

    private static byte[] sample() throws IOException {
        try (InputStream in = KvsIntegrationTest.class.getResourceAsStream("/kinesisvideo/sample.mkv")) {
            return in.readAllBytes();
        }
    }

    @Test
    void ingestsAndReadsMedia() throws IOException {
        kv("/createStream", "{\"StreamName\":\"cam\",\"DataRetentionInHours\":2,\"MediaType\":\"video/h264\","
                + "\"Tags\":{\"team\":\"video\"}}").statusCode(200).body("StreamARN", containsString(":stream/cam/"));
        kv("/createStream", "{\"StreamName\":\"cam\"}").statusCode(400)
                .header("x-amzn-ErrorType", "ResourceInUseException");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kv("/describeStream", "{\"StreamName\":\"cam\"}")
                .statusCode(200).body("StreamInfo.Status", equalTo("ACTIVE")));
        String version = kv("/describeStream", "{\"StreamName\":\"cam\"}").extract().path("StreamInfo.Version");
        kv("/updateDataRetention", "{\"StreamName\":\"cam\",\"CurrentVersion\":\"wrong\",\"Operation\":"
                + "\"INCREASE_DATA_RETENTION\",\"DataRetentionChangeInHours\":1}").statusCode(400)
                .header("x-amzn-ErrorType", "VersionMismatchException");
        kv("/updateDataRetention", "{\"StreamName\":\"cam\",\"CurrentVersion\":\"" + version + "\",\"Operation\":"
                + "\"INCREASE_DATA_RETENTION\",\"DataRetentionChangeInHours\":1}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kv("/describeStream", "{\"StreamName\":\"cam\"}")
                .statusCode(200).body("StreamInfo.Status", equalTo("ACTIVE"))
                .body("StreamInfo.DataRetentionInHours", equalTo(3)));
        kv("/listTagsForStream", "{\"StreamName\":\"cam\"}").statusCode(200).body("Tags.team", equalTo("video"));
        kv("/getDataEndpoint", "{\"StreamName\":\"cam\",\"APIName\":\"PUT_MEDIA\"}").statusCode(200)
                .body("DataEndpoint", startsWith("http"));

        String acks = given().header("Authorization", auth()).header("x-amzn-stream-name", "cam")
                .header("x-amzn-fragment-timecode-type", "RELATIVE")
                .header("x-amzn-producer-start-timestamp", String.valueOf(System.currentTimeMillis() / 1000.0))
                .contentType("application/octet-stream").body(sample()).post("/putMedia").then().statusCode(200)
                .extract().asString();
        List<String> lines = acks.lines().toList();
        assertEquals(9, lines.size(), acks);
        assertTrue(lines.get(2).contains("\"PERSISTED\""), acks);

        kv("/listFragments", "{\"StreamName\":\"cam\"}").statusCode(200).body("Fragments", hasSize(3))
                .body("Fragments[0].FragmentLengthInMilliseconds", equalTo(1000));
        String number = kv("/listFragments", "{\"StreamName\":\"cam\"}").extract().path("Fragments[1].FragmentNumber");
        byte[] fragment = given().header("Authorization", auth()).contentType("application/json")
                .body("{\"StreamName\":\"cam\",\"Fragments\":[\"" + number + "\"]}").post("/getMediaForFragmentList")
                .then().statusCode(200).header("Content-Type", "video/webm").extract().asByteArray();
        assertTrue(new String(fragment, StandardCharsets.ISO_8859_1).contains(number));
        kv("/getMediaForFragmentList", "{\"StreamName\":\"cam\",\"Fragments\":[\"1\"]}").statusCode(400)
                .body("Message", containsString("Fragment numbers are invalid"));

        byte[] media = given().header("Authorization", auth()).contentType("application/json")
                .body("{\"StreamName\":\"cam\",\"StartSelector\":{\"StartSelectorType\":\"EARLIEST\"}}")
                .post("/getMedia").then().statusCode(200).header("Content-Type", "video/webm").extract()
                .asByteArray();
        String text = new String(media, StandardCharsets.ISO_8859_1);
        assertEquals(4, text.split("AWS_KINESISVIDEO_FRAGMENT_NUMBER|AWS_KINESISVIDEO_CONTINUATION_TOKEN", -1).length
                - 1, "three fragments and a continuation token");

        kv("/getHLSStreamingSessionURL", "{\"StreamName\":\"cam\",\"PlaybackMode\":\"LIVE\"}").statusCode(200)
                .body("HLSStreamingSessionURL", containsString("getHLSMasterPlaylist.m3u8"));
        kv("/getClip", "{\"StreamName\":\"cam\",\"ClipFragmentSelector\":{\"FragmentSelectorType\":"
                + "\"SERVER_TIMESTAMP\",\"TimestampRange\":{\"StartTimestamp\":1,\"EndTimestamp\":2}}}").statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException");

        kv("/deleteStream", "{\"StreamARN\":\"" + kv("/describeStream", "{\"StreamName\":\"cam\"}").extract()
                .path("StreamInfo.StreamARN") + "\"}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kv("/describeStream", "{\"StreamName\":\"cam\"}")
                .statusCode(404));
    }

    @Test
    void relaysSignalingBetweenMasterAndViewers() throws Exception {
        String arn = kv("/createSignalingChannel", "{\"ChannelName\":\"door\",\"SingleMasterConfiguration\":{"
                + "\"MessageTtlSeconds\":10}}").statusCode(200).extract().path("ChannelARN");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> kv("/describeSignalingChannel",
                "{\"ChannelName\":\"door\"}").statusCode(200).body("ChannelInfo.ChannelStatus", equalTo("ACTIVE")));
        kv("/getSignalingChannelEndpoint", "{\"ChannelARN\":\"" + arn + "\",\"SingleMasterChannelEndpointConfiguration\":"
                + "{\"Protocols\":[\"WSS\",\"HTTPS\"],\"Role\":\"MASTER\"}}").statusCode(200)
                .body("ResourceEndpointList.Protocol", hasItem("WSS"));
        kv("/v1/get-ice-server-config", "{\"ChannelARN\":\"" + arn + "\",\"ClientId\":\"v1\"}").statusCode(200)
                .body("IceServerList[0].Uris[0]", startsWith("turn:"));
        kv("/joinStorageSession", "{\"channelArn\":\"" + arn + "\"}").statusCode(400)
                .body("Message", containsString("MediaStorageConfiguration"));

        String ws = "ws://" + base.getHost() + ":" + base.getPort() + KvsSignaling.PATH + "us-east-1?X-Amz-ChannelARN="
                + arn;
        LinkedBlockingQueue<String> masterInbox = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> viewerInbox = new LinkedBlockingQueue<>();
        HttpClient http = HttpClient.newHttpClient();
        WebSocket viewer = http.newWebSocketBuilder().buildAsync(URI.create(ws + "&X-Amz-ClientId=v1"),
                listener(viewerInbox)).get(10, TimeUnit.SECONDS);
        viewer.sendText("{\"action\":\"SDP_OFFER\",\"messagePayload\":\"b2ZmZXI=\"}", true).get(5, TimeUnit.SECONDS);
        WebSocket master = http.newWebSocketBuilder().buildAsync(URI.create(ws), listener(masterInbox))
                .get(10, TimeUnit.SECONDS);
        String held = masterInbox.poll(10, TimeUnit.SECONDS);
        assertTrue(held != null && held.contains("\"senderClientId\":\"v1\"") && held.contains("SDP_OFFER"), held);
        master.sendText("{\"action\":\"SDP_ANSWER\",\"recipientClientId\":\"v1\",\"messagePayload\":\"YW5zd2Vy\"}",
                true).get(5, TimeUnit.SECONDS);
        String answer = viewerInbox.poll(10, TimeUnit.SECONDS);
        assertTrue(answer != null && answer.contains("SDP_ANSWER"), answer);
        master.sendText("{\"action\":\"ICE_CANDIDATE\",\"recipientClientId\":\"gone\",\"messagePayload\":\"eA==\","
                + "\"correlationId\":\"c1\"}", true).get(5, TimeUnit.SECONDS);
        String status = masterInbox.poll(10, TimeUnit.SECONDS);
        assertTrue(status != null && status.contains("STATUS_RESPONSE") && status.contains("c1"), status);
        viewer.abort();
        master.abort();

        String version = kv("/describeSignalingChannel", "{\"ChannelName\":\"door\"}").extract()
                .path("ChannelInfo.Version");
        kv("/deleteSignalingChannel", "{\"ChannelARN\":\"" + arn + "\",\"CurrentVersion\":\"" + version + "\"}")
                .statusCode(200);
    }

    private static WebSocket.Listener listener(LinkedBlockingQueue<String> inbox) {
        return new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    inbox.add(text.toString());
                    text.setLength(0);
                }
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
