package io.github.hectorvent.floci.services.ssmcontacts;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 SSM Contacts coverage used by Alchemy Contact.test.ts:
 * {@code GetContact} typed {@code ResourceNotFoundException}, plus contact /
 * channel / plan / rotation / tags / policy round-trip.
 */
@QuarkusTest
class SsmContactsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ssm-contacts/aws4_request";
    private static final String MISSING_ARN =
            "arn:aws:ssm-contacts:us-east-1:000000000000:contact/alchemy-nonexistent-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getContact_missingArn_returnsResourceNotFoundException() {
        contacts("GetContact", "{\"ContactId\":\"" + MISSING_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void contactChannelPlanRotationPolicyAndTags() {
        String alias = "alchemy-ssmcontacts-oncall";
        String contactArn = contacts("CreateContact", "{"
                + "\"Alias\":\"" + alias + "\","
                + "\"DisplayName\":\"Primary On-Call\","
                + "\"Type\":\"PERSONAL\","
                + "\"Plan\":{\"Stages\":[]},"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"ssm-contacts\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("ContactArn", notNullValue())
                .extract().path("ContactArn");

        contacts("GetContact", "{\"ContactId\":\"" + contactArn + "\"}")
                .then()
                .statusCode(200)
                .body("Alias", equalTo(alias))
                .body("DisplayName", equalTo("Primary On-Call"))
                .body("Type", equalTo("PERSONAL"));

        String channelArn = contacts("CreateContactChannel", "{"
                + "\"ContactId\":\"" + contactArn + "\","
                + "\"Name\":\"Email\","
                + "\"Type\":\"EMAIL\","
                + "\"DeliveryAddress\":{\"SimpleAddress\":\"oncall@example.com\"},"
                + "\"DeferActivation\":true"
                + "}")
                .then()
                .statusCode(200)
                .body("ContactChannelArn", notNullValue())
                .extract().path("ContactChannelArn");

        contacts("GetContactChannel", "{\"ContactChannelId\":\"" + channelArn + "\"}")
                .then()
                .statusCode(200)
                .body("Type", equalTo("EMAIL"))
                .body("DeliveryAddress.SimpleAddress", equalTo("oncall@example.com"))
                .body("ActivationStatus", equalTo("NOT_ACTIVATED"));

        contacts("UpdateContact", "{"
                + "\"ContactId\":\"" + contactArn + "\","
                + "\"DisplayName\":\"Secondary On-Call\","
                + "\"Plan\":{\"Stages\":[{\"DurationInMinutes\":10,\"Targets\":[{"
                + "\"ChannelTargetInfo\":{\"ContactChannelId\":\"" + channelArn + "\",\"RetryIntervalInMinutes\":2}"
                + "}]}]}"
                + "}")
                .then()
                .statusCode(200);

        contacts("GetContact", "{\"ContactId\":\"" + contactArn + "\"}")
                .then()
                .statusCode(200)
                .body("DisplayName", equalTo("Secondary On-Call"))
                .body("Plan.Stages[0].DurationInMinutes", equalTo(10));

        contacts("UpdateContactChannel", "{"
                + "\"ContactChannelId\":\"" + channelArn + "\","
                + "\"DeliveryAddress\":{\"SimpleAddress\":\"standby@example.com\"}"
                + "}")
                .then()
                .statusCode(200);

        contacts("GetContactChannel", "{\"ContactChannelId\":\"" + channelArn + "\"}")
                .then()
                .statusCode(200)
                .body("DeliveryAddress.SimpleAddress", equalTo("standby@example.com"));

        String rotationArn = contacts("CreateRotation", "{"
                + "\"Name\":\"alchemy-ssmcontacts-primary\","
                + "\"ContactIds\":[\"" + contactArn + "\"],"
                + "\"TimeZoneId\":\"America/Los_Angeles\","
                + "\"StartTime\":1893456000,"
                + "\"Recurrence\":{\"NumberOfOnCalls\":1,\"RecurrenceMultiplier\":1,"
                + "\"DailySettings\":[{\"HourOfDay\":9,\"MinuteOfHour\":0}]}"
                + "}")
                .then()
                .statusCode(200)
                .body("RotationArn", notNullValue())
                .extract().path("RotationArn");

        contacts("GetRotation", "{\"RotationId\":\"" + rotationArn + "\"}")
                .then()
                .statusCode(200)
                .body("TimeZoneId", equalTo("America/Los_Angeles"))
                .body("Recurrence.DailySettings[0].HourOfDay", equalTo(9));

        contacts("UpdateRotation", "{"
                + "\"RotationId\":\"" + rotationArn + "\","
                + "\"ContactIds\":[\"" + contactArn + "\"],"
                + "\"TimeZoneId\":\"America/Los_Angeles\","
                + "\"Recurrence\":{\"NumberOfOnCalls\":1,\"RecurrenceMultiplier\":1,"
                + "\"DailySettings\":[{\"HourOfDay\":10,\"MinuteOfHour\":30}]}"
                + "}")
                .then()
                .statusCode(200);

        contacts("GetRotation", "{\"RotationId\":\"" + rotationArn + "\"}")
                .then()
                .statusCode(200)
                .body("Recurrence.DailySettings[0].HourOfDay", equalTo(10));

        contacts("TagResource", "{"
                + "\"ResourceARN\":\"" + contactArn + "\","
                + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200);

        contacts("ListTagsForResource", "{\"ResourceARN\":\"" + contactArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"000000000000\"},\"Action\":[\"ssm-contacts:GetContact\"],"
                + "\"Resource\":[\"" + contactArn + "\"]}]}";
        contacts("PutContactPolicy", "{\"ContactArn\":\"" + contactArn + "\",\"Policy\":"
                + toJsonString(policy) + "}")
                .then()
                .statusCode(200);

        contacts("GetContactPolicy", "{\"ContactArn\":\"" + contactArn + "\"}")
                .then()
                .statusCode(200)
                .body("Policy", org.hamcrest.Matchers.containsString("ssm-contacts:GetContact"));

        contacts("DeleteRotation", "{\"RotationId\":\"" + rotationArn + "\"}").then().statusCode(200);
        contacts("DeleteContactChannel", "{\"ContactChannelId\":\"" + channelArn + "\"}")
                .then().statusCode(200);
        contacts("DeleteContact", "{\"ContactId\":\"" + contactArn + "\"}").then().statusCode(200);

        contacts("GetContact", "{\"ContactId\":\"" + contactArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        contacts("GetRotation", "{\"RotationId\":\"" + rotationArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String toJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
