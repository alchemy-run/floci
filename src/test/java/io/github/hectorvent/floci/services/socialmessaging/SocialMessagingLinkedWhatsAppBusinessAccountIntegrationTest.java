package io.github.hectorvent.floci.services.socialmessaging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Alchemy {@code test/AWS/SocialMessaging/LinkedWhatsAppBusinessAccount.test.ts}:
 * list succeeds without onboarding; get/disassociate on a missing id fail with
 * {@code ResourceNotFoundException}.
 */
@QuarkusTest
class SocialMessagingLinkedWhatsAppBusinessAccountIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String MISSING_ID = "waba-0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listLinkedWhatsAppBusinessAccountsSucceeds() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .when()
                .get("/v1/whatsapp/waba/list")
                .then()
                .statusCode(200)
                .body("linkedAccounts", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    @Test
    void getLinkedWhatsAppBusinessAccountOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", MISSING_ID)
                .when()
                .get("/v1/whatsapp/waba/details")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void disassociateWhatsAppBusinessAccountOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", MISSING_ID)
                .when()
                .delete("/v1/whatsapp/waba/disassociate")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateGetTagAndDisassociateRoundTrip() {
        String wabaId = "waba-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String id = given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "setupFinalization": {
                            "waba": {
                              "id": "%s",
                              "accountName": "Alchemy WABA",
                              "tags": [{"key": "fixture", "value": "socialmessaging"}]
                            }
                          }
                        }
                        """.formatted(wabaId))
                .when()
                .post("/v1/whatsapp/signup")
                .then()
                .statusCode(200)
                .body("linkedWhatsAppBusinessAccountId", equalTo(wabaId))
                .extract()
                .path("linkedWhatsAppBusinessAccountId");

        String arn = given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", id)
                .when()
                .get("/v1/whatsapp/waba/details")
                .then()
                .statusCode(200)
                .body("account.id", equalTo(id))
                .body("account.wabaName", equalTo("Alchemy WABA"))
                .body("account.arn", containsString(":waba/"))
                .body("account.arn", startsWith("arn:aws:social-messaging:"))
                .body("account.phoneNumbers", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .extract()
                .path("account.arn");

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("resourceArn", arn)
                .when()
                .get("/v1/tags/list")
                .then()
                .statusCode(200)
                .body("tags.find { it.key == 'fixture' }.value", equalTo("socialmessaging"));

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "resourceArn": "%s",
                          "tags": [{"key": "updated", "value": "true"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/v1/tags/tag-resource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "id": "%s",
                          "eventDestinations": [
                            {"eventDestinationArn": "arn:aws:sns:%s:%s:whatsapp-events"}
                          ]
                        }
                        """.formatted(id, REGION, ACCOUNT))
                .when()
                .put("/v1/whatsapp/waba/eventdestinations")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", id)
                .when()
                .get("/v1/whatsapp/waba/details")
                .then()
                .statusCode(200)
                .body("account.eventDestinations[0].eventDestinationArn", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", id)
                .when()
                .delete("/v1/whatsapp/waba/disassociate")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("id", id)
                .when()
                .get("/v1/whatsapp/waba/details")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION
                + "/social-messaging/aws4_request";
    }
}
