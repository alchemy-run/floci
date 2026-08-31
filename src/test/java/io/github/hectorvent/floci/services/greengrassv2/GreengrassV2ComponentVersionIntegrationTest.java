package io.github.hectorvent.floci.services.greengrassv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Greengrass V2 restJson1 component-version lifecycle and tags. */
@QuarkusTest
class GreengrassV2ComponentVersionIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeComponentOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:greengrass:" + EAST + ":" + ACCOUNT
                + ":components:com.alchemy.test.Nonexistent:versions:0.0.1";
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/greengrass/v2/components/" + encode(arn) + "/metadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeTagReplaceDeleteComponentVersionLifecycle() {
        String authorization = auth(EAST);
        String recipeV1 = recipe("1.0.0");
        String createBody = """
                {
                  "inlineRecipe":"%s",
                  "tags":{"fixture":"greengrass-component","alchemy::id":"Hello"}
                }
                """.formatted(base64(recipeV1));

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(createBody)
                .when()
                .post("/greengrass/v2/createComponentVersion")
                .then()
                .statusCode(201)
                .body("componentName", equalTo("com.alchemy.test.GgHello"))
                .body("componentVersion", equalTo("1.0.0"))
                .body("status.componentState", equalTo("DEPLOYABLE"))
                .body("arn", notNullValue())
                .extract().path("arn");

        assertTrue(arn.contains(":components:com.alchemy.test.GgHello:versions:1.0.0"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(arn) + "/metadata")
                .then()
                .statusCode(200)
                .body("arn", equalTo(arn))
                .body("componentName", equalTo("com.alchemy.test.GgHello"))
                .body("componentVersion", equalTo("1.0.0"))
                .body("status.componentState", equalTo("DEPLOYABLE"))
                .body("tags.fixture", equalTo("greengrass-component"))
                .body("tags.'alchemy::id'", equalTo("Hello"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"team\":\"edge\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("edge"))
                .body("tags.fixture", equalTo("greengrass-component"));

        String componentArn = "arn:aws:greengrass:" + EAST + ":" + ACCOUNT
                + ":components:com.alchemy.test.GgHello";
        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components?scope=PRIVATE")
                .then()
                .statusCode(200)
                .body("components[0].componentName", equalTo("com.alchemy.test.GgHello"))
                .body("components[0].arn", equalTo(componentArn));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(componentArn) + "/versions")
                .then()
                .statusCode(200)
                .body("componentVersions[0].arn", equalTo(arn))
                .body("componentVersions[0].componentVersion", equalTo("1.0.0"));

        String recipeV2 = recipe("1.0.1");
        String bumpedArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "inlineRecipe":"%s",
                          "tags":{"fixture":"greengrass-component"}
                        }
                        """.formatted(base64(recipeV2)))
                .when()
                .post("/greengrass/v2/createComponentVersion")
                .then()
                .statusCode(201)
                .body("componentVersion", equalTo("1.0.1"))
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/components/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(arn) + "/metadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/components/" + encode(bumpedArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(bumpedArn) + "/metadata")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String recipe(String version) {
        return """
                {"RecipeFormatVersion":"2020-01-25","ComponentName":"com.alchemy.test.GgHello","ComponentVersion":"%s","ComponentDescription":"Alchemy GreengrassV2 test component","ComponentPublisher":"Alchemy","Manifests":[{"Platform":{"os":"linux"},"Lifecycle":{"run":"echo hello from alchemy"}}]}
                """.formatted(version).trim();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/greengrass/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
