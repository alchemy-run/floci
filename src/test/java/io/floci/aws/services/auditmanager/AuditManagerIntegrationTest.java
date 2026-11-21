package io.floci.aws.services.auditmanager;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit Manager over its REST API: registration gating, the control and framework libraries, assessments with
 * delegations, reviews and evidence (manual, and collected from AWS Config), signed reports in S3, insights,
 * framework sharing between accounts, and tags.
 */
@QuarkusTest
class AuditManagerIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification am(String account, String region) {
        return given().contentType("application/json").header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + region + "/auditmanager/aws4_request");
    }

    static RequestSpecification am() {
        return am(ACCOUNT, "us-east-1");
    }

    static void register(String account, String region) {
        am(account, region).body("{}").post("/account/registerAccount").then().statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"auditmanager.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request")
                .formParam("Action", "CreateRole").formParam("Version", "2010-05-08").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200);
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    private static String bucket(String name) {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request")
                .put("/" + name).then().statusCode(200);
        return name;
    }

    private static String control(String name, String sources) {
        return am().body("{\"name\":\"" + name + "\",\"controlMappingSources\":" + sources + "}").post("/controls")
                .then().statusCode(200).extract().path("control.id");
    }

    private static final String MANUAL = "[{\"sourceName\":\"manual\",\"sourceSetUpOption\":"
            + "\"Procedural_Controls_Mapping\",\"sourceType\":\"MANUAL\"}]";

    private static String framework(String name, String... controls) {
        StringBuilder members = new StringBuilder();
        for (String id : controls) {
            members.append(members.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\"}");
        }
        return am().body("{\"name\":\"" + name + "\",\"complianceType\":\"Internal\",\"controlSets\":[{\"name\":"
                + "\"Operations\",\"controls\":[" + members + "]}]}").post("/assessmentFrameworks").then()
                .statusCode(200).extract().path("framework.id");
    }

    private static String assessment(String frameworkId, String owner, String reports) {
        return am().body("{\"name\":\"" + unique("assessment") + "\",\"frameworkId\":\"" + frameworkId + "\","
                + "\"assessmentReportsDestination\":{\"destinationType\":\"S3\",\"destination\":\"s3://" + reports
                + "/reports\"},\"scope\":{\"awsAccounts\":[{\"id\":\"" + ACCOUNT + "\"}]},\"roles\":[{\"roleType\":"
                + "\"PROCESS_OWNER\",\"roleArn\":\"" + owner + "\"}]}").post("/assessments").then().statusCode(200)
                .extract().path("assessment.metadata.id");
    }

    @Test
    void anUnregisteredAccountCanOnlyRegister() {
        String region = "eu-west-3";
        am("123456789012", region).get("/account/status").then().statusCode(200).body("status", equalTo("INACTIVE"));
        am("123456789012", region).body("{\"name\":\"x\",\"controlMappingSources\":" + MANUAL + "}")
                .post("/controls").then().statusCode(403).body("message", containsString("setup"));
        am("123456789012", region).get("/insights").then().statusCode(403);
        register("123456789012", region);
        am("123456789012", region).get("/account/status").then().statusCode(200).body("status", equalTo("ACTIVE"));
        am("123456789012", region).get("/settings/ALL").then().statusCode(200)
                .body("settings.kmsKey", equalTo("DEFAULT"))
                .body("settings.deregistrationPolicy.deleteResources", equalTo("DEFAULT"))
                .body("settings.evidenceFinderEnablement.enablementStatus", equalTo("DISABLED"));
        am("123456789012", region).body("{\"deregistrationPolicy\":{\"deleteResources\":\"ALL\"},"
                + "\"evidenceFinderEnabled\":true}").put("/settings").then().statusCode(200)
                .body("settings.evidenceFinderEnablement.enablementStatus", equalTo("ENABLED"));
        String controlId = am("123456789012", region).body("{\"name\":\"x\",\"controlMappingSources\":" + MANUAL
                + "}").post("/controls").then().statusCode(200).extract().path("control.id");
        am("123456789012", region).post("/account/deregisterAccount").then().statusCode(200)
                .body("status", equalTo("INACTIVE"));
        register("123456789012", region);
        am("123456789012", region).get("/controls/" + controlId).then().statusCode(404);
    }

    @Test
    void controlsAndFrameworks() {
        register(ACCOUNT, "us-east-1");
        String name = unique("control");
        String id = am().body("{\"name\":\"" + name + "\",\"description\":\"first\",\"controlMappingSources\":["
                + "{\"sourceName\":\"rule\",\"sourceSetUpOption\":\"System_Controls_Mapping\",\"sourceType\":"
                + "\"AWS_Config\",\"sourceKeyword\":{\"keywordInputType\":\"SELECT_FROM_LIST\",\"keywordValue\":"
                + "\"S3_BUCKET_PUBLIC_READ_PROHIBITED\"}}],\"tags\":{\"team\":\"audit\"}}").post("/controls").then()
                .statusCode(200).body("control.type", equalTo("Custom"))
                .body("control.arn", containsString(":control/"))
                .body("control.controlSources", equalTo("AWS Config"))
                .body("control.controlMappingSources[0].sourceId", notNullValue())
                .extract().path("control.id");
        am().body("{\"name\":\"" + name + "\",\"controlMappingSources\":" + MANUAL + "}").post("/controls").then()
                .statusCode(400).body("message", containsString("already exists"));
        am().body("{\"name\":\"bad\",\"controlMappingSources\":[{\"sourceName\":\"m\",\"sourceSetUpOption\":"
                + "\"System_Controls_Mapping\",\"sourceType\":\"MANUAL\"}]}").post("/controls").then()
                .statusCode(400);
        am().body("{\"name\":\"" + name + "\",\"description\":\"second\",\"controlMappingSources\":" + MANUAL + "}")
                .put("/controls/" + id).then().statusCode(200).body("control.description", equalTo("second"))
                .body("control.controlSources", equalTo("Manual"));
        am().queryParam("controlType", "Custom").get("/controls").then().statusCode(200)
                .body("controlMetadataList.id", hasItem(id));
        List<String> core = am().queryParam("controlType", "Core").get("/controls").then().statusCode(200)
                .extract().path("controlMetadataList.id");
        assertTrue(core.size() > 5);
        am().get("/controls/" + core.get(0)).then().statusCode(200).body("control.type", equalTo("Core"));
        am().delete("/controls/" + core.get(0)).then().statusCode(400);

        String frameworkId = framework(unique("framework"), id, core.get(0));
        am().get("/assessmentFrameworks/" + frameworkId).then().statusCode(200)
                .body("framework.controlSets[0].controls", hasSize(2))
                .body("framework.controlSources", containsString("Manual"));
        am().queryParam("frameworkType", "Custom").get("/assessmentFrameworks").then().statusCode(200)
                .body("frameworkMetadataList.find { it.id == '" + frameworkId + "' }.controlsCount", equalTo(2));
        am().queryParam("frameworkType", "Standard").get("/assessmentFrameworks").then().statusCode(200)
                .body("frameworkMetadataList[0].type", equalTo("Standard"));
        am().body("{\"name\":\"f\",\"controlSets\":[{\"name\":\"s\",\"controls\":[{\"id\":\""
                + UUID.randomUUID() + "\"}]}]}").post("/assessmentFrameworks").then().statusCode(404);

        am().delete("/controls/" + id).then().statusCode(200);
        am().get("/assessmentFrameworks/" + frameworkId).then().statusCode(200)
                .body("framework.controlSets[0].controls", hasSize(1));
        am().get("/controls/" + id).then().statusCode(404);
        am().delete("/assessmentFrameworks/" + frameworkId).then().statusCode(200);
        am().get("/assessmentFrameworks/" + frameworkId).then().statusCode(404);
    }

    @Test
    void anAssessmentCollectsEvidenceAndSignsItsReport() {
        register(ACCOUNT, "us-east-1");
        String controlId = control(unique("manual"), MANUAL);
        String frameworkId = framework(unique("framework"), controlId);
        String owner = role(unique("owner"));
        String reports = bucket(unique("reports"));
        am().body("{\"name\":\"a\",\"frameworkId\":\"" + frameworkId + "\",\"assessmentReportsDestination\":"
                + "{\"destination\":\"s3://" + reports + "\"},\"scope\":{\"awsAccounts\":[{\"id\":\"" + ACCOUNT
                + "\"}]},\"roles\":[{\"roleType\":\"PROCESS_OWNER\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/missing\"}]}").post("/assessments").then().statusCode(400)
                .body("message", containsString("doesn't exist"));
        String id = assessment(frameworkId, owner, reports);
        String base = "/assessments/" + id;
        am().get(base).then().statusCode(200).body("assessment.metadata.status", equalTo("ACTIVE"))
                .body("assessment.framework.controlSets[0].id", equalTo("Operations"))
                .body("assessment.framework.controlSets[0].controls[0].status", equalTo("UNDER_REVIEW"))
                .body("assessment.framework.controlSets[0].controls[0].response", equalTo("MANUAL"))
                .body("assessment.awsAccount.id", equalTo(ACCOUNT))
                .body("userRole.roleType", equalTo("PROCESS_OWNER"));

        String resourceOwner = role(unique("delegate"));
        String delegation = am().body("{\"createDelegationRequests\":[{\"controlSetId\":\"Operations\",\"roleArn\":\""
                + resourceOwner + "\",\"roleType\":\"RESOURCE_OWNER\",\"comment\":\"please review\"},{\"controlSetId\":"
                + "\"Missing\",\"roleArn\":\"" + resourceOwner + "\",\"roleType\":\"RESOURCE_OWNER\"}]}")
                .post(base + "/delegations").then().statusCode(200).body("delegations", hasSize(1))
                .body("errors", hasSize(1)).extract().path("delegations[0].id");
        am().get("/delegations").then().statusCode(200).body("delegations.id", hasItem(delegation));
        am().get("/notifications").then().statusCode(200).body("notifications.assessmentId", hasItem(id));
        am().body("{\"status\":\"REVIEWED\",\"comment\":\"done\"}").put(base + "/controlSets/Operations/status")
                .then().statusCode(200).body("controlSet.status", equalTo("REVIEWED"))
                .body("controlSet.delegations[0].status", equalTo("COMPLETE"));
        am().body("{\"controlStatus\":\"REVIEWED\",\"commentBody\":\"looks good\"}")
                .put(base + "/controlSets/Operations/controls/" + controlId).then().statusCode(200)
                .body("control.status", equalTo("REVIEWED")).body("control.comments[0].commentBody",
                        equalTo("looks good"));

        Map<String, String> upload = am().queryParam("fileName", "inventory.csv").get("/evidenceFileUploadUrl")
                .then().statusCode(200).extract().path("$");
        String fileName = upload.get("evidenceFileName");
        String uploadUrl = upload.get("uploadUrl");
        assertTrue(uploadUrl.contains("/_auditmanager-evidence/"), uploadUrl);
        given().body("host,owner\nweb,ops\n").put(uploadUrl.replaceAll("^https?://[^/]+", "")).then()
                .statusCode(200);
        given().body("x").put("/_auditmanager-evidence/unknown").then().statusCode(404);
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request")
                .body("evidence").put("/" + reports + "/evidence/screenshot.png").then().statusCode(200);
        am().body("{\"manualEvidence\":[{\"textResponse\":\"We rotate keys yearly.\"},{\"evidenceFileName\":\""
                + fileName + "\"},{\"s3ResourcePath\":\"s3://" + reports + "/evidence/screenshot.png\"},"
                + "{\"s3ResourcePath\":\"s3://" + reports + "/missing\"},{\"evidenceFileName\":\"nope_x\"}]}")
                .post(base + "/controlSets/Operations/controls/" + controlId + "/evidence").then().statusCode(200)
                .body("errors", hasSize(2));

        String folderId = am().get(base + "/evidenceFolders").then().statusCode(200)
                .body("evidenceFolders", hasSize(1)).body("evidenceFolders[0].totalEvidence", equalTo(3))
                .body("evidenceFolders[0].evidenceByTypeManualCount", equalTo(3))
                .extract().path("evidenceFolders[0].id");
        am().get(base + "/evidenceFolders-by-assessment-control/Operations/" + controlId).then().statusCode(200)
                .body("evidenceFolders.id", hasItem(folderId));
        List<String> evidenceIds = am().get(base + "/controlSets/Operations/evidenceFolders/" + folderId
                + "/evidence").then().statusCode(200).body("evidence", hasSize(3))
                .body("evidence.dataSource", everyItem(equalTo("Manual"))).extract().path("evidence.id");
        am().get(base + "/controlSets/Operations/evidenceFolders/" + folderId + "/evidence/" + evidenceIds.get(0))
                .then().statusCode(200).body("evidence.evidenceFolderId", equalTo(folderId));
        am().body("{\"evidenceFolderId\":\"" + folderId + "\",\"evidenceIds\":[\"" + evidenceIds.get(0) + "\",\""
                + UUID.randomUUID() + "\"]}").put(base + "/batchAssociateToAssessmentReport").then().statusCode(200)
                .body("evidenceIds", hasSize(1)).body("errors", hasSize(1));
        am().get(base + "/controlSets/Operations/evidenceFolders/" + folderId).then().statusCode(200)
                .body("evidenceFolder.assessmentReportSelectionCount", equalTo(1));

        String reportId = am().body("{\"name\":\"quarterly\"}").post(base + "/reports").then().statusCode(200)
                .body("assessmentReport.status", equalTo("COMPLETE")).extract().path("assessmentReport.id");
        am().get("/assessmentReports").then().statusCode(200).body("assessmentReports.id", hasItem(reportId));
        String link = am().get(base + "/reports/" + reportId + "/url").then().statusCode(200)
                .body("preSignedUrl.hyperlinkName", containsString("quarterly")).extract().path("preSignedUrl.link");
        assertTrue(link.contains(reports), link);
        String key = "reports/" + am().get(base + "/reports/" + reportId + "/url").then().extract()
                .<String>path("preSignedUrl.hyperlinkName");
        am().body("{\"s3RelativePath\":\"s3://" + reports + "/" + key + "\"}").post("/assessmentReports/integrity")
                .then().statusCode(200).body("signatureValid", equalTo(true))
                .body("signatureAlgorithm", equalTo("SHA256withRSA")).body("validationErrors", empty());
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request")
                .body("tampered").put("/" + reports + "/" + key).then().statusCode(200);
        am().body("{\"s3RelativePath\":\"s3://" + reports + "/" + key + "\"}").post("/assessmentReports/integrity")
                .then().statusCode(200).body("signatureValid", equalTo(false));
        am().body("{\"s3RelativePath\":\"s3://" + reports + "/nothing.zip\"}").post("/assessmentReports/integrity")
                .then().statusCode(404);
        am().delete(base + "/reports/" + reportId).then().statusCode(200);

        am().get(base + "/changelogs").then().statusCode(200)
                .body("changeLogs.action", hasItem("IMPORT_EVIDENCE")).body("changeLogs.objectType",
                        hasItem("DELEGATION"));
        am().queryParam("controlSetId", "Operations").queryParam("controlId", controlId).get(base + "/changelogs")
                .then().statusCode(200).body("changeLogs.objectType", everyItem(equalTo("CONTROL")));
        am().get("/insights/assessments/" + id).then().statusCode(200)
                .body("insights.totalAssessmentControlsCount", equalTo(1))
                .body("insights.inconclusiveEvidenceCount", equalTo(3));
        am().queryParam("assessmentId", id).get("/insights/control-domains-by-assessment").then().statusCode(200)
                .body("controlDomainInsights[0].id", equalTo("UNCATEGORIZED"));

        am().body("{\"status\":\"INACTIVE\"}").put(base + "/status").then().statusCode(200)
                .body("assessment.metadata.status", equalTo("INACTIVE"));
        am().body("{\"status\":\"ACTIVE\"}").put(base + "/status").then().statusCode(400);
        am().body("{\"scope\":{\"awsAccounts\":[{\"id\":\"" + ACCOUNT + "\"}]}}").put(base).then().statusCode(400);
        am().delete(base).then().statusCode(200);
        am().get(base).then().statusCode(404);
    }

    @Test
    void configRuleEvaluationsBecomeComplianceEvidence() {
        register(ACCOUNT, "us-east-1");
        String rule = unique("public-read");
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/config/aws4_request")
                .header("X-Amz-Target", "StarlingDoveService.PutConfigRule")
                .body("{\"ConfigRule\":{\"ConfigRuleName\":\"" + rule + "\",\"Source\":{\"Owner\":\"AWS\","
                        + "\"SourceIdentifier\":\"S3_BUCKET_PUBLIC_READ_PROHIBITED\"}}}").post("/").then()
                .statusCode(200);
        long now = Instant.now().getEpochSecond();
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/config/aws4_request")
                .header("X-Amz-Target", "StarlingDoveService.PutEvaluations")
                .body("{\"ResultToken\":\"" + rule + ":token\",\"Evaluations\":[{\"ComplianceResourceType\":"
                        + "\"AWS::S3::Bucket\",\"ComplianceResourceId\":\"open-bucket\",\"ComplianceType\":"
                        + "\"NON_COMPLIANT\",\"OrderingTimestamp\":" + now + "},{\"ComplianceResourceType\":"
                        + "\"AWS::S3::Bucket\",\"ComplianceResourceId\":\"closed-bucket\",\"ComplianceType\":"
                        + "\"COMPLIANT\",\"OrderingTimestamp\":" + now + "}]}").post("/").then().statusCode(200);
        String controlId = control(unique("public-read"), "[{\"sourceName\":\"rule\",\"sourceSetUpOption\":"
                + "\"System_Controls_Mapping\",\"sourceType\":\"AWS_Config\",\"sourceKeyword\":{\"keywordInputType\":"
                + "\"SELECT_FROM_LIST\",\"keywordValue\":\"S3_BUCKET_PUBLIC_READ_PROHIBITED\"}}]");
        String id = assessment(framework(unique("framework"), controlId), role(unique("owner")),
                bucket(unique("reports")));
        am().get("/insights/assessments/" + id).then().statusCode(200)
                .body("insights.noncompliantEvidenceCount", greaterThanOrEqualTo(1))
                .body("insights.compliantEvidenceCount", greaterThanOrEqualTo(1))
                .body("insights.assessmentControlsCountByNoncompliantEvidence", equalTo(1));
        String folder = am().get("/assessments/" + id + "/evidenceFolders").then().statusCode(200)
                .body("evidenceFolders[0].dataSource", equalTo("AWS Config"))
                .body("evidenceFolders[0].evidenceByTypeComplianceCheckIssuesCount", greaterThanOrEqualTo(1))
                .extract().path("evidenceFolders[0].id");
        am().get("/assessments/" + id + "/controlSets/Operations/evidenceFolders/" + folder + "/evidence").then()
                .statusCode(200).body("evidence.complianceCheck", hasItem("NON_COMPLIANT"))
                .body("evidence.attributes.configRuleName", hasItem(rule));
        am().get("/insights").then().statusCode(200).body("insights.activeAssessmentsCount", greaterThanOrEqualTo(1));
    }

    @Test
    void aFrameworkSharedWithAnotherAccountIsCopiedOnAccept() {
        register(ACCOUNT, "us-east-1");
        String recipient = "222233334444";
        register(recipient, "us-west-2");
        String name = unique("shared");
        String frameworkId = framework(name, control(unique("manual"), MANUAL));
        String requestId = am().body("{\"destinationAccount\":\"" + recipient + "\",\"destinationRegion\":"
                + "\"us-west-2\",\"comment\":\"for you\"}").post("/assessmentFrameworks/" + frameworkId
                + "/shareRequests").then().statusCode(200)
                .body("assessmentFrameworkShareRequest.status", equalTo("ACTIVE"))
                .body("assessmentFrameworkShareRequest.customControlsCount", equalTo(1))
                .extract().path("assessmentFrameworkShareRequest.id");
        am(recipient, "us-west-2").queryParam("requestType", "RECEIVED").get("/assessmentFrameworkShareRequests")
                .then().statusCode(200).body("assessmentFrameworkShareRequests.id", hasItem(requestId));
        am().body("{\"requestType\":\"SENT\",\"action\":\"ACCEPT\"}").put("/assessmentFrameworkShareRequests/"
                + requestId).then().statusCode(400);
        am(recipient, "us-west-2").body("{\"requestType\":\"RECEIVED\",\"action\":\"ACCEPT\"}")
                .put("/assessmentFrameworkShareRequests/" + requestId).then().statusCode(200)
                .body("assessmentFrameworkShareRequest.status", equalTo("SHARED"));
        am().queryParam("requestType", "SENT").get("/assessmentFrameworkShareRequests").then().statusCode(200)
                .body("assessmentFrameworkShareRequests.find { it.id == '" + requestId + "' }.status",
                        equalTo("SHARED"));
        List<Map<String, Object>> copies = am(recipient, "us-west-2").queryParam("frameworkType", "Custom")
                .get("/assessmentFrameworks").then().statusCode(200).extract().path("frameworkMetadataList");
        assertTrue(copies.stream().anyMatch(f -> name.equals(f.get("name"))), copies.toString());
        am(recipient, "us-west-2").queryParam("controlType", "Custom").get("/controls").then().statusCode(200)
                .body("controlMetadataList", not(empty()));
        am(recipient, "us-west-2").queryParam("requestType", "RECEIVED")
                .delete("/assessmentFrameworkShareRequests/" + requestId).then().statusCode(200);
    }

    @Test
    void tagsAndCatalogReads() {
        register(ACCOUNT, "us-east-1");
        String arn = am().body("{\"name\":\"" + unique("tagged") + "\",\"controlMappingSources\":" + MANUAL
                + ",\"tags\":{\"team\":\"audit\"}}").post("/controls").then().statusCode(200)
                .extract().path("control.arn");
        am().get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("audit"));
        am().body("{\"tags\":{\"stage\":\"dev\"}}").post("/tags/" + arn).then().statusCode(200);
        am().queryParam("tagKeys", "team").delete("/tags/" + arn).then().statusCode(200);
        Map<String, String> tags = am().get("/tags/" + arn).then().statusCode(200).extract().path("tags");
        assertEquals(Map.of("stage", "dev"), tags);
        am().get("/tags/arn:aws:auditmanager:us-east-1:" + ACCOUNT + ":control/" + UUID.randomUUID()).then()
                .statusCode(404);

        am().get("/services").then().statusCode(200).body("serviceMetadata.name", hasItem("s3"));
        am().queryParam("source", "AWS_Config").get("/dataSourceKeywords").then().statusCode(200)
                .body("keywords", hasItem("S3_BUCKET_PUBLIC_READ_PROHIBITED"));
        am().queryParam("source", "AWS_Config").queryParam("maxResults", 2).get("/dataSourceKeywords").then()
                .statusCode(200).body("keywords", hasSize(2)).body("nextToken", notNullValue());
        String domain = am().get("/insights/control-domains").then().statusCode(200).extract()
                .path("controlDomainInsights[0].id");
        am().queryParam("controlDomainId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").get("/insights/controls").then()
                .statusCode(404);
        if (domain != null) {
            am().queryParam("controlDomainId", domain).get("/insights/controls").then().statusCode(200);
        }
    }
}
