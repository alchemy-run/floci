package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * UpdateStack accepts StackId (ARN) as StackName. AWS keeps that StackId stable across
 * updates and applies request tags. Keying a new stack on the raw ARN used to mint a
 * nested id {@code arn:…:stack/<arn>/<uuid>}.
 */
@QuarkusTest
class CloudFormationUpdateByStackIdIntegrationTest {

    private static final String TEMPLATE = """
            {
              "Parameters": { "Value": { "Type": "String" } },
              "Resources": {
                "Param": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": { "Type": "String", "Value": { "Ref": "Value" } }
                }
              },
              "Outputs": { "ParamName": { "Value": { "Ref": "Param" } } }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateStack_byStackId_keepsStableStackIdAndAppliesTags() {
        String stackName = "cfn-update-by-id-" + Long.toString(System.nanoTime(), 36);

        String createXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Value")
            .formParam("Parameters.member.1.ParameterValue", "v1")
            .formParam("Tags.member.1.Key", "alchemy::id")
            .formParam("Tags.member.1.Value", "CfnTestStack")
        .when().post("/")
        .then().statusCode(200)
        .extract().asString();

        String stackId = xmlValue(createXml, "StackId");
        assertThat(stackId, startsWith("arn:aws:cloudformation:"));
        assertThat(stackId, containsString(":stack/" + stackName + "/"));
        assertThat(stackId, not(containsString("stack/arn:")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackId)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<StackId>" + stackId + "</StackId>"));

        String updateXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackId)
            .formParam("TemplateBody", TEMPLATE)
            .formParam("Parameters.member.1.ParameterKey", "Value")
            .formParam("Parameters.member.1.ParameterValue", "v2")
            .formParam("Tags.member.1.Key", "alchemy::id")
            .formParam("Tags.member.1.Value", "CfnTestStack")
            .formParam("Tags.member.2.Key", "env")
            .formParam("Tags.member.2.Value", "prod")
        .when().post("/")
        .then().statusCode(200)
        .extract().asString();

        assertThat(xmlValue(updateXml, "StackId"), equalTo(stackId));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackId)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString("<StackId>" + stackId + "</StackId>"))
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>prod</Value>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackId)
        .when().post("/")
        .then().statusCode(200);
    }

    private static String xmlValue(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end = xml.indexOf(close, start);
        return xml.substring(start + open.length(), end);
    }
}
