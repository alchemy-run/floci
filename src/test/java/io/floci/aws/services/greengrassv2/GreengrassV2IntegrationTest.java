package io.floci.aws.services.greengrassv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS IoT Greengrass V2 over REST: components from JSON and YAML recipes and their S3 artifacts, deployments to a
 * thing (through its deployment shadow) and to a thing group (through an IoT job), core devices registered by the
 * Fleet Status reports a nucleus publishes, candidate resolution, connectivity, client devices and tags.
 */
@QuarkusTest
class GreengrassV2IntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification gg() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                + "greengrass/aws4_request").contentType("application/json");
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                + "s3/aws4_request");
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String componentArn(String name, String version) {
        return "arn:aws:greengrass:us-east-1:" + ACCOUNT + ":components:" + name + ":versions:" + version;
    }

    private static void report(String thing, String body) {
        given().contentType("application/json").body(body).post("/topics/$aws/things/" + thing
                + "/greengrassv2/health/json").then().statusCode(200);
    }

    @Test
    void componentsDeploymentsAndCoreDevices() {
        s3().put("/gg-artifacts").then().statusCode(200);
        s3().body("print('hello')").put("/gg-artifacts/hello/hello.py").then().statusCode(200);

        gg().get("/greengrass/v2/components/" + componentArn("com.example.Nope", "1.0.0") + "/metadata").then()
                .statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException");
        gg().body("{\"inlineRecipe\":\"" + b64("{\"RecipeFormatVersion\":\"2020-01-25\",\"ComponentName\":"
                + "\"aws.greengrass.Mine\",\"ComponentVersion\":\"1.0.0\"}") + "\"}")
                .post("/greengrass/v2/createComponentVersion").then().statusCode(400)
                .body("message", containsString("reserved"));
        gg().body("{\"inlineRecipe\":\"" + b64("{\"RecipeFormatVersion\":\"2020-01-25\",\"ComponentName\":"
                + "\"com.example.Bad\",\"ComponentVersion\":\"one\"}") + "\"}")
                .post("/greengrass/v2/createComponentVersion").then().statusCode(400);

        String recipe = "{\"RecipeFormatVersion\":\"2020-01-25\",\"ComponentName\":\"com.example.Hello\","
                + "\"ComponentVersion\":\"1.0.0\",\"ComponentPublisher\":\"Example\",\"ComponentDescription\":\"hi\","
                + "\"ComponentDependencies\":{\"aws.greengrass.Nucleus\":{\"VersionRequirement\":\">=2.0.0 <3.0.0\"}},"
                + "\"Manifests\":[{\"Platform\":{\"os\":\"linux\"},\"Lifecycle\":{\"Run\":\"python3 "
                + "{artifacts:path}/hello.py\"},\"Artifacts\":[{\"Uri\":\"s3://gg-artifacts/hello/hello.py\"}]}]}";
        gg().body("{\"inlineRecipe\":\"" + b64(recipe) + "\",\"tags\":{\"team\":\"edge\"}}")
                .post("/greengrass/v2/createComponentVersion").then().statusCode(201)
                .body("arn", equalTo(componentArn("com.example.Hello", "1.0.0")))
                .body("status.componentState", equalTo("DEPLOYABLE"));
        gg().body("{\"inlineRecipe\":\"" + b64(recipe) + "\"}").post("/greengrass/v2/createComponentVersion").then()
                .statusCode(409);
        String yaml = "RecipeFormatVersion: '2020-01-25'\nComponentName: com.example.Hello\nComponentVersion: 1.1.0\n"
                + "Manifests:\n  - Platform:\n      os: windows\n    Lifecycle:\n      Run: echo hi\n";
        gg().body("{\"inlineRecipe\":\"" + b64(yaml) + "\"}").post("/greengrass/v2/createComponentVersion").then()
                .statusCode(201);
        String missing = "{\"RecipeFormatVersion\":\"2020-01-25\",\"ComponentName\":\"com.example.Broken\","
                + "\"ComponentVersion\":\"1.0.0\",\"Manifests\":[{\"Artifacts\":[{\"Uri\":"
                + "\"s3://gg-artifacts/missing.zip\"}]}]}";
        gg().body("{\"inlineRecipe\":\"" + b64(missing) + "\"}").post("/greengrass/v2/createComponentVersion").then()
                .statusCode(201).body("status.componentState", equalTo("FAILED"))
                .body("status.errors.'s3://gg-artifacts/missing.zip'", containsString("S3"));

        gg().get("/greengrass/v2/components/" + componentArn("com.example.Hello", "1.0.0") + "/metadata").then()
                .statusCode(200).body("publisher", equalTo("Example")).body("platforms[0].attributes.os",
                        equalTo("linux")).body("tags.team", equalTo("edge"));
        String fetched = gg().queryParam("recipeOutputFormat", "JSON").get("/greengrass/v2/components/"
                + componentArn("com.example.Hello", "1.0.0")).then().statusCode(200).extract().path("recipe");
        String json = new String(Base64.getDecoder().decode(fetched), StandardCharsets.UTF_8);
        assertEquals(true, json.contains("\"Digest\""), json);
        String yamlOut = gg().queryParam("recipeOutputFormat", "YAML").get("/greengrass/v2/components/"
                + componentArn("com.example.Hello", "1.1.0")).then().statusCode(200).extract().path("recipe");
        assertEquals(true, new String(Base64.getDecoder().decode(yamlOut), StandardCharsets.UTF_8)
                .contains("ComponentVersion: \"1.1.0\""));
        String url = gg().get("/greengrass/v2/components/" + componentArn("com.example.Hello", "1.0.0")
                + "/artifacts/gg-artifacts/hello/hello.py").then().statusCode(200).extract().path("preSignedUrl");
        URI artifact = URI.create(url);
        given().urlEncodingEnabled(false).header("Host", artifact.getRawAuthority()).get(artifact.getRawPath() + "?" + artifact.getRawQuery()).then()
                .statusCode(200).body(equalTo("print('hello')"));
        gg().get("/greengrass/v2/components/" + componentArn("com.example.Hello", "1.0.0") + "/artifacts/nope.zip")
                .then().statusCode(404);
        gg().get("/greengrass/v2/components/arn:aws:greengrass:us-east-1:" + ACCOUNT + ":components:"
                + "com.example.Hello/versions").then().statusCode(200).body("componentVersions.componentVersion",
                        equalTo(List.of("1.1.0", "1.0.0")));
        gg().get("/greengrass/v2/components").then().statusCode(200)
                .body("components.find { it.componentName == 'com.example.Hello' }.latestVersion.componentVersion",
                        equalTo("1.1.0"));
        gg().queryParam("scope", "PUBLIC").get("/greengrass/v2/components").then().statusCode(200)
                .body("components.componentName", hasItem("aws.greengrass.Nucleus"));

        // Resolution: a nucleus resolves with its certificate; IAM credentials are refused.
        String resolve = "{\"platform\":{\"attributes\":{\"os\":\"linux\",\"architecture\":\"amd64\"}},"
                + "\"componentCandidates\":[{\"componentName\":\"com.example.Hello\",\"componentVersion\":\"1.0.0\","
                + "\"versionRequirements\":{\"deployment\":\">=1.0.0\"}}]}";
        gg().body(resolve).post("/greengrass/v2/resolveComponentCandidates").then().statusCode(403);
        given().contentType("application/json").body(resolve).post("/greengrass/v2/resolveComponentCandidates")
                .then().statusCode(200).body("resolvedComponentVersions[0].componentVersion", equalTo("1.0.0"));
        given().contentType("application/json").body(resolve.replace(">=1.0.0", "^2.0.0"))
                .post("/greengrass/v2/resolveComponentCandidates").then().statusCode(404);

        // A deployment to a thing is written to its deployment shadow.
        given().contentType("application/json").body("{}").post("/things/gg-core").then().statusCode(200);
        String thingArn = "arn:aws:iot:us-east-1:" + ACCOUNT + ":thing/gg-core";
        gg().body("{\"targetArn\":\"arn:aws:iot:us-east-1:" + ACCOUNT + ":thing/nope\",\"components\":{}}")
                .post("/greengrass/v2/deployments").then().statusCode(404);
        gg().body("{\"targetArn\":\"" + thingArn + "\",\"components\":{\"com.example.Missing\":"
                + "{\"componentVersion\":\"1.0.0\"}}}").post("/greengrass/v2/deployments").then().statusCode(404);
        String first = gg().body("{\"targetArn\":\"" + thingArn + "\",\"deploymentName\":\"first\",\"components\":"
                + "{\"com.example.Hello\":{\"componentVersion\":\"1.0.0\"},\"aws.greengrass.Nucleus\":"
                + "{\"componentVersion\":\"2.15.0\"}}}").post("/greengrass/v2/deployments").then().statusCode(201)
                .extract().path("deploymentId");
        given().queryParam("name", "AWSManagedGreengrassV2Deployment").get("/things/gg-core/shadow").then()
                .statusCode(200).body("state.desired.fleetConfig.deploymentId", equalTo(first))
                .body("state.desired.fleetConfig.components.'com.example.Hello'.version", equalTo("1.0.0"));
        String second = gg().body("{\"targetArn\":\"" + thingArn + "\",\"deploymentName\":\"second\","
                + "\"components\":{\"com.example.Hello\":{\"componentVersion\":\"1.0.0\"}},\"tags\":{\"k\":\"v\"}}")
                .post("/greengrass/v2/deployments").then().statusCode(201).extract().path("deploymentId");
        gg().get("/greengrass/v2/deployments/" + first).then().statusCode(200)
                .body("deploymentStatus", equalTo("INACTIVE")).body("isLatestForTarget", equalTo(false));
        gg().get("/greengrass/v2/deployments/" + second).then().statusCode(200).body("revisionId", equalTo("2"))
                .body("deploymentStatus", equalTo("ACTIVE")).body("iotJobId", nullValue());
        gg().get("/greengrass/v2/deployments").then().statusCode(200).body("deployments.deploymentId",
                not(hasItem(first)));
        gg().queryParam("historyFilter", "ALL").get("/greengrass/v2/deployments").then().statusCode(200)
                .body("deployments.deploymentId", hasItem(first));

        // The device's Fleet Status report registers it and completes the deployment it applied.
        gg().get("/greengrass/v2/coreDevices/gg-core").then().statusCode(404);
        report("gg-core", "{\"ggcVersion\":\"2.15.0\",\"platform\":\"linux\",\"architecture\":\"amd64\","
                + "\"thing\":\"gg-core\",\"sequenceNumber\":1,\"timestamp\":1780000000000,\"messageType\":"
                + "\"COMPLETE\",\"trigger\":\"THING_DEPLOYMENT\",\"overallDeviceStatus\":\"HEALTHY\",\"components\":"
                + "[{\"componentName\":\"com.example.Hello\",\"version\":\"1.0.0\",\"lifecycleState\":\"RUNNING\","
                + "\"isRoot\":true},{\"componentName\":\"aws.greengrass.Nucleus\",\"version\":\"2.15.0\","
                + "\"lifecycleState\":\"FINISHED\",\"isRoot\":false}],\"deploymentInformation\":{\"status\":"
                + "\"SUCCEEDED\",\"deploymentId\":\"" + second + "\",\"statusDetails\":{\"detailedStatus\":"
                + "\"SUCCESSFUL\"}}}");
        gg().get("/greengrass/v2/coreDevices/gg-core").then().statusCode(200).body("status", equalTo("HEALTHY"))
                .body("coreVersion", equalTo("2.15.0")).body("architecture", equalTo("amd64"));
        gg().get("/greengrass/v2/coreDevices").then().statusCode(200).body("coreDevices.coreDeviceThingName",
                hasItem("gg-core"));
        gg().get("/greengrass/v2/coreDevices/gg-core/installedComponents").then().statusCode(200)
                .body("installedComponents", hasSize(1));
        gg().queryParam("topologyFilter", "ALL").get("/greengrass/v2/coreDevices/gg-core/installedComponents").then()
                .statusCode(200).body("installedComponents", hasSize(2));
        gg().get("/greengrass/v2/coreDevices/gg-core/effectiveDeployments").then().statusCode(200)
                .body("effectiveDeployments.find { it.deploymentId == '" + second + "' }.coreDeviceExecutionStatus",
                        equalTo("SUCCEEDED"));
        gg().get("/greengrass/v2/deployments/" + second).then().body("deploymentStatus", equalTo("COMPLETED"));

        // A deployment to a thing group is an IoT job.
        given().contentType("application/json").body("{}").post("/thing-groups/gg-fleet").then().statusCode(200);
        String groupArn = "arn:aws:iot:us-east-1:" + ACCOUNT + ":thinggroup/gg-fleet";
        String fleet = gg().body("{\"targetArn\":\"" + groupArn + "\",\"components\":{\"com.example.Hello\":"
                + "{\"componentVersion\":\"1.0.0\"}}}").post("/greengrass/v2/deployments").then().statusCode(201)
                .body("iotJobId", containsString("greengrass-")).extract().path("deploymentId");
        String jobId = gg().get("/greengrass/v2/deployments/" + fleet).then().statusCode(200)
                .extract().path("iotJobId");
        given().get("/jobs/" + jobId).then().statusCode(200).body("job.status", equalTo("IN_PROGRESS"));
        gg().body("{}").post("/greengrass/v2/deployments/" + fleet + "/cancel").then().statusCode(200);
        given().get("/jobs/" + jobId).then().statusCode(200).body("job.status", equalTo("CANCELED"));
        gg().body("{}").post("/greengrass/v2/deployments/" + fleet + "/cancel").then().statusCode(409);
        gg().delete("/greengrass/v2/deployments/" + second).then().statusCode(204);
        gg().delete("/greengrass/v2/deployments/" + fleet).then().statusCode(204);
        given().get("/jobs/" + jobId).then().statusCode(404);

        // Connectivity, client devices, the service role and tags.
        gg().body("{\"ConnectivityInfo\":[{\"HostAddress\":\"127.0.0.1\",\"PortNumber\":8883}]}")
                .put("/greengrass/things/gg-core/connectivityInfo").then().statusCode(200);
        gg().get("/greengrass/things/gg-core/connectivityInfo").then().statusCode(200)
                .body("ConnectivityInfo.HostAddress", hasItem("127.0.0.1"));
        given().contentType("application/json").body("{}").post("/things/gg-sensor").then().statusCode(200);
        gg().body("{\"entries\":[{\"thingName\":\"gg-sensor\"},{\"thingName\":\"gg-ghost\"}]}")
                .post("/greengrass/v2/coreDevices/gg-core/associateClientDevices").then().statusCode(200)
                .body("errorEntries", hasSize(1)).body("errorEntries[0].thingName", equalTo("gg-ghost"));
        gg().get("/greengrass/v2/coreDevices/gg-core/associatedClientDevices").then().statusCode(200)
                .body("associatedClientDevices.thingName", hasItem("gg-sensor"));
        gg().body("{\"RoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/GreengrassServiceRole\"}")
                .put("/greengrass/servicerole").then().statusCode(200);
        gg().get("/greengrass/servicerole").then().statusCode(200).body("RoleArn", containsString("Greengrass"));
        String tagArn = URLEncoder.encode(componentArn("com.example.Hello", "1.0.0"), StandardCharsets.UTF_8);
        gg().body("{\"tags\":{\"stage\":\"one\"}}").post("/tags/" + tagArn).then().statusCode(200);
        gg().get("/tags/" + tagArn).then().statusCode(200).body("tags.stage", equalTo("one"))
                .body("tags.team", equalTo("edge"));
        gg().queryParam("tagKeys", "team").delete("/tags/" + tagArn).then().statusCode(200);
        gg().get("/tags/" + tagArn).then().body("tags.team", nullValue());

        gg().delete("/greengrass/v2/coreDevices/gg-core").then().statusCode(204);
        gg().get("/greengrass/v2/coreDevices/gg-core").then().statusCode(404);
        gg().delete("/greengrass/v2/components/" + componentArn("com.example.Hello", "1.1.0")).then()
                .statusCode(204);
        gg().get("/greengrass/v2/components/" + componentArn("com.example.Hello", "1.1.0") + "/metadata").then()
                .statusCode(404);
    }
}
