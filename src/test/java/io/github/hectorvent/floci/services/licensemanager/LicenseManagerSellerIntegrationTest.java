package io.github.hectorvent.floci.services.licensemanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.1 coverage for Alchemy's License Manager seller lifecycle:
 * issue → version → token mint/exchange → checkout/extend/check-in →
 * grant create/delete → delete, plus CreateGrantVersion on a missing ARN.
 */
@QuarkusTest
class LicenseManagerSellerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/license-manager/aws4_request";
    private static final String TARGET = "AWSLicenseManager.";
    private static final String BOGUS_GRANT_ARN =
            "arn:aws:license-manager::111111111111:grant:g-00000000000000000000000000000000";
    private static final String LICENSE_NAME = "alchemy-lm-seller-e2e";
    private static final String PRODUCT_SKU = "alchemy-lm-seller-sku";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listLicenseConfigurations_isKnownOperation() {
        invoke("ListLicenseConfigurations", "{\"MaxResults\":1}")
                .then()
                .statusCode(200);
    }

    @Test
    void createGrantVersion_missingGrant_returnsInvalidParameterValue() {
        invoke("CreateGrantVersion", "{"
                + "\"GrantArn\":\"" + BOGUS_GRANT_ARN + "\","
                + "\"ClientToken\":\"00000000-0000-4000-8000-000000000001\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void sellerLifecycle_issueVersionTokenCheckoutGrantAndDelete() {
        Instant now = Instant.now();
        String begin = now.minus(1, ChronoUnit.DAYS).toString();
        String end = now.plus(365, ChronoUnit.DAYS).toString();
        String bumpedEnd = now.plus(730, ChronoUnit.DAYS).toString();
        String clientToken = UUID.randomUUID().toString();

        Response created = invoke("CreateLicense", """
                {
                  "LicenseName": "%s",
                  "ProductName": "Alchemy LicenseManager Fixture",
                  "ProductSKU": "%s",
                  "Issuer": {"Name": "alchemy"},
                  "HomeRegion": "us-east-1",
                  "Validity": {"Begin": "%s", "End": "%s"},
                  "Entitlements": [{
                    "Name": "seats",
                    "MaxCount": 10,
                    "Unit": "Count",
                    "AllowCheckIn": true,
                    "Overage": false
                  }],
                  "Beneficiary": "000000000000",
                  "ConsumptionConfiguration": {
                    "RenewType": "None",
                    "ProvisionalConfiguration": {"MaxTimeToLiveInMinutes": 60}
                  },
                  "ClientToken": "%s"
                }
                """.formatted(LICENSE_NAME, PRODUCT_SKU, begin, end, clientToken));
        created.then()
                .statusCode(200)
                .body("LicenseArn", startsWith("arn:aws:license-manager::"))
                .body("Status", equalTo("AVAILABLE"))
                .body("Version", equalTo("1"));
        String licenseArn = created.jsonPath().getString("LicenseArn");

        Response got = invoke("GetLicense", "{\"LicenseArn\":\"" + licenseArn + "\"}");
        got.then()
                .statusCode(200)
                .body("License.Status", equalTo("AVAILABLE"))
                .body("License.Version", equalTo("1"));
        String fingerprint = got.jsonPath().getString("License.Issuer.KeyFingerprint");
        assertTrue(fingerprint != null && !fingerprint.isBlank());

        invoke("CreateLicenseVersion", """
                {
                  "LicenseArn": "%s",
                  "LicenseName": "%s",
                  "ProductName": "Alchemy LicenseManager Fixture",
                  "Issuer": {"Name": "alchemy"},
                  "HomeRegion": "us-east-1",
                  "Validity": {"Begin": "%s", "End": "%s"},
                  "Entitlements": [{
                    "Name": "seats",
                    "MaxCount": 10,
                    "Unit": "Count",
                    "AllowCheckIn": true,
                    "Overage": false
                  }],
                  "ConsumptionConfiguration": {
                    "RenewType": "None",
                    "ProvisionalConfiguration": {"MaxTimeToLiveInMinutes": 60}
                  },
                  "Status": "AVAILABLE",
                  "SourceVersion": "1",
                  "ClientToken": "%s"
                }
                """.formatted(licenseArn, LICENSE_NAME, begin, bumpedEnd, UUID.randomUUID()))
                .then()
                .statusCode(200)
                .body("Version", equalTo("2"))
                .body("Status", equalTo("AVAILABLE"));

        Response token = invoke("CreateToken", "{"
                + "\"LicenseArn\":\"" + licenseArn + "\","
                + "\"ClientToken\":\"" + UUID.randomUUID() + "\""
                + "}");
        token.then()
                .statusCode(200)
                .body("TokenId", org.hamcrest.Matchers.notNullValue())
                .body("TokenType", equalTo("REFRESH_TOKEN"));
        String tokenId = token.jsonPath().getString("TokenId");
        String refresh = token.jsonPath().getString("Token");

        invoke("ListTokens", "{\"TokenIds\":[\"" + tokenId + "\"]}")
                .then()
                .statusCode(200)
                .body("Tokens", hasSize(1))
                .body("Tokens[0].TokenId", equalTo(tokenId));

        invoke("GetAccessToken", "{\"Token\":\"" + refresh + "\"}")
                .then()
                .statusCode(200)
                .body("AccessToken", startsWith("lmat-"));

        invoke("DeleteToken", "{\"TokenId\":\"" + tokenId + "\"}")
                .then()
                .statusCode(200);

        Response checkout = invoke("CheckoutLicense", """
                {
                  "ProductSKU": "%s",
                  "CheckoutType": "PROVISIONAL",
                  "KeyFingerprint": "%s",
                  "Entitlements": [{"Name": "seats", "Value": "1", "Unit": "Count"}],
                  "ClientToken": "%s",
                  "Beneficiary": "000000000000"
                }
                """.formatted(PRODUCT_SKU, fingerprint, UUID.randomUUID()));
        checkout.then()
                .statusCode(200)
                .body("LicenseConsumptionToken", org.hamcrest.Matchers.notNullValue());
        String consumption = checkout.jsonPath().getString("LicenseConsumptionToken");

        invoke("ExtendLicenseConsumption",
                "{\"LicenseConsumptionToken\":\"" + consumption + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseConsumptionToken", equalTo(consumption));

        invoke("CheckInLicense", "{\"LicenseConsumptionToken\":\"" + consumption + "\"}")
                .then()
                .statusCode(200);

        Response grant = invoke("CreateGrant", """
                {
                  "GrantName": "alchemy-lm-seller-grant",
                  "LicenseArn": "%s",
                  "HomeRegion": "us-east-1",
                  "Principals": ["arn:aws:iam::000000000000:root"],
                  "AllowedOperations": [
                    "CheckoutLicense",
                    "CheckInLicense",
                    "ExtendConsumptionLicense",
                    "ListPurchasedLicenses"
                  ],
                  "ClientToken": "%s"
                }
                """.formatted(licenseArn, UUID.randomUUID()));
        grant.then()
                .statusCode(200)
                .body("GrantArn", startsWith("arn:aws:license-manager::"))
                .body("Version", equalTo("1"));
        String grantArn = grant.jsonPath().getString("GrantArn");
        String grantVersion = grant.jsonPath().getString("Version");

        invoke("DeleteGrant", "{\"GrantArn\":\"" + grantArn + "\",\"Version\":\"" + grantVersion + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("DELETED"));

        invoke("DeleteLicense", "{\"LicenseArn\":\"" + licenseArn + "\",\"SourceVersion\":\"2\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("DELETED"));

        Response listed = invoke("ListLicenses", "{}");
        listed.then().statusCode(200);
        int leftover = 0;
        var licenses = listed.jsonPath().getList("Licenses");
        if (licenses != null) {
            for (Object item : licenses) {
                if (item instanceof java.util.Map<?, ?> map
                        && LICENSE_NAME.equals(map.get("LicenseName"))
                        && "AVAILABLE".equals(map.get("Status"))) {
                    leftover++;
                }
            }
        }
        assertEquals(0, leftover);
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
