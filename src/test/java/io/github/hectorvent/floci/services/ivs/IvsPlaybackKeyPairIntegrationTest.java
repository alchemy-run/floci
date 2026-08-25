package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies IVS restJson1 playback key pair operations used by Alchemy
 * {@code PlaybackKeyPair.test.ts}: import, get, list, tag, replace-via-delete,
 * and typed ResourceNotFoundException.
 */
@QuarkusTest
class IvsPlaybackKeyPairIntegrationTest {

    private static final String EAST = "us-east-1";

    // Same secp384r1 keys as packages/alchemy/test/AWS/IVS/PlaybackKeyPair.test.ts
    private static final String PUBLIC_KEY_A = """
            -----BEGIN PUBLIC KEY-----
            MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAESErCxbkHXtts2QDGbIfjMpUjNtnBtHwm
            vIu3tC3Rqdv3NAcDzBadv045/QzYLFYpW1qqAMBGCASZKl+fvk+oI6ES+wV6aT1i
            vGQdcQN88zA1DeJLu2CEA5dRXmSKhP4C
            -----END PUBLIC KEY-----""";

    private static final String PUBLIC_KEY_B = """
            -----BEGIN PUBLIC KEY-----
            MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEiS2HkhQsxtJG4i++/Na99ZwNEugpI+M/
            N0EKZtiBKnt5QhZMcyeRjcwY3zJBtrZSd8hgdyaW0HQoGtyNVNZNi54XPRq3BQUf
            r4NJCuts5JUtFVRM6ynCdqdi78ZxNUBj
            -----END PUBLIC KEY-----""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPlaybackKeyPairOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:playback-key/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackKeyPair")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void importGetListTagReplaceAndDeletePlaybackKeyPairLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-keypair";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "publicKeyMaterial":%s,
                          "tags":{"fixture":"ivs-playback-key-pair","alchemy::id":"Viewer"}
                        }
                        """.formatted(name, jsonString(PUBLIC_KEY_A)))
                .when()
                .post("/ImportPlaybackKeyPair")
                .then()
                .statusCode(200)
                .body("keyPair.arn", containsString(":playback-key/"))
                .body("keyPair.name", equalTo(name))
                .body("keyPair.fingerprint", notNullValue())
                .body("keyPair.tags.fixture", equalTo("ivs-playback-key-pair"))
                .body("keyPair.tags.'alchemy::id'", equalTo("Viewer"))
                .extract()
                .path("keyPair.arn");

        String fingerprint = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackKeyPair")
                .then()
                .statusCode(200)
                .body("keyPair.arn", equalTo(arn))
                .body("keyPair.name", equalTo(name))
                .body("keyPair.fingerprint", notNullValue())
                .extract()
                .path("keyPair.fingerprint");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListPlaybackKeyPairs")
                .then()
                .statusCode(200)
                .body("keyPairs.find { it.arn == '" + arn + "' }.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "publicKeyMaterial":%s
                        }
                        """.formatted(name, jsonString(PUBLIC_KEY_B)))
                .when()
                .post("/ImportPlaybackKeyPair")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeletePlaybackKeyPair")
                .then()
                .statusCode(200);

        String replacedArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "publicKeyMaterial":%s,
                          "tags":{"fixture":"ivs-playback-key-pair","alchemy::id":"Viewer"}
                        }
                        """.formatted(name, jsonString(PUBLIC_KEY_B)))
                .when()
                .post("/ImportPlaybackKeyPair")
                .then()
                .statusCode(200)
                .body("keyPair.arn", containsString(":playback-key/"))
                .body("keyPair.arn", not(equalTo(arn)))
                .body("keyPair.fingerprint", not(equalTo(fingerprint)))
                .extract()
                .path("keyPair.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetPlaybackKeyPair")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + replacedArn + "\"}")
                .when()
                .post("/DeletePlaybackKeyPair")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + replacedArn + "\"}")
                .when()
                .post("/DeletePlaybackKeyPair")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
