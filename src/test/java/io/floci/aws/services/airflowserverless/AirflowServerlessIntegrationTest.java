package io.floci.aws.services.airflowserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/** MWAA Serverless workflows: definitions checked and snapshotted as versions, tags, log groups and run lookups. */
@QuarkusTest
class AirflowServerlessIntegrationTest {

    static final String ROLE = "arn:aws:iam::000000000000:role/airflow";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static ValidatableResponse mwaa(String action, String body) {
        return given().header("Authorization", auth("airflow-serverless"))
                .header("X-Amz-Target", "AmazonMWAAServerless." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then().log().ifValidationFails();
    }

    static void put(String bucket, String key, String content) {
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(anyOf(equalTo(200),
                equalTo(409)));
        given().header("Authorization", auth("s3")).contentType("application/octet-stream")
                .body(content.getBytes(StandardCharsets.UTF_8)).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    static String definition(String bucket) {
        return """
                etl:
                  default_args:
                    owner: floci
                  schedule: null
                  tasks:
                    write:
                      operator: airflow.providers.amazon.aws.operators.s3.S3CreateObjectOperator
                      s3_bucket: %s
                      s3_key: out/hello.txt
                      data: hello
                    list:
                      operator: airflow.providers.amazon.aws.operators.s3.S3ListOperator
                      bucket: %s
                      prefix: out/
                      dependencies: [write]
                """.formatted(bucket, bucket);
    }

    static String create(String name, String bucket, String key) {
        return mwaa("CreateWorkflow", "{\"Name\":\"" + name + "\",\"DefinitionS3Location\":{\"Bucket\":\"" + bucket
                + "\",\"ObjectKey\":\"" + key + "\"},\"RoleArn\":\"" + ROLE + "\",\"Description\":\"first\","
                + "\"Tags\":{\"team\":\"data\"}}").statusCode(200).body("WorkflowStatus", equalTo("READY"))
                .body("IsLatestVersion", equalTo(true)).extract().path("WorkflowArn");
    }

    @Test
    void workflowsAreVersionedDefinitions() {
        put("mwaa-defs", "workflows/etl.yaml", definition("mwaa-defs"));
        String arn = create("etl", "mwaa-defs", "workflows/etl.yaml");
        mwaa("CreateWorkflow", "{\"Name\":\"etl\",\"DefinitionS3Location\":{\"Bucket\":\"mwaa-defs\",\"ObjectKey\":"
                + "\"workflows/etl.yaml\"},\"RoleArn\":\"" + ROLE + "\"}").statusCode(409)
                .body("__type", containsString("ConflictException"));
        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}").statusCode(200).body("Name", equalTo("etl"))
                .body("RoleArn", equalTo(ROLE)).body("WorkflowDefinition", equalTo("etl"))
                .body("LoggingConfiguration.LogGroupName", containsString("/aws/mwaa-serverless/etl-"));
        String logGroup = mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}").extract()
                .path("LoggingConfiguration.LogGroupName");
        given().header("Authorization", auth("logs")).header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .contentType("application/x-amz-json-1.1").body("{\"logGroupNamePrefix\":\"" + logGroup + "\"}")
                .post("/").then().statusCode(200).body("logGroups", hasSize(1));
        mwaa("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").body("Tags.team", equalTo("data"));
        mwaa("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":{\"env\":\"dev\"}}").statusCode(200);
        mwaa("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").statusCode(200);
        mwaa("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}").body("Tags.env", equalTo("dev"))
                .body("Tags.team", equalTo(null));

        put("mwaa-defs", "workflows/etl.yaml", definition("mwaa-defs").replace("schedule: null",
                "schedule: \"@daily\""));
        String version = mwaa("UpdateWorkflow", "{\"WorkflowArn\":\"" + arn + "\",\"DefinitionS3Location\":{"
                + "\"Bucket\":\"mwaa-defs\",\"ObjectKey\":\"workflows/etl.yaml\"},\"RoleArn\":\"" + ROLE
                + "\",\"Description\":\"second\"}").statusCode(200).extract().path("WorkflowVersion");
        mwaa("ListWorkflowVersions", "{\"WorkflowArn\":\"" + arn + "\"}").body("WorkflowVersions", hasSize(2))
                .body("WorkflowVersions.find { it.IsLatestVersion }.WorkflowVersion", equalTo(version))
                .body("WorkflowVersions.find { it.IsLatestVersion }.ScheduleConfiguration.CronExpression",
                        equalTo("0 0 * * *"));
        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}").body("Description", equalTo("second"))
                .body("WorkflowVersion", equalTo(version));
        mwaa("ListWorkflows", "{}").body("Workflows.WorkflowArn", hasItem(arn));

        mwaa("GetWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"00000000-0000-4000-8000-000000000000\"}")
                .statusCode(404).body("__type", containsString("ResourceNotFoundException"));
        mwaa("StopWorkflowRun", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"00000000-0000-4000-8000-000000000000\"}")
                .statusCode(404);
        mwaa("ListTaskInstances", "{\"WorkflowArn\":\"" + arn + "\",\"RunId\":\"00000000-0000-4000-8000-000000000000\"}")
                .statusCode(200).body("TaskInstances", hasSize(0));

        mwaa("DeleteWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}").statusCode(200);
        mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn + "\"}").body("WorkflowStatus", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mwaa("GetWorkflow", "{\"WorkflowArn\":\"" + arn
                + "\"}").statusCode(404));
        mwaa("ListWorkflows", "{}").body("Workflows.WorkflowArn", not(hasItem(arn)));
        mwaa("GetWorkflow", "{\"WorkflowArn\":\"arn:aws:airflow-serverless:us-east-1:000000000000:workflow/nope-abcdefghij\"}")
                .statusCode(404).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void definitionsAreChecked() {
        put("mwaa-bad", "missing-operator.yaml", "dag:\n  tasks:\n    a:\n      bucket: x\n");
        put("mwaa-bad", "cycle.yaml", "dag:\n  tasks:\n    a:\n      operator: airflow.operators.empty.EmptyOperator\n"
                + "      dependencies: [b]\n    b:\n      operator: airflow.operators.empty.EmptyOperator\n"
                + "      dependencies: [a]\n");
        put("mwaa-bad", "unknown-dependency.yaml", "dag:\n  tasks:\n    a:\n      operator: "
                + "airflow.operators.empty.EmptyOperator\n      dependencies: [z]\n");
        put("mwaa-bad", "custom.yaml", "dag:\n  tasks:\n    a:\n      operator: mine.Operator\n");
        put("mwaa-bad", "bad-schedule.yaml", "dag:\n  schedule: every day\n  tasks:\n    a:\n      operator: "
                + "airflow.operators.empty.EmptyOperator\n");
        for (String[] bad : new String[][] {{"missing-operator.yaml", "names no operator"}, {"cycle.yaml", "cycle"},
                {"unknown-dependency.yaml", "doesn't define"}, {"custom.yaml", "needs a code package"},
                {"bad-schedule.yaml", "not a cron"}, {"absent.yaml", "can't be read"}}) {
            mwaa("CreateWorkflow", "{\"Name\":\"bad\",\"DefinitionS3Location\":{\"Bucket\":\"mwaa-bad\",\"ObjectKey\":\""
                    + bad[0] + "\"},\"RoleArn\":\"" + ROLE + "\"}").statusCode(400)
                    .body("__type", containsString("ValidationException")).body("message", containsString(bad[1]));
        }
        mwaa("CreateWorkflow", "{\"Name\":\"badrole\",\"DefinitionS3Location\":{\"Bucket\":\"mwaa-bad\",\"ObjectKey\":"
                + "\"cycle.yaml\"},\"RoleArn\":\"arn:aws:s3:::nope\"}").statusCode(400);
        String code = "{\"Name\":\"idem\",\"ClientToken\":\"t-1\",\"DefinitionS3Location\":{\"Bucket\":\"mwaa-bad\","
                + "\"ObjectKey\":\"bad-schedule.yaml\"},\"RoleArn\":\"" + ROLE + "\"}";
        put("mwaa-bad", "ok.yaml", "dag:\n  tasks:\n    a:\n      operator: airflow.operators.empty.EmptyOperator\n");
        String ok = code.replace("bad-schedule.yaml", "ok.yaml");
        String first = mwaa("CreateWorkflow", ok).statusCode(200).extract().path("WorkflowArn");
        mwaa("CreateWorkflow", ok).statusCode(200).body("WorkflowArn", equalTo(first));
    }
}
