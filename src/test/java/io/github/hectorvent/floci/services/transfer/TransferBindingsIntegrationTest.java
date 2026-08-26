package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for Alchemy Transfer Bindings.test.ts: missing-server
 * StartServer / ImportSshPublicKey / TestIdentityProvider, missing-workflow
 * SendWorkflowStepState, and SERVICE_MANAGED TestIdentityProvider.
 */
@QuarkusTest
class TransferBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/transfer/aws4_request";
    private static final String MISSING_SERVER = "s-00000000000000000";
    private static final String MISSING_WORKFLOW = "w-1234567890abcdef0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void startServer_missingServer_returnsResourceNotFound() {
        transfer("StartServer", "{\"ServerId\":\"" + MISSING_SERVER + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void importSshPublicKey_missingServer_returnsResourceNotFound() {
        transfer("ImportSshPublicKey", """
                {"ServerId":"%s","UserName":"nobody","SshPublicKeyBody":"ssh-ed25519 AAAA"}
                """.formatted(MISSING_SERVER))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void testIdentityProvider_missingServer_returnsResourceNotFound() {
        transfer("TestIdentityProvider", """
                {"ServerId":"%s","UserName":"nobody","UserPassword":"not-a-real-password"}
                """.formatted(MISSING_SERVER))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void sendWorkflowStepState_missingWorkflow_returnsResourceNotFound() {
        transfer("SendWorkflowStepState", """
                {"WorkflowId":"%s","ExecutionId":"00000000-0000-0000-0000-000000000000","Token":"MA==","Status":"SUCCESS"}
                """.formatted(MISSING_WORKFLOW))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void testIdentityProvider_serviceManaged_returnsInvalidRequest() {
        String serverId = transfer("CreateServer", """
                {"Protocols":["SFTP"],"IdentityProviderType":"SERVICE_MANAGED"}
                """)
                .then()
                .statusCode(200)
                .body("ServerId", notNullValue())
                .extract().path("ServerId");

        transfer("TestIdentityProvider", """
                {"ServerId":"%s","UserName":"alice","UserPassword":"not-a-real-password","ServerProtocol":"SFTP"}
                """.formatted(serverId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    private static Response transfer(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "TransferService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
