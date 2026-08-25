package io.github.hectorvent.floci.services.chatbot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Verifies Chatbot association restJson1 behaviour used by Alchemy. */
@QuarkusTest
class ChatbotAssociationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000801";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listAssociationsWithAnUnknownConfigurationArnYieldsAnEmptyList() {
        String missing = "arn:aws:chatbot::" + ACCOUNT
                + ":chat-configuration/slack-channel/alchemy-probe-nonexistent";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"ChatConfiguration\":\"" + missing + "\"}")
                .when()
                .post("/list-associations")
                .then()
                .statusCode(200)
                .body("Associations", hasSize(0));
    }

    @Test
    void associateToConfigurationWithAnUnknownConfigurationArnFailsWithResourceNotFoundException() {
        String missingConfig = "arn:aws:chatbot::" + ACCOUNT
                + ":chat-configuration/slack-channel/alchemy-probe-nonexistent";
        String missingAction = "arn:aws:chatbot::" + ACCOUNT + ":custom-action/alchemy-probe-nonexistent";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"ChatConfiguration\":\"" + missingConfig + "\",\"Resource\":\"" + missingAction + "\"}")
                .when()
                .post("/associate-to-configuration")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Channel Arn " + missingConfig + " does not exist!"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/chatbot/aws4_request";
    }
}
