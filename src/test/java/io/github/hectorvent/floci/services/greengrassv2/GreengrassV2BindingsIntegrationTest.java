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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-plane Greengrass V2 operations exercised by Alchemy's Bindings.test.ts:
 * GetComponent, artifacts, deployments, core-device probes, connectivity, and
 * IAM-signed ResolveComponentCandidates.
 */
@QuarkusTest
class GreengrassV2BindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String COMPONENT = "com.alchemy.test.GgBindingsOps";
    private static final String VERSION = "1.0.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getComponentRoundTripsTheRecipeAndMissingArtifactIsNotFound() {
        String authorization = auth(EAST);
        String arn = createComponent(authorization);

        given()
                .header("Authorization", authorization)
                .queryParam("recipeOutputFormat", "JSON")
                .when()
                .get("/greengrass/v2/components/" + encode(arn))
                .then()
                .statusCode(200)
                .body("recipeOutputFormat", equalTo("JSON"))
                .body("recipe", equalTo(base64(recipe())));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/components/" + encode(arn) + "/artifacts/missing.zip")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/components/" + encode(arn));
    }

    @Test
    void createGetListAndCancelDeployment() {
        String authorization = auth(EAST);
        createComponent(authorization);
        String targetArn = "arn:aws:iot:" + EAST + ":" + ACCOUNT + ":thing/gg-bindings-core";

        String deploymentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "targetArn":"%s",
                          "deploymentName":"BindingRollout",
                          "components":{"%s":{"componentVersion":"%s"}},
                          "tags":{"fixture":"greengrass-deployment"}
                        }
                        """.formatted(targetArn, COMPONENT, VERSION))
                .when()
                .post("/greengrass/v2/deployments")
                .then()
                .statusCode(200)
                .body("deploymentId", notNullValue())
                .extract().path("deploymentId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(deploymentId))
                .body("targetArn", equalTo(targetArn))
                .body("deploymentStatus", equalTo("ACTIVE"))
                .body("isLatestForTarget", equalTo(true))
                .body("tags.fixture", equalTo("greengrass-deployment"));

        given()
                .header("Authorization", authorization)
                .queryParam("historyFilter", "LATEST_ONLY")
                .when()
                .get("/greengrass/v2/deployments")
                .then()
                .statusCode(200)
                .body("deployments.deploymentId", hasItem(deploymentId));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/greengrass/v2/deployments/" + deploymentId + "/cancel")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("deploymentStatus", equalTo("CANCELED"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/components/" + encode(
                        "arn:aws:greengrass:" + EAST + ":" + ACCOUNT
                                + ":components:" + COMPONENT + ":versions:" + VERSION));
    }

    @Test
    void missingCoreDeviceProbesAreTypedNotFoundAndListsAreEmpty() {
        String authorization = auth(EAST);
        String missing = "alchemy-gg-bindings-missing-core";

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/coreDevices")
                .then()
                .statusCode(200)
                .body("coreDevices.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/coreDevices/" + missing)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/greengrass/v2/coreDevices/" + missing)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/coreDevices/" + missing + "/installedComponents")
                .then()
                .statusCode(200)
                .body("installedComponents.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/coreDevices/" + missing + "/effectiveDeployments")
                .then()
                .statusCode(200)
                .body("effectiveDeployments.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/v2/coreDevices/" + missing + "/associatedClientDevices")
                .then()
                .statusCode(200)
                .body("associatedClientDevices.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"entries\":[{\"thingName\":\"alchemy-gg-bindings-client\"}]}")
                .when()
                .post("/greengrass/v2/coreDevices/" + missing + "/associateClientDevices")
                .then()
                .statusCode(200)
                .body("errorEntries.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"entries\":[{\"thingName\":\"alchemy-gg-bindings-client\"}]}")
                .when()
                .post("/greengrass/v2/coreDevices/" + missing + "/disassociateClientDevices")
                .then()
                .statusCode(200)
                .body("errorEntries.size()", equalTo(0));
    }

    @Test
    void connectivityInfoRoundTripsAndResolveIsAccessDenied() {
        String authorization = auth(EAST);
        String thingName = "gg-bindings-core";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"ConnectivityInfo":[{"Id":"fixture","HostAddress":"127.0.0.1","PortNumber":8883}]}
                        """)
                .when()
                .put("/greengrass/things/" + thingName + "/connectivityInfo")
                .then()
                .statusCode(200)
                .body("Version", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/greengrass/things/" + thingName + "/connectivityInfo")
                .then()
                .statusCode(200)
                .body("ConnectivityInfo[0].HostAddress", equalTo("127.0.0.1"))
                .body("ConnectivityInfo[0].Id", equalTo("fixture"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "platform":{"attributes":{"os":"linux","architecture":"amd64"}},
                          "componentCandidates":[{"componentName":"%s","versionRequirements":{"fixture":"=%s"}}]
                        }
                        """.formatted(COMPONENT, VERSION))
                .when()
                .post("/greengrass/v2/resolveComponentCandidates")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private static String createComponent(String authorization) {
        var response = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"inlineRecipe\":\"" + base64(recipe()) + "\"}")
                .when()
                .post("/greengrass/v2/createComponentVersion")
                .then()
                .extract().response();
        if (response.statusCode() == 201) {
            return response.path("arn");
        }
        if (response.statusCode() == 409) {
            return "arn:aws:greengrass:" + EAST + ":" + ACCOUNT
                    + ":components:" + COMPONENT + ":versions:" + VERSION;
        }
        throw new AssertionError("createComponentVersion returned " + response.statusCode()
                + ": " + response.asString());
    }

    private static String recipe() {
        return """
                {"RecipeFormatVersion":"2020-01-25","ComponentName":"%s","ComponentVersion":"%s","ComponentDescription":"Alchemy GreengrassV2 bindings fixture component","ComponentPublisher":"Alchemy","Manifests":[{"Platform":{"os":"linux"},"Lifecycle":{"run":"echo greengrass bindings fixture"}}]}
                """.formatted(COMPONENT, VERSION).trim();
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
