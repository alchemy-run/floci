package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.ContainerEndpoints;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How cluster-mode-disabled replication groups are made reachable on their own hostname and Port
 * from the containers Floci launches (VPC Lambdas, ECS tasks). As on AWS every group reports its
 * configured Port on its own hostname, with Floci in Docker or on the host: a group without
 * authentication is its own engine container, and a group with authentication is its auth proxy,
 * reached through Floci's address while the proxy holds the Port and otherwise through a relay of
 * its own. Only the proxy host clients use moves when another group's proxy holds the Port.
 */
class ElastiCacheEndpointRoutingTest {

    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;
    private ContainerEndpoints endpoints;
    private ContainerDetector containerDetector;
    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheContainerManager.class);
        proxyManager = mock(ElastiCacheProxyManager.class);
        endpoints = mock(ContainerEndpoints.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(6379);
        when(ecConfig.proxyMaxPort()).thenReturn(6399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.empty());
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            String groupId = inv.getArgument(0, String.class);
            ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                    "cid-" + groupId, groupId, "localhost", 32000 + groupId.length());
            handle.setNetworkIp("172.18.0." + (10 + groupId.length()));
            return handle;
        });
        KmsService kmsService = mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(new AwsException("NotFoundException", "missing", 404));
        containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        service = new ElastiCacheService(containerManager, proxyManager, mock(ValkeyClusterFormation.class),
                storageFactory, config, mock(DockerHostResolver.class), containerDetector,
                mock(Ec2Service.class), new RegionResolver("us-east-1", "000000000000"), kmsService, endpoints);
    }

    @Test
    void groupsWithoutAuthenticationShareTheDefaultPortEachOnItsOwnContainer() {
        ReplicationGroup first = service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, null));
        ReplicationGroup second = service.createReplicationGroup(request("cache-bb", AuthMode.NO_AUTH, null));

        assertEquals(6379, first.getConfigurationEndpoint().port());
        assertEquals(6379, second.getConfigurationEndpoint().port());
        assertNotEquals(first.getConfigurationEndpoint().address(), second.getConfigurationEndpoint().address());
        verify(endpoints).routeToContainer(primaryHostname("cache-a"), "172.18.0.17");
        verify(endpoints).routeToContainer(primaryHostname("cache-bb"), "172.18.0.18");
        verify(endpoints, never()).publishHostPort(anyInt());
        verify(containerManager).tryStart(eq("cache-bb"), anyString(), eq(6379));
    }

    @Test
    void anExplicitPortIsNeverExclusive() {
        ReplicationGroup first = service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, 6380));
        ReplicationGroup replacement = service.createReplicationGroup(request("cache-bb", AuthMode.NO_AUTH, 6380));

        assertEquals(6380, first.getConfigurationEndpoint().port());
        assertEquals(6380, replacement.getConfigurationEndpoint().port());
        verify(endpoints).routeToContainer(primaryHostname("cache-bb"), "172.18.0.18");
    }

    @Test
    void anAuthenticatedGroupIsReachedThroughItsProxyWhenTheProxyHoldsItsPort() {
        ReplicationGroup group = service.createReplicationGroup(request("secure", AuthMode.PASSWORD, null));

        assertEquals(6379, group.getProxyPort());
        verify(endpoints).release(primaryHostname("secure"));
        verify(endpoints).publishHostPort(6379);
        verify(endpoints, never()).routeToContainer(anyString(), anyString());

        service.deleteReplicationGroup("secure");
        verify(endpoints).withdrawHostPort(6379);
    }

    @Test
    void anAuthenticatedGroupWhosePortIsHeldByAnotherProxyGetsARelayOfItsOwn() {
        when(endpoints.relayToFloci(anyString(), anyInt(), anyInt())).thenReturn(true);
        service.createReplicationGroup(request("secure", AuthMode.PASSWORD, null));
        ReplicationGroup second = service.createReplicationGroup(request("secure-two", AuthMode.IAM, null));

        assertEquals(6379, second.getConfigurationEndpoint().port());
        assertNotEquals(6379, second.getProxyPort());
        verify(endpoints).relayToFloci(primaryHostname("secure-two"), 6379, second.getProxyPort());
        verify(endpoints, never()).publishHostPort(second.getProxyPort());
        verify(endpoints, never()).refuse(anyString());
        verify(endpoints, never()).routeToContainer(eq(primaryHostname("secure-two")), anyString());

        service.deleteReplicationGroup("secure-two");
        verify(endpoints).release(primaryHostname("secure-two"));
    }

    @Test
    void anAuthenticatedGroupWhoseRelayCannotStartIsRefusedRatherThanMisrouted() {
        when(endpoints.relayToFloci(anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("no Docker network"));
        service.createReplicationGroup(request("secure", AuthMode.PASSWORD, null));
        ReplicationGroup second = service.createReplicationGroup(request("secure-two", AuthMode.IAM, null));

        assertEquals(ReplicationGroupStatus.AVAILABLE, second.getStatus());
        verify(endpoints).refuse(primaryHostname("secure-two"));
    }

    @Test
    void deletingAGroupReleasesItsHostname() {
        service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, null));

        service.deleteReplicationGroup("cache-a");

        verify(endpoints).release(primaryHostname("cache-a"));
    }

    @Test
    void aGroupWithoutAContainerIsNotRouted() {
        when(containerManager.tryStart(anyString(), anyString(), anyInt())).thenReturn(null);

        service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, null));

        verify(endpoints).release(primaryHostname("cache-a"));
        verify(endpoints, never()).routeToContainer(anyString(), anyString());
        verify(endpoints, never()).publishHostPort(anyInt());
    }

    @Test
    void onTheHostEveryGroupReportsItsOwnPortAndOnlyTheHostProxyMoves() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(endpoints.relayToFloci(anyString(), anyInt(), anyInt())).thenReturn(true);

        ReplicationGroup first = service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, null));
        ReplicationGroup second = service.createReplicationGroup(request("secure", AuthMode.PASSWORD, null));

        assertEquals(6379, first.getConfigurationEndpoint().port());
        assertEquals(6379, second.getConfigurationEndpoint().port());
        assertEquals(6379, first.getProxyPort());
        assertEquals(6380, second.getProxyPort());
        verify(containerManager).tryStart(eq("secure"), anyString(), eq(6379));
        verify(endpoints).routeToContainer(primaryHostname("cache-a"), "172.18.0.17");
        verify(endpoints).relayToFloci(primaryHostname("secure"), 6379, 6380);
        verify(endpoints, never()).refuse(anyString());
    }

    @Test
    void onTheHostAPinnedPortIsHonoredWhenAnotherGroupsProxyHoldsIt() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        ReplicationGroup first = service.createReplicationGroup(request("cache-a", AuthMode.NO_AUTH, 6380));

        ReplicationGroup second = service.createReplicationGroup(request("cache-bb", AuthMode.NO_AUTH, 6380));

        assertEquals(6380, first.getConfigurationEndpoint().port());
        assertEquals(6380, second.getConfigurationEndpoint().port());
        assertEquals(6380, first.getProxyPort());
        assertNotEquals(6380, second.getProxyPort());
        verify(endpoints).routeToContainer(primaryHostname("cache-bb"), "172.18.0.18");
    }

    @Test
    void onTheHostAGroupWhosePortIsTakenLocallyKeepsItsPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(endpoints.relayToFloci(anyString(), anyInt(), anyInt())).thenReturn(true);
        doThrow(new RuntimeException("Address already in use"))
                .when(proxyManager).startProxy(eq("secure"), any(), eq(6379), anyString(), anyInt(), any());

        ReplicationGroup group = service.createReplicationGroup(request("secure", AuthMode.PASSWORD, null));

        assertEquals(6380, group.getProxyPort());
        assertEquals(6379, group.getConfigurationEndpoint().port());
        verify(endpoints).relayToFloci(primaryHostname("secure"), 6379, 6380);
        verify(endpoints, never()).publishHostPort(anyInt());
    }

    private static ElastiCacheService.CreateReplicationGroupRequest request(String groupId, AuthMode authMode,
                                                                            Integer port) {
        return new ElastiCacheService.CreateReplicationGroupRequest(groupId, "test", authMode,
                authMode == AuthMode.PASSWORD ? "secret-token-123456" : null, "us-east-1", null, null, null,
                null, null, null, null, null, null, null, null, port, ReplicationGroupSettings.defaults(), Map.of());
    }

    private static String primaryHostname(String groupId) {
        return "master." + groupId + "." + ElastiCacheEndpoints.hash("000000000000", "us-east-1")
                + ".use1.cache.localhost.floci.io";
    }
}
