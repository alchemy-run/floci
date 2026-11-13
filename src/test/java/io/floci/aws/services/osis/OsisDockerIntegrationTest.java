package io.floci.aws.services.osis;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipelines on Data Prepper: events posted to a pipeline's ingest endpoint run through its sub-pipelines and
 * processors into an S3 bucket as the pipeline's role, its log goes to CloudWatch Logs, and a configuration Data
 * Prepper can't run leaves the pipeline {@code CREATE_FAILED} with Data Prepper's error.
 */
@QuarkusTest
@TestProfile(OsisEngineProfile.class)
@Tag("docker")
class OsisDockerIntegrationTest {

    static final String API = OsisIntegrationTest.API;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static List<String> keys(String bucket) {
        return given().header("Authorization", OsisIntegrationTest.auth("s3")).queryParam("list-type", "2")
                .get("/" + bucket).then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
    }

    @Test
    void eventsFlowFromTheIngestEndpointToS3() {
        given().header("Authorization", OsisIntegrationTest.auth("s3")).put("/osis-archive").then().statusCode(200);
        String role = OsisIntegrationTest.role("osis-engine", "osis-pipelines.amazonaws.com");
        String config = "version: \"2\"\nentry-pipeline:\n  source:\n    http:\n      path: \"/${pipelineName}/logs\"\n"
                + "  processor:\n    - add_entries:\n        entries:\n          - key: \"via\"\n"
                + "            value: \"floci\"\n  sink:\n    - pipeline:\n        name: \"archive-pipeline\"\n"
                + "archive-pipeline:\n  source:\n    pipeline:\n      name: \"entry-pipeline\"\n  sink:\n"
                + "    - s3:\n        aws:\n          sts_role_arn: \"" + role + "\"\n          region: \"us-east-1\"\n"
                + "        bucket: \"osis-archive\"\n        threshold:\n          event_collect_timeout: \"5s\"\n"
                + "        codec:\n          ndjson:\n";
        String url = OsisIntegrationTest.osis().body("{\"PipelineName\":\"engine\",\"MinUnits\":1,\"MaxUnits\":1,"
                        + "\"PipelineConfigurationBody\":" + OsisIntegrationTest.json(config) + ",\"LogPublishingOptions\":"
                        + "{\"IsLoggingEnabled\":true,\"CloudWatchLogDestination\":{\"LogGroup\":"
                        + "\"/aws/vendedlogs/osis-engine\"}}}").post(API + "/createPipeline").then().statusCode(200)
                .extract().path("Pipeline.IngestEndpointUrls[0]");
        OsisIntegrationTest.awaitStatus("engine", "ACTIVE");
        String host = URI.create(url).getAuthority();

        given().header("Host", host).contentType("application/json").body("[{\"msg\":\"unsigned\"}]")
                .post("/entry-pipeline/logs").then().statusCode(403);
        given().header("Host", host).header("Authorization", OsisIntegrationTest.auth("osis"))
                .contentType("application/json").body("[{\"msg\":\"x\"}]").post("/elsewhere").then().statusCode(404);
        given().header("Host", host).header("Authorization", OsisIntegrationTest.auth("osis"))
                .contentType("application/json").body("[{\"msg\":\"hello\"},{\"msg\":\"world\"}]")
                .post("/entry-pipeline/logs").then().statusCode(200);

        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2))
                .until(() -> !keys("osis-archive").isEmpty());
        String key = keys("osis-archive").get(0);
        assertTrue(key.endsWith(".ndjson"), key);
        String object = given().header("Authorization", OsisIntegrationTest.auth("s3")).get("/osis-archive/" + key)
                .then().statusCode(200).extract().asString();
        assertTrue(object.contains("\"msg\":\"hello\"") && object.contains("\"msg\":\"world\""), object);
        assertTrue(object.contains("\"via\":\"floci\""), object);

        String events = given().header("Authorization", OsisIntegrationTest.auth("logs"))
                .header("X-Amz-Target", "Logs_20140328.GetLogEvents").contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\":\"/aws/vendedlogs/osis-engine\",\"logStreamName\":\"pipeline/engine\","
                        + "\"startFromHead\":true}").post("/").then().statusCode(200).extract().asString();
        assertTrue(events.contains("Started http source"), events);

        OsisIntegrationTest.osis().put(API + "/stopPipeline/engine").then().statusCode(200);
        OsisIntegrationTest.awaitStatus("engine", "STOPPED");
        given().header("Host", host).header("Authorization", OsisIntegrationTest.auth("osis"))
                .contentType("application/json").body("[{\"msg\":\"stopped\"}]").post("/entry-pipeline/logs").then()
                .statusCode(503);
        OsisIntegrationTest.osis().delete(API + "/deletePipeline/engine").then().statusCode(200);
    }

    @Test
    void aConfigurationDataPrepperRejectsFailsTheCreate() {
        String role = OsisIntegrationTest.role("osis-rejected", "osis-pipelines.amazonaws.com");
        String config = OsisIntegrationTest.config(role, "osis-rejected").replace("codec:\n          ndjson:\n",
                "codec:\n          not_a_codec:\n");
        OsisIntegrationTest.osis().body("{\"PipelineName\":\"rejected\",\"MinUnits\":1,\"MaxUnits\":1,"
                + "\"PipelineConfigurationBody\":" + OsisIntegrationTest.json(config) + "}")
                .post(API + "/createPipeline").then().statusCode(200);
        OsisIntegrationTest.awaitStatus("rejected", "CREATE_FAILED");
        OsisIntegrationTest.osis().get(API + "/getPipeline/rejected").then().statusCode(200)
                .body("Pipeline.StatusReason.Description", containsString("not_a_codec"));
        OsisIntegrationTest.osis().get(API + "/getPipelineChangeProgress/rejected").then().statusCode(200)
                .body("ChangeProgressStatuses[0].Status", equalTo("FAILED"));
        OsisIntegrationTest.osis().delete(API + "/deletePipeline/rejected").then().statusCode(200);
    }
}
