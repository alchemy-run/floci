package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the Account restJson1 operations Alchemy's Bindings fixture probes:
 * GetAccountInformation, GetContactInformation, GetAlternateContact (BILLING
 * may be missing), ListRegions (MaxResults 50 so us-east-1 is on the page),
 * and GetRegionOptStatus for us-east-1.
 *
 * <p>The Lambda handler posts empty or {@code {}} bodies; distilled parses
 * {@code AccountCreatedDate} as an ISO-8601 date-time.
 */
@QuarkusTest
class AccountBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000801";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAccountInformationReturnsCallerMetadata() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo(ACCOUNT))
                .body("AccountId", matchesPattern("\\d{12}"))
                .body("AccountState", equalTo("ACTIVE"))
                .body("AccountName", not(equalTo("")))
                .body("AccountName", notNullValue())
                .body("AccountCreatedDate", matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z"));
    }

    @Test
    void getAccountInformationAcceptsAnEmptyBody() {
        given()
                .header("Authorization", auth("000000000802"))
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo("000000000802"))
                .body("AccountState", equalTo("ACTIVE"));
    }

    @Test
    void getContactInformationReturnsSeededPrimaryContact() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000803"))
                .body("{}")
                .when()
                .post("/getContactInformation")
                .then()
                .statusCode(200)
                .body("ContactInformation.FullName", not(equalTo("")))
                .body("ContactInformation.FullName", notNullValue())
                .body("ContactInformation.CountryCode", not(equalTo("")))
                .body("ContactInformation.CountryCode", notNullValue());
    }

    @Test
    void getAlternateContactForUnsetBillingIsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000804"))
                .body("{\"AlternateContactType\":\"BILLING\"}")
                .when()
                .post("/getAlternateContact")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listRegionsIncludesUsEast1WhenAskingForTheMaximumPage() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000805"))
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
                .header("Authorization", auth("000000000806"))
                .body("{\"RegionName\":\"us-east-1\"}")
                .when()
                .post("/getRegionOptStatus")
                .then()
                .statusCode(200)
                .body("RegionName", equalTo("us-east-1"))
                .body("RegionOptStatus", equalTo("ENABLED_BY_DEFAULT"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + EAST + "/account/aws4_request";
    }
}
