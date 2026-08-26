package io.github.hectorvent.floci.services.lexv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Lex Models V2 + Runtime V2 operations used by Alchemy
 * {@code RecognizeText.test.ts}: bot/locale/intent/version/alias CRUD,
 * RecognizeText NLU (Greet + FallbackIntent), sessions, and RecognizeUtterance
 * response metadata.
 */
@QuarkusTest
class LexV2IntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeBotOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/bots/DOESNOTEXIST")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void slotTypeCreateDescribeUpdateListAndDelete() {
        String authorization = auth(EAST);
        String botName = "slottype-" + id();
        String botId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "botName":"%s",
                          "roleArn":"arn:aws:iam::000000000000:role/LexBotRole",
                          "dataPrivacy":{"childDirected":false},
                          "idleSessionTTLInSeconds":300
                        }
                        """.formatted(botName))
                .when()
                .put("/bots")
                .then()
                .statusCode(200)
                .extract().path("botId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"localeId\":\"en_US\",\"nluIntentConfidenceThreshold\":0.4}")
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales")
                .then()
                .statusCode(200);

        String slotTypeId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "slotTypeName":"Size",
                          "slotTypeValues":[
                            {"sampleValue":{"value":"small"},"synonyms":[{"value":"tiny"}]},
                            {"sampleValue":{"value":"large"},"synonyms":[{"value":"big"}]}
                          ],
                          "valueSelectionSetting":{"resolutionStrategy":"TopResolution"}
                        }
                        """)
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/slottypes")
                .then()
                .statusCode(200)
                .body("slotTypeId", notNullValue())
                .body("valueSelectionSetting.resolutionStrategy", equalTo("TopResolution"))
                .extract().path("slotTypeId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "slotTypeName":"Size",
                          "slotTypeValues":[
                            {"sampleValue":{"value":"small"},"synonyms":[{"value":"tiny"}]},
                            {"sampleValue":{"value":"medium"}},
                            {"sampleValue":{"value":"large"},"synonyms":[{"value":"big"}]}
                          ],
                          "valueSelectionSetting":{"resolutionStrategy":"TopResolution"}
                        }
                        """)
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/slottypes/" + slotTypeId)
                .then()
                .statusCode(200)
                .body("slotTypeValues.size()", equalTo(3));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"filters\":[{\"name\":\"SlotTypeName\",\"values\":[\"Size\"],\"operator\":\"EQ\"}]}")
                .when()
                .post("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/slottypes")
                .then()
                .statusCode(200)
                .body("slotTypeSummaries[0].slotTypeId", equalTo(slotTypeId));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/slottypes/" + slotTypeId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/slottypes/" + slotTypeId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void recognizeTextMatchesGreetAndFallsBackAndSessionsRoundTrip() {
        String authorization = auth(EAST);
        String botName = "alchemy-lex-" + id();

        String botId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "botName":"%s",
                          "roleArn":"arn:aws:iam::000000000000:role/LexBotRole",
                          "dataPrivacy":{"childDirected":false},
                          "idleSessionTTLInSeconds":300,
                          "botTags":{"fixture":"lex-recognize"}
                        }
                        """.formatted(botName))
                .when()
                .put("/bots")
                .then()
                .statusCode(200)
                .body("botId", notNullValue())
                .body("botStatus", equalTo("Available"))
                .extract()
                .path("botId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/bots/" + botId)
                .then()
                .statusCode(200)
                .body("botName", equalTo(botName))
                .body("idleSessionTTLInSeconds", equalTo(300));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"filters":[{"name":"BotName","values":["%s"],"operator":"EQ"}]}
                        """.formatted(botName))
                .when()
                .post("/bots")
                .then()
                .statusCode(200)
                .body("botSummaries[0].botId", equalTo(botId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "localeId":"en_US",
                          "nluIntentConfidenceThreshold":0.4
                        }
                        """)
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales")
                .then()
                .statusCode(200)
                .body("localeId", equalTo("en_US"))
                .body("botLocaleStatus", equalTo("NotBuilt"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "intentName":"Greet",
                          "sampleUtterances":[{"utterance":"hello"},{"utterance":"hi"}]
                        }
                        """)
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/intents")
                .then()
                .statusCode(200)
                .body("intentName", equalTo("Greet"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "intentName":"OrderPizza",
                          "sampleUtterances":[{"utterance":"order a pizza"}],
                          "fulfillmentCodeHook":{"enabled":true}
                        }
                        """)
                .when()
                .put("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US/intents")
                .then()
                .statusCode(200)
                .body("intentName", equalTo("OrderPizza"))
                .body("fulfillmentCodeHook.enabled", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"filters\":[{\"name\":\"BotLocaleId\",\"values\":[\"en_US\"],\"operator\":\"EQ\"}]}")
                .when()
                .post("/bots/" + botId + "/botversions/DRAFT/botlocales")
                .then()
                .statusCode(200)
                .body("botLocaleSummaries[0].localeId", equalTo("en_US"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/bots/" + botId + "/botversions/DRAFT/botlocales/en_US")
                .then()
                .statusCode(200)
                .body("botLocaleStatus", equalTo("Built"));

        String botVersion = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"botVersionLocaleSpecification":{"en_US":{"sourceBotVersion":"DRAFT"}}}
                        """)
                .when()
                .put("/bots/" + botId + "/botversions")
                .then()
                .statusCode(200)
                .body("botVersion", equalTo("1"))
                .body("botStatus", equalTo("Available"))
                .extract()
                .path("botVersion");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/bots/" + botId + "/botversions")
                .then()
                .statusCode(200)
                .body("botVersionSummaries[0].botVersion", equalTo(botVersion));

        String aliasId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "botAliasName":"Live",
                          "botVersion":"%s",
                          "tags":{"fixture":"lex-alias"}
                        }
                        """.formatted(botVersion))
                .when()
                .put("/bots/" + botId + "/botaliases")
                .then()
                .statusCode(200)
                .body("botAliasStatus", equalTo("Available"))
                .extract()
                .path("botAliasId");

        String sessionRoot = "/bots/" + botId + "/botAliases/" + aliasId + "/botLocales/en_US/sessions/";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"text\":\"hello\"}")
                .when()
                .post(sessionRoot + "greet/text")
                .then()
                .statusCode(200)
                .body("sessionState.intent.name", equalTo("Greet"))
                .body("sessionId", equalTo("greet"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"text\":\"purple monkey dishwasher\"}")
                .when()
                .post(sessionRoot + "fallback/text")
                .then()
                .statusCode(200)
                .body("sessionState.intent.name", equalTo("FallbackIntent"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "sessionState":{
                            "dialogAction":{"type":"ElicitIntent"},
                            "sessionAttributes":{"favorite":"pepperoni"}
                          },
                          "messages":[{"contentType":"PlainText","content":"How can I help?"}]
                        }
                        """)
                .when()
                .post(sessionRoot + "alchemy-e2e-session")
                .then()
                .statusCode(200)
                .header("x-amz-lex-session-id", equalTo("alchemy-e2e-session"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get(sessionRoot + "alchemy-e2e-session")
                .then()
                .statusCode(200)
                .body("sessionState.sessionAttributes.favorite", equalTo("pepperoni"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete(sessionRoot + "alchemy-e2e-session")
                .then()
                .statusCode(200)
                .body("sessionId", equalTo("alchemy-e2e-session"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get(sessionRoot + "alchemy-e2e-session")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("text/plain; charset=utf-8")
                .header("Authorization", authorization)
                .header("Response-Content-Type", "text/plain; charset=utf-8")
                .body("hello")
                .when()
                .post(sessionRoot + "utterance/utterance")
                .then()
                .statusCode(200)
                .header("x-amz-lex-session-id", equalTo("utterance"))
                .header("Content-Type", org.hamcrest.Matchers.containsString("text/plain"));
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/lex/aws4_request";
    }
}
