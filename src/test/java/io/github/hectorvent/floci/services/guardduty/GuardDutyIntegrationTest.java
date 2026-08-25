package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies GuardDuty restJson1 detector lifecycle and the operations Alchemy
 * {@code Bindings.test.ts} drives through the Lambda fixture.
 */
@QuarkusTest
class GuardDutyIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000601";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listDetectorsOnAFreshAccountIsEmpty() {
        given()
                .header("Authorization", auth("000000000602", EAST))
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", hasSize(0));
    }

    @Test
    void createDetectorWhenOneAlreadyExistsIsBadRequest() {
        String authorization = auth("000000000603", EAST);
        create(authorization);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enable\":true}")
                .when()
                .post("/detector")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void updateDetectorChangesFindingPublishingFrequencyInPlace() {
        String authorization = auth("000000000605", EAST);
        String detectorId = create(authorization, """
                {"enable":true,"findingPublishingFrequency":"SIX_HOURS","tags":{"env":"test"}}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enable\":true,\"findingPublishingFrequency\":\"FIFTEEN_MINUTES\"}")
                .when()
                .post("/detector/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("FIFTEEN_MINUTES"))
                .body("tags.env", equalTo("test"));
    }

    @Test
    void getDetectorOnAMissingDetectorIsBadRequest() {
        given()
                .header("Authorization", auth("000000000604", EAST))
                .when()
                .get("/detector/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void bindingsDetectorFindingsOrgInvitationsAndTags() {
        String authorization = auth(ACCOUNT, EAST);
        String detectorId = create(authorization, """
                {"enable":true,"findingPublishingFrequency":"FIFTEEN_MINUTES","tags":{"fixture":"guardduty-bindings"}}
                """);
        assertTrue(detectorId.length() >= 16);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", hasSize(1))
                .body("detectorIds[0]", equalTo(detectorId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("FIFTEEN_MINUTES"))
                .body("serviceRole", notNullValue())
                .body("tags.fixture", equalTo("guardduty-bindings"));

        post(authorization, "/detector/" + detectorId + "/findings/create",
                "{\"findingTypes\":[\"Recon:EC2/PortProbeUnprotectedPort\"]}")
                .then()
                .statusCode(200);

        String findingId = post(authorization, "/detector/" + detectorId + "/findings", "{}")
                .then()
                .statusCode(200)
                .body("findingIds", hasSize(1))
                .extract()
                .path("findingIds[0]");

        post(authorization, "/detector/" + detectorId + "/findings/get",
                "{\"findingIds\":[\"" + findingId + "\"]}")
                .then()
                .statusCode(200)
                .body("findings", hasSize(1))
                .body("findings[0].type", equalTo("Recon:EC2/PortProbeUnprotectedPort"))
                .body("findings[0].service.archived", equalTo(false));

        post(authorization, "/detector/" + detectorId + "/findings/statistics",
                "{\"findingStatisticTypes\":[\"COUNT_BY_SEVERITY\"]}")
                .then()
                .statusCode(200)
                .body("findingStatistics.countBySeverity.2", greaterThan(0));

        post(authorization, "/detector/" + detectorId + "/findings/archive",
                "{\"findingIds\":[\"" + findingId + "\"]}")
                .then()
                .statusCode(200);

        post(authorization, "/detector/" + detectorId + "/findings/get",
                "{\"findingIds\":[\"" + findingId + "\"]}")
                .then()
                .statusCode(200)
                .body("findings[0].service.archived", equalTo(true));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/member")
                .then()
                .statusCode(200)
                .body("members", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/administrator")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/malware-scan-settings")
                .then()
                .statusCode(200)
                .body("ebsSnapshotPreservation", equalTo("NO_RETENTION"));

        post(authorization, "/detector/" + detectorId + "/usage/statistics",
                "{\"usageStatisticsType\":\"SUM_BY_DATA_SOURCE\",\"usageCriteria\":{\"dataSources\":[\"FLOW_LOGS\"]}}")
                .then()
                .statusCode(200)
                .body("usageStatistics.sumByDataSource", hasSize(0));

        post(authorization, "/detector/" + detectorId + "/coverage", "{}")
                .then()
                .statusCode(200)
                .body("resources", hasSize(0));

        post(authorization, "/detector/" + detectorId + "/freeTrial/daysRemaining",
                "{\"accountIds\":[\"" + ACCOUNT + "\"]}")
                .then()
                .statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo(ACCOUNT));

        post(authorization, "/detector/" + detectorId + "/investigation/list", "{}")
                .then()
                .statusCode(200)
                .body("investigations", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(false));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body("adminAccounts", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/organization/statistics")
                .then()
                .statusCode(200)
                .body("organizationDetails.organizationStatistics.activeAccountsCount", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitation/count")
                .then()
                .statusCode(200)
                .body("invitationsCount", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitation")
                .then()
                .statusCode(200)
                .body("invitations", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/filter")
                .then()
                .statusCode(200)
                .body("filterNames", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", hasSize(0));
    }

    @Test
    void detectorScopedFilterIpSetAndThreatIntelSet() {
        String authorization = auth("000000000606", EAST);
        String detectorId = create(authorization, """
                {"enable":true,"findingPublishingFrequency":"SIX_HOURS","tags":{"fixture":"guardduty-detector-resources"}}
                """);

        String filterName = post(authorization, "/detector/" + detectorId + "/filter", """
                {
                  "name":"HighSeverity",
                  "description":"keep high severity findings visible",
                  "action":"NOOP",
                  "rank":1,
                  "findingCriteria":{"criterion":{"severity":{"gte":7}}},
                  "tags":{"env":"test","alchemy::id":"HighSeverity"}
                }
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("name");
        assertEquals("HighSeverity", filterName);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/filter/" + filterName)
                .then()
                .statusCode(200)
                .body("action", equalTo("NOOP"))
                .body("rank", equalTo(1))
                .body("tags.env", equalTo("test"));
        Map<String, String> filterTags = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/filter/" + filterName)
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("HighSeverity", filterTags.get("alchemy::id"));

        String ipSetId = post(authorization, "/detector/" + detectorId + "/ipset", """
                {
                  "name":"TrustedIPs",
                  "format":"TXT",
                  "location":"https://s3.amazonaws.com/lists/trusted-ips.txt",
                  "activate":true
                }
                """)
                .then()
                .statusCode(200)
                .body("ipSetId", notNullValue())
                .extract()
                .path("ipSetId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/ipset/" + ipSetId)
                .then()
                .statusCode(200)
                .body("format", equalTo("TXT"))
                .body("status", equalTo("ACTIVE"))
                .body("location", equalTo("https://s3.amazonaws.com/lists/trusted-ips.txt"));

        String threatIntelSetId = post(authorization, "/detector/" + detectorId + "/threatintelset", """
                {
                  "name":"ThreatIPs",
                  "format":"TXT",
                  "location":"https://s3.amazonaws.com/lists/threat-ips.txt",
                  "activate":false
                }
                """)
                .then()
                .statusCode(200)
                .body("threatIntelSetId", notNullValue())
                .extract()
                .path("threatIntelSetId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/threatintelset/" + threatIntelSetId)
                .then()
                .statusCode(200)
                .body("format", equalTo("TXT"))
                .body("status", equalTo("INACTIVE"))
                .body("location", equalTo("https://s3.amazonaws.com/lists/threat-ips.txt"));

        post(authorization, "/detector/" + detectorId + "/filter/" + filterName, """
                {
                  "action":"ARCHIVE",
                  "rank":1,
                  "description":"auto-archive high severity findings"
                }
                """)
                .then()
                .statusCode(200)
                .body("name", equalTo(filterName));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/filter/" + filterName)
                .then()
                .statusCode(200)
                .body("action", equalTo("ARCHIVE"))
                .body("rank", equalTo(1))
                .body("description", equalTo("auto-archive high severity findings"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId + "/filter/" + filterName)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId + "/ipset/" + ipSetId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId + "/threatintelset/" + threatIntelSetId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", hasSize(0));
    }

    private static String create(String authorization) {
        return create(authorization, "{\"enable\":true,\"tags\":{\"fixture\":\"guardduty\"}}");
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/detector")
                .then()
                .statusCode(200)
                .body("detectorId", notNullValue())
                .extract()
                .path("detectorId");
    }

    private static Response post(String authorization, String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/guardduty/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
