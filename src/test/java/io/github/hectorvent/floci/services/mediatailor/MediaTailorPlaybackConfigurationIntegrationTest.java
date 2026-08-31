package io.github.hectorvent.floci.services.mediatailor;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Verifies MediaTailor restJson1 playback-configuration CRUD, logs, tags, and not-found. */
@QuarkusTest
class MediaTailorPlaybackConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPlaybackConfigurationOnANonexistentNameFailsWithNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/playbackConfiguration/alchemy-nonexistent-playback-config-probe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"))
                .body("message", containsString("not found"));
    }

    @Test
    void putGetUpdateLogsTagsAndDeletePlaybackConfiguration() {
        String authorization = auth(EAST);
        String name = "alchemy-mt-pc-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "AdDecisionServerUrl":"https://ads.example.com/vast?ip=[client_ip]",
                          "VideoContentSourceUrl":"https://origin.example.com/live",
                          "SlateAdUrl":"https://origin.example.com/slate.mp4",
                          "tags":{"Environment":"test","alchemy::id":"TestConfig"}
                        }
                        """.formatted(name))
                .when()
                .put("/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("PlaybackConfigurationArn", containsString(":playbackConfiguration/" + name))
                .body("PlaybackEndpointPrefix", containsString("mediatailor"))
                .body("SessionInitializationEndpointPrefix", containsString("mediatailor"))
                .body("AdDecisionServerUrl", equalTo("https://ads.example.com/vast?ip=[client_ip]"))
                .body("SlateAdUrl", equalTo("https://origin.example.com/slate.mp4"))
                .body("tags.Environment", equalTo("test"))
                .body("LogConfiguration.PercentEnabled", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("tags['alchemy::id']", equalTo("TestConfig"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "AdDecisionServerUrl":"https://ads.example.com/vast/v2",
                          "VideoContentSourceUrl":"https://origin.example.com/live",
                          "PersonalizationThresholdSeconds":2,
                          "ManifestProcessingRules":{"AdMarkerPassthrough":{"Enabled":true}},
                          "AvailSuppression":{"Mode":"BEHIND_LIVE_EDGE","Value":"00:00:30"},
                          "tags":{"Purpose":"alchemy-live-test","alchemy::id":"TestConfig"}
                        }
                        """.formatted(name))
                .when()
                .put("/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("AdDecisionServerUrl", equalTo("https://ads.example.com/vast/v2"))
                .body("SlateAdUrl", nullValue())
                .body("PersonalizationThresholdSeconds", equalTo(2))
                .body("ManifestProcessingRules.AdMarkerPassthrough.Enabled", equalTo(true))
                .body("AvailSuppression.Mode", equalTo("BEHIND_LIVE_EDGE"))
                .body("tags.Purpose", equalTo("alchemy-live-test"))
                .body("tags.Environment", nullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "PlaybackConfigurationName":"%s",
                          "PercentEnabled":10
                        }
                        """.formatted(name))
                .when()
                .put("/configureLogs/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("PercentEnabled", equalTo(10))
                .body("PlaybackConfigurationName", equalTo(name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + name)
                .then()
                .statusCode(200)
                .body("LogConfiguration.PercentEnabled", equalTo(10));

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + name)
                .then()
                .statusCode(200)
                .extract().path("PlaybackConfigurationArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .urlEncodingEnabled(false)
                .body("{\"tags\":{\"Extra\":\"yes\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Extra", equalTo("yes"))
                .body("tags.Purpose", equalTo("alchemy-live-test"));

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .delete("/tags/" + encode(arn) + "?tagKeys=Extra")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/playbackConfiguration/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/playbackConfiguration/" + name)
                .then()
                .statusCode(200);
    }

    @Test
    void putReplacementNameLeavesTheOriginalDeleted() {
        String authorization = auth(EAST);
        String first = "alchemy-mt-pc-a-" + UUID.randomUUID().toString().substring(0, 8);
        String second = "alchemy-mt-pc-b-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "AdDecisionServerUrl":"https://ads.example.com/vast",
                          "VideoContentSourceUrl":"https://origin.example.com/vod"
                        }
                        """.formatted(first))
                .when()
                .put("/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("Name", equalTo(first));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "AdDecisionServerUrl":"https://ads.example.com/vast",
                          "VideoContentSourceUrl":"https://origin.example.com/vod"
                        }
                        """.formatted(second))
                .when()
                .put("/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("Name", equalTo(second));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/playbackConfiguration/" + first)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + first)
                .then()
                .statusCode(404);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/playbackConfiguration/" + second)
                .then()
                .statusCode(200)
                .body("Name", equalTo(second));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/playbackConfiguration/" + second)
                .then()
                .statusCode(200);
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediatailor/aws4_request";
    }
}
