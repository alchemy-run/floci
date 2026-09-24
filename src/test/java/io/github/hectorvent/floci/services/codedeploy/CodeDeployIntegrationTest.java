package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void listDeploymentConfigsIncludesBuiltIns() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentConfigs")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.AllAtOnce"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.HalfAtATime"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.OneAtATime"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.ECSAllAtOnce"));
    }

    @Test
    @Order(2)
    void getBuiltInDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigInfo.deploymentConfigName", equalTo("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentConfigInfo.computePlatform", equalTo("Lambda"))
            .body("deploymentConfigInfo.trafficRoutingConfig.type", equalTo("AllAtOnce"));
    }

    @Test
    @Order(3)
    void createApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "computePlatform": "Lambda"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue());
    }

    @Test
    @Order(4)
    void createDuplicateApplicationFails() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app", "computePlatform": "Lambda"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ApplicationAlreadyExistsException"));
    }

    @Test
    @Order(5)
    void getApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("application.applicationName", equalTo("my-lambda-app"))
            .body("application.computePlatform", equalTo("Lambda"))
            .body("application.linkedToGitHub", equalTo(false));
    }

    @Test
    @Order(6)
    void listApplications() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListApplications")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applications", hasItem("my-lambda-app"));
    }

    @Test
    @Order(7)
    void batchGetApplications() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetApplications")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationNames": ["my-lambda-app"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationsInfo", hasSize(1))
            .body("applicationsInfo[0].applicationName", equalTo("my-lambda-app"));
    }

    @Test
    @Order(8)
    void createDeploymentGroup() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg",
                    "deploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy-role",
                    "deploymentStyle": {
                        "deploymentType": "BLUE_GREEN",
                        "deploymentOption": "WITH_TRAFFIC_CONTROL"
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupId", notNullValue());
    }

    @Test
    @Order(9)
    void getDeploymentGroup() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupInfo.deploymentGroupName", equalTo("my-lambda-dg"))
            .body("deploymentGroupInfo.applicationName", equalTo("my-lambda-app"))
            .body("deploymentGroupInfo.computePlatform", equalTo("Lambda"))
            .body("deploymentGroupInfo.deploymentConfigName", equalTo("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentGroupInfo.serviceRoleArn", equalTo("arn:aws:iam::000000000000:role/codedeploy-role"));
    }

    @Test
    @Order(10)
    void listDeploymentGroups() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentGroups")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationName", equalTo("my-lambda-app"))
            .body("deploymentGroups", hasItem("my-lambda-dg"));
    }

    @Test
    @Order(11)
    void batchGetDeploymentGroups() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetDeploymentGroups")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupNames": ["my-lambda-dg"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupsInfo", hasSize(1))
            .body("deploymentGroupsInfo[0].deploymentGroupName", equalTo("my-lambda-dg"));
    }

    @Test
    @Order(12)
    void createCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentConfigName": "MyCustomConfig",
                    "minimumHealthyHosts": {
                        "type": "FLEET_PERCENT",
                        "value": 75
                    },
                    "computePlatform": "Server"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigId", notNullValue());
    }

    @Test
    @Order(13)
    void getCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "MyCustomConfig"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigInfo.deploymentConfigName", equalTo("MyCustomConfig"))
            .body("deploymentConfigInfo.computePlatform", equalTo("Server"))
            .body("deploymentConfigInfo.minimumHealthyHosts.type", equalTo("FLEET_PERCENT"))
            .body("deploymentConfigInfo.minimumHealthyHosts.value", equalTo(75));
    }

    @Test
    @Order(14)
    void cannotCreateDeploymentConfigWithBuiltInPrefix() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentConfigName": "CodeDeployDefault.MyCustom",
                    "minimumHealthyHosts": {"type": "FLEET_PERCENT", "value": 50}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidDeploymentConfigNameException"));
    }

    @Test
    @Order(15)
    void tagAndUntagResource() {
        String appArn = "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app";

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app",
                    "Tags": [
                        {"Key": "env", "Value": "test"},
                        {"Key": "team", "Value": "platform"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.Key", hasItems("env", "team"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.UntagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app",
                    "TagKeys": ["team"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));
    }

    @Test
    @Order(16)
    void deleteDeploymentGroupAndApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(17)
    void deleteCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "MyCustomConfig"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void deploymentControlsRejectUnknownMissingAndMalformedIds() {
        for (String action : List.of("StopDeployment", "ContinueDeployment", "PutLifecycleEventHookExecutionStatus")) {
            call(action, Map.of("deploymentId", "d-AAAAAAAAA", "lifecycleEventHookExecutionId", "unknown",
                    "status", "Succeeded"))
                    .statusCode(400).body("__type", equalTo("DeploymentDoesNotExistException"));
            call(action, Map.of()).statusCode(400).body("__type", equalTo("DeploymentIdRequiredException"));
            call(action, Map.of("deploymentId", "malformed"))
                    .statusCode(400).body("__type", equalTo("InvalidDeploymentIdException"));
        }
    }

    @Test
    void groupsInheritPlatformAndMissingRevisionIsDistinctFromInvalidRevision() {
        for (String platform : List.of("Lambda", "ECS", "Server")) {
            String app = "wire-platform-" + platform;
            call("CreateApplication", Map.of("applicationName", app, "computePlatform", platform)).statusCode(200);
            try {
                call("CreateDeploymentGroup", Map.of("applicationName", app, "deploymentGroupName", "group",
                        "serviceRoleArn", "arn:aws:iam::000000000000:role/codedeploy-role",
                        "computePlatform", "invalid-override")).statusCode(200);
                call("GetDeploymentGroup", Map.of("applicationName", app, "deploymentGroupName", "group"))
                        .statusCode(200).body("deploymentGroupInfo.computePlatform", equalTo(platform));
                call("UpdateDeploymentGroup", Map.of("applicationName", app, "currentDeploymentGroupName", "group",
                        "newDeploymentGroupName", "renamed")).statusCode(200);
                call("BatchGetDeploymentGroups", Map.of("applicationName", app, "deploymentGroupNames", List.of("renamed")))
                        .statusCode(200).body("deploymentGroupsInfo[0].computePlatform", equalTo(platform));
                call("CreateDeployment", Map.of("applicationName", app, "deploymentGroupName", "renamed"))
                        .statusCode(400).body("__type", equalTo("RevisionRequiredException"));
                call("CreateDeployment", Map.of("applicationName", app, "deploymentGroupName", "renamed", "revision", Map.of()))
                        .statusCode(400).body("__type", equalTo("InvalidRevisionException"));
                call("ListDeployments", Map.of("applicationName", app)).statusCode(200).body("deployments", empty());
            } finally {
                call("DeleteApplication", Map.of("applicationName", app)).statusCode(200);
            }
        }
    }

    @Test
    void revisionsRoundTripAndRemainIsolatedByApplicationRegionAndAccount() {
        String app = "wire-revisions";
        Map<String, Object> revision = Map.of("revisionType", "S3", "s3Location",
                Map.of("bucket", "metadata-only-bucket", "key", "bundles/app.zip", "bundleType", "zip", "version", "v1"));
        String owner = "000000000011";
        String other = "000000000012";
        for (String account : List.of(owner, other)) {
            call("CreateApplication", Map.of("applicationName", app, "computePlatform", "Lambda"), "us-east-1", account)
                    .statusCode(200);
        }
        call("CreateApplication", Map.of("applicationName", app), "us-west-2", owner).statusCode(200);
        call("CreateApplication", Map.of("applicationName", app + "-other"), "us-east-1", owner).statusCode(200);
        try {
            call("GetApplicationRevision", Map.of("applicationName", app, "revision", revision), "us-east-1", owner)
                    .statusCode(400).body("__type", equalTo("RevisionDoesNotExistException"));
            call("RegisterApplicationRevision", Map.of("applicationName", app, "revision", revision,
                    "description", "first"), "us-east-1", owner).statusCode(200);
            call("RegisterApplicationRevision", Map.of("applicationName", app, "revision", revision,
                    "description", "updated"), "us-east-1", owner).statusCode(200);
            call("GetApplicationRevision", Map.of("applicationName", app, "revision", revision), "us-east-1", owner)
                    .statusCode(200).body("applicationName", equalTo(app)).body("revision", equalTo(revision))
                    .body("revisionInfo.description", equalTo("updated"))
                    .body("revisionInfo.registerTime", instanceOf(Number.class))
                    .body("revisionInfo.deploymentGroups", empty());
            call("ListApplicationRevisions", Map.of("applicationName", app, "s3Bucket", "metadata-only-bucket",
                    "s3KeyPrefix", "bundles/", "deployed", "exclude"), "us-east-1", owner)
                    .statusCode(200).body("revisions", contains(revision));
            call("BatchGetApplicationRevisions", Map.of("applicationName", app, "revisions", List.of(revision)), "us-east-1", owner)
                    .statusCode(200).body("applicationName", equalTo(app)).body("revisions", hasSize(1))
                    .body("revisions[0].revisionLocation", equalTo(revision))
                    .body("revisions[0].genericRevisionInfo.description", equalTo("updated"));
            for (String[] scope : List.of(new String[]{"us-east-1", other, app},
                    new String[]{"us-west-2", owner, app}, new String[]{"us-east-1", owner, app + "-other"})) {
                call("ListApplicationRevisions", Map.of("applicationName", scope[2]), scope[0], scope[1])
                        .statusCode(200).body("revisions", empty());
                call("GetApplicationRevision", Map.of("applicationName", scope[2], "revision", revision), scope[0], scope[1])
                        .statusCode(400).body("__type", equalTo("RevisionDoesNotExistException"));
                call("BatchGetApplicationRevisions", Map.of("applicationName", scope[2], "revisions", List.of(revision)), scope[0], scope[1])
                        .statusCode(200).body("revisions", empty());
            }
            call("DeleteApplication", Map.of("applicationName", app), "us-east-1", owner).statusCode(200);
            call("CreateApplication", Map.of("applicationName", app), "us-east-1", owner).statusCode(200);
            call("ListApplicationRevisions", Map.of("applicationName", app), "us-east-1", owner)
                    .statusCode(200).body("revisions", empty());
        } finally {
            call("DeleteApplication", Map.of("applicationName", app), "us-east-1", owner).statusCode(200);
            call("DeleteApplication", Map.of("applicationName", app), "us-east-1", other).statusCode(200);
            call("DeleteApplication", Map.of("applicationName", app), "us-west-2", owner).statusCode(200);
            call("DeleteApplication", Map.of("applicationName", app + "-other"), "us-east-1", owner).statusCode(200);
        }
    }

    @Test
    void deploymentControlsCannotAddressAnotherAccountOrRegion() {
        String app = "wire-deployment-scope";
        String owner = "000000000031";
        call("CreateApplication", Map.of("applicationName", app, "computePlatform", "Server"), "us-east-1", owner)
                .statusCode(200);
        try {
            call("CreateDeploymentGroup", Map.of("applicationName", app, "deploymentGroupName", "group",
                    "serviceRoleArn", "arn:aws:iam::000000000031:role/codedeploy"), "us-east-1", owner).statusCode(200);
            String deploymentId = call("CreateDeployment", Map.of("applicationName", app, "deploymentGroupName", "group",
                    "revision", Map.of("revisionType", "AppSpecContent", "appSpecContent", Map.of("content", "os: linux"))),
                    "us-east-1", owner).statusCode(200).extract().path("deploymentId");
            call("GetDeployment", Map.of("deploymentId", deploymentId), "us-east-1", owner)
                    .statusCode(200).body("deploymentInfo.status", equalTo("Failed"))
                    .body("deploymentInfo.errorInformation.code", equalTo("NoInstancesReachable"));
            for (String action : List.of("StopDeployment", "ContinueDeployment", "PutLifecycleEventHookExecutionStatus")) {
                Map<String, Object> request = Map.of("deploymentId", deploymentId,
                        "lifecycleEventHookExecutionId", "unknown", "status", "Succeeded");
                call(action, request, "us-east-1", "000000000032")
                        .statusCode(400).body("__type", equalTo("DeploymentDoesNotExistException"));
                call(action, request, "us-west-2", owner)
                        .statusCode(400).body("__type", equalTo("DeploymentDoesNotExistException"));
            }
            call("StopDeployment", Map.of("deploymentId", deploymentId), "us-east-1", owner)
                    .statusCode(400).body("__type", equalTo("DeploymentAlreadyCompletedException"));
        } finally {
            call("DeleteApplication", Map.of("applicationName", app), "us-east-1", owner).statusCode(200);
        }
    }

    @Test
    void revisionValidationReturnsModeledErrorsWithoutRegisteringInvalidMetadata() {
        String app = "wire-invalid-revisions";
        call("CreateApplication", Map.of("applicationName", app)).statusCode(200);
        try {
            for (String action : List.of("RegisterApplicationRevision", "GetApplicationRevision")) {
                call(action, Map.of("applicationName", app)).statusCode(400)
                        .body("__type", equalTo("RevisionRequiredException"));
                for (Object revision : List.of(Map.of(), "not-an-object",
                        Map.of("revisionType", "S3", "s3Location", Map.of("bucket", "bucket")))) {
                    call(action, Map.of("applicationName", app, "revision", revision)).statusCode(400)
                            .body("__type", equalTo("InvalidRevisionException"));
                }
            }
            call("BatchGetApplicationRevisions", Map.of("applicationName", app, "revisions", List.of()))
                    .statusCode(400).body("__type", equalTo("RevisionRequiredException"));
            call("ListApplicationRevisions", Map.of("applicationName", app, "nextToken", "invalid"))
                    .statusCode(400).body("__type", equalTo("InvalidNextTokenException"));
            call("ListApplicationRevisions", Map.of("applicationName", app, "s3KeyPrefix", "bundle/"))
                    .statusCode(400).body("__type", equalTo("BucketNameFilterRequiredException"));
            call("ListApplicationRevisions", Map.of("applicationName", app, "sortBy", "invalid"))
                    .statusCode(400).body("__type", equalTo("InvalidSortByException"));
            call("ListApplicationRevisions", Map.of("applicationName", app, "sortOrder", "invalid"))
                    .statusCode(400).body("__type", equalTo("InvalidSortOrderException"));
            call("ListApplicationRevisions", Map.of("applicationName", app, "deployed", "invalid"))
                    .statusCode(400).body("__type", equalTo("InvalidDeployedStateFilterException"));
            call("ListApplicationRevisions", Map.of("applicationName", app)).statusCode(200).body("revisions", empty());
            call("ListApplicationRevisions", Map.of("applicationName", app + "-missing"))
                    .statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));
        } finally {
            call("DeleteApplication", Map.of("applicationName", app)).statusCode(200);
        }
    }

    private ValidatableResponse call(String action, Map<String, Object> body) {
        return call(action, body, "us-east-1", "000000000000");
    }

    private ValidatableResponse call(String action, Map<String, Object> body, String region, String account) {
        return given().header("X-Amz-Target", "CodeDeploy_20141006." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260921/" + region + "/codedeploy/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType(CONTENT_TYPE).body(body).when().post("/").then();
    }

    @Test
    @Order(18)
    void cannotDeleteBuiltInDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "CodeDeployDefault.AllAtOnce"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidDeploymentConfigNameException"));
    }
}
