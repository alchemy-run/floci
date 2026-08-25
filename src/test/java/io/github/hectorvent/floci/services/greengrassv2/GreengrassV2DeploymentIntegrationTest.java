package io.github.hectorvent.floci.services.greengrassv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies Greengrass V2 restJson1 component versions and deployment revisions. */
@QuarkusTest
class GreengrassV2DeploymentIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "00000000-dead-beef-0000-000000000000";
    private static final String RECIPE = """
            {
              "RecipeFormatVersion":"2020-01-25",
              "ComponentName":"com.floci.test.GgDeploy",
              "ComponentVersion":"1.0.0",
              "ComponentDescription":"Floci GreengrassV2 deployment test component",
              "ComponentPublisher":"Floci",
              "Manifests":[{"Platform":{"os":"linux"},"Lifecycle":{"run":"echo deployed"}}]
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDeploymentOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/greengrass/v2/deployments/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING))
                .body("resourceType", equalTo("deployment"));
    }

    @Test
    void createComponentDescribeAndDeployThenReviseCancelAndDelete() {
        String authorization = auth("000000000402", EAST);
        String thingArn = "arn:aws:iot:" + EAST + ":000000000402:thing/GgCore";
        String encodedRecipe = Base64.getEncoder().encodeToString(RECIPE.getBytes(StandardCharsets.UTF_8));

        Response createdComponent = given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("""
                        {
                          "inlineRecipe":"%s",
                          "tags":{"fixture":"greengrass-component","alchemy::id":"DeployHello"}
                        }
                        """.formatted(encodedRecipe))
                .when()
                .post("/greengrass/v2/createComponentVersion")
                .then()
                .statusCode(201)
                .body("componentName", equalTo("com.floci.test.GgDeploy"))
                .body("componentVersion", equalTo("1.0.0"))
                .body("status.componentState", equalTo("DEPLOYABLE"))
                .body("arn", notNullValue())
                .extract()
                .response();
        String componentArn = createdComponent.path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(componentArn) + "/metadata")
                .then()
                .statusCode(200)
                .body("componentName", equalTo("com.floci.test.GgDeploy"))
                .body("status.componentState", equalTo("DEPLOYABLE"))
                .body("tags.fixture", equalTo("greengrass-component"));

        Response created = given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("""
                        {
                          "targetArn":"%s",
                          "deploymentName":"Rollout",
                          "components":{
                            "com.floci.test.GgDeploy":{"componentVersion":"1.0.0"}
                          },
                          "tags":{"fixture":"greengrass-deployment","alchemy::id":"Rollout"}
                        }
                        """.formatted(thingArn))
                .when()
                .post("/greengrass/v2/deployments")
                .then()
                .statusCode(200)
                .body("deploymentId", notNullValue())
                .extract()
                .response();
        String deploymentId = created.path("deploymentId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(deploymentId))
                .body("targetArn", equalTo(thingArn))
                .body("isLatestForTarget", equalTo(true))
                .body("components.'com.floci.test.GgDeploy'.componentVersion", equalTo("1.0.0"))
                .body("tags.'alchemy::id'", equalTo("Rollout"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments?targetArn=" + thingArn + "&historyFilter=LATEST_ONLY")
                .then()
                .statusCode(200)
                .body("deployments.size()", equalTo(1))
                .body("deployments[0].deploymentId", equalTo(deploymentId));

        Response revised = given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("""
                        {
                          "targetArn":"%s",
                          "deploymentName":"Rollout",
                          "components":{
                            "com.floci.test.GgDeploy":{
                              "componentVersion":"1.0.0",
                              "configurationUpdate":{"merge":"{\\"interval\\":30}"}
                            }
                          },
                          "tags":{"fixture":"greengrass-deployment","alchemy::id":"Rollout"}
                        }
                        """.formatted(thingArn))
                .when()
                .post("/greengrass/v2/deployments")
                .then()
                .statusCode(200)
                .body("deploymentId", not(equalTo(deploymentId)))
                .extract()
                .response();
        String revisedId = revised.path("deploymentId");
        assertNotEquals(deploymentId, revisedId);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + revisedId)
                .then()
                .statusCode(200)
                .body("isLatestForTarget", equalTo(true))
                .body("components.'com.floci.test.GgDeploy'.configurationUpdate.merge",
                        equalTo("{\"interval\":30}"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/greengrass/v2/deployments/" + deploymentId + "/cancel")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/deployments/" + deploymentId)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + deploymentId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String deploymentArn = "arn:aws:greengrass:" + EAST + ":000000000402:deployments:" + revisedId;
        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{\"tags\":{\"team\":\"edge\"}}")
                .when()
                .post("/tags/" + encode(deploymentArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + revisedId)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("edge"));

        given()
                .header("Authorization", authorization)
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/greengrass/v2/deployments/" + revisedId + "/cancel")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/deployments/" + revisedId)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/components/" + encode(componentArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(componentArn) + "/metadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/greengrass/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
