package io.github.hectorvent.floci.services.chatbot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Chatbot restJson1 channel-configuration operations used by Alchemy
 * {@code ChannelConfiguration.test.ts}: Slack create against an unauthorized
 * workspace fails with InvalidRequestException (and reserves the name as a
 * ConflictException tombstone), Teams create against an unconfigured team
 * fails without reserving the name, Teams get of a missing ARN is
 * ResourceNotFoundException, and Slack describe of an unknown ARN is empty.
 */
@QuarkusTest
class ChatbotChannelConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String SLACK_CREATE = """
            {
              "SlackTeamId":"T0000000000",
              "SlackChannelId":"C0000000000",
              "ConfigurationName":"%s",
              "IamRoleArn":"arn:aws:iam::000000000501:role/alchemy-nonexistent-role"
            }
            """;
    private static final String TEAMS_CREATE = """
            {
              "ChannelId":"19%%3aalchemyprobe%%40thread.tacv2",
              "TeamId":"0a1b2c3d-4e5f-1a2b-3c4d-0a1b2c3d4e5f",
              "TenantId":"1a2b3c4d-5e6f-1a2b-3c4d-1a2b3c4d5e6f",
              "ConfigurationName":"%s",
              "IamRoleArn":"arn:aws:iam::000000000502:role/alchemy-nonexistent-role"
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createSlackChannelConfigurationWithoutAnOnboardedWorkspaceFailsWithInvalidRequestException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .body(SLACK_CREATE.formatted("alchemy-chatbot-probe-slack"))
                .when()
                .post("/create-slack-channel-configuration")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidRequestException"))
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("is not authorized with AWS account"));
    }

    @Test
    void createSlackChannelConfigurationReservesTheNameAsAConflictTombstone() {
        String authorization = auth("000000000511", EAST);
        String name = "alchemy-chatbot-tombstone-slack";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(SLACK_CREATE.formatted(name))
                .when()
                .post("/create-slack-channel-configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(SLACK_CREATE.formatted(name))
                .when()
                .post("/create-slack-channel-configuration")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void createMicrosoftTeamsChannelConfigurationWithoutAConfiguredTeamFails() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000502", EAST))
                .body(TEAMS_CREATE.formatted("alchemy-chatbot-probe-teams"))
                .when()
                .post("/create-ms-teams-channel-configuration")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidRequestException"))
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("team id you are using is not configured"));
    }

    @Test
    void createMicrosoftTeamsChannelConfigurationDoesNotReserveTheName() {
        String authorization = auth("000000000512", EAST);
        String body = TEAMS_CREATE.formatted("alchemy-chatbot-repeat-teams");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/create-ms-teams-channel-configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/create-ms-teams-channel-configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("team id you are using is not configured"));
    }

    @Test
    void getMicrosoftTeamsChannelConfigurationOnANonexistentConfigurationFails() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000503", EAST))
                .body("""
                        {"ChatConfigurationArn":"arn:aws:chatbot::000000000503:chat-configuration/microsoft-teams-channel/alchemy-probe-nonexistent"}
                        """)
                .when()
                .post("/get-ms-teams-channel-configuration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeSlackChannelConfigurationsWithAnUnknownArnYieldsAnEmptyList() {
        List<Map<String, Object>> configs = given()
                .contentType("application/json")
                .header("Authorization", auth("000000000504", EAST))
                .body("""
                        {"ChatConfigurationArn":"arn:aws:chatbot::000000000504:chat-configuration/slack-channel/alchemy-probe-nonexistent"}
                        """)
                .when()
                .post("/describe-slack-channel-configurations")
                .then()
                .statusCode(200)
                .extract()
                .path("SlackChannelConfigurations");
        assertTrue(configs == null || configs.isEmpty());
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/chatbot/aws4_request";
    }
}
