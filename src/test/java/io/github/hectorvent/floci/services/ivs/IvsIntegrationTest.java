package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies IVS restJson1 channel lifecycle, tags, and idle-stream data-plane ops. */
@QuarkusTest
class IvsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listChannelsOnAnEmptyAccountReturnsAnEmptyCollection() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/ListChannels")
                .then()
                .statusCode(200)
                .body("channels", notNullValue());
    }

    @Test
    void createGetListUpdateTagsAndDeleteChannelLifecycle() {
        String authorization = auth(EAST);
        String name = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "type":"BASIC",
                          "latencyMode":"NORMAL",
                          "tags":{"fixture":"ivs-bindings"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateChannel")
                .then()
                .statusCode(200)
                .body("channel.arn", startsWith("arn:aws:ivs:" + EAST + ":"))
                .body("channel.name", equalTo(name))
                .body("channel.type", equalTo("BASIC"))
                .body("channel.latencyMode", equalTo("NORMAL"))
                .body("channel.ingestEndpoint", notNullValue())
                .body("channel.playbackUrl", notNullValue())
                .body("channel.tags.fixture", equalTo("ivs-bindings"))
                .body("streamKey.arn", notNullValue())
                .body("streamKey.value", notNullValue())
                .extract().path("channel.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetChannel")
                .then()
                .statusCode(200)
                .body("channel.arn", equalTo(arn))
                .body("channel.name", equalTo(name))
                .body("channel.type", equalTo("BASIC"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"filterByName\":\"" + name + "\"}")
                .when()
                .post("/ListChannels")
                .then()
                .statusCode(200)
                .body("channels.size()", greaterThanOrEqualTo(1))
                .body("channels.find { it.arn == '" + arn + "' }.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\",\"name\":\"" + name + "-updated\"}")
                .when()
                .post("/UpdateChannel")
                .then()
                .statusCode(200)
                .body("channel.name", equalTo(name + "-updated"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"1\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("ivs-bindings"))
                .body("tags.extra", equalTo("1"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteChannel")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void idleChannelDataPlaneMatchesTheBindingsContract() {
        String authorization = auth(EAST);
        String name = "idle-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "\",\"type\":\"BASIC\",\"latencyMode\":\"NORMAL\"}")
                .when()
                .post("/CreateChannel")
                .then()
                .statusCode(200)
                .extract().path("channel.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\"}")
                .when()
                .post("/GetStream")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ChannelNotBroadcasting"))
                .body("__type", equalTo("ChannelNotBroadcasting"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\"}")
                .when()
                .post("/GetStreamSession")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\",\"maxResults\":10}")
                .when()
                .post("/ListStreamSessions")
                .then()
                .statusCode(200)
                .body("streamSessions", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListStreams")
                .then()
                .statusCode(200)
                .body("streams", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\",\"metadata\":\"{\\\"hello\\\":\\\"viewers\\\"}\"}")
                .when()
                .post("/PutMetadata")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ChannelNotBroadcasting"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\"}")
                .when()
                .post("/StopStream")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ChannelNotBroadcasting"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\",\"viewerId\":\"alchemy-test-viewer\"}")
                .when()
                .post("/StartViewerSessionRevocation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + arn + "\",\"durationSeconds\":30}")
                .when()
                .post("/InsertAdBreak")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ChannelNotBroadcasting"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"viewerSessions\":[{\"channelArn\":\"" + arn
                        + "\",\"viewerId\":\"alchemy-test-viewer\"}]}")
                .when()
                .post("/BatchStartViewerSessionRevocation")
                .then()
                .statusCode(200)
                .body("errors", hasSize(0));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
