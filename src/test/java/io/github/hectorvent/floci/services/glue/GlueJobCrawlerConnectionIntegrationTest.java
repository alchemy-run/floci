package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

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

        glue("GetJobBookmark", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        glue("ResetJobBookmark", "{\"JobName\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));

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
                .body("JobRunId", startsWith("jr_"))
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

        glue("StopCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("CrawlerNotRunningException"));

        glue("UpdateCrawler", """
                {
                  "Name": "%s",
                  "Role": "arn:aws:iam::000000000000:role/GlueCrawler",
                  "DatabaseName": "analytics",
                  "Targets": {"S3Targets":[{"Path":"s3://bucket/data/"}]},
                  "Schedule": "cron(0 12 * * ? *)",
                  "SchemaChangePolicy": {
                    "UpdateBehavior": "UPDATE_IN_DATABASE",
                    "DeleteBehavior": "DEPRECATE_IN_DATABASE"
                  }
                }
                """.formatted(name))
                .statusCode(200);
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.Schedule.ScheduleExpression", equalTo("cron(0 12 * * ? *)"))
                .body("Crawler.Schedule.State", equalTo("SCHEDULED"))
                .body("Crawler.SchemaChangePolicy.UpdateBehavior", equalTo("UPDATE_IN_DATABASE"));

        glue("StartCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200);
        awaitCrawlerReady(name);
        // The crawler's database was never created, so the crawl itself fails.
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.State", equalTo("READY"))
                .body("Crawler.LastCrawl.Status", equalTo("FAILED"))
                .body("Crawler.LastCrawl.ErrorMessage", containsString("analytics"))
                .body("Crawler.Schedule.ScheduleExpression", equalTo("cron(0 12 * * ? *)"));

        glue("StopCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("CrawlerNotRunningException"));

        glue("DeleteCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200);
        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void partitionBatchLifecycle() {
        String database = "db-" + UUID.randomUUID().toString().substring(0, 8);
        String table = "events";

        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"%s\"}}".formatted(database))
                .statusCode(200);
        glue("CreateTable", """
                {
                  "DatabaseName": "%s",
                  "TableInput": {
                    "Name": "%s",
                    "PartitionKeys": [{"Name":"dt","Type":"string"}],
                    "StorageDescriptor": {
                      "Columns": [{"Name":"id","Type":"string"}],
                      "Location": "s3://bucket/events/"
                    }
                  }
                }
                """.formatted(database, table))
                .statusCode(200);

        glue("CreatePartition", """
                {
                  "DatabaseName": "%s",
                  "TableName": "%s",
                  "PartitionInput": {"Values":["2026-01-01"]}
                }
                """.formatted(database, table))
                .statusCode(200);
        glue("BatchCreatePartition", """
                {
                  "DatabaseName": "%s",
                  "TableName": "%s",
                  "PartitionInputList": [{"Values":["2026-01-02"]},{"Values":["2026-01-03"]}]
                }
                """.formatted(database, table))
                .statusCode(200)
                .body("Errors.size()", equalTo(0));
        glue("GetPartitions", "{\"DatabaseName\":\"%s\",\"TableName\":\"%s\"}".formatted(database, table))
                .statusCode(200)
                .body("Partitions.size()", equalTo(3));
        glue("BatchDeletePartition", """
                {
                  "DatabaseName": "%s",
                  "TableName": "%s",
                  "PartitionsToDelete": [{"Values":["2026-01-02"]},{"Values":["2026-01-03"]}]
                }
                """.formatted(database, table))
                .statusCode(200)
                .body("Errors.size()", equalTo(0));
        glue("GetPartitions", "{\"DatabaseName\":\"%s\",\"TableName\":\"%s\"}".formatted(database, table))
                .statusCode(200)
                .body("Partitions.size()", equalTo(1));
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

    @Test
    void crawlerInfersCsvTableFromS3AndRecordsSucceededCrawl() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "crawl-" + suffix;
        String database = "crawl_db_" + suffix;
        String name = "crawler-" + suffix;

        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("text/csv").body("id,amount\n1,10.5\n2,20.0\n")
                .when().put("/" + bucket + "/data/events/part-0.csv").then().statusCode(200);
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"%s\"}}".formatted(database))
                .statusCode(200);
        glue("CreateCrawler", """
                {
                  "Name": "%s",
                  "Role": "arn:aws:iam::000000000000:role/GlueCrawler",
                  "DatabaseName": "%s",
                  "Targets": {"S3Targets":[{"Path":"s3://%s/data/"}]}
                }
                """.formatted(name, database, bucket))
                .statusCode(200);

        glue("StartCrawler", "{\"Name\":\"%s\"}".formatted(name)).statusCode(200);
        awaitCrawlerReady(name);

        glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                .statusCode(200)
                .body("Crawler.State", equalTo("READY"))
                .body("Crawler.LastCrawl.Status", equalTo("SUCCEEDED"))
                .body("Crawler.LastCrawl.StartTime", notNullValue());
        glue("GetTable", "{\"DatabaseName\":\"%s\",\"Name\":\"events\"}".formatted(database))
                .statusCode(200)
                .body("Table.Parameters.classification", equalTo("csv"))
                .body("Table.Parameters.UPDATED_BY_CRAWLER", equalTo(name))
                .body("Table.StorageDescriptor.Location", equalTo("s3://" + bucket + "/data/events/"))
                .body("Table.StorageDescriptor.Columns.Name", contains("id", "amount"))
                .body("Table.StorageDescriptor.Columns.Type", contains("bigint", "double"))
                .body("Table.StorageDescriptor.SerdeInfo.Parameters.'field.delim'", equalTo(","));

        glue("DeleteCrawler", "{\"Name\":\"%s\"}".formatted(name)).statusCode(200);
    }

    private static void awaitCrawlerReady(String name) {
        for (int i = 0; i < 100; i++) {
            String state = glue("GetCrawler", "{\"Name\":\"%s\"}".formatted(name))
                    .statusCode(200).extract().path("Crawler.State");
            if ("READY".equals(state)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Crawler " + name + " did not return to READY");
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
