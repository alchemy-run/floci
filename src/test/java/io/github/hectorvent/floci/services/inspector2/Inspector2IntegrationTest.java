package io.github.hectorvent.floci.services.inspector2;

import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Inspector2IntegrationTest {

    @Inject
    OrganizationsService organizationsService;

    @Test
    void delegatedAdminEnablementAndOrganizationConfiguration() {
        String management = "940000000001";
        String administrator = "940000000002";
        createOrganization(management, administrator);

        given().contentType("application/json").header("Authorization", auth(management)).body("{}")
                .post("/delegatedadminaccounts/list").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"delegatedAdminAccountId\":\"" + administrator + "\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200)
                .body("delegatedAdminAccountId", equalTo(administrator));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"lambda\":true,"
                        + "\"lambdaCode\":true,\"codeRepository\":true}}")
                .post("/organizationconfiguration/update").then().statusCode(200)
                .body("autoEnable.codeRepository", equalTo(true));
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/organizationconfiguration/describe").then().statusCode(200)
                .body("autoEnable.ec2", equalTo(true))
                .body("autoEnable.ecr", equalTo(true))
                .body("autoEnable.lambda", equalTo(true))
                .body("autoEnable.lambdaCode", equalTo(true))
                .body("autoEnable.codeRepository", equalTo(true));
    }

    @Test
    void managementAuthorizationAndMembershipAreEnforced() {
        String management = "940000000011";
        String administrator = "940000000012";
        String member = "940000000013";
        String outsider = "950000000014";
        createOrganization(management, administrator, member);

        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"delegatedAdminAccountId\":\"" + administrator + "\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"delegatedAdminAccountId\":\"" + outsider + "\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void delegatedAdminManagesMemberPerResourceState() {
        String management = "940000000021";
        String administrator = "940000000022";
        String member = "940000000023";
        createOrganization(management, administrator, member);
        designateAdministrator(management, administrator);

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"accountIds\":[\"" + member + "\"],\"resourceTypes\":[\"EC2\"]}")
                .post("/enable").then().statusCode(200)
                .body("accounts[0].accountId", equalTo(member))
                .body("accounts[0].resourceStatus.ec2", equalTo("ENABLING"))
                .body("accounts[0].resourceStatus.ecr", equalTo("DISABLED"));

        String body = "{\"accountIds\":[\"" + member + "\"]}";
        given().contentType("application/json").header("Authorization", auth(administrator)).body(body)
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(member))
                .body("accounts[0].state.status", equalTo("ENABLING"))
                .body("accounts[0].resourceState.ec2.status", equalTo("ENABLING"))
                .body("accounts[0].resourceState.ecr.status", equalTo("DISABLED"));
        given().contentType("application/json").header("Authorization", auth(administrator)).body(body)
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts[0].state.status", equalTo("ENABLED"))
                .body("accounts[0].resourceState.ec2.status", equalTo("ENABLED"))
                .body("accounts[0].resourceState.ecr.status", equalTo("DISABLED"));
    }

    @Test
    void memberCanEnableItselfWithOmittedAccountIds() {
        String management = "940000000031";
        String member = "940000000032";
        createOrganization(management, member);

        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"resourceTypes\":[\"ECR\"]}")
                .post("/enable").then().statusCode(200)
                .body("accounts[0].accountId", equalTo(member))
                .body("accounts[0].resourceStatus.ecr", equalTo("ENABLING"));
        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(member));
    }

    @Test
    void failedOrganizationUpdateDoesNotPartiallyMutateState() {
        String management = "940000000041";
        String administrator = "940000000042";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"autoEnable\":{\"ec2\":false,\"ecr\":false}}")
                .post("/organizationconfiguration/update").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"autoEnable\":{\"ec2\":true}}")
                .post("/organizationconfiguration/update").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/organizationconfiguration/describe").then().statusCode(200)
                .body("autoEnable.ec2", equalTo(false))
                .body("autoEnable.ecr", equalTo(false));
    }

    @Test
    void delegatedAdminCanBeDisabledByManagementAccount() {
        String management = "940000000051";
        String administrator = "940000000052";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"delegatedAdminAccountId\":\"" + administrator + "\"}")
                .post("/delegatedadminaccounts/disable").then().statusCode(200)
                .body("delegatedAdminAccountId", equalTo(administrator));
    }

    @Test
    void invalidAccountIdReturnsValidationException() {
        String management = "940000000061";
        organizationsService.createOrganization(management, "ALL");
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"delegatedAdminAccountId\":\"bad\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void filterLifecycleUsesInspectorRoutesAndAwsWireShape() {
        String account = "940000000071";
        String arn = given().contentType("application/json").header("Authorization", auth(account))
                .body("""
                        {"name":"informational","action":"SUPPRESS","reason":"Initially suppressed",
                         "filterCriteria":{"severity":[{"comparison":"EQUALS","value":"INFORMATIONAL"}]},
                         "tags":{"env":"test","alchemy::id":"Filter"}}
                        """)
                .post("/filters/create").then().statusCode(200)
                .body("arn", startsWith("arn:aws:inspector2:us-east-1:" + account + ":owner/" + account + "/filter/"))
                .extract().path("arn");
        String byArn = "{\"arns\":[\"" + arn + "\"]}";
        given().contentType("application/json").header("Authorization", auth(account)).body(byArn)
                .post("/filters/list").then().statusCode(200)
                .body("filters", hasSize(1))
                .body("filters[0].arn", equalTo(arn))
                .body("filters[0].ownerId", equalTo(account))
                .body("filters[0].criteria.severity[0].comparison", equalTo("EQUALS"))
                .body("filters[0].criteria.severity[0].value", equalTo("INFORMATIONAL"))
                .body("filters[0].createdAt", instanceOf(Number.class))
                .body("filters[0].updatedAt", instanceOf(Number.class))
                .body("filters[0].tags.env", equalTo("test"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"filterArn\":\"" + arn + "\",\"action\":\"NONE\",\"reason\":\"Keep visible\"}")
                .post("/filters/update").then().statusCode(200).body("arn", equalTo(arn));
        given().contentType("application/json").header("Authorization", auth(account)).body(byArn)
                .post("/filters/list").then().statusCode(200)
                .body("filters[0].action", equalTo("NONE"))
                .body("filters[0].reason", equalTo("Keep visible"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"tags\":{\"env\":\"updated\",\"extra\":\"present\"}}")
                .post("/tags/{arn}", arn).then().statusCode(200);
        given().header("Authorization", auth(account)).queryParam("tagKeys", "extra")
                .delete("/tags/{arn}", arn).then().statusCode(200);
        given().header("Authorization", auth(account)).get("/tags/{arn}", arn).then().statusCode(200)
                .body("tags.env", equalTo("updated"))
                .body("tags.size()", equalTo(2));
        given().contentType("application/json").header("Authorization", auth(account)).body(byArn)
                .post("/filters/list").then().statusCode(200)
                .body("filters[0].tags.env", equalTo("updated"))
                .body("filters[0].tags.size()", equalTo(2));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"arn\":\"" + arn + "\"}").post("/filters/delete").then().statusCode(200)
                .body("arn", equalTo(arn));
        given().contentType("application/json").header("Authorization", auth(account)).body(byArn)
                .post("/filters/list").then().statusCode(200).body("filters", hasSize(0));
        given().header("Authorization", auth(account)).get("/tags/{arn}", arn).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"arn\":\"" + arn + "\"}").post("/filters/delete").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void filtersAndTagsCannotEscapeTheRequestAccountOrRegion() {
        String account = "940000000081";
        String other = "940000000082";
        String arn = given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"name\":\"isolated\",\"action\":\"NONE\",\"filterCriteria\":{}}")
                .post("/filters/create").then().statusCode(200).extract().path("arn");
        for (String credential : new String[]{auth(other), auth(account).replace("us-east-1", "us-west-2")}) {
            given().contentType("application/json").header("Authorization", credential).body("{}")
                    .post("/filters/list").then().statusCode(200).body("filters", hasSize(0));
            given().header("Authorization", credential).get("/tags/{arn}", arn).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().contentType("application/json").header("Authorization", credential)
                    .body("{\"tags\":{\"stolen\":\"true\"}}")
                    .post("/tags/{arn}", arn).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        }
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"arn\":\"" + arn + "\"}").post("/filters/delete").then().statusCode(200);
    }

    @Test
    void filterValidationIsJsonAndDoesNotFallThroughToS3() {
        String account = "940000000091";
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/filters/create").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"maxResults\":0}").post("/filters/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", auth(account)).body("[]")
                .post("/filters/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", auth(account).replace("/inspector2/", "/s3/"))
                .body("{}").post("/filters/list").then().statusCode(400)
                .body("__type", equalTo("AuthorizationHeaderMalformed"));
    }

    @Test
    void deepInspectionPostRequiresEc2ScanningAndReturnsStringStatus() {
        String account = "940000000101";
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/ec2deepinspectionconfiguration/get").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        enableAndConverge(account, "ECR");
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/ec2deepinspectionconfiguration/get").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        enableAndConverge(account, "EC2");
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/ec2deepinspectionconfiguration/get").then().statusCode(200)
                .body("status", equalTo("ACTIVATED"))
                .body("packagePaths", hasSize(0))
                .body("orgPackagePaths", hasSize(0));
        given().contentType("application/json")
                .header("Authorization", auth(account).replace("us-east-1", "us-west-2")).body("{}")
                .post("/ec2deepinspectionconfiguration/get").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void cisAccountGuardUsesLiveEnablementInsteadOfValidationOrBlanketDenial() {
        String account = "940000000111";
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/cis/scan-configuration/list").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Invoking account is not enabled."));
        enableAndConverge(account, "ECR");
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/cis/scan-configuration/list").then().statusCode(501)
                .body("__type", equalTo("NotImplementedException"))
                .body("message", notNullValue());
        given().contentType("application/json")
                .header("Authorization", auth(account).replace("us-east-1", "us-west-2")).body("{}")
                .post("/cis/scan-configuration/list").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void findingsCoverageAndUsageAreEmptyWithoutAScannerAndValidateInput() {
        String account = "940000000121";
        for (String[] route : new String[][]{
                {"/findings/list", "findings"}, {"/coverage/list", "coveredResources"},
                {"/usage/list", "totals"}}) {
            given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                    .post(route[0]).then().statusCode(200)
                    .body(route[1], hasSize(0))
                    .body("nextToken", nullValue());
            given().contentType("application/json").header("Authorization", auth(account))
                    .body("{\"maxResults\":0}").post(route[0]).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"filterCriteria\":[]}").post("/findings/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"accountIds\":[\"940000000122\"]}").post("/usage/list").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void searchVulnerabilitiesAnswersFromTheCatalogAndReportsUnknownIdsAsEmpty() {
        String account = "940000000131";
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"filterCriteria\":{\"vulnerabilityIds\":[\"CVE-2021-44228\"]}}")
                .post("/vulnerabilities/search").then().statusCode(200)
                .body("vulnerabilities", hasSize(1))
                .body("vulnerabilities[0].id", equalTo("CVE-2021-44228"))
                .body("vulnerabilities[0].source", equalTo("NVD"))
                .body("vulnerabilities[0].cvss3.baseScore", equalTo(10.0f))
                .body("vulnerabilities[0].cisaData.dateAdded", equalTo(1639094400));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"filterCriteria\":{\"vulnerabilityIds\":[\"CVE-1999-0001\"]}}")
                .post("/vulnerabilities/search").then().statusCode(200)
                .body("vulnerabilities", hasSize(0));
        for (String bad : new String[]{"{}", "{\"filterCriteria\":{\"vulnerabilityIds\":[]}}",
                "{\"filterCriteria\":{\"vulnerabilityIds\":[\"GHSA-1234\"]}}",
                "{\"filterCriteria\":{\"vulnerabilityIds\":[\"CVE-2021-1\",\"CVE-2021-2\"]}}"}) {
            given().contentType("application/json").header("Authorization", auth(account)).body(bad)
                    .post("/vulnerabilities/search").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void accountSettingsReflectEnablementAndOrganizationManagement() {
        String management = "940000000141";
        String administrator = "940000000142";
        String member = "940000000143";
        createOrganization(management, administrator, member);

        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/accountpermissions/list").then().statusCode(200)
                .body("permissions", hasSize(8));
        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"service\":\"ECR\"}").post("/accountpermissions/list").then().statusCode(200)
                .body("permissions", hasSize(4))
                .body("permissions.service", everyItem(equalTo("ECR")));
        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"service\":\"S3\"}").post("/accountpermissions/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String trialBody = "{\"accountIds\":[\"" + member + "\"]}";
        given().contentType("application/json").header("Authorization", auth(member)).body(trialBody)
                .post("/freetrialinfo/batchget").then().statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(member))
                .body("accounts[0].freeTrialInfo", hasSize(0))
                .body("failedAccounts", hasSize(0));
        enableAndConverge(member, "ECR");
        given().contentType("application/json").header("Authorization", auth(member)).body(trialBody)
                .post("/freetrialinfo/batchget").then().statusCode(200)
                .body("accounts[0].freeTrialInfo", hasSize(1))
                .body("accounts[0].freeTrialInfo[0].type", equalTo("ECR"))
                .body("accounts[0].freeTrialInfo[0].status", equalTo("ACTIVE"))
                .body("accounts[0].freeTrialInfo[0].start", instanceOf(Number.class))
                .body("accounts[0].freeTrialInfo[0].end", instanceOf(Number.class));
        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"accountIds\":[\"" + administrator + "\"]}")
                .post("/freetrialinfo/batchget").then().statusCode(200)
                .body("accounts", hasSize(0))
                .body("failedAccounts[0].accountId", equalTo(administrator))
                .body("failedAccounts[0].code", equalTo("ACCESS_DENIED"));

        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/configuration/get").then().statusCode(200)
                .body("ecrConfiguration", nullValue())
                .body("ec2Configuration", nullValue());
        given().contentType("application/json").header("Authorization", auth(member))
                .body("{\"accountId\":\"" + administrator + "\"}")
                .post("/configuration/get").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        designateAdministrator(management, administrator);
        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/accountpermissions/list").then().statusCode(200)
                .body("permissions", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/accountpermissions/list").then().statusCode(200)
                .body("permissions", hasSize(8));
        given().contentType("application/json").header("Authorization", auth(administrator)).body(trialBody)
                .post("/freetrialinfo/batchget").then().statusCode(200)
                .body("accounts[0].accountId", equalTo(member))
                .body("failedAccounts", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"accountId\":\"" + member + "\"}")
                .post("/configuration/get").then().statusCode(200);
    }

    @Test
    void encryptionKeyAndReportStatusReturnTypedNotFound() {
        String account = "940000000151";
        given().header("Authorization", auth(account))
                .queryParam("scanType", "PACKAGE").queryParam("resourceType", "AWS_ECR_CONTAINER_IMAGE")
                .get("/encryptionkey/get").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().header("Authorization", auth(account))
                .queryParam("scanType", "BOGUS").queryParam("resourceType", "AWS_ECR_CONTAINER_IMAGE")
                .get("/encryptionkey/get").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        given().header("Authorization", auth(account)).queryParam("scanType", "PACKAGE")
                .get("/encryptionkey/get").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"reportId\":\"00000000-0000-0000-0000-000000000000\"}")
                .post("/reporting/status/get").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"reportId\":\"not-a-report\"}")
                .post("/reporting/status/get").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void cisScansRequireEnablement() {
        String account = "940000000161";
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/cis/scan/list").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"sortBy\":\"NAME\"}").post("/cis/scan/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        enableAndConverge(account, "EC2");
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/cis/scan/list").then().statusCode(200)
                .body("scans", hasSize(0));
    }

    @Test
    void membersAndDelegatedAdminFollowOrganizationState() {
        String management = "940000000171";
        String administrator = "940000000172";
        String member = "940000000173";
        String standalone = "950000000174";
        createOrganization(management, administrator, member);

        given().contentType("application/json").header("Authorization", auth(standalone)).body("{}")
                .post("/delegatedadminaccounts/get").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        given().contentType("application/json").header("Authorization", auth(management)).body("{}")
                .post("/delegatedadminaccounts/get").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        given().contentType("application/json").header("Authorization", auth(standalone)).body("{}")
                .post("/members/list").then().statusCode(200)
                .body("members", hasSize(0));

        designateAdministrator(management, administrator);
        for (String caller : new String[]{management, administrator, member}) {
            given().contentType("application/json").header("Authorization", auth(caller)).body("{}")
                    .post("/delegatedadminaccounts/get").then().statusCode(200)
                    .body("delegatedAdmin.accountId", equalTo(administrator))
                    .body("delegatedAdmin.relationshipStatus", equalTo("ENABLED"));
        }
        given().contentType("application/json").header("Authorization", auth(member)).body("{}")
                .post("/members/list").then().statusCode(200)
                .body("members", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/members/list").then().statusCode(200)
                .body("members", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"onlyAssociated\":false}").post("/members/list").then().statusCode(200)
                .body("members", hasSize(2))
                .body("members.relationshipStatus", everyItem(equalTo("CREATED")))
                .body("members.delegatedAdminAccountId", everyItem(equalTo(administrator)));

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"accountIds\":[\"" + member + "\"],\"resourceTypes\":[\"LAMBDA\"]}")
                .post("/enable").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(administrator)).body("{}")
                .post("/members/list").then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].accountId", equalTo(member))
                .body("members[0].relationshipStatus", equalTo("ENABLED"));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"maxResults\":51}").post("/members/list").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private void enableAndConverge(String account, String resourceType) {
        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"resourceTypes\":[\"" + resourceType + "\"]}")
                .post("/enable").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts[0].state.status", equalTo("ENABLING"));
        given().contentType("application/json").header("Authorization", auth(account)).body("{}")
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts[0].state.status", equalTo("ENABLED"));
    }

    private void createOrganization(String managementAccountId, String... members) {
        organizationsService.createOrganization(managementAccountId, "ALL");
        for (String member : members) {
            var handshake = organizationsService.inviteAccountToOrganization(
                    managementAccountId, member, "ACCOUNT", null);
            organizationsService.acceptHandshake(member, handshake.getId());
        }
    }

    private void designateAdministrator(String managementAccountId, String administratorAccountId) {
        given().contentType("application/json").header("Authorization", auth(managementAccountId))
                .body("{\"delegatedAdminAccountId\":\"" + administratorAccountId + "\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200);
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/inspector2/aws4_request";
    }
}
