package io.github.hectorvent.floci.services.licensemanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 License Manager coverage used by Alchemy's LicenseConfiguration
 * resource: list, get-not-found (synthetic InvalidParameterValueException),
 * create / update / tag / untag / soft-delete.
 */
@QuarkusTest
class LicenseManagerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/license-manager/aws4_request";
    private static final String TARGET = "AWSLicenseManager.";
    private static final String MISSING_ARN =
            "arn:aws:license-manager:us-east-1:000000000000:license-configuration:"
                    + "lic-00000000000000000000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listLicenseConfigurations_emptyOrExisting_returns200() {
        invoke("ListLicenseConfigurations", "{\"MaxResults\":1}")
                .then()
                .statusCode(200);
    }

    @Test
    void getLicenseConfiguration_unknownArn_returnsInvalidParameterValue() {
        invoke("GetLicenseConfiguration",
                "{\"LicenseConfigurationArn\":\"" + MISSING_ARN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid license configuration ARN."));
    }

    @Test
    void createGetUpdateTagAndDelete() {
        Response created = invoke("CreateLicenseConfiguration", """
                {
                  "Name": "floci-lm-fixture",
                  "Description": "alchemy license-manager test",
                  "LicenseCountingType": "vCPU",
                  "LicenseCount": 10,
                  "Tags": [{"Key": "fixture", "Value": "license-configuration"}]
                }
                """);
        created.then().statusCode(200);
        String arn = created.jsonPath().getString("LicenseConfigurationArn");
        org.junit.jupiter.api.Assertions.assertTrue(
                arn.contains(":license-configuration:lic-"));

        invoke("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo("floci-lm-fixture"))
                .body("LicenseCountingType", equalTo("vCPU"))
                .body("LicenseCount", equalTo(10))
                .body("LicenseCountHardLimit", equalTo(false))
                .body("Status", equalTo("AVAILABLE"))
                .body("LicenseConfigurationId", startsWith("lic-"))
                .body("Tags.Key", hasItem("fixture"));

        invoke("ListLicenseConfigurations",
                "{\"LicenseConfigurationArns\":[\"" + arn + "\"]}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurations", hasSize(1))
                .body("LicenseConfigurations[0].LicenseConfigurationArn", equalTo(arn));

        invoke("UpdateLicenseConfiguration", """
                {
                  "LicenseConfigurationArn": "%s",
                  "LicenseCount": 20,
                  "LicenseCountHardLimit": true,
                  "Description": "alchemy license-manager test (updated)"
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("TagResource", """
                {
                  "ResourceArn": "%s",
                  "Tags": [{"Key": "phase", "Value": "two"}]
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("LicenseCount", equalTo(20))
                .body("LicenseCountHardLimit", equalTo(true))
                .body("Description", equalTo("alchemy license-manager test (updated)"))
                .body("Tags.Key", hasItem("phase"));

        invoke("UntagResource", """
                {
                  "ResourceArn": "%s",
                  "TagKeys": ["phase"]
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("DeleteLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        invoke("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("DELETED"));

        invoke("ListLicenseConfigurations",
                "{\"LicenseConfigurationArns\":[\"" + arn + "\"]}")
                .then()
                .statusCode(200)
                .body("LicenseConfigurations", hasSize(0));

        invoke("DeleteLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
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
