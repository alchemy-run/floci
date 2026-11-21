package io.floci.aws.services.chatbot;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS Chatbot: onboarded workspaces and teams, channel configurations, custom actions and associations, tags, and
 * delivery of SNS notifications to a channel (recorded, and logged to {@code /aws/chatbot/<name>}).
 */
@QuarkusTest
class ChatbotIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @Inject
    ChatbotService service;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String path, String body) {
        return given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/chatbot/aws4_request")
                .body(body).post("/" + path).then();
    }

    private static String role() {
        String name = "chatbot-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"chatbot.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request")
                .formParam("Action", "CreateRole").formParam("Version", "2010-05-08").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200);
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    private static String topic() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request")
                .formParam("Action", "CreateTopic").formParam("Name", "alerts-" + UUID.randomUUID().toString()
                        .substring(0, 8)).post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private static void publish(String topic, String message) {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request")
                .formParam("Action", "Publish").formParam("TopicArn", topic).formParam("Message", message)
                .post("/").then().statusCode(200);
    }

    @Test
    void onlyOnboardedWorkspacesAndTeamsTakeConfigurations() {
        call("create-slack-channel-configuration", "{\"SlackTeamId\":\"T0000000000\",\"SlackChannelId\":\"C1\","
                + "\"ConfigurationName\":\"x\",\"IamRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/none\"}")
                .statusCode(400).body("message", containsString("is not authorized with AWS account"));
        call("create-ms-teams-channel-configuration", "{\"ChannelId\":\"19%3ax%40thread.tacv2\",\"TeamId\":"
                + "\"0a1b2c3d-4e5f-1a2b-3c4d-0a1b2c3d4e5f\",\"TenantId\":\"1a2b3c4d-5e6f-1a2b-3c4d-1a2b3c4d5e6f\","
                + "\"ConfigurationName\":\"x\",\"IamRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/none\"}")
                .statusCode(400).body("message", containsString("team id you are using is not configured"));
        call("describe-slack-workspaces", "{}").statusCode(200)
                .body("SlackWorkspaces.SlackTeamId", hasItem(ChatbotService.SLACK_TEAM_ID));
        call("list-ms-teams-configured-teams", "{}").statusCode(200)
                .body("ConfiguredTeams.TeamId", hasItem(ChatbotService.TEAMS_TEAM_ID));
        call("delete-ms-teams-configured-teams", "{\"TeamId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}")
                .statusCode(404);
        call("get-ms-teams-channel-configuration", "{\"ChatConfigurationArn\":\"arn:aws:chatbot::" + ACCOUNT
                + ":chat-configuration/microsoft-teams-channel/none\"}").statusCode(404);
        call("describe-slack-channel-configurations", "{\"ChatConfigurationArn\":\"arn:aws:chatbot::" + ACCOUNT
                + ":chat-configuration/slack-channel/none\"}").statusCode(200)
                .body("SlackChannelConfigurations", hasSize(0));
        call("get-account-preferences", "{}").statusCode(200)
                .body("AccountPreferences.TrainingDataCollectionEnabled", equalTo(true));
        call("update-account-preferences", "{\"TrainingDataCollectionEnabled\":false}").statusCode(200)
                .body("AccountPreferences.TrainingDataCollectionEnabled", equalTo(false));
    }

    @Test
    void aSlackChannelReceivesItsTopicsAlarms() {
        String name = "ops-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = topic();
        String arn = call("create-slack-channel-configuration", "{\"SlackTeamId\":\"" + ChatbotService.SLACK_TEAM_ID
                + "\",\"SlackChannelId\":\"C0FLOCI0001\",\"ConfigurationName\":\"" + name + "\",\"IamRoleArn\":\""
                + role() + "\",\"SnsTopicArns\":[\"" + topic + "\"],\"LoggingLevel\":\"INFO\",\"Tags\":[{\"TagKey\":"
                + "\"team\",\"TagValue\":\"ops\"}]}").statusCode(200)
                .body("ChannelConfiguration.SlackTeamName", equalTo("Floci"))
                .body("ChannelConfiguration.State", equalTo("ENABLED"))
                .body("ChannelConfiguration.GuardrailPolicyArns[0]", containsString("AdministratorAccess"))
                .extract().path("ChannelConfiguration.ChatConfigurationArn");
        assertEquals("arn:aws:chatbot::" + ACCOUNT + ":chat-configuration/slack-channel/" + name, arn);
        call("create-slack-channel-configuration", "{\"SlackTeamId\":\"" + ChatbotService.SLACK_TEAM_ID
                + "\",\"SlackChannelId\":\"C1\",\"ConfigurationName\":\"" + name + "\",\"IamRoleArn\":\"" + role()
                + "\"}").statusCode(409);

        publish(topic, "{\"AlarmName\":\"HighCPU\",\"NewStateValue\":\"ALARM\",\"NewStateReason\":\"CPU above 90%\","
                + "\"Region\":\"US East (N. Virginia)\",\"AWSAccountId\":\"" + ACCOUNT + "\"}");
        publish(topic, "just some text");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> service.notifications(arn).size() == 2);
        assertEquals("DELIVERED", service.notifications(arn).get(0).path("Status").asText());
        assertEquals("FAILED", service.notifications(arn).get(1).path("Status").asText());
        given().contentType("application/x-amz-json-1.1").header("X-Amz-Target", "Logs_20140328.FilterLogEvents")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/logs/aws4_request")
                .body("{\"logGroupName\":\"/aws/chatbot/" + name + "\"}").post("/").then().statusCode(200)
                .body("events.message", hasItem(containsString("HighCPU")));

        call("update-slack-channel-configuration", "{\"ChatConfigurationArn\":\"" + arn + "\",\"SlackChannelId\":"
                + "\"C0FLOCI0002\",\"LoggingLevel\":\"ERROR\"}").statusCode(200)
                .body("ChannelConfiguration.LoggingLevel", equalTo("ERROR"))
                .body("ChannelConfiguration.SlackChannelId", equalTo("C0FLOCI0002"))
                .body("ChannelConfiguration.SnsTopicArns[0]", equalTo(topic));
        call("list-tags-for-resource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags.TagKey", hasItem("team"));
        call("delete-slack-workspace-authorization", "{\"SlackTeamId\":\"" + ChatbotService.SLACK_TEAM_ID + "\"}")
                .statusCode(400);
        call("delete-slack-channel-configuration", "{\"ChatConfigurationArn\":\"" + arn + "\"}").statusCode(200);
        call("describe-slack-channel-configurations", "{\"ChatConfigurationArn\":\"" + arn + "\"}").statusCode(200)
                .body("SlackChannelConfigurations", hasSize(0));
        call("delete-slack-channel-configuration", "{\"ChatConfigurationArn\":\"" + arn + "\"}").statusCode(404);
    }

    @Test
    void customActionsAssociateWithConfigurations() {
        String action = "act-" + UUID.randomUUID().toString().substring(0, 8);
        String actionArn = call("create-custom-action", "{\"ActionName\":\"" + action + "\",\"Definition\":"
                + "{\"CommandText\":\"aws s3 ls\"},\"ClientToken\":\"token-0123456789abcdef0123456789abcdef\",\"Tags\":[{\"TagKey\":\"env\",\"TagValue\":"
                + "\"test\"}]}").statusCode(200).extract().path("CustomActionArn");
        assertEquals("arn:aws:chatbot::" + ACCOUNT + ":custom-action/" + action, actionArn);
        call("create-custom-action", "{\"ActionName\":\"" + action + "\",\"Definition\":{\"CommandText\":\"aws s3 ls\"},"
                + "\"ClientToken\":\"token-0123456789abcdef0123456789abcdef\"}").statusCode(200);
        call("create-custom-action", "{\"ActionName\":\"" + action + "\",\"Definition\":{\"CommandText\":\"x\"}}")
                .statusCode(409);
        call("update-custom-action", "{\"CustomActionArn\":\"" + actionArn + "\",\"Definition\":{\"CommandText\":"
                + "\"aws cloudwatch describe-alarms\"},\"AliasName\":\"alarms\",\"Attachments\":[{\"NotificationType\":"
                + "\"CloudWatch\",\"ButtonText\":\"Describe\"}]}").statusCode(200);
        call("get-custom-action", "{\"CustomActionArn\":\"" + actionArn + "\"}").statusCode(200)
                .body("CustomAction.AliasName", equalTo("alarms"))
                .body("CustomAction.Attachments[0].ButtonText", equalTo("Describe"));

        String config = call("create-ms-teams-channel-configuration", "{\"ChannelId\":\"19%3aops%40thread.tacv2\","
                + "\"TeamId\":\"" + ChatbotService.TEAMS_TEAM_ID + "\",\"TenantId\":\"" + ChatbotService.TEAMS_TENANT_ID
                + "\",\"ConfigurationName\":\"teams-" + action + "\",\"IamRoleArn\":\"" + role() + "\"}")
                .statusCode(200).body("ChannelConfiguration.TeamName", equalTo("Floci")).extract()
                .path("ChannelConfiguration.ChatConfigurationArn");
        call("associate-to-configuration", "{\"Resource\":\"" + actionArn + "\",\"ChatConfiguration\":\"" + config
                + "\"}").statusCode(200);
        call("list-associations", "{\"ChatConfiguration\":\"" + config + "\"}").statusCode(200)
                .body("Associations.Resource", hasItem(actionArn));
        call("associate-to-configuration", "{\"Resource\":\"" + actionArn + "\",\"ChatConfiguration\":\""
                + "arn:aws:chatbot::" + ACCOUNT + ":chat-configuration/slack-channel/none\"}").statusCode(404);
        call("delete-custom-action", "{\"CustomActionArn\":\"" + actionArn + "\"}").statusCode(200);
        call("list-associations", "{\"ChatConfiguration\":\"" + config + "\"}").statusCode(200)
                .body("Associations", hasSize(0));
        call("list-custom-actions", "{}").statusCode(200).body("CustomActions", not(hasItem(actionArn)));
        call("delete-ms-teams-channel-configuration", "{\"ChatConfigurationArn\":\"" + config + "\"}")
                .statusCode(200);
        call("get-ms-teams-channel-configuration", "{\"ChatConfigurationArn\":\"" + config + "\"}").statusCode(404);
    }
}
