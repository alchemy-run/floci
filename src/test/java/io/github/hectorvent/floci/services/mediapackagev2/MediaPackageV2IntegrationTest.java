package io.github.hectorvent.floci.services.mediapackagev2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies MediaPackage v2 restJson1 channel-group / channel / origin-endpoint
 * lifecycle used by Alchemy Sweep.test.ts (create, list, reap children, delete).
 */
@QuarkusTest
class MediaPackageV2IntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth(EAST);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getChannelGroupOnABogusNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup/alchemy-nonexistent-channel-group-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void sweepReapsGroupChannelAndOriginEndpointAndLeavesZeroRemaining() {
        String group = "alchemy-mpv2-sweep-" + UUID.randomUUID().toString().substring(0, 8);
        String channel = "probe-feed";
        String endpoint = "probe-playback";

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ChannelGroupName\":\"" + group + "\"}")
                .when()
                .post("/channelGroup")
                .then()
                .statusCode(200)
                .body("ChannelGroupName", equalTo(group))
                .body("Arn", notNullValue())
                .body("EgressDomain", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ChannelGroupName\":\"" + group + "\"}")
                .when()
                .post("/channelGroup")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"ChannelName\":\"" + channel + "\",\"InputType\":\"HLS\"}")
                .when()
                .post("/channelGroup/" + group + "/channel")
                .then()
                .statusCode(200)
                .body("ChannelName", equalTo(channel))
                .body("ChannelGroupName", equalTo(group))
                .body("InputType", equalTo("HLS"))
                .body("IngestEndpoints", hasSize(greaterThanOrEqualTo(1)));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "OriginEndpointName":"%s",
                          "ContainerType":"TS",
                          "Segment":{"SegmentDurationSeconds":6},
                          "HlsManifests":[{"ManifestName":"index"}]
                        }
                        """.formatted(endpoint))
                .when()
                .post("/channelGroup/" + group + "/channel/" + channel + "/originEndpoint")
                .then()
                .statusCode(200)
                .body("OriginEndpointName", equalTo(endpoint))
                .body("ContainerType", equalTo("TS"))
                .body("HlsManifests[0].ManifestName", equalTo("index"))
                .body("HlsManifests[0].Url", notNullValue());

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup")
                .then()
                .statusCode(200)
                .body("Items.ChannelGroupName", hasItem(group));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup/" + group + "/channel")
                .then()
                .statusCode(200)
                .body("Items.ChannelName", hasItem(channel));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup/" + group + "/channel/" + channel + "/originEndpoint")
                .then()
                .statusCode(200)
                .body("Items.OriginEndpointName", hasItem(endpoint));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group + "/channel/" + channel)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group + "/channel/" + channel + "/originEndpoint/" + endpoint)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group + "/channel/" + channel + "/")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/channelGroup/" + group)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup/" + group)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/channelGroup/" + group + "/channel")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateOriginEndpointAndPutPoliciesViaAwsMethods() {
        String group = "alchemy-mpv2-update-" + UUID.randomUUID().toString().substring(0, 8);
        String channel = "feed";
        String endpoint = "playback";

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ChannelGroupName\":\"" + group + "\",\"tags\":{\"fixture\":\"mediapackagev2\"}}")
                .when().post("/channelGroup")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ChannelName\":\"" + channel + "\",\"InputType\":\"HLS\"}")
                .when().post("/channelGroup/" + group + "/channel")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                        {
                          "OriginEndpointName":"%s",
                          "ContainerType":"TS",
                          "Description":"v1",
                          "Segment":{"SegmentDurationSeconds":6},
                          "HlsManifests":[{"ManifestName":"index"}]
                        }
                        """.formatted(endpoint))
                .when().post("/channelGroup/" + group + "/channel/" + channel + "/originEndpoint")
                .then().statusCode(200)
                .body("Description", equalTo("v1"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                        {
                          "ContainerType":"TS",
                          "Description":"v2",
                          "StartoverWindowSeconds":300,
                          "Segment":{"SegmentDurationSeconds":6},
                          "HlsManifests":[{"ManifestName":"index"}]
                        }
                        """)
                .when().put("/channelGroup/" + group + "/channel/" + channel + "/originEndpoint/" + endpoint)
                .then().statusCode(200)
                .body("Description", equalTo("v2"))
                .body("StartoverWindowSeconds", equalTo(300));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Policy\":\"{\\\"Statement\\\":[{\\\"Sid\\\":\\\"AllowIngest\\\"}]}\"}")
                .when().put("/channelGroup/" + group + "/channel/" + channel + "/policy")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/channelGroup/" + group + "/channel/" + channel + "/policy")
                .then().statusCode(200)
                .body("Policy", equalTo("{\"Statement\":[{\"Sid\":\"AllowIngest\"}]}"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Policy\":\"{\\\"Statement\\\":[{\\\"Sid\\\":\\\"AllowPlayback\\\"}]}\"}")
                .when().post("/channelGroup/" + group + "/channel/" + channel
                        + "/originEndpoint/" + endpoint + "/policy")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/channelGroup/" + group + "/channel/" + channel
                        + "/originEndpoint/" + endpoint + "/policy")
                .then().statusCode(200)
                .body("Policy", equalTo("{\"Statement\":[{\"Sid\":\"AllowPlayback\"}]}"));

        given().header("Authorization", AUTH)
                .when().delete("/channelGroup/" + group + "/channel/" + channel + "/policy")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/channelGroup/" + group + "/channel/" + channel + "/policy")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", AUTH)
                .when().delete("/channelGroup/" + group + "/channel/" + channel
                        + "/originEndpoint/" + endpoint)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .when().delete("/channelGroup/" + group + "/channel/" + channel + "/")
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .when().delete("/channelGroup/" + group)
                .then().statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediapackagev2/aws4_request";
    }
}
