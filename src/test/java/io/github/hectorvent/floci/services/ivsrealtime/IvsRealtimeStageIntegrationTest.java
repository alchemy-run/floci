package io.github.hectorvent.floci.services.ivsrealtime;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies IVS Real-Time restJson1 stage operations used by Alchemy
 * {@code Stage.test.ts}: GetStage of an unknown ARN is a typed
 * ResourceNotFoundException, and create/rename-in-place/tag/delete converge.
 */
@QuarkusTest
class IvsRealtimeStageIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getStageOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:stage/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetStage")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateDeleteStageLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-stage-" + UUID.randomUUID().toString().substring(0, 8);
        String renamed = name + "-b";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "tags":{"fixture":"ivs-realtime-stage","alchemy::id":"Room"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateStage")
                .then()
                .statusCode(200)
                .body("stage.arn", containsString(":stage/"))
                .body("stage.name", equalTo(name))
                .body("stage.endpoints.whip", notNullValue())
                .body("stage.tags.fixture", equalTo("ivs-realtime-stage"))
                .body("stage.tags.'alchemy::id'", equalTo("Room"))
                .extract()
                .path("stage.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetStage")
                .then()
                .statusCode(200)
                .body("stage.arn", equalTo(arn))
                .body("stage.name", equalTo(name))
                .body("stage.tags.fixture", equalTo("ivs-realtime-stage"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListStages")
                .then()
                .statusCode(200)
                .body("stages.find { it.arn == '" + arn + "' }.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "arn":"%s",
                          "name":"%s"
                        }
                        """.formatted(arn, renamed))
                .when()
                .post("/UpdateStage")
                .then()
                .statusCode(200)
                .body("stage.arn", equalTo(arn))
                .body("stage.name", equalTo(renamed));

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
                .body("tags.fixture", equalTo("ivs-realtime-stage"))
                .body("tags.extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteStage")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetStage")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteStage")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
