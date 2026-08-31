package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Verifies the AWS restJson1 primary-contact get/put singleton and account isolation. */
@QuarkusTest
class AccountContactInformationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String CONTACT = """
            {
              "ContactInformation": {
                "FullName": "Alchemy Test",
                "AddressLine1": "123 Any Street",
                "City": "Seattle",
                "StateOrRegion": "WA",
                "PostalCode": "98101",
                "CountryCode": "US",
                "PhoneNumber": "+12025550100",
                "CompanyName": "Alchemy"
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getContactInformationSeedsADefaultPrimaryContact() {
        String authorization = auth("000000000301", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.FullName", equalTo("Floci User"))
                .body("ContactInformation.CompanyName", equalTo("Floci"))
                .body("ContactInformation.PhoneNumber", equalTo("+12025550100"))
                .body("ContactInformation.CountryCode", equalTo("US"));
    }

    @Test
    void putThenGetRoundTripsAndUpdatesInPlace() {
        String authorization = auth("000000000302", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CONTACT)
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.FullName", equalTo("Alchemy Test"))
                .body("ContactInformation.AddressLine1", equalTo("123 Any Street"))
                .body("ContactInformation.City", equalTo("Seattle"))
                .body("ContactInformation.StateOrRegion", equalTo("WA"))
                .body("ContactInformation.PostalCode", equalTo("98101"))
                .body("ContactInformation.CountryCode", equalTo("US"))
                .body("ContactInformation.PhoneNumber", equalTo("+12025550100"))
                .body("ContactInformation.CompanyName", equalTo("Alchemy"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ContactInformation": {
                            "FullName": "Alchemy Test",
                            "AddressLine1": "123 Any Street",
                            "City": "Seattle",
                            "StateOrRegion": "WA",
                            "PostalCode": "98101",
                            "CountryCode": "US",
                            "PhoneNumber": "+12025550100",
                            "CompanyName": "Alchemy Updated"
                          }
                        }
                        """)
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.CompanyName", equalTo("Alchemy Updated"))
                .body("ContactInformation.FullName", equalTo("Alchemy Test"))
                .body("ContactInformation.WebsiteUrl", nullValue());
    }

    @Test
    void contactsAreIsolatedPerAccount() {
        String first = auth("000000000303", EAST);
        String second = auth("000000000304", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body(CONTACT)
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", second)
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.CompanyName", equalTo("Floci"));

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.CompanyName", equalTo("Alchemy"));
    }

    @Test
    void putContactInformationRejectsInvalidShapes() {
        String authorization = auth("000000000305", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ContactInformation": {
                            "FullName": "Alchemy Test",
                            "AddressLine1": "123 Any Street",
                            "City": "Seattle",
                            "PostalCode": "98101",
                            "CountryCode": "US",
                            "PhoneNumber": "2025550100"
                          }
                        }
                        """)
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "AccountId": "not-an-account",
                          "ContactInformation": {
                            "FullName": "Alchemy Test",
                            "AddressLine1": "123 Any Street",
                            "City": "Seattle",
                            "PostalCode": "98101",
                            "CountryCode": "US",
                            "PhoneNumber": "+12025550100"
                          }
                        }
                        """)
                .when()
                .post("/putContactInformation")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/account/aws4_request";
    }
}
