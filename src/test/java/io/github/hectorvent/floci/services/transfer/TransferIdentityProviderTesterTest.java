package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.transfer.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferIdentityProviderTesterTest {

    private static final String SERVER_ID = "s-0123456789abcdef0";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:idp";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TransferService transferService;
    private LambdaService lambdaService;
    private TransferIdentityProviderTester tester;

    @BeforeEach
    void setUp() {
        transferService = Mockito.mock(TransferService.class);
        lambdaService = Mockito.mock(LambdaService.class);
        tester = new TransferIdentityProviderTester(transferService, lambdaService, objectMapper, 4566,
                HttpClient.newHttpClient());
    }

    private Server server(String identityProviderType, Map<String, String> details) {
        Server server = new Server();
        server.setServerId(SERVER_ID);
        server.setIdentityProviderType(identityProviderType);
        server.setIdentityProviderDetails(details);
        when(transferService.getServer(SERVER_ID)).thenReturn(server);
        return server;
    }

    @Test
    void unknownServerSurfacesResourceNotFound() {
        when(transferService.getServer("s-00000000000000000"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Unknown server", 404));
        AwsException e = assertThrows(AwsException.class, () -> tester.testIdentityProvider(
                "s-00000000000000000", null, null, "nobody", "not-a-real-password", "us-east-1"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void serviceManagedServerIsRejected() {
        server("SERVICE_MANAGED", null);
        AwsException e = assertThrows(AwsException.class, () -> tester.testIdentityProvider(
                SERVER_ID, null, null, "alice", "pw", "us-east-1"));
        assertEquals("InvalidRequestException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        verifyNoInteractions(lambdaService);
    }

    @Test
    void malformedServerIdFailsValidationBeforeLookup() {
        AwsException e = assertThrows(AwsException.class, () -> tester.testIdentityProvider(
                "server-1", null, null, "alice", "pw", "us-east-1"));
        assertEquals("ValidationException", e.getErrorCode());
        // AWS reports every violated constraint: 'server-1' breaks both the length and the pattern.
        assertTrue(e.getMessage().startsWith("2 validation errors detected: Value 'server-1' at 'serverId' "
                + "failed to satisfy constraint: Member must have length greater than or equal to 19"), e.getMessage());
        assertTrue(e.getMessage().contains("Member must satisfy regular expression pattern: ^s-([0-9a-f]{17})$"),
                e.getMessage());
        verifyNoInteractions(transferService);
    }

    @Test
    void passwordNeverAppearsInValidationMessages() {
        String longPassword = "p".repeat(1025);
        AwsException e = assertThrows(AwsException.class, () -> tester.testIdentityProvider(
                SERVER_ID, null, null, "alice", longPassword, "us-east-1"));
        assertEquals("ValidationException", e.getErrorCode());
        assertFalse(e.getMessage().contains(longPassword));
    }

    @Test
    void lambdaProviderIsInvokedWithTransferIdentityEvent() throws Exception {
        server("AWS_LAMBDA", Map.of("Function", FUNCTION_ARN));
        byte[] payload = "{\"Role\":\"arn:aws:iam::000000000000:role/sftp\",\"HomeDirectory\":\"/bucket\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION_ARN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, payload, null, "req-1"));

        ObjectNode response = tester.testIdentityProvider(
                SERVER_ID, "SFTP", "10.0.0.1", "alice", "secret", "us-east-1");

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq(FUNCTION_ARN), event.capture(),
                eq(InvocationType.RequestResponse));
        JsonNode sent = objectMapper.readTree(event.getValue());
        assertEquals("alice", sent.path("username").asText());
        assertEquals("secret", sent.path("password").asText());
        assertEquals("SFTP", sent.path("protocol").asText());
        assertEquals(SERVER_ID, sent.path("serverId").asText());
        assertEquals("10.0.0.1", sent.path("sourceIp").asText());

        assertEquals(200, response.path("StatusCode").asInt());
        assertEquals(new String(payload, StandardCharsets.UTF_8), response.path("Response").asText());
        assertEquals("", response.path("Message").asText());
        assertEquals(FUNCTION_ARN, response.path("Url").asText());
    }

    @Test
    void lambdaFunctionErrorIsReportedNotHidden() {
        server("AWS_LAMBDA", Map.of("Function", FUNCTION_ARN));
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION_ARN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Unhandled",
                        "{\"errorMessage\":\"boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-2"));

        ObjectNode response = tester.testIdentityProvider(SERVER_ID, null, null, "alice", "pw", "us-east-1");

        assertTrue(response.path("Message").asText().contains("Unhandled"));
        assertTrue(response.path("Message").asText().contains("boom"));
    }

    @Test
    void apiGatewayProviderIsCalledWithTransferIdentityRequest() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> password = new AtomicReference<>();
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getQuery());
            password.set(exchange.getRequestHeaders().getFirst("Password"));
            byte[] body = "{\"Role\":\"arn:aws:iam::000000000000:role/sftp\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        idp.start();
        try {
            String url = "http://127.0.0.1:" + idp.getAddress().getPort() + "/prod/";
            server("API_GATEWAY", Map.of("Url", url));

            ObjectNode response = tester.testIdentityProvider(
                    SERVER_ID, "FTPS", "10.0.0.2", "alice", "secret", "us-east-1");

            assertEquals("/prod/servers/" + SERVER_ID + "/users/alice/config", path.get());
            assertEquals("protocol=FTPS&sourceIp=10.0.0.2", query.get());
            assertEquals("secret", password.get());
            assertEquals(200, response.path("StatusCode").asInt());
            assertEquals("{\"Role\":\"arn:aws:iam::000000000000:role/sftp\"}", response.path("Response").asText());
            assertTrue(response.path("Url").asText().endsWith("/prod/servers/" + SERVER_ID + "/users/alice/config"
                    + "?protocol=FTPS&sourceIp=10.0.0.2"));
        } finally {
            idp.stop(0);
        }
    }

    @Test
    void awsExecuteApiHostsRouteToFlociPathStylePlane() {
        assertEquals("http://localhost:4566/execute-api/abc123/prod/servers/s-1/users/a/config?protocol=SFTP",
                tester.resolveLocally(
                        "https://abc123.execute-api.us-east-1.amazonaws.com/prod/servers/s-1/users/a/config?protocol=SFTP"));
        assertEquals("http://idp.example.test/prod", tester.resolveLocally("http://idp.example.test/prod"));
    }
}
