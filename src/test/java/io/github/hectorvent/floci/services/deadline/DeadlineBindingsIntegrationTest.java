package io.github.hectorvent.floci.services.deadline;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding-surface operations Alchemy Deadline Bindings exercise: farm/queue
 * lifecycle, CreateJob from an OpenJD template, steps/tasks, search, and
 * sessions-statistics aggregation.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeadlineBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/" + EAST + "/deadline/aws4_request";
    private static final String TEMPLATE = """
            {
              "specificationVersion": "jobtemplate-2023-09",
              "name": "AlchemyDeadlineBindingsJob",
              "steps": [
                {
                  "name": "Echo",
                  "script": {
                    "actions": { "onRun": { "command": "/bin/echo", "args": ["hello"] } }
                  }
                }
              ]
            }
            """;

    private static String farmId;
    private static String queueId;
    private static String jobId;
    private static String stepId;
    private static String taskId;
    private static String farmArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(10)
    void listFarmsReturnsArray() {
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms")
        .then()
                .statusCode(200)
                .body("farms", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @Order(20)
    void createFarmGetFarmAndTags() {
        farmId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "displayName":"bindings-farm-%s",
                          "description":"alchemy deadline bindings fixture farm",
                          "tags":{"suite":"bindings"}
                        }
                        """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
                .post("/2023-10-12/farms")
        .then()
                .statusCode(200)
                .body("farmId", startsWith("farm-"))
                .extract().path("farmId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId)
        .then()
                .statusCode(200)
                .body("farmId", equalTo(farmId))
                .body("costScaleFactor", equalTo(1.0f))
                .body("description", equalTo("alchemy deadline bindings fixture farm"));

        farmArn = "arn:aws:deadline:" + EAST + ":000000000000:farm/" + farmId;
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/tags/" + encode(farmArn))
        .then()
                .statusCode(200)
                .body("tags.suite", equalTo("bindings"));
    }

    @Test
    @Order(30)
    void createQueueAndGetQueue() {
        queueId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "displayName":"bindings-queue",
                          "description":"alchemy deadline bindings fixture queue",
                          "jobRunAsUser":{"runAs":"WORKER_AGENT_USER"}
                        }
                        """)
        .when()
                .post("/2023-10-12/farms/" + farmId + "/queues")
        .then()
                .statusCode(200)
                .body("queueId", startsWith("queue-"))
                .extract().path("queueId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId)
        .then()
                .statusCode(200)
                .body("queueId", equalTo(queueId))
                .body("status", equalTo("IDLE"))
                .body("defaultBudgetAction", equalTo("NONE"))
                .body("jobRunAsUser.runAs", equalTo("WORKER_AGENT_USER"));
    }

    @Test
    @Order(40)
    void createJobGetJobListJobsSearchJobsAndUpdatePriority() {
        String body = """
                {
                  "clientToken":"alchemy-deadline-bindings-job-%s",
                  "template":%s,
                  "templateType":"JSON",
                  "priority":50
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8), quoteJson(TEMPLATE));

        jobId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body)
        .when()
                .post("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs")
        .then()
                .statusCode(200)
                .body("jobId", startsWith("job-"))
                .extract().path("jobId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId)
        .then()
                .statusCode(200)
                .body("jobId", equalTo(jobId))
                .body("priority", equalTo(50))
                .body("lifecycleStatus", equalTo("CREATE_COMPLETE"))
                .body("name", equalTo("AlchemyDeadlineBindingsJob"));

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs")
        .then()
                .statusCode(200)
                .body("jobs.jobId", hasItem(jobId));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"itemOffset\":0,\"queueIds\":[\"" + queueId + "\"]}")
        .when()
                .post("/2023-10-12/farms/" + farmId + "/search/jobs")
        .then()
                .statusCode(200)
                .body("jobs.jobId", hasItem(jobId))
                .body("totalResults", equalTo(1));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"priority\":75}")
        .when()
                .patch("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId)
        .then()
                .statusCode(200)
                .body("priority", equalTo(75));
    }

    @Test
    @Order(50)
    void listStepsGetStepListTasksGetTask() {
        stepId = given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId + "/steps")
        .then()
                .statusCode(200)
                .body("steps", hasSize(1))
                .body("steps[0].name", equalTo("Echo"))
                .extract().path("steps[0].stepId");
        assertTrue(stepId.startsWith("step-"));

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId + "/steps/" + stepId)
        .then()
                .statusCode(200)
                .body("stepId", equalTo(stepId))
                .body("name", equalTo("Echo"));

        taskId = given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId + "/tasks")
        .then()
                .statusCode(200)
                .body("tasks", hasSize(1))
                .extract().path("tasks[0].taskId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId + "/tasks/" + taskId)
        .then()
                .statusCode(200)
                .body("taskId", equalTo(taskId))
                .body("runStatus", equalTo("READY"));
    }

    @Test
    @Order(60)
    void searchStepsSearchTasksAndParameterDefinitions() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"itemOffset\":0,\"queueIds\":[\"" + queueId + "\"],\"jobId\":\"" + jobId + "\"}")
        .when()
                .post("/2023-10-12/farms/" + farmId + "/search/steps")
        .then()
                .statusCode(200)
                .body("steps.stepId", hasItem(stepId));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"itemOffset\":0,\"queueIds\":[\"" + queueId + "\"],\"jobId\":\"" + jobId + "\"}")
        .when()
                .post("/2023-10-12/farms/" + farmId + "/search/tasks")
        .then()
                .statusCode(200)
                .body("tasks.taskId", hasItem(taskId));

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/parameter-definitions")
        .then()
                .statusCode(200)
                .body("jobParameterDefinitions", hasSize(0));
    }

    @Test
    @Order(70)
    void cancelTaskThenRequeueStep() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"targetRunStatus\":\"CANCELED\"}")
        .when()
                .patch("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId + "/tasks/" + taskId)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId + "/tasks/" + taskId)
        .then()
                .statusCode(200)
                .body("runStatus", equalTo("CANCELED"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"targetTaskRunStatus\":\"READY\"}")
        .when()
                .patch("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId)
        .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/steps/" + stepId + "/tasks/" + taskId)
        .then()
                .statusCode(200)
                .body("runStatus", equalTo("READY"));
    }

    @Test
    @Order(80)
    void listSessionsAndSessionActionsAreEmpty() {
        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId + "/sessions")
        .then()
                .statusCode(200)
                .body("sessions", hasSize(0));

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/queues/" + queueId
                        + "/jobs/" + jobId + "/session-actions?taskId=" + taskId)
        .then()
                .statusCode(200)
                .body("sessionActions", hasSize(0));
    }

    @Test
    @Order(90)
    void sessionsStatisticsAggregationCompletes() {
        String aggregationId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "resourceIds":{"queueIds":["%s"]},
                          "startTime":"2026-08-24T00:00:00Z",
                          "endTime":"2026-08-25T00:00:00Z",
                          "groupBy":["QUEUE_ID"],
                          "statistics":["SUM"]
                        }
                        """.formatted(queueId))
        .when()
                .post("/2023-10-12/farms/" + farmId + "/sessions-statistics-aggregation")
        .then()
                .statusCode(200)
                .body("aggregationId", startsWith("aggregation-"))
                .extract().path("aggregationId");

        given()
                .header("Authorization", AUTH)
        .when()
                .get("/2023-10-12/farms/" + farmId + "/sessions-statistics-aggregation?aggregationId=" + aggregationId)
        .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    @Order(100)
    void emptyChildListsAndDelete() {
        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId + "/budgets")
                .then().statusCode(200).body("budgets", hasSize(0));
        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId + "/fleets")
                .then().statusCode(200).body("fleets", hasSize(0));
        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId + "/limits")
                .then().statusCode(200).body("limits", hasSize(0));
        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId + "/storage-profiles")
                .then().statusCode(200).body("storageProfiles", hasSize(0));
        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId + "/queue-fleet-associations")
                .then().statusCode(200).body("queueFleetAssociations", hasSize(0));

        given().header("Authorization", AUTH)
                .when().delete("/2023-10-12/farms/" + farmId)
                .then().statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given().header("Authorization", AUTH)
                .when().delete("/2023-10-12/farms/" + farmId + "/queues/" + queueId)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().delete("/2023-10-12/farms/" + farmId)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/2023-10-12/farms/" + farmId)
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        assertEquals(farmId.startsWith("farm-"), true);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quoteJson(String json) {
        String escaped = json.strip()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "");
        return "\"" + escaped + "\"";
    }
}
