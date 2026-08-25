package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies IVS restJson1 stream-key operations used by Alchemy
 * {@code StreamKey.test.ts}: CreateChannel auto-provisions one key,
 * List/Get/Tag adopt it, a second CreateStreamKey is quota-exceeded,
 * and DeleteStreamKey is idempotent via ResourceNotFoundException.
 */
@QuarkusTest
class IvsStreamKeyIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createChannelAdoptsASingleStreamKeyAndTagsConverge() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-streamkey-" + UUID.randomUUID().toString().substring(0, 8);

        String channelArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "type":"BASIC"
                        }
                        """.formatted(name))
                .when()
                .post("/CreateChannel")
                .then()
                .statusCode(200)
                .body("channel.arn", containsString(":channel/"))
                .body("streamKey.arn", containsString(":stream-key/"))
                .body("streamKey.value", startsWith("sk_"))
                .extract()
                .path("channel.arn");

        String streamKeyArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + channelArn + "\"}")
                .when()
                .post("/ListStreamKeys")
                .then()
                .statusCode(200)
                .body("streamKeys", hasSize(1))
                .body("streamKeys[0].arn", containsString(":stream-key/"))
                .body("streamKeys[0].channelArn", equalTo(channelArn))
                .extract()
                .path("streamKeys[0].arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + streamKeyArn + "\"}")
                .when()
                .post("/GetStreamKey")
                .then()
                .statusCode(200)
                .body("streamKey.arn", equalTo(streamKeyArn))
                .body("streamKey.channelArn", equalTo(channelArn))
                .body("streamKey.value", startsWith("sk_"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"fixture\":\"ivs-stream-key\",\"alchemy::id\":\"Key\"}}")
                .when()
                .post("/tags/" + streamKeyArn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + streamKeyArn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("ivs-stream-key"))
                .body("tags.'alchemy::id'", equalTo("Key"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + streamKeyArn + "\"}")
                .when()
                .post("/GetStreamKey")
                .then()
                .statusCode(200)
                .body("streamKey.tags.fixture", equalTo("ivs-stream-key"))
                .body("streamKey.tags.'alchemy::id'", equalTo("Key"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + channelArn + "\"}")
                .when()
                .post("/CreateStreamKey")
                .then()
                .statusCode(402)
                .header("X-Amzn-Errortype", equalTo("ServiceQuotaExceededException"))
                .body("__type", equalTo("ServiceQuotaExceededException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + streamKeyArn + "\"}")
                .when()
                .post("/DeleteStreamKey")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + streamKeyArn + "\"}")
                .when()
                .post("/GetStreamKey")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"channelArn\":\"" + channelArn + "\"}")
                .when()
                .post("/ListStreamKeys")
                .then()
                .statusCode(200)
                .body("streamKeys", hasSize(0));

        String recreatedArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "channelArn":"%s",
                          "tags":{"fixture":"ivs-stream-key","alchemy::id":"Key"}
                        }
                        """.formatted(channelArn))
                .when()
                .post("/CreateStreamKey")
                .then()
                .statusCode(200)
                .body("streamKey.arn", containsString(":stream-key/"))
                .body("streamKey.channelArn", equalTo(channelArn))
                .body("streamKey.value", startsWith("sk_"))
                .body("streamKey.tags.fixture", equalTo("ivs-stream-key"))
                .extract()
                .path("streamKey.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + recreatedArn + "\"}")
                .when()
                .post("/DeleteStreamKey")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + recreatedArn + "\"}")
                .when()
                .post("/DeleteStreamKey")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getStreamKeyOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:stream-key/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetStreamKey")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
