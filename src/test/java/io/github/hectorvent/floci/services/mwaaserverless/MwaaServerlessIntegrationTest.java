package io.github.hectorvent.floci.services.mwaaserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 MWAA Serverless coverage used by Alchemy Workflow.test.ts:
 * GetWorkflow on a missing well-formed ARN returns ResourceNotFoundException;
 * create / update description / tags / delete round-trip and auto-create the
 * per-workflow log group.
 */
@QuarkusTest
class MwaaServerlessIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "AmazonMWAAServerless.";
    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String MISSING_ARN =
            "arn:aws:airflow-serverless:" + EAST + ":" + ACCOUNT
                    + ":workflow/alchemy-nonexistent-probe-0123456789";
    private static final String ROLE =
            "arn:aws:iam::" + ACCOUNT + ":role/alchemy-mwaa-serverless-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getWorkflow_missing_returnsResourceNotFoundException() {
        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + MISSING_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceId", equalTo(MISSING_ARN))
                .body("ResourceType", equalTo("Workflow"));
    }

    @Test
    void getWorkflow_malformedArn_returnsValidationException() {
        mwaa("GetWorkflow",
                "{\"WorkflowArn\":\"arn:aws:airflow-serverless:" + EAST + ":"
                        + ACCOUNT + ":workflow/no-suffix\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createUpdateTagDeleteWorkflowRoundTrip() {
        String authorization = auth(EAST);

        String arn = mwaa("CreateWorkflow", """
                {
                  "Name":"alchemy-mwaa-serverless-it",
                  "DefinitionS3Location":{"Bucket":"defs","ObjectKey":"workflows/test.yaml"},
                  "RoleArn":"%s",
                  "Description":"alchemy mwaa-serverless test workflow",
                  "Tags":{"fixture":"mwaa-serverless-workflow"}
                }
                """.formatted(ROLE), authorization)
                .then()
                .statusCode(200)
                .body("WorkflowArn", startsWith("arn:aws:airflow-serverless:" + EAST + ":"))
                .body("WorkflowStatus", equalTo("READY"))
                .body("WorkflowVersion", equalTo("1"))
                .extract().path("WorkflowArn");

        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}", authorization)
                .then()
                .statusCode(200)
                .body("Name", equalTo("alchemy-mwaa-serverless-it"))
                .body("Description", equalTo("alchemy mwaa-serverless test workflow"))
                .body("RoleArn", equalTo(ROLE))
                .body("DefinitionS3Location.Bucket", equalTo("defs"));

        mwaa("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}", authorization)
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("mwaa-serverless-workflow"));

        mwaa("UpdateWorkflow", """
                {
                  "WorkflowArn":"%s",
                  "DefinitionS3Location":{"Bucket":"defs","ObjectKey":"workflows/test.yaml"},
                  "RoleArn":"%s",
                  "Description":"alchemy mwaa-serverless test workflow (updated)"
                }
                """.formatted(arn, ROLE), authorization)
                .then()
                .statusCode(200)
                .body("WorkflowArn", equalTo(arn))
                .body("WorkflowVersion", equalTo("2"));

        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}", authorization)
                .then()
                .statusCode(200)
                .body("Description", equalTo("alchemy mwaa-serverless test workflow (updated)"))
                .body("WorkflowVersion", equalTo("2"));

        mwaa("ListWorkflows", "{}", authorization)
                .then()
                .statusCode(200)
                .body("Workflows.Name", hasItem("alchemy-mwaa-serverless-it"));

        String resourceId = arn.substring(arn.lastIndexOf('/') + 1);
        String logGroup = "/aws/mwaa-serverless/" + resourceId + "/";
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .header("Authorization", authLogs(EAST))
                .body("{\"logGroupNamePrefix\":\"" + logGroup + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("logGroups.logGroupName", hasItem(logGroup));

        mwaa("DeleteWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}", authorization)
                .then()
                .statusCode(200)
                .body("WorkflowArn", equalTo(arn));

        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}", authorization)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        mwaa("ListWorkflows", "{}", authorization)
                .then()
                .statusCode(200)
                .body("Workflows.Name", not(hasItem("alchemy-mwaa-serverless-it")));
    }

    @Test
    void listTagsForResource_missing_returnsResourceNotFoundException() {
        mwaa("ListTagsForResource", "{\"ResourceArn\":\"" + MISSING_ARN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response mwaa(String action, String body) {
        return mwaa(action, body, auth(EAST));
    }

    private static Response mwaa(String action, String body, String authorization) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region
                + "/airflow-serverless/aws4_request";
    }

    private static String authLogs(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/logs/aws4_request";
    }
}
