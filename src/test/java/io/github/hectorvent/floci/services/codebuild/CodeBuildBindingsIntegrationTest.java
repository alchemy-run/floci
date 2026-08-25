package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CodeBuildBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String FAKE_UUID = "00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourcePolicyWithoutPolicyIsNotFound() {
        String name = unique("policy");
        String arn = createProject(name);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void putGetDeleteResourcePolicyRoundTrip() {
        String name = unique("policy-rt");
        String arn = createProject(name);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.PutResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\", \"policy\": " + quote(policy) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("resourceArn", equalTo(arn));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("policy", equalTo(policy));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void startStopRetryAndBatchDeleteBuild() {
        String name = unique("build");
        createProject(name);

        String buildId = given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("build.id", containsString(name))
            .body("build.buildStatus", equalTo("IN_PROGRESS"))
            .extract().path("build.id");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListBuildsForProject")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ids", hasItem(buildId));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.StopBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + buildId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("build.buildStatus", equalTo("STOPPED"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.RetryBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + buildId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("build.id", containsString(name));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchDeleteBuilds")
            .contentType(CONTENT_TYPE)
            .body("{\"ids\": [\"" + buildId + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("buildsNotDeleted[0].id", equalTo(buildId));
    }

    @Test
    void invalidateProjectCacheSucceeds() {
        String name = unique("cache");
        createProject(name);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.InvalidateProjectCache")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void startBuildBatchWithoutConfigIsInvalidInput() {
        String name = unique("batch");
        createProject(name);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartBuildBatch")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidInputException"));
    }

    @Test
    void listAndGetBuildBatchesForUnknownIds() {
        String name = unique("batch-list");
        createProject(name);
        String fakeId = name + ":" + FAKE_UUID;

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListBuildBatchesForProject")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ids", empty());

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetBuildBatches")
            .contentType(CONTENT_TYPE)
            .body("{\"ids\": [\"" + fakeId + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("buildBatches", empty())
            .body("buildBatchesNotFound", hasItem(fakeId));
    }

    @Test
    void stopRetryDeleteBuildBatchUnknownIdAreTyped() {
        String fakeId = "missing-project:" + FAKE_UUID;

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.StopBuildBatch")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + fakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.RetryBuildBatch")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + fakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteBuildBatch")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + fakeId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidInputException"));
    }

    @Test
    void startStopListSandboxes() {
        String name = unique("sandbox");
        createProject(name);

        String sandboxId = given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartSandbox")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("sandbox.id", containsString(name))
            .extract().path("sandbox.id");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.StopSandbox")
            .contentType(CONTENT_TYPE)
            .body("{\"id\": \"" + sandboxId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("sandbox.status", equalTo("STOPPED"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetSandboxes")
            .contentType(CONTENT_TYPE)
            .body("{\"ids\": [\"" + sandboxId + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("sandboxes[0].id", equalTo(sandboxId));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListSandboxesForProject")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ids", hasItem(sandboxId));
    }

    @Test
    void commandExecutionUnknownSandboxIsNotFound() {
        String fakeSandbox = "missing-project:" + FAKE_UUID;

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartCommandExecution")
            .contentType(CONTENT_TYPE)
            .body("{\"sandboxId\": \"" + fakeSandbox + "\", \"command\": \"echo hello\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetCommandExecutions")
            .contentType(CONTENT_TYPE)
            .body("{\"sandboxId\": \"" + fakeSandbox + "\", \"commandExecutionIds\": [\"" + FAKE_UUID + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("commandExecutions", empty())
            .body("commandExecutionsNotFound", hasItem(FAKE_UUID));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListCommandExecutionsForSandbox")
            .contentType(CONTENT_TYPE)
            .body("{\"sandboxId\": \"" + fakeSandbox + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void reportBindingsOnEmptyGroup() {
        String name = unique("reports");
        String groupArn = createReportGroup(name);
        String fakeReportArn = groupArn.replace(":report-group/", ":report/") + ":" + FAKE_UUID;

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListReportsForReportGroup")
            .contentType(CONTENT_TYPE)
            .body("{\"reportGroupArn\": \"" + groupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reports", empty());

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetReports")
            .contentType(CONTENT_TYPE)
            .body("{\"reportArns\": [\"" + fakeReportArn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reports", empty())
            .body("reportsNotFound", hasItem(fakeReportArn));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DescribeTestCases")
            .contentType(CONTENT_TYPE)
            .body("{\"reportArn\": \"" + fakeReportArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("testCases", empty());

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DescribeCodeCoverages")
            .contentType(CONTENT_TYPE)
            .body("{\"reportArn\": \"" + fakeReportArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("codeCoverages", empty());

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.GetReportGroupTrend")
            .contentType(CONTENT_TYPE)
            .body("{\"reportGroupArn\": \"" + groupArn + "\", \"trendField\": \"DURATION\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("stats.average", notNullValue());

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteReport")
            .contentType(CONTENT_TYPE)
            .body("{\"arn\": \"" + fakeReportArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String unique(String prefix) {
        return "cb-bind-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String createProject(String name) {
        return given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "source": {"type": "NO_SOURCE", "buildspec": "version: 0.2\\nphases:\\n  build:\\n    commands:\\n      - echo hi"},
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/standard:7.0",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """.formatted(name))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("project.arn");
    }

    private static String createReportGroup(String name) {
        return given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateReportGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "type": "TEST",
                    "exportConfig": {"exportConfigType": "NO_EXPORT"}
                }
                """.formatted(name))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("reportGroup.arn");
    }
}
