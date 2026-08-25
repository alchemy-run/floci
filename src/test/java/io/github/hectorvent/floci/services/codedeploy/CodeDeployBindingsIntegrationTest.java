package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Binding-plane operations Alchemy's CodeDeploy Bindings suite exercises:
 * typed RevisionRequiredException, unknown-id errors, and S3 revision metadata.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String APP = "alchemy-bindings-rev-app";
    private static final String GROUP = "alchemy-bindings-rev-dg";
    private static final String FAKE_DEPLOYMENT = "d-AAAAAAAAA";
    private static final String REVISION = """
            {
                "revisionType": "S3",
                "s3Location": {
                    "bucket": "alchemy-test-codedeploy-bindings-nonexistent",
                    "key": "app.zip",
                    "bundleType": "zip"
                }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createLambdaApplicationAndGroup() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "%s", "computePlatform": "Lambda"}
                """.formatted(APP))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue());

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "deploymentGroupName": "%s",
                    "deploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy-role",
                    "deploymentStyle": {
                        "deploymentType": "BLUE_GREEN",
                        "deploymentOption": "WITH_TRAFFIC_CONTROL"
                    }
                }
                """.formatted(APP, GROUP))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupId", notNullValue());
    }

    @Test
    @Order(2)
    void createDeploymentWithoutRevisionIsRevisionRequired() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeployment")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "deploymentGroupName": "%s"
                }
                """.formatted(APP, GROUP))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("RevisionRequiredException"));
    }

    @Test
    @Order(3)
    void unknownDeploymentIdsAreTyped() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeployment")
            .contentType(CONTENT_TYPE)
            .body("{\"deploymentId\": \"%s\"}".formatted(FAKE_DEPLOYMENT))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DeploymentDoesNotExistException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ContinueDeployment")
            .contentType(CONTENT_TYPE)
            .body("{\"deploymentId\": \"%s\"}".formatted(FAKE_DEPLOYMENT))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DeploymentDoesNotExistException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.PutLifecycleEventHookExecutionStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentId": "%s",
                    "lifecycleEventHookExecutionId": "00000000-0000-0000-0000-000000000000",
                    "status": "Succeeded"
                }
                """.formatted(FAKE_DEPLOYMENT))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DeploymentDoesNotExistException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentTarget")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentId": "%s",
                    "targetId": "alchemy-test-nonexistent-function"
                }
                """.formatted(FAKE_DEPLOYMENT))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("DeploymentDoesNotExistException"));
    }

    @Test
    @Order(4)
    void registerGetListAndBatchGetS3Revision() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.RegisterApplicationRevision")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "description": "alchemy CodeDeploy bindings fixture revision",
                    "revision": %s
                }
                """.formatted(APP, REVISION))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetApplicationRevision")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "revision": %s
                }
                """.formatted(APP, REVISION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationName", equalTo(APP))
            .body("revisionInfo.description",
                    equalTo("alchemy CodeDeploy bindings fixture revision"))
            .body("revision.s3Location.bucket",
                    equalTo("alchemy-test-codedeploy-bindings-nonexistent"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListApplicationRevisions")
            .contentType(CONTENT_TYPE)
            .body("{\"applicationName\": \"%s\"}".formatted(APP))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("revisions", hasSize(greaterThanOrEqualTo(1)))
            .body("revisions.s3Location.key", hasItem("app.zip"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetApplicationRevisions")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "revisions": [%s]
                }
                """.formatted(APP, REVISION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("revisions", hasSize(1))
            .body("revisions[0].genericRevisionInfo.description",
                    equalTo("alchemy CodeDeploy bindings fixture revision"));
    }
}
