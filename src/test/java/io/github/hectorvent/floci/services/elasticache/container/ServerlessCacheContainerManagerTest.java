package io.github.hectorvent.floci.services.elasticache.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerlessCacheContainerManagerTest {

    @Test
    void valkeyServesTlsOnTheEndpointPortsAndPlaintextOnlyOnTheInternalPort() {
        String script = ServerlessCacheContainerManager.valkeyScript();

        assertTrue(script.contains("exec valkey-server --port 6390 --tls-port 6379 "), script);
        assertTrue(script.contains("valkey-server --port 0 --tls-port 6380 "), script);
        assertTrue(script.contains("--replicaof 127.0.0.1 6390 --replica-read-only yes"), script);
        assertTrue(script.contains("--tls-cert-file /tmp/floci-tls/server.crt"), script);
        assertTrue(script.contains("--tls-auth-clients no"), script);
        assertFalse(script.contains("--port 6379"), "6379 must never accept plaintext");
    }

    @Test
    void memcachedServesTlsOnBothEndpointPorts() {
        String script = ServerlessCacheContainerManager.memcachedScript();

        assertTrue(script.contains("exec memcached -Z -o ssl_chain_cert=/tmp/floci-tls/server.crt,"
                + "ssl_key=/tmp/floci-tls/server.key"), script);
        assertTrue(script.contains("-l notls:0.0.0.0:11290,0.0.0.0:11211,0.0.0.0:11212"), script);
    }

    @Test
    void tlsMaterialIsWrittenFromTheEnvironmentWithPrivatePermissions() {
        String script = ServerlessCacheContainerManager.valkeyScript();

        assertTrue(script.startsWith("set -e\numask 077\n"), script);
        assertTrue(script.contains("printf '%s\\n' \"$FLOCI_TLS_KEY\" > /tmp/floci-tls/server.key"), script);
    }

    @Test
    void anEngineThatExitsFailsTheStartAtOnce() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "engine-id", Map.of(ServerlessCacheContainerManager.RESP_INTERNAL_PORT,
                        new ContainerLifecycleManager.EndpointInfo("127.0.0.1", closedPort))));
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(false);
        when(state.getStatus()).thenReturn("exited");
        when(state.getExitCodeLong()).thenReturn(1L);
        when(docker.inspectContainerCmd("engine-id").exec().getState()).thenReturn(state);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elasticache().dockerNetwork()).thenReturn(Optional.empty());
        ServerlessCacheContainerManager manager = new ServerlessCacheContainerManager(containerBuilder,
                lifecycleManager, mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"));

        long started = System.currentTimeMillis();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.tryStart("key",
                "valkey", "valkey/valkey:8", "000000000000", "us-east-1",
                new ServerlessCacheContainerManager.TlsMaterial("cert", "key", "ca")));

        assertTrue(failure.getMessage().contains("exited with code 1"), failure.getMessage());
        assertTrue(System.currentTimeMillis() - started < 10_000, "an exited engine is not waited on");
    }
}
