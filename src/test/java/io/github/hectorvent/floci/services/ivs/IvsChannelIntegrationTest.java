package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies IVS restJson1 channel operations used by Alchemy
 * {@code Channel.test.ts}: GetChannel of an unknown ARN is a typed
 * ResourceNotFoundException, and create/update/delete/tag converge.
 */
@QuarkusTest
class IvsChannelIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getChannelOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:channel/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateDeleteChannelLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-channel-" + UUID.randomUUID().toString().substring(0, 8);
        String renamed = name + "-b";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "latencyMode":"LOW",
                          "type":"BASIC",
                          "tags":{"fixture":"ivs-channel","alchemy::id":"Live"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateChannel")
                .then()
                .statusCode(200)
                .body("channel.arn", containsString(":channel/"))
                .body("channel.name", equalTo(name))
                .body("channel.type", equalTo("BASIC"))
                .body("channel.latencyMode", equalTo("LOW"))
                .body("channel.ingestEndpoint", notNullValue())
                .body("channel.playbackUrl", startsWith("https://"))
                .body("channel.tags.fixture", equalTo("ivs-channel"))
                .body("channel.tags.'alchemy::id'", equalTo("Live"))
                .body("streamKey.arn", containsString(":stream-key/"))
                .extract()
                .path("channel.arn");

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
                .body("channel.tags.fixture", equalTo("ivs-channel"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"filterByName\":\"" + name + "\"}")
                .when()
                .post("/ListChannels")
                .then()
                .statusCode(200)
                .body("channels[0].arn", equalTo(arn))
                .body("channels[0].name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "arn":"%s",
                          "name":"%s",
                          "latencyMode":"NORMAL",
                          "authorized":true
                        }
                        """.formatted(arn, renamed))
                .when()
                .post("/UpdateChannel")
                .then()
                .statusCode(200)
                .body("channel.arn", equalTo(arn))
                .body("channel.name", equalTo(renamed))
                .body("channel.latencyMode", equalTo("NORMAL"))
                .body("channel.authorized", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"yes\"}}")
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
                .body("tags.fixture", equalTo("ivs-channel"))
                .body("tags.extra", equalTo("yes"));

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
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
