package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CodeBuildProjectIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createNoSourceProjectDefaultsLogsAndEnvironment() {
        String name = unique("defaults");
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "source": {
                        "type": "NO_SOURCE",
                        "buildspec": "version: 0.2\\nphases:\\n  build:\\n    commands:\\n      - echo hi"
                    },
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "image": "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                        "environmentVariables": [{"name": "STAGE", "value": "dev"}]
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """.formatted(name))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.name", equalTo(name))
            .body("project.source.type", equalTo("NO_SOURCE"))
            .body("project.environment.type", equalTo("LINUX_CONTAINER"))
            .body("project.environment.computeType", equalTo("BUILD_GENERAL1_SMALL"))
            .body("project.environment.environmentVariables[0].name", equalTo("STAGE"))
            .body("project.environment.environmentVariables[0].value", equalTo("dev"))
            .body("project.environment.environmentVariables[0].type", equalTo("PLAINTEXT"))
            .body("project.logsConfig.cloudWatchLogs.status", equalTo("ENABLED"))
            .body("project.logsConfig.s3Logs.status", equalTo("DISABLED"));
    }

    @Test
    void updateProjectEnvTimeoutAndLogsConfig() {
        String name = unique("update");
        createNoSource(name);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.UpdateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "timeoutInMinutes": 30,
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                        "computeType": "BUILD_GENERAL1_MEDIUM",
                        "environmentVariables": [{"name": "STAGE", "value": "prod"}]
                    },
                    "logsConfig": {
                        "cloudWatchLogs": {"status": "DISABLED"},
                        "s3Logs": {"status": "DISABLED"}
                    }
                }
                """.formatted(name))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.timeoutInMinutes", equalTo(30))
            .body("project.environment.computeType", equalTo("BUILD_GENERAL1_MEDIUM"))
            .body("project.environment.environmentVariables[0].value", equalTo("prod"))
            .body("project.logsConfig.cloudWatchLogs.status", equalTo("DISABLED"))
            .body("project.logsConfig.s3Logs.status", equalTo("DISABLED"));
    }

    @Test
    void projectResourcePolicyRoundTrip() {
        String name = unique("policy");
        String arn = createNoSource(name);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Share\",\"Effect\":\"Allow\",\"Action\":\"codebuild:BatchGetProjects\",\"Resource\":\"*\"}]}";

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
            .contentType(CONTENT_TYPE)
            .body("{\"resourceArn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));

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
    void listProjectsIncludesCreatedName() {
        String name = unique("list");
        createNoSource(name);

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListProjects")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("projects", hasItem(name));
    }

    private static String unique(String prefix) {
        return "cb-proj-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String createNoSource(String name) {
        return given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "source": {
                        "type": "NO_SOURCE",
                        "buildspec": "version: 0.2\\nphases:\\n  build:\\n    commands:\\n      - echo hi"
                    },
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
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
}
