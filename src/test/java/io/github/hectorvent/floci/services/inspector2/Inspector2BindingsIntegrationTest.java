package io.github.hectorvent.floci.services.inspector2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * restJson1 coverage for the Alchemy Inspector2 bindings suite: list/get
 * operations return empty collections or default configuration, the public
 * vulnerability catalog resolves log4shell, and missing encryption keys /
 * reports / delegated admins surface typed ResourceNotFoundException.
 */
@QuarkusTest
class Inspector2BindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000701";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listFindingsReturnsEmptyCollection() {
        post("/findings/list", "{\"maxResults\":10}")
                .then()
                .statusCode(200)
                .body("findings", hasSize(0));
    }

    @Test
    void listCoverageReturnsEmptyCollection() {
        post("/coverage/list", "{\"maxResults\":10}")
                .then()
                .statusCode(200)
                .body("coveredResources", hasSize(0));
    }

    @Test
    void searchVulnerabilitiesResolvesLog4shell() {
        post("/vulnerabilities/search", "{\"filterCriteria\":{\"vulnerabilityIds\":[\"CVE-2021-44228\"]}}")
                .then()
                .statusCode(200)
                .body("vulnerabilities.id", hasItem("CVE-2021-44228"));
    }

    @Test
    void searchVulnerabilitiesWithoutCriteriaIsValidationException() {
        post("/vulnerabilities/search", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listUsageTotalsReturnsEmptyCollection() {
        post("/usage/list", "{}")
                .then()
                .statusCode(200)
                .body("totals", hasSize(0));
    }

    @Test
    void listAccountPermissionsReturnsGrantedOperations() {
        post("/accountpermissions/list", "{}")
                .then()
                .statusCode(200)
                .body("permissions.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void batchGetFreeTrialInfoReturnsTheRequestedAccount() {
        post("/freetrialinfo/batchget", "{\"accountIds\":[\"" + ACCOUNT + "\"]}")
                .then()
                .statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(ACCOUNT))
                .body("failedAccounts", hasSize(0));
    }

    @Test
    void getConfigurationReturnsEcrAndEc2State() {
        post("/configuration/get", "{}")
                .then()
                .statusCode(200)
                .body("ecrConfiguration.rescanDurationState.status", equalTo("SUCCESS"))
                .body("ec2Configuration.scanModeState.scanModeStatus", equalTo("SUCCESS"));
    }

    @Test
    void getEc2DeepInspectionConfigurationReturnsDisabledByDefault() {
        post("/ec2deepinspectionconfiguration/get", "{}")
                .then()
                .statusCode(200)
                .body("status", equalTo("DISABLED"));
    }

    @Test
    void getEncryptionKeyWithoutCustomerManagedKeyIsNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .queryParam("scanType", "PACKAGE")
                .queryParam("resourceType", "AWS_ECR_CONTAINER_IMAGE")
                .when()
                .get("/encryptionkey/get")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listCisScansReturnsEmptyCollection() {
        post("/cis/scan/list", "{}")
                .then()
                .statusCode(200)
                .body("scans", hasSize(0));
    }

    @Test
    void listMembersReturnsEmptyCollection() {
        post("/members/list", "{}")
                .then()
                .statusCode(200)
                .body("members", hasSize(0));
    }

    @Test
    void describeOrganizationConfigurationReturnsLimitFlag() {
        post("/organizationconfiguration/describe", "{}")
                .then()
                .statusCode(200)
                .body("maxAccountLimitReached", equalTo(false));
    }

    @Test
    void getDelegatedAdminAccountWithoutDesignationIsNotFound() {
        post("/delegatedadminaccounts/get", "{}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getFindingsReportStatusForUnknownIdIsNotFound() {
        post("/reporting/status/get", "{\"reportId\":\"00000000-0000-0000-0000-000000000000\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response post(String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body(body)
                .when()
                .post(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/inspector2/aws4_request";
    }
}
