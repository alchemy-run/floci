package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2PrivateIpAllocationTest {
    private static final String REGION = "us-east-1";
    private final RetainedStorage storage = new RetainedStorage();
    private Ec2Service service;
    private String subnet;

    @BeforeEach
    void setUp() {
        service = service(null);
        String vpc = service.createVpc(REGION, "10.0.0.0/16", false).getVpcId();
        subnet = service.createSubnet(REGION, vpc, "10.0.1.0/24", REGION + "a").getSubnetId();
    }

    @Test
    void requestedAddressSurvivesStopStartAndRestoredStateUntilTermination() {
        Instance instance = launch("10.0.1.77", null);
        assertEquals("10.0.1.77", instance.getPrivateIpAddress());
        assertEquals("10.0.1.77", instance.getLogicalPrivateIpAddress());
        assertEquals("10.0.1.77", instance.getNetworkInterfaces().getFirst().getPrivateIpAddress());
        service.stopInstances(REGION, List.of(instance.getInstanceId()));
        service = service(null);
        assertInUse("10.0.1.77");
        service.startInstances(REGION, List.of(instance.getInstanceId()));
        assertEquals("10.0.1.77", service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of())
                .getFirst().getInstances().getFirst().getPrivateIpAddress());
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        Instance replacement = launch("10.0.1.77", null);
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        assertInUse("10.0.1.77");
        assertNotEquals(instance.getInstanceId(), replacement.getInstanceId());
    }

    @Test
    void concurrentExplicitRequestsHaveExactlyOneOwner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return launch("10.0.1.77", null).getPrivateIpAddress();
                    } catch (AwsException error) {
                        return error.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS));
            assertTrue(outcomes.contains("10.0.1.77"));
            assertTrue(outcomes.contains("InvalidIPAddress.InUse"));
        }
    }

    @Test
    void explicitAutomaticAndStandaloneAddressesShareOnePool() {
        NetworkInterface eni = service.createNetworkInterface(REGION, subnet, null, "10.0.1.10",
                List.of("10.0.1.11"), List.of(), List.of());
        assertEquals("10.0.1.12", launch(null, null).getPrivateIpAddress());
        assertInUse("10.0.1.10");
        assertInUse("10.0.1.11");
        AwsException duplicate = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                REGION, subnet, null, "10.0.1.12", List.of(), List.of(), List.of()));
        assertEquals("InvalidIPAddress.InUse", duplicate.getErrorCode());
        service.deleteNetworkInterface(REGION, eni.getNetworkInterfaceId());
        assertEquals("10.0.1.10", launch(null, null).getPrivateIpAddress());
    }

    @Test
    void existingEniKeepsOwnershipAcrossTerminationAndFailedLaunch() {
        NetworkInterface eni = service.createNetworkInterface(REGION, subnet, null, "10.0.1.77",
                List.of(), List.of(), List.of());
        Instance first = launch(null, eni.getNetworkInterfaceId());
        assertEquals("10.0.1.77", first.getPrivateIpAddress());
        service.terminateInstances(REGION, List.of(first.getInstanceId()));
        assertInUse("10.0.1.77");
        Instance reused = launch(null, eni.getNetworkInterfaceId());
        reused.setState(InstanceState.terminated());
        reused.setContainerLaunchFailed(true);
        assertInUse("10.0.1.77");
        Instance retried = launch(null, eni.getNetworkInterfaceId());
        assertEquals("10.0.1.77", retried.getPrivateIpAddress());
        service.terminateInstances(REGION, List.of(retried.getInstanceId()));
        service.deleteNetworkInterface(REGION, eni.getNetworkInterfaceId());
        assertEquals("10.0.1.77", launch("10.0.1.77", null).getPrivateIpAddress());
    }

    @Test
    void failedImplicitLaunchReleasesOnlyItsOwnLogicalAddress() {
        Instance failed = launch("10.0.1.77", null);
        Instance live = launch("10.0.1.78", null);
        failed.setState(InstanceState.terminated());
        failed.setContainerLaunchFailed(true);
        assertEquals("10.0.1.77", launch("10.0.1.77", null).getPrivateIpAddress());
        assertInUse(live.getPrivateIpAddress());
    }

    @Test
    void validatesDeclaredSubnetAndReservedAddressesWithoutLeakingAllocations() {
        for (String invalid : List.of("10.0.2.77", "10.0.1.0", "10.0.1.1", "10.0.1.2", "10.0.1.3",
                "10.0.1.255", "10.0.1.999", "not-an-ip")) {
            AwsException error = assertThrows(AwsException.class, () -> launch(invalid, null));
            assertEquals("InvalidParameterValue", error.getErrorCode(), invalid);
        }
        assertThrows(AwsException.class, () -> service.createNetworkInterface(REGION, subnet, null,
                "10.0.1.77", List.of("10.0.2.78"), List.of(), List.of()));
        assertEquals("10.0.1.77", launch("10.0.1.77", null).getPrivateIpAddress());
        assertEquals("10.0.1.10", launch(null, null).getPrivateIpAddress());
    }

    @Test
    void dryRunAndExhaustedBatchDoNotConsumeAddressesOrPublishPartialInstances() {
        AwsException dryRun = assertThrows(AwsException.class, () -> service.runInstances(REGION,
                "ami-private-ip", "t3.micro", 1, 1, null, List.of(), subnet, null, List.of(), null, null,
                null, null, 0, null, null, null, null, true, "10.0.1.77"));
        assertEquals("DryRunOperation", dryRun.getErrorCode());
        assertEquals("10.0.1.77", launch("10.0.1.77", null).getPrivateIpAddress());
        String vpc = service.createVpc(REGION, "10.1.0.0/16", false).getVpcId();
        subnet = service.createSubnet(REGION, vpc, "10.1.1.0/28", REGION + "a").getSubnetId();
        AwsException full = assertThrows(AwsException.class, () -> service.runInstances(REGION,
                "ami-private-ip", "t3.micro", 12, 12, null, List.of(), subnet, null, List.of(), null, null));
        assertEquals("InsufficientFreeAddressesInSubnet", full.getErrorCode());
        assertTrue(service.describeInstances(REGION, List.of(), Map.of("subnet-id", List.of(subnet))).isEmpty());
        Instance first = launch(null, null);
        assertEquals("10.1.1.10", first.getPrivateIpAddress());
        for (int i = 0; i < 10; i++) {
            launch(null, null);
        }
        assertEquals("InsufficientFreeAddressesInSubnet",
                assertThrows(AwsException.class, () -> launch(null, null)).getErrorCode());
        service.terminateInstances(REGION, List.of(first.getInstanceId()));
        assertEquals("10.1.1.10", launch(null, null).getPrivateIpAddress());
    }

    @Test
    void dockerOverlapChangesTransportNotRequestedOrAutomaticIdentity() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(16);
        DockerClient docker = mock(DockerClient.class);
        ListNetworksCmd list = mock(ListNetworksCmd.class, RETURNS_SELF);
        Network occupied = mock(Network.class);
        when(occupied.getIpam()).thenReturn(new Network.Ipam().withConfig(
                new Network.Ipam.Config().withSubnet("10.0.0.0/16")));
        when(occupied.getLabels()).thenReturn(Map.of());
        when(list.exec()).thenReturn(List.of(occupied));
        when(docker.listNetworksCmd()).thenReturn(list);
        VpcNetworkManager networks = new VpcNetworkManager(config, docker, null);
        service = service(networks);
        String vpc = service.createVpc(REGION, "10.0.0.0/16", false).getVpcId();
        subnet = service.createSubnet(REGION, vpc, "10.0.1.0/24", REGION + "a").getSubnetId();
        Instance fixed = launch("10.0.1.77", null);
        assertEquals("10.0.1.77", fixed.getPrivateIpAddress());
        assertEquals("10.240.1.77", networks.allocateTransportPrivateIp(REGION, subnet,
                fixed.getPrivateIpAddress(), fixed.getInstanceId()).orElseThrow());
        assertEquals("10.0.1.10", launch(null, null).getPrivateIpAddress());
        assertInUse("10.0.1.77");
    }

    private void assertInUse(String address) {
        assertEquals("InvalidIPAddress.InUse",
                assertThrows(AwsException.class, () -> launch(address, null)).getErrorCode());
    }

    private Instance launch(String address, String eni) {
        return service.runInstances(REGION, "ami-private-ip", "t3.micro", 1, 1, null,
                List.of(), eni == null ? subnet : null, null, List.of(), null, null,
                null, eni, 0, null, null, null, null, false, address).getInstances().getFirst();
    }

    private Ec2Service service(VpcNetworkManager networks) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), storage, networks);
    }

    private static final class RetainedStorage extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        private RetainedStorage() { super(null, null); }
        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String service, String file, TypeReference<Map<String, V>> type) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(file,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
