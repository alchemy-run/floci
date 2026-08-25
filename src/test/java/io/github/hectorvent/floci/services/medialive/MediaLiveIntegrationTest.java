package io.github.hectorvent.floci.services.medialive;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies MediaLive restJson1 input-security-group, input, channel, and tag lifecycle. */
@QuarkusTest
class MediaLiveIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOnABogusIdFailsWithNotFoundException() {
        String authorization = auth(EAST);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/9999999")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputs/9999999")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputSecurityGroups/9999999")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void inputSecurityGroupCreateUpdateTagsAndDelete() {
        String authorization = auth(EAST);
        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "whitelistRules":[{"cidr":"10.0.0.0/16"}],
                          "tags":{"Environment":"test","alchemy::id":"Allowlist"}
                        }
                        """)
                .when()
                .post("/prod/inputSecurityGroups")
                .then()
                .statusCode(200)
                .body("securityGroup.id", notNullValue())
                .body("securityGroup.arn", containsString(":inputSecurityGroup:"))
                .body("securityGroup.whitelistRules[0].cidr", equalTo("10.0.0.0/16"))
                .body("securityGroup.tags.Environment", equalTo("test"))
                .extract().path("securityGroup.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputSecurityGroups/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("whitelistRules[0].cidr", equalTo("10.0.0.0/16"))
                .body("tags['alchemy::id']", equalTo("Allowlist"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "whitelistRules":[{"cidr":"10.1.0.0/16"},{"cidr":"192.168.0.0/24"}]
                        }
                        """)
                .when()
                .put("/prod/inputSecurityGroups/" + id)
                .then()
                .statusCode(200)
                .body("securityGroup.id", equalTo(id))
                .body("securityGroup.whitelistRules.size()", equalTo(2));

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputSecurityGroups/" + id)
                .then()
                .statusCode(200)
                .extract().path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .urlEncodingEnabled(false)
                .body("{\"tags\":{\"Extra\":\"yes\"}}")
                .when()
                .post("/prod/tags/" + encode(arn))
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", authorization)
                .when()
                .get("/prod/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Extra", equalTo("yes"))
                .body("tags.Environment", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputSecurityGroups/" + id)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputSecurityGroups/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void urlPullInputCreateUpdateSourcesAndDelete() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ml-pull-" + UUID.randomUUID().toString().substring(0, 8);
        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "type":"URL_PULL",
                          "sources":[{"url":"https://example.com/stream/index.m3u8"}],
                          "tags":{"alchemy::id":"Pull"}
                        }
                        """.formatted(name))
                .when()
                .post("/prod/inputs")
                .then()
                .statusCode(200)
                .body("input.id", notNullValue())
                .body("input.arn", containsString(":input:"))
                .body("input.name", equalTo(name))
                .body("input.type", equalTo("URL_PULL"))
                .body("input.state", equalTo("DETACHED"))
                .extract().path("input.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputs/" + id)
                .then()
                .statusCode(200)
                .body("sources[0].url", equalTo("https://example.com/stream/index.m3u8"))
                .body("tags['alchemy::id']", equalTo("Pull"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "sources":[{"url":"https://example.com/stream/other.m3u8"}]
                        }
                        """.formatted(name))
                .when()
                .put("/prod/inputs/" + id)
                .then()
                .statusCode(200)
                .body("input.id", equalTo(id))
                .body("input.sources[0].url", equalTo("https://example.com/stream/other.m3u8"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputs")
                .then()
                .statusCode(200)
                .body("inputs.size()", greaterThanOrEqualTo(1));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputs/" + id)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputs/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void rtmpPushInputBehindAnAllowlistAndDeleteOrdering() {
        String authorization = auth(EAST);
        String groupId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"whitelistRules\":[{\"cidr\":\"0.0.0.0/0\"}]}")
                .when()
                .post("/prod/inputSecurityGroups")
                .then()
                .statusCode(200)
                .extract().path("securityGroup.id");

        String inputId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-test-ml-push",
                          "type":"RTMP_PUSH",
                          "inputSecurityGroups":["%s"],
                          "destinations":[{"streamName":"live/primary"},{"streamName":"live/secondary"}]
                        }
                        """.formatted(groupId))
                .when()
                .post("/prod/inputs")
                .then()
                .statusCode(200)
                .body("input.securityGroups[0]", equalTo(groupId))
                .body("input.destinations.size()", equalTo(2))
                .body("input.destinations[0].url", containsString("rtmp"))
                .extract().path("input.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/inputSecurityGroups/" + groupId)
                .then()
                .statusCode(200)
                .body("inputs", hasItem(inputId))
                .body("state", equalTo("IN_USE"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputSecurityGroups/" + groupId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputs/" + inputId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputSecurityGroups/" + groupId)
                .then()
                .statusCode(200);
    }

    @Test
    void channelCreateUpdateLogLevelAndDelete() {
        String authorization = auth(EAST);
        String inputId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "type":"URL_PULL",
                          "sources":[{"url":"https://example.com/stream/index.m3u8"}]
                        }
                        """)
                .when()
                .post("/prod/inputs")
                .then()
                .statusCode(200)
                .extract().path("input.id");

        String channelId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-test-ml-channel",
                          "channelClass":"SINGLE_PIPELINE",
                          "logLevel":"DISABLED",
                          "inputAttachments":[{"inputId":"%s","inputAttachmentName":"primary"}],
                          "tags":{"alchemy::id":"Live"}
                        }
                        """.formatted(inputId))
                .when()
                .post("/prod/channels")
                .then()
                .statusCode(200)
                .body("channel.id", notNullValue())
                .body("channel.arn", containsString(":channel:"))
                .body("channel.state", equalTo("IDLE"))
                .body("channel.channelClass", equalTo("SINGLE_PIPELINE"))
                .extract().path("channel.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/" + channelId)
                .then()
                .statusCode(200)
                .body("state", equalTo("IDLE"))
                .body("tags['alchemy::id']", equalTo("Live"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"logLevel\":\"ERROR\"}")
                .when()
                .put("/prod/channels/" + channelId)
                .then()
                .statusCode(200)
                .body("channel.id", equalTo(channelId))
                .body("channel.logLevel", equalTo("ERROR"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/channels/" + channelId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/" + channelId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputs/" + inputId)
                .then()
                .statusCode(200);
    }

    @Test
    void idleChannelScheduleAlertsAndThumbnailsRoundTrip() {
        String authorization = auth(EAST);
        String inputId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"binding-input-%s",
                          "type":"URL_PULL",
                          "sources":[{"url":"https://example.com/stream/index.m3u8"}]
                        }
                        """.formatted(UUID.randomUUID().toString().substring(0, 8)))
                .when()
                .post("/prod/inputs")
                .then()
                .statusCode(200)
                .extract().path("input.id");

        String channelId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"binding-channel",
                          "channelClass":"SINGLE_PIPELINE",
                          "inputAttachments":[{
                            "inputId":"%s",
                            "inputAttachmentName":"primary"
                          }]
                        }
                        """.formatted(inputId))
                .when()
                .post("/prod/channels")
                .then()
                .statusCode(200)
                .body("channel.state", equalTo("IDLE"))
                .extract().path("channel.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/" + channelId + "/schedule")
                .then()
                .statusCode(200)
                .body("scheduleActions.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "creates":{
                            "scheduleActions":[{
                              "actionName":"alchemy-binding-test",
                              "scheduleActionSettings":{
                                "inputSwitchSettings":{"inputAttachmentNameReference":"primary"}
                              }
                            }]
                          }
                        }
                        """)
                .when()
                .put("/prod/channels/" + channelId + "/schedule")
                .then()
                .statusCode(200)
                .body("creates.scheduleActions.size()", equalTo(1));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/channels/" + channelId + "/schedule")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/" + channelId + "/alerts")
                .then()
                .statusCode(200)
                .body("alerts.size()", greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prod/channels/" + channelId + "/thumbnails?pipelineId=0")
                .then()
                .statusCode(200)
                .body("thumbnailDetails.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/channels/" + channelId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prod/inputs/" + inputId)
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/medialive/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
