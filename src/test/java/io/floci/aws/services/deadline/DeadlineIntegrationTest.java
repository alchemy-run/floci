package io.floci.aws.services.deadline;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * AWS Deadline Cloud over REST: farm resources and their deletion rules, Open Job Description job submission and
 * task expansion, run status roll-up and updates, search, and the worker scheduling protocol a customer-managed
 * fleet worker speaks, from registration to a job that runs to completion.
 */
@QuarkusTest
class DeadlineIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String BASE = "/2023-10-12";
    private static final String WORKER_TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"credentials.deadline.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification dl() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260101/us-east-1/deadline/aws4_request").contentType("application/json");
    }

    private static ValidatableResponse post(String path, String body) {
        return dl().body(body).post(BASE + path).then();
    }

    private static ValidatableResponse patch(String path, String body) {
        return dl().body(body).patch(BASE + path).then();
    }

    private static ValidatableResponse get(String path) {
        return dl().get(BASE + path).then();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String role(String trust) {
        String name = "dl-" + suffix();
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260101/us-east-1/iam/aws4_request").formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", trust).post("/").then().statusCode(200);
        return "arn:aws:iam::" + ACCOUNT + ":role/" + name;
    }

    private static String farm() {
        return post("/farms", "{\"displayName\":\"farm-" + suffix() + "\",\"tags\":{\"team\":\"render\"}}")
                .statusCode(200).body("farmId", startsWith("farm-")).extract().path("farmId");
    }

    private static String queue(String farmId) {
        return post("/farms/" + farmId + "/queues", "{\"displayName\":\"queue-" + suffix() + "\"}").statusCode(200)
                .extract().path("queueId");
    }

    private static String fleet(String farmId) {
        return post("/farms/" + farmId + "/fleets", "{\"displayName\":\"fleet\",\"roleArn\":\"" + role(WORKER_TRUST)
                + "\",\"maxWorkerCount\":2,\"configuration\":{\"customerManaged\":{\"mode\":\"NO_SCALING\","
                + "\"workerCapabilities\":{\"vCpuCount\":{\"min\":1},\"memoryMiB\":{\"min\":1024},\"osFamily\":"
                + "\"LINUX\",\"cpuArchitectureType\":\"x86_64\"}}}}").statusCode(200).extract().path("fleetId");
    }

    private static String job(String farmId, String queueId, String template, String parameters) {
        return post("/farms/" + farmId + "/queues/" + queueId + "/jobs", "{\"template\":" + quote(template)
                + ",\"templateType\":\"JSON\",\"priority\":50" + (parameters == null ? "" : ",\"parameters\":"
                + parameters) + "}").statusCode(201).body("jobId", startsWith("job-")).extract().path("jobId");
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void farmResourcesAndTheirDeletionRules() {
        String farmId = farm();
        get("/farms/" + farmId).statusCode(200).body("costScaleFactor", equalTo(1.0f));
        String arn = "arn:aws:deadline:us-east-1:" + ACCOUNT + ":farm/" + farmId;
        get("/tags/" + arn).statusCode(200).body("tags.team", equalTo("render"));
        String profile = post("/farms/" + farmId + "/storage-profiles", "{\"displayName\":\"linux\",\"osFamily\":"
                + "\"LINUX\",\"fileSystemLocations\":[{\"name\":\"Assets\",\"path\":\"/mnt/assets\",\"type\":"
                + "\"SHARED\"}]}").statusCode(200).body("storageProfileId", startsWith("sp-")).extract()
                .path("storageProfileId");
        post("/farms/" + farmId + "/storage-profiles", "{\"displayName\":\"bad\",\"osFamily\":\"LINUX\","
                + "\"fileSystemLocations\":[{\"name\":\"A\",\"path\":\"relative\",\"type\":\"SHARED\"}]}")
                .statusCode(400).body("message", containsString("absolute"));
        String queueId = post("/farms/" + farmId + "/queues", "{\"displayName\":\"q\",\"defaultBudgetAction\":"
                + "\"STOP_SCHEDULING_AND_COMPLETE_TASKS\",\"allowedStorageProfileIds\":[\"" + profile + "\"]}")
                .statusCode(200).extract().path("queueId");
        get("/farms/" + farmId + "/queues/" + queueId).statusCode(200).body("status", equalTo("SCHEDULING_BLOCKED"))
                .body("blockedReason", equalTo("NO_BUDGET_CONFIGURED"));
        long now = Instant.now().getEpochSecond();
        String budget = post("/farms/" + farmId + "/budgets", "{\"displayName\":\"b\",\"usageTrackingResource\":"
                + "{\"queueId\":\"" + queueId + "\"},\"approximateDollarLimit\":10,\"actions\":[{\"type\":"
                + "\"STOP_SCHEDULING_AND_COMPLETE_TASKS\",\"thresholdPercentage\":100}],\"schedule\":{\"fixed\":"
                + "{\"startTime\":" + now + ",\"endTime\":" + (now + 86400 * 200) + "}}}").statusCode(400)
                .body("message", containsString("120 days")).extract().path("budgetId");
        budget = post("/farms/" + farmId + "/budgets", "{\"displayName\":\"b\",\"usageTrackingResource\":"
                + "{\"queueId\":\"" + queueId + "\"},\"approximateDollarLimit\":10,\"actions\":[{\"type\":"
                + "\"STOP_SCHEDULING_AND_COMPLETE_TASKS\",\"thresholdPercentage\":100}],\"schedule\":{\"fixed\":"
                + "{\"startTime\":" + now + ",\"endTime\":" + (now + 86400 * 30) + "}}}").statusCode(200)
                .extract().path("budgetId");
        get("/farms/" + farmId + "/queues/" + queueId).statusCode(200).body("status", equalTo("IDLE"));
        get("/farms/" + farmId + "/budgets/" + budget).statusCode(200).body("usages.approximateDollarUsage",
                equalTo(0.0f));

        String fleetId = fleet(farmId);
        get("/farms/" + farmId + "/fleets/" + fleetId).statusCode(200).body("status", equalTo("ACTIVE"))
                .body("capabilities.attributes.name", hasItem("attr.worker.os.family"));
        dl().body("{\"queueId\":\"" + queueId + "\",\"fleetId\":\"" + fleetId + "\"}")
                .put(BASE + "/farms/" + farmId + "/queue-fleet-associations").then().statusCode(200);
        String limit = post("/farms/" + farmId + "/limits", "{\"displayName\":\"maya\",\"amountRequirementName\":"
                + "\"amount.license.maya\",\"maxCount\":1}").statusCode(200).extract().path("limitId");
        dl().body("{\"queueId\":\"" + queueId + "\",\"limitId\":\"" + limit + "\"}")
                .put(BASE + "/farms/" + farmId + "/queue-limit-associations").then().statusCode(200);

        dl().delete(BASE + "/farms/" + farmId).then().statusCode(409);
        dl().delete(BASE + "/farms/" + farmId + "/storage-profiles/" + profile).then().statusCode(409);
        String association = "/farms/" + farmId + "/queue-fleet-associations/" + queueId + "/" + fleetId;
        dl().delete(BASE + association).then().statusCode(409).body("message", containsString("Stop"));
        patch(association, "{\"status\":\"STOP_SCHEDULING_AND_COMPLETE_TASKS\"}").statusCode(200);
        get(association).statusCode(200).body("status", equalTo("STOPPED"));
        dl().delete(BASE + association).then().statusCode(200);
        String limitAssociation = "/farms/" + farmId + "/queue-limit-associations/" + queueId + "/" + limit;
        patch(limitAssociation, "{\"status\":\"STOP_LIMIT_USAGE_AND_CANCEL_TASKS\"}").statusCode(200);
        dl().delete(BASE + limitAssociation).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/budgets/" + budget).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/queues/" + queueId).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/fleets/" + fleetId).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/limits/" + limit).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/limits/" + limit).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId + "/storage-profiles/" + profile).then().statusCode(200);
        dl().delete(BASE + "/farms/" + farmId).then().statusCode(200);
        get("/farms/" + farmId).statusCode(404).body("resourceType", equalTo("FARM"));
    }

    @Test
    void jobsExpandTemplatesIntoStepsAndTasks() {
        String farmId = farm();
        String queueId = queue(farmId);
        String template = "{\"specificationVersion\":\"jobtemplate-2023-09\",\"name\":\"Render {{Param.Shot}}\","
                + "\"parameterDefinitions\":[{\"name\":\"Shot\",\"type\":\"STRING\"},{\"name\":\"Frames\",\"type\":"
                + "\"STRING\",\"default\":\"1-4\"}],\"steps\":[{\"name\":\"Render\",\"parameterSpace\":"
                + "{\"taskParameterDefinitions\":[{\"name\":\"Frame\",\"type\":\"INT\",\"range\":\"{{Param.Frames}}\"},"
                + "{\"name\":\"Layer\",\"type\":\"STRING\",\"range\":[\"fg\",\"bg\"]}],\"combination\":"
                + "\"Frame * Layer\"},\"script\":{\"actions\":{\"onRun\":{\"command\":\"render\"}}}},"
                + "{\"name\":\"Encode\",\"dependencies\":[{\"dependsOn\":\"Render\"}],\"script\":{\"actions\":"
                + "{\"onRun\":{\"command\":\"ffmpeg\"}}}}]}";
        post("/farms/" + farmId + "/queues/" + queueId + "/jobs", "{\"template\":" + quote(template)
                + ",\"templateType\":\"JSON\",\"priority\":50}").statusCode(400)
                .body("message", containsString("Shot is required"));
        String jobId = job(farmId, queueId, template, "{\"Shot\":{\"string\":\"sh010\"}}");
        String jobPath = "/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId;
        get(jobPath).statusCode(200).body("name", equalTo("Render sh010")).body("lifecycleStatus",
                equalTo("CREATE_COMPLETE")).body("taskRunStatus", equalTo("READY"))
                .body("taskRunStatusCounts.READY", equalTo(8)).body("taskRunStatusCounts.PENDING", equalTo(1))
                .body("parameters.Frames.string", equalTo("1-4"));
        get(jobPath + "/parameter-definitions").statusCode(200).body("jobParameterDefinitions", hasSize(2));
        List<String> steps = get(jobPath + "/steps").statusCode(200).extract().path("steps.stepId");
        String render = steps.get(0);
        String encode = steps.get(1);
        get(jobPath + "/steps/" + encode + "/dependencies").statusCode(200)
                .body("dependencies[0].stepId", equalTo(render)).body("dependencies[0].status",
                        equalTo("UNRESOLVED"));
        get(jobPath + "/steps/" + render + "/tasks").statusCode(200).body("tasks", hasSize(8))
                .body("tasks[1].parameters.Layer.string", equalTo("bg"))
                .body("tasks[2].parameters.Frame.int", equalTo("2"));

        // Suspending, then requeueing, the render step moves every task.
        patch(jobPath + "/steps/" + render, "{\"targetTaskRunStatus\":\"SUSPENDED\"}").statusCode(200);
        get(jobPath + "/steps/" + render).statusCode(200).body("taskRunStatus", equalTo("SUSPENDED"));
        patch(jobPath + "/steps/" + render, "{\"targetTaskRunStatus\":\"READY\"}").statusCode(200);
        // Succeeding every render task resolves the encode step's dependency.
        patch(jobPath + "/steps/" + render, "{\"targetTaskRunStatus\":\"SUCCEEDED\"}").statusCode(200);
        get(jobPath + "/steps/" + encode).statusCode(200).body("taskRunStatus", equalTo("READY"));
        patch(jobPath, "{\"priority\":90}").statusCode(200);
        get(jobPath).statusCode(200).body("priority", equalTo(90)).body("lifecycleStatus",
                equalTo("UPDATE_SUCCEEDED"));
        patch(jobPath, "{\"lifecycleStatus\":\"ARCHIVED\"}").statusCode(409);

        post("/farms/" + farmId + "/search/jobs", "{\"queueIds\":[\"" + queueId + "\"],\"itemOffset\":0,"
                + "\"filterExpressions\":{\"operator\":\"AND\",\"filters\":[{\"searchTermFilter\":{\"searchTerm\":"
                + "\"sh010\",\"matchType\":\"CONTAINS\"}}]}}").statusCode(200).body("totalResults", equalTo(1))
                .body("jobs[0].jobId", equalTo(jobId));
        post("/farms/" + farmId + "/search/tasks", "{\"queueIds\":[\"" + queueId + "\"],\"jobId\":\"" + jobId
                + "\",\"itemOffset\":0,\"pageSize\":5}").statusCode(200).body("totalResults", equalTo(9))
                .body("tasks", hasSize(5)).body("nextItemOffset", equalTo(5));

        String cyclic = "{\"specificationVersion\":\"jobtemplate-2023-09\",\"name\":\"c\",\"steps\":[{\"name\":"
                + "\"A\",\"dependencies\":[{\"dependsOn\":\"B\"}],\"script\":{\"actions\":{\"onRun\":{\"command\":"
                + "\"a\"}}}},{\"name\":\"B\",\"dependencies\":[{\"dependsOn\":\"A\"}],\"script\":{\"actions\":"
                + "{\"onRun\":{\"command\":\"b\"}}}}]}";
        post("/farms/" + farmId + "/queues/" + queueId + "/jobs", "{\"template\":" + quote(cyclic)
                + ",\"templateType\":\"JSON\",\"priority\":1}").statusCode(400).body("message",
                containsString("cycle"));
        String yaml = "specificationVersion: jobtemplate-2023-09\nname: yaml-job\nsteps:\n  - name: Only\n    script:\n"
                + "      actions:\n        onRun:\n          command: echo\n";
        post("/farms/" + farmId + "/queues/" + queueId + "/jobs", "{\"template\":" + quote(yaml)
                + ",\"templateType\":\"YAML\",\"priority\":1}").statusCode(201);
    }

    @Test
    void aWorkerRunsAJobToCompletion() {
        String farmId = farm();
        String queueId = queue(farmId);
        String fleetId = fleet(farmId);
        dl().body("{\"queueId\":\"" + queueId + "\",\"fleetId\":\"" + fleetId + "\"}")
                .put(BASE + "/farms/" + farmId + "/queue-fleet-associations").then().statusCode(200);
        dl().body("{\"priority\":1,\"templateType\":\"JSON\",\"template\":" + quote("{\"specificationVersion\":"
                + "\"environment-2023-09\",\"environment\":{\"name\":\"Conda\",\"script\":{\"actions\":{\"onEnter\":"
                + "{\"command\":\"conda\"}}}}}") + "}").post(BASE + "/farms/" + farmId + "/queues/" + queueId
                + "/environments").then().statusCode(200);
        String template = "{\"specificationVersion\":\"jobtemplate-2023-09\",\"name\":\"Frames\",\"steps\":[{\"name\":"
                + "\"Render\",\"parameterSpace\":{\"taskParameterDefinitions\":[{\"name\":\"Frame\",\"type\":\"INT\","
                + "\"range\":\"1-2\"}]},\"hostRequirements\":{\"amounts\":[{\"name\":\"amount.worker.vcpu\","
                + "\"min\":2}]},\"script\":{\"actions\":{\"onRun\":{\"command\":\"render\"}}}}]}";
        String jobId = job(farmId, queueId, template, null);
        String jobPath = "/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId;

        String workerPath = "/farms/" + farmId + "/fleets/" + fleetId + "/workers/" + post("/farms/" + farmId
                + "/fleets/" + fleetId + "/workers", "{\"hostProperties\":{\"hostName\":\"render-01\"}}")
                .statusCode(200).extract().path("workerId");
        patch(workerPath + "/schedule", "{}").statusCode(409);
        patch(workerPath, "{\"status\":\"STARTED\",\"capabilities\":{\"amounts\":[{\"name\":\"amount.worker.vcpu\","
                + "\"value\":4},{\"name\":\"amount.worker.memory\",\"value\":8192}],\"attributes\":[{\"name\":"
                + "\"attr.worker.os.family\",\"values\":[\"linux\"]},{\"name\":\"attr.worker.cpu.arch\","
                + "\"values\":[\"x86_64\"]}]}}").statusCode(200)
                .body("log.options.logGroupName", equalTo("/aws/deadline/" + farmId + "/" + fleetId));
        dl().get(BASE + workerPath + "/fleet-roles").then().statusCode(200).body("credentials.accessKeyId",
                startsWith("ASIA"));

        // The first poll opens a session: enter the queue environment, then run the first frame.
        ValidatableResponse first = patch(workerPath + "/schedule", "{}").statusCode(200);
        Map<String, Object> sessions = first.extract().path("assignedSessions");
        assertThat(sessions.size(), equalTo(1));
        String sessionId = sessions.keySet().iterator().next();
        String prefix = "assignedSessions." + sessionId;
        first.body(prefix + ".jobId", equalTo(jobId)).body(prefix + ".sessionActions", hasSize(2))
                .body(prefix + ".sessionActions[0].definition", hasKey("envEnter"))
                .body(prefix + ".sessionActions[1].definition.taskRun.parameters.Frame.int", equalTo("1"));
        List<String> actions = first.extract().path(prefix + ".sessionActions.sessionActionId");
        String environment = first.extract().path(prefix + ".sessionActions[0].definition.envEnter.environmentId");
        post(workerPath + "/batchGetJobEntity", "{\"identifiers\":[{\"jobDetails\":{\"jobId\":\"" + jobId + "\"}},"
                + "{\"environmentDetails\":{\"jobId\":\"" + jobId + "\",\"environmentId\":\"" + environment
                + "\"}}]}").statusCode(200).body("entities", hasSize(2))
                .body("entities[0].jobDetails.logGroupName", equalTo("/aws/deadline/" + farmId + "/" + queueId))
                .body("entities[1].environmentDetails.template.name", equalTo("Conda"));

        // Report the environment entered and the first frame running, then succeeded.
        patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + actions.get(0) + "\":{\"completedStatus\":"
                + "\"SUCCEEDED\"},\"" + actions.get(1) + "\":{\"startedAt\":" + Instant.now().getEpochSecond()
                + "}}}").statusCode(200);
        get(jobPath).statusCode(200).body("taskRunStatus", equalTo("RUNNING"));
        ValidatableResponse second = patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\""
                + actions.get(1) + "\":{\"completedStatus\":\"SUCCEEDED\",\"processExitCode\":0}}}").statusCode(200);
        String nextAction = second.extract().path(prefix + ".sessionActions[0].sessionActionId");
        second.body(prefix + ".sessionActions[0].definition.taskRun.parameters.Frame.int", equalTo("2"));

        // The second frame fails once (retried), then succeeds; the session then exits its environment and ends.
        ValidatableResponse retried = patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + nextAction
                + "\":{\"completedStatus\":\"FAILED\",\"processExitCode\":1}}}").statusCode(200);
        String retry = retried.extract().path(prefix + ".sessionActions[0].sessionActionId");
        ValidatableResponse exit = patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + retry
                + "\":{\"completedStatus\":\"SUCCEEDED\"}}}").statusCode(200);
        exit.body(prefix + ".sessionActions[0].definition.envExit.environmentId", equalTo(environment));
        String exitAction = exit.extract().path(prefix + ".sessionActions[0].sessionActionId");
        patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + exitAction + "\":{\"completedStatus\":"
                + "\"SUCCEEDED\"}}}").statusCode(200).body("assignedSessions.size()", equalTo(0));

        get(jobPath).statusCode(200).body("taskRunStatus", equalTo("SUCCEEDED")).body("endedAt", notNullValue())
                .body("taskFailureRetryCount", equalTo(1));
        get(jobPath + "/sessions/" + sessionId).statusCode(200).body("lifecycleStatus", equalTo("ENDED"))
                .body("workerId", equalTo(workerPath.substring(workerPath.lastIndexOf('/') + 1)));
        get(jobPath + "/session-actions?sessionId=" + sessionId).statusCode(200).body("sessionActions", hasSize(5));
        patch(jobPath, "{\"lifecycleStatus\":\"ARCHIVED\"}").statusCode(200);

        // Statistics aggregate the session's runtime by queue.
        long now = Instant.now().getEpochSecond();
        String aggregation = post("/farms/" + farmId + "/sessions-statistics-aggregation", "{\"resourceIds\":"
                + "{\"queueIds\":[\"" + queueId + "\"]},\"startTime\":" + (now - 3600) + ",\"endTime\":" + (now + 60)
                + ",\"groupBy\":[\"QUEUE_ID\"],\"statistics\":[\"SUM\",\"AVG\"]}").statusCode(200).extract()
                .path("aggregationId");
        get("/farms/" + farmId + "/sessions-statistics-aggregation?aggregationId=" + aggregation).statusCode(200)
                .body("status", equalTo("COMPLETED")).body("statistics[0].queueId", equalTo(queueId))
                .body("statistics[0].count", equalTo(1));

        patch(workerPath, "{\"status\":\"STOPPED\"}").statusCode(200);
        dl().delete(BASE + workerPath).then().statusCode(200);
    }

    @Test
    void canceledTasksInterruptTheirWorker() {
        String farmId = farm();
        String queueId = queue(farmId);
        String fleetId = fleet(farmId);
        dl().body("{\"queueId\":\"" + queueId + "\",\"fleetId\":\"" + fleetId + "\"}")
                .put(BASE + "/farms/" + farmId + "/queue-fleet-associations").then().statusCode(200);
        String jobId = job(farmId, queueId, "{\"specificationVersion\":\"jobtemplate-2023-09\",\"name\":\"long\","
                + "\"steps\":[{\"name\":\"Sleep\",\"script\":{\"actions\":{\"onRun\":{\"command\":\"sleep\"}}}}]}",
                null);
        String jobPath = "/farms/" + farmId + "/queues/" + queueId + "/jobs/" + jobId;
        String workerPath = "/farms/" + farmId + "/fleets/" + fleetId + "/workers/" + post("/farms/" + farmId
                + "/fleets/" + fleetId + "/workers", "{}").statusCode(200).extract().path("workerId");
        patch(workerPath, "{\"status\":\"STARTED\",\"capabilities\":{\"amounts\":[{\"name\":\"amount.worker.vcpu\","
                + "\"value\":2},{\"name\":\"amount.worker.memory\",\"value\":2048}],\"attributes\":[{\"name\":"
                + "\"attr.worker.os.family\",\"values\":[\"linux\"]},{\"name\":\"attr.worker.cpu.arch\",\"values\":"
                + "[\"x86_64\"]}]}}").statusCode(200);
        ValidatableResponse assigned = patch(workerPath + "/schedule", "{}").statusCode(200);
        Map<String, Object> sessions = assigned.extract().path("assignedSessions");
        String sessionId = sessions.keySet().iterator().next();
        String action = assigned.extract().path("assignedSessions." + sessionId + ".sessionActions[0].sessionActionId");
        patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + action + "\":{\"startedAt\":"
                + Instant.now().getEpochSecond() + "}}}").statusCode(200);
        String stepId = get(jobPath + "/steps").extract().path("steps[0].stepId");
        String taskId = get(jobPath + "/steps/" + stepId + "/tasks").extract().path("tasks[0].taskId");
        patch(jobPath + "/steps/" + stepId + "/tasks/" + taskId, "{\"targetRunStatus\":\"CANCELED\"}").statusCode(200);
        get(jobPath + "/steps/" + stepId + "/tasks/" + taskId).statusCode(200).body("runStatus",
                equalTo("INTERRUPTING"));
        patch(workerPath + "/schedule", "{}").statusCode(200)
                .body("cancelSessionActions." + sessionId, hasItem(action));
        patch(workerPath + "/schedule", "{\"updatedSessionActions\":{\"" + action + "\":{\"completedStatus\":"
                + "\"CANCELED\"}}}").statusCode(200);
        get(jobPath + "/steps/" + stepId + "/tasks/" + taskId).statusCode(200).body("runStatus",
                equalTo("CANCELED"));
        get(jobPath).statusCode(200).body("taskRunStatus", equalTo("CANCELED"));
    }
}
