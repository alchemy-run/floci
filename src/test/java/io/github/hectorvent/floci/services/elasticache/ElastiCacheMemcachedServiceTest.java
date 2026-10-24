package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.ContainerEndpoints;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheMemcachedProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheMemcachedServiceTest {

    private ElastiCacheMemcachedService service;
    private ElastiCacheMemcachedContainerManager containerManager;
    private ElastiCacheMemcachedProxyManager proxyManager;
    private ElastiCacheService elasticacheService;
    private ContainerDetector containerDetector;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheMemcachedContainerManager.class);
        proxyManager = mock(ElastiCacheMemcachedProxyManager.class);
        elasticacheService = mock(ElastiCacheService.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "localhost", 11211));
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(6379, 6380, 6381);

        this.config = config;
        containerDetector = mock(ContainerDetector.class);
        // Floci in Docker, where every cluster's container has an address of its own; the tests
        // for Floci on the host switch this off.
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        service = new ElastiCacheMemcachedService(containerManager, proxyManager, elasticacheService,
                storageFactory, config, dockerHostResolver, containerDetector);
    }

    @Test
    void onTheHostEveryClusterReportsItsOwnPortAndOnlyTheHostRelayMoves() {
        // As on AWS every cluster reports 11211 on its own hostname; containers reach each by name.
        // Host clients share one loopback address, so only the first cluster's relay holds 11211.
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(elasticacheService.allocateHostProxyPort(11211)).thenReturn(11211, 6379);

        CacheCluster first = service.createCacheCluster("cluster-a");
        CacheCluster second = service.createCacheCluster("cluster-b");

        assertEquals(11211, first.getConfigurationEndpoint().port());
        assertEquals(11211, second.getConfigurationEndpoint().port());
        assertEquals(11211, first.getProxyPort());
        assertEquals(6379, second.getProxyPort());
        verify(containerManager).tryStart(eq("cluster-b"), anyString(), eq(11211));
        verify(proxyManager).startProxy(eq("cluster-b"), eq(6379), anyString(), anyInt());
    }

    @Test
    void onTheHostAPinnedPortHeldByAnotherCacheIsStillTheClustersPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(elasticacheService.allocateHostProxyPort(11300)).thenReturn(6379);

        CacheCluster cluster = service.createCacheCluster("my-cluster", 11300);

        assertEquals(11300, cluster.getConfigurationEndpoint().port());
        assertEquals(6379, cluster.getProxyPort());
        verify(containerManager).tryStart(eq("my-cluster"), anyString(), eq(11300));
    }

    @Test
    void onTheHostAnUnpinnedClusterWhosePortIsTakenLocallyKeepsItsPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(11211);
        when(elasticacheService.allocateProxyPort()).thenReturn(6390);
        doThrow(new RuntimeException("Address already in use"))
                .when(proxyManager).startProxy(eq("my-cluster"), eq(11211), anyString(), anyInt());

        CacheCluster cluster = service.createCacheCluster("my-cluster");

        assertEquals(6390, cluster.getProxyPort());
        assertEquals(11211, cluster.getConfigurationEndpoint().port());
    }

    @Test
    void createClusterReturnsAvailableCluster() {
        CacheCluster cluster = service.createCacheCluster("my-cluster");

        assertEquals("my-cluster", cluster.getCacheClusterId());
        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals("memcached", cluster.getEngine());
        assertEquals(configurationHostname("my-cluster"), cluster.getConfigurationEndpoint().address());
        assertEquals(11211, cluster.getConfigurationEndpoint().port());
        verify(containerManager).tryStart(eq("my-cluster"), anyString(), eq(11211));
    }

    @Test
    void everyClusterReportsItsOwnHostnameOnTheDefaultPort() {
        // AWS's default Memcached Port is 11211 for every cluster; the hostname tells them apart.
        CacheCluster first = service.createCacheCluster("cluster-a");
        CacheCluster second = service.createCacheCluster("cluster-b");

        assertEquals(11211, first.getConfigurationEndpoint().port());
        assertEquals(11211, second.getConfigurationEndpoint().port());
        assertEquals(configurationHostname("cluster-b"), second.getConfigurationEndpoint().address());
        verify(elasticacheService, times(2)).allocateHostProxyPort(11211);
    }

    @Test
    void aRequestedPortIsTheEnginePort() {
        CacheCluster cluster = service.createCacheCluster("my-cluster", 11300);

        assertEquals(11300, cluster.getConfigurationEndpoint().port());
        assertEquals(11300, cluster.getEnginePort());
        verify(containerManager).tryStart(eq("my-cluster"), anyString(), eq(11300));
    }

    @Test
    void anInvalidPortIsRejectedBeforeAContainerStarts() {
        AwsException ex = assertThrows(AwsException.class, () -> service.createCacheCluster("my-cluster", 0));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(containerManager, never()).tryStart(anyString(), anyString(), anyInt());
    }

    @Test
    void containersReachTheClusterContainerByName() {
        ContainerEndpoints endpoints = mock(ContainerEndpoints.class);
        ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 32770);
        handle.setNetworkIp("172.18.0.7");
        when(containerManager.tryStart(anyString(), anyString(), anyInt())).thenReturn(handle);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        ElastiCacheMemcachedService routed = new ElastiCacheMemcachedService(containerManager, proxyManager,
                elasticacheService, storageFactory, config, mock(DockerHostResolver.class), containerDetector,
                new RegionResolver("us-east-1", "000000000000"), endpoints);

        routed.createCacheCluster("my-cluster");
        verify(endpoints).routeToContainer(configurationHostname("my-cluster"), "172.18.0.7");
        verify(endpoints, never()).publishHostPort(anyInt());

        routed.deleteCacheCluster("my-cluster");
        verify(endpoints).release(configurationHostname("my-cluster"));
    }

    @Test
    void withoutAContainerAddressTheRelayOnThePortIsPublished() {
        ContainerEndpoints endpoints = mock(ContainerEndpoints.class);
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(11211);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        ElastiCacheMemcachedService routed = new ElastiCacheMemcachedService(containerManager, proxyManager,
                elasticacheService, storageFactory, config, mock(DockerHostResolver.class), containerDetector,
                new RegionResolver("us-east-1", "000000000000"), endpoints);

        routed.createCacheCluster("my-cluster");
        verify(endpoints).publishHostPort(11211);

        routed.deleteCacheCluster("my-cluster");
        verify(endpoints).withdrawHostPort(11211);
    }

    @Test
    void withoutAContainerAddressAClusterWhosePortIsHeldElsewhereGetsARelayOfItsOwn() {
        ContainerEndpoints endpoints = mock(ContainerEndpoints.class);
        when(endpoints.relayToFloci(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(6390);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        ElastiCacheMemcachedService routed = new ElastiCacheMemcachedService(containerManager, proxyManager,
                elasticacheService, storageFactory, config, mock(DockerHostResolver.class), containerDetector,
                new RegionResolver("us-east-1", "000000000000"), endpoints);

        CacheCluster cluster = routed.createCacheCluster("my-cluster");

        assertEquals(11211, cluster.getConfigurationEndpoint().port());
        verify(endpoints).relayToFloci(configurationHostname("my-cluster"), 11211, 6390);
        verify(endpoints, never()).publishHostPort(anyInt());
        verify(endpoints, never()).refuse(anyString());
    }

    @Test
    void aTakenPortMovesTheHostRelayInsteadOfFailingTheCreate() {
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(11211);
        when(elasticacheService.allocateProxyPort()).thenReturn(6390);
        doThrow(new RuntimeException("Address already in use"))
                .when(proxyManager).startProxy(eq("my-cluster"), eq(11211), anyString(), anyInt());

        CacheCluster cluster = service.createCacheCluster("my-cluster");

        assertEquals(11211, cluster.getConfigurationEndpoint().port());
        assertEquals(6390, cluster.getProxyPort());
        verify(elasticacheService).releaseProxyPort(11211);
        verify(proxyManager).startProxy("my-cluster", 6390, "localhost", 11211);
    }

    private static String configurationHostname(String clusterId) {
        return clusterId + "." + ElastiCacheEndpoints.hash("000000000000", "us-east-1")
                + ".cfg.use1.cache.localhost.floci.io";
    }

    @Test
    void createDuplicateClusterThrows() {
        service.createCacheCluster("my-cluster");

        AwsException ex = assertThrows(AwsException.class, () -> service.createCacheCluster("my-cluster"));
        assertEquals("CacheClusterAlreadyExistsFault", ex.getErrorCode());
    }

    @Test
    void getUnknownClusterThrows() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getCacheCluster("no-such-cluster"));
        assertEquals("CacheClusterNotFoundFault", ex.getErrorCode());
    }

    @Test
    void listClustersReturnsAll() {
        service.createCacheCluster("cluster-a");
        service.createCacheCluster("cluster-b");

        Collection<CacheCluster> list = service.listCacheClusters(null);
        assertEquals(2, list.size());
    }

    @Test
    void listClustersFiltersById() {
        service.createCacheCluster("cluster-a");
        service.createCacheCluster("cluster-b");

        Collection<CacheCluster> list = service.listCacheClusters("cluster-a");
        assertEquals(1, list.size());
        assertEquals("cluster-a", list.iterator().next().getCacheClusterId());
    }

    @Test
    void deleteClusterRemovesIt() {
        service.createCacheCluster("my-cluster");
        service.deleteCacheCluster("my-cluster");

        AwsException ex = assertThrows(AwsException.class, () -> service.getCacheCluster("my-cluster"));
        assertEquals("CacheClusterNotFoundFault", ex.getErrorCode());
    }

    @Test
    void createClusterUsesAnAwsShapedHostnameWhenHostnameNotConfigured() {
        ElastiCacheMemcachedContainerManager containerManager = mock(ElastiCacheMemcachedContainerManager.class);
        ElastiCacheMemcachedProxyManager proxyManager = mock(ElastiCacheMemcachedProxyManager.class);
        ElastiCacheService elasticacheService = mock(ElastiCacheService.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.20.0.2");

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "172.20.0.10", 11211));
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(6379);

        ElastiCacheMemcachedService containerModeService =
                new ElastiCacheMemcachedService(containerManager, proxyManager, elasticacheService, storageFactory, config, dockerHostResolver);

        CacheCluster cluster = containerModeService.createCacheCluster("container-cluster");

        assertEquals(configurationHostname("container-cluster"), cluster.getConfigurationEndpoint().address());
    }

    @Test
    void createClusterInDockerReportsItsHostnameAndPortNotTheContainerAddress() {
        when(config.hostname()).thenReturn(Optional.empty());
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(containerManager.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "172.20.0.10", 11211));

        CacheCluster cluster = service.createCacheCluster("container-cluster");

        assertEquals(configurationHostname("container-cluster"), cluster.getConfigurationEndpoint().address());
        assertEquals(11211, cluster.getConfigurationEndpoint().port());
    }

    @Test
    void createClusterWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cache cluster record is
        // metadata, so the create still succeeds and the cluster reaches 'available' on the first
        // describe (what SDK/Terraform waiters poll), on its Port.
        when(containerManager.tryStart(anyString(), anyString(), anyInt())).thenReturn(null);

        CacheCluster cluster = service.createCacheCluster("no-docker-cluster");

        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals(configurationHostname("no-docker-cluster"), cluster.getConfigurationEndpoint().address());
        assertEquals(11211, cluster.getConfigurationEndpoint().port());
        assertEquals("no-docker-cluster",
                service.getCacheCluster("no-docker-cluster").getCacheClusterId());

        // Delete must not reach for a container that was never created.
        service.deleteCacheCluster("no-docker-cluster");
        verify(containerManager, never()).stop(any());
    }

    @Test
    void restorePersistedRuntimeRestartsTheContainerAndRepointsTheEndpoint() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 32770));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        when(restarted.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid2", "my-cluster", "localhost", 32771));
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);

        restartedService.restorePersistedRuntime().join();

        verify(restarted).tryStart(eq("my-cluster"), anyString(), anyInt());
        CacheCluster cluster = restartedService.getCacheCluster("my-cluster");
        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals(11211, cluster.getConfigurationEndpoint().port(),
                "The endpoint reports the cluster's Port, not the host port Docker published this run");
        assertEquals(32771, cluster.getContainerPort(), "The relay must follow the fresh backend port");
    }

    @Test
    void restoreKeepsTheHostProxyEndpointAndRebindsTheNewBackend() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        service.createCacheCluster("host-cluster");
        when(elasticacheService.reserveOrAllocateProxyPort(6379)).thenReturn(6379);
        when(containerManager.tryStart(eq("host-cluster"), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("restored", "host-cluster", "localhost", 32771));

        service.restorePersistedRuntime().join();

        CacheCluster restored = service.getCacheCluster("host-cluster");
        assertEquals(11211, restored.getConfigurationEndpoint().port());
        assertEquals(6379, restored.getProxyPort());
        assertEquals(configurationHostname("host-cluster"), restored.getConfigurationEndpoint().address());
        verify(elasticacheService).reserveOrAllocateProxyPort(6379);
        verify(proxyManager).startProxy("host-cluster", 6379, "localhost", 32771);
        service.deleteCacheCluster("host-cluster");
        verify(proxyManager).stopProxy("host-cluster");
        verify(elasticacheService).releaseProxyPort(6379);
    }

    @Test
    void memcachedRestoreFailureReportsRestoreFailed() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 11211));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        when(restarted.tryStart(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("container failed"));
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);

        restartedService.restorePersistedRuntime().join();

        CacheCluster cluster = restartedService.getCacheCluster("my-cluster");
        assertEquals(CacheClusterStatus.RESTORE_FAILED, cluster.getCacheClusterStatus());
        assertNull(cluster.getConfigurationEndpoint(),
                "A cluster whose container is gone must not advertise an endpoint");
    }

    @Test
    void restoreDoesNotResurrectAClusterDeletedWhileItWasRestoring() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString(), anyInt()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 32770));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);
        ElastiCacheContainerHandle restoredHandle =
                new ElastiCacheContainerHandle("cid2", "my-cluster", "localhost", 32771);
        // The delete lands in the window the cluster's monitor closes: the container is up, the
        // record has not been written back yet.
        when(restarted.tryStart(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            restartedService.deleteCacheCluster("my-cluster");
            return restoredHandle;
        });

        restartedService.restorePersistedRuntime().join();

        assertThrows(AwsException.class, () -> restartedService.getCacheCluster("my-cluster"),
                "A cluster deleted while it was restoring must stay deleted");
        verify(restarted).stop(restoredHandle);
    }

    private static StorageFactory sharedStorageFactory() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        Map<String, Object> backends = new ConcurrentHashMap<>();
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                backends.computeIfAbsent(inv.getArgument(1, String.class),
                        key -> AccountAwareStorageBackend.inMemory("000000000000")));
        return storageFactory;
    }

    private static ElastiCacheMemcachedService serviceWith(StorageFactory storageFactory,
                                                           ElastiCacheMemcachedContainerManager containerManager) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        ElastiCacheMemcachedProxyManager proxyManager = mock(ElastiCacheMemcachedProxyManager.class);
        ElastiCacheService elasticacheService = mock(ElastiCacheService.class);
        when(elasticacheService.allocateHostProxyPort(anyInt())).thenReturn(6379);
        when(elasticacheService.reserveOrAllocateProxyPort(6379)).thenReturn(6379);
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);
        return new ElastiCacheMemcachedService(containerManager, proxyManager, elasticacheService,
                storageFactory, config, mock(DockerHostResolver.class), detector);
    }
}
