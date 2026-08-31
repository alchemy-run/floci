package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Glue DataBrew binding-plane operations used by Alchemy
 * {@code test/AWS/DataBrew/Bindings.test.ts}: recipe describe/publish, job runs,
 * and project sessions.
 */
@QuarkusTest
class DataBrewBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth("000000000507", EAST);

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private void seedCsv(String bucket, String key) {
        s3Service.createBucket(bucket, EAST);
        s3Service.putObject(bucket, key, "id,name\n1,alice\n2,bob\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", Map.of());
    }

    @Test
    void describeRecipeOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/recipes/missing-recipe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPublishListAndDescribeRecipeLifecycle() {
        String name = "bindings-recipe-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Description": "working",
                          "Steps": [
                            {
                              "Action": {
                                "Operation": "UPPER_CASE",
                                "Parameters": { "sourceColumn": "name" }
                              }
                            }
                          ],
                          "Tags": { "Environment": "test" }
                        }
                        """.formatted(name))
                .when()
                .post("/recipes")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/recipes/" + name + "?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("RecipeVersion", equalTo("LATEST_WORKING"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Description\":\"published by bindings fixture\"}")
                .when()
                .post("/recipes/" + name + "/publishRecipe")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/recipeVersions?name=" + name)
                .then()
                .statusCode(200)
                .body("Recipes.RecipeVersion", hasItem("1.0"));
    }

    @Test
    void startListDescribeAndStopJobRun() {
        String dataset = "bindings-ds-" + UUID.randomUUID().toString().substring(0, 8);
        String job = "bindings-job-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Format": "CSV",
                          "Input": {
                            "S3InputDefinition": { "Bucket": "raw-data", "Key": "raw/data.csv" }
                          }
                        }
                        """.formatted(dataset))
                .when()
                .post("/datasets")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "DatasetName": "%s",
                          "RoleArn": "arn:aws:iam::000000000507:role/DataBrewRole",
                          "OutputLocation": { "Bucket": "raw-data", "Key": "profiles/" },
                          "JobSample": { "Mode": "CUSTOM_ROWS", "Size": 100 },
                          "MaxCapacity": 2,
                          "Timeout": 30
                        }
                        """.formatted(job, dataset))
                .when()
                .post("/profileJobs")
                .then()
                .statusCode(200)
                .body("Name", equalTo(job));

        String runId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .when()
                .post("/jobs/" + job + "/startJobRun")
                .then()
                .statusCode(200)
                .body("RunId", notNullValue())
                .extract()
                .path("RunId");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + job + "/jobRuns")
                .then()
                .statusCode(200)
                .body("JobRuns.RunId", hasItem(runId));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + job + "/jobRun/" + runId)
                .then()
                .statusCode(200)
                .body("State", equalTo("RUNNING"))
                .body("JobName", equalTo(job));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .when()
                .post("/jobs/" + job + "/jobRun/" + runId + "/stopJobRun")
                .then()
                .statusCode(200)
                .body("RunId", equalTo(runId));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + job + "/jobRun/" + runId)
                .then()
                .statusCode(200)
                .body("State", equalTo("STOPPED"));
    }

    @Test
    void startProjectSessionAndSendPreviewAction() {
        String bucket = "databrew-session-" + UUID.randomUUID().toString().substring(0, 8);
        seedCsv(bucket, "raw/data.csv");
        String dataset = "session-ds-" + UUID.randomUUID().toString().substring(0, 8);
        String recipe = "session-recipe-" + UUID.randomUUID().toString().substring(0, 8);
        String project = "session-project-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Format": "CSV",
                          "Input": {
                            "S3InputDefinition": { "Bucket": "%s", "Key": "raw/data.csv" }
                          }
                        }
                        """.formatted(dataset, bucket))
                .when()
                .post("/datasets")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Steps": [
                            {
                              "Action": {
                                "Operation": "UPPER_CASE",
                                "Parameters": { "sourceColumn": "name" }
                              }
                            }
                          ]
                        }
                        """.formatted(recipe))
                .when()
                .post("/recipes")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "DatasetName": "%s",
                          "RecipeName": "%s",
                          "RoleArn": "arn:aws:iam::000000000507:role/DataBrewRole",
                          "Sample": { "Type": "FIRST_N", "Size": 100 }
                        }
                        """.formatted(project, dataset, recipe))
                .when()
                .post("/projects")
                .then()
                .statusCode(200);

        String sessionId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"AssumeControl\":true}")
                .when()
                .put("/projects/" + project + "/startProjectSession")
                .then()
                .statusCode(200)
                .body("Name", equalTo(project))
                .body("ClientSessionId", notNullValue())
                .extract()
                .path("ClientSessionId");

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Preview": true,
                          "ClientSessionId": "%s",
                          "RecipeStep": {
                            "Action": {
                              "Operation": "UPPER_CASE",
                              "Parameters": { "sourceColumn": "name" }
                            }
                          }
                        }
                        """.formatted(sessionId))
                .when()
                .put("/projects/" + project + "/sendProjectSessionAction")
                .then()
                .statusCode(200)
                .body("Name", equalTo(project))
                .body("ActionId", notNullValue());
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
