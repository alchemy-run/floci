package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * SageMaker Runtime restJson1 coverage used by Alchemy InvokeEndpoint:
 * typed ValidationError on a missing endpoint (sync, async, stream) plus a
 * stub invoke against an InService endpoint.
 */
@QuarkusTest
class SageMakerRuntimeIntegrationTest {

    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final String JSON = "application/json";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String UNKNOWN = "alchemy-nonexistent-endpoint-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void invokeEndpoint_unknownName_validationError() {
        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body("{\"instances\":[[0]]}")
                .when()
                .post("/endpoints/" + UNKNOWN + "/invocations")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationError"))
                .body("message", containsString("Could not find endpoint \"" + UNKNOWN + "\""));
    }

    @Test
    void invokeEndpointAsync_unknownName_validationError() {
        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .header("X-Amzn-SageMaker-InputLocation",
                        "s3://alchemy-nonexistent-bucket-probe/input/request.json")
                .when()
                .post("/endpoints/" + UNKNOWN + "/async-invocations")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationError"))
                .body("message", containsString("Could not find endpoint \"" + UNKNOWN + "\""));
    }

    @Test
    void invokeEndpointWithResponseStream_unknownName_validationError() {
        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body("{\"inputs\":\"hello\"}")
                .when()
                .post("/endpoints/" + UNKNOWN + "/invocations-response-stream")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationError"))
                .body("message", containsString("Could not find endpoint \"" + UNKNOWN + "\""));
    }

    @Test
    void invokeEndpoint_existingEndpoint_echoesBody() {
        String name = "floci-sagemaker-runtime-endpoint";
        sagemaker("CreateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "floci-sagemaker-runtime-config"
                }
                """.formatted(name))
                .then()
                .statusCode(200);

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .body("{\"instances\":[[1,2,3]]}")
                .when()
                .post("/endpoints/" + name + "/invocations")
                .then()
                .statusCode(200)
                .header("x-Amzn-Invoked-Production-Variant", equalTo("AllTraffic"))
                .body("instances[0][0]", equalTo(1));

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .header("X-Amzn-SageMaker-InputLocation",
                        "s3://alchemy-runtime-bucket/input/request.json")
                .when()
                .post("/endpoints/" + name + "/async-invocations")
                .then()
                .statusCode(202)
                .header("X-Amzn-SageMaker-OutputLocation", startsWith("s3://"))
                .body("InferenceId", containsString("-"));

        sagemaker("DeleteEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
    }

    @Test
    void invokeEndpointAsync_missingInputLocation_validationError() {
        String name = "floci-sagemaker-runtime-async-missing-input";
        sagemaker("CreateEndpoint", """
                {
                  "EndpointName": "%s",
                  "EndpointConfigName": "floci-sagemaker-runtime-config"
                }
                """.formatted(name))
                .then()
                .statusCode(200);

        given()
                .contentType(JSON)
                .header("Authorization", AUTH)
                .when()
                .post("/endpoints/" + name + "/async-invocations")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationError"))
                .body("message", containsString("InputLocation"));

        sagemaker("DeleteEndpoint", "{\"EndpointName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
    }

    private static io.restassured.response.Response sagemaker(String action, String body) {
        return given()
                .contentType(JSON11)
                .header("X-Amz-Target", "SageMaker." + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
