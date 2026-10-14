package io.github.hectorvent.floci.services.amazonmq.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveMqManagerTest {

    private static final List<ActiveMqBrokerConfig.Credential> USERS = List.of(
            new ActiveMqBrokerConfig.Credential("alchemyadmin", "SuperSecretPassw0rd!", false, null));

    private EmulatorConfig config;
    private EmulatorConfig.AmazonMqServiceConfig amazonmq;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private CopyArchiveToContainerCmd copyCmd;
    private ActiveMqManager manager;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        amazonmq = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.amazonmq()).thenReturn(amazonmq);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(amazonmq.activemqImage()).thenReturn(Optional.empty());
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");
        EmulatorConfig.DockerConfig docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = Mockito.mock(DockerClient.class);
        copyCmd = Mockito.mock(CopyArchiveToContainerCmd.class, Mockito.RETURNS_SELF);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);

        containerBuilder = Mockito.mock(ContainerBuilder.class);
        builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        ContainerDetector containerDetector = Mockito.mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        manager = new ActiveMqManager(containerBuilder, lifecycleManager, Mockito.mock(ContainerLogStreamer.class),
                containerDetector, config, regionResolver);
    }

    private static Broker broker(String engineVersion) {
        return new Broker("b-1", "arn", "orders", "ACTIVEMQ", engineVersion, "SINGLE_INSTANCE", "mq.t3.micro");
    }

    private void containerStartsOn(String host) {
        when(lifecycleManager.create(any())).thenReturn("container-id");
        when(lifecycleManager.startCreated(Mockito.eq("container-id"), any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        ActiveMqManager.OPENWIRE_PORT, new ContainerLifecycleManager.EndpointInfo(host, 61616),
                        ActiveMqManager.AMQP_PORT, new ContainerLifecycleManager.EndpointInfo(host, 5672),
                        ActiveMqManager.STOMP_PORT, new ContainerLifecycleManager.EndpointInfo(host, 61613),
                        ActiveMqManager.MQTT_PORT, new ContainerLifecycleManager.EndpointInfo(host, 1883),
                        ActiveMqManager.WS_PORT, new ContainerLifecycleManager.EndpointInfo(host, 61614),
                        ActiveMqManager.CONSOLE_PORT, new ContainerLifecycleManager.EndpointInfo(host, 8161))));
    }

    @Test
    void startContainerBoundsMemoryPublishesEveryPortAndCopiesTheConfiguration() {
        containerStartsOn("172.17.0.9");
        Broker broker = broker("5.18");

        manager.startContainer(broker, USERS, null);

        verify(containerBuilder).newContainer(ActiveMqManager.DEFAULT_IMAGE);
        verify(builder).withMemoryMb(ActiveMqManager.MEMORY_MB);
        verify(builder).withEnv(Mockito.eq("ACTIVEMQ_OPTS"), Mockito.contains("-Xmx384m"));
        for (int port : List.of(61616, 5672, 61613, 1883, 61614, 8161)) {
            verify(builder).withDynamicPort(port);
        }
        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "amazonmq",
                "io.floci.resource-id", "b-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
        verify(copyCmd).withRemotePath("/opt/apache-activemq/conf");
        verify(copyCmd).withTarInputStream(any(InputStream.class));
        verify(copyCmd).exec();
        verify(lifecycleManager).startCreated(Mockito.eq("container-id"), any());

        assertEquals("container-id", broker.getContainerId());
        assertEquals(List.of("tcp://172.17.0.9:61616", "amqp://172.17.0.9:5672", "stomp://172.17.0.9:61613",
                        "mqtt://172.17.0.9:1883", "ws://172.17.0.9:61614"),
                broker.getBrokerInstances().get(0).getEndpoints());
        assertEquals("http://172.17.0.9:8161", broker.getBrokerInstances().get(0).getConsoleURL());
    }

    @Test
    void startContainerRemovesTheContainerWhenStartFails() {
        when(lifecycleManager.create(any())).thenReturn("container-id");
        when(lifecycleManager.startCreated(Mockito.eq("container-id"), any()))
                .thenThrow(new RuntimeException("port in use"));

        assertThrows(RuntimeException.class, () -> manager.startContainer(broker("5.18"), USERS, null));

        // Once for the stale-container sweep before create, once for the rollback.
        verify(lifecycleManager, Mockito.times(2)).removeIfExists("floci-amazonmq-b-1");
    }

    @Test
    void imageFollowsTheEngineVersionUnlessOverridden() {
        assertEquals(ActiveMqManager.DEFAULT_IMAGE, manager.imageFor("5.18"));
        assertEquals(ActiveMqManager.LEGACY_IMAGE, manager.imageFor("5.17.6"));
        assertEquals(ActiveMqManager.LEGACY_IMAGE, manager.imageFor("5.15.16"));

        when(amazonmq.activemqImage()).thenReturn(Optional.of("mirror.local/activemq:5.18.7"));
        assertEquals("mirror.local/activemq:5.18.7", manager.imageFor("5.18"));
    }

    @Test
    void anExitedContainerIsReportedButAnUnreachableDaemonIsNot() {
        containerStartsOn("localhost");
        Broker broker = broker("5.18");
        manager.startContainer(broker, USERS, null);

        when(lifecycleManager.presenceOf("container-id")).thenReturn(ContainerPresence.UNKNOWN);
        assertFalse(manager.hasExited(broker));
        when(lifecycleManager.presenceOf("container-id")).thenReturn(ContainerPresence.STOPPED);
        assertTrue(manager.hasExited(broker));
    }

    @Test
    void stopManagedContainersStopsEveryTrackedBroker() {
        containerStartsOn("localhost");
        manager.startContainer(broker("5.18"), USERS, null);

        manager.stopManagedContainers();

        verify(lifecycleManager).stopAndRemove(Mockito.eq("container-id"), Mockito.any());
    }
}
