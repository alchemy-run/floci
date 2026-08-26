package io.github.hectorvent.floci.services.kinesisvideo;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.hasSize;

/**
 * Verifies Kinesis Video operations used by Alchemy {@code Bindings.test.ts}:
 * stream/channel lifecycle, data-endpoint discovery, empty-stream archived-media
 * errors, ICE config, and WEBRTC storage rejection without MediaStorageConfiguration.
 */
@QuarkusTest
class KinesisVideoBindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeSignalingChannelOnAMissingNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"ChannelName\":\"does-not-exist-" + id() + "\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void streamAndChannelLifecyclePlusEmptyDataPlane() {
        String authorization = auth(EAST);
        String streamName = "kv-bindings-stream-" + id();
        String channelName = "kv-bindings-channel-" + id();

        String streamArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamName":"%s",
                          "MediaType":"video/h264",
                          "DataRetentionInHours":24,
                          "Tags":{"alchemy::id":"FixtureStream"}
                        }
                        """.formatted(streamName))
                .when()
                .post("/createStream")
                .then()
                .statusCode(200)
                .body("StreamARN", containsString(":stream/" + streamName + "/"))
                .extract()
                .path("StreamARN");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + streamName + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(200)
                .body("StreamInfo.StreamName", equalTo(streamName))
                .body("StreamInfo.StreamARN", equalTo(streamArn))
                .body("StreamInfo.Status", equalTo("ACTIVE"))
                .body("StreamInfo.MediaType", equalTo("video/h264"))
                .body("StreamInfo.DataRetentionInHours", equalTo(24));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamNameCondition":{
                            "ComparisonOperator":"BEGINS_WITH",
                            "ComparisonValue":"%s"
                          }
                        }
                        """.formatted(streamName))
                .when()
                .post("/listStreams")
                .then()
                .statusCode(200)
                .body("StreamInfoList[0].StreamARN", equalTo(streamArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + streamArn + "\"}")
                .when()
                .post("/listTagsForStream")
                .then()
                .statusCode(200)
                .body("Tags.'alchemy::id'", equalTo("FixtureStream"));

        String endpoint = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + streamArn + "\",\"APIName\":\"GET_HLS_STREAMING_SESSION_URL\"}")
                .when()
                .post("/getDataEndpoint")
                .then()
                .statusCode(200)
                .body("DataEndpoint", startsWith("http"))
                .extract()
                .path("DataEndpoint");
        org.junit.jupiter.api.Assertions.assertTrue(endpoint.contains("://"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + streamArn + "\",\"PlaybackMode\":\"LIVE\"}")
                .when()
                .post("/getHLSStreamingSessionURL")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + streamArn + "\",\"PlaybackMode\":\"LIVE\"}")
                .when()
                .post("/getDASHStreamingSessionURL")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "FragmentSelector":{
                            "FragmentSelectorType":"SERVER_TIMESTAMP",
                            "TimestampRange":{"StartTimestamp":1,"EndTimestamp":2}
                          }
                        }
                        """.formatted(streamArn))
                .when()
                .post("/listFragments")
                .then()
                .statusCode(200)
                .body("Fragments", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "ClipFragmentSelector":{
                            "FragmentSelectorType":"SERVER_TIMESTAMP",
                            "TimestampRange":{"StartTimestamp":1,"EndTimestamp":2}
                          }
                        }
                        """.formatted(streamArn))
                .when()
                .post("/getClip")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "ImageSelectorType":"SERVER_TIMESTAMP",
                          "StartTimestamp":1,
                          "EndTimestamp":2,
                          "Format":"JPEG"
                        }
                        """.formatted(streamArn))
                .when()
                .post("/getImages")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "Fragments":["91343852333181432392682062607743920994"]
                        }
                        """.formatted(streamArn))
                .when()
                .post("/getMediaForFragmentList")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidArgumentException"))
                .body("Message", containsString("Fragment numbers are invalid"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "StartSelector":{"StartSelectorType":"EARLIEST"}
                        }
                        """.formatted(streamArn))
                .when()
                .post("/getMedia")
                .then()
                .statusCode(200)
                .contentType("video/webm");

        String channelArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ChannelName":"%s",
                          "Tags":[{"Key":"alchemy::id","Value":"FixtureChannel"}]
                        }
                        """.formatted(channelName))
                .when()
                .post("/createSignalingChannel")
                .then()
                .statusCode(200)
                .body("ChannelARN", containsString(":channel/" + channelName + "/"))
                .extract()
                .path("ChannelARN");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + channelName + "\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(200)
                .body("ChannelInfo.ChannelName", equalTo(channelName))
                .body("ChannelInfo.ChannelARN", equalTo(channelArn))
                .body("ChannelInfo.ChannelStatus", equalTo("ACTIVE"))
                .body("ChannelInfo.SingleMasterConfiguration.MessageTtlSeconds", equalTo(60));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + channelArn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.'alchemy::id'", equalTo("FixtureChannel"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ChannelARN":"%s",
                          "SingleMasterChannelEndpointConfiguration":{
                            "Protocols":["HTTPS"],
                            "Role":"MASTER"
                          }
                        }
                        """.formatted(channelArn))
                .when()
                .post("/getSignalingChannelEndpoint")
                .then()
                .statusCode(200)
                .body("ResourceEndpointList[0].Protocol", equalTo("HTTPS"))
                .body("ResourceEndpointList[0].ResourceEndpoint", startsWith("http"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ChannelARN":"%s",
                          "SingleMasterChannelEndpointConfiguration":{
                            "Protocols":["WEBRTC"],
                            "Role":"MASTER"
                          }
                        }
                        """.formatted(channelArn))
                .when()
                .post("/getSignalingChannelEndpoint")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidArgumentException"))
                .body("Message", containsString("MediaStorageConfiguration"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelARN\":\"" + channelArn + "\",\"ClientId\":\"alchemy-test\"}")
                .when()
                .post("/v1/get-ice-server-config")
                .then()
                .statusCode(200)
                .body("IceServerList", hasSize(greaterThan(0)))
                .body("IceServerList[0].Uris[0]", startsWith("turn"))
                .body("IceServerList[0].Username", equalTo("floci"))
                .body("IceServerList[0].Password", equalTo("floci-ice-secret"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + streamArn + "\"}")
                .when()
                .post("/deleteStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + streamName + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelARN\":\"" + channelArn + "\"}")
                .when()
                .post("/deleteSignalingChannel")
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/kinesisvideo/aws4_request";
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
