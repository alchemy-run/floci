package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
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
class CodeBuildIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "description": "Integration test project",
                    "source": {
                        "type": "S3",
                        "location": "my-bucket/source.zip"
                    },
                    "artifacts": {
                        "type": "NO_ARTIFACTS"
                    },
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/standard:7.0",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.name", equalTo("my-build-project"))
            .body("project.description", equalTo("Integration test project"))
            .body("project.arn", containsString("arn:aws:codebuild:"))
            .body("project.arn", containsString(":project/my-build-project"))
            .body("project.serviceRole", equalTo("arn:aws:iam::000000000000:role/codebuild-role"))
            .body("project.timeoutInMinutes", equalTo(60))
            .body("project.projectVisibility", equalTo("PRIVATE"));
    }

    @Test
    @Order(2)
    void createDuplicateProjectFails() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "source": {"type": "NO_SOURCE"},
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/standard:7.0",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(3)
    void batchGetProjects() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetProjects")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "names": [
                        "arn:aws:codebuild:us-east-1:000000000000:project/my-build-project",
                        "nonexistent-project"
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("projects", hasSize(1))
            .body("projects[0].name", equalTo("my-build-project"))
            .body("projectsNotFound", hasSize(1))
            .body("projectsNotFound[0]", equalTo("nonexistent-project"));
    }

    @Test
    @Order(4)
    void listProjects() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListProjects")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("projects", hasItem("my-build-project"));
    }

    @Test
    @Order(5)
    void updateProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.UpdateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "description": "Updated description",
                    "timeoutInMinutes": 120
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.name", equalTo("my-build-project"))
            .body("project.description", equalTo("Updated description"))
            .body("project.timeoutInMinutes", equalTo(120));
    }

    @Test
    @Order(6)
    void createReportGroup() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateReportGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-report-group",
                    "type": "TEST",
                    "exportConfig": {
                        "exportConfigType": "NO_EXPORT"
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroup.name", equalTo("my-report-group"))
            .body("reportGroup.type", equalTo("TEST"))
            .body("reportGroup.arn", containsString(":report-group/my-report-group"))
            .body("reportGroup.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(7)
    void listReportGroups() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroups", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(8)
    void batchGetReportGroups() {
        // Fetch ARN first
        String arn = given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("reportGroups[0]");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"reportGroupArns\": [\"" + arn + "\", \"arn:aws:codebuild:us-east-1:000000000000:report-group/nonexistent\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroups", hasSize(1))
            .body("reportGroupsNotFound", hasSize(1));
    }

    @Test
    @Order(9)
    void importAndListSourceCredentials() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ImportSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "token": "ghp_test_token",
                    "serverType": "GITHUB",
                    "authType": "OAUTH"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("arn", containsString(":token/github-"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("sourceCredentialsInfos", hasSize(greaterThanOrEqualTo(1)))
            .body("sourceCredentialsInfos[0].serverType", equalTo("GITHUB"))
            .body("sourceCredentialsInfos[0].authType", equalTo("OAUTH"));
    }

    @Test
    @Order(10)
    void listCuratedEnvironmentImages() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListCuratedEnvironmentImages")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("platforms", hasSize(greaterThanOrEqualTo(1)))
            .body("platforms[0].platform", notNullValue());
    }

    @Test
    @Order(11)
    void deleteSourceCredentials() {
        String arn = given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("sourceCredentialsInfos[0].arn");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{\"arn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn));
    }

    @Test
    @Order(12)
    void deleteProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {"name": "my-build-project"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void deleteNonexistentProjectIsIdempotent() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {"name": "my-build-project"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void batchAndReportReadWireContracts() {
        String name = "http-codebuild-bindings";
        given().header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
                .contentType(CONTENT_TYPE).body(Map.of("name", name,
                        "source", Map.of("type", "NO_SOURCE"),
                        "artifacts", Map.of("type", "NO_ARTIFACTS"),
                        "environment", Map.of("type", "LINUX_CONTAINER", "image", "aws/codebuild/standard:7.0",
                                "computeType", "BUILD_GENERAL1_SMALL"),
                        "serviceRole", "arn:aws:iam::000000000000:role/codebuild-role"))
                .post("/").then().statusCode(200);
        String groupArn = given().header("X-Amz-Target", "CodeBuild_20161006.CreateReportGroup")
                .contentType(CONTENT_TYPE).body(Map.of("name", name, "type", "TEST",
                        "exportConfig", Map.of("exportConfigType", "NO_EXPORT")))
                .post("/").then().statusCode(200).extract().path("reportGroup.arn");
        String missingBatch = name + ":00000000-0000-0000-0000-000000000000";
        String missingReport = groupArn.replace(":report-group/", ":report/") + ":missing";
        try {
            given().header("X-Amz-Target", "CodeBuild_20161006.StartBuildBatch")
                    .contentType(CONTENT_TYPE).body(Map.of("projectName", name))
                    .post("/").then().statusCode(400).body("__type", containsString("InvalidInputException"));
            given().header("X-Amz-Target", "CodeBuild_20161006.ListBuildBatchesForProject")
                    .contentType(CONTENT_TYPE).body(Map.of("projectName", name))
                    .post("/").then().statusCode(200).body("ids", empty());
            given().header("X-Amz-Target", "CodeBuild_20161006.BatchGetBuildBatches")
                    .contentType(CONTENT_TYPE).body(Map.of("ids", List.of(missingBatch)))
                    .post("/").then().statusCode(200).body("buildBatches", empty())
                    .body("buildBatchesNotFound", contains(missingBatch));
            given().header("X-Amz-Target", "CodeBuild_20161006.ListReportsForReportGroup")
                    .contentType(CONTENT_TYPE).body(Map.of("reportGroupArn", groupArn))
                    .post("/").then().statusCode(200).body("reports", empty());
            given().header("X-Amz-Target", "CodeBuild_20161006.BatchGetReports")
                    .contentType(CONTENT_TYPE).body(Map.of("reportArns", List.of(missingReport)))
                    .post("/").then().statusCode(200).body("reports", empty())
                    .body("reportsNotFound", contains(missingReport));
            given().header("X-Amz-Target", "CodeBuild_20161006.DescribeTestCases")
                    .contentType(CONTENT_TYPE).body(Map.of("reportArn", missingReport))
                    .post("/").then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
            given().header("X-Amz-Target", "CodeBuild_20161006.DescribeCodeCoverages")
                    .contentType(CONTENT_TYPE).body(Map.of("reportArn", missingReport))
                    .post("/").then().statusCode(400).body("__type", containsString("InvalidInputException"));
            given().header("X-Amz-Target", "CodeBuild_20161006.GetReportGroupTrend")
                    .contentType(CONTENT_TYPE).body(Map.of("reportGroupArn", groupArn, "trendField", "DURATION"))
                    .post("/").then().statusCode(200).body("rawData", empty()).body("stats", nullValue());
            given().header("X-Amz-Target", "CodeBuild_20161006.DeleteReport")
                    .contentType(CONTENT_TYPE).body(Map.of("arn", missingReport))
                    .post("/").then().statusCode(200);
        } finally {
            given().header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
                    .contentType(CONTENT_TYPE).body(Map.of("name", name)).post("/").then().statusCode(200);
            given().header("X-Amz-Target", "CodeBuild_20161006.DeleteReportGroup")
                    .contentType(CONTENT_TYPE).body(Map.of("arn", groupArn)).post("/").then().statusCode(200);
        }
    }

    @Test
    void resourcePolicyWireLifecycleAndOwnerValidation() {
        String projectName = "http-policy-project";
        String arn = given()
                .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
                .contentType(CONTENT_TYPE)
                .body(Map.of("name", projectName,
                        "source", Map.of("type", "NO_SOURCE"),
                        "artifacts", Map.of("type", "NO_ARTIFACTS"),
                        "environment", Map.of("type", "LINUX_CONTAINER", "image", "aws/codebuild/standard:7.0",
                                "computeType", "BUILD_GENERAL1_SMALL"),
                        "serviceRole", "arn:aws:iam::000000000000:role/codebuild-role"))
                .post("/").then().statusCode(200).extract().path("project.arn");
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":["codebuild:BatchGetProjects"],"Resource":"%s"}]}
                """.formatted(arn);
        try {
            given().header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn))
                    .post("/").then().statusCode(200).body("policy", nullValue());
            given().header("X-Amz-Target", "CodeBuild_20161006.PutResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn, "policy", policy))
                    .post("/").then().statusCode(200).body("resourceArn", equalTo(arn));
            given().header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn))
                    .post("/").then().statusCode(200).body("policy", equalTo(policy));

            String foreignArn = arn.replace("000000000000", "111111111111");
            for (String action : List.of("GetResourcePolicy", "PutResourcePolicy", "DeleteResourcePolicy")) {
                given().header("X-Amz-Target", "CodeBuild_20161006." + action)
                        .contentType(CONTENT_TYPE).body(Map.of("resourceArn", foreignArn, "policy", policy))
                        .post("/").then().statusCode(400)
                        .body("__type", containsString("InvalidInputException"));
            }
            given().header("X-Amz-Target", "CodeBuild_20161006.PutResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn, "policy", "not-json"))
                    .post("/").then().statusCode(400)
                    .body("__type", containsString("InvalidInputException"));
            given().header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn))
                    .post("/").then().statusCode(200).body("policy", equalTo(policy));
            for (int i = 0; i < 2; i++) {
                given().header("X-Amz-Target", "CodeBuild_20161006.DeleteResourcePolicy")
                        .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn))
                        .post("/").then().statusCode(200);
            }
            given().header("X-Amz-Target", "CodeBuild_20161006.GetResourcePolicy")
                    .contentType(CONTENT_TYPE).body(Map.of("resourceArn", arn))
                    .post("/").then().statusCode(200).body("policy", nullValue());
        } finally {
            given().header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
                    .contentType(CONTENT_TYPE).body(Map.of("name", projectName))
                    .post("/").then().statusCode(200);
        }
    }
}
