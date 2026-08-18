package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SesSendBounceV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";

    @Test
    void sendBounce_unverifiedSender_isMessageRejected() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", "SendBounce")
                .formParam("OriginalMessageId", "00000000-0000-0000-0000-000000000000")
                .formParam("BounceSender", "mailer-daemon@example.com")
                .formParam("BouncedRecipientInfoList.member.1.Recipient", "bounce@example.com")
                .when().post("/")
                .then().statusCode(400)
                .body(containsString("MessageRejected"))
                .body(containsString("not verified"));
    }
}
