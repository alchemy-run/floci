package io.floci.aws.services.imagebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.floci.aws.services.imagebuilder.ImageBuilderIntegrationTest.get;
import static io.floci.aws.services.imagebuilder.ImageBuilderIntegrationTest.json;
import static io.floci.aws.services.imagebuilder.ImageBuilderIntegrationTest.post;
import static io.floci.aws.services.imagebuilder.ImageBuilderIntegrationTest.put;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real image builds: an AMI built on an EC2 instance from Amazon Linux 2023, its components run by AWSTOE over SSM,
 * tested on an instance launched from the new AMI with a custom test workflow that waits for an approval, then
 * distributed with a templated name, tags and an SSM parameter; and a container image built from a Dockerfile
 * template and pushed to ECR.
 */
@Tag("docker")
@QuarkusTest
@TestProfile(ImageBuilderDockerIntegrationTest.RealInstances.class)
class ImageBuilderDockerIntegrationTest {

    /** Builds need real instances: EC2 instances run as containers. */
    public static class RealInstances implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String TEST_COMPONENT = """
            name: check
            schemaVersion: 1.0
            phases:
              - name: test
                steps:
                  - name: Built
                    action: ExecuteBash
                    inputs:
                      commands:
                        - grep hello /tmp/hello.txt
            """;
    private static final String APPROVAL = """
            schemaVersion: 1.0
            steps:
              - name: LaunchTestInstance
                action: LaunchInstance
                onFailure: Abort
                inputs:
                  waitFor: ssmAgent
              - name: ApplyTestComponents
                action: ExecuteComponents
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
              - name: Approval
                action: WaitForAction
                inputs:
                  payload: "{{ $.stepOutputs.LaunchTestInstance.instanceId }}"
              - name: TerminateTestInstance
                action: TerminateInstance
                onFailure: Continue
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification signed(String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/"
                + service + "/aws4_request");
    }

    private static JsonNode read(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T await(String what, int seconds, Supplier<T> attempt) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            T value = attempt.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(what + " did not happen within " + seconds + "s");
        return null;
    }

    private static JsonNode image(String arn) {
        return read(get("GetImage", "imageBuildVersionArn", arn).extract().asString()).path("image");
    }

    private static String settle(String arn, int seconds) {
        return await("the image " + arn + " to finish", seconds, () -> {
            String status = image(arn).path("state").path("status").asText();
            return ImageBuilderEngine.terminal(status) ? status : null;
        });
    }

    private static String failures(String arn) {
        StringBuilder out = new StringBuilder(image(arn).path("state").toString());
        for (JsonNode execution : read(post("ListWorkflowExecutions", "{\"imageBuildVersionArn\":\"" + arn + "\"}")
                .extract().asString()).path("workflowExecutions")) {
            out.append('\n').append(execution.path("type").asText()).append(' ').append(execution.path("status")
                    .asText()).append(' ').append(execution.path("message").asText());
            for (JsonNode step : read(post("ListWorkflowStepExecutions", "{\"workflowExecutionId\":\""
                    + execution.path("workflowExecutionId").asText() + "\"}").extract().asString()).path("steps")) {
                out.append("\n  ").append(step.path("name").asText()).append(' ').append(step.path("status").asText())
                        .append(' ').append(step.path("message").asText());
            }
        }
        return out.toString();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void anAmiIsBuiltTestedAfterApprovalAndDistributed() {
        signed("iam").contentType("application/x-www-form-urlencoded").formParam("Action", "CreateInstanceProfile")
                .formParam("InstanceProfileName", "image-builder").formParam("Version", "2010-05-08").post("/");
        String build = put("CreateComponent", "{\"name\":\"Hello\",\"semanticVersion\":\"1.0.0\",\"platform\":"
                + "\"Linux\",\"clientToken\":\"b\",\"data\":" + json(ImageBuilderIntegrationTest.COMPONENT) + "}")
                .statusCode(200).extract().path("componentBuildVersionArn");
        String test = put("CreateComponent", "{\"name\":\"Check\",\"semanticVersion\":\"1.0.0\",\"platform\":"
                + "\"Linux\",\"clientToken\":\"t\",\"data\":" + json(TEST_COMPONENT) + "}").statusCode(200)
                .extract().path("componentBuildVersionArn");
        String recipe = put("CreateImageRecipe", "{\"name\":\"Golden\",\"semanticVersion\":\"1.0.0\",\"parentImage\":"
                + "\"arn:aws:imagebuilder:us-east-1:aws:image/amazon-linux-2023-x86/x.x.x\",\"components\":[{"
                + "\"componentArn\":\"" + build + "\"},{\"componentArn\":\"" + test + "\"}],\"amiTags\":{\"layer\":"
                + "\"golden\"},\"clientToken\":\"r\"}").statusCode(200).extract().path("imageRecipeArn");
        String infrastructure = put("CreateInfrastructureConfiguration", "{\"name\":\"Infra\",\"instanceProfileName\""
                + ":\"image-builder\",\"instanceTypes\":[\"t3.micro\"],\"clientToken\":\"i\"}").statusCode(200)
                .extract().path("infrastructureConfigurationArn");
        String distribution = put("CreateDistributionConfiguration", "{\"name\":\"Dist\",\"distributions\":[{"
                + "\"region\":\"us-east-1\",\"amiDistributionConfiguration\":{\"name\":\"golden-{{ imagebuilder:"
                + "buildVersion }}-{{ imagebuilder:buildDate:yyyyMMdd }}\",\"amiTags\":{\"stage\":\"prod\"}},"
                + "\"ssmParameterConfigurations\":[{\"parameterName\":\"/images/golden\"}]}],\"clientToken\":\"d\"}")
                .statusCode(200).extract().path("distributionConfigurationArn");
        String approval = put("CreateWorkflow", "{\"name\":\"Approval\",\"semanticVersion\":\"1.0.0\",\"type\":"
                + "\"TEST\",\"clientToken\":\"w\",\"data\":" + json(APPROVAL) + "}").statusCode(200)
                .extract().path("workflowBuildVersionArn");

        String arn = put("CreateImage", "{\"imageRecipeArn\":\"" + recipe + "\",\"infrastructureConfigurationArn\":\""
                + infrastructure + "\",\"distributionConfigurationArn\":\"" + distribution + "\",\"workflows\":[{"
                + "\"workflowArn\":\"arn:aws:imagebuilder:us-east-1:aws:workflow/build/build-image/x.x.x\"},{"
                + "\"workflowArn\":\"" + approval + "\"}],\"clientToken\":\"img\"}").statusCode(200)
                .extract().path("imageBuildVersionArn");
        assertTrue(arn.endsWith("image/golden/1.0.0/1"), arn);

        JsonNode waiting;
        try {
            waiting = await("the approval step to wait", 360, () -> {
            String status = image(arn).path("state").path("status").asText();
            if (ImageBuilderEngine.terminal(status)) {
                fail("The image ended " + status + " before the approval: " + failures(arn));
            }
            for (JsonNode step : read(post("ListWaitingWorkflowSteps", "{}").extract().asString()).path("steps")) {
                if (arn.equals(step.path("imageBuildVersionArn").asText())) {
                    return step;
                }
            }
            return null;
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + failures(arn), e);
        }
        assertEquals("TESTING", image(arn).path("state").path("status").asText());
        put("SendWorkflowStepAction", "{\"stepExecutionId\":\"" + waiting.path("stepExecutionId").asText()
                + "\",\"imageBuildVersionArn\":\"" + arn + "\",\"action\":\"RESUME\",\"reason\":\"looks good\","
                + "\"clientToken\":\"approve\"}").statusCode(200);
        String status = settle(arn, 180);
        assertEquals("AVAILABLE", status, failures(arn));

        JsonNode image = image(arn);
        assertEquals("Amazon Linux 2023", image.path("osVersion").asText().replaceAll(" [0-9.]+$", "")
                .replace("Amazon Linux", "Amazon Linux 2023").replace("2023 2023", "2023"));
        JsonNode ami = image.path("outputResources").path("amis").path(0);
        assertTrue(ami.path("name").asText().matches("golden-1-\\d{8}"), ami.toString());
        String described = signed("ec2").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeImages").formParam("ImageId.1", ami.path("image").asText())
                .formParam("Version", "2016-11-15").post("/").then().statusCode(200).extract().asString();
        assertTrue(described.contains("<key>stage</key>") && described.contains("<value>prod</value>"), described);
        String parameter = signed("ssm").header("X-Amz-Target", "AmazonSSM.GetParameter")
                .contentType("application/x-amz-json-1.1").body("{\"Name\":\"/images/golden\"}").post("/").then()
                .statusCode(200).extract().path("Parameter.Value");
        assertEquals(ami.path("image").asText(), parameter);
        String packages = post("ListImagePackages", "{\"imageBuildVersionArn\":\"" + arn + "\"}").statusCode(200)
                .extract().asString();
        assertTrue(packages.contains("\"packageName\":\"bash\""), packages);

        List<String> stages = new ArrayList<>();
        for (JsonNode execution : read(post("ListWorkflowExecutions", "{\"imageBuildVersionArn\":\"" + arn + "\"}")
                .extract().asString()).path("workflowExecutions")) {
            stages.add(execution.path("type").asText() + ":" + execution.path("status").asText());
            if ("TEST".equals(execution.path("type").asText())) {
                JsonNode steps = read(post("ListWorkflowStepExecutions", "{\"workflowExecutionId\":\""
                        + execution.path("workflowExecutionId").asText() + "\"}").extract().asString()).path("steps");
                JsonNode wait = steps.path(2);
                assertEquals("Approval", wait.path("name").asText());
                assertTrue(wait.path("outputs").asText().contains("looks good"), wait.toString());
            }
        }
        assertEquals(List.of("BUILD:COMPLETED", "TEST:COMPLETED"), stages);
        String reused = put("CreateImage", "{\"imageRecipeArn\":\"" + recipe + "\",\"infrastructureConfigurationArn\":\""
                + infrastructure + "\",\"imageTestsConfiguration\":{\"imageTestsEnabled\":false},\"clientToken\":"
                + "\"img2\"}").statusCode(200).extract().path("imageBuildVersionArn");
        assertTrue(reused.endsWith("image/golden/1.0.0/2"), reused);
        put("CancelImageCreation", "{\"imageBuildVersionArn\":\"" + reused + "\",\"clientToken\":\"cancel\"}")
                .statusCode(200);
        assertEquals("CANCELLED", settle(reused, 120));
        post("ListImageBuildVersions", "{\"imageVersionArn\":\"" + arn.replaceAll("/1$", "") + "\"}")
                .statusCode(200).body(containsString(reused));
        assertFalse(packages.isEmpty());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aContainerImageIsBuiltAndPushedToEcr() {
        signed("iam").contentType("application/x-www-form-urlencoded").formParam("Action", "CreateInstanceProfile")
                .formParam("InstanceProfileName", "image-builder").formParam("Version", "2010-05-08").post("/");
        signed("ecr").header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.CreateRepository")
                .contentType("application/x-amz-json-1.1").body("{\"repositoryName\":\"golden-app\"}").post("/")
                .then().statusCode(anyOf(is(200), is(400))); // the shared registry container may already hold it
        String build = put("CreateComponent", "{\"name\":\"AppLayer\",\"semanticVersion\":\"1.0.0\",\"platform\":"
                + "\"Linux\",\"clientToken\":\"cb\",\"data\":" + json(ImageBuilderIntegrationTest.COMPONENT) + "}")
                .statusCode(200).extract().path("componentBuildVersionArn");
        String test = put("CreateComponent", "{\"name\":\"AppCheck\",\"semanticVersion\":\"1.0.0\",\"platform\":"
                + "\"Linux\",\"clientToken\":\"ct\",\"data\":" + json(TEST_COMPONENT) + "}").statusCode(200)
                .extract().path("componentBuildVersionArn");
        String recipe = put("CreateContainerRecipe", "{\"containerType\":\"DOCKER\",\"name\":\"App\","
                + "\"semanticVersion\":\"1.0.0\",\"parentImage\":\"public.ecr.aws/amazonlinux/amazonlinux:2023\","
                + "\"components\":[{\"componentArn\":\"" + build + "\"},{\"componentArn\":\"" + test + "\"}],"
                + "\"dockerfileTemplateData\":" + json("FROM {{{ imagebuilder:parentImage }}}\n"
                + "{{{ imagebuilder:environments }}}\n{{{ imagebuilder:components }}}\n") + ",\"targetRepository\":"
                + "{\"service\":\"ECR\",\"repositoryName\":\"golden-app\"},\"clientToken\":\"cr\"}").statusCode(200)
                .extract().path("containerRecipeArn");
        String infrastructure = put("CreateInfrastructureConfiguration", "{\"name\":\"ContainerInfra\","
                + "\"instanceProfileName\":\"image-builder\",\"clientToken\":\"ci\"}").statusCode(200)
                .extract().path("infrastructureConfigurationArn");
        String arn = put("CreateImage", "{\"containerRecipeArn\":\"" + recipe + "\",\"infrastructureConfigurationArn\":\""
                + infrastructure + "\",\"clientToken\":\"cimg\"}").statusCode(200).extract().path("imageBuildVersionArn");
        String status = settle(arn, 480);
        assertEquals("AVAILABLE", status, failures(arn));
        JsonNode container = image(arn).path("outputResources").path("containers").path(0);
        assertTrue(container.path("imageUris").path(0).asText().endsWith("golden-app:1.0.0-1"), container.toString());
        String images = signed("ecr").header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.ListImages")
                .contentType("application/x-amz-json-1.1").body("{\"repositoryName\":\"golden-app\"}").post("/")
                .then().statusCode(200).extract().asString();
        assertTrue(images.contains("1.0.0-1"), images);
    }
}
