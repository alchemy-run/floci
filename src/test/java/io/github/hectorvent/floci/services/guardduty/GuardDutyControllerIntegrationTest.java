package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the GuardDuty restJson1 detector lifecycle, organization readback, and isolation. */
@QuarkusTest
class GuardDutyControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void detectorCreateGetUpdateDeleteLifecycle() {
        String authorization = auth("000000000201", EAST);
        String detectorId = createDetector(authorization, """
                {"enable":true,"findingPublishingFrequency":"SIX_HOURS","tags":{"env":"compat"}}
                """);

        Response detector = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("SIX_HOURS"))
                .body("serviceRole", notNullValue())
                .body("tags.env", equalTo("compat"))
                .extract().response();
        assertTrue(detector.path("createdAt").toString().endsWith("Z"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"enable\":false,\"findingPublishingFrequency\":\"ONE_HOUR\"}")
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
                .body("status", equalTo("DISABLED"))
                .body("findingPublishingFrequency", equalTo("ONE_HOUR"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector")
                .then()
                .statusCode(200)
                .body("detectorIds", equalTo(List.of(detectorId)));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/detector/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
    }

    @Test
    void featureOrderIsPreservedOnReadBack() {
        String authorization = auth("000000000202", EAST);
        String detectorId = createDetector(authorization, """
                {"enable":true,"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]},
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"}
                ]}
                """);

        Response detector = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                detector.path("features.name"));
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                detector.path("features[0].additionalConfiguration.name"));
    }

    @Test
    void organizationConfigurationLifecycle() {
        String authorization = auth("000000000203", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true}");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(false))
                .body("memberAccountLimitReached", equalTo(false))
                .body("autoEnableOrganizationMembers", equalTo("NONE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"autoEnableOrganizationMembers":"ALL","features":[
                          {"name":"RUNTIME_MONITORING","autoEnable":"ALL","additionalConfiguration":[
                            {"name":"ECS_FARGATE_AGENT_MANAGEMENT","autoEnable":"ALL"},
                            {"name":"EC2_AGENT_MANAGEMENT","autoEnable":"ALL"},
                            {"name":"EKS_ADDON_MANAGEMENT","autoEnable":"NONE"}
                          ]}
                        ]}
                        """)
                .when()
                .post("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200);

        Response configuration = given()
                .header("Authorization", authorization)
                .when()
                .get("/detector/" + detectorId + "/admin")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(true))
                .body("autoEnableOrganizationMembers", equalTo("ALL"))
                .extract().response();
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                configuration.path("features[0].additionalConfiguration.name"));
    }

    @Test
    void organizationAdminAccountLifecycle() {
        String authorization = auth("000000000204", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/enable")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body("adminAccounts[0].adminAccountId", equalTo("111111111111"))
                .body("adminAccounts[0].adminStatus", equalTo("ENABLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/disable")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"adminAccountId\":\"111111111111\"}")
                .when()
                .post("/admin/disable")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.ADMIN_ALREADY_DISABLED_MESSAGE));
    }

    @Test
    void detectorsAreIsolatedByAccountAndRegion() {
        String eastAccountA = auth("000000000205", EAST);
        String eastAccountB = auth("000000000206", EAST);
        String westAccountA = auth("000000000205", WEST);
        String detectorId = createDetector(eastAccountA, "{\"enable\":true}");

        given()
                .header("Authorization", eastAccountB)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));

        given()
                .header("Authorization", westAccountA)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(400)
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));

        given()
                .header("Authorization", eastAccountA)
                .when()
                .get("/detector/" + detectorId)
                .then()
                .statusCode(200);
    }

    @Test
    void secondCreateInSameAccountAndRegionIsRejected() {
        String authorization = auth("000000000207", EAST);
        createDetector(authorization, "{\"enable\":true}");

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
    void detectorTagsAreServedThroughTheSharedTagsRoutes() {
        String authorization = auth("000000000208", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true,\"tags\":{\"env\":\"test\"}}");
        String arn = "arn:aws:guardduty:" + EAST + ":000000000208:detector/" + detectorId;

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"team\":\"security\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", equalTo("security"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "env")
                .when()
                .delete("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("security"));
    }

    @Test
    void sampleFindingRoutesRoundTripAndRejectOtherAccountRegionAndSigningService() {
        String authorization = auth("000000000220", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true}");
        String base = "/detector/" + detectorId;
        try {
            post(authorization, base + "/findings", "{}").then().statusCode(200).body("findingIds", hasSize(0));
            post(authorization, base + "/findings/create",
                    "{\"findingTypes\":[\"Recon:EC2/PortProbeUnprotectedPort\"]}").then().statusCode(200);
            String id = post(authorization, base + "/findings", "{}").then().statusCode(200)
                    .body("findingIds", hasSize(1)).extract().path("findingIds[0]");
            String ids = "{\"findingIds\":[\"" + id + "\"]}";
            post(authorization, base + "/findings/get", ids).then().statusCode(200)
                    .body("findings[0].accountId", equalTo("000000000220"))
                    .body("findings[0].type", equalTo("Recon:EC2/PortProbeUnprotectedPort"))
                    .body("findings[0].service.detectorId", equalTo(detectorId))
                    .body("findings[0].service.archived", equalTo(false))
                    .body("findings[0].service.additionalInfo.value", equalTo("{\"sample\":true}"));
            post(authorization, base + "/findings/statistics", "{\"findingStatisticTypes\":[\"COUNT_BY_SEVERITY\"]}")
                    .then().statusCode(200).body("findingStatistics.countBySeverity.'2.0'", equalTo(1));
            post(authorization, base + "/findings/archive", ids).then().statusCode(200);
            post(authorization, base + "/findings/get", ids).then().statusCode(200)
                    .body("findings[0].service.archived", equalTo(true));
            post(authorization, base + "/findings/unarchive", ids).then().statusCode(200);
            post(authorization, base + "/findings/feedback",
                    "{\"findingIds\":[\"" + id + "\"],\"feedback\":\"USEFUL\"}").then().statusCode(200);
            post(authorization, base + "/findings/get", ids).then().statusCode(200)
                    .body("findings[0].service.archived", equalTo(false))
                    .body("findings[0].service.userFeedback", equalTo("USEFUL"));
            for (String foreign : List.of(auth("000000000221", EAST), auth("000000000220", WEST))) {
                post(foreign, base + "/findings/get", ids).then().statusCode(400)
                        .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
                post(foreign, base + "/findings/archive", ids).then().statusCode(400);
            }
            post(authorization.replace("/guardduty/", "/s3/"), base + "/findings", "{}").then().statusCode(400);
        } finally {
            given().header("Authorization", authorization).delete(base).then().statusCode(200);
        }
        post(authorization, base + "/findings", "{}").then().statusCode(400);
    }

    @Test
    void metadataRoutesDoNotFallThroughToS3OrInventTrials() {
        String authorization = auth("000000000222", EAST);
        String detectorId = createDetector(authorization, "{\"enable\":true}");
        String base = "/detector/" + detectorId;
        try {
            post(authorization, base + "/usage/statistics", """
                    {"usageStatisticsType":"SUM_BY_DATA_SOURCE","usageCriteria":{"dataSources":["FLOW_LOGS"]}}
                    """).then().statusCode(200).body("usageStatistics.sumByDataSource", hasSize(0));
            post(authorization, base + "/coverage", "{}").then().statusCode(200).body("resources", hasSize(0));
            post(authorization, base + "/freeTrial/daysRemaining", "{\"accountIds\":[\"000000000222\"]}")
                    .then().statusCode(200).body("accounts", hasSize(0))
                    .body("unprocessedAccounts[0].accountId", equalTo("000000000222"))
                    .body("unprocessedAccounts[0].result", containsString("no AWS billing enrollment"));
            for (String path : List.of("/coverage", "/usage/statistics", "/freeTrial/daysRemaining")) {
                post(auth("000000000223", EAST), base + path, "{}").then().statusCode(400)
                        .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
            }
        } finally {
            given().header("Authorization", authorization).delete(base).then().statusCode(200);
        }
    }

    @Test
    void invitationsAreVisibleOnlyToTheInvitedAccountInTheSameRegion() {
        String administrator = auth("000000000224", EAST);
        String member = auth("000000000225", EAST);
        String detectorId = createDetector(administrator, "{\"enable\":true}");
        String base = "/detector/" + detectorId;
        try {
            post(administrator, base + "/member", """
                    {"accountDetails":[{"accountId":"000000000225","email":"invitee@example.com"}]}
                    """).then().statusCode(200);
            given().header("Authorization", member).get("/invitation/count").then().statusCode(200)
                    .body("invitationsCount", equalTo(0));
            post(administrator, base + "/member/invite", """
                    {"accountIds":["000000000225"],"disableEmailNotification":true}
                    """).then().statusCode(200).body("unprocessedAccounts", hasSize(0));
            given().header("Authorization", member).get("/invitation/count").then().statusCode(200)
                    .body("invitationsCount", equalTo(1));
            given().header("Authorization", member).get("/invitation").then().statusCode(200)
                    .body("invitations", hasSize(1)).body("invitations[0].accountId", equalTo("000000000224"))
                    .body("invitations[0].invitationId", notNullValue())
                    .body("invitations[0].relationshipStatus", equalTo("Invited"));
            for (String foreign : List.of(administrator, auth("000000000225", WEST), auth("000000000226", EAST))) {
                given().header("Authorization", foreign).get("/invitation").then().statusCode(200)
                        .body("invitations", hasSize(0));
            }
        } finally {
            given().header("Authorization", administrator).delete(base).then().statusCode(200);
        }
        given().header("Authorization", member).get("/invitation/count").then().statusCode(200)
                .body("invitationsCount", equalTo(0));
    }

    @Test
    void detectorResourcesUseStoredFiltersAndRealS3Lists() {
        String authorization = auth("000000000227", EAST);
        String s3Authorization = authorization.replace("/guardduty/", "/s3/");
        String bucket = "guardduty-source-lists-000000000227";
        given().header("Authorization", s3Authorization).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", s3Authorization).contentType("text/plain")
                .body("203.0.113.10\n198.51.100.0/24\n").put("/" + bucket + "/ips.txt").then().statusCode(200);
        String detectorId = createDetector(authorization, "{\"enable\":true}");
        String base = "/detector/" + detectorId;
        try {
            post(authorization, base + "/filter", """
                    {"name":"high-severity","action":"NOOP","rank":1,"description":"visible",
                     "findingCriteria":{"criterion":{"severity":{"gte":7}}},"tags":{"env":"test"}}
                    """).then().statusCode(200).body("name", equalTo("high-severity"));
            given().header("Authorization", authorization).get(base + "/filter").then().statusCode(200)
                    .body("filterNames", equalTo(List.of("high-severity")));
            post(authorization, base + "/filter/high-severity", "{\"action\":\"ARCHIVE\",\"description\":\"archive\"}")
                    .then().statusCode(200);
            given().header("Authorization", authorization).get(base + "/filter/high-severity").then().statusCode(200)
                    .body("action", equalTo("ARCHIVE")).body("rank", equalTo(1)).body("tags.env", equalTo("test"));
            for (String kind : List.of("ipset", "threatintelset")) {
                String field = "ipset".equals(kind) ? "ipSetId" : "threatIntelSetId";
                boolean activate = "ipset".equals(kind);
                String id = post(authorization, base + "/" + kind,
                        "{\"name\":\"source-list\",\"format\":\"TXT\",\"location\":\"https://s3.amazonaws.com/"
                                + bucket + "/ips.txt\",\"activate\":" + activate + "}")
                        .then().statusCode(200).extract().path(field);
                String resourcePath = base + "/" + kind + "/" + id;
                given().header("Authorization", authorization).get(resourcePath).then().statusCode(200)
                        .body("format", equalTo("TXT")).body("status", equalTo(activate ? "ACTIVE" : "INACTIVE"));
                given().header("Authorization", authorization).get(base + "/" + kind).then().statusCode(200)
                        .body(field + "s", equalTo(List.of(id)));
                String arn = "arn:aws:guardduty:" + EAST + ":000000000227:detector/" + detectorId + "/" + kind + "/" + id;
                post(authorization, "/tags/" + arn, "{\"tags\":{\"team\":\"security\"}}")
                        .then().statusCode(204);
                given().header("Authorization", authorization).get(resourcePath).then().statusCode(200)
                        .body("tags.team", equalTo("security"));
                post(authorization, resourcePath, "{\"activate\":false}").then().statusCode(200);
                given().header("Authorization", auth("000000000228", EAST)).get(resourcePath).then().statusCode(400);
                given().header("Authorization", authorization).delete(resourcePath).then().statusCode(200);
                given().header("Authorization", authorization).get(resourcePath).then().statusCode(400);
            }
            post(authorization, base + "/ipset", "{\"name\":\"missing-list\",\"format\":\"TXT\",\"location\":\"https://s3.amazonaws.com/"
                    + bucket + "/missing.txt\",\"activate\":true}").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
            given().header("Authorization", authorization).delete(base + "/filter/high-severity").then().statusCode(200);
        } finally {
            given().header("Authorization", authorization).delete(base).then().statusCode(200);
            given().header("Authorization", s3Authorization).delete("/" + bucket + "/ips.txt").then().statusCode(204);
            given().header("Authorization", s3Authorization).delete("/" + bucket).then().statusCode(204);
        }
    }

    private static Response post(String authorization, String path, String body) {
        return given().header("Authorization", authorization).contentType("application/json").body(body).post(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260215/" + region + "/guardduty/aws4_request";
    }

    private static String createDetector(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/detector")
                .then()
                .statusCode(200)
                .body("detectorId", notNullValue())
                .extract().path("detectorId");
    }
}
