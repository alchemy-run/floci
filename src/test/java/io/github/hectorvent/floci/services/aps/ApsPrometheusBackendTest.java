package io.github.hectorvent.floci.services.aps;

import com.github.dockerjava.api.model.MountType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApsPrometheusBackendTest {

    private static final String ARN = "arn:aws:aps:us-east-1:000000000000:workspace/ws-example";
    private ApsPrometheusBackend backend;
    private ContainerLifecycleManager lifecycle;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("amp-test"));
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("2");
        when(config.services().dockerNetwork()).thenReturn(Optional.of("floci-test"));
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), null);
        lifecycle = mock(ContainerLifecycleManager.class);
        client = mock(HttpClient.class);
        backend = new ApsPrometheusBackend(builder, lifecycle, config, client);
    }

    @Test
    void usesRealPinnedPrometheusWithIsolatedEphemeralStorageAndBoundedResources() {
        ContainerSpec spec = backend.spec(ARN, backend.containerName(ARN));
        assertEquals("prom/prometheus:v3.5.0", spec.image());
        assertEquals("floci-amp-test-aps-000000000000-us-east-1-ws-example", spec.name());
        assertEquals("floci-test", spec.networkMode());
        assertEquals(512L * 1024 * 1024, spec.memoryBytes());
        assertEquals(List.of(9090), spec.loopbackPortBindings());
        assertEquals(0, spec.portBindings().get(9090));
        assertTrue(spec.cmd().contains("--web.enable-remote-write-receiver"));
        assertTrue(spec.cmd().contains("--query.timeout=30s"));
        assertTrue(spec.cmd().contains("--config.file=/dev/null"));
        assertEquals(1, spec.mounts().size());
        assertEquals(MountType.TMPFS, spec.mounts().getFirst().getType());
        assertEquals("/prometheus", spec.mounts().getFirst().getTarget());
        assertEquals("000000000000", spec.labels().get("io.floci.account"));
        assertEquals("us-east-1", spec.labels().get("io.floci.region"));
        assertNotEquals(spec.name(), backend.containerName(ARN.replace("us-east-1", "eu-west-1")));
        assertNotEquals(spec.name(), backend.containerName(ARN.replace("000000000000", "111111111111")));
        verifyNoInteractions(lifecycle, client);
    }

    @Test
    void startupFailureCleansOwnedNameAndReturnsFailureRatherThanSuccess() {
        when(lifecycle.createAndStart(any(ContainerSpec.class))).thenThrow(new IllegalStateException("Docker failed"));
        AwsException error = assertThrows(AwsException.class, () -> backend.forward(ARN, "GET",
                "/api/v1/query", "query=up", Map.of(), new byte[0]));
        assertEquals(503, error.getHttpStatus());
        verify(lifecycle).stopAndRemoveStrict(backend.containerName(ARN), null);
        verifyNoInteractions(client);
    }

    @Test
    void deletionCanRecoverContainerOwnershipAfterJvmRestart() {
        backend.remove(ARN);
        verify(lifecycle).removeIfExistsStrict(backend.containerName(ARN));
        verifyNoInteractions(client);
    }

    @Test
    void resetRejectsNewRequestsUntilResumed() {
        backend.beforeReset();
        AwsException error = assertThrows(AwsException.class, () -> backend.forward(ARN, "GET",
                "/api/v1/query", "query=up", Map.of(), new byte[0]));
        assertEquals(503, error.getHttpStatus());
        backend.clear();
        backend.afterReset();
        verifyNoInteractions(lifecycle, client);
    }
}
