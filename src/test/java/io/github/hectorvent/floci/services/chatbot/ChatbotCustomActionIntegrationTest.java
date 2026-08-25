package io.github.hectorvent.floci.services.chatbot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies Chatbot custom-action restJson1 behaviour used by Alchemy. */
@QuarkusTest
class ChatbotCustomActionIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000830";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCustomActionOnANonexistentActionFailsWithResourceNotFoundException() {
        String missing = "arn:aws:chatbot::" + ACCOUNT + ":custom-action/alchemy-probe-nonexistent";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"CustomActionArn\":\"" + missing + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagsAndDeleteCustomAction() {
        String authorization = auth(ACCOUNT, EAST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ActionName":"alchemy-test-action",
                          "Definition":{"CommandText":"aws lambda list-functions"},
                          "Tags":[{"TagKey":"Environment","TagValue":"test"},{"TagKey":"alchemy::id","TagValue":"TestAction"}]
                        }
                        """)
                .when()
                .post("/create-custom-action")
                .then()
                .statusCode(200)
                .body("CustomActionArn", equalTo(
                        "arn:aws:chatbot::" + ACCOUNT + ":custom-action/alchemy-test-action"))
                .extract().path("CustomActionArn");
        assertEquals("arn:aws:chatbot::" + ACCOUNT + ":custom-action/alchemy-test-action", arn);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + arn + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(200)
                .body("CustomAction.Definition.CommandText", equalTo("aws lambda list-functions"))
                .body("CustomAction.AliasName", nullValue())
                .body("CustomAction.ActionName", equalTo("alchemy-test-action"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/list-tags-for-resource")
                .then()
                .statusCode(200)
                .body("Tags.find { it.TagKey == 'Environment' }.TagValue", equalTo("test"))
                .body("Tags.find { it.TagKey == 'alchemy::id' }.TagValue", equalTo("TestAction"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "CustomActionArn":"%s",
                          "Definition":{"CommandText":"aws cloudwatch describe-alarms --alarm-names $AlarmName"},
                          "AliasName":"alchemy-test-describe-alarm",
                          "Attachments":[{
                            "NotificationType":"CloudWatch",
                            "ButtonText":"Describe alarm",
                            "Criteria":[{"Operator":"HAS_VALUE","VariableName":"AlarmName"}]
                          }]
                        }
                        """.formatted(arn))
                .when()
                .post("/update-custom-action")
                .then()
                .statusCode(200)
                .body("CustomActionArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + arn + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(200)
                .body("CustomAction.Definition.CommandText",
                        equalTo("aws cloudwatch describe-alarms --alarm-names $AlarmName"))
                .body("CustomAction.AliasName", equalTo("alchemy-test-describe-alarm"))
                .body("CustomAction.Attachments[0].ButtonText", equalTo("Describe alarm"))
                .body("CustomAction.Attachments[0].Criteria[0].VariableName", equalTo("AlarmName"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "Tags":[{"TagKey":"Environment","TagValue":"production"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/tag-resource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/list-tags-for-resource")
                .then()
                .statusCode(200)
                .body("Tags.find { it.TagKey == 'Environment' }.TagValue", equalTo("production"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + arn + "\"}")
                .when()
                .post("/delete-custom-action")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + arn + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void renamingCreatesANewActionAndDeletesTheOldOne() {
        String authorization = auth("000000000831", EAST);
        String firstArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ActionName":"alchemy-test-action-a",
                          "Definition":{"CommandText":"aws s3 ls"}
                        }
                        """)
                .when()
                .post("/create-custom-action")
                .then()
                .statusCode(200)
                .extract().path("CustomActionArn");
        assertEquals("arn:aws:chatbot::000000000831:custom-action/alchemy-test-action-a", firstArn);

        String secondArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ActionName":"alchemy-test-action-b",
                          "Definition":{"CommandText":"aws s3 ls"}
                        }
                        """)
                .when()
                .post("/create-custom-action")
                .then()
                .statusCode(200)
                .extract().path("CustomActionArn");
        assertEquals("arn:aws:chatbot::000000000831:custom-action/alchemy-test-action-b", secondArn);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + firstArn + "\"}")
                .when()
                .post("/delete-custom-action")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + secondArn + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(200)
                .body("CustomAction.Definition.CommandText", equalTo("aws s3 ls"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CustomActionArn\":\"" + firstArn + "\"}")
                .when()
                .post("/get-custom-action")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/chatbot/aws4_request";
    }
}
