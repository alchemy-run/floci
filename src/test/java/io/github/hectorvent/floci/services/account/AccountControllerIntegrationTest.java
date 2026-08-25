package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies AWS Account restJson1 GetAccountInformation / PutAccountName. */
@QuarkusTest
class AccountControllerIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAccountInformationReturnsDefaultNameForANewAccount() {
        String authorization = auth("000000000401", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo("000000000401"))
                .body("AccountName", equalTo(AccountService.DEFAULT_ACCOUNT_NAME))
                .body("AccountCreatedDate", equalTo(AccountService.DEFAULT_CREATED_DATE))
                .body("AccountState", equalTo(AccountService.DEFAULT_ACCOUNT_STATE));
    }

    @Test
    void putAccountNameThenGetAccountInformationRoundTripsTheDisplayName() {
        String authorization = auth("000000000402", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AccountName\":\"alchemy-test-account-name\"}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountName", equalTo("alchemy-test-account-name"))
                .body("AccountId", equalTo("000000000402"))
                .body("AccountCreatedDate", notNullValue())
                .body("AccountState", equalTo("ACTIVE"));
    }

    @Test
    void putAccountNameRejectsMissingAndOversizedNames() {
        String authorization = auth("000000000403", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AccountName\":\"" + "n".repeat(51) + "\"}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void accountNamesAreIsolatedPerCallingAccount() {
        String first = auth("000000000404", EAST);
        String second = auth("000000000405", EAST);
        putName(first, "first-account");
        putName(second, "second-account");

        given()
                .contentType("application/json")
                .header("Authorization", first)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountName", equalTo("first-account"))
                .body("AccountId", equalTo("000000000404"));

        given()
                .contentType("application/json")
                .header("Authorization", second)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountName", equalTo("second-account"))
                .body("AccountId", equalTo("000000000405"));
    }

    @Test
    void putAccountNameWithAccountIdTargetsThatAccount() {
        String caller = auth("000000000406", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AccountName\":\"member-name\",\"AccountId\":\"000000000407\"}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{\"AccountId\":\"000000000407\"}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo("000000000407"))
                .body("AccountName", equalTo("member-name"));

        given()
                .contentType("application/json")
                .header("Authorization", caller)
                .body("{}")
                .when()
                .post("/getAccountInformation")
                .then()
                .statusCode(200)
                .body("AccountId", equalTo("000000000406"))
                .body("AccountName", equalTo(AccountService.DEFAULT_ACCOUNT_NAME));
    }

    private static void putName(String authorization, String name) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"AccountName\":\"" + name + "\"}")
                .when()
                .post("/putAccountName")
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/account/aws4_request";
    }
}
