package io.github.hectorvent.floci.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerClientProducer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real source-mode Docker transport without booting Quarkus or an AWS provider. Only configuration
 * is stubbed; DNS, the gateway socket, image preparation, helper and container lifecycle are real.
 */
@EnabledIfEnvironmentVariable(named = "FLOCI_DNS_SOURCE_ENABLED", matches = "(?i)true")
class SourceNetworkHelperDockerIntegrationTest {

    private static final String AWS_HOST = "sync-states.us-east-1.amazonaws.com";
    private static final int EXEC_TIMEOUT_SECONDS = 12;

    @TempDir
    Path tempDir;

    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private SourceNetworkHelper helper;
    private String helperId;
    private EmbeddedDnsServer dns;
    private final List<String> dnsQueries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Vertx vertx;
    private HttpServer gateway;
    private ExecutorService gatewayExecutor;
    private final List<String> containers = new ArrayList<>();
    private final List<String> networks = new ArrayList<>();

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void relaysDnsAndBothGatewayPortsThenRemovesOnlyItsOwnedHelper() throws Exception {
        ContainerDetector detector = new ContainerDetector();
        assumeFalse(detector.isRunningInContainer(), "source-mode test must run on the Docker Desktop host");
        String suffix = UUID.randomUUID().toString();
        String namespace = System.getenv().getOrDefault("FLOCI_DOCKER_RESOURCE_NAMESPACE", "test")
                + "-source-network-it-" + suffix;
        String networkName = "floci-source-network-it-" + suffix;
        AtomicInteger requests = prepareGateway();
        int gatewayPort = gateway.getAddress().getPort();
        assertEquals("127.0.0.1", gateway.getAddress().getAddress().getHostAddress());
        assertTrue(gatewayPort >= 1024);

        EmulatorConfig config = config(namespace, networkName, gatewayPort);
        Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
        String ownedNamespace = labels.get("floci_namespace");
        docker = new DockerClientProducer(config).dockerClient();
        docker.pingCmd().exec();
        assertEquals("linux", docker.infoCmd().exec().getOsType());
        lifecycle = new ContainerLifecycleManager(docker, new ImageCacheService(docker, config),
                detector, new PortAllocator(docker), config);
        String networkId = createNetwork(networkName, labels);
        String sentinelNetworkId = createNetwork(networkName + "-sentinel", Map.of(
                "floci", "true", "floci_emulator", "floci-aws", "floci_namespace", ownedNamespace + "-sentinel"));

        DockerHostResolver resolver = new DockerHostResolver(config, detector);
        ContainerBuilder helperBuilder = new ContainerBuilder(config, resolver, null);
        AtomicInteger loopbackDnsPort = new AtomicInteger();
        helper = new SourceNetworkHelper(config, helperBuilder, lifecycle) {
            @Override
            public synchronized String start(int port) {
                loopbackDnsPort.set(port);
                return super.start(port);
            }
        };
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(2));
        // The constructor waits for listeners and a validated DNS roundtrip through the host.
        dns = new EmbeddedDnsServer(config, detector, vertx, helper) {
            @Override
            Optional<String> resolveARecord(String name, String myIp) {
                dnsQueries.add(name);
                return super.resolveARecord(name, myIp);
            }
        };
        assertTrue(loopbackDnsPort.get() >= 1024);
        String helperAddress = dns.getServerIp().orElseThrow();
        gateway.start();
        var ownedHelpers = docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                "floci_namespace", ownedNamespace, "floci.source-network-helper", "true")).exec();
        assertEquals(1, ownedHelpers.size());
        helperId = ownedHelpers.getFirst().getId();
        var helperState = docker.inspectContainerCmd(helperId).exec();
        assertEquals(helperAddress, helperState.getNetworkSettings().getNetworks().get(networkName).getIpAddress());
        assertFalse(Boolean.TRUE.equals(helperState.getHostConfig().getPrivileged()));
        assertTrue(helperState.getHostConfig().getPortBindings() == null
                || helperState.getHostConfig().getPortBindings().getBindings().isEmpty());
        assertNotNull(helperState.getConfig().getLabels().get("floci.source-network-owner"));
        assertEquals("ready", exec(helperId, "sh", "-ec",
                "test -n \"$(ss -H -lun 'sport = :53')\"; "
                        + "test -n \"$(ss -H -ltn 'sport = :443')\"; "
                        + "test -n \"$(ss -H -ltn 'sport = :" + gatewayPort + "')\"; printf ready").trim());

        ContainerBuilder clientBuilder = new ContainerBuilder(config, resolver, dns);
        ContainerSpec clientSpec = clientBuilder.newContainer(SourceNetworkHelper.DEFAULT_IMAGE)
                .withName(ContainerStorageHelper.dockerName(config, "floci-source-client-" + suffix))
                .withNetworkMode(networkName)
                .withEmbeddedDns()
                .withMemoryMb(32)
                .withEntrypoint(List.of("sleep"))
                .withCmd(List.of("180"))
                .withLabels(labels)
                .build();
        String clientId = startContainer(clientSpec);
        assertEquals(List.of(helperAddress), clientSpec.dnsServers());
        assertEquals(List.of(helperAddress), Arrays.asList(docker.inspectContainerCmd(clientId).exec().getHostConfig().getDns()));
        ContainerSpec sentinelSpec = helperBuilder.newContainer(SourceNetworkHelper.DEFAULT_IMAGE)
                .withName("floci-source-sentinel-" + suffix)
                .withNetworkMode(networkName + "-sentinel")
                .withMemoryMb(16)
                .withEntrypoint(List.of("sleep"))
                .withCmd(List.of("180"))
                .withLabels(Map.of("floci_namespace", ownedNamespace + "-sentinel",
                        "floci.source-network-helper", "true", "floci.source-network-owner", "sentinel-" + suffix))
                .build();
        String sentinelId = startContainer(sentinelSpec);
        Set<String> sentinelEndpoints = Set.copyOf(docker.inspectNetworkCmd().withNetworkId(sentinelNetworkId)
                .exec().getContainers().keySet());
        assertTrue(sentinelEndpoints.contains(sentinelId));

        for (String name : List.of(AWS_HOST, "localhost.floci.io", "bucket.localhost.floci.io")) {
            byte[] response = queryDns(clientId, helperAddress, name, 1);
            assertEquals(1, Short.toUnsignedInt(ByteBuffer.wrap(response).getShort(6)));
            assertArrayEquals(InetAddress.getByName(helperAddress).getAddress(),
                    Arrays.copyOfRange(response, response.length - 4, response.length));
        }
        byte[] ipv6 = queryDns(clientId, helperAddress, AWS_HOST, 28);
        assertEquals(0, Short.toUnsignedInt(ByteBuffer.wrap(ipv6).getShort(6)));

        if (Boolean.parseBoolean(System.getenv("FLOCI_SOURCE_NETWORK_PUBLIC_DNS"))) {
            byte[] publicResponse = queryDns(clientId, helperAddress, "example.com", 1);
            List<String> publicAddresses = ipv4Answers(publicResponse);
            assertFalse(publicAddresses.isEmpty(), "public DNS must return an upstream A record");
            assertFalse(publicAddresses.contains(helperAddress),
                    "public names must be forwarded upstream, not treated as Floci-owned names");
        }

        String path = "/wire%2Fprobe?X-Amz-Signature=opaque%2Fvalue&x=1";
        String payload = "source-network-body:" + suffix + "=%2F&not-json";
        String expected = "POST\n" + AWS_HOST + "\n" + path + "\n" + payload;
        for (int port : List.of(443, gatewayPort)) {
            String body = exec(clientId, "curl", "--silent", "--show-error", "--fail",
                    "--noproxy", "*", "--http1.1", "--path-as-is", "--connect-timeout", "2", "--max-time", "5",
                    "--request", "POST", "--header", "Host: " + AWS_HOST,
                    "--data-binary", payload, "http://" + AWS_HOST + ":" + port + path);
            assertEquals(expected, body, "TCP relay must preserve the host, raw path/query and body on port " + port);
        }
        String runtime = exec(clientId, "curl", "--silent", "--show-error", "--fail", "--noproxy", "*",
                "--connect-timeout", "2", "--max-time", "5",
                "http://host.docker.internal:" + gatewayPort + "/runtime-check");
        assertEquals("GET\nhost.docker.internal:" + gatewayPort + "\n/runtime-check\n", runtime);
        assertEquals(3, requests.get());

        dns.stop();
        helper.stop();
        assertTrue(dns.getServerIp().isEmpty());
        assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(helperId).exec());
        assertTrue(Boolean.TRUE.equals(docker.inspectContainerCmd(clientId).exec().getState().getRunning()));
        assertTrue(Boolean.TRUE.equals(docker.inspectContainerCmd(sentinelId).exec().getState().getRunning()));
        assertEquals(sentinelEndpoints, docker.inspectNetworkCmd().withNetworkId(sentinelNetworkId)
                .exec().getContainers().keySet());
        var remainingNetwork = docker.inspectNetworkCmd().withNetworkId(networkId).exec();
        assertEquals(labels, remainingNetwork.getLabels());
        assertEquals(Set.of(clientId), remainingNetwork.getContainers().keySet());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/still-owned"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "helper shutdown must not close the host gateway listener");
        }
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void unreachableDnsUpstreamFailsConstructionAndRemovesOnlyTheOwnedHelper() throws Exception {
        ContainerDetector detector = new ContainerDetector();
        assumeFalse(detector.isRunningInContainer(), "source-mode test must run on the Docker host");
        String suffix = UUID.randomUUID().toString();
        String namespace = System.getenv().getOrDefault("FLOCI_DOCKER_RESOURCE_NAMESPACE", "test")
                + "-source-network-unreachable-it-" + suffix;
        String networkName = "floci-source-network-unreachable-it-" + suffix;
        EmulatorConfig config = config(namespace, networkName, 4566);
        Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
        String ownedNamespace = labels.get("floci_namespace");
        docker = new DockerClientProducer(config).dockerClient();
        docker.pingCmd().exec();
        lifecycle = new ContainerLifecycleManager(docker, new ImageCacheService(docker, config),
                detector, new PortAllocator(docker), config) {
            @Override
            public String create(ContainerSpec spec) {
                String id = super.create(spec);
                if (ownedNamespace.equals(spec.labels().get("floci_namespace"))
                        && "true".equals(spec.labels().get("floci.source-network-helper"))) {
                    helperId = id;
                }
                return id;
            }
        };
        String networkId = createNetwork(networkName, labels);
        Map<String, String> sentinelLabels = Map.of("floci_namespace", ownedNamespace + "-sentinel",
                "floci.source-network-helper", "true", "floci.source-network-owner", "sentinel-" + suffix);
        String sentinelNetworkId = createNetwork(networkName + "-sentinel", sentinelLabels);
        AtomicReference<String> sentinelId = new AtomicReference<>();
        AtomicReference<Set<String>> sentinelEndpoints = new AtomicReference<>();
        DockerHostResolver resolver = new DockerHostResolver(config, detector);
        ContainerBuilder builder = new ContainerBuilder(config, resolver, null);
        helper = new SourceNetworkHelper(config, builder, lifecycle) {
            @Override
            public synchronized String start(int port) {
                String address = super.start(port);
                ContainerSpec sentinel = builder.newContainer(SourceNetworkHelper.DEFAULT_IMAGE)
                        .withName("floci-source-unreachable-sentinel-" + suffix)
                        .withNetworkMode(networkName + "-sentinel")
                        .withMemoryMb(16)
                        .withEntrypoint(List.of("sleep"))
                        .withCmd(List.of("180"))
                        .withLabels(sentinelLabels)
                        .build();
                sentinelId.set(startContainer(sentinel));
                sentinelEndpoints.set(Set.copyOf(docker.inspectNetworkCmd().withNetworkId(sentinelNetworkId)
                        .exec().getContainers().keySet()));
                try {
                    // Only the owned helper's upstream is redirected to a loopback port with no UDP listener.
                    exec(helperId, "sh", "-ec", "test -n \"$(ss -H -lun 'sport = :53')\"; "
                            + "test -z \"$(ss -H -lun 'sport = :" + port + "')\"; "
                            + "printf '127.0.0.1 host.docker.internal\\n' > /etc/hosts");
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot isolate the owned DNS upstream", e);
                }
                return address;
            }
        };
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(2));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EmbeddedDnsServer(config, detector, vertx, helper));
        assertNotNull(helperId, "the failure must occur after helper creation");
        assertTrue(failure.getCause().getMessage().startsWith("Source network helper DNS"), failure::toString);
        assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(helperId).exec());
        helper.stop();
        assertTrue(Boolean.TRUE.equals(docker.inspectContainerCmd(sentinelId.get()).exec().getState().getRunning()));
        assertEquals(sentinelEndpoints.get(), docker.inspectNetworkCmd().withNetworkId(sentinelNetworkId)
                .exec().getContainers().keySet());
        assertEquals(sentinelLabels, docker.inspectNetworkCmd().withNetworkId(sentinelNetworkId).exec().getLabels());
        assertTrue(docker.inspectNetworkCmd().withNetworkId(networkId).exec().getContainers().isEmpty());
        assertEquals(labels, docker.inspectNetworkCmd().withNetworkId(networkId).exec().getLabels());
        assertNull(gateway, "DNS failure detection must not depend on starting the host gateway");
    }

    private AtomicInteger prepareGateway() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        gatewayExecutor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "source-network-test-http");
            thread.setDaemon(true);
            return thread;
        });
        gateway.setExecutor(gatewayExecutor);
        gateway.createContext("/", exchange -> {
            try {
                requests.incrementAndGet();
                String body = exchange.getRequestMethod() + "\n" + exchange.getRequestHeaders().getFirst("Host")
                        + "\n" + exchange.getRequestURI().toASCIIString() + "\n"
                        + new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        return requests;
    }

    private EmulatorConfig config(String namespace, String network, int gatewayPort) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(gatewayPort);
        when(config.dns().sourceEnabled()).thenReturn(true);
        when(config.dns().sourceHelperImage()).thenReturn(SourceNetworkHelper.DEFAULT_IMAGE);
        when(config.dns().containerFallbackServers()).thenReturn(List.of("1.1.1.1", "8.8.8.8"));
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(0);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.services().lambda().dockerNetwork()).thenReturn(Optional.of(network));
        when(config.docker().dockerHost()).thenReturn(
                System.getenv().getOrDefault("FLOCI_DOCKER_DOCKER_HOST", "unix:///var/run/docker.sock"));
        when(config.docker().dockerConfigPath()).thenReturn(Optional.ofNullable(System.getenv("FLOCI_DOCKER_DOCKER_CONFIG_PATH")));
        when(config.docker().imageRegistryBase()).thenReturn(Optional.ofNullable(System.getenv("FLOCI_DOCKER_IMAGE_REGISTRY_BASE")));
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(namespace));
        when(config.docker().registryCredentials()).thenReturn(List.of());
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().logMaxSize()).thenReturn("1m");
        when(config.docker().logMaxFile()).thenReturn("1");
        return config;
    }

    private String createNetwork(String name, Map<String, String> labels) {
        String id = docker.createNetworkCmd().withName(name).withDriver("bridge").withLabels(labels).exec().getId();
        networks.add(id);
        return id;
    }

    private String startContainer(ContainerSpec spec) {
        String id = lifecycle.create(spec);
        containers.add(id);
        lifecycle.startCreated(id, spec);
        return id;
    }

    private String exec(String containerId, String... command) throws Exception {
        String execId = docker.execCreateCmd(containerId).withAttachStdout(true).withAttachStderr(true)
                .withCmd(command).exec().getId();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
            assertTrue(docker.execStartCmd(execId).exec(callback).awaitCompletion(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "container command timed out: " + Arrays.toString(command));
        }
        assertEquals(0L, docker.inspectExecCmd(execId).exec().getExitCodeLong(),
                () -> "command failed: " + Arrays.toString(command) + "\n" + stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private byte[] queryDns(String clientId, String server, String name, int type) throws Exception {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort((short) 0x1234).putShort((short) 0x0100).putShort((short) 1);
        query.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) bytes.length).put(bytes);
        }
        query.put((byte) 0).putShort((short) type).putShort((short) 1);
        StringBuilder octal = new StringBuilder();
        for (byte value : Arrays.copyOf(query.array(), query.position())) {
            octal.append(String.format("\\0%03o", Byte.toUnsignedInt(value)));
        }
        String encoded = exec(clientId, "sh", "-ec", "set -o pipefail; printf '%b' '" + octal
                + "' | timeout 8 socat -d -d -T 5 -t 5 - UDP4:" + server + ":53,shut-none 2>/tmp/dns-client.log | base64");
        byte[] response = Base64.getMimeDecoder().decode(encoded);
        if (response.length < 12) {
            ByteArrayOutputStream logs = new ByteArrayOutputStream();
            try (ExecStartResultCallback callback = new ExecStartResultCallback(logs, logs)) {
                docker.logContainerCmd(helperId).withStdOut(true).withStdErr(true).withTail(100)
                        .exec(callback).awaitCompletion(5, TimeUnit.SECONDS);
            }
            fail("missing UDP DNS response for " + name + "; queries=" + dnsQueries + "; helper logs="
                    + logs.toString(StandardCharsets.UTF_8) + "; client logs=" + exec(clientId, "cat", "/tmp/dns-client.log"));
        }
        assertTrue(response.length >= 12, () -> "missing UDP DNS response for " + name
                + "; received queries: " + dnsQueries
                + "; helper state: " + docker.inspectContainerCmd(helperId).exec().getState());
        assertEquals(0x1234, Short.toUnsignedInt(ByteBuffer.wrap(response).getShort(0)));
        assertEquals(0, response[3] & 0x0f, "DNS response code for " + name);
        return response;
    }

    private List<String> ipv4Answers(byte[] response) throws Exception {
        ByteBuffer packet = ByteBuffer.wrap(response);
        int questions = Short.toUnsignedInt(packet.getShort(4));
        int answers = Short.toUnsignedInt(packet.getShort(6));
        packet.position(12);
        for (int i = 0; i < questions; i++) {
            skipDnsName(packet);
            packet.position(packet.position() + 4);
        }
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < answers; i++) {
            skipDnsName(packet);
            int type = Short.toUnsignedInt(packet.getShort());
            packet.getShort();
            packet.getInt();
            int length = Short.toUnsignedInt(packet.getShort());
            int start = packet.position();
            if (type == 1 && length == 4) {
                addresses.add(InetAddress.getByAddress(Arrays.copyOfRange(response, start, start + length)).getHostAddress());
            }
            packet.position(start + length);
        }
        return addresses;
    }

    private void skipDnsName(ByteBuffer packet) {
        int length;
        while ((length = Byte.toUnsignedInt(packet.get())) != 0) {
            if ((length & 0xc0) == 0xc0) {
                packet.get();
                return;
            }
            assertTrue(length <= 63, "invalid DNS label length");
            packet.position(packet.position() + length);
        }
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        List<Executable> cleanup = new ArrayList<>();
        if (dns != null) {
            cleanup.add(dns::stop);
        } else if (helper != null) {
            cleanup.add(helper::stop);
        }
        for (String id : containers.reversed()) {
            cleanup.add(() -> {
                lifecycle.stopAndRemoveStrict(id, null);
                assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(id).exec());
            });
        }
        for (String id : networks.reversed()) {
            cleanup.add(() -> {
                docker.removeNetworkCmd(id).exec();
                assertThrows(NotFoundException.class, () -> docker.inspectNetworkCmd().withNetworkId(id).exec());
            });
        }
        if (gateway != null) {
            cleanup.add(() -> gateway.stop(0));
        }
        if (gatewayExecutor != null) {
            cleanup.add(() -> {
                gatewayExecutor.shutdownNow();
                assertTrue(gatewayExecutor.awaitTermination(2, TimeUnit.SECONDS));
            });
        }
        if (vertx != null) {
            cleanup.add(() -> vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
        if (docker != null) {
            cleanup.add(docker::close);
        }
        assertAll("owned source networking fixture cleanup", cleanup);
    }
}
