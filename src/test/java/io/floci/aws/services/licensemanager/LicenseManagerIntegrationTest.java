package io.floci.aws.services.licensemanager;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * License Manager: license configurations with rules, associations, usage and hard limits; seller licenses with
 * versions, tokens, checkouts, borrows and grants across accounts; asset rulesets and groups; settings and reports.
 */
@QuarkusTest
class LicenseManagerIntegrationTest {

    private static final String SELLER = "000000000000";
    private static final String BUYER = "111111111111";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse lm(String account, String action, String body) {
        return given().header("Authorization", auth(account, "license-manager"))
                .header("X-Amz-Target", "AWSLicenseManager." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().log().ifValidationFails();
    }

    private static ValidatableResponse lm(String action, String body) {
        return lm(SELLER, action, body);
    }

    @Test
    void configurationsCountTheirResources() {
        String arn = lm("CreateLicenseConfiguration", "{\"Name\":\"sql\",\"LicenseCountingType\":\"vCPU\","
                + "\"LicenseCount\":3,\"LicenseCountHardLimit\":true,\"LicenseRules\":[\"#minimumVcpus=2\"],"
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"db\"}]}").statusCode(200)
                .body("LicenseConfigurationArn", containsString(":license-configuration:lic-")).extract()
                .path("LicenseConfigurationArn");
        lm("CreateLicenseConfiguration", "{\"Name\":\"bad\",\"LicenseCountingType\":\"Instance\","
                + "\"LicenseRules\":[\"#minimumVcpus=2\"]}").statusCode(400)
                .body("__type", containsString("InvalidParameterValueException"));
        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}").statusCode(200)
                .body("LicenseCountingType", equalTo("vCPU")).body("LicenseCount", equalTo(3))
                .body("Status", equalTo("AVAILABLE")).body("Tags[0].Value", equalTo("db"))
                .body("ConsumedLicenses", equalTo(0));
        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"arn:aws:license-manager:us-east-1:"
                + SELLER + ":license-configuration:lic-00000000000000000000000000000000\"}").statusCode(400)
                .body("message", containsString("Invalid license configuration ARN"));

        String host = "arn:aws:ec2:us-east-1:" + SELLER + ":dedicated-host/h-0123456789abcdef0";
        lm("UpdateLicenseSpecificationsForResource", "{\"ResourceArn\":\"" + host + "\",\"AddLicenseSpecifications\":["
                + "{\"LicenseConfigurationArn\":\"" + arn + "\"}]}").statusCode(200);
        lm("ListLicenseSpecificationsForResource", "{\"ResourceArn\":\"" + host + "\"}").statusCode(200)
                .body("LicenseSpecifications[0].LicenseConfigurationArn", equalTo(arn));
        lm("ListUsageForLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .body("LicenseConfigurationUsageList[0].ConsumedLicenses", equalTo(2))
                .body("LicenseConfigurationUsageList[0].ResourceType", equalTo("EC2_HOST"));
        String second = "arn:aws:ec2:us-east-1:" + SELLER + ":dedicated-host/h-0fedcba9876543210";
        lm("UpdateLicenseSpecificationsForResource", "{\"ResourceArn\":\"" + second
                + "\",\"AddLicenseSpecifications\":[{\"LicenseConfigurationArn\":\"" + arn + "\"}]}").statusCode(400)
                .body("__type", containsString("LicenseUsageException"));
        lm("ListFailuresForLicenseConfigurationOperations", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .body("LicenseOperationFailureList", hasSize(1))
                .body("LicenseOperationFailureList[0].ResourceArn", equalTo(second));
        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .body("ConsumedLicenses", equalTo(2)).body("ManagedResourceSummaryList[0].AssociationCount",
                        equalTo(1));
        lm("ListAssociationsForLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}")
                .body("LicenseConfigurationAssociations", hasSize(1));

        lm("UpdateLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\",\"LicenseCount\":10,"
                + "\"Description\":\"more\"}").statusCode(200);
        lm("ListLicenseConfigurations", "{\"Filters\":[{\"Name\":\"licenseCountingType\",\"Values\":[\"vCPU\"]}]}")
                .body("LicenseConfigurations.LicenseConfigurationArn", hasItem(arn));
        lm("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .statusCode(200);
        lm("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").statusCode(200);
        lm("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("env"));
        lm("DeleteLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}").statusCode(200);
        lm("GetLicenseConfiguration", "{\"LicenseConfigurationArn\":\"" + arn + "\"}").statusCode(400);
    }

    private static String licenseBody(String name, String sku, String beneficiary) {
        Instant now = Instant.now();
        return "{\"LicenseName\":\"" + name + "\",\"ProductName\":\"Widget\",\"ProductSKU\":\"" + sku + "\","
                + "\"Issuer\":{\"Name\":\"acme\"},\"HomeRegion\":\"us-east-1\",\"Validity\":{\"Begin\":\""
                + now.minus(1, ChronoUnit.DAYS) + "\",\"End\":\"" + now.plus(365, ChronoUnit.DAYS) + "\"},"
                + "\"Entitlements\":[{\"Name\":\"seats\",\"MaxCount\":2,\"Unit\":\"Count\",\"AllowCheckIn\":true}],"
                + "\"Beneficiary\":\"" + beneficiary + "\",\"ConsumptionConfiguration\":{\"RenewType\":\"None\","
                + "\"ProvisionalConfiguration\":{\"MaxTimeToLiveInMinutes\":60},\"BorrowConfiguration\":{"
                + "\"AllowEarlyCheckIn\":true,\"MaxTimeToLiveInMinutes\":120}},\"ClientToken\":\"" + name + "\"}";
    }

    @Test
    void sellsLicensesAndConsumesThem() {
        String body = licenseBody("widget", "sku-1", SELLER);
        String arn = lm("CreateLicense", body).statusCode(200)
                .body("Status", equalTo("PENDING_AVAILABLE")).body("Version", equalTo("1"))
                .body("LicenseArn", startsWith("arn:aws:license-manager::" + SELLER + ":license:l-")).extract()
                .path("LicenseArn");
        lm("CreateLicense", body).statusCode(200).body("LicenseArn", equalTo(arn));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lm("GetLicense", "{\"LicenseArn\":\"" + arn
                + "\"}").statusCode(200).body("License.Status", equalTo("AVAILABLE")));
        String fingerprint = lm("GetLicense", "{\"LicenseArn\":\"" + arn + "\"}").extract()
                .path("License.Issuer.KeyFingerprint");
        assertEquals("aws:" + SELLER + ":acme:issuer-fingerprint", fingerprint);

        String version = licenseBody("widget", "sku-1", SELLER).replace("\"ClientToken\":\"widget\"",
                "\"ClientToken\":\"v2\",\"LicenseArn\":\"" + arn + "\",\"SourceVersion\":\"1\",\"Status\":"
                        + "\"AVAILABLE\"").replace(",\"Beneficiary\":\"" + SELLER + "\"", "")
                .replace(",\"ProductSKU\":\"sku-1\"", "");
        lm("CreateLicenseVersion", version).statusCode(200).body("Version", equalTo("2"));
        lm("CreateLicenseVersion", version.replace("\"v2\"", "\"v3\"")).statusCode(400)
                .body("__type", containsString("ConflictException"));
        lm("ListLicenseVersions", "{\"LicenseArn\":\"" + arn + "\"}").body("Licenses", hasSize(2));

        String token = lm("CreateToken", "{\"LicenseArn\":\"" + arn + "\",\"ClientToken\":\"t1\",\"TokenProperties\":"
                + "[\"node\"]}").statusCode(200).body("TokenType", equalTo("REFRESH_TOKEN")).extract().path("Token");
        String access = lm("GetAccessToken", "{\"Token\":\"" + token + "\"}").statusCode(200).extract()
                .path("AccessToken");
        String claims = new String(Base64.getUrlDecoder().decode(access.split("\\.")[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("ACCESS_TOKEN") && claims.contains(arn), claims);
        lm("GetAccessToken", "{\"Token\":\"not-a-token\"}").statusCode(400)
                .body("__type", containsString("InvalidParameterValueException"));
        String tokenId = lm("ListTokens", "{}").body("Tokens", hasSize(1)).extract().path("Tokens[0].TokenId");
        lm("DeleteToken", "{\"TokenId\":\"" + tokenId + "\"}").statusCode(200);
        lm("GetAccessToken", "{\"Token\":\"" + token + "\"}").statusCode(400)
                .body("__type", containsString("AuthorizationException"));

        String checkout = "{\"ProductSKU\":\"sku-1\",\"CheckoutType\":\"PROVISIONAL\",\"KeyFingerprint\":\""
                + fingerprint + "\",\"Entitlements\":[{\"Name\":\"seats\",\"Value\":\"2\",\"Unit\":\"Count\"}],"
                + "\"ClientToken\":\"c1\"}";
        String consumption = lm("CheckoutLicense", checkout).statusCode(200).body("LicenseArn", equalTo(arn))
                .body("SignedToken", notNullValue()).body("Expiration", notNullValue()).extract()
                .path("LicenseConsumptionToken");
        lm("CheckoutLicense", checkout.replace("\"c1\"", "\"c2\"").replace("\"Value\":\"2\"", "\"Value\":\"1\""))
                .statusCode(400).body("__type", containsString("EntitlementNotAllowedException"));
        lm("CheckoutLicense", checkout.replace("\"c1\"", "\"c3\"").replace("seats", "cores")).statusCode(400)
                .body("__type", containsString("NoEntitlementsAllowedException"));
        lm("CheckoutLicense", checkout.replace("sku-1", "sku-none").replace("\"c1\"", "\"c4\"")).statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        lm("GetLicenseUsage", "{\"LicenseArn\":\"" + arn + "\"}").body("LicenseUsage.EntitlementUsages[0]"
                + ".ConsumedValue", equalTo("2"));
        lm("ExtendLicenseConsumption", "{\"LicenseConsumptionToken\":\"" + consumption + "\"}").statusCode(200)
                .body("LicenseConsumptionToken", equalTo(consumption));
        lm("DeleteLicense", "{\"LicenseArn\":\"" + arn + "\",\"SourceVersion\":\"2\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        lm("CheckInLicense", "{\"LicenseConsumptionToken\":\"" + consumption + "\"}").statusCode(200);
        lm("GetLicenseUsage", "{\"LicenseArn\":\"" + arn + "\"}").body("LicenseUsage.EntitlementUsages[0]"
                + ".ConsumedValue", equalTo("0"));

        String borrow = lm("CheckoutBorrowLicense", "{\"LicenseArn\":\"" + arn + "\",\"Entitlements\":[{\"Name\":"
                + "\"seats\",\"Value\":\"1\",\"Unit\":\"Count\"}],\"DigitalSignatureMethod\":\"JWT_PS384\",\"NodeId\":"
                + "\"node-1\",\"ClientToken\":\"b1\"}").statusCode(200).body("NodeId", equalTo("node-1")).extract()
                .path("LicenseConsumptionToken");
        lm("CheckInLicense", "{\"LicenseConsumptionToken\":\"" + borrow + "\"}").statusCode(200);

        lm("DeleteLicense", "{\"LicenseArn\":\"" + arn + "\",\"SourceVersion\":\"2\"}").statusCode(200)
                .body("Status", equalTo("DELETED"));
        lm("ListLicenses", "{\"Filters\":[{\"Name\":\"Status\",\"Values\":[\"AVAILABLE\"]}]}")
                .body("Licenses.LicenseArn", not(hasItem(arn)));
    }

    @Test
    void grantsLicensesToAnotherAccount() {
        String arn = lm("CreateLicense", licenseBody("shared", "sku-2", SELLER)).statusCode(200).extract()
                .path("LicenseArn");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lm("GetLicense", "{\"LicenseArn\":\"" + arn
                + "\"}").body("License.Status", equalTo("AVAILABLE")));
        String grant = lm("CreateGrant", "{\"ClientToken\":\"g1\",\"GrantName\":\"to-buyer\",\"LicenseArn\":\"" + arn
                + "\",\"Principals\":[\"arn:aws:iam::" + BUYER + ":root\"],\"HomeRegion\":\"us-east-1\","
                + "\"AllowedOperations\":[\"CheckoutLicense\",\"CheckInLicense\",\"ListPurchasedLicenses\"]}")
                .statusCode(200).body("Status", equalTo("PENDING_ACCEPT")).extract().path("GrantArn");
        lm(BUYER, "ListReceivedGrants", "{}").body("Grants.GrantArn", hasItem(grant));
        lm(BUYER, "GetLicense", "{\"LicenseArn\":\"" + arn + "\"}").statusCode(400)
                .body("__type", containsString("AuthorizationException"));
        lm(BUYER, "AcceptGrant", "{\"GrantArn\":\"" + grant + "\"}").statusCode(200).body("Status", equalTo("ACTIVE"));
        lm(BUYER, "ListReceivedLicenses", "{}").body("Licenses.LicenseArn", hasItem(arn))
                .body("Licenses[0].ReceivedMetadata.ReceivedStatus", equalTo("ACTIVE"));
        String fingerprint = lm("GetLicense", "{\"LicenseArn\":\"" + arn + "\"}").extract()
                .path("License.Issuer.KeyFingerprint");
        lm(BUYER, "CheckoutLicense", "{\"ProductSKU\":\"sku-2\",\"CheckoutType\":\"PROVISIONAL\",\"KeyFingerprint\":\""
                + fingerprint + "\",\"Entitlements\":[{\"Name\":\"seats\",\"Value\":\"1\",\"Unit\":\"Count\"}],"
                + "\"ClientToken\":\"buyer-1\"}").statusCode(200).body("LicenseArn", equalTo(arn));
        lm("ListDistributedGrants", "{\"Filters\":[{\"Name\":\"GrantStatus\",\"Values\":[\"ACTIVE\"]}]}")
                .body("Grants.GrantArn", hasItem(grant));
        lm("CreateGrantVersion", "{\"ClientToken\":\"gv1\",\"GrantArn\":\"" + grant + "\",\"Status\":\"DISABLED\","
                + "\"SourceVersion\":\"1\"}").statusCode(200).body("Version", equalTo("2"));
        lm("GetGrant", "{\"GrantArn\":\"" + grant + "\",\"Version\":\"1\"}").body("Grant.GrantStatus",
                equalTo("PENDING_ACCEPT"));
        lm("CreateGrantVersion", "{\"ClientToken\":\"gv2\",\"GrantArn\":\"arn:aws:license-manager::" + SELLER
                + ":grant:g-00000000000000000000000000000000\"}").statusCode(400)
                .body("__type", containsString("InvalidParameterValueException"));
        lm("GetGrant", "{\"GrantArn\":\"arn:aws:license-manager::" + SELLER
                + ":grant:g-00000000000000000000000000000000\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        lm("DeleteGrant", "{\"GrantArn\":\"" + grant + "\",\"Version\":\"2\"}").statusCode(200)
                .body("Status", equalTo("DELETED"));
    }

    @Test
    void assetGroupsReportsAndSettings() {
        lm("GetServiceSettings", "{}").statusCode(200).body("OrganizationConfiguration.EnableIntegration",
                equalTo(false));
        lm("ListLicenseConfigurationsForOrganization", "{}").statusCode(400)
                .body("__type", containsString("AccessDeniedException"));
        given().header("Authorization", auth(SELLER, "s3")).put("/lm-reports").then().statusCode(anyOf(equalTo(200),
                equalTo(409)));
        lm("UpdateServiceSettings", "{\"S3BucketArn\":\"arn:aws:s3:::lm-reports\",\"SnsTopicArn\":\"arn:aws:sns:"
                + "us-east-1:" + SELLER + ":lm\"}").statusCode(200);
        lm("GetServiceSettings", "{}").body("S3BucketArn", equalTo("arn:aws:s3:::lm-reports"));

        String config = lm("CreateLicenseConfiguration", "{\"Name\":\"reported\",\"LicenseCountingType\":"
                + "\"Instance\",\"LicenseCount\":5}").extract().path("LicenseConfigurationArn");
        String generator = lm("CreateLicenseManagerReportGenerator", "{\"ReportGeneratorName\":\"weekly\",\"Type\":["
                + "\"LicenseConfigurationSummaryReport\"],\"ReportContext\":{\"licenseConfigurationArns\":[\""
                + config + "\"]},\"ReportFrequency\":{\"value\":1,\"period\":\"WEEK\"},\"ClientToken\":\"r1\"}")
                .statusCode(200).extract().path("LicenseManagerReportGeneratorArn");
        lm("GetLicenseManagerReportGenerator", "{\"LicenseManagerReportGeneratorArn\":\"" + generator + "\"}")
                .body("ReportGenerator.LastRunStatus", equalTo("SUCCESS"))
                .body("ReportGenerator.S3Location.bucket", equalTo("lm-reports"));
        given().header("Authorization", auth(SELLER, "s3")).get("/lm-reports?list-type=2&prefix=license-manager-"
                + "reports/weekly/").then().statusCode(200).body(containsString("LicenseConfigurationSummaryReport"));
        lm("DeleteLicenseManagerReportGenerator", "{\"LicenseManagerReportGeneratorArn\":\"" + generator + "\"}")
                .statusCode(200);

        String ruleset = lm("CreateLicenseAssetRuleset", "{\"Name\":\"instances\",\"ClientToken\":\"rs1\",\"Rules\":["
                + "{\"RuleStatement\":{\"LicenseConfigurationRuleStatement\":{\"MatchingRuleStatement\":{"
                + "\"KeyToMatch\":\"LicenseCountingType\",\"Constraint\":\"equals\",\"ValueToMatch\":[\"Instance\"]}}}}"
                + "]}").statusCode(200).extract().path("LicenseAssetRulesetArn");
        String group = lm("CreateLicenseAssetGroup", "{\"Name\":\"group\",\"ClientToken\":\"ag1\","
                + "\"LicenseAssetGroupConfigurations\":[{\"UsageDimension\":\"vCPU\"}],"
                + "\"AssociatedLicenseAssetRulesetARNs\":[\"" + ruleset + "\"]}").statusCode(200)
                .body("Status", equalTo("ACTIVE")).extract().path("LicenseAssetGroupArn");
        lm("ListAssetsForLicenseAssetGroup", "{\"LicenseAssetGroupArn\":\"" + group + "\",\"AssetType\":"
                + "\"LicenseConfiguration\"}").statusCode(200).body("Assets.AssetArn", hasItem(config));
        lm("DeleteLicenseAssetRuleset", "{\"LicenseAssetRulesetArn\":\"" + ruleset + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        lm("ListLicenseAssetRulesets", "{\"ShowAWSManagedLicenseAssetRulesets\":true}")
                .body("LicenseAssetRulesets.Name", hasItem("AWS-WindowsServerInstances"));
        lm("DeleteLicenseAssetGroup", "{\"LicenseAssetGroupArn\":\"" + group + "\"}").body("Status",
                equalTo("DELETED"));
        lm("DeleteLicenseAssetRuleset", "{\"LicenseAssetRulesetArn\":\"" + ruleset + "\"}").statusCode(200);

        String task = lm("CreateLicenseConversionTaskForResource", "{\"ResourceArn\":\"arn:aws:ec2:us-east-1:" + SELLER
                + ":instance/i-0123456789abcdef0\",\"SourceLicenseContext\":{\"UsageOperation\":\"RunInstances\"},"
                + "\"DestinationLicenseContext\":{\"UsageOperation\":\"RunInstances:0002\"}}").statusCode(200)
                .extract().path("LicenseConversionTaskId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> lm("GetLicenseConversionTask",
                "{\"LicenseConversionTaskId\":\"" + task + "\"}").body("Status", equalTo("FAILED"))
                .body("StatusMessage", containsString("not found")));
        lm("ListResourceInventory", "{}").statusCode(200).body("ResourceInventoryList", notNullValue());
    }
}
