package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;


/** Verifies DataBrew restJson1 recipe, dataset, and job APIs used by Alchemy Job tests. */
@QuarkusTest
class DataBrewJobIntegrationTest {

    private static final String AUTH = auth("000000000401", "us-east-1");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRecipeOnAMissingNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/recipes/missing-recipe?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPublishDescribeAndDeleteRecipe() {
        String name = "floci-recipe-job";
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Steps": [
                            {"Action": {"Operation": "UPPER_CASE", "Parameters": {"sourceColumn": "name"}}}
                          ],
                          "Tags": {"Environment": "test"}
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
                .body("Steps[0].Action.Operation", equalTo("UPPER_CASE"))
                .body("Tags.Environment", equalTo("test"))
                .body("ResourceArn", notNullValue());

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
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
                .body("Recipes[0].RecipeVersion", equalTo("1.0"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"RecipeVersions\":[\"1.0\"]}")
                .when()
                .post("/recipes/" + name + "/batchDeleteRecipeVersion")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/recipes/" + name + "/recipeVersion/LATEST_WORKING")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/recipes/" + name + "?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateAndDeleteProfileAndRecipeJobs() {
        String dataset = "floci-job-dataset";
        String recipe = "floci-job-recipe";
        String profile = "floci-profile-job";
        String recipeJob = "floci-recipe-job-def";

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Format": "CSV",
                          "FormatOptions": {"Csv": {"Delimiter": ",", "HeaderRow": true}},
                          "Input": {"S3InputDefinition": {"Bucket": "job-bucket", "Key": "raw/data.csv"}}
                        }
                        """.formatted(dataset))
                .when()
                .post("/datasets")
                .then()
                .statusCode(200)
                .body("Name", equalTo(dataset));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Steps": [
                            {"Action": {"Operation": "UPPER_CASE", "Parameters": {"sourceColumn": "name"}}}
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
                .body("{}")
                .when()
                .post("/recipes/" + recipe + "/publishRecipe")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "DatasetName": "%s",
                          "RoleArn": "arn:aws:iam::000000000401:role/DataBrewJobRole",
                          "OutputLocation": {"Bucket": "job-bucket", "Key": "profiles/"},
                          "JobSample": {"Mode": "CUSTOM_ROWS", "Size": 100},
                          "MaxCapacity": 2,
                          "Timeout": 60,
                          "Tags": {"Environment": "test"}
                        }
                        """.formatted(profile, dataset))
                .when()
                .post("/profileJobs")
                .then()
                .statusCode(200)
                .body("Name", equalTo(profile));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "DatasetName": "%s",
                          "RecipeReference": {"Name": "%s", "RecipeVersion": "LATEST_PUBLISHED"},
                          "RoleArn": "arn:aws:iam::000000000401:role/DataBrewJobRole",
                          "Outputs": [
                            {"Location": {"Bucket": "job-bucket", "Key": "curated/"}, "Format": "CSV", "Overwrite": true}
                          ],
                          "MaxCapacity": 2,
                          "Tags": {"Environment": "test"}
                        }
                        """.formatted(recipeJob, dataset, recipe))
                .when()
                .post("/recipeJobs")
                .then()
                .statusCode(200)
                .body("Name", equalTo(recipeJob));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + profile)
                .then()
                .statusCode(200)
                .body("Type", equalTo("PROFILE"))
                .body("DatasetName", equalTo(dataset))
                .body("JobSample.Size", equalTo(100))
                .body("MaxCapacity", equalTo(2))
                .body("Timeout", equalTo(60))
                .body("Tags.Environment", equalTo("test"))
                .body("ResourceArn", notNullValue());

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + recipeJob)
                .then()
                .statusCode(200)
                .body("Type", equalTo("RECIPE"))
                .body("RecipeReference.Name", equalTo(recipe))
                .body("Outputs[0].Location.Bucket", equalTo("job-bucket"))
                .body("Outputs[0].Overwrite", equalTo(true));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "RoleArn": "arn:aws:iam::000000000401:role/DataBrewJobRole",
                          "OutputLocation": {"Bucket": "job-bucket", "Key": "profiles/"},
                          "JobSample": {"Mode": "CUSTOM_ROWS", "Size": 100},
                          "MaxCapacity": 3,
                          "Timeout": 60
                        }
                        """)
                .when()
                .put("/profileJobs/" + profile)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "RoleArn": "arn:aws:iam::000000000401:role/DataBrewJobRole",
                          "Outputs": [
                            {"Location": {"Bucket": "job-bucket", "Key": "curated/"}, "Format": "CSV", "Overwrite": true}
                          ],
                          "MaxCapacity": 3
                        }
                        """)
                .when()
                .put("/recipeJobs/" + recipeJob)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + profile)
                .then()
                .statusCode(200)
                .body("MaxCapacity", equalTo(3));
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + recipeJob)
                .then()
                .statusCode(200)
                .body("MaxCapacity", equalTo(3));

        Response described = given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + profile)
                .then()
                .statusCode(200)
                .extract()
                .response();
        String arn = described.path("ResourceArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/jobs/" + profile)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/jobs/" + recipeJob)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/jobs/" + profile)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/datasets/" + dataset)
                .then()
                .statusCode(200);
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"RecipeVersions\":[\"1.0\"]}")
                .when()
                .post("/recipes/" + recipe + "/batchDeleteRecipeVersion")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/recipes/" + recipe + "/recipeVersion/LATEST_WORKING")
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
