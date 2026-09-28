package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level dispatch of {@code TestIdentityProvider} and {@code SendWorkflowStepState}: both used
 * to fall through to {@code UnknownOperationException}.
 */
@QuarkusTest
class TransferCallbackOperationsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void testIdentityProviderOnUnknownServerIsResourceNotFound() {
        given()
            .header("X-Amz-Target", "TransferService.TestIdentityProvider")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\":\"s-00000000000000000\",\"UserName\":\"nobody\",\"UserPassword\":\"pw\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void testIdentityProviderOnServiceManagedServerIsInvalidRequest() {
        String serverId = given()
            .header("X-Amz-Target", "TransferService.CreateServer")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ServerId");

        given()
            .header("X-Amz-Target", "TransferService.TestIdentityProvider")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\":\"" + serverId + "\",\"UserName\":\"alice\",\"UserPassword\":\"pw\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void sendWorkflowStepStateWithMalformedTokenIsValidationException() {
        given()
            .header("X-Amz-Target", "TransferService.SendWorkflowStepState")
            .contentType(CONTENT_TYPE)
            .body("{\"WorkflowId\":\"w-1234567890abcdef0\","
                    + "\"ExecutionId\":\"00000000-0000-0000-0000-000000000000\","
                    + "\"Token\":\"MA==\",\"Status\":\"SUCCESS\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", startsWith("1 validation error detected"));
    }

    @Test
    void sendWorkflowStepStateOnUnknownWorkflowIsResourceNotFound() {
        given()
            .header("X-Amz-Target", "TransferService.SendWorkflowStepState")
            .contentType(CONTENT_TYPE)
            .body("{\"WorkflowId\":\"w-1234567890abcdef0\","
                    + "\"ExecutionId\":\"00000000-0000-0000-0000-000000000000\","
                    + "\"Token\":\"abc123\",\"Status\":\"SUCCESS\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("Resource", equalTo("w-1234567890abcdef0"))
            .body("ResourceType", equalTo("Workflow"));
    }
}
