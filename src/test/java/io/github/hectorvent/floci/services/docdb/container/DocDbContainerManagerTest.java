package io.github.hectorvent.floci.services.docdb.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import com.github.dockerjava.api.DockerClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocDbContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(DocDbContainerManagerTest.class);
    private static final String MEMBER_HOST = "cluster1.cluster-abcdefghijkl.us-east-1.docdb.localhost.floci.io";

    @Test
    void tryStartReportsUnavailableInsteadOfThrowingWhenNoDockerDaemonIsReachable() {
        // Floci running inside Docker without a mounted daemon socket: the DocumentDB control
        // plane must keep working, so the failure is reported rather than propagated.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(lifecycleManager.getDockerClient()).thenThrow(
                new RuntimeException("java.net.SocketException: No such file or directory"));

        DocDbContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", MEMBER_HOST, 27017),
                    "attempt " + attempt + " should report unavailable");
        }
        assertFalse(manager.isDockerReachable());
    }

    @Test
    void tryStartPropagatesFailuresRaisedWhileTheDaemonIsReachable() {
        // A reachable daemon that cannot start the container is a genuine failure, not a
        // degraded mode: CreateDBCluster must still surface it.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("no such image: mongo:7.0"));
        DockerClient dockerClient = mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        DocDbContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "mongo:7.0", "admin", "secret", MEMBER_HOST, 27017));
        assertEquals("no such image: mongo:7.0", failure.getMessage());
    }

    @Test
    void tryStartReturnsTheHandleOnceTheBackendIsAWritablePrimary() throws IOException {
        // A loopback stand-in for mongod: the manager is ready only once hello reports a primary.
        try (FakeMongod mongod = new FakeMongod(2)) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(27017, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", mongod.port()))));

            DocDbContainerManager manager = newManager(lifecycleManager);

            DocDbContainerHandle handle = manager.tryStart("cluster1", "mongo:7.0", "admin", "secret",
                    MEMBER_HOST, 27017);

            assertEquals("container-id", handle.getContainerId());
            assertEquals(mongod.port(), handle.getPort());
            assertTrue(mongod.hellos() >= 3, "a secondary must not count as ready");
        }
    }

    @Test
    void startRunsAReplicaSetAdvertisingTheEndpointAndLabelsTheContainer() throws IOException {
        try (FakeMongod mongod = new FakeMongod(0)) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(27018,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", mongod.port()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mock(ContainerSpec.class));

            DocDbContainerManager manager = new DocDbContainerManager(containerBuilder, lifecycleManager,
                    logStreamer(), mock(ContainerDetector.class), docdbConfig(),
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "mongo:7.0", "admin", "secret", MEMBER_HOST, 27018);

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "docdb",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "us-east-1"));
            verify(builder).withEnv(List.of(
                    "MONGO_INITDB_ROOT_USERNAME=admin",
                    "MONGO_INITDB_ROOT_PASSWORD=secret",
                    "FLOCI_DOCDB_HOST=" + MEMBER_HOST,
                    "FLOCI_DOCDB_PORT=27018"));
            // inside the container the advertised member resolves to mongod itself
            verify(builder).withExtraHost(MEMBER_HOST, "127.0.0.1");
            // the backend is published on loopback only; clients go through the cluster proxy
            verify(builder).withLoopbackPortBinding(27018, 0);
            verify(builder).withMemoryMb(anyInt());
        }
    }

    @Test
    void replicaSetScriptStartsMongodAsSetRs0AndInitiatesTheAdvertisedMember() {
        String script = DocDbContainerManager.replicaSetScript();

        assertTrue(script.contains("docker-entrypoint.sh mongod --replSet rs0 "), script);
        assertTrue(script.contains("--keyFile"), script);
        assertTrue(script.contains("--port \"$FLOCI_DOCDB_PORT\""), script);
        assertTrue(script.contains("--wiredTigerCacheSizeGB"), script);
        assertTrue(script.contains("rs.initiate({ _id: \"rs0\""), script);
        assertTrue(script.contains("process.env.FLOCI_DOCDB_HOST + \":\" + process.env.FLOCI_DOCDB_PORT"), script);
        assertTrue(script.contains("trap "), "docker stop must reach mongod: " + script);
    }

    @Test
    void waitForBackendReadyGivesUpWhenNoPrimaryAppears() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DocDbContainerManager.waitForBackendReady("cluster1", "127.0.0.1", 1, 300));
        assertTrue(failure.getMessage().contains("writable replica set primary"), failure.getMessage());
    }

    private DocDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        return new DocDbContainerManager(containerBuilder, lifecycleManager, logStreamer(),
                mock(ContainerDetector.class), docdbConfig(), new RegionResolver("us-east-1", "000000000000"));
    }

    private static ContainerLogStreamer logStreamer() {
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        return logStreamer;
    }

    private static EmulatorConfig docdbConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.DocDbServiceConfig docdb = mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.docdb()).thenReturn(docdb);
        when(docdb.dockerNetwork()).thenReturn(Optional.empty());
        return config;
    }

    /**
     * Answers every {@code hello} as mongod does while a replica set forms: as a secondary for
     * the first {@code secondaryReplies} requests, then as the writable primary.
     */
    static final class FakeMongod implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final AtomicInteger hellos = new AtomicInteger();

        FakeMongod(int secondaryReplies) throws IOException {
            serverSocket = new ServerSocket(0);
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept()) {
                        MongoHelloProbe.readReply(socket.getInputStream());
                        int count = hellos.incrementAndGet();
                        OutputStream out = socket.getOutputStream();
                        out.write(MongoHelloProbeTest.helloReply(count > secondaryReplies));
                        out.flush();
                    } catch (IOException e) {
                        // Test teardown closes the server socket; nothing left to assert on.
                        LOG.debugv(e, "Fake mongod connection ended");
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int hellos() {
            return hellos.get();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
