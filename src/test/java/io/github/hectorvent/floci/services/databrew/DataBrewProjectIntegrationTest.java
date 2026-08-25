package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glue DataBrew restJson1 — dataset, recipe, and project lifecycle used by
 * alchemy {@code DataBrew.Project} (create/describe/update sample/tags/delete).
 */
@QuarkusTest
class DataBrewProjectIntegrationTest {

    private static final String EAST = "us-east-1";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeProjectOnANonexistentProjectFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/projects/missing-project")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeRecipeOnANonexistentRecipeFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("recipeVersion", "LATEST_WORKING")
                .when()
                .get("/recipes/missing-recipe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createProjectWithoutTheDatasetSourceObjectFailsWithValidationException() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "databrew-it-empty-" + suffix;
        s3Service.createBucket(bucket, EAST);

        String authorization = auth(EAST);
        String dataset = "empty-source-" + suffix;
        String recipe = "empty-recipe-" + suffix;

        createDataset(authorization, dataset, bucket, "raw/data.csv");
        createRecipe(authorization, recipe);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(projectBody("empty-project-" + suffix, dataset, recipe, null))
                .when()
                .post("/projects")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createDescribeUpdateSampleTagAndDeleteProjectLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "databrew-it-" + suffix;
        String dataset = "source-" + suffix;
        String recipe = "transform-" + suffix;
        String project = "explore-" + suffix;
        s3Service.createBucket(bucket, EAST);
        s3Service.putObject(bucket, "raw/data.csv", "id,name\n1,alice\n2,bob\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", Map.of());

        String authorization = auth(EAST);
        createDataset(authorization, dataset, bucket, "raw/data.csv");
        createRecipe(authorization, recipe);

        given()
                .header("Authorization", authorization)
                .queryParam("recipeVersion", "LATEST_WORKING")
                .when()
                .get("/recipes/" + recipe)
                .then()
                .statusCode(200)
                .body("Name", equalTo(recipe))
                .body("RecipeVersion", equalTo("LATEST_WORKING"))
                .body("Steps[0].Action.Operation", equalTo("UPPER_CASE"));

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(projectBody(project, dataset, recipe, null))
                .when()
                .post("/projects")
                .then()
                .statusCode(200)
                .body("Name", equalTo(project))
                .extract().path("Name");
        assertTrue(arn.equals(project));

        String resourceArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/projects/" + project)
                .then()
                .statusCode(200)
                .body("Name", equalTo(project))
                .body("DatasetName", equalTo(dataset))
                .body("RecipeName", equalTo(recipe))
                .body("ResourceArn", notNullValue())
                .body("Sample.Type", equalTo("FIRST_N"))
                .body("Sample.Size", equalTo(500))
                .body("Tags.Environment", equalTo("test"))
                .extract().path("ResourceArn");
        assertTrue(resourceArn.contains(":project/" + project));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(resourceArn))
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"Team\":\"platform\"}}")
                .when()
                .post("/tags/" + encode(resourceArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(resourceArn))
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"))
                .body("Tags.Team", equalTo("platform"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "RoleArn":"arn:aws:iam::000000000000:role/DataBrewProjectRole",
                          "Sample":{"Type":"RANDOM","Size":250}
                        }
                        """)
                .when()
                .put("/projects/" + project)
                .then()
                .statusCode(200)
                .body("Name", equalTo(project))
                .body("LastModifiedDate", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/projects/" + project)
                .then()
                .statusCode(200)
                .body("Sample.Type", equalTo("RANDOM"))
                .body("Sample.Size", equalTo(250));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/projects/" + project)
                .then()
                .statusCode(200)
                .body("Name", equalTo(project));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/projects/" + project)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/recipes/" + recipe + "/recipeVersion/LATEST_WORKING")
                .then()
                .statusCode(200)
                .body("Name", equalTo(recipe));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/datasets/" + dataset)
                .then()
                .statusCode(200)
                .body("Name", equalTo(dataset));
    }

    private void createDataset(String authorization, String name, String bucket, String key) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "Format":"CSV",
                          "FormatOptions":{"Csv":{"Delimiter":",","HeaderRow":true}},
                          "Input":{"S3InputDefinition":{"Bucket":"%s","Key":"%s"}},
                          "Tags":{"Environment":"test"}
                        }
                        """.formatted(name, bucket, key))
                .when()
                .post("/datasets")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));
    }

    private void createRecipe(String authorization, String name) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "Steps":[
                            {"Action":{"Operation":"UPPER_CASE","Parameters":{"sourceColumn":"name"}}}
                          ],
                          "Tags":{"Environment":"test"}
                        }
                        """.formatted(name))
                .when()
                .post("/recipes")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));
    }

    private static String projectBody(String name, String dataset, String recipe, String sample) {
        String sampleJson = sample == null
                ? ""
                : ",\"Sample\":" + sample;
        return """
                {
                  "Name":"%s",
                  "DatasetName":"%s",
                  "RecipeName":"%s",
                  "RoleArn":"arn:aws:iam::000000000000:role/DataBrewProjectRole",
                  "Tags":{"Environment":"test"}%s
                }
                """.formatted(name, dataset, recipe, sampleJson);
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/databrew/aws4_request";
    }
}
