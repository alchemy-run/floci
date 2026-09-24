package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.vertx.core.json.JsonObject;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2MultiNetworkMetadataIntegrationTest {
    @Test
    void natGuestsRequireTheirOwnCapabilityAndGenerationBoundToken() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        when(config.defaultAccountId()).thenReturn("000000000000");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-21T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, null, clock);
        Instance first = guest("i-first", "111111111111");
        Instance second = guest("i-second", "222222222222");
        // Neither Docker address is the HTTP client's loopback source after NAT.
        server.registerContainer("172.17.0.2", first.getInstanceId(), first);
        server.registerContainer("172.17.0.3", second.getInstanceId(), second);
        String firstCapability = server.registerProxy(first);
        String secondCapability = server.registerProxy(second);
        assertNotEquals(firstCapability, secondCapability);
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            HttpResponse<String> firstToken = request(client, endpoint, "api/token", firstCapability, null, "60");
            HttpResponse<String> secondToken = request(client, endpoint, "api/token", secondCapability, null, "60");
            assertEquals(200, firstToken.statusCode());
            assertEquals(200, secondToken.statusCode());
            assertEquals("i-first", request(client, endpoint, "meta-data/instance-id", firstCapability, firstToken.body(), null).body());
            assertEquals("i-second", request(client, endpoint, "meta-data/instance-id", secondCapability, secondToken.body(), null).body());
            assertEquals("111111111111", new JsonObject(request(client, endpoint, "dynamic/instance-identity/document",
                    firstCapability, firstToken.body(), null).body()).getString("accountId"));
            assertEquals("222222222222", new JsonObject(request(client, endpoint, "dynamic/instance-identity/document",
                    secondCapability, secondToken.body(), null).body()).getString("accountId"));
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", firstCapability, secondToken.body(), null).statusCode());
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", secondCapability, firstToken.body(), null).statusCode());
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", "forged", firstToken.body(), null).statusCode());
            assertEquals(401, request(client, endpoint, "api/token", "i-first", null, "60").statusCode());
            assertEquals(404, request(client, endpoint, "api/token", null, null, "60").statusCode());
            assertEquals(404, request(client, endpoint, "meta-data/instance-id", null, firstToken.body(), null).statusCode());
            HttpResponse<String> forgedSource = client.send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/latest/meta-data/instance-id"))
                    .timeout(Duration.ofSeconds(5)).header("X-Forwarded-For", "172.17.0.2")
                    .header("X-Floci-Instance-Id", "i-first").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, forgedSource.statusCode());
            server.registerContainer("127.0.0.1", first.getInstanceId(), first);
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", null, firstToken.body(), null).statusCode());
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", "forged", firstToken.body(), null).statusCode());
            for (String ttl : List.of("0", "21601", "no", "-1")) {
                assertEquals(400, request(client, endpoint, "api/token", firstCapability, null, ttl).statusCode());
            }
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", firstCapability, "wrong", null).statusCode());
            assertEquals(200, request(client, endpoint, "meta-data/instance-id", firstCapability, null, null).statusCode());
            LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
            options.setHttpTokens("required");
            first.setMetadataOptions(options);
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", firstCapability, null, null).statusCode());
            now.set(now.get().plusSeconds(60));
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", firstCapability, firstToken.body(), null).statusCode());
            String currentToken = request(client, endpoint, "api/token", firstCapability, null, "60").body();
            String rotated = server.registerProxy(first);
            assertEquals(401, request(client, endpoint, "api/token", firstCapability, null, "60").statusCode());
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", rotated, currentToken, null).statusCode());
            server.unregisterProxy(first, firstCapability);
            assertEquals(200, request(client, endpoint, "api/token", rotated, null, "60").statusCode(),
                    "cleanup of an older launch must not revoke its successor");
            String rotatedToken = request(client, endpoint, "api/token", rotated, null, "60").body();
            first.setDockerContainerId("replacement-container");
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", rotated, rotatedToken, null).statusCode());
            String replacement = server.registerProxy(first);
            String replacementToken = request(client, endpoint, "api/token", replacement, null, "60").body();
            server.unregisterInstance(first);
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", replacement, replacementToken, null).statusCode());
            assertEquals(200, request(client, endpoint, "meta-data/instance-id", secondCapability, null, null).statusCode());
            String beforeRestore = request(client, endpoint, "api/token", secondCapability, null, "60").body();
            Instance restoredModel = guest("i-second", "222222222222");
            String restoredCapability = server.registerProxy(restoredModel);
            server.unregisterInstance(second);
            assertEquals(401, request(client, endpoint, "api/token", secondCapability, null, "60").statusCode());
            assertEquals(401, request(client, endpoint, "meta-data/instance-id", restoredCapability, beforeRestore, null).statusCode());
            assertEquals(200, request(client, endpoint, "api/token", restoredCapability, null, "60").statusCode());
            Instance otherAccount = guest("i-second", "333333333333");
            String otherCapability = server.registerProxy(otherAccount);
            assertEquals("333333333333", new JsonObject(request(client, endpoint, "dynamic/instance-identity/document",
                    otherCapability, null, null).body()).getString("accountId"));
            assertEquals(200, request(client, endpoint, "api/token", restoredCapability, null, "60").statusCode());
            server.stop();
            server.start().get(10, TimeUnit.SECONDS);
            assertEquals(401, request(client, endpoint, "api/token", secondCapability, null, "60").statusCode());
            String restored = server.registerProxy(second);
            assertEquals(200, request(client, endpoint, "api/token", restored, null, "60").statusCode());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static Instance guest(String id, String account) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setDockerContainerId("container-" + id);
        instance.setRegion("us-east-1");
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setOwnerId(account);
        instance.setNetworkInterfaces(List.of(eni));
        return instance;
    }

    private static HttpResponse<String> request(HttpClient client, String endpoint, String path,
                                                String capability, String token, String ttl) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/latest/" + path))
                .timeout(Duration.ofSeconds(5));
        if (capability != null) {
            request.header(Ec2MetadataServer.PROXY_HEADER, capability);
        }
        if (token != null) {
            request.header("X-aws-ec2-metadata-token", token);
        }
        if (ttl != null) {
            request.header("X-aws-ec2-metadata-token-ttl-seconds", ttl).PUT(HttpRequest.BodyPublishers.noBody());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void restoredGuestCanReadMetadataThroughItsSharedNetworkAddress() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, null);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        when(docker.inspectContainerCmd("guest")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getNetworkSettings()).thenReturn(settings);
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("vpc", new ContainerNetwork().withIpv4Address("10.0.1.10"));
        networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.4"));
        // Use loopback for the actual HTTP client's source, standing in for the shared network.
        networks.put("shared", new ContainerNetwork().withIpv4Address("127.0.0.1"));
        when(settings.getNetworks()).thenReturn(networks);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.isContainerRunning("guest")).thenReturn(true);
        Ec2ContainerManager manager = spy(new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                docker, mock(PortAllocator.class), config, server, mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class)));
        AtomicReference<String> capability = new AtomicReference<>();
        doAnswer(call -> {
            capability.set(server.registerProxy(call.getArgument(0)));
            return true;
        }).when(manager).configureLinkLocalMetadataEndpoint(any(), eq("guest"));
        Instance instance = new Instance();
        instance.setInstanceId("i-multinetwork");
        instance.setDockerContainerId("guest");
        instance.setUserData("#!/bin/sh\necho metadata\n");
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            assertTrue(manager.restoreMetadataRegistration(instance));
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                    .header(Ec2MetadataServer.PROXY_HEADER, capability.get())
                    .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, token.statusCode());
            for (Map.Entry<String, String> entry : Map.of("meta-data/instance-id", "i-multinetwork",
                    "user-data", instance.getUserData()).entrySet()) {
                HttpResponse<String> metadata = client.send(HttpRequest.newBuilder(
                                URI.create(endpoint + "/latest/" + entry.getKey()))
                        .header(Ec2MetadataServer.PROXY_HEADER, capability.get())
                        .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token", token.body())
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, metadata.statusCode());
                assertEquals(entry.getValue(), metadata.body());
            }
        } finally {
            manager.stop();
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
