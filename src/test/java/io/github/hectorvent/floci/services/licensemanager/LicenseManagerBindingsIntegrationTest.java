package io.github.hectorvent.floci.services.licensemanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for the Alchemy License Manager bindings suite:
 * configuration CRUD + the list/get/error paths the Lambda fixture drives.
 */
@QuarkusTest
class LicenseManagerBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/license-manager/aws4_request";
    private static final String BOGUS_ARN =
            "arn:aws:license-manager:us-east-1:000000000000:license-configuration:lic-00000000000000000000000000000000";
    private static final String BOGUS_LICENSE_ARN =
            "arn:aws:license-manager::111111111111:license:l-00000000000000000000000000000000";
    private static final String BOGUS_GRANT_ARN =
            "arn:aws:license-manager::111111111111:grant:g-00000000000000000000000000000000";
    private static final String BOGUS_RESOURCE_ARN =
            "arn:aws:ec2:us-east-1::image/ami-00000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getLicenseConfiguration_missingArn_returnsInvalidParameterValue() {
        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + BOGUS_ARN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid license configuration ARN."));
    }

    @Test
    void configurationLifecycle_createGetListUpdateTagsDelete() {
        String name = "alchemy-lm-bindings";
        String arn = lm("CreateLicenseConfiguration", "{"
                + "\"Name\":\"" + name + "\","
                + "\"Description\":\"alchemy license-manager bindings fixture\","
                + "\"LicenseCountingType\":\"vCPU\","
                + "\"LicenseCount\":5,"
                + "\"Tags\":[{\"Key\":\"Environment\",\"Value\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurationArn", notNullValue())
                .extract().path("LicenseConfigurationArn");

        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("LicenseCountingType", equalTo("vCPU"))
                .body("LicenseCount", equalTo(5))
                .body("Status", equalTo("AVAILABLE"))
                .body("Tags[0].Key", equalTo("Environment"));

        lm("ListLicenseConfigurations", "{\"MaxResults\":100}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurations.find { it.LicenseConfigurationArn == '" + arn + "' }.Name",
                        equalTo(name));

        lm("UpdateLicenseConfiguration", "{"
                + "\"LicenseConfigurationArn\":\"" + arn + "\","
                + "\"Description\":\"updated\""
                + "}")
                .then()
                .statusCode(200);

        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Description", equalTo("updated"));

        lm("TagResource", "{"
                + "\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"Owner\",\"Value\":\"alchemy\"}]"
                + "}")
                .then()
                .statusCode(200);

        lm("UntagResource", "{"
                + "\"ResourceArn\":\"" + arn + "\","
                + "\"TagKeys\":[\"Environment\"]"
                + "}")
                .then()
                .statusCode(200);

        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("Owner"));

        lm("ListAssociationsForLicenseConfiguration",
                "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurationAssociations", hasSize(0));

        lm("ListUsageForLicenseConfiguration",
                "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurationUsageList", hasSize(0));

        lm("ListFailuresForLicenseConfigurationOperations",
                "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseOperationFailureList", hasSize(0));

        lm("DeleteLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("DELETED"));

        lm("DeleteLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void bindingReads_returnEmptyCollections() {
        lm("ListLicenses", "{}")
                .then()
                .statusCode(200)
                .body("Licenses", notNullValue());
        lm("ListReceivedLicenses", "{}")
                .then()
                .statusCode(200)
                .body("Licenses", notNullValue());
        lm("ListReceivedGrants", "{}")
                .then()
                .statusCode(200)
                .body("Grants", notNullValue());
        lm("ListDistributedGrants", "{}")
                .then()
                .statusCode(200)
                .body("Grants", notNullValue());
        lm("ListResourceInventory", "{}")
                .then()
                .statusCode(200)
                .body("ResourceInventoryList", hasSize(0));
        lm("GetServiceSettings", "{}")
                .then()
                .statusCode(200)
                .body("EnableCrossAccountsDiscovery", equalTo(false));
        lm("ListLicenseSpecificationsForResource",
                "{\"ResourceArn\":\"" + BOGUS_RESOURCE_ARN + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseSpecifications", hasSize(0));
    }

    @Test
    void checkoutLicense_unknownSku_returnsResourceNotFound() {
        lm("CheckoutLicense", "{"
                + "\"ProductSKU\":\"00000000-0000-0000-0000-000000000000\","
                + "\"CheckoutType\":\"PROVISIONAL\","
                + "\"KeyFingerprint\":\"aws:294406891311:AWS/KeyManagement:v1\","
                + "\"Entitlements\":[{\"Name\":\"seats\",\"Value\":\"1\",\"Unit\":\"Count\"}],"
                + "\"ClientToken\":\"00000000-0000-4000-8000-000000000000\""
                + "}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getAccessToken_malformed_returnsInvalidParameterValue() {
        lm("GetAccessToken", "{\"Token\":\"not-a-valid-refresh-token\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void getLicense_missing_returnsResourceNotFound() {
        lm("GetLicense", "{\"LicenseArn\":\"" + BOGUS_LICENSE_ARN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getGrant_missing_returnsInvalidParameterValue() {
        lm("GetGrant", "{\"GrantArn\":\"" + BOGUS_GRANT_ARN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    private static Response lm(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSLicenseManager." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
