package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * SageMaker Endpoint bindings coverage: Model + EndpointConfig + Endpoint
 * settle immediately, DescribeEndpoint returns {@code InService} with the
 * production variant, and UpdateEndpointWeightsAndCapacities is a no-wait
 * in-place update.
 */
@QuarkusTest
class SageMakerEndpointBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpoint_unknownName_validationExceptionCouldNotFindEndpoint() {
        invoke("DescribeEndpoint",
                "{\"EndpointName\":\"alchemy-nonexistent-sagemaker-endpoint-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint \""));
    }

    @Test
    void describeModel_unknownName_validationExceptionCouldNotFindModel() {
        invoke("DescribeModel",
                "{\"ModelName\":\"alchemy-nonexistent-sagemaker-model-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find model"));
    }

    @Test
    void modelConfigEndpoint_describeInServiceAndUpdateWeights() {
        String modelName = "BindingsEndpointModel";
        String configName = "BindingsEndpointConfig";
        String endpointName = "BindingsEndpoint";
        String roleArn = "arn:aws:iam::000000000000:role/SageMaker";

        invoke("CreateModel", """
                {
                  "ModelName": "%s",
                  "ExecutionRoleArn": "%s",
                  "PrimaryContainer": {
                    "Image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/dummy:latest"
                  },
                  "Tags": [{"Key": "alchemy::id", "Value": "BindingsEndpointModel"}]
                }
                """.formatted(modelName, roleArn))
                .then()
                .statusCode(200)
                .body("ModelArn", startsWith("arn:aws:sagemaker:"));

        invoke("DescribeModel", "{\"ModelName\":\"" + modelName + "\"}")
                .then()
                .statusCode(200)
                .body("ModelName", equalTo(modelName))
                .body("ExecutionRoleArn", equalTo(roleArn));

        String configArn = invoke("CreateEndpointConfig", """
                {
                  "EndpointConfigName": "%s",
                  "ProductionVariants": [{
                    "VariantName": "AllTraffic",
                    "ModelName": "%s",
                    "ServerlessConfig": {"MemorySizeInMB": 2048, "MaxConcurrency": 1}
                  }],
                  "Tags": [{"Key": "alchemy::id", "Value": "BindingsEndpointConfig"}]
                }
                """.formatted(configName, modelName))
                .then()
                .statusCode(200)
                .body("EndpointConfigArn", startsWith("arn:aws:sagemaker:"))
                .extract().path("EndpointConfigArn");

        invoke("ListTags", "{\"ResourceArn\":\"" + configArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        Response created = invoke("CreateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "%s",
                  "Tags": [{"Key": "alchemy::id", "Value": "BindingsEndpoint"}]
                }
                """.formatted(endpointName, configName));
        created.then()
                .statusCode(200)
                .body("EndpointArn", startsWith("arn:aws:sagemaker:"));
        String endpointArn = created.jsonPath().getString("EndpointArn");

        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + endpointName + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointName", equalTo(endpointName))
                .body("EndpointStatus", equalTo("InService"))
                .body("ProductionVariants.VariantName", hasItem("AllTraffic"))
                .body("ProductionVariants[0].CurrentServerlessConfig.MemorySizeInMB", equalTo(2048));

        invoke("UpdateEndpointWeightsAndCapacities", """
                {
                  "EndpointName": "%s",
                  "DesiredWeightsAndCapacities": [
                    {"VariantName": "AllTraffic", "DesiredWeight": 1}
                  ]
                }
                """.formatted(endpointName))
                .then()
                .statusCode(200)
                .body("EndpointArn", equalTo(endpointArn));

        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + endpointName + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointStatus", equalTo("InService"))
                .body("ProductionVariants[0].DesiredWeight", equalTo(1.0f));

        invoke("DeleteEndpoint", "{\"EndpointName\":\"" + endpointName + "\"}")
                .then()
                .statusCode(200);
        invoke("DeleteEndpointConfig", "{\"EndpointConfigName\":\"" + configName + "\"}")
                .then()
                .statusCode(200);
        invoke("DeleteModel", "{\"ModelName\":\"" + modelName + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
