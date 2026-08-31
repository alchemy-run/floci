package io.github.hectorvent.floci.services.mwaaserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 coverage for Alchemy MWAAServerless/Bindings.test.ts: start / get /
 * stop / list runs, list versions, and typed not-found probes on a fake run.
 */
@QuarkusTest
class MwaaServerlessBindingsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "AmazonMWAAServerless.";
    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE =
            "arn:aws:iam::" + ACCOUNT + ":role/alchemy-mwaa-serverless-bind-role";
    private static final String FAKE_RUN = "00000000-0000-4000-8000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listWorkflowVersions_returnsLatestVersion() {
        String arn = createWorkflow("floci-mwaas-versions");
        mwaa("ListWorkflowVersions", "{\"WorkflowArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("WorkflowVersions.WorkflowVersion", hasItem("1"))
                .body("WorkflowVersions.IsLatestVersion", hasItem(true));
    }

    @Test
    void fakeRunProbes_returnTypedOutcomes() {
        String arn = createWorkflow("floci-mwaas-fake-run");

        mwaa("GetWorkflowRun",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + FAKE_RUN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("WorkflowRun"));

        mwaa("ListTaskInstances",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + FAKE_RUN + "\"}")
                .then()
                .statusCode(200)
                .body("TaskInstances", hasSize(0));

        mwaa("GetTaskInstance",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + FAKE_RUN
                        + "\",\"TaskInstanceId\":\"" + FAKE_RUN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        mwaa("StopWorkflowRun",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + FAKE_RUN + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startGetStopListWorkflowRunRoundTrip() {
        String arn = createWorkflow("floci-mwaas-run-rt");

        mwaa("ListWorkflowRuns", "{\"WorkflowArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("WorkflowRuns", hasSize(0));

        String runId = mwaa("StartWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("RunId", notNullValue())
                .body("Status", equalTo("RUNNING"))
                .extract().path("RunId");

        mwaa("GetWorkflowRun",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + runId + "\"}")
                .then()
                .statusCode(200)
                .body("RunId", equalTo(runId))
                .body("RunType", equalTo("ON_DEMAND"))
                .body("RunDetail.RunState", equalTo("RUNNING"));

        mwaa("StopWorkflowRun",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + runId + "\"}")
                .then()
                .statusCode(200)
                .body("RunId", equalTo(runId))
                .body("Status", equalTo("STOPPED"));

        mwaa("GetWorkflowRun",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + runId + "\"}")
                .then()
                .statusCode(200)
                .body("RunDetail.RunState", equalTo("STOPPED"));

        mwaa("ListWorkflowRuns", "{\"WorkflowArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("WorkflowRuns.RunId", hasItem(runId));

        String taskId = mwaa("ListTaskInstances",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + runId + "\"}")
                .then()
                .statusCode(200)
                .body("TaskInstances", hasSize(1))
                .extract().path("TaskInstances[0].TaskInstanceId");

        mwaa("GetTaskInstance",
                "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"" + runId
                        + "\",\"TaskInstanceId\":\"" + taskId + "\"}")
                .then()
                .statusCode(200)
                .body("TaskInstanceId", equalTo(taskId))
                .body("Status", equalTo("SUCCESS"));
    }

    private static String createWorkflow(String namePrefix) {
        String name = namePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return mwaa("CreateWorkflow", """
                {
                  "Name":"%s",
                  "DefinitionS3Location":{"Bucket":"defs","ObjectKey":"workflows/bindings.yaml"},
                  "RoleArn":"%s",
                  "Description":"alchemy mwaa-serverless bindings fixture"
                }
                """.formatted(name, ROLE))
                .then()
                .statusCode(200)
                .body("WorkflowArn", notNullValue())
                .body("WorkflowStatus", equalTo("READY"))
                .extract().path("WorkflowArn");
    }

    private static Response mwaa(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", auth())
                .body(body)
                .when()
                .post("/");
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + EAST
                + "/airflow-serverless/aws4_request";
    }
}
