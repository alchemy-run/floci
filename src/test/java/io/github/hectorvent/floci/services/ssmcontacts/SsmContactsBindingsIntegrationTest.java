package io.github.hectorvent.floci.services.ssmcontacts;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 coverage for the Alchemy SSM Contacts bindings suite: missing
 * engagement/channel lookups surface {@code ResourceNotFoundException}, an
 * unresolvable rotation ARN is a {@code ValidationException} ("Invalid
 * resource Arn"), and the engagement/page/override data-plane round-trips.
 */
@QuarkusTest
class SsmContactsBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ssm-contacts/aws4_request";
    private static final String ACCOUNT = "000000000000";
    private static final String ENGAGEMENT_ARN =
            "arn:aws:ssm-contacts:us-east-1:" + ACCOUNT
                    + ":engagement/alchemy-nonexistent-probe/00000000-0000-0000-0000-000000000000";
    private static final String ROTATION_ARN =
            "arn:aws:ssm-contacts:us-east-1:" + ACCOUNT + ":rotation/alchemy-nonexistent-probe";
    private static final String CHANNEL_ARN =
            "arn:aws:ssm-contacts:us-east-1:" + ACCOUNT
                    + ":contact-channel/alchemy-nonexistent-probe/11111111-1111-1111-1111-111111111111";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        contacts("ListContacts", "{}");
    }

    @Test
    void describeEngagement_missing_returnsResourceNotFound() {
        contacts("DescribeEngagement", "{\"EngagementId\":\"" + ENGAGEMENT_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo(ENGAGEMENT_ARN))
                .body("ResourceType", equalTo("engagement"));
    }

    @Test
    void listRotationShifts_unresolvableArn_returnsInvalidResourceArn() {
        long end = System.currentTimeMillis() / 1000 + 86400;
        contacts("ListRotationShifts",
                "{\"RotationId\":\"" + ROTATION_ARN + "\",\"EndTime\":" + end + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Invalid resource Arn"));
    }

    @Test
    void sendActivationCode_missingChannel_returnsResourceNotFound() {
        contacts("SendActivationCode", "{\"ContactChannelId\":\"" + CHANNEL_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo(CHANNEL_ARN))
                .body("ResourceType", equalTo("contact-channel"));
    }

    @Test
    void bindingsRoundTrip_engagementsPagesShiftsAndOverrides() {
        String contactArn = contacts("CreateContact", """
                {
                  "Alias": "bindings-oncall",
                  "DisplayName": "Bindings Fixture On-Call",
                  "Type": "PERSONAL",
                  "Plan": { "Stages": [] }
                }
                """)
                .then()
                .statusCode(200)
                .body("ContactArn", startsWith("arn:aws:ssm-contacts:"))
                .extract()
                .path("ContactArn");

        String channelArn = contacts("CreateContactChannel", """
                {
                  "ContactId": "%s",
                  "Name": "BindingsEmail",
                  "Type": "EMAIL",
                  "DeliveryAddress": { "SimpleAddress": "oncall@example.com" },
                  "DeferActivation": true
                }
                """.formatted(contactArn))
                .then()
                .statusCode(200)
                .extract()
                .path("ContactChannelArn");

        String rotationArn = contacts("CreateRotation", """
                {
                  "Name": "BindingsRotation",
                  "ContactIds": ["%s"],
                  "TimeZoneId": "America/Los_Angeles",
                  "StartTime": 1893456000,
                  "Recurrence": {
                    "NumberOfOnCalls": 1,
                    "RecurrenceMultiplier": 1,
                    "DailySettings": [{ "HourOfDay": 9, "MinuteOfHour": 0 }]
                  }
                }
                """.formatted(contactArn))
                .then()
                .statusCode(200)
                .extract()
                .path("RotationArn");

        long now = System.currentTimeMillis() / 1000;
        contacts("ListRotationShifts",
                "{\"RotationId\":\"" + rotationArn + "\",\"EndTime\":" + (now + 7 * 86400) + "}")
                .then()
                .statusCode(200)
                .body("RotationShifts", notNullValue());

        contacts("ListPreviewRotationShifts", """
                {
                  "EndTime": %d,
                  "Members": ["%s"],
                  "TimeZoneId": "America/Los_Angeles",
                  "Recurrence": {
                    "NumberOfOnCalls": 1,
                    "RecurrenceMultiplier": 1,
                    "DailySettings": [{ "HourOfDay": 9, "MinuteOfHour": 0 }]
                  }
                }
                """.formatted(now + 7 * 86400, contactArn))
                .then()
                .statusCode(200)
                .body("RotationShifts.size()", greaterThanOrEqualTo(0));

        String overrideId = contacts("CreateRotationOverride", """
                {
                  "RotationId": "%s",
                  "NewContactIds": ["%s"],
                  "StartTime": %d,
                  "EndTime": %d
                }
                """.formatted(rotationArn, contactArn, now + 3600, now + 7200))
                .then()
                .statusCode(200)
                .body("RotationOverrideId", notNullValue())
                .extract()
                .path("RotationOverrideId");

        contacts("GetRotationOverride",
                "{\"RotationId\":\"" + rotationArn + "\",\"RotationOverrideId\":\"" + overrideId + "\"}")
                .then()
                .statusCode(200)
                .body("NewContactIds[0]", equalTo(contactArn));

        contacts("ListRotationOverrides",
                "{\"RotationId\":\"" + rotationArn + "\",\"StartTime\":" + now
                        + ",\"EndTime\":" + (now + 10800) + "}")
                .then()
                .statusCode(200)
                .body("RotationOverrides.size()", greaterThanOrEqualTo(1));

        contacts("DeleteRotationOverride",
                "{\"RotationId\":\"" + rotationArn + "\",\"RotationOverrideId\":\"" + overrideId + "\"}")
                .then()
                .statusCode(200);

        String engagementArn = contacts("StartEngagement", """
                {
                  "ContactId": "%s",
                  "Sender": "alchemy-bindings-fixture",
                  "Subject": "alchemy bindings fixture engagement",
                  "Content": "Exercising the SSM Contacts runtime bindings."
                }
                """.formatted(contactArn))
                .then()
                .statusCode(200)
                .body("EngagementArn", containsString(":engagement/"))
                .extract()
                .path("EngagementArn");

        contacts("DescribeEngagement", "{\"EngagementId\":\"" + engagementArn + "\"}")
                .then()
                .statusCode(200)
                .body("Subject", equalTo("alchemy bindings fixture engagement"));

        String pageArn = contacts("ListPagesByEngagement",
                "{\"EngagementId\":\"" + engagementArn + "\"}")
                .then()
                .statusCode(200)
                .body("Pages.size()", greaterThanOrEqualTo(1))
                .extract()
                .path("Pages[0].PageArn");

        contacts("DescribePage", "{\"PageId\":\"" + pageArn + "\"}")
                .then()
                .statusCode(200)
                .body("PageArn", equalTo(pageArn));

        contacts("ListPageReceipts", "{\"PageId\":\"" + pageArn + "\"}")
                .then()
                .statusCode(200);

        contacts("ListPageResolutions", "{\"PageId\":\"" + pageArn + "\"}")
                .then()
                .statusCode(200)
                .body("PageResolutions.size()", greaterThanOrEqualTo(1));

        contacts("StopEngagement",
                "{\"EngagementId\":\"" + engagementArn + "\",\"Reason\":\"fixture done\"}")
                .then()
                .statusCode(200);

        contacts("ListEngagements", "{}")
                .then()
                .statusCode(200)
                .body("Engagements.size()", greaterThanOrEqualTo(1));

        contacts("ListPagesByContact", "{\"ContactId\":\"" + contactArn + "\"}")
                .then()
                .statusCode(200)
                .body("Pages.size()", greaterThanOrEqualTo(1));

        contacts("SendActivationCode", "{\"ContactChannelId\":\"" + channelArn + "\"}")
                .then()
                .statusCode(200);

        contacts("DeactivateContactChannel", "{\"ContactChannelId\":\"" + channelArn + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response contacts(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "SSMContacts." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
