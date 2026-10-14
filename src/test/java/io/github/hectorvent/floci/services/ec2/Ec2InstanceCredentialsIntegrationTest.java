package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class Ec2InstanceCredentialsIntegrationTest {
    @Inject
    IamService iam;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imdsCredentialsResolveToTheAttachedRoleAndAreRevokedOnCleanup(boolean stopServer) throws Exception {
        String account = "246813579012";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "worker-" + suffix;
        String profile = "profile-" + suffix;
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", profile)
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", profile)
                .formParam("RoleName", role).post("/").then().statusCode(200);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, iam);
        Instance instance = new Instance();
        instance.setInstanceId("i-credentials");
        instance.setIamInstanceProfileArn("arn:aws:iam::" + account + ":instance-profile/" + profile);
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            server.registerContainer("127.0.0.1", instance.getInstanceId(), instance);
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                    .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, token.statusCode());
            String path = "/latest/meta-data/iam/security-credentials/";
            assertEquals(role, get(client, endpoint + path, token.body()).body());
            assertEquals(404, get(client, endpoint + path + "wrong-role", token.body()).statusCode());
            HttpResponse<String> response = get(client, endpoint + path + role, token.body());
            assertEquals(200, response.statusCode());
            JsonObject credential = new JsonObject(response.body());
            assertEquals("Success", credential.getString("Code"));
            assertEquals("AWS-HMAC", credential.getString("Type"));
            assertTrue(Instant.parse(credential.getString("Expiration")).isAfter(Instant.now()));
            String key = credential.getString("AccessKeyId");
            String sessionToken = credential.getString("Token");
            assertEquals(credential.getString("SecretAccessKey"), iam.findSecretKey(key, sessionToken).orElseThrow());
            assertEquals(account, iam.resolveAccountId(key).orElseThrow());
            assertEquals("arn:aws:sts::" + account + ":assumed-role/" + role + "/i-credentials",
                    iam.resolveCallerArn(key).orElseThrow());
            assertEquals(response.body(), get(client, endpoint + path + role, token.body()).body());
            if (stopServer) {
                server.stop();
            } else {
                server.unregisterInstance(instance);
                assertNotEquals(200, get(client, endpoint + path + role, token.body()).statusCode());
            }
            assertTrue(iam.findSecretKey(key, sessionToken).isEmpty());
            assertTrue(server.registeredContainer("127.0.0.1").isEmpty());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profile)
                    .formParam("RoleName", role).post("/").then().statusCode(200);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteInstanceProfile").formParam("InstanceProfileName", profile)
                    .post("/").then().statusCode(200);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void natCredentialsStayInTheirOwningAccountAndAreRevokedOnRotation() throws Exception {
        List<String> accounts = List.of("135791357913", "246802468024");
        String name = "nat-" + UUID.randomUUID().toString().substring(0, 8);
        for (String account : accounts) {
            given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateRole").formParam("RoleName", name)
                    .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                    .post("/").then().statusCode(200);
            given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                    .post("/").then().statusCode(200);
            given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", name)
                    .formParam("RoleName", name).post("/").then().statusCode(200);
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        when(config.defaultAccountId()).thenReturn("000000000000");
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, iam);
        Instance first = credentialGuest("i-nat-first", accounts.getFirst(), name);
        Instance second = credentialGuest("i-nat-second", accounts.getLast(), name);
        String firstCapability = server.registerProxy(first);
        String secondCapability = server.registerProxy(second);
        String endpoint = "http://127.0.0.1:" + port;
        String path = "/latest/meta-data/iam/security-credentials/";
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            String firstToken = token(client, endpoint, firstCapability);
            String secondToken = token(client, endpoint, secondCapability);
            assertEquals(name, get(client, endpoint + path, firstToken, firstCapability).body());
            assertEquals(name, get(client, endpoint + path, secondToken, secondCapability).body());
            assertEquals(401, get(client, endpoint + path + name, firstToken, secondCapability).statusCode());
            assertEquals(404, get(client, endpoint + path + "wrong-role", firstToken, firstCapability).statusCode());
            HttpResponse<String> firstResponse = get(client, endpoint + path + name, firstToken, firstCapability);
            HttpResponse<String> secondResponse = get(client, endpoint + path + name, secondToken, secondCapability);
            assertEquals(200, firstResponse.statusCode());
            assertEquals(200, secondResponse.statusCode());
            JsonObject firstCredentials = new JsonObject(firstResponse.body());
            JsonObject secondCredentials = new JsonObject(secondResponse.body());
            String firstKey = firstCredentials.getString("AccessKeyId");
            String secondKey = secondCredentials.getString("AccessKeyId");
            assertNotEquals(firstKey, secondKey);
            assertEquals(accounts.getFirst(), iam.resolveAccountId(firstKey).orElseThrow());
            assertEquals(accounts.getLast(), iam.resolveAccountId(secondKey).orElseThrow());
            assertTrue(iam.findSecretKey(firstKey, secondCredentials.getString("Token")).isEmpty());
            assertTrue(iam.findSecretKey(secondKey, firstCredentials.getString("Token")).isEmpty());
            assertEquals("arn:aws:sts::" + accounts.getFirst() + ":assumed-role/" + name + "/i-nat-first",
                    iam.resolveCallerArn(firstKey).orElseThrow());
            String rotated = server.registerProxy(first);
            assertTrue(iam.findSecretKey(firstKey, firstCredentials.getString("Token")).isEmpty());
            assertTrue(iam.findSecretKey(secondKey, secondCredentials.getString("Token")).isPresent());
            assertEquals(401, get(client, endpoint + path + name, firstToken, rotated).statusCode());
            String freshToken = token(client, endpoint, rotated);
            first.setIamInstanceProfileArn(second.getIamInstanceProfileArn());
            assertEquals(404, get(client, endpoint + path + name, freshToken, rotated).statusCode(),
                    "a profile from another account must not override the guest owner");
            server.unregisterInstance(second);
            assertTrue(iam.findSecretKey(secondKey, secondCredentials.getString("Token")).isEmpty());
            assertEquals(401, get(client, endpoint + path + name, secondToken, secondCapability).statusCode());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (String account : accounts) {
                given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                        .formParam("Action", "RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", name)
                        .formParam("RoleName", name).post("/").then().statusCode(200);
                given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                        .formParam("Action", "DeleteInstanceProfile").formParam("InstanceProfileName", name)
                        .post("/").then().statusCode(200);
                given().header("Authorization", auth(account)).contentType("application/x-www-form-urlencoded")
                        .formParam("Action", "DeleteRole").formParam("RoleName", name)
                        .post("/").then().statusCode(200);
            }
        }
    }

    private static Instance credentialGuest(String id, String account, String profile) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setDockerContainerId("container-" + id);
        instance.setIamInstanceProfileArn("arn:aws:iam::" + account + ":instance-profile/" + profile);
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setOwnerId(account);
        instance.setNetworkInterfaces(List.of(eni));
        return instance;
    }

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260921/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String token(HttpClient client, String endpoint, String capability) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                .timeout(Duration.ofSeconds(5)).header(Ec2MetadataServer.PROXY_HEADER, capability)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static HttpResponse<String> get(HttpClient client, String url, String token, String capability) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header(Ec2MetadataServer.PROXY_HEADER, capability)
                .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String url, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
