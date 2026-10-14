package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Detector-scoped and organization reads served by GuardDutyController: administrator lookup,
 * malware scan settings, investigations, and organization statistics. Each test uses its own
 * account in us-west-2 so shared JVM state from other GuardDuty tests cannot interfere.
 */
@QuarkusTest
class GuardDutyAccountOperationsIntegrationTest {
    private static final String REGION = "us-west-2";
    private static final String UNKNOWN_DETECTOR = "0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void standaloneDetectorHasNoAdministrator() {
        String account = "818181818101";
        String detectorId = createDetector(account);

        given().header("Authorization", auth(account))
                .get("/detector/" + detectorId + "/administrator")
                .then().statusCode(200)
                .body(equalTo("{}"));

        given().header("Authorization", auth(account))
                .get("/detector/" + UNKNOWN_DETECTOR + "/administrator")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
    }

    @Test
    void organizationMemberReportsItsDelegatedAdministrator() {
        String admin = "818181818102";
        String member = "818181818103";
        given().contentType("application/json").header("Authorization", auth(admin))
                .body("{\"adminAccountId\":\"" + admin + "\"}")
                .post("/admin/enable").then().statusCode(200);
        try {
            String adminDetector = createDetector(admin);
            given().contentType("application/json").header("Authorization", auth(admin))
                    .body("{\"accountDetails\":[{\"accountId\":\"" + member
                            + "\",\"email\":\"member@example.com\"}]}")
                    .post("/detector/" + adminDetector + "/member").then().statusCode(200);

            String memberDetector = createDetector(member);
            given().header("Authorization", auth(member))
                    .get("/detector/" + memberDetector + "/administrator")
                    .then().statusCode(200)
                    .body("administrator.accountId", equalTo(admin))
                    .body("administrator.relationshipStatus", equalTo("Enabled"));
        } finally {
            given().contentType("application/json").header("Authorization", auth(admin))
                    .body("{\"adminAccountId\":\"" + admin + "\"}")
                    .post("/admin/disable").then().statusCode(200);
        }
    }

    @Test
    void malwareScanSettingsDefaultAndPersistPerDetector() {
        String account = "818181818104";
        String detectorId = createDetector(account);

        given().header("Authorization", auth(account))
                .get("/detector/" + detectorId + "/malware-scan-settings")
                .then().statusCode(200)
                .body("ebsSnapshotPreservation", equalTo("NO_RETENTION"))
                .body("scanResourceCriteria", nullValue());

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"ebsSnapshotPreservation\":\"RETENTION_WITH_FINDING\",\"scanResourceCriteria\":"
                        + "{\"include\":{\"EC2_INSTANCE_TAG\":{\"mapEquals\":[{\"key\":\"scan\",\"value\":\"yes\"}]}}}}")
                .post("/detector/" + detectorId + "/malware-scan-settings")
                .then().statusCode(200);

        given().header("Authorization", auth(account))
                .get("/detector/" + detectorId + "/malware-scan-settings")
                .then().statusCode(200)
                .body("ebsSnapshotPreservation", equalTo("RETENTION_WITH_FINDING"))
                .body("scanResourceCriteria.include.EC2_INSTANCE_TAG.mapEquals[0].key", equalTo("scan"))
                .body("scanResourceCriteria.include.EC2_INSTANCE_TAG.mapEquals[0].value", equalTo("yes"));

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"ebsSnapshotPreservation\":\"FOREVER\"}")
                .post("/detector/" + detectorId + "/malware-scan-settings")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given().header("Authorization", auth(account))
                .get("/detector/" + UNKNOWN_DETECTOR + "/malware-scan-settings")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void freshDetectorHasNoInvestigations() {
        String account = "818181818105";
        String detectorId = createDetector(account);

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{}")
                .post("/detector/" + detectorId + "/investigation/list")
                .then().statusCode(200)
                .body("investigations", hasSize(0));

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{\"maxResults\":0}")
                .post("/detector/" + detectorId + "/investigation/list")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given().contentType("application/json").header("Authorization", auth(account))
                .body("{}")
                .post("/detector/" + UNKNOWN_DETECTOR + "/investigation/list")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void organizationStatisticsRequireTheDelegatedAdministrator() {
        String account = "818181818106";
        given().header("Authorization", auth(account))
                .get("/organization/statistics")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void delegatedAdministratorOutsideAnOrganizationIsRejected() {
        String admin = "818181818107";
        given().contentType("application/json").header("Authorization", auth(admin))
                .body("{\"adminAccountId\":\"" + admin + "\"}")
                .post("/admin/enable").then().statusCode(200);
        try {
            given().header("Authorization", auth(admin))
                    .get("/organization/statistics")
                    .then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
        } finally {
            given().contentType("application/json").header("Authorization", auth(admin))
                    .body("{\"adminAccountId\":\"" + admin + "\"}")
                    .post("/admin/disable").then().statusCode(200);
        }
    }

    @Test
    void delegatedAdministratorReadsOrganizationStatistics() {
        String admin = "818181818108";
        given().header("X-Amz-Target", "AWSOrganizationsV20161128.CreateOrganization")
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + admin
                        + "/20260904/us-east-1/organizations/aws4_request")
                .body("{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(admin))
                .body("{\"adminAccountId\":\"" + admin + "\"}")
                .post("/admin/enable").then().statusCode(200);
        try {
            String detectorId = createDetector(admin);
            given().contentType("application/json").header("Authorization", auth(admin))
                    .body("{\"features\":[{\"name\":\"S3_DATA_EVENTS\",\"status\":\"ENABLED\"}]}")
                    .post("/detector/" + detectorId).then().statusCode(200);

            given().header("Authorization", auth(admin))
                    .get("/organization/statistics")
                    .then().statusCode(200)
                    .body("organizationDetails.organizationStatistics.totalAccountsCount", equalTo(1))
                    .body("organizationDetails.organizationStatistics.memberAccountsCount", equalTo(1))
                    .body("organizationDetails.organizationStatistics.activeAccountsCount", equalTo(1))
                    .body("organizationDetails.organizationStatistics.enabledAccountsCount", equalTo(1))
                    .body("organizationDetails.organizationStatistics.countByFeature[0].name",
                            equalTo("S3_DATA_EVENTS"))
                    .body("organizationDetails.organizationStatistics.countByFeature[0].enabledAccountsCount",
                            equalTo(1));
        } finally {
            given().contentType("application/json").header("Authorization", auth(admin))
                    .body("{\"adminAccountId\":\"" + admin + "\"}")
                    .post("/admin/disable").then().statusCode(200);
            given().header("X-Amz-Target", "AWSOrganizationsV20161128.DeleteOrganization")
                    .contentType("application/x-amz-json-1.1")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + admin
                            + "/20260904/us-east-1/organizations/aws4_request")
                    .body("{}")
                    .post("/");
        }
    }

    private static String createDetector(String accountId) {
        return given()
                .contentType("application/json")
                .header("Authorization", auth(accountId))
                .body("{\"enable\":true}")
                .post("/detector")
                .then().statusCode(200)
                .extract().path("detectorId");
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/" + REGION + "/guardduty/aws4_request";
    }
}
