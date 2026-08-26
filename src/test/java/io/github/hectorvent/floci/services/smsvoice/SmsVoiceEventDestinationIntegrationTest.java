package io.github.hectorvent.floci.services.smsvoice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * JSON 1.0 Pinpoint SMS Voice V2 coverage used by Alchemy
 * EventDestination.test.ts: SNS destination create / describe / update
 * (event types + enabled) / delete, nested on DescribeConfigurationSets.
 */
@QuarkusTest
class SmsVoiceEventDestinationIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sms-voice/aws4_request";
    private static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";
    private static final String TOPIC_ARN =
            "arn:aws:sns:us-east-1:000000000000:alchemy-test-smsvoice-event-dest";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void snsEventDestination_createDescribeUpdateDelete() {
        String setName = "floci-smsvoice-ed-cs-" + UUID.randomUUID().toString().substring(0, 8);
        String destName = "alchemy-test-smsvoice-event-dest";

        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + setName + "\"}")
                .then()
                .statusCode(200);

        smsvoice("CreateEventDestination", """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "%s",
                  "MatchingEventTypes": ["ALL"],
                  "SnsDestination": {"TopicArn": "%s"}
                }
                """.formatted(setName, destName, TOPIC_ARN))
                .then()
                .statusCode(200)
                .body("ConfigurationSetName", equalTo(setName))
                .body("EventDestination.EventDestinationName", equalTo(destName))
                .body("EventDestination.Enabled", equalTo(true))
                .body("EventDestination.MatchingEventTypes", hasItem("ALL"))
                .body("EventDestination.SnsDestination.TopicArn", equalTo(TOPIC_ARN));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + setName + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].EventDestinations[0].EventDestinationName",
                        equalTo(destName))
                .body("ConfigurationSets[0].EventDestinations[0].Enabled", equalTo(true))
                .body("ConfigurationSets[0].EventDestinations[0].MatchingEventTypes[0]",
                        equalTo("ALL"))
                .body("ConfigurationSets[0].EventDestinations[0].SnsDestination.TopicArn",
                        equalTo(TOPIC_ARN));

        smsvoice("UpdateEventDestination", """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "%s",
                  "Enabled": false,
                  "MatchingEventTypes": ["TEXT_ALL"],
                  "SnsDestination": {"TopicArn": "%s"}
                }
                """.formatted(setName, destName, TOPIC_ARN))
                .then()
                .statusCode(200)
                .body("EventDestination.Enabled", equalTo(false))
                .body("EventDestination.MatchingEventTypes[0]", equalTo("TEXT_ALL"));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + setName + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].EventDestinations[0].Enabled", equalTo(false))
                .body("ConfigurationSets[0].EventDestinations[0].MatchingEventTypes[0]",
                        equalTo("TEXT_ALL"));

        smsvoice("DeleteEventDestination", """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "%s"
                }
                """.formatted(setName, destName))
                .then()
                .statusCode(200)
                .body("EventDestination.EventDestinationName", equalTo(destName));

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + setName + "\"]}")
                .then()
                .statusCode(200)
                .body("ConfigurationSets[0].EventDestinations", empty());

        smsvoice("DeleteConfigurationSet", "{\"ConfigurationSetName\":\"" + setName + "\"}")
                .then()
                .statusCode(200);

        smsvoice("DescribeConfigurationSets",
                "{\"ConfigurationSetNames\":[\"" + setName + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createEventDestination_missingConfigurationSet_returnsResourceNotFound() {
        smsvoice("CreateEventDestination", """
                {
                  "ConfigurationSetName": "missing-smsvoice-cs",
                  "EventDestinationName": "dest",
                  "MatchingEventTypes": ["ALL"],
                  "SnsDestination": {"TopicArn": "%s"}
                }
                """.formatted(TOPIC_ARN))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createEventDestination_duplicateName_returnsConflictException() {
        String setName = "floci-smsvoice-ed-dup-" + UUID.randomUUID().toString().substring(0, 8);
        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + setName + "\"}")
                .then()
                .statusCode(200);
        String body = """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "events",
                  "MatchingEventTypes": ["ALL"],
                  "SnsDestination": {"TopicArn": "%s"}
                }
                """.formatted(setName, TOPIC_ARN);
        smsvoice("CreateEventDestination", body).then().statusCode(200);
        smsvoice("CreateEventDestination", body)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void createEventDestination_withoutDestination_returnsValidationException() {
        String setName = "floci-smsvoice-ed-val-" + UUID.randomUUID().toString().substring(0, 8);
        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + setName + "\"}")
                .then()
                .statusCode(200);
        smsvoice("CreateEventDestination", """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "events",
                  "MatchingEventTypes": ["ALL"]
                }
                """.formatted(setName))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void deleteEventDestination_missing_returnsResourceNotFound() {
        String setName = "floci-smsvoice-ed-miss-" + UUID.randomUUID().toString().substring(0, 8);
        smsvoice("CreateConfigurationSet", "{\"ConfigurationSetName\":\"" + setName + "\"}")
                .then()
                .statusCode(200);
        smsvoice("DeleteEventDestination", """
                {
                  "ConfigurationSetName": "%s",
                  "EventDestinationName": "missing-dest"
                }
                """.formatted(setName))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
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
