package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "FLOCI_TEST_EMR_SERVERLESS_DOCKER", matches = "1")
class EmrServerlessSparkDockerIntegrationTest {

    @Inject
    EmulatorConfig config;
    @Inject
    IamService iam;
    @Inject
    DockerClient docker;

    private String applicationId;
    private String roleName;
    private String roleArn;
    private String jobId;

    @BeforeEach
    void createResources(TestInfo info) {
        RestAssuredJsonUtils.configureAwsContentTypes();
        roleName = "emrserverless-runtime-" + info.getTestMethod().orElseThrow().getName();
        roleArn = iam.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"Service":"emr-serverless.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 3600, Map.of()).getArn();
        applicationId = request("emr-serverless").body(Map.of("name", roleName, "clientToken", roleName,
                        "releaseLabel", "emr-7.5.0", "type", "SPARK"))
                .post("/applications").then().statusCode(200).extract().path("applicationId");
    }

    @AfterEach
    void deleteResources() throws InterruptedException {
        if (applicationId != null) {
            if (jobId != null) {
                String state = state();
                if (!List.of("SUCCESS", "FAILED", "CANCELLED").contains(state)) {
                    request("emr-serverless").delete(jobPath()).then().statusCode(200);
                    awaitTerminal(80);
                }
                assertWorkerRemoved();
                Response response = request("logs").header("X-Amz-Target", "Logs_20140328.DeleteLogStream")
                        .body(Map.of("logGroupName", EmrServerlessSparkRunner.LOG_GROUP,
                                "logStreamName", applicationId + "/" + jobId + "/SPARK_DRIVER")).post("/");
                if (response.statusCode() != 200) {
                    assertEquals("ResourceNotFoundException", response.path("__type"));
                }
            }
            request("emr-serverless").post("/applications/" + applicationId + "/stop").then().statusCode(200);
            request("emr-serverless").delete("/applications/" + applicationId).then().statusCode(200);
        }
        if (roleArn != null) {
            iam.deleteRole(roleName);
        }
    }

    @Test
    void sparkPiCompletes() throws InterruptedException {
        submit("local:///usr/lib/spark/examples/src/main/python/pi.py", "2");
        assertEquals("SUCCESS", awaitTerminal(360));
        awaitMessage("Pi is roughly");
        request("emr-serverless").get(jobPath()).then().statusCode(200)
                .body("jobRun.executionRole", equalTo(roleArn))
                .body("jobRun.stateDetails", equalTo("Spark container exited with code 0"));
        assertWorkerRemoved();
    }

    @Test
    void missingScriptFails() throws InterruptedException {
        submit("local:///usr/lib/spark/examples/src/main/python/does-not-exist.py", "2");
        assertEquals("FAILED", awaitTerminal(360));
        awaitMessage("does-not-exist.py");
        assertWorkerRemoved();
    }

    @Test
    void runningJobIsCancelled() throws InterruptedException {
        submit("local:///usr/lib/spark/examples/src/main/python/pi.py", "100");
        boolean runningSpark = false;
        for (int i = 0; i < 360; i++) {
            String state = state();
            if ("RUNNING".equals(state) && messages().stream().anyMatch(message -> message.contains("SparkContext"))) {
                runningSpark = true;
                break;
            }
            assertFalse(List.of("SUCCESS", "FAILED", "CANCELLED").contains(state),
                    "Job terminated before cancellation could exercise the running worker: " + state);
            Thread.sleep(250);
        }
        assertTrue(runningSpark, "The real Spark process did not start within 90 seconds");
        List<Container> workers = docker.listContainersCmd().withLabelFilter(labels()).exec();
        assertEquals(1, workers.size());
        assertEquals(1536L * 1024 * 1024,
                docker.inspectContainerCmd(workers.getFirst().getId()).exec().getHostConfig().getMemory().longValue());
        request("emr-serverless").delete(jobPath()).then().statusCode(200).body("jobRunId", equalTo(jobId));
        assertEquals("CANCELLED", awaitTerminal(80));
        assertWorkerRemoved();
    }

    private void submit(String entryPoint, String argument) {
        jobId = request("emr-serverless").body(Map.of("clientToken", "runtime-job", "executionRoleArn", roleArn,
                        "executionTimeoutMinutes", 2, "jobDriver", Map.of("sparkSubmit",
                                Map.of("entryPoint", entryPoint, "entryPointArguments", List.of(argument)))))
                .post("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200).extract().path("jobRunId");
    }

    private String state() {
        return request("emr-serverless").get(jobPath()).then().statusCode(200).extract().path("jobRun.state");
    }

    private String awaitTerminal(int iterations) throws InterruptedException {
        for (int i = 0; i < iterations; i++) {
            String state = state();
            if (List.of("SUCCESS", "FAILED", "CANCELLED").contains(state)) {
                return state;
            }
            Thread.sleep(250);
        }
        return fail("Job did not terminate within " + iterations / 4 + " seconds: " + state());
    }

    private void awaitMessage(String text) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (messages().stream().anyMatch(message -> message.contains(text))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Real Spark output did not contain: " + text);
    }

    private List<String> messages() {
        Response response = request("logs").header("X-Amz-Target", "Logs_20140328.GetLogEvents")
                .body(Map.of("logGroupName", EmrServerlessSparkRunner.LOG_GROUP,
                        "logStreamName", applicationId + "/" + jobId + "/SPARK_DRIVER", "startFromHead", true))
                .post("/");
        if (response.statusCode() == 400 && "ResourceNotFoundException".equals(response.path("__type"))) {
            return List.of();
        }
        return response.then().statusCode(200).extract().path("events.message");
    }

    private void assertWorkerRemoved() {
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(labels()).exec().isEmpty(),
                "A terminal job must not leave a running or stopped workload container");
    }

    private Map<String, String> labels() {
        return Map.of("io.floci.service", "emrserverless", "io.floci.resource-id", jobId,
                "io.floci.account", config.defaultAccountId(), "io.floci.region", config.defaultRegion());
    }

    private String jobPath() {
        return "/applications/" + applicationId + "/jobruns/" + jobId;
    }

    private RequestSpecification request(String signingService) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + config.defaultAccountId()
                        + "/20260922/" + config.defaultRegion() + "/" + signingService
                        + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("logs".equals(signingService) ? "application/x-amz-json-1.1" : "application/json");
    }
}
