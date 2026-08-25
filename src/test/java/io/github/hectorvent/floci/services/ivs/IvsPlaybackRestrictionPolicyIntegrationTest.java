package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Verifies IVS restJson1 playback restriction policy operations used by
 * Alchemy {@code PlaybackRestrictionPolicy.test.ts}: Get of an unknown ARN
 * is a typed ResourceNotFoundException, and create/update/delete/tag converge.
 */
@QuarkusTest
class IvsPlaybackRestrictionPolicyIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPlaybackRestrictionPolicyOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:playback-restriction-policy/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackRestrictionPolicy")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateDeletePlaybackRestrictionPolicyLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-prp-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "allowedCountries":["US","CA"],
                          "allowedOrigins":["https://example.com"],
                          "tags":{"fixture":"ivs-prp","alchemy::id":"GeoFence"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreatePlaybackRestrictionPolicy")
                .then()
                .statusCode(200)
                .body("playbackRestrictionPolicy.arn", containsString(":playback-restriction-policy/"))
                .body("playbackRestrictionPolicy.name", equalTo(name))
                .body("playbackRestrictionPolicy.allowedCountries", hasItem("US"))
                .body("playbackRestrictionPolicy.allowedCountries", hasItem("CA"))
                .body("playbackRestrictionPolicy.allowedOrigins", contains("https://example.com"))
                .body("playbackRestrictionPolicy.enableStrictOriginEnforcement", equalTo(false))
                .body("playbackRestrictionPolicy.tags.fixture", equalTo("ivs-prp"))
                .body("playbackRestrictionPolicy.tags.'alchemy::id'", equalTo("GeoFence"))
                .extract()
                .path("playbackRestrictionPolicy.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackRestrictionPolicy")
                .then()
                .statusCode(200)
                .body("playbackRestrictionPolicy.arn", equalTo(arn))
                .body("playbackRestrictionPolicy.name", equalTo(name))
                .body("playbackRestrictionPolicy.tags.fixture", equalTo("ivs-prp"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListPlaybackRestrictionPolicies")
                .then()
                .statusCode(200)
                .body("playbackRestrictionPolicies.find { it.arn == '" + arn + "' }.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "arn":"%s",
                          "allowedCountries":["US"],
                          "allowedOrigins":["https://example.com","https://example.org"],
                          "enableStrictOriginEnforcement":true
                        }
                        """.formatted(arn))
                .when()
                .post("/UpdatePlaybackRestrictionPolicy")
                .then()
                .statusCode(200)
                .body("playbackRestrictionPolicy.arn", equalTo(arn))
                .body("playbackRestrictionPolicy.allowedCountries", contains("US"))
                .body("playbackRestrictionPolicy.allowedOrigins", hasSize(2))
                .body("playbackRestrictionPolicy.enableStrictOriginEnforcement", equalTo(true));

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
                .body("tags.fixture", equalTo("ivs-prp"))
                .body("tags.extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeletePlaybackRestrictionPolicy")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackRestrictionPolicy")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeletePlaybackRestrictionPolicy")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
