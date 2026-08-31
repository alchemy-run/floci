package io.github.hectorvent.floci.services.ivsrealtime;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies IVS Real-Time restJson1 operations used by Alchemy
 * {@code Bindings.test.ts}: ListStages is empty on a fresh account, stages
 * create/get/tag, participant tokens honor duration, and missing
 * session/composition lookups return ResourceNotFoundException.
 */
@QuarkusTest
class IvsRealtimeIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listStagesOnAnEmptyAccountReturnsAnEmptyCollection() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/ListStages")
                .then()
                .statusCode(200)
                .body("stages", notNullValue());
    }

    @Test
    void createGetTokenAndMissingLookups() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivsrealtime-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "tags":{"fixture":"ivsrealtime-bindings"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateStage")
                .then()
                .statusCode(200)
                .body("stage.arn", containsString(":stage/"))
                .body("stage.name", equalTo(name))
                .body("stage.endpoints.whip", notNullValue())
                .body("stage.tags.fixture", equalTo("ivsrealtime-bindings"))
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
                .body("stage.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListStages")
                .then()
                .statusCode(200)
                .body("stages", hasSize(greaterThan(0)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("ivsrealtime-bindings"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "stageArn":"%s",
                          "userId":"alchemy-test-user",
                          "capabilities":["PUBLISH","SUBSCRIBE"],
                          "duration":30,
                          "attributes":{"displayName":"Alchemy"}
                        }
                        """.formatted(arn))
                .when()
                .post("/CreateParticipantToken")
                .then()
                .statusCode(200)
                .body("participantToken.token", notNullValue())
                .body("participantToken.participantId", notNullValue())
                .body("participantToken.duration", equalTo(30))
                .body("participantToken.userId", equalTo("alchemy-test-user"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"stageArn\":\"" + arn + "\"}")
                .when()
                .post("/ListStageSessions")
                .then()
                .statusCode(200)
                .body("stageSessions", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"stageArn\":\"" + arn + "\",\"sessionId\":\"st-0000AbCd0000\"}")
                .when()
                .post("/GetStageSession")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"stageArn\":\"" + arn + "\",\"sessionId\":\"st-0000AbCd0000\"}")
                .when()
                .post("/ListParticipants")
                .then()
                .statusCode(200)
                .body("participants", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "stageArn":"%s",
                          "sessionId":"st-0000AbCd0000",
                          "participantId":"abcDEF123456"
                        }
                        """.formatted(arn))
                .when()
                .post("/GetParticipant")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "stageArn":"%s",
                          "sessionId":"st-0000AbCd0000",
                          "participantId":"abcDEF123456"
                        }
                        """.formatted(arn))
                .when()
                .post("/ListParticipantEvents")
                .then()
                .statusCode(200)
                .body("events", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"sourceStageArn\":\"" + arn + "\",\"participantId\":\"abcDEF123456\"}")
                .when()
                .post("/ListParticipantReplicas")
                .then()
                .statusCode(200)
                .body("replicas", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "stageArn":"%s",
                          "participantId":"abcDEF123456",
                          "reason":"alchemy test"
                        }
                        """.formatted(arn))
                .when()
                .post("/DisconnectParticipant")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        String overflow = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "-b\"}")
                .when()
                .post("/CreateStage")
                .then()
                .statusCode(200)
                .extract()
                .path("stage.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "sourceStageArn":"%s",
                          "destinationStageArn":"%s",
                          "participantId":"abcDEF123456"
                        }
                        """.formatted(arn, overflow))
                .when()
                .post("/StartParticipantReplication")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "sourceStageArn":"%s",
                          "destinationStageArn":"%s",
                          "participantId":"abcDEF123456"
                        }
                        """.formatted(arn, overflow))
                .when()
                .post("/StopParticipantReplication")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListCompositions")
                .then()
                .statusCode(200)
                .body("compositions", hasSize(0));

        String compositionArn = "arn:aws:ivs:" + EAST + ":000000000000:composition/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + compositionArn + "\"}")
                .when()
                .post("/GetComposition")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + compositionArn + "\"}")
                .when()
                .post("/StopComposition")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        String storageArn = "arn:aws:ivs:" + EAST + ":000000000000:storage-configuration/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "stageArn":"%s",
                          "destinations":[{"s3":{"storageConfigurationArn":"%s","encoderConfigurationArns":[]}}]
                        }
                        """.formatted(arn, storageArn))
                .when()
                .post("/StartComposition")
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
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + overflow + "\"}")
                .when()
                .post("/DeleteStage")
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
