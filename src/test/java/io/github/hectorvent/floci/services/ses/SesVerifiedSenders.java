package io.github.hectorvent.floci.services.ses;

import static io.restassured.RestAssured.given;

/**
 * Verifies sender addresses through {@code VerifyEmailIdentity}. Floci keeps a created email identity
 * pending, as SES does until its link is clicked, and refuses to send from an address no verify call
 * registered.
 */
final class SesVerifiedSenders {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    private SesVerifiedSenders() {
    }

    static void verify(String... addresses) {
        for (String address : addresses) {
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", SES_AUTH)
                    .formParam("Action", "VerifyEmailIdentity")
                    .formParam("EmailAddress", address)
            .when()
                    .post("/")
            .then()
                    .statusCode(200);
        }
    }
}
