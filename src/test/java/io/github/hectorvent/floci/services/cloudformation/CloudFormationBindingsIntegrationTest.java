package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Binding-surface CloudFormation Query ops exercised by Alchemy's
 * {@code test/AWS/CloudFormation/Bindings.test.ts}: ValidateTemplate, ListImports,
 * SignalResource, and the DetectStackDrift family.
 */
@QuarkusTest
class CloudFormationBindingsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void validateTemplate_acceptsAResourcefulTemplateAndRejectsEmptyResources() {
        String valid = """
                {
                  "Resources": {
                    "Param": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": { "Type": "String", "Value": "ok" }
                    }
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "ValidateTemplate")
            .formParam("TemplateBody", valid)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("ValidateTemplateResult"))
            .body(containsString("<Parameters>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "ValidateTemplate")
            .formParam("TemplateBody", "{\"Resources\": {}}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("At least one Resources member must be defined"));
    }

    @Test
    void listImports_rejectsAnUnusedExport_andListsAnImporter() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String exportName = "cfn-bind-export-" + suffix;
        String exporter = "cfn-bind-exporter-" + suffix;
        String importer = "cfn-bind-importer-" + suffix;
        String paramName = "/cfn-bind/" + suffix;

        String exporterTemplate = """
                {
                  "Resources": {
                    "Param": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": { "Name": "%s", "Type": "String", "Value": "v" }
                    }
                  },
                  "Outputs": {
                    "ParamName": {
                      "Value": { "Ref": "Param" },
                      "Export": { "Name": "%s" }
                    }
                  }
                }
                """.formatted(paramName, exportName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", exporter)
            .formParam("TemplateBody", exporterTemplate)
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "ListImports")
            .formParam("ExportName", exportName)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("is not imported by any stack"));

        String importerTemplate = """
                {
                  "Resources": {
                    "Imported": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {
                        "Name": "%s-imported",
                        "Type": "String",
                        "Value": { "Fn::ImportValue": "%s" }
                      }
                    }
                  }
                }
                """.formatted(paramName, exportName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", importer)
            .formParam("TemplateBody", importerTemplate)
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "ListImports")
            .formParam("ExportName", exportName)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>" + importer + "</member>"));
    }

    @Test
    void detectStackDrift_completesAndDescribeResourceDriftsReturnsMembers() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-bind-drift-" + suffix;
        String paramName = "/cfn-bind-drift/" + suffix;

        String template = """
                {
                  "Resources": {
                    "Param": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": { "Name": "%s", "Type": "String", "Value": "drift" }
                    }
                  }
                }
                """.formatted(paramName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);

        String detectXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DetectStackDrift")
            .formParam("StackName", stackName)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackDriftDetectionId>"))
            .extract().asString();

        String detectionId = between(detectXml, "<StackDriftDetectionId>", "</StackDriftDetectionId>");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackDriftDetectionStatus")
            .formParam("StackDriftDetectionId", detectionId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DetectionStatus>DETECTION_COMPLETE</DetectionStatus>"))
            .body(containsString("<StackDriftStatus>IN_SYNC</StackDriftStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResourceDrifts")
            .formParam("StackName", stackName)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Param</LogicalResourceId>"))
            .body(containsString("<StackResourceDriftStatus>IN_SYNC</StackResourceDriftStatus>"));
    }

    @Test
    void signalResource_succeedsForAProvisionedResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-bind-signal-" + suffix;
        String paramName = "/cfn-bind-signal/" + suffix;

        String template = """
                {
                  "Resources": {
                    "Param": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": { "Name": "%s", "Type": "String", "Value": "sig" }
                    }
                  }
                }
                """.formatted(paramName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "SignalResource")
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "Param")
            .formParam("UniqueId", "cfn-bindings-test")
            .formParam("Status", "SUCCESS")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("SignalResourceResponse"))
            .body(not(containsString("<Code>")));
    }

    private static String between(String xml, String open, String close) {
        int start = xml.indexOf(open);
        int end = xml.indexOf(close, start + open.length());
        return xml.substring(start + open.length(), end);
    }
}
