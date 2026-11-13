package io.floci.aws.services.airflowserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MWAA Serverless runs in Apache Airflow: operators act on Floci, code packages run, failures and stops report. */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@Tag("docker")
class AirflowServerlessDockerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String run(String arn) {
        return AirflowServerlessIntegrationTest.mwaa("StartWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\"}")
                .statusCode(200).extract().path("RunId");
    }

    private static void awaitState(String arn, String runId, String state) {
        await().atMost(Duration.ofMinutes(8)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                AirflowServerlessIntegrationTest.mwaa("GetWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\""
                        + runId + "\"}").statusCode(200).body("RunDetail.RunState", equalTo(state)));
    }

    private static byte[] zip(String name, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void runsOperatorsCodeAndFailures() throws IOException {
        AirflowServerlessIntegrationTest.put("mwaa-run", "w/etl.yaml", AirflowServerlessIntegrationTest
                .definition("mwaa-run"));
        String etl = AirflowServerlessIntegrationTest.create("etl-run", "mwaa-run", "w/etl.yaml");
        String runId = run(etl);
        awaitState(etl, runId, "SUCCESS");
        given().header("Authorization", AirflowServerlessIntegrationTest.auth("s3")).get("/mwaa-run/out/hello.txt")
                .then().statusCode(200).body(equalTo("hello"));
        List<String> instances = AirflowServerlessIntegrationTest.mwaa("ListTaskInstances", "{\"WorkflowArn\":\"" + etl
                + "\",\"RunId\":\"" + runId + "\"}").body("TaskInstances", hasSize(2))
                .body("TaskInstances.Status", hasItem("SUCCESS")).extract().path("TaskInstances.TaskInstanceId");
        String listed = null;
        for (String instance : instances) {
            String xcom = AirflowServerlessIntegrationTest.mwaa("GetTaskInstance", "{\"WorkflowArn\":\"" + etl
                    + "\",\"RunId\":\"" + runId + "\",\"TaskInstanceId\":\"" + instance + "\"}").statusCode(200)
                    .extract().path("Xcom.return_value");
            listed = xcom != null ? xcom : listed;
        }
        assertTrue(listed != null && listed.contains("out/hello.txt"), String.valueOf(listed));
        AirflowServerlessIntegrationTest.mwaa("ListWorkflowRuns", "{\"WorkflowArn\":\"" + etl + "\"}")
                .body("WorkflowRuns[0].RunDetailSummary.Status", equalTo("SUCCESS"));

        given().header("Authorization", AirflowServerlessIntegrationTest.auth("s3")).contentType("application/zip")
                .body(zip("greet.py", "def hello(**context):\n    print('hello from code')\n    return 'greeted'\n\n"
                        + "def boom(**context):\n    raise ValueError('boom happened')\n"))
                .put("/mwaa-run/code.zip").then().statusCode(200);
        AirflowServerlessIntegrationTest.put("mwaa-run", "w/code.yaml", """
                code:
                  tasks:
                    hello:
                      operator: airflow.providers.standard.operators.python.PythonOperator
                      python_callable: greet.hello
                    boom:
                      operator: airflow.providers.standard.operators.python.PythonOperator
                      python_callable: greet.boom
                      dependencies: [hello]
                    after:
                      operator: airflow.providers.standard.operators.bash.BashOperator
                      bash_command: echo never
                      dependencies: [boom]
                """);
        String code = AirflowServerlessIntegrationTest.mwaa("CreateWorkflow", "{\"Name\":\"code-run\","
                + "\"DefinitionS3Location\":{\"Bucket\":\"mwaa-run\",\"ObjectKey\":\"w/code.yaml\"},\"Code\":{"
                + "\"S3Location\":{\"Bucket\":\"mwaa-run\",\"ObjectKey\":\"code.zip\"}},\"RoleArn\":\""
                + AirflowServerlessIntegrationTest.ROLE + "\"}").statusCode(200).extract().path("WorkflowArn");
        String failed = run(code);
        awaitState(code, failed, "FAILED");
        AirflowServerlessIntegrationTest.mwaa("ListTaskInstances", "{\"WorkflowArn\":\"" + code + "\",\"RunId\":\""
                + failed + "\"}").body("TaskInstances.Status", hasItem("UPSTREAM_FAILED"))
                .body("TaskInstances.Status", hasItem("FAILED")).body("TaskInstances.Status", hasItem("SUCCESS"));
        List<String> ids = AirflowServerlessIntegrationTest.mwaa("ListTaskInstances", "{\"WorkflowArn\":\"" + code
                + "\",\"RunId\":\"" + failed + "\"}").extract().path("TaskInstances.TaskInstanceId");
        boolean sawError = false;
        for (String id : ids) {
            String error = AirflowServerlessIntegrationTest.mwaa("GetTaskInstance", "{\"WorkflowArn\":\"" + code
                    + "\",\"RunId\":\"" + failed + "\",\"TaskInstanceId\":\"" + id + "\"}").extract()
                    .path("ErrorMessage");
            sawError |= error != null && error.contains("boom happened");
        }
        assertTrue(sawError);
    }

    @Test
    void stopsRuns() {
        AirflowServerlessIntegrationTest.put("mwaa-stop", "w/slow.yaml", """
                slow:
                  tasks:
                    wait:
                      operator: airflow.providers.standard.operators.bash.BashOperator
                      bash_command: sleep 300
                """);
        String arn = AirflowServerlessIntegrationTest.create("slow-run", "mwaa-stop", "w/slow.yaml");
        String runId = run(arn);
        awaitState(arn, runId, "RUNNING");
        AirflowServerlessIntegrationTest.mwaa("StopWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\""
                + runId + "\"}").statusCode(200).body("Status", equalTo("STOPPING"));
        awaitState(arn, runId, "STOPPED");
        AirflowServerlessIntegrationTest.mwaa("StopWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\""
                + runId + "\"}").statusCode(400).body("message", containsString("STOPPED"));
    }
}
