package io.github.hectorvent.floci.services.auditmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Audit Manager restJson1 account status, control, framework, and assessment APIs. */
@QuarkusTest
class AuditManagerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String CONTROL_BODY = """
            {
              "name":"lifecycle-control",
              "description":"initial control",
              "controlMappingSources":[{
                "sourceName":"manual-evidence",
                "sourceDescription":"Manually uploaded evidence",
                "sourceSetUpOption":"Procedural_Controls_Mapping",
                "sourceType":"MANUAL"
              }],
              "tags":{"fixture":"auditmanager"}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAccountStatusReturnsActiveByDefault() {
        given()
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/account/status")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void registerAccountIsIdempotentOnAnActiveAccount() {
        String authorization = auth("000000000402", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/account/registerAccount")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/account/registerAccount")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void accountLevelBindingsReturnDataAndTypedErrors() {
        String authorization = auth("000000000406", EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/services")
                .then()
                .statusCode(200)
                .body("serviceMetadata.size()", equalTo(5))
                .body("serviceMetadata[0].name", equalTo("s3"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/insights")
                .then()
                .statusCode(200)
                .body("insights.activeAssessmentsCount", equalTo(0))
                .body("insights.totalAssessmentControlsCount", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/insights/control-domains")
                .then()
                .statusCode(200)
                .body("controlDomainInsights.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/insights/controls?controlDomainId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .then()
                .statusCode(200)
                .body("controlInsightsMetadata.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/delegations")
                .then()
                .statusCode(200)
                .body("delegations.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/evidenceFileUploadUrl?fileName=alchemy-evidence.txt")
                .then()
                .statusCode(200)
                .body("evidenceFileName", equalTo("alchemy-evidence.txt"))
                .body("uploadUrl", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/assessmentReports")
                .then()
                .statusCode(200)
                .body("assessmentReports.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"s3RelativePath\":\"s3://alchemy-nonexistent-bucket/report.zip\"}")
                .when()
                .post("/assessmentReports/integrity")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/dataSourceKeywords?source=AWS_Cloudtrail")
                .then()
                .statusCode(200)
                .body("keywords.size()", equalTo(4))
                .body("keywords[0]", equalTo("ConsoleLogin"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notifications")
                .then()
                .statusCode(200)
                .body("notifications.size()", equalTo(0));
    }

    @Test
    void getControlOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000403", EAST))
                .when()
                .get("/controls/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
                .body("resourceType", equalTo("AWS::AuditManager::Control"));
    }

    @Test
    void controlFrameworkAndAssessmentCreateUpdateDeleteLifecycle() {
        String authorization = auth("000000000404", EAST);

        String controlId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CONTROL_BODY)
                .when()
                .post("/controls")
                .then()
                .statusCode(200)
                .body("control.id", notNullValue())
                .body("control.type", equalTo("Custom"))
                .body("control.name", equalTo("lifecycle-control"))
                .body("control.tags.fixture", equalTo("auditmanager"))
                .extract()
                .path("control.id");
        String controlArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/controls/" + controlId)
                .then()
                .statusCode(200)
                .body("control.arn", notNullValue())
                .extract()
                .path("control.arn");
        assertTrue(controlArn.contains(":control/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"lifecycle-control",
                          "description":"updated control",
                          "controlMappingSources":[{
                            "sourceName":"manual-evidence",
                            "sourceSetUpOption":"Procedural_Controls_Mapping",
                            "sourceType":"MANUAL"
                          }]
                        }
                        """)
                .when()
                .put("/controls/" + controlId)
                .then()
                .statusCode(200)
                .body("control.description", equalTo("updated control"))
                .body("control.id", equalTo(controlId));

        String frameworkId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"lifecycle-framework",
                          "description":"initial framework",
                          "complianceType":"Internal",
                          "controlSets":[{"name":"Operations","controls":[{"id":"%s"}]}],
                          "tags":{"fixture":"auditmanager"}
                        }
                        """.formatted(controlId))
                .when()
                .post("/assessmentFrameworks")
                .then()
                .statusCode(200)
                .body("framework.type", equalTo("Custom"))
                .body("framework.controlSets[0].controls[0].id", equalTo(controlId))
                .extract()
                .path("framework.id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"lifecycle-framework",
                          "description":"updated framework",
                          "complianceType":"Internal",
                          "controlSets":[{"name":"Operations","controls":[{"id":"%s"}]}]
                        }
                        """.formatted(controlId))
                .when()
                .put("/assessmentFrameworks/" + frameworkId)
                .then()
                .statusCode(200)
                .body("framework.description", equalTo("updated framework"))
                .body("framework.id", equalTo(frameworkId));

        String assessmentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"lifecycle-assessment",
                          "description":"initial assessment",
                          "frameworkId":"%s",
                          "assessmentReportsDestination":{"destinationType":"S3","destination":"s3://audit-reports"},
                          "roles":[{"roleType":"PROCESS_OWNER","roleArn":"arn:aws:iam::000000000404:role/AuditOwner"}],
                          "tags":{"fixture":"auditmanager"}
                        }
                        """.formatted(frameworkId))
                .when()
                .post("/assessments")
                .then()
                .statusCode(200)
                .body("assessment.metadata.status", equalTo("ACTIVE"))
                .body("assessment.framework.id", equalTo(frameworkId))
                .extract()
                .path("assessment.metadata.id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "assessmentName":"lifecycle-assessment",
                          "assessmentDescription":"updated assessment",
                          "scope":{"awsAccounts":[{"id":"000000000404"}]},
                          "assessmentReportsDestination":{"destinationType":"S3","destination":"s3://audit-reports"},
                          "roles":[{"roleType":"PROCESS_OWNER","roleArn":"arn:aws:iam::000000000404:role/AuditOwner"}]
                        }
                        """)
                .when()
                .put("/assessments/" + assessmentId)
                .then()
                .statusCode(200)
                .body("assessment.metadata.description", equalTo("updated assessment"))
                .body("assessment.metadata.id", equalTo(assessmentId));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + controlArn)
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("auditmanager", tags.get("fixture"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/assessments/" + assessmentId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/assessments/" + assessmentId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/assessmentFrameworks/" + frameworkId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .delete("/controls/" + controlId)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/controls/" + controlId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listControlsReturnsCreatedCustomControls() {
        String authorization = auth("000000000405", EAST);
        createControl(authorization, "listed-control");
        given()
                .header("Authorization", authorization)
                .when()
                .get("/controls?controlType=Custom")
                .then()
                .statusCode(200)
                .body("controlMetadataList.size()", equalTo(1))
                .body("controlMetadataList[0].name", equalTo("listed-control"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/auditmanager/aws4_request";
    }

    private static Response createControl(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "controlMappingSources":[{
                            "sourceName":"manual-evidence",
                            "sourceSetUpOption":"Procedural_Controls_Mapping",
                            "sourceType":"MANUAL"
                          }]
                        }
                        """.formatted(name))
                .when()
                .post("/controls")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
