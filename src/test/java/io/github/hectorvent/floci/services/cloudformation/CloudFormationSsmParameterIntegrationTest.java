package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationSsmParameterIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void arnUpdateTagsSignalsAndDriftUseTheLiveParameter() {
        String name = "cfn-observed-parameter-stack";
        String parameter = "/cfn/observed/parameter";
        String template = """
                {"Parameters":{"Value":{"Type":"String"}},"Resources":{"Param":{"Type":"AWS::SSM::Parameter",
                "Properties":{"Name":"/cfn/observed/parameter","Type":"String","Value":{"Ref":"Value"}}}}}
                """;
        String id = query("CreateStack").formParam("StackName", name).formParam("TemplateBody", template)
                .formParam("Parameters.member.1.ParameterKey", "Value").formParam("Parameters.member.1.ParameterValue", "one")
                .when().post("/").then().statusCode(200).extract().xmlPath().getString("CreateStackResponse.CreateStackResult.StackId");
        try {
            query("UpdateStack").formParam("StackName", id).formParam("TemplateBody", template)
                    .formParam("Parameters.member.1.ParameterKey", "Value").formParam("Parameters.member.1.ParameterValue", "two")
                    .formParam("Tags.member.1.Key", "env").formParam("Tags.member.1.Value", "prod")
                    .when().post("/").then().statusCode(200).body(containsString(id));
            query("DescribeStacks").formParam("StackName", id).when().post("/").then().statusCode(200)
                    .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
                    .body(containsString("<Key>env</Key><Value>prod</Value>"));
            given().contentType(SSM_CONTENT_TYPE).header("X-Amz-Target", "AmazonSSM.GetParameter")
                    .body("{\"Name\":\"" + parameter + "\"}").when().post("/").then().statusCode(200)
                    .body("Parameter.Value", org.hamcrest.Matchers.equalTo("two"));
            query("SignalResource").formParam("StackName", id).formParam("LogicalResourceId", "Param")
                    .formParam("UniqueId", "signal-one").formParam("Status", "SUCCESS")
                    .when().post("/").then().statusCode(200).body(containsString("<SignalResourceResult>"));
            query("SignalResource").formParam("StackName", id).formParam("LogicalResourceId", "Missing")
                    .formParam("UniqueId", "signal-one").formParam("Status", "SUCCESS")
                    .when().post("/").then().statusCode(400).body(containsString("ValidationError"));
            assertDrift(id, "IN_SYNC", "IN_SYNC", 0);
            given().contentType(SSM_CONTENT_TYPE).header("X-Amz-Target", "AmazonSSM.PutParameter")
                    .body("{\"Name\":\"" + parameter + "\",\"Type\":\"String\",\"Value\":\"external\",\"Overwrite\":true}")
                    .when().post("/").then().statusCode(200);
            assertDrift(id, "DRIFTED", "MODIFIED", 1);
            query("DescribeStackResourceDrifts").formParam("StackName", id).when().post("/").then().statusCode(200)
                    .body(containsString("<PropertyPath>/Value</PropertyPath>"))
                    .body(containsString("external"));
            given().contentType(SSM_CONTENT_TYPE).header("X-Amz-Target", "AmazonSSM.DeleteParameter")
                    .body("{\"Name\":\"" + parameter + "\"}").when().post("/").then().statusCode(200);
            assertDrift(id, "DRIFTED", "DELETED", 1);
        } finally {
            query("DeleteStack").formParam("StackName", id).when().post("/").then().statusCode(200);
        }
    }

    @Test
    void validationReturnsTemplateMetadataAndRejectsInvalidResources() {
        query("ValidateTemplate").formParam("TemplateBody", "{\"Resources\":{}}")
                .when().post("/").then().statusCode(400).body(containsString("ValidationError"));
        query("ValidateTemplate").formParam("TemplateBody", "{broken")
                .when().post("/").then().statusCode(400).body(containsString("ValidationError"));
        query("ValidateTemplate").formParam("TemplateBody", """
                Parameters:
                  Value:
                    Type: String
                    Default: hello
                Resources:
                  Param:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Type: String
                      Value: !Ref Value
                """).when().post("/").then().statusCode(200)
                .body(containsString("<ParameterKey>Value</ParameterKey>"))
                .body(containsString("<DefaultValue>hello</DefaultValue>"));
        query("DescribeStackDriftDetectionStatus").formParam("StackDriftDetectionId", "missing")
                .when().post("/").then().statusCode(400).body(containsString("ValidationError"));
    }

    private void assertDrift(String id, String stackStatus, String resourceStatus, int count) {
        String detection = query("DetectStackDrift").formParam("StackName", id).when().post("/")
                .then().statusCode(200).extract().xmlPath().getString("DetectStackDriftResponse.DetectStackDriftResult.StackDriftDetectionId");
        query("DescribeStackDriftDetectionStatus").formParam("StackDriftDetectionId", detection)
                .when().post("/").then().statusCode(200)
                .body(containsString("<DetectionStatus>DETECTION_COMPLETE</DetectionStatus>"))
                .body(containsString("<StackDriftStatus>" + stackStatus + "</StackDriftStatus>"))
                .body(containsString("<DriftedStackResourceCount>" + count + "</DriftedStackResourceCount>"));
        query("DescribeStackResourceDrifts").formParam("StackName", id).when().post("/").then().statusCode(200)
                .body(containsString("<LogicalResourceId>Param</LogicalResourceId>"))
                .body(containsString("<StackResourceDriftStatus>" + resourceStatus + "</StackResourceDriftStatus>"));
    }

    private io.restassured.specification.RequestSpecification query(String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260922/us-east-1/cloudformation/aws4_request")
                .formParam("Action", action);
    }

    @Test
    void createStack_resolvesSsmTypedParameterValue() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "/cfn/test/queue-suffix",
                    "Value": "orders-primary",
                    "Type": "String",
                    "Overwrite": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Parameters": {
                "QueueSuffix": {
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/cfn/test/queue-suffix"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-${QueueSuffix}" }
                  }
                }
              },
              "Outputs": {
                "ResolvedSuffix": { "Value": { "Ref": "QueueSuffix" } }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>orders-primary</OutputValue>"))
            .body(not(containsString("<OutputValue>/cfn/test/queue-suffix</OutputValue>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cfn-ssm-orders-primary")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cfn-ssm-orders-primary"));
    }

    @Test
    void createStack_missingSsmParameterFailsWithValidationError() {
        String template = """
            {
              "Parameters": {
                "MissingParam": {
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/cfn/test/does-not-exist"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-missing-${MissingParam}" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-missing-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-missing-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString(
                    "Unable to fetch parameters [/cfn/test/does-not-exist] from parameter store for this account"));
    }

    @Test
    void createStack_secretsManagerReferenceIsNotAPlaintextParameter() {
        given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(SSM_CONTENT_TYPE)
            .body("{\"Name\": \"cfn-ssm-ref-secret\", \"SecretString\": \"s3cr3t\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        String template = """
            {
              "Parameters": {
                "Secret": {
                  "Type": "AWS::SSM::Parameter::Value<String>",
                  "Default": "/aws/reference/secretsmanager/cfn-ssm-ref-secret"
                }
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::Sub": "cfn-ssm-ref-${Secret}" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-secret-ref-param-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ssm-secret-ref-param-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString("Unable to fetch parameters [/aws/reference/secretsmanager/cfn-ssm-ref-secret]"));
    }

    @Test
    void createStack_parameterArnAttributeMatchesTheGetParameterArn() {
        String suffix = Long.toString(System.nanoTime());
        String stackName = "ssm-param-arn-" + suffix;
        String plain = "cfn-arn-plain-" + suffix;
        String nested = "/cfn-arn/nested-" + suffix;
        String template = """
            {
              "Resources": {
                "Plain": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": { "Name": "%s", "Type": "String", "Value": "v" }
                },
                "Nested": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": { "Name": "%s", "Type": "String", "Value": "v" }
                }
              },
              "Outputs": {
                "PlainArn": { "Value": { "Fn::GetAtt": ["Plain", "Arn"] } },
                "NestedArn": { "Value": { "Fn::GetAtt": ["Nested", "Arn"] } }
              }
            }
            """.formatted(plain, nested);

        cfn("CreateStack", stackName).formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertThat(CfnStackWaits.awaitTerminal(stackName).status(), equalTo("CREATE_COMPLETE"));

        XmlPath outputs = cfn("DescribeStacks", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        assertArnMatchesGetParameter(output(outputs, "PlainArn"), plain);
        assertArnMatchesGetParameter(output(outputs, "NestedArn"), nested);

        cfn("DeleteStack", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static String output(XmlPath describe, String key) {
        return describe.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void assertArnMatchesGetParameter(String arn, String name) {
        assertThat(arn, allOf(containsString(":parameter/"), not(containsString("//"))));
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{ \"Name\": \"" + name + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.ARN", equalTo(arn));
    }
}
