package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

/** Verifies the AWS Account Management restJson1 alternate-contact lifecycle. */
@QuarkusTest
class AccountIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String OPS = """
            {
              "AlternateContactType":"OPERATIONS",
              "Name":"Alchemy Test Ops",
              "Title":"On-Call",
              "EmailAddress":"alchemy-test-ops@example.com",
              "PhoneNumber":"+15555550100"
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAlternateContactOnAMissingContactFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", EAST))
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putGetDeleteAlternateContactLifecycle() {
        String authorization = auth("000000000302", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(OPS)
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(200)
                .body("AlternateContact.AlternateContactType", equalTo("OPERATIONS"))
                .body("AlternateContact.Name", equalTo("Alchemy Test Ops"))
                .body("AlternateContact.Title", equalTo("On-Call"))
                .body("AlternateContact.EmailAddress", equalTo("alchemy-test-ops@example.com"))
                .body("AlternateContact.PhoneNumber", equalTo("+15555550100"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "AlternateContactType":"OPERATIONS",
                          "Name":"Ops Updated",
                          "Title":"SRE",
                          "EmailAddress":"ops-updated@example.com",
                          "PhoneNumber":"+15555550199"
                        }
                        """)
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(200)
                .body("AlternateContact.Name", equalTo("Ops Updated"))
                .body("AlternateContact.EmailAddress", equalTo("ops-updated@example.com"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/deleteAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/deleteAlternateContact")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void alternateContactsAreIsolatedByAccount() {
        String first = auth("000000000303", EAST);
        String second = auth("000000000304", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body(OPS)
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", second)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(200)
                .body("AlternateContact.EmailAddress", equalTo("alchemy-test-ops@example.com"));
    }

    @Test
    void putAlternateContactHonorsExplicitAccountId() {
        String caller = auth("000000000305", EAST);
        String memberId = "000000000306";

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("""
                        {
                          "AccountId":"%s",
                          "AlternateContactType":"BILLING",
                          "Name":"Finance",
                          "Title":"AP Clerk",
                          "EmailAddress":"ap@example.com",
                          "PhoneNumber":"+15555550124"
                        }
                        """.formatted(memberId))
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AlternateContactType\":\"BILLING\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AccountId\":\"%s\",\"AlternateContactType\":\"BILLING\"}".formatted(memberId))
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(200)
                .body("AlternateContact.Name", equalTo("Finance"))
                .body("AlternateContact.EmailAddress", equalTo("ap@example.com"));

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AccountId\":\"%s\",\"AlternateContactType\":\"BILLING\"}".formatted(memberId))
                .when()
                .post("/deleteAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AccountId\":\"%s\",\"AlternateContactType\":\"BILLING\"}".formatted(memberId))
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404);
    }

    @Test
    void putAlternateContactRejectsUnknownType() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000307", EAST))
                .body("""
                        {
                          "AlternateContactType":"LEGAL",
                          "Name":"Legal",
                          "Title":"Counsel",
                          "EmailAddress":"legal@example.com",
                          "PhoneNumber":"+15555550100"
                        }
                        """)
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void unusedContactTypeIsIndependent() {
        String authorization = auth("000000000308", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(OPS)
                .when()
                .post("/putAlternateContact")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"SECURITY\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AlternateContactType\":\"OPERATIONS\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(200)
                .body("AlternateContact.AlternateContactType", equalTo("OPERATIONS"));
    }

    @Test
    void getDoesNotLeakContactWhenBodyOmitsType() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000309", EAST))
                .body("{}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("AlternateContact", nullValue());
    }

    @Test
    void getAccountInformationReturnsCallerMetadata() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000310", EAST))
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo("000000000310"))
                .body("AccountState", equalTo("ACTIVE"))
                .body("AccountName", equalTo("Floci Account"))
                .body("AccountCreatedDate", equalTo("2017-01-01T00:00:00Z"));
    }

    @Test
    void putAccountNameIsVisibleOnGetAccountInformation() {
        String authorization = auth("000000000311", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AccountName\":\"acme-prod\"}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountName", equalTo("acme-prod"))
                .body("AccountId", equalTo("000000000311"));
    }

    @Test
    void getContactInformationReturnsSeededPrimaryContact() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000312", EAST))
                .body("{}")
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.FullName", equalTo("Floci User"))
                .body("ContactInformation.CountryCode", equalTo("US"));
    }

    @Test
    void getAlternateContactForUnsetBillingIsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000313", EAST))
                .body("{\"AlternateContactType\":\"BILLING\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listRegionsIncludesUsEast1() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000314", EAST))
                .body("{\"MaxResults\":50}")
                .when()
                .post("/listRegions")
                .then()
                .statusCode(200)
                .body("Regions.size()", greaterThanOrEqualTo(1))
                .body("Regions.RegionName", hasItem("us-east-1"));
    }

    @Test
    void getRegionOptStatusForUsEast1IsEnabledByDefault() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000315", EAST))
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionName", equalTo("us-east-1"))
                .body("RegionOptStatus", equalTo("ENABLED_BY_DEFAULT"));
    }

    @Test
    void enableRegionOptsInAnOptInRegion() {
        String authorization = auth("000000000316", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("DISABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/enableRegion")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RegionName\":\"ap-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionOptStatus", equalTo("ENABLED"));
    }

    @Test
    void disableRegionRejectsEnabledByDefaultRegions() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000317", EAST))
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/disableRegion")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/account/aws4_request";
    }
}
