package io.github.hectorvent.floci.services.imagebuilder;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Image Builder restJson1 Get/Create/Update/Delete and not-found errors. */
@QuarkusTest
class ImageBuilderIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String COMPONENT_DATA = """
            name: alchemy-test-component
            description: no-op
            schemaVersion: 1.0
            phases:
              - name: build
                steps:
                  - name: hello
                    action: ExecuteBash
                    inputs:
                      commands:
                        - echo hello
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getComponentOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:imagebuilder:" + EAST
                + ":000000000000:component/alchemy-nonexistent-probe/1.0.0/1";
        given()
                .header("Authorization", auth(EAST))
                .queryParam("componentBuildVersionArn", arn)
                .when()
                .get("/GetComponent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getImageAndDeleteImageOnANonexistentArnFailWithResourceNotFoundException() {
        String arn = "arn:aws:imagebuilder:" + EAST
                + ":000000000000:image/alchemy-nonexistent-probe/1.0.0/1";
        given()
                .header("Authorization", auth(EAST))
                .queryParam("imageBuildVersionArn", arn)
                .when()
                .get("/GetImage")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", auth(EAST))
                .queryParam("imageBuildVersionArn", arn)
                .when()
                .delete("/DeleteImage")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateReplaceAndDeleteConfigLifecycle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String componentName = "ib-comp-" + suffix;
        String recipeName = "ib-recipe-" + suffix;
        String infraName = "ib-infra-" + suffix;
        String distName = "ib-dist-" + suffix;
        String pipelineName = "ib-pipe-" + suffix;
        String parentImage = "arn:aws:imagebuilder:" + EAST + ":aws:image/amazon-linux-2023-x86/x.x.x";
        String authorization = auth(EAST);

        String componentArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.0",
                          "platform":"Linux",
                          "data":%s,
                          "tags":{"fixture":"imagebuilder"},
                          "clientToken":"%s"
                        }
                        """.formatted(componentName, jsonString(COMPONENT_DATA), UUID.randomUUID()))
                .when()
                .put("/CreateComponent")
                .then()
                .statusCode(200)
                .body("componentBuildVersionArn", notNullValue())
                .extract().path("componentBuildVersionArn");

        given()
                .header("Authorization", authorization)
                .queryParam("componentBuildVersionArn", componentArn)
                .when()
                .get("/GetComponent")
                .then()
                .statusCode(200)
                .body("component.arn", equalTo(componentArn))
                .body("component.platform", equalTo("Linux"))
                .body("component.version", equalTo("1.0.0"));

        String recipeArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.0",
                          "parentImage":"%s",
                          "components":[{"componentArn":"%s"}],
                          "tags":{"fixture":"imagebuilder"},
                          "clientToken":"%s"
                        }
                        """.formatted(recipeName, parentImage, componentArn, UUID.randomUUID()))
                .when()
                .put("/CreateImageRecipe")
                .then()
                .statusCode(200)
                .body("imageRecipeArn", notNullValue())
                .extract().path("imageRecipeArn");

        given()
                .header("Authorization", authorization)
                .queryParam("imageRecipeArn", recipeArn)
                .when()
                .get("/GetImageRecipe")
                .then()
                .statusCode(200)
                .body("imageRecipe.arn", equalTo(recipeArn))
                .body("imageRecipe.parentImage", equalTo(parentImage))
                .body("imageRecipe.components[0].componentArn", equalTo(componentArn));

        String infraArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "instanceProfileName":"BuilderProfile",
                          "instanceTypes":["t3.micro"],
                          "terminateInstanceOnFailure":true,
                          "tags":{"fixture":"imagebuilder"},
                          "clientToken":"%s"
                        }
                        """.formatted(infraName, UUID.randomUUID()))
                .when()
                .put("/CreateInfrastructureConfiguration")
                .then()
                .statusCode(200)
                .extract().path("infrastructureConfigurationArn");

        given()
                .header("Authorization", authorization)
                .queryParam("infrastructureConfigurationArn", infraArn)
                .when()
                .get("/GetInfrastructureConfiguration")
                .then()
                .statusCode(200)
                .body("infrastructureConfiguration.instanceProfileName", equalTo("BuilderProfile"))
                .body("infrastructureConfiguration.instanceTypes[0]", equalTo("t3.micro"));

        String distArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy imagebuilder test",
                          "distributions":[{
                            "region":"%s",
                            "amiDistributionConfiguration":{
                              "name":"alchemy-imagebuilder-test-{{ imagebuilder:buildDate }}",
                              "amiTags":{"fixture":"imagebuilder"}
                            }
                          }],
                          "tags":{"fixture":"imagebuilder"},
                          "clientToken":"%s"
                        }
                        """.formatted(distName, EAST, UUID.randomUUID()))
                .when()
                .put("/CreateDistributionConfiguration")
                .then()
                .statusCode(200)
                .extract().path("distributionConfigurationArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "distributionConfigurationArn":"%s",
                          "description":"alchemy imagebuilder test (updated)",
                          "distributions":[{
                            "region":"%s",
                            "amiDistributionConfiguration":{
                              "name":"alchemy-imagebuilder-test-{{ imagebuilder:buildDate }}"
                            }
                          }],
                          "clientToken":"%s"
                        }
                        """.formatted(distArn, EAST, UUID.randomUUID()))
                .when()
                .put("/UpdateDistributionConfiguration")
                .then()
                .statusCode(200)
                .body("distributionConfigurationArn", equalTo(distArn));

        given()
                .header("Authorization", authorization)
                .queryParam("distributionConfigurationArn", distArn)
                .when()
                .get("/GetDistributionConfiguration")
                .then()
                .statusCode(200)
                .body("distributionConfiguration.description", equalTo("alchemy imagebuilder test (updated)"));

        String pipelineArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy imagebuilder pipeline",
                          "imageRecipeArn":"%s",
                          "infrastructureConfigurationArn":"%s",
                          "distributionConfigurationArn":"%s",
                          "status":"DISABLED",
                          "imageTestsConfiguration":{"imageTestsEnabled":false,"timeoutMinutes":120},
                          "tags":{"fixture":"imagebuilder","alchemy::id":"Pipeline"},
                          "clientToken":"%s"
                        }
                        """.formatted(pipelineName, recipeArn, infraArn, distArn, UUID.randomUUID()))
                .when()
                .put("/CreateImagePipeline")
                .then()
                .statusCode(200)
                .extract().path("imagePipelineArn");

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .get("/GetImagePipeline")
                .then()
                .statusCode(200)
                .body("imagePipeline.imageRecipeArn", equalTo(recipeArn))
                .body("imagePipeline.infrastructureConfigurationArn", equalTo(infraArn))
                .body("imagePipeline.status", equalTo("DISABLED"))
                .body("imagePipeline.imageTestsConfiguration.timeoutMinutes", equalTo(120))
                .body("imagePipeline.tags.fixture", equalTo("imagebuilder"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(pipelineArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("imagebuilder"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "imagePipelineArn":"%s",
                          "description":"alchemy imagebuilder pipeline (updated)",
                          "imageRecipeArn":"%s",
                          "infrastructureConfigurationArn":"%s",
                          "distributionConfigurationArn":"%s",
                          "status":"DISABLED",
                          "imageTestsConfiguration":{"imageTestsEnabled":false,"timeoutMinutes":120},
                          "clientToken":"%s"
                        }
                        """.formatted(pipelineArn, recipeArn, infraArn, distArn, UUID.randomUUID()))
                .when()
                .put("/UpdateImagePipeline")
                .then()
                .statusCode(200)
                .body("imagePipelineArn", equalTo(pipelineArn));

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .get("/GetImagePipeline")
                .then()
                .statusCode(200)
                .body("imagePipeline.description", equalTo("alchemy imagebuilder pipeline (updated)"));

        String replacedComponentArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.1",
                          "platform":"Linux",
                          "data":%s,
                          "clientToken":"%s"
                        }
                        """.formatted(componentName, jsonString(COMPONENT_DATA), UUID.randomUUID()))
                .when()
                .put("/CreateComponent")
                .then()
                .statusCode(200)
                .body("componentBuildVersionArn", not(equalTo(componentArn)))
                .extract().path("componentBuildVersionArn");

        String replacedRecipeArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.1",
                          "parentImage":"%s",
                          "components":[{"componentArn":"%s"}],
                          "clientToken":"%s"
                        }
                        """.formatted(recipeName, parentImage, replacedComponentArn, UUID.randomUUID()))
                .when()
                .put("/CreateImageRecipe")
                .then()
                .statusCode(200)
                .body("imageRecipeArn", not(equalTo(recipeArn)))
                .extract().path("imageRecipeArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "imagePipelineArn":"%s",
                          "description":"alchemy imagebuilder pipeline (updated)",
                          "imageRecipeArn":"%s",
                          "infrastructureConfigurationArn":"%s",
                          "distributionConfigurationArn":"%s",
                          "status":"DISABLED",
                          "imageTestsConfiguration":{"imageTestsEnabled":false,"timeoutMinutes":120},
                          "clientToken":"%s"
                        }
                        """.formatted(pipelineArn, replacedRecipeArn, infraArn, distArn, UUID.randomUUID()))
                .when()
                .put("/UpdateImagePipeline")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .get("/GetImagePipeline")
                .then()
                .statusCode(200)
                .body("imagePipeline.imageRecipeArn", equalTo(replacedRecipeArn));

        given().header("Authorization", authorization).queryParam("imagePipelineArn", pipelineArn)
                .when().delete("/DeleteImagePipeline").then().statusCode(200);
        given().header("Authorization", authorization).queryParam("imageRecipeArn", replacedRecipeArn)
                .when().delete("/DeleteImageRecipe").then().statusCode(200);
        given().header("Authorization", authorization).queryParam("imageRecipeArn", recipeArn)
                .when().delete("/DeleteImageRecipe").then().statusCode(200);
        given().header("Authorization", authorization).queryParam("componentBuildVersionArn", replacedComponentArn)
                .when().delete("/DeleteComponent").then().statusCode(200);
        given().header("Authorization", authorization).queryParam("componentBuildVersionArn", componentArn)
                .when().delete("/DeleteComponent").then().statusCode(200);
        given().header("Authorization", authorization)
                .queryParam("infrastructureConfigurationArn", infraArn)
                .when().delete("/DeleteInfrastructureConfiguration").then().statusCode(200);
        given().header("Authorization", authorization)
                .queryParam("distributionConfigurationArn", distArn)
                .when().delete("/DeleteDistributionConfiguration").then().statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .get("/GetImagePipeline")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/imagebuilder/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
