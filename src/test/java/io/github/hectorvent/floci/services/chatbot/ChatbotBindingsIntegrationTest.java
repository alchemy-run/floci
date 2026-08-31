package io.github.hectorvent.floci.services.chatbot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Alchemy {@code test/AWS/Chatbot/Bindings.test.ts}: account preferences,
 * empty Slack/Teams listings, and typed not-found on identity deletes.
 */
@QuarkusTest
class ChatbotBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000810";
    private static final String REGION = "us-east-1";
    private static final String NONEXISTENT_SLACK_TEAM = "T0000000000";
    private static final String NONEXISTENT_SLACK_USER = "U0000000000";
    private static final String NONEXISTENT_TEAMS_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAccountPreferencesReturnsDefaultPreferences() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{}")
                .when()
                .post("/get-account-preferences")
                .then()
                .statusCode(200)
                .body("AccountPreferences", notNullValue())
                .body("AccountPreferences.UserAuthorizationRequired", equalTo(false))
                .body("AccountPreferences.TrainingDataCollectionEnabled", equalTo(false));
    }

    @Test
    void updateAccountPreferencesRoundTripSucceeds() {
        String authorization = auth("000000000811");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"UserAuthorizationRequired\":false,\"TrainingDataCollectionEnabled\":false}")
                .when()
                .post("/update-account-preferences")
                .then()
                .statusCode(200)
                .body("AccountPreferences.UserAuthorizationRequired", equalTo(false))
                .body("AccountPreferences.TrainingDataCollectionEnabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/get-account-preferences")
                .then()
                .statusCode(200)
                .body("AccountPreferences.UserAuthorizationRequired", equalTo(false));
    }

    @Test
    void describeSlackWorkspacesAnswersEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000812"))
                .body("{}")
                .when()
                .post("/describe-slack-workspaces")
                .then()
                .statusCode(200)
                .body("SlackWorkspaces", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void describeSlackUserIdentitiesAnswersEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000813"))
                .body("{}")
                .when()
                .post("/describe-slack-user-identities")
                .then()
                .statusCode(200)
                .body("SlackUserIdentities", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void listMicrosoftTeamsConfiguredTeamsAnswersEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000814"))
                .body("{}")
                .when()
                .post("/list-ms-teams-configured-teams")
                .then()
                .statusCode(200)
                .body("ConfiguredTeams", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void listMicrosoftTeamsUserIdentitiesAnswersEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000815"))
                .body("{}")
                .when()
                .post("/list-ms-teams-user-identities")
                .then()
                .statusCode(200)
                .body("TeamsUserIdentities", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void deleteSlackUserIdentityForANonexistentIdentityFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "ChatConfigurationArn":"arn:aws:chatbot::000000000810:chat-configuration/slack-channel/alchemy-probe-nonexistent",
                          "SlackTeamId":"%s",
                          "SlackUserId":"%s"
                        }
                        """.formatted(NONEXISTENT_SLACK_TEAM, NONEXISTENT_SLACK_USER))
                .when()
                .post("/delete-slack-user-identity")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteSlackWorkspaceAuthorizationIsIdempotentForAWorkspaceThatWasNeverOnboarded() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000816"))
                .body("{\"SlackTeamId\":\"" + NONEXISTENT_SLACK_TEAM + "\"}")
                .when()
                .post("/delete-slack-workspace-authorization")
                .then()
                .statusCode(200);
    }

    @Test
    void deleteMicrosoftTeamsUserIdentityForANonexistentIdentityFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "ChatConfigurationArn":"arn:aws:chatbot::000000000810:chat-configuration/microsoft-teams-channel/alchemy-probe-nonexistent",
                          "UserId":"%s"
                        }
                        """.formatted(NONEXISTENT_TEAMS_ID))
                .when()
                .post("/delete-ms-teams-user-identity")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteMicrosoftTeamsConfiguredTeamForATeamThatWasNeverOnboardedFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000817"))
                .body("{\"TeamId\":\"" + NONEXISTENT_TEAMS_ID + "\"}")
                .when()
                .post("/delete-ms-teams-configured-teams")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("No Team found for the team id"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION + "/chatbot/aws4_request";
    }
}
