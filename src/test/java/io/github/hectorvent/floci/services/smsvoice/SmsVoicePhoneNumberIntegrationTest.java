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
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 Pinpoint SMS Voice V2 origination numbers used by Alchemy
 * PhoneNumber.test.ts: describe on a missing id is ResourceNotFoundException;
 * request / tag / update deletion protection / release round-trip.
 */
@QuarkusTest
class SmsVoicePhoneNumberIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sms-voice/aws4_request";
    private static final String TARGET_PREFIX = "PinpointSMSVoiceV2.";
    private static final String MISSING = "phone-ffffffffffffffffffffffffffffffff";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describePhoneNumbers_missing_returnsResourceNotFoundException() {
        smsvoice("DescribePhoneNumbers",
                "{\"PhoneNumberIds\":[\"" + MISSING + "\"]}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void requestPhoneNumber_updateProtection_tag_and_release() {
        String phoneNumberId = smsvoice("RequestPhoneNumber", """
                {
                  "IsoCountryCode": "US",
                  "MessageType": "TRANSACTIONAL",
                  "NumberCapabilities": ["SMS"],
                  "NumberType": "SIMULATOR",
                  "Tags": [{"Key": "fixture", "Value": "smsvoice-phone-number"}]
                }
                """)
                .then()
                .statusCode(200)
                .body("PhoneNumberId", startsWith("phone-"))
                .body("PhoneNumberArn", startsWith("arn:aws:sms-voice:"))
                .body("PhoneNumber", startsWith("+1"))
                .body("NumberType", equalTo("SIMULATOR"))
                .body("Status", equalTo("ACTIVE"))
                .body("DeletionProtectionEnabled", equalTo(false))
                .extract().path("PhoneNumberId");

        String arn = smsvoice("DescribePhoneNumbers",
                "{\"PhoneNumberIds\":[\"" + phoneNumberId + "\"]}")
                .then()
                .statusCode(200)
                .body("PhoneNumbers[0].PhoneNumberId", equalTo(phoneNumberId))
                .body("PhoneNumbers[0].Status", equalTo("ACTIVE"))
                .extract().path("PhoneNumbers[0].PhoneNumberArn");

        smsvoice("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"))
                .body("Tags.Value", hasItem("smsvoice-phone-number"));

        smsvoice("UpdatePhoneNumber", """
                {"PhoneNumberId":"%s","DeletionProtectionEnabled":true}
                """.formatted(phoneNumberId))
                .then()
                .statusCode(200)
                .body("PhoneNumberId", equalTo(phoneNumberId))
                .body("DeletionProtectionEnabled", equalTo(true));

        smsvoice("DescribePhoneNumbers",
                "{\"PhoneNumberIds\":[\"" + phoneNumberId + "\"]}")
                .then()
                .statusCode(200)
                .body("PhoneNumbers[0].DeletionProtectionEnabled", equalTo(true));

        smsvoice("ReleasePhoneNumber", "{\"PhoneNumberId\":\"" + phoneNumberId + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        smsvoice("UpdatePhoneNumber", """
                {"PhoneNumberId":"%s","DeletionProtectionEnabled":false}
                """.formatted(phoneNumberId))
                .then()
                .statusCode(200)
                .body("DeletionProtectionEnabled", equalTo(false));

        smsvoice("ReleasePhoneNumber", "{\"PhoneNumberId\":\"" + phoneNumberId + "\"}")
                .then()
                .statusCode(200)
                .body("PhoneNumberId", equalTo(phoneNumberId));

        smsvoice("DescribePhoneNumbers",
                "{\"PhoneNumberIds\":[\"" + phoneNumberId + "\"]}")
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
