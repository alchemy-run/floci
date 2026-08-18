package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Control-plane gaps Alchemy's ECS Bindings suite hits: DescribeTasks reports
 * {@code MISSING} for an unknown task, ListTasks honors {@code startedBy}, and
 * task protection on a standalone RunTask task is {@code TASK_NOT_VALID}.
 */
@QuarkusTest
class EcsDescribeTasksIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    private static void seed(String cluster, String family) {
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}");
    }

    @Test
    void unknownTaskIsReportedAsAMissingFailureRatherThanDroppedSilently() {
        seed("task-miss-cluster", "task-miss-td");

        call("DescribeTasks", "{\"cluster\":\"task-miss-cluster\","
                + "\"tasks\":[\"00000000000000000000000000000000\"]}")
                .then()
                .body("tasks", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"));
    }

    @Test
    void listTasksFiltersByStartedBy() {
        seed("task-started-cluster", "task-started-td");
        String taskArn = call("RunTask", "{\"cluster\":\"task-started-cluster\","
                + "\"taskDefinition\":\"task-started-td\",\"startedBy\":\"alchemy-list-test\"}")
                .path("tasks[0].taskArn");

        call("ListTasks", "{\"cluster\":\"task-started-cluster\",\"startedBy\":\"alchemy-list-test\"}")
                .then()
                .body("taskArns", hasItem(taskArn));
        call("ListTasks", "{\"cluster\":\"task-started-cluster\",\"startedBy\":\"someone-else\"}")
                .then()
                .body("taskArns", not(hasItem(taskArn)));
    }

    @Test
    void standaloneRunTaskProtectionIsTaskNotValid() {
        seed("task-prot-cluster", "task-prot-td");
        String taskArn = call("RunTask", "{\"cluster\":\"task-prot-cluster\","
                + "\"taskDefinition\":\"task-prot-td\"}")
                .path("tasks[0].taskArn");

        call("UpdateTaskProtection", "{\"cluster\":\"task-prot-cluster\","
                + "\"tasks\":[\"" + taskArn + "\"],\"protectionEnabled\":true,\"expiresInMinutes\":10}")
                .then()
                .body("protectedTasks", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("TASK_NOT_VALID"));

        call("GetTaskProtection", "{\"cluster\":\"task-prot-cluster\","
                + "\"tasks\":[\"" + taskArn + "\"]}")
                .then()
                .body("protectedTasks", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("TASK_NOT_VALID"));
    }
}
