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
 * SageMaker Endpoint JSON 1.1 coverage used by Alchemy Endpoint: typed
 * describe-missing (ValidationException "Could not find endpoint \""),
 * create/describe/list/tags/update/delete, and already-exists.
 */
@QuarkusTest
class SageMakerEndpointIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "alchemy-nonexistent-sagemaker-endpoint-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpoint_unknownName_validationExceptionCouldNotFindEndpoint() {
        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint \"" + UNKNOWN + "\""));
    }

    @Test
    void createDescribeTagUpdateAndDeleteEndpoint() {
        String name = "floci-sagemaker-endpoint";
        String config = "floci-sagemaker-endpoint-config";
        Response created = invoke("CreateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "%s",
                  "Tags": [{"Key": "purpose", "Value": "alchemy-test"}]
                }
                """.formatted(name, config));
        created.then()
                .statusCode(200)
                .body("EndpointArn", startsWith("arn:aws:sagemaker:"));
        String arn = created.jsonPath().getString("EndpointArn");

        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointName", equalTo(name))
                .body("EndpointArn", equalTo(arn))
                .body("EndpointConfigName", equalTo(config))
                .body("EndpointStatus", equalTo("InService"));

        invoke("ListEndpoints", "{}")
                .then()
                .statusCode(200)
                .body("Endpoints.find { it.EndpointName == '" + name + "' }.EndpointStatus",
                        equalTo("InService"));

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("AddTags", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"local"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("CreateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "%s"
                }
                """.formatted(name, config))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Cannot create already existing endpoint"));

        invoke("UpdateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "%s-v2"
                }
                """.formatted(name, config))
                .then()
                .statusCode(200)
                .body("EndpointArn", equalTo(arn));

        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointConfigName", equalTo(config + "-v2"))
                .body("EndpointStatus", equalTo("InService"));

        invoke("DeleteEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        invoke("DescribeEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint \"" + name + "\""));

        invoke("DeleteEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find endpoint \"" + name + "\""));
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
