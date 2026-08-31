package io.github.hectorvent.floci.services.smsvoice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * JSON 1.0 Pinpoint SMS Voice V2 coverage used by Alchemy
 * ConfigurationSet.test.ts: describe on a missing name is
 * ResourceNotFoundException; create / default message type / tags / delete
 * round-trip.
 */
@QuarkusTest
class SmsVoiceConfigurationSetIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sms-voice/aws4_request";
    private static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";
    private static final String MISSING = "alchemy-nonexistent-config-set-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeConfigurationSets_missing_returnsResourceNotFoundException() {
        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + MISSING + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void configurationSet_lifecycle_defaultMessageType_and_tags() {
        String name = "floci-smsvoice-cs-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = smsvoice("CreateConfigurationSet", """
                {
                  "ConfigurationSetName": "%s",
                  "Tags": [{"Key": "fixture", "Value": "smsvoice-config-set"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("ConfigurationSetName", equalTo(name))
                .body("ConfigurationSetArn", containsString(":configuration-set/"))
                .body("CreatedTimestamp", notNullValue())
                .extract().path("ConfigurationSetArn");

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].ConfigurationSetName", equalTo(name))
                .body("ConfigurationSets[0].ConfigurationSetArn", equalTo(arn))
                .body("ConfigurationSets[0].DefaultMessageType", nullValue());

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Value", hasItem("smsvoice-config-set"));

        smsvoice("SetDefaultMessageType", """
                {"ConfigurationSetName": "%s", "MessageType": "TRANSACTIONAL"}
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("MessageType", equalTo("TRANSACTIONAL"));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].DefaultMessageType", equalTo("TRANSACTIONAL"));

        smsvoice("TagResource", """
                {
                  "ResourceArn": "%s",
                  "Tags": [{"Key": "fixture", "Value": "smsvoice-config-set-v2"}]
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Value", hasItem("smsvoice-config-set-v2"));

        smsvoice("DeleteDefaultMessageType",
                "{\"ConfigurationSetName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("MessageType", equalTo("TRANSACTIONAL"));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].DefaultMessageType", nullValue());

        smsvoice("DeleteConfigurationSet",
                "{\"ConfigurationSetName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ConfigurationSetName", equalTo(name));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + name + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createConfigurationSet_duplicateName_returnsConflictException() {
        String name = "floci-smsvoice-dup-" + UUID.randomUUID().toString().substring(0, 8);
        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + name + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void untagResource_removesKey() {
        String name = "floci-smsvoice-untag-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = smsvoice("CreateConfigurationSet", """
                {
                  "ConfigurationSetName": "%s",
                  "Tags": [
                    {"Key": "keep", "Value": "yes"},
                    {"Key": "drop", "Value": "no"}
                  ]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .extract().path("ConfigurationSetArn");

        smsvoice("UntagResource", """
                {"ResourceArn": "%s", "TagKeys": ["drop"]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("keep"))
                .body("Tags.Key", not(hasItem("drop")));
    }

    private static Response smsvoice(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
