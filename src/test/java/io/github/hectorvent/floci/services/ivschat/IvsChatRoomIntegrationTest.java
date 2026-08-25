package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies IVS Chat restJson1 room operations used by Alchemy
 * {@code Room.test.ts}: GetRoom of an unknown ARN is a typed
 * ResourceNotFoundException, and create/update/delete/tag converge.
 */
@QuarkusTest
class IvsChatRoomIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRoomOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivschat:" + EAST + ":000000000000:room/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetRoom")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("ROOM"))
                .body("resourceId", equalTo(arn));
    }

    @Test
    void createGetUpdateDeleteRoomLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivschat-room";
        String renamed = name + "-b";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "maximumMessageRatePerSecond":5,
                          "maximumMessageLength":200,
                          "tags":{"fixture":"ivschat-room","alchemy::id":"Chat"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateRoom")
                .then()
                .statusCode(200)
                .body("arn", containsString(":room/"))
                .body("id", notNullValue())
                .body("name", equalTo(name))
                .body("maximumMessageRatePerSecond", equalTo(5))
                .body("maximumMessageLength", equalTo(200))
                .body("tags.fixture", equalTo("ivschat-room"))
                .body("tags.'alchemy::id'", equalTo("Chat"))
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetRoom")
                .then()
                .statusCode(200)
                .body("arn", equalTo(arn))
                .body("name", equalTo(name))
                .body("maximumMessageRatePerSecond", equalTo(5))
                .body("maximumMessageLength", equalTo(200))
                .body("tags.fixture", equalTo("ivschat-room"))
                .body("tags.'alchemy::id'", equalTo("Chat"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "\"}")
                .when()
                .post("/ListRooms")
                .then()
                .statusCode(200)
                .body("rooms[0].arn", equalTo(arn))
                .body("rooms[0].name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "identifier":"%s",
                          "name":"%s",
                          "maximumMessageLength":300
                        }
                        """.formatted(arn, renamed))
                .when()
                .post("/UpdateRoom")
                .then()
                .statusCode(200)
                .body("arn", equalTo(arn))
                .body("name", equalTo(renamed))
                .body("maximumMessageLength", equalTo(300));

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
                .body("tags.fixture", equalTo("ivschat-room"))
                .body("tags.extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteRoom")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetRoom")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("ROOM"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteRoom")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivschat/aws4_request";
    }
}
