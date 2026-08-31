package io.github.hectorvent.floci.services.imagebuilder;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-plane Image Builder operations exercised by Alchemy's
 * Bindings.test.ts: component/recipe/infra/pipeline CRUD, GetImagePipeline,
 * Start/Cancel/Get/Delete image, list APIs, and tags.
 */
@QuarkusTest
class ImageBuilderBindingsIntegrationTest {

    private static final String WEST = "us-west-2";
    private static final String PARENT =
            "arn:aws:imagebuilder:us-west-2:aws:image/amazon-linux-2023-x86/x.x.x";
    private static final String COMPONENT_DATA = """
            name: alchemy-imagebuilder-bindings-component
            description: no-op component used by alchemy binding tests
            schemaVersion: 1.0
            phases:
              - name: build
                steps:
                  - name: hello
                    action: ExecuteBash
                    inputs:
                      commands:
                        - echo hello-from-alchemy-bindings
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getComponentOnAMissingArnIsNotFound() {
        given()
                .header("Authorization", auth(WEST))
                .queryParam("componentBuildVersionArn",
                        "arn:aws:imagebuilder:" + WEST + ":000000000000:component/missing/1.0.0/1")
                .when()
                .get("/GetComponent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetTagAndDeleteComponentRoundTrip() {
        String authorization = auth(WEST);
        String name = "ib-comp-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.0",
                          "platform":"Linux",
                          "data":%s,
                          "clientToken":"%s",
                          "tags":{"fixture":"imagebuilder"}
                        }
                        """.formatted(name, jsonString(COMPONENT_DATA), UUID.randomUUID()))
                .when()
                .put("/CreateComponent")
                .then()
                .statusCode(200)
                .body("componentBuildVersionArn", notNullValue())
                .extract().path("componentBuildVersionArn");

        given()
                .header("Authorization", authorization)
                .queryParam("componentBuildVersionArn", arn)
                .when()
                .get("/GetComponent")
                .then()
                .statusCode(200)
                .body("component.arn", equalTo(arn))
                .body("component.name", equalTo(name))
                .body("component.version", equalTo("1.0.0"))
                .body("component.platform", equalTo("Linux"))
                .body("component.type", equalTo("BUILD"))
                .body("component.data", equalTo(COMPONENT_DATA))
                .body("component.tags.fixture", equalTo("imagebuilder"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"1\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("imagebuilder"))
                .body("tags.extra", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .queryParam("componentBuildVersionArn", arn)
                .when()
                .delete("/DeleteComponent")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("componentBuildVersionArn", arn)
                .when()
                .get("/GetComponent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void pipelineLifecycleStartCancelGetAndDeleteImage() {
        String authorization = auth(WEST);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String componentArn = createComponent(authorization, "ib-pipe-c-" + suffix);
        String recipeArn = createRecipe(authorization, "ib-pipe-r-" + suffix, componentArn);
        String infraArn = createInfra(authorization, "ib-pipe-i-" + suffix);
        String pipelineArn = createPipeline(authorization, "ib-pipe-p-" + suffix, recipeArn, infraArn);

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .get("/GetImagePipeline")
                .then()
                .statusCode(200)
                .body("imagePipeline.arn", equalTo(pipelineArn))
                .body("imagePipeline.status", equalTo("ENABLED"))
                .body("imagePipeline.imageTestsConfiguration.timeoutMinutes", equalTo(60))
                .body("imagePipeline.imageTestsConfiguration.imageTestsEnabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"imagePipelineArn\":\"" + pipelineArn + "\"}")
                .when()
                .post("/ListImagePipelineImages")
                .then()
                .statusCode(200)
                .body("imageSummaryList", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"owner\":\"Self\"}")
                .when()
                .post("/ListImages")
                .then()
                .statusCode(200)
                .body("imageVersionList.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListImageScanFindings")
                .then()
                .statusCode(200)
                .body("findings.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListImageScanFindingAggregations")
                .then()
                .statusCode(200)
                .body("responses.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListWaitingWorkflowSteps")
                .then()
                .statusCode(200)
                .body("steps.size()", equalTo(0));

        String imageArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "imagePipelineArn":"%s",
                          "clientToken":"%s"
                        }
                        """.formatted(pipelineArn, UUID.randomUUID()))
                .when()
                .put("/StartImagePipelineExecution")
                .then()
                .statusCode(200)
                .body("imageBuildVersionArn", notNullValue())
                .extract().path("imageBuildVersionArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "imageBuildVersionArn":"%s",
                          "clientToken":"%s"
                        }
                        """.formatted(imageArn, UUID.randomUUID()))
                .when()
                .put("/CancelImageCreation")
                .then()
                .statusCode(200)
                .body("imageBuildVersionArn", equalTo(imageArn));

        given()
                .header("Authorization", authorization)
                .queryParam("imageBuildVersionArn", imageArn)
                .when()
                .get("/GetImage")
                .then()
                .statusCode(200)
                .body("image.arn", equalTo(imageArn))
                .body("image.state.status", equalTo("CANCELLED"));

        String versionArn = imageArn.replaceAll("/\\d+$", "");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"imageVersionArn\":\"" + versionArn + "\"}")
                .when()
                .post("/ListImageBuildVersions")
                .then()
                .statusCode(200)
                .body("imageSummaryList.arn", hasItem(imageArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"imageBuildVersionArn\":\"" + imageArn + "\"}")
                .when()
                .post("/ListImagePackages")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"imageBuildVersionArn\":\"" + imageArn + "\"}")
                .when()
                .post("/ListWorkflowExecutions")
                .then()
                .statusCode(200)
                .body("workflowExecutions.size()", greaterThanOrEqualTo(1));

        given()
                .header("Authorization", authorization)
                .queryParam("imageBuildVersionArn", imageArn)
                .when()
                .delete("/DeleteImage")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("imagePipelineArn", pipelineArn)
                .when()
                .delete("/DeleteImagePipeline")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .queryParam("imageRecipeArn", recipeArn)
                .when()
                .delete("/DeleteImageRecipe")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .queryParam("infrastructureConfigurationArn", infraArn)
                .when()
                .delete("/DeleteInfrastructureConfiguration")
                .then()
                .statusCode(200);
        given()
                .header("Authorization", authorization)
                .queryParam("componentBuildVersionArn", componentArn)
                .when()
                .delete("/DeleteComponent")
                .then()
                .statusCode(200);
    }

    private static String createComponent(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.0",
                          "platform":"Linux",
                          "data":%s,
                          "clientToken":"%s"
                        }
                        """.formatted(name, jsonString(COMPONENT_DATA), UUID.randomUUID()))
                .when()
                .put("/CreateComponent")
                .then()
                .statusCode(200)
                .extract().path("componentBuildVersionArn");
    }

    private static String createRecipe(String authorization, String name, String componentArn) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "semanticVersion":"1.0.0",
                          "parentImage":"%s",
                          "components":[{"componentArn":"%s"}],
                          "clientToken":"%s"
                        }
                        """.formatted(name, PARENT, componentArn, UUID.randomUUID()))
                .when()
                .put("/CreateImageRecipe")
                .then()
                .statusCode(200)
                .extract().path("imageRecipeArn");
    }

    private static String createInfra(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "instanceProfileName":"BindingsBuilderProfile",
                          "instanceTypes":["t3.micro"],
                          "terminateInstanceOnFailure":true,
                          "clientToken":"%s"
                        }
                        """.formatted(name, UUID.randomUUID()))
                .when()
                .put("/CreateInfrastructureConfiguration")
                .then()
                .statusCode(200)
                .extract().path("infrastructureConfigurationArn");
    }

    private static String createPipeline(
            String authorization, String name, String recipeArn, String infraArn) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "imageRecipeArn":"%s",
                          "infrastructureConfigurationArn":"%s",
                          "imageTestsConfiguration":{"imageTestsEnabled":false,"timeoutMinutes":60},
                          "clientToken":"%s"
                        }
                        """.formatted(name, recipeArn, infraArn, UUID.randomUUID()))
                .when()
                .put("/CreateImagePipeline")
                .then()
                .statusCode(200)
                .extract().path("imagePipelineArn");
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/imagebuilder/aws4_request";
    }
}
