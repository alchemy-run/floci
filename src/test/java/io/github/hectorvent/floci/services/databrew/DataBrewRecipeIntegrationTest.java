package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies DataBrew restJson1 recipe create/publish/update/delete used by Alchemy. */
@QuarkusTest
class DataBrewRecipeIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String STEP = """
            {
              "Action": {
                "Operation": "UPPER_CASE",
                "Parameters": { "sourceColumn": "name" }
              }
            }
            """;
    private static final String UPDATED_STEPS = """
            [
              {
                "Action": {
                  "Operation": "UPPER_CASE",
                  "Parameters": { "sourceColumn": "name" }
                }
              },
              {
                "Action": {
                  "Operation": "REMOVE_VALUES",
                  "Parameters": { "sourceColumn": "email" }
                },
                "ConditionExpressions": [
                  { "Condition": "IS_MISSING", "TargetColumn": "email" }
                ]
              }
            ]
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRecipeOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/recipes/missing-recipe?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPublishUpdateAndDeleteRecipeLifecycle() {
        String authorization = auth("000000000502", EAST);
        String name = "normalize-names";
        String createBody = """
                {
                  "Name": "%s",
                  "Description": "normalize names",
                  "Steps": [%s],
                  "Tags": { "Environment": "test", "alchemy::id": "recipe-1" }
                }
                """.formatted(name, STEP);

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body(createBody)
                .when()
                .post("/recipes")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipes/" + name + "?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Description", equalTo("normalize names"))
                .body("RecipeVersion", equalTo("LATEST_WORKING"))
                .body("Steps", hasSize(1))
                .body("Steps[0].Action.Operation", equalTo("UPPER_CASE"))
                .body("Tags.Environment", equalTo("test"))
                .body("Tags['alchemy::id']", equalTo("recipe-1"))
                .body("ResourceArn", notNullValue());

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{\"Description\":\"normalize names\"}")
                .when()
                .post("/recipes/" + name + "/publishRecipe")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipeVersions?name=" + name)
                .then()
                .statusCode(200)
                .body("Recipes", hasSize(1))
                .body("Recipes[0].RecipeVersion", equalTo("1.0"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipes?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(200)
                .body("Recipes.find { it.Name == '" + name + "' }.RecipeVersion", equalTo("LATEST_WORKING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipes")
                .then()
                .statusCode(200)
                .body("Recipes.find { it.Name == '" + name + "' }.RecipeVersion", equalTo("1.0"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("""
                        {
                          "Description": "normalize names",
                          "Steps": %s
                        }
                        """.formatted(UPDATED_STEPS))
                .when()
                .put("/recipes/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{\"Description\":\"normalize names\"}")
                .when()
                .post("/recipes/" + name + "/publishRecipe")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipes/" + name + "?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(200)
                .body("Steps", hasSize(2))
                .body("Steps[1].Action.Operation", equalTo("REMOVE_VALUES"))
                .body("Steps[1].ConditionExpressions[0].Condition", equalTo("IS_MISSING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipeVersions?name=" + name)
                .then()
                .statusCode(200)
                .body("Recipes", hasSize(2))
                .body("Recipes[1].RecipeVersion", equalTo("2.0"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{\"RecipeVersions\":[\"1.0\",\"2.0\"]}")
                .when()
                .post("/recipes/" + name + "/batchDeleteRecipeVersion")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/recipes/" + name + "/recipeVersion/LATEST_WORKING")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/recipes/" + name + "?recipeVersion=LATEST_WORKING")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void creatingADuplicateRecipeFailsWithConflictException() {
        String authorization = auth("000000000503", EAST);
        String body = """
                {
                  "Name": "duplicate",
                  "Steps": [%s]
                }
                """.formatted(STEP);
        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/recipes")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/recipes")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
