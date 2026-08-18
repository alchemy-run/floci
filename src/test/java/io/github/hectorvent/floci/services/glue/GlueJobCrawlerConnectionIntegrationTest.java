package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class GlueJobCrawlerConnectionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void jobLifecycleAndTags() {
        String name = "job-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = "arn:aws:glue:us-east-1:000000000000:job/" + name;

        glue("CreateJob", """
                {
                  "Name": "%s",
                  "Role": "arn:aws:iam::000000000000:role/GlueJob",
                  "Command": {"Name":"pythonshell","ScriptLocation":"s3://bucket/etl.py","PythonVersion":"3.9"},
                  "GlueVersion": "3.0",
                  "Tags": {"Environment":"test"}
                }
                """.formatted(name))
                .statusCode(200)
                .body("Name", equalTo(name));

        glue("GetJob", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Job.Name", equalTo(name))
                .body("Job.GlueVersion", equalTo("3.0"))
                .body("Job.Command.Name", equalTo("pythonshell"));

        glue("GetJobs", "{}")
                .statusCode(200)
                .body("Jobs.Name", hasItem(name));

        glue("GetTags", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"));

        glue("UpdateJob", """
                {
                  "JobName": "%s",
                  "JobUpdate": {
                    "Role": "arn:aws:iam::000000000000:role/GlueJob",
                    "Command": {"Name":"pythonshell","ScriptLocation":"s3://bucket/etl2.py","PythonVersion":"3.9"},
                    "GlueVersion": "4.0"
                  }
                }
                """.formatted(name))
                .statusCode(200);

        String runId = glue("StartJobRun", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("JobRunId", notNullValue())
                .extract().path("JobRunId");

        glue("GetJobRun", "{\"JobName\":\"%s\",\"RunId\":\"%s\"}".formatted(name, runId))
                .statusCode(200)
                .body("JobRun.JobRunState", equalTo("SUCCEEDED"));

        glue("GetJobRuns", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("JobRuns[0].Id", equalTo(runId));

        glue("GetJobBookmark", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("JobBookmarkEntry.JobName", equalTo(name));

        glue("ResetJobBookmark", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200);

        glue("DeleteJob", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(200);

        glue("GetJob", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void crawlerStartStopAndDelete() {
        String name = "crawler-" + UUID.randomUUID().toString().substring(0, 8);

        glue("CreateCrawler", """
                {
                  "Name": "%s",
                  "Role": "arn:aws:iam::000000000000:role/GlueCrawler",
                  "DatabaseName": "analytics",
                  "Targets": {"S3Targets":[{"Path":"s3://bucket/data/"}]}
                }
                """.formatted(name))
                .statusCode(200);

        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.State", equalTo("READY"));

        glue("StartCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200);
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.State", equalTo("RUNNING"));

        glue("StopCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200);
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.State", equalTo("READY"));

        glue("DeleteCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200);
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void connectionHidePasswordAndTags() {
        String name = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = "arn:aws:glue:us-east-1:000000000000:connection/" + name;

        glue("CreateConnection", """
                {
                  "ConnectionInput": {
                    "Name": "%s",
                    "ConnectionType": "JDBC",
                    "ConnectionProperties": {"USERNAME":"admin","PASSWORD":"secret"}
                  },
                  "Tags": {"Owner":"qa"}
                }
                """.formatted(name))
                .statusCode(200);

        glue("GetConnection", "{\"Name\":\"%s\",\"HidePassword\":true}".formatted(name))
                .statusCode(200)
                .body("Connection.Name", equalTo(name))
                .body("Connection.ConnectionProperties.USERNAME", equalTo("admin"))
                .body("Connection.ConnectionProperties.PASSWORD", equalTo(null));

        glue("GetTags", "{\"ResourceArn\":\"%s\"}".formatted(arn))
                .statusCode(200)
                .body("Tags.Owner", equalTo("qa"));

        glue("DeleteConnection", "{\"ConnectionName\":\"%s\"}".formatted(name))
                .statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse glue(String action, String body) {
        return given()
                .header("X-Amz-Target", "AWSGlue." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/")
                .then();
    }
}
