package io.github.hectorvent.floci.services.socialmessaging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Social Messaging restJson1 coverage for Alchemy Bindings.test.ts: unknown
 * WABA / phone-number identifiers surface ResourceNotFoundException; linking
 * a WABA lets template listing succeed.
 */
@QuarkusTest
class SocialMessagingBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String BOGUS_WABA = "waba-0123456789abcdef0123456789abcdef";
    private static final String BOGUS_PHONE = "phone-number-id-0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listWhatsAppMessageTemplates_missingWaba_returnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("id", BOGUS_WABA)
                .when()
                .get("/v1/whatsapp/template/list")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listWhatsAppFlows_missingWaba_returnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("id", BOGUS_WABA)
                .when()
                .get("/v1/whatsapp/flow/list")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getLinkedWhatsAppBusinessAccountPhoneNumber_missingPhone_returnsResourceNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("id", BOGUS_PHONE)
                .when()
                .get("/v1/whatsapp/waba/phone/details")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void sendWhatsAppMessage_missingPhone_returnsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("""
                        {
                          "originationPhoneNumberId":"%s",
                          "metaApiVersion":"v20.0",
                          "message":"e30="
                        }
                        """.formatted(BOGUS_PHONE))
                .when()
                .post("/v1/whatsapp/send")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getWhatsAppMessageMedia_missingPhone_returnsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("""
                        {
                          "mediaId":"alchemy-nonexistent-media-id",
                          "originationPhoneNumberId":"%s",
                          "metadataOnly":true
                        }
                        """.formatted(BOGUS_PHONE))
                .when()
                .post("/v1/whatsapp/media/get")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listWhatsAppMessageTemplates_linkedWaba_returnsEmptyTemplates() {
        String authorization = auth(EAST);
        String wabaId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "setupFinalization": {
                            "associateInProgressToken": "token",
                            "phoneNumbers": [{"id":"phone-number-id-floci-bindings","twoFactorPin":"123456"}],
                            "waba": {"id":"waba-floci-bindings"}
                          }
                        }
                        """)
                .when()
                .post("/v1/whatsapp/signup")
                .then()
                .statusCode(200)
                .body("linkedWhatsAppBusinessAccountId", equalTo("waba-floci-bindings"))
                .extract()
                .path("linkedWhatsAppBusinessAccountId");

        given()
                .header("Authorization", authorization)
                .queryParam("id", wabaId)
                .when()
                .get("/v1/whatsapp/template/list")
                .then()
                .statusCode(200)
                .body("templates", empty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "originationPhoneNumberId":"phone-number-id-floci-bindings",
                          "metaApiVersion":"v20.0",
                          "message":"e30="
                        }
                        """)
                .when()
                .post("/v1/whatsapp/send")
                .then()
                .statusCode(200)
                .body("messageId", notNullValue());
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/social-messaging/aws4_request";
    }
}
