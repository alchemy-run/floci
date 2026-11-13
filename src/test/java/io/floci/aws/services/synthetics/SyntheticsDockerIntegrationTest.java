package io.floci.aws.services.synthetics;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.ACCOUNT;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.SCRIPT;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.bucket;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.canaryBody;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.get;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.role;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.synthetics;
import static io.floci.aws.services.synthetics.SyntheticsIntegrationTest.zip;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canary runs on a real backing Lambda function: a started canary runs its script, records the run, writes the
 * report and log to its artifact bucket, publishes CloudWatchSynthetics metrics and sends aws.synthetics events;
 * a failing script is retried and reported with its error; a dry run tries a fix that an update then applies.
 */
@QuarkusTest
@Tag("docker")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class SyntheticsDockerIntegrationTest {

    private static final String BROKEN = """
            const synthetics = require("@aws/synthetics-puppeteer");
            exports.handler = async function () {
              await synthetics.executeStep("checkout", async function () { throw new Error("upstream down"); });
            };
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification json(String target, String prefix) {
        return given().header("X-Amz-Target", prefix + "." + target)
                .contentType("application/x-amz-json-1.1");
    }

    /** An SQS queue receiving every aws.synthetics event on the default bus. */
    private static String eventQueue() {
        String url = json("CreateQueue", "AmazonSQS").contentType("application/x-amz-json-1.0")
                .body("{\"QueueName\":\"synthetics-events\"}").post("/").then().statusCode(200).extract()
                .path("QueueUrl");
        json("PutRule", "AWSEvents").body("{\"Name\":\"synthetics\",\"EventPattern\":"
                + "\"{\\\"source\\\":[\\\"aws.synthetics\\\"]}\"}").post("/").then().statusCode(200);
        json("PutTargets", "AWSEvents").body("{\"Rule\":\"synthetics\",\"Targets\":[{\"Id\":\"q\",\"Arn\":"
                + "\"arn:aws:sqs:us-east-1:" + ACCOUNT + ":synthetics-events\"}]}").post("/").then().statusCode(200);
        return url;
    }

    private static List<String> drain(String queueUrl) {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<String> batch = json("ReceiveMessage", "AmazonSQS").contentType("application/x-amz-json-1.0")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10}").post("/").then()
                    .statusCode(200).extract().path("Messages.Body");
            if (batch == null || batch.isEmpty()) {
                break;
            }
            bodies.addAll(batch);
        }
        return bodies;
    }

    @Test
    void canariesRunOnTheirBackingFunction() {
        String role = role("synthetics-runs");
        bucket("synthetics-runs");
        String queue = eventQueue();

        // A one-time canary runs once when started, passes, and stops.
        synthetics().body(canaryBody("pulse", role, "synthetics-runs", "rate(0 minute)",
                zip("nodejs/node_modules/index.js", SCRIPT))).post("/canary").then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                get("pulse").body("Canary.Status.State", equalTo("READY")));
        synthetics().post("/canary/pulse/start").then().statusCode(200);
        await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
                synthetics().body("{}").post("/canary/pulse/runs").then().statusCode(200)
                        .body("CanaryRuns[0].Status.State", equalTo("PASSED")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                get("pulse").body("Canary.Status.State", equalTo("STOPPED")));
        String location = synthetics().body("{}").post("/canary/pulse/runs").then()
                .body("CanaryRuns.size()", equalTo(1))
                .body("CanaryRuns[0].Status.TestResult", equalTo("PASSED"))
                .extract().path("CanaryRuns[0].ArtifactS3Location");
        assertTrue(location.startsWith("synthetics-runs/pulse/canary/us-east-1/pulse/"), location);
        String prefix = location.substring("synthetics-runs/".length());
        given().get("/synthetics-runs/" + prefix + "/SyntheticsReport-PASSED.json").then().statusCode(200)
                .body("status", equalTo("PASSED")).body("stepsReport[0].stepName", equalTo("heartbeat"));
        given().get("/synthetics-runs/" + prefix + "/log.txt").then().statusCode(200)
                .body(containsString("heartbeat ok"));
        synthetics().body("{\"Names\":[\"pulse\"]}").post("/canaries/last-run").then().statusCode(200)
                .body("CanariesLastRun[0].LastRun.Status.State", equalTo("PASSED"));
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/monitoring/aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListMetrics").formParam("Version", "2010-08-01")
                .formParam("Namespace", "CloudWatchSynthetics").post("/").then().statusCode(200)
                .body(containsString("SuccessPercent")).body(containsString("pulse"));
        List<String> events = drain(queue);
        assertTrue(events.stream().anyMatch(e -> e.contains("Synthetics Canary TestRun Successful")
                && e.contains("\"canary-name\":\"pulse\"")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("Synthetics Canary Status Change")
                && e.contains("\"current-state\":\"RUNNING\"")), events.toString());

        // A failing script fails the run with its error, and is retried once.
        synthetics().body(canaryBody("broken", role, "synthetics-runs", "rate(0 minute)",
                        zip("nodejs/node_modules/index.js", BROKEN))
                .replace("\"Expression\":\"rate(0 minute)\"",
                        "\"Expression\":\"rate(0 minute)\",\"RetryConfig\":{\"MaxRetries\":1}"))
                .post("/canary").then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                get("broken").body("Canary.Status.State", equalTo("READY")));
        synthetics().post("/canary/broken/start").then().statusCode(200);
        await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
                get("broken").body("Canary.Status.State", equalTo("STOPPED")));
        synthetics().body("{}").post("/canary/broken/runs").then().statusCode(200)
                .body("CanaryRuns.size()", equalTo(2))
                .body("CanaryRuns.Status.State", equalTo(List.of("FAILED", "FAILED")))
                .body("CanaryRuns[0].RetryAttempt", equalTo(1))
                .body("CanaryRuns[0].Status.StateReason", containsString("upstream down"))
                .body("CanaryRuns[0].Status.StateReasonCode", equalTo("CANARY_FAILURE"));
        assertTrue(drain(queue).stream().anyMatch(e -> e.contains("Synthetics Canary TestRun Failure")
                && e.contains("upstream down")));

        // A dry run tries the fix without changing the canary; the update applies it.
        String dryRunId = synthetics().body("{\"Code\":{\"Handler\":\"index.handler\",\"ZipFile\":\""
                        + zip("nodejs/node_modules/index.js", SCRIPT) + "\"}}").post("/canary/broken/dry-run/start")
                .then().statusCode(200).extract().path("DryRunConfig.DryRunId");
        await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
                synthetics().queryParam("dryRunId", dryRunId).get("/canary/broken").then().statusCode(200)
                        .body("Canary.DryRunConfig.LastDryRunExecutionStatus", equalTo("PASSED")));
        synthetics().body("{}").post("/canary/broken/runs").then().body("CanaryRuns.size()", equalTo(2));
        synthetics().body("{\"RunType\":\"DRY_RUN\"}").post("/canary/broken/runs").then()
                .body("CanaryRuns.Status.State", equalTo(List.of("PASSED")));
        synthetics().body("{\"DryRunId\":\"" + dryRunId + "\"}").patch("/canary/broken").then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                get("broken").body("Canary.Status.State", equalTo("STOPPED")));
        synthetics().post("/canary/broken/start").then().statusCode(200);
        await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
                synthetics().body("{}").post("/canary/broken/runs").then()
                        .body("CanaryRuns[0].Status.State", equalTo("PASSED")));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            get("pulse").body("Canary.Status.State", equalTo("STOPPED"));
            get("broken").body("Canary.Status.State", equalTo("STOPPED"));
        });
        for (String name : List.of("pulse", "broken")) {
            synthetics().delete("/canary/" + name).then().statusCode(200);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            get("pulse").statusCode(404);
            get("broken").statusCode(404);
        });
        List<String> functions = given().get("/2015-03-31/functions").then().statusCode(200).extract()
                .path("Functions.FunctionName");
        assertEquals(List.of(), functions.stream().filter(f -> f.startsWith("cwsyn-")).toList());
    }
}
