package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * SageMaker EndpointConfig JSON 1.1 coverage used by Alchemy EndpointConfig:
 * typed describe-missing (ValidationException "Could not find endpoint
 * configuration"), create/describe/list/tags/delete, serverless variants,
 * and already-exists.
 */
@QuarkusTest
class SageMakerEndpointConfigIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "alchemy-nonexistent-sagemaker-config-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpointConfig_unknownName_validationExceptionCouldNotFindEndpointConfiguration() {
        invoke("DescribeEndpointConfig", "{\"EndpointConfigName\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint configuration \"" + UNKNOWN + "\""));
    }

    @Test
    void createDescribeTagAndDeleteServerlessEndpointConfig() {
        String name = "floci-sagemaker-endpoint-config";
        String model = "floci-sagemaker-config-model";
        Response created = invoke("CreateEndpointConfig", """
                {
                  "EndpointConfigName": "%s",
                  "ProductionVariants": [{
                    "VariantName": "AllTraffic",
                    "ModelName": "%s",
                    "ServerlessConfig": {"MemorySizeInMB": 1024, "MaxConcurrency": 1}
                  }],
                  "Tags": [{"Key": "purpose", "Value": "alchemy-test"}]
                }
                """.formatted(name, model));
        created.then()
                .statusCode(200)
                .body("EndpointConfigArn", startsWith("arn:aws:sagemaker:"))
                .body("EndpointConfigArn", containsString(":endpoint-config/"));
        String arn = created.jsonPath().getString("EndpointConfigArn");

        invoke("DescribeEndpointConfig", "{\"EndpointConfigName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointConfigName", equalTo(name))
                .body("EndpointConfigArn", equalTo(arn))
                .body("ProductionVariants[0].ModelName", equalTo(model))
                .body("ProductionVariants[0].VariantName", equalTo("AllTraffic"))
                .body("ProductionVariants[0].ServerlessConfig.MemorySizeInMB", equalTo(1024))
                .body("ProductionVariants[0].ServerlessConfig.MaxConcurrency", equalTo(1));

        invoke("ListEndpointConfigs", "{}")
                .then()
                .statusCode(200)
                .body("EndpointConfigs.find { it.EndpointConfigName == '" + name + "' }.EndpointConfigArn",
                        equalTo(arn));

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("AddTags", """
                {"ResourceArn":"%s","Tags":[{"Key":"alchemy::id","Value":"TestConfig"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'alchemy::id' }.Value", equalTo("TestConfig"))
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("CreateEndpointConfig", """
                {
                  "EndpointConfigName": "%s",
                  "ProductionVariants": [{
                    "VariantName": "AllTraffic",
                    "ModelName": "%s",
                    "ServerlessConfig": {"MemorySizeInMB": 1024, "MaxConcurrency": 1}
                  }]
                }
                """.formatted(name, model))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Cannot create already existing endpoint configuration"));

        invoke("DeleteEndpointConfig", "{\"EndpointConfigName\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeEndpointConfig", "{\"EndpointConfigName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint configuration \"" + name + "\""));

        invoke("DeleteEndpointConfig", "{\"EndpointConfigName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint configuration \"" + name + "\""));
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
