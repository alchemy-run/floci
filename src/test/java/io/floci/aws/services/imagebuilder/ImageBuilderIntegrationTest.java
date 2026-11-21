package io.floci.aws.services.imagebuilder;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * EC2 Image Builder's configuration resources over REST: versioned components and recipes, infrastructure and
 * distribution configurations, workflows, pipelines and lifecycle policies, Amazon's managed resources, idempotent
 * client tokens, dependencies, resource policies and tags.
 */
@QuarkusTest
class ImageBuilderIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    static final String COMPONENT = """
            name: hello
            schemaVersion: 1.0
            phases:
              - name: build
                steps:
                  - name: Hello
                    action: ExecuteBash
                    inputs:
                      commands:
                        - echo hello > /tmp/hello.txt
              - name: validate
                steps:
                  - name: Check
                    action: ExecuteBash
                    inputs:
                      commands:
                        - grep hello /tmp/hello.txt
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static RequestSpecification signed() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                + "imagebuilder/aws4_request").contentType("application/json");
    }

    static ValidatableResponse put(String operation, String body) {
        return signed().body(body).put("/" + operation).then();
    }

    static ValidatableResponse post(String operation, String body) {
        return signed().body(body).post("/" + operation).then();
    }

    static ValidatableResponse get(String operation, String parameter, String value) {
        return signed().queryParam(parameter, value).get("/" + operation).then();
    }

    static ValidatableResponse delete(String operation, String parameter, String value) {
        return signed().queryParam(parameter, value).delete("/" + operation).then();
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void componentsRecipesAndConfigurations() {
        get("GetComponent", "componentBuildVersionArn", "arn:aws:imagebuilder:us-east-1:" + ACCOUNT
                + ":component/none/1.0.0/1").statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException")
                .body("message", containsString("does not exist"));
        put("CreateComponent", "{\"name\":\"Bad\",\"semanticVersion\":\"1.0\",\"platform\":\"Linux\",\"clientToken\""
                + ":\"t0\",\"data\":" + json(COMPONENT) + "}").statusCode(400)
                .header("x-amzn-ErrorType", "InvalidParameterValueException");
        put("CreateComponent", "{\"name\":\"Bad\",\"semanticVersion\":\"1.0.0\",\"platform\":\"Linux\","
                + "\"clientToken\":\"t1\",\"data\":\"phases: []\"}").statusCode(400)
                .header("x-amzn-ErrorType", "InvalidParameterValueException");

        String create = "{\"name\":\"Hello\",\"semanticVersion\":\"1.0.0\",\"platform\":\"Linux\",\"clientToken\":"
                + "\"c1\",\"data\":" + json(COMPONENT) + ",\"tags\":{\"team\":\"images\"}}";
        String component = put("CreateComponent", create).statusCode(200)
                .body("componentBuildVersionArn", equalTo("arn:aws:imagebuilder:us-east-1:" + ACCOUNT
                        + ":component/hello/1.0.0/1"))
                .body("latestVersionReferences.latestMinorVersionArn", endsWith("component/hello/1.0.x"))
                .extract().path("componentBuildVersionArn");
        put("CreateComponent", create).statusCode(200).body("componentBuildVersionArn", equalTo(component));
        put("CreateComponent", create.replace("\"Linux\"", "\"Windows\"")).statusCode(400)
                .header("x-amzn-ErrorType", "IdempotentParameterMismatchException");
        put("CreateComponent", create.replace("\"c1\"", "\"c2\"")).statusCode(400)
                .header("x-amzn-ErrorType", "ResourceAlreadyExistsException");
        get("GetComponent", "componentBuildVersionArn", component.replace("/1.0.0/1", "/x.x.x")).statusCode(200)
                .body("component.type", equalTo("BUILD")).body("component.state.status", equalTo("ACTIVE"))
                .body("component.tags.team", equalTo("images"));
        put("ImportComponent", "{\"name\":\"Script\",\"semanticVersion\":\"2.0.0\",\"type\":\"TEST\",\"format\":"
                + "\"SHELL\",\"platform\":\"Linux\",\"clientToken\":\"c3\",\"data\":\"echo testing\"}")
                .statusCode(200).body("componentBuildVersionArn", endsWith("component/script/2.0.0/1"));
        post("ListComponents", "{}").statusCode(200).body("componentVersionList.name", hasItem("Hello"));
        post("ListComponents", "{\"owner\":\"Amazon\"}").statusCode(200)
                .body("componentVersionList.name", hasItem("update-linux"));
        post("ListComponentBuildVersions", "{\"componentVersionArn\":\"" + component.replaceAll("/1$", "") + "\"}")
                .statusCode(200).body("componentSummaryList", hasSize(1));

        String managedParent = "arn:aws:imagebuilder:us-east-1:aws:image/amazon-linux-2023-x86/x.x.x";
        String recipe = put("CreateImageRecipe", "{\"name\":\"Base\",\"semanticVersion\":\"1.0.0\",\"parentImage\":\""
                + managedParent + "\",\"components\":[{\"componentArn\":\"" + component + "\"},{\"componentArn\":"
                + "\"arn:aws:imagebuilder:us-east-1:aws:component/update-linux/x.x.x\"}],\"clientToken\":\"r1\"}")
                .statusCode(200).body("imageRecipeArn", endsWith("image-recipe/base/1.0.0"))
                .extract().path("imageRecipeArn");
        get("GetImageRecipe", "imageRecipeArn", recipe).statusCode(200).body("imageRecipe.platform", equalTo("Linux"))
                .body("imageRecipe.parentImage", equalTo(managedParent)).body("imageRecipe.components", hasSize(2));
        put("CreateImageRecipe", "{\"name\":\"Broken\",\"semanticVersion\":\"1.0.0\",\"parentImage\":\""
                + managedParent + "\",\"components\":[{\"componentArn\":\"arn:aws:imagebuilder:us-east-1:" + ACCOUNT
                + ":component/missing/1.0.0/1\"}],\"clientToken\":\"r2\"}").statusCode(404);
        delete("DeleteComponent", "componentBuildVersionArn", component).statusCode(400)
                .header("x-amzn-ErrorType", "ResourceDependencyException");

        String infrastructure = put("CreateInfrastructureConfiguration", "{\"name\":\"Infra\",\"instanceProfileName\""
                + ":\"builder\",\"instanceTypes\":[\"t3.micro\"],\"clientToken\":\"i1\"}").statusCode(200)
                .extract().path("infrastructureConfigurationArn");
        get("GetInfrastructureConfiguration", "infrastructureConfigurationArn", infrastructure).statusCode(200)
                .body("infrastructureConfiguration.terminateInstanceOnFailure", equalTo(true))
                .body("infrastructureConfiguration.instanceTypes", hasItem("t3.micro"));
        put("UpdateInfrastructureConfiguration", "{\"infrastructureConfigurationArn\":\"" + infrastructure
                + "\",\"instanceProfileName\":\"builder\",\"description\":\"updated\",\"clientToken\":\"i2\"}")
                .statusCode(200);
        get("GetInfrastructureConfiguration", "infrastructureConfigurationArn", infrastructure).statusCode(200)
                .body("infrastructureConfiguration.description", equalTo("updated"))
                .body("infrastructureConfiguration.instanceTypes", equalTo(null));

        put("CreateDistributionConfiguration", "{\"name\":\"Fixed\",\"distributions\":[{\"region\":\"us-east-1\","
                + "\"amiDistributionConfiguration\":{\"name\":\"fixed-name\"}}],\"clientToken\":\"d0\"}")
                .statusCode(400);
        String distribution = put("CreateDistributionConfiguration", "{\"name\":\"Dist\",\"distributions\":[{"
                + "\"region\":\"us-east-1\",\"amiDistributionConfiguration\":{\"name\":\"base-{{ imagebuilder:buildDate"
                + " }}\"}}],\"clientToken\":\"d1\"}").statusCode(200).extract().path("distributionConfigurationArn");
        post("ListDistributionConfigurations", "{}").statusCode(200)
                .body("distributionConfigurationSummaryList[0].regions", hasItem("us-east-1"));

        // Workflows: documents are validated; Amazon's managed ones are listed.
        put("CreateWorkflow", "{\"name\":\"Bad\",\"semanticVersion\":\"1.0.0\",\"type\":\"BUILD\",\"clientToken\":"
                + "\"w0\",\"data\":" + json("schemaVersion: 1.0\nsteps:\n  - name: Nope\n    action: Teleport\n")
                + "}").statusCode(400);
        String workflow = put("CreateWorkflow", "{\"name\":\"Approve\",\"semanticVersion\":\"1.0.0\",\"type\":\"TEST\","
                + "\"clientToken\":\"w1\",\"data\":" + json("schemaVersion: 1.0\nparameters:\n  - name: wait\n"
                + "    type: boolean\n    default: true\nsteps:\n  - name: Approval\n    action: WaitForAction\n")
                + "}").statusCode(200).body("workflowBuildVersionArn", endsWith("workflow/test/approve/1.0.0/1"))
                .extract().path("workflowBuildVersionArn");
        get("GetWorkflow", "workflowBuildVersionArn", workflow).statusCode(200)
                .body("workflow.parameters[0].name", equalTo("wait"));
        post("ListWorkflows", "{\"owner\":\"Amazon\"}").statusCode(200)
                .body("workflowVersionList.name", hasItem("build-image"));

        // Pipelines depend on their recipe and configurations.
        put("CreateImagePipeline", "{\"name\":\"Bad\",\"imageRecipeArn\":\"" + recipe + "\","
                + "\"infrastructureConfigurationArn\":\"" + infrastructure + "\",\"schedule\":{\"scheduleExpression\":"
                + "\"every tuesday\"},\"clientToken\":\"p0\"}").statusCode(400);
        String pipeline = put("CreateImagePipeline", "{\"name\":\"Nightly\",\"imageRecipeArn\":\"" + recipe + "\","
                + "\"infrastructureConfigurationArn\":\"" + infrastructure + "\",\"distributionConfigurationArn\":\""
                + distribution + "\",\"schedule\":{\"scheduleExpression\":\"cron(0 3 * * ? *)\"},\"status\":"
                + "\"DISABLED\",\"imageTestsConfiguration\":{\"timeoutMinutes\":90},\"workflows\":[{\"workflowArn\":\""
                + workflow + "\"}],\"clientToken\":\"p1\"}").statusCode(200).extract().path("imagePipelineArn");
        get("GetImagePipeline", "imagePipelineArn", pipeline).statusCode(200)
                .body("imagePipeline.status", equalTo("DISABLED"))
                .body("imagePipeline.imageTestsConfiguration.timeoutMinutes", equalTo(90))
                .body("imagePipeline.imageTestsConfiguration.imageTestsEnabled", equalTo(true))
                .body("imagePipeline.schedule.pipelineExecutionStartCondition",
                        equalTo("EXPRESSION_MATCH_AND_DEPENDENCY_UPDATES_AVAILABLE"));
        delete("DeleteImageRecipe", "imageRecipeArn", recipe).statusCode(400)
                .header("x-amzn-ErrorType", "ResourceDependencyException");
        delete("DeleteWorkflow", "workflowBuildVersionArn", workflow).statusCode(400);

        // Resource policies and tags.
        put("PutImageRecipePolicy", "{\"imageRecipeArn\":\"" + recipe + "\",\"policy\":\"{\\\"Version\\\":"
                + "\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}").statusCode(200);
        get("GetImageRecipePolicy", "imageRecipeArn", recipe).statusCode(200).body("policy", containsString("Statement"));
        String encoded = URLEncoder.encode(pipeline, StandardCharsets.UTF_8);
        signed().urlEncodingEnabled(false).body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + encoded).then()
                .statusCode(200);
        signed().urlEncodingEnabled(false).get("/tags/" + encoded).then().statusCode(200)
                .body("tags.env", equalTo("dev"));

        // Lifecycle policies.
        String policy = put("CreateLifecyclePolicy", "{\"name\":\"Keep3\",\"executionRole\":\"arn:aws:iam::"
                + ACCOUNT + ":role/lifecycle\",\"resourceType\":\"AMI_IMAGE\",\"policyDetails\":[{\"action\":{"
                + "\"type\":\"DELETE\"},\"filter\":{\"type\":\"COUNT\",\"value\":3}}],\"resourceSelection\":{"
                + "\"recipes\":[{\"name\":\"Base\",\"semanticVersion\":\"1.x.x\"}]},\"clientToken\":\"l1\"}")
                .statusCode(200).extract().path("lifecyclePolicyArn");
        get("GetLifecyclePolicy", "lifecyclePolicyArn", policy).statusCode(200)
                .body("lifecyclePolicy.status", equalTo("ENABLED"));
        post("ListLifecyclePolicies", "{}").statusCode(200).body("lifecyclePolicySummaryList.name", hasItem("Keep3"));
        delete("DeleteLifecyclePolicy", "lifecyclePolicyArn", policy).statusCode(200);

        delete("DeleteImagePipeline", "imagePipelineArn", pipeline).statusCode(200);
        delete("DeleteImageRecipe", "imageRecipeArn", recipe).statusCode(200);
        delete("DeleteWorkflow", "workflowBuildVersionArn", workflow).statusCode(200);
        delete("DeleteDistributionConfiguration", "distributionConfigurationArn", distribution).statusCode(200);
        delete("DeleteInfrastructureConfiguration", "infrastructureConfigurationArn", infrastructure)
                .statusCode(200);
        delete("DeleteComponent", "componentBuildVersionArn", component).statusCode(200);
        post("ListComponents", "{}").statusCode(200).body("componentVersionList.name", not(hasItem("Hello")));
        post("ListImages", "{\"owner\":\"Amazon\"}").statusCode(200)
                .body("imageVersionList.name", hasItem("amazon-linux-2023-x86"));
    }
}
