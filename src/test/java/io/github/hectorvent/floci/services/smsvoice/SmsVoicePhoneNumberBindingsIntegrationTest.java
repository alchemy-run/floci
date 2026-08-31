package io.github.hectorvent.floci.services.smsvoice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy PhoneNumberBindings.test.ts: SendTextMessage
 * returns a MessageId; SendVoiceMessage/SendMediaMessage on an SMS-only
 * SIMULATOR number are a typed capability conflict (not AccessDenied);
 * Put/Describe/DeleteKeyword round-trip.
 */
@QuarkusTest
class SmsVoicePhoneNumberBindingsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sms-voice/aws4_request";
    private static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";
    private static final String DESTINATION = "+14254147755";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sendText_andKeywordRoundtrip_onSimulatorNumber() {
        String arn = smsvoice("RequestPhoneNumber", """
                {
                  "IsoCountryCode": "US",
                  "MessageType": "TRANSACTIONAL",
                  "NumberCapabilities": ["SMS"],
                  "NumberType": "SIMULATOR"
                }
                """)
                .then()
                .statusCode(200)
                .extract().path("PhoneNumberArn");

        smsvoice("SendTextMessage", """
                {
                  "DestinationPhoneNumber": "%s",
                  "OriginationIdentity": "%s",
                  "MessageBody": "hello from alchemy",
                  "MessageType": "TRANSACTIONAL"
                }
                """.formatted(DESTINATION, arn))
                .then()
                .statusCode(200)
                .body("MessageId", notNullValue());

        smsvoice("SendVoiceMessage", """
                {
                  "DestinationPhoneNumber": "%s",
                  "OriginationIdentity": "%s",
                  "MessageBody": "hello from alchemy"
                }
                """.formatted(DESTINATION, arn))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        smsvoice("SendMediaMessage", """
                {
                  "DestinationPhoneNumber": "%s",
                  "OriginationIdentity": "%s",
                  "MessageBody": "hello from alchemy (mms)"
                }
                """.formatted(DESTINATION, arn))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        smsvoice("PutKeyword", """
                {
                  "OriginationIdentity": "%s",
                  "Keyword": "INFO",
                  "KeywordMessage": "Visit https://alchemy.run for details."
                }
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("Keyword", equalTo("INFO"))
                .body("KeywordMessage", equalTo("Visit https://alchemy.run for details."))
                .body("KeywordAction", equalTo("AUTOMATIC_RESPONSE"));

        smsvoice("DescribeKeywords", "{\"OriginationIdentity\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Keywords.Keyword", hasItem("INFO"));

        smsvoice("DeleteKeyword", """
                {"OriginationIdentity": "%s", "Keyword": "INFO"}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("Keyword", equalTo("INFO"));

        smsvoice("DescribeKeywords", "{\"OriginationIdentity\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Keywords.Keyword", not(hasItem("INFO")));
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
