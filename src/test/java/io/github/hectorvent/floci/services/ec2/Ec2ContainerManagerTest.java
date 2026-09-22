package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import io.vertx.core.json.JsonObject;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import com.github.dockerjava.api.model.NetworkSettings;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.withSettings;
import com.github.dockerjava.api.command.PingCmd;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

class Ec2ContainerManagerTest {

    @Test
    void authenticatedMetadataIsDeliveredThroughOwnedArchivesAndReinstalledOnStartRestoreAndReboot() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance guest = metadataGuest(harness, "i-owned", "4566");
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        when(harness.metadataServer.registerProxy(guest)).thenReturn("cap-one", "cap-two", "cap-three", "cap-four");
        List<MetadataFile> files = new CopyOnWriteArrayList<>();
        CopyArchiveToContainerCmd copy = harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID);
        when(copy.withTarInputStream(any(InputStream.class))).thenAnswer(call -> {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(call.getArgument(0))) {
                TarArchiveEntry entry = tar.getNextEntry();
                files.add(new MetadataFile(entry.getName(), entry.getMode(),
                        new String(tar.readAllBytes(), StandardCharsets.UTF_8)));
            }
            return copy;
        });
        try {
            assertTrue(harness.manager.configureMetadataEndpoint(guest));
            assertEquals(2, files.size());
            assertEquals("floci-imds-proxy.py", files.getFirst().name());
            assertEquals(0700, files.getFirst().mode());
            assertEquals(0600, files.getLast().mode());
            assertEquals("floci-imds-proxy.json.next", files.getLast().name());
            assertEquals("cap-one", new JsonObject(files.getLast().content()).getString("capability"));
            assertTrue(harness.executedCommands.stream().noneMatch(command -> String.join(" ", command).contains("cap-one")));
            verify(copy, times(2)).withRemotePath("/var/lib");
            assertTrue(harness.manager.restoreMetadataRegistration(guest));
            assertEquals("cap-two", new JsonObject(files.getLast().content()).getString("capability"));
            when(harness.dockerClient.stopContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StopContainerCmd.class, RETURNS_SELF));
            harness.manager.stop(guest);
            verify(harness.metadataServer).unregisterInstance(guest);
            awaitUntil(() -> "stopped".equals(guest.getState().getName()), Duration.ofSeconds(2));
            when(harness.dockerClient.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
            harness.manager.start(guest);
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            assertEquals("cap-three", new JsonObject(files.getLast().content()).getString("capability"));
            when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(RestartContainerCmd.class, RETURNS_SELF));
            harness.manager.reboot(guest);
            verify(harness.portForwardManager, timeout(2000)).restore(guest);
            assertEquals("cap-four", new JsonObject(files.getLast().content()).getString("capability"));
            verify(harness.metadataServer, times(4)).registerProxy(guest);
            verify(harness.metadataServer, never()).unregisterProxy(any(), anyString());
            guest.setState(InstanceState.pending());
            guest.setContainerLaunchPending(true);
            assertTrue(harness.manager.cancelLaunch(guest));
            assertEquals("terminated", guest.getState().getName());
            verify(harness.metadataServer, times(4)).unregisterInstance(guest);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void metadataCapabilityIsNotDeliveredToAnotherNamespaceAndIsRevokedOnFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance guest = metadataGuest(harness, "i-foreign", "another-namespace/4566");
        when(harness.metadataServer.registerProxy(guest)).thenReturn("undelivered");
        try {
            assertFalse(harness.manager.configureMetadataEndpoint(guest));
            verify(harness.dockerClient, never()).copyArchiveToContainerCmd(anyString());
            verify(harness.dockerClient, never()).execCreateCmd(anyString());
            verify(harness.metadataServer).unregisterProxy(guest, "undelivered");
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedMetadataProxyStartupRevokesTheProvisionedCapability() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance guest = metadataGuest(harness, "i-failed-proxy", "4566");
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        when(harness.metadataServer.registerProxy(guest)).thenReturn("failed-capability");
        InspectExecResponse installed = mock(InspectExecResponse.class);
        InspectExecResponse failed = mock(InspectExecResponse.class);
        when(installed.getExitCodeLong()).thenReturn(0L);
        when(failed.getExitCodeLong()).thenReturn(1L);
        when(harness.dockerClient.inspectExecCmd("metadata-exec").exec()).thenReturn(installed, failed);
        try {
            assertFalse(harness.manager.configureMetadataEndpoint(guest));
            verify(harness.metadataServer).unregisterProxy(guest, "failed-capability");
            verify(harness.dockerClient, times(2)).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void profileGuestUsesTheLocalMetadataProxyWithoutStaticCredentials() throws Exception {
        LaunchHarness harness = launchHarness();
        Instance guest = instance("i-profile-guest");
        guest.setIamInstanceProfileArn("arn:aws:iam::123456789012:instance-profile/worker");
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setOwnerId("123456789012");
        guest.setNetworkInterfaces(List.of(eni));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse response = inspectResponse("172.17.0.2");
        when(inspect.exec()).thenReturn(response);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> guest.getState() != null && "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.builder).withEnv(List.of(
                    "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                    "AWS_ENDPOINT_URL=http://localhost.floci.io:4680",
                    "AWS_DEFAULT_REGION=us-west-2", "AWS_REGION=us-west-2"));
            verify(harness.builder).withLabels(Map.of("io.floci", "aws", "io.floci.service", "ec2",
                    "io.floci.resource-id", "i-profile-guest", "io.floci.account", "123456789012",
                    "io.floci.region", "us-west-2"));
        } finally {
            harness.manager.stop();
        }
    }

    private record MetadataFile(String name, int mode, String content) {}

    private static Instance metadataGuest(LaunchHarness harness, String id, String owner) {
        Instance guest = instance(id);
        guest.setDockerContainerId(TEST_CONTAINER_ID);
        guest.setRegion("us-west-2");
        guest.setState(InstanceState.running());
        InstanceNetworkInterface eni = new InstanceNetworkInterface();
        eni.setOwnerId("123456789012");
        guest.setNetworkInterfaces(List.of(eni));
        when(harness.config.port()).thenReturn(4566);
        InspectContainerResponse response = inspectResponse("172.17.0.2");
        ContainerConfig containerConfig = mock(ContainerConfig.class);
        when(response.getConfig()).thenReturn(containerConfig);
        when(containerConfig.getLabels()).thenReturn(Map.of("floci_owner_port", owner,
                "io.floci.service", "ec2", "io.floci.resource-id", id,
                "io.floci.account", "123456789012", "io.floci.region", "us-west-2"));
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        doCallRealMethod().when(harness.manager).configureLinkLocalMetadataEndpoint(any(), anyString());
        return guest;
    }

    @Test
    void guestIsNotCreatedUntilStockImagePreparationCompletes() throws Exception {
        ManagedHarness managed = managedHarness("i-bootstrap-ready");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        doAnswer(invocation -> {
            preparing.countDown();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            return ResolvedAmiImage.minimal("floci/ec2-bootstrap:prepared");
        }).when(harness.manager).prepareImage(any());
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            assertTrue(preparing.await(2, TimeUnit.SECONDS));
            assertEquals("pending", guest.getState().getName());
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
            verify(managed.firewall(), never()).createNetworkNamespace(anyString(), anyString(), anyString(),
                    anyString(), any(), any());
            verify(harness.manager, never()).configureLinkLocalMetadataEndpoint(any(), anyString());
            ready.countDown();
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.containerBuilder).newContainer("floci/ec2-bootstrap:prepared");
        } finally {
            ready.countDown();
            harness.manager.stop();
        }
    }

    @Test
    void managedLogicalAddressSurvivesRemappingStopStartRestoreAndReboot() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-lifecycle");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            assertEquals("10.0.1.77", guest.getPrivateIpAddress());
            assertEquals("10.240.1.77", guest.getContainerBridgeIp());
            assertEquals("10.0.2.88", guest.getNetworkInterfaces().get(1).getPrivateIpAddress());
            verify(harness.vpcNetworkManager).attach("us-west-2", "vpc-logical", "subnet-logical",
                    managed.helperId(), "10.240.1.77");
            verify(managed.firewall()).registerNetworkEndpoint(argThat(endpoint ->
                    "123456789012".equals(endpoint.accountId()) && "10.0.1.77".equals(endpoint.logicalAddress())
                            && "10.240.1.77".equals(endpoint.transportAddress())), eq(managed.helperId()));
            verify(harness.metadataServer).reconcileContainerAddresses(Set.of("172.17.0.7", "10.240.1.77"), guest);
            verify(harness.manager).configureLinkLocalMetadataEndpoint(guest, managed.helperId());
            verify(harness.builder).withNetworkMode("container:" + managed.helperId());
            verify(harness.builder, never()).withEmbeddedDns();
            verify(harness.builder, never()).withHostDockerInternalOnLinux();
            verify(harness.builder).withPrivileged(false);
            harness.manager.stop(guest);
            awaitUntil(() -> "stopped".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp(anyString(), anyString(), anyString());
            harness.manager.restoreSecurityGroups(guest, "us-west-2", List.of(), Map.of());
            harness.manager.start(guest);
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            assertTrue(harness.manager.restoreMetadataRegistration(guest));
            harness.manager.reboot(guest);
            verify(harness.portForwardManager, timeout(2000).times(2)).restore(guest);
            assertEquals("10.0.1.77", guest.getPrivateIpAddress());
            assertEquals("10.240.1.77", guest.getContainerBridgeIp());
            assertEquals("10.0.1.77", guest.getNetworkInterfaces().getFirst().getPrivateIpAddress());
            verify(harness.vpcNetworkManager, atLeastOnce()).reserveTransportPrivateIp("us-west-2", "subnet-logical",
                    "10.0.1.77", "10.240.1.77", guest.getInstanceId());
            harness.manager.terminate(guest);
            awaitUntil(() -> "terminated".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.vpcNetworkManager).releaseTransportPrivateIp("us-west-2", "subnet-logical", guest.getInstanceId());
            verify(managed.firewall()).unregister("eni-logical", managed.helperId());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedManagedRemovalRetainsLeaseAndCanBeRetriedWithoutRemovingSuccessor() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-removal");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            doNothing().when(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
            harness.manager.terminate(guest);
            verify(harness.lifecycleManager, timeout(2000)).removeIfExists(TEST_CONTAINER_ID);
            assertEquals("shutting-down", guest.getState().getName());
            assertEquals(TEST_CONTAINER_ID, guest.getDockerContainerId());
            assertEquals("10.240.1.77", guest.getContainerBridgeIp());
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp(anyString(), anyString(), anyString());
            verify(managed.firewall(), never()).unregister(anyString(), anyString());
            doAnswer(call -> { managed.live().remove(TEST_CONTAINER_ID); return null; })
                    .when(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
            harness.manager.terminate(guest);
            awaitUntil(() -> "terminated".equals(guest.getState().getName()), Duration.ofSeconds(2));
            managed.firewall().registerNetworkEndpoint(new SecurityGroupNftCompiler.Endpoint("123456789012", "us-west-2",
                    "vpc-logical", "eni-logical", "10.0.1.77", "10.240.1.77", Set.of(), List.of()), "helper-successor");
            harness.manager.terminate(guest);
            verify(managed.firewall(), times(1)).unregister("eni-logical", managed.helperId());
            verify(managed.firewall(), never()).unregister(anyString());
            verify(managed.firewall(), never()).unregister("eni-logical", "helper-successor");
            verify(harness.lifecycleManager, never()).removeIfExists("helper-successor");
            verify(harness.vpcNetworkManager, times(1)).releaseTransportPrivateIp("us-west-2", "subnet-logical", guest.getInstanceId());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void retiredCleanupCallbackCannotUnregisterSuccessorOrReleaseItsLease() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        ManagedHarness managed = managedHarness("i-retired", executor);
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            tasks.removeFirst().run();
            assertEquals("running", guest.getState().getName());
            harness.manager.terminate(guest);
            harness.manager.terminate(guest);
            assertEquals(2, tasks.size());
            tasks.removeFirst().run();
            assertEquals("terminated", guest.getState().getName());
            managed.firewall().registerNetworkEndpoint(new SecurityGroupNftCompiler.Endpoint("123456789012", "us-west-2",
                    "vpc-logical", "eni-logical", "10.0.1.77", "10.240.1.77", Set.of(), List.of()), "helper-successor");
            tasks.removeFirst().run();
            verify(managed.firewall(), times(1)).unregister("eni-logical", managed.helperId());
            verify(managed.firewall(), never()).unregister(anyString());
            verify(harness.lifecycleManager, never()).removeIfExists("helper-successor");
            verify(harness.vpcNetworkManager, times(1)).releaseTransportPrivateIp("us-west-2", "subnet-logical", "i-retired");
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp("us-west-2", "subnet-logical", "i-successor");
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void failedManagedLaunchKeepsLeaseUntilHelperRemovalIsConfirmed() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-failure");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new IllegalStateException("guest start failed"));
        doNothing().when(harness.lifecycleManager).removeIfExists(managed.helperId());
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> "shutting-down".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp(anyString(), anyString(), anyString());
            assertEquals("10.0.1.77", guest.getPrivateIpAddress());
            assertTrue(managed.live().contains(managed.helperId()));
            doAnswer(call -> { managed.live().remove(managed.helperId()); return null; })
                    .when(harness.lifecycleManager).removeIfExists(managed.helperId());
            harness.manager.terminate(guest);
            awaitUntil(() -> "terminated".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.vpcNetworkManager).releaseTransportPrivateIp("us-west-2", "subnet-logical", guest.getInstanceId());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void cancelledManagedCreateRetainsOwnershipUntilLateContainerIsRemoved() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-cancelled");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(complete.await(2, TimeUnit.SECONDS));
            return TEST_CONTAINER_ID;
        });
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(harness.manager.cancelLaunch(guest));
            assertEquals("shutting-down", guest.getState().getName());
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp(anyString(), anyString(), anyString());
            complete.countDown();
            awaitUntil(() -> "terminated".equals(guest.getState().getName()), Duration.ofSeconds(2));
            verify(harness.lifecycleManager, never()).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
            verify(harness.vpcNetworkManager).releaseTransportPrivateIp("us-west-2", "subnet-logical", guest.getInstanceId());
            assertFalse(managed.live().contains(managed.helperId()));
            assertFalse(managed.live().contains(TEST_CONTAINER_ID));
        } finally {
            complete.countDown();
            harness.manager.stop();
        }
    }

    @Test
    void helperNamespaceReceivesAuthenticatedMetadataCapabilityNotTheWorkload() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-metadata");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            ExecCreateCmd exec = harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID);
            when(harness.dockerClient.execCreateCmd(managed.helperId())).thenReturn(exec);
            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
            when(harness.dockerClient.copyArchiveToContainerCmd(managed.helperId())).thenReturn(copy);
            List<MetadataFile> files = new CopyOnWriteArrayList<>();
            when(copy.withTarInputStream(any(InputStream.class))).thenAnswer(call -> {
                try (TarArchiveInputStream archive = new TarArchiveInputStream(call.getArgument(0))) {
                    TarArchiveEntry entry = archive.getNextEntry();
                    files.add(new MetadataFile(entry.getName(), entry.getMode(),
                            new String(archive.readAllBytes(), StandardCharsets.UTF_8)));
                }
                return copy;
            });
            when(harness.metadataServer.registerProxy(guest)).thenReturn("helper-capability");
            doCallRealMethod().when(harness.manager).configureLinkLocalMetadataEndpoint(any(), anyString());
            assertTrue(harness.manager.configureMetadataEndpoint(guest));
            verify(harness.dockerClient, times(2)).copyArchiveToContainerCmd(managed.helperId());
            verify(harness.metadataServer).registerProxy(guest);
            assertEquals(0600, files.getLast().mode());
            assertEquals("helper-capability", new JsonObject(files.getLast().content()).getString("capability"));
            assertTrue(harness.executedCommands.stream().noneMatch(command ->
                    String.join(" ", command).contains("helper-capability")));
            verify(harness.metadataServer, never()).registerContainer("10.0.1.77", guest.getInstanceId(), guest);
            verify(harness.builder).withEnv(argThat(env ->
                    env.contains("AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254")));
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void restoredNamespaceCannotSilentlyChooseAnotherTransport() throws Exception {
        ManagedHarness managed = managedHarness("i-logical-restore");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
            managed.networks().remove("vpc-transport");
            assertThrows(IllegalStateException.class, () -> harness.manager.restoreMetadataRegistration(guest));
            assertEquals("10.240.1.77", guest.getContainerBridgeIp());
            assertEquals("10.0.1.77", guest.getPrivateIpAddress());
            verify(harness.vpcNetworkManager, never()).releaseTransportPrivateIp(anyString(), anyString(), anyString());
        } finally {
            harness.manager.stop();
        }
    }

    private record ManagedHarness(LaunchHarness launch, SecurityGroupFirewallManager firewall, Instance guest,
                                  String helperId, Set<String> live, Map<String, ContainerNetwork> networks) {}

    private static ManagedHarness managedHarness(String id) throws Exception {
        return managedHarness(id, null);
    }

    private static ManagedHarness managedHarness(String id, ExecutorService executor) throws Exception {
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        LaunchHarness harness = launchHarness(executor, Duration.ofMinutes(30), firewall);
        String helperId = "helper-" + id;
        Instance guest = instance(id);
        guest.setRegion("us-west-2");
        guest.setVpcId("vpc-logical");
        guest.setSubnetId("subnet-logical");
        guest.setPrivateIpAddress("10.0.1.77");
        guest.setLogicalPrivateIpAddress("10.0.1.77");
        guest.setPrivateDnsName("ip-10-0-1-77.ec2.internal");
        InstanceNetworkInterface primary = new InstanceNetworkInterface();
        primary.setNetworkInterfaceId("eni-logical");
        primary.setOwnerId("123456789012");
        primary.setPrivateIpAddress("10.0.1.77");
        InstanceNetworkInterface secondary = new InstanceNetworkInterface();
        secondary.setDeviceIndex(1);
        secondary.setPrivateIpAddress("10.0.2.88");
        guest.setNetworkInterfaces(List.of(primary, secondary));
        when(harness.config.port()).thenReturn(4566);
        when(firewall.createNetworkNamespace(eq("ec2"), eq(id), eq("123456789012"), eq("us-west-2"),
                eq(Optional.empty()), eq(Map.of(22, 2201))))
                .thenReturn(new SecurityGroupFirewallManager.Namespace(helperId, "172.17.0.7"));
        when(harness.vpcNetworkManager.enabled()).thenReturn(true);
        when(harness.vpcNetworkManager.networkNameFor("us-west-2", "vpc-logical")).thenReturn(Optional.of("vpc-transport"));
        when(harness.vpcNetworkManager.allocateTransportPrivateIp("us-west-2", "subnet-logical", "10.0.1.77", id))
                .thenReturn(Optional.of("10.240.1.77"));
        when(harness.vpcNetworkManager.reserveTransportPrivateIp("us-west-2", "subnet-logical", "10.0.1.77", "10.240.1.77", id))
                .thenReturn(true);
        when(harness.vpcNetworkManager.attach("us-west-2", "vpc-logical", "subnet-logical", helperId, "10.240.1.77"))
                .thenReturn(Optional.of("vpc-transport"));
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.7"));
        networks.put("vpc-transport", new ContainerNetwork().withIpv4Address("10.240.1.77"));
        InspectContainerResponse helper = inspectResponse("172.17.0.7");
        when(helper.getId()).thenReturn(helperId);
        when(helper.getNetworkSettings().getNetworks()).thenReturn(networks);
        ContainerConfig helperConfig = mock(ContainerConfig.class);
        when(helper.getConfig()).thenReturn(helperConfig);
        when(helperConfig.getLabels()).thenReturn(Map.of("floci.security-group-helper", "true", "floci_owner_port", "4566",
                "io.floci.service", "ec2", "io.floci.resource-id", id, "io.floci.region", "us-west-2",
                "io.floci.account", "123456789012"));
        InspectContainerResponse worker = inspectResponse(null);
        when(worker.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode("container:" + helperId));
        Set<String> live = ConcurrentHashMap.newKeySet();
        live.addAll(List.of(TEST_CONTAINER_ID, helperId));
        when(harness.dockerClient.inspectContainerCmd(anyString())).thenAnswer(call -> {
            String container = call.getArgument(0);
            InspectContainerCmd command = mock(InspectContainerCmd.class);
            when(command.exec()).thenAnswer(inspect -> {
                if (!live.contains(container)) {
                    throw new NotFoundException("removed " + container);
                }
                return container.equals(helperId) ? helper : worker;
            });
            return command;
        });
        doAnswer(call -> { live.remove(call.getArgument(0)); return null; })
                .when(harness.lifecycleManager).removeIfExists(anyString());
        when(harness.dockerClient.stopContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StopContainerCmd.class, RETURNS_SELF));
        when(harness.dockerClient.startContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        when(harness.dockerClient.restartContainerCmd(TEST_CONTAINER_ID)).thenReturn(mock(RestartContainerCmd.class, RETURNS_SELF));
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        return new ManagedHarness(harness, firewall, guest, helperId, live, networks);
    }

    private static final String TEST_USER_DATA_OUTPUT = "test-output";
    private static final String TEST_CONTAINER_ID = "container-1";
    private static final String TEST_LOG_STREAM_NAME = "yyyy/MM/dd/user-data";
    private static final String TEST_SSH_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest test@floci";

    @org.junit.jupiter.api.AfterEach
    void resetBridgeIpPolling() {
        Ec2ContainerManager.containerBridgeIpAttempts = 30;
        Ec2ContainerManager.containerBridgeIpPollMillis = 500;
    }

    @Test
    void exposeReachablePrivateAddressUpdatesInstanceAndAttachedNetworkInterfaces() {
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21");

        assertEquals("192.168.215.21", instance.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", instance.getPrivateDnsName());
        assertEquals("192.168.215.21", networkInterface.getPrivateIpAddress());
        assertEquals("ip-192-168-215-21.ec2.internal", networkInterface.getPrivateDnsName());
    }

    @Test
    void exposeReachablePrivateAddressPreservesAllocatedIpWhenAwsFaithful() {
        // #1983: with awsFaithfulPrivateIp=true, the CFN/subnet-allocated private
        // IP set at launch is left untouched — the container bridge IP is not
        // reported (routing still uses it via containerBridgeIp, tracked elsewhere).
        Instance instance = new Instance();
        instance.setPrivateIpAddress("10.82.32.10");
        instance.setPrivateDnsName("ip-10-82-32-10.ec2.internal");

        InstanceNetworkInterface networkInterface = new InstanceNetworkInterface();
        networkInterface.setPrivateIpAddress("10.82.32.10");
        networkInterface.setPrivateDnsName("ip-10-82-32-10.ec2.internal");
        instance.setNetworkInterfaces(List.of(networkInterface));

        Ec2ContainerManager.exposeReachablePrivateAddress(instance, "192.168.215.21", true);

        assertEquals("10.82.32.10", instance.getPrivateIpAddress());
        assertEquals("ip-10-82-32-10.ec2.internal", instance.getPrivateDnsName());
        assertEquals("10.82.32.10", networkInterface.getPrivateIpAddress());
    }

    @Test
    void restoreMetadataRegistrationRegistersRunningPersistedContainer() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-restored");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("192.168.215.7");

        assertTrue(networkRegistrationManager(manager).restoreMetadataRegistration(instance));

        assertEquals("192.168.215.42", instance.getContainerBridgeIp());
        assertEquals("192.168.215.42", instance.getPrivateIpAddress());
        verify(metadataServer).unregisterContainer("192.168.215.7", instance);
        verify(metadataServer).registerContainer("192.168.215.42", "i-restored", instance);
    }

    @Test
    void restoreMetadataRegistrationAlsoRegistersTheAddressImdsRequestsArriveFrom() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.4");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-vpc-restored");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.2");

        assertTrue(manager.restoreMetadataRegistration(instance));

        // The VPC address is what Floci reports, but the container's default route is the bridge,
        // so that is the source address Ec2MetadataServer resolves the instance by.
        verify(metadataServer).registerContainer("10.0.1.10", "i-vpc-restored", instance);
        verify(metadataServer).registerContainer("172.17.0.4", "i-vpc-restored", instance);
        verify(metadataServer).unregisterContainer("172.17.0.2", instance);
        assertEquals("172.17.0.4", instance.getImdsSourceIp());
    }

    @Test
    void restoreRegistersAllNetworksAndReplacesStaleSharedAddress() {
        for (boolean sharedFirst : List.of(true, false)) {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);
            DockerClient docker = mock(DockerClient.class);
            InspectContainerCmd command = mock(InspectContainerCmd.class);
            when(docker.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(command);
            InspectContainerResponse response = mock(InspectContainerResponse.class);
            NetworkSettings settings = mock(NetworkSettings.class);
            when(response.getNetworkSettings()).thenReturn(settings);
            Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
            networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.4"));
            String first = sharedFirst ? "shared" : "vpc";
            String second = sharedFirst ? "vpc" : "shared";
            networks.put(first, new ContainerNetwork().withIpv4Address(sharedFirst ? "192.0.2.10" : "10.0.1.10"));
            networks.put(second, new ContainerNetwork().withIpv4Address(sharedFirst ? "10.0.1.10" : "192.0.2.10"));
            when(settings.getNetworks()).thenReturn(networks);
            when(command.exec()).thenReturn(response);
            Ec2MetadataServer server = new Ec2MetadataServer(null, null, null);
            Ec2ContainerManager manager = managerWith(lifecycle, docker, server);
            Instance guest = instance("i-multinetwork");
            guest.setDockerContainerId(TEST_CONTAINER_ID);
            server.registerContainer("192.0.2.9", guest.getInstanceId(), guest);

            assertTrue(manager.restoreMetadataRegistration(guest));

            for (String address : List.of("172.17.0.4", "10.0.1.10", "192.0.2.10")) {
                assertEquals(guest, server.registeredContainer(address).orElseThrow());
            }
            assertTrue(server.registeredContainer("192.0.2.9").isEmpty());
        }
    }

    @Test
    void restoreMetadataRegistrationLeavesABridgeOnlyInstanceWithNoSeparateImdsSource() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerResponse response = inspectResponse("172.17.0.4");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-bridge-only");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("172.17.0.4");

        assertTrue(manager.restoreMetadataRegistration(instance));

        assertNull(instance.getImdsSourceIp(), "one address means one registration");
        verify(metadataServer).registerContainer("172.17.0.4", "i-bridge-only", instance);
        verify(metadataServer, never()).unregisterContainer(anyString(), any(Instance.class));
    }

    @Test
    void restoreMetadataRegistrationKeepsTheImdsSourceWhenTheContainerCannotBeInspected() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new DockerException("dial unix /var/run/docker.sock: EOF", 500));

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-inspect-failed");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");

        assertTrue(manager.restoreMetadataRegistration(instance));

        // A failed inspect leaves the bridge attachment unknown, not known to be absent. Tearing
        // the registration down here would stop this healthy instance's metadata requests
        // resolving, with nothing registered in place of what was removed.
        verify(metadataServer, never()).unregisterContainer("172.17.0.4", instance);
        verify(metadataServer, never()).reconcileContainerAddresses(anySet(), any());
        assertEquals("172.17.0.4", instance.getImdsSourceIp(),
                "a transient Docker failure must not drop an existing IMDS source registration");
    }

    @Test
    void startRegistersTheNewBridgeAddressDockerHandsOutOnRestart() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        // The VPC address is static, so it survives the stop; the bridge address does not.
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.9");
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = managerWith(lifecycleManager, dockerClient, metadataServer);

        Instance instance = new Instance();
        instance.setInstanceId("i-vpc-started");
        instance.setDockerContainerId(TEST_CONTAINER_ID);
        instance.setContainerBridgeIp("10.0.1.10");
        instance.setImdsSourceIp("172.17.0.4");

        manager.start(instance);
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));

        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.9", "i-vpc-started", instance);
        verify(metadataServer, timeout(2000)).unregisterContainer("172.17.0.4", instance);
        verify(metadataServer, timeout(2000)).reconcileContainerAddresses(
                Set.of("10.0.1.10", "172.17.0.9"), instance);
        assertEquals("172.17.0.9", instance.getImdsSourceIp(),
                "the stale address would otherwise stay registered while IMDS requests arrive "
                        + "from an address nothing knows about");
    }

    @Test
    void startOfAProtectedInstanceReusesTheHelperTransportAddress() throws Exception {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.startContainerCmd(TEST_CONTAINER_ID))
                .thenReturn(mock(StartContainerCmd.class, RETURNS_SELF));
        // A workload in the helper's namespace has no Docker attachment of its own, so there is
        // no bridge address on it to rediscover after the restart.
        InspectContainerResponse workload = mock(InspectContainerResponse.class);
        HostConfig hostConfig = mock(HostConfig.class);
        when(hostConfig.getNetworkMode()).thenReturn("container:helper-1");
        when(workload.getHostConfig()).thenReturn(hostConfig);
        InspectContainerCmd inspectWorkload = mock(InspectContainerCmd.class);
        when(inspectWorkload.exec()).thenReturn(workload);
        when(dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspectWorkload);

        InspectContainerResponse helper = mock(InspectContainerResponse.class);
        ContainerConfig helperConfig = mock(ContainerConfig.class);
        when(helperConfig.getLabels()).thenReturn(Map.of(
                "floci.security-group-helper", "true",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-protected",
                "floci_owner_port", "4566"));
        when(helper.getConfig()).thenReturn(helperConfig);
        NetworkSettings helperNetworks = mock(NetworkSettings.class);
        when(helperNetworks.getNetworks())
                .thenReturn(Map.of("bridge", new ContainerNetwork().withIpv4Address("172.17.0.7")));
        when(helper.getNetworkSettings()).thenReturn(helperNetworks);
        InspectContainerCmd inspectHelper = mock(InspectContainerCmd.class);
        when(inspectHelper.exec()).thenReturn(helper);
        when(dockerClient.inspectContainerCmd("helper-1")).thenReturn(inspectHelper);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        Ec2ContainerManager manager = new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                config,
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class),
                mock(SecurityGroupFirewallManager.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-protected");
        instance.setDockerContainerId(TEST_CONTAINER_ID);

        manager.start(instance);
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(5));

        verify(metadataServer, timeout(2000)).registerContainer("172.17.0.7", "i-protected", instance);
        assertEquals("172.17.0.7", instance.getContainerBridgeIp(),
                "StartInstances must keep addressing the instance through its protected namespace");
    }

    private static Ec2ContainerManager managerWith(ContainerLifecycleManager lifecycleManager,
                                                   DockerClient dockerClient,
                                                   Ec2MetadataServer metadataServer) {
        return networkRegistrationManager(new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                metadataServer,
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class)));
    }

    private static Ec2ContainerManager networkRegistrationManager(Ec2ContainerManager manager) {
        Ec2ContainerManager isolated = spy(manager);
        // Network-registration tests isolate the Docker guest installation boundary.
        doReturn(true).when(isolated).configureMetadataEndpoint(any());
        return isolated;
    }

    /** A container on both its VPC network and the default bridge, which is what launch leaves. */
    private static InspectContainerResponse vpcAttachedInspectResponse(String vpcIp, String bridgeIp) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getNetworks()).thenReturn(Map.of(
                "floci-vpc-4566-us-west-2-vpc-1", new ContainerNetwork().withIpv4Address(vpcIp),
                "bridge", new ContainerNetwork().withIpv4Address(bridgeIp)));
        return inspect;
    }

    @Test
    void userDataExecutionCommandRunsScriptDirectlySoShebangIsHonored() {
        assertArrayEquals(new String[]{"/var/lib/user-data.sh"}, Ec2ContainerManager.userDataExecutionCommand());
    }

    @Test
    void userDataOutputSummaryRetainsBoundedTailAndReportsTruncation() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("0123456789".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 8 bytes)\n23456789",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryRetainsTailAcrossFrames() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("1234".getBytes(StandardCharsets.UTF_8));
        output.write("567890".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 8 bytes)\n34567890",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryPreservesOutputWithinLimit() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(8);
        output.write("🙂".getBytes(StandardCharsets.UTF_8));

        assertEquals("🙂", Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataOutputSummaryStartsAtUtf8CharacterBoundary() throws Exception {
        Ec2ContainerManager.BoundedOutput output = new Ec2ContainerManager.BoundedOutput(4);
        output.write("🙂YZ".getBytes(StandardCharsets.UTF_8));

        assertEquals("(output truncated; showing last 4 bytes)\nYZ",
                Ec2ContainerManager.summarizeUserDataOutput(output));
    }

    @Test
    void userDataShellScriptsPreservesRawShellScript() {
        String script = "#!/bin/bash\nset -euo pipefail\necho ready\n";

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(script));
    }

    @Test
    void userDataShellScriptsExtractsMimeShellscriptPartsInOrder() {
        String userData = """
                Content-Type: multipart/mixed; boundary="==SAMPLE-CLOUD-INIT=="
                MIME-Version: 1.0

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/cloud-config; charset="us-ascii"

                #cloud-config

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript; charset="us-ascii"

                #!/bin/bash
                echo first

                --==SAMPLE-CLOUD-INIT==
                Content-Type: text/x-shellscript

                #!/bin/sh
                echo second

                --==SAMPLE-CLOUD-INIT==--
                """;

        assertEquals(
                List.of(
                        "#!/bin/bash\necho first\n",
                        "#!/bin/sh\necho second\n"),
                Ec2ContainerManager.userDataShellScripts(userData));
    }

    private static String gzipBase64(String plain) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static final String CLOUDINIT_MULTIPART = """
            Content-Type: multipart/mixed; boundary="MIMEBOUNDARY"
            MIME-Version: 1.0

            --MIMEBOUNDARY
            Content-Disposition: attachment; filename="bastion-default-cloud-init"
            Content-Transfer-Encoding: 7bit
            Content-Type: text/x-shellscript
            Mime-Version: 1.0

            #!/bin/bash
            apt-get install -y redis-tools

            --MIMEBOUNDARY--
            """;

    @Test
    void userDataShellScriptsDecodesBase64OfGzippedCloudInit() throws Exception {
        // What data.cloudinit_config { gzip = true, base64_encode = true } renders, as it
        // reaches the launch-configuration path that stores UserData still wire-encoded.
        assertEquals(
                List.of("#!/bin/bash\napt-get install -y redis-tools\n"),
                Ec2ContainerManager.userDataShellScripts(gzipBase64(CLOUDINIT_MULTIPART)));
    }

    @Test
    void userDataShellScriptsDiscardsGzipBombRatherThanExhaustingHeap() throws Exception {
        // A highly-compressible payload well past the 10 MB decompressed cap this guards
        // against: ~50 MB of a repeated character gzips down to a few KB on the wire, so an
        // Auto Scaling launch configuration can smuggle it through in a single UserData field.
        // Without the bound, GZIPInputStream#readAllBytes materializes the full expansion in
        // the shared emulator JVM. With it, decoding gives up and no scripts are extracted.
        String bomb = "A".repeat(50 * 1024 * 1024);

        assertEquals(List.of(), Ec2ContainerManager.userDataShellScripts(gzipBase64(bomb)));
    }

    @Test
    void userDataShellScriptsBoundsAggregateConcurrentDecompression() throws Exception {
        // Each launch decodes its UserData independently on Ec2ContainerManager's unbounded
        // cached launch executor, so the per-payload 10 MB cap alone does not stop many
        // concurrent launches from each decompressing near that limit at once (Greptile's
        // follow-up on the gzip-bomb fix). Drive real concurrent decompressions through the
        // public entry point and use the test hook to prove no more than
        // MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS (4) ever run at the same instant, while still
        // exercising genuine concurrency rather than serialized calls.
        int concurrentLaunches = 12;
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger peakActive = new AtomicInteger(0);
        Ec2ContainerManager.userDataDecompressionTestHook = () -> {
            int now = active.incrementAndGet();
            peakActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(75);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        List<List<String>> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrentLaunches);
        try {
            // ~9 MB of a repeated character: near the 10 MB per-payload cap, well within it.
            String nearCapPayload = "A".repeat(9 * 1024 * 1024);
            String gzipped = gzipBase64(nearCapPayload);

            List<Future<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentLaunches; i++) {
                futures.add(pool.submit(() -> Ec2ContainerManager.userDataShellScripts(gzipped)));
            }
            for (Future<List<String>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            Ec2ContainerManager.userDataDecompressionTestHook = null;
        }

        assertEquals(concurrentLaunches, results.size());
        assertTrue(peakActive.get() <= 4,
                "peak concurrent UserData decompressions was " + peakActive.get() + ", expected <= 4");
        assertTrue(peakActive.get() >= 2,
                "test did not exercise real concurrency; peak concurrent decompressions was only "
                        + peakActive.get());
    }

    @Test
    void userDataShellScriptsDecodesPlainBase64ShellScript() {
        String script = "#!/bin/bash\necho ready\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(encoded));
    }

    @Test
    void userDataShellScriptsLeavesAccidentallyBase64ShapedScriptAlone() {
        // Valid base64 by shape, but decodes to nothing cloud-init would recognise, so it must
        // be treated as the literal script it is rather than decoded into binary noise.
        String script = "#!/bin/sh\necho deadbeefdeadbeef\n";

        assertEquals(List.of(script), Ec2ContainerManager.userDataShellScripts(script));
    }

    @Test
    void userDataShellScriptsIgnoresGarbage() {
        assertTrue(Ec2ContainerManager.userDataShellScripts("not a script at all").isEmpty());
    }

    @Test
    void reachablePublicAddressReportsContainerIpWhenRoutable() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("192.168.215.2")).thenReturn(true);
        Ec2ContainerManager manager = managerWithReachability(reachability);

        Instance instance = new Instance();
        instance.setContainerBridgeIp("192.168.215.2");

        assertEquals("192.168.215.2", manager.reachablePublicAddress(instance));
        manager.exposeReachablePublicAddress(instance);
        assertEquals("192.168.215.2", instance.getPublicIpAddress());
        assertEquals("192.168.215.2", instance.getPublicDnsName());
    }

    @Test
    void reachablePublicAddressFallsBackToLoopbackWhenContainerNetworkIsUnroutable() {
        ContainerNetworkReachability reachability = mock(ContainerNetworkReachability.class);
        when(reachability.isContainerIpRoutable("192.168.215.2")).thenReturn(false);
        Ec2ContainerManager manager = managerWithReachability(reachability);

        Instance instance = new Instance();
        instance.setContainerBridgeIp("192.168.215.2");

        assertEquals("127.0.0.1", manager.reachablePublicAddress(instance));
        manager.exposeReachablePublicAddress(instance);
        assertEquals("127.0.0.1", instance.getPublicIpAddress());
        assertEquals("localhost", instance.getPublicDnsName());
    }

    @Test
    void refreshingReachablePublicAddressPreservesHostedInstanceRouting() {
        Ec2ContainerManager manager = managerWithReachability(mock(ContainerNetworkReachability.class));
        Instance instance = new Instance();
        instance.setInstanceId("i-hosted");
        instance.setContainerBridgeIp("192.168.215.2");
        Ec2Service.assignAutoPublicAddress(instance);

        manager.exposeReachablePublicAddress(instance);

        assertEquals("i-hosted.localhost.floci.io", instance.getPublicIpAddress());
        assertEquals("i-hosted.localhost.floci.io", instance.getPublicDnsName());
    }

    @Test
    void reachablePublicAddressIsNullBeforeAContainerExists() {
        Ec2ContainerManager manager = managerWithReachability(mock(ContainerNetworkReachability.class));

        assertEquals(null, manager.reachablePublicAddress(new Instance()));
        assertEquals(null, manager.reachablePublicAddress(null));
    }

    private static Ec2ContainerManager managerWithReachability(ContainerNetworkReachability reachability) {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                mock(DockerClient.class),
                mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                reachability,
                mock(VpcNetworkManager.class),
                mock(ContainerReachableEndpoint.class));
    }

    @Test
    void userDataShellScriptsIgnoresCloudConfigWithoutShellscript() {
        String userData = """
                Content-Type: multipart/mixed; boundary=cloudinit
                MIME-Version: 1.0

                --cloudinit
                Content-Type: text/cloud-config

                #cloud-config

                --cloudinit--
                """;

        assertTrue(Ec2ContainerManager.userDataShellScripts(userData).isEmpty());
    }

    @Test
    void launchReturnsTheLeaseWhenTheContainerNeverJoinsTheVpcNetwork() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerResponse noIp = inspectResponse(null);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);
        // attach() returns empty: nothing holds the address, and the instance is about to start
        // reporting its bridge address instead, so the lease is no longer findable from it.

        Instance instance = leasedInstance("i-noattach");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.vpcNetworkManager, timeout(2000).atLeastOnce())
                .releasePrivateIp("us-west-2", "subnet-lease", "10.0.1.10");
    }

    @Test
    void launchReturnsTheLeaseWhenTheLaunchThrows() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.builder.build()).thenThrow(new IllegalStateException("daemon went away"));

        Instance instance = leasedInstance("i-throws");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        // Detach before release: Docker holds the address while the endpoint stands, so handing it
        // to the next launch before disconnecting would have the daemon refuse it.
        verify(harness.vpcNetworkManager, timeout(2000)).detach("us-west-2", "vpc-lease", null);
        verify(harness.vpcNetworkManager, timeout(2000))
                .releasePrivateIp("us-west-2", "subnet-lease", "10.0.1.10");
    }

    private static Instance leasedInstance(String instanceId) {
        Instance instance = instance(instanceId);
        instance.setRegion("us-west-2");
        instance.setVpcId("vpc-lease");
        instance.setSubnetId("subnet-lease");
        instance.setPrivateIpAddress("10.0.1.10");
        return instance;
    }

    @Test
    void launchInstanceUserDataStreamToCloudWatch() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        // manually set up container bridge IP
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        String instanceId = "i-userdatacloudwatch";
        Instance instance = instance(instanceId);
        instance.setUserData("""
                #!/bin/bash
                echo test
                """);
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));

        verify(harness.logStreamer, timeout(2000)).streamToCloudWatchLogs(
            any(String.class), any(String.class), eq("us-west-2"), eq(TEST_USER_DATA_OUTPUT)
        );
        int filesystem = commandIndex(harness.executedCommands, Ec2ContainerManager.prepareGuestFilesystemCommand());
        int shim = commandIndex(harness.executedCommands, Ec2ContainerManager.systemctlShimInstallCommand());
        int userData = commandIndex(harness.executedCommands, Ec2ContainerManager.userDataExecutionCommand());
        assertTrue(filesystem >= 0 && filesystem < shim && shim < userData,
                "Filesystem and systemctl preparation must precede user data");
    }

    @Test
    void failedStockImagePreparationDoesNotCreateOrStartGuest() throws Exception {
        ManagedHarness managed = managedHarness("i-tools-failure");
        LaunchHarness harness = managed.launch();
        Instance guest = managed.guest();
        doThrow(new IllegalStateException("Docker build failed")).when(harness.manager).prepareImage(any());
        guest.setUserData("#!/bin/sh\nprintf 'must not run'\n");
        try {
            harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");
            awaitUntil(guest::isContainerLaunchFailed, Duration.ofSeconds(2));
            verify(harness.lifecycleManager, never()).create(any(ContainerSpec.class));
            verify(harness.lifecycleManager, never()).startCreated(anyString(), any(ContainerSpec.class));
            verify(harness.dockerClient, never()).execCreateCmd(anyString());
        } finally {
            harness.manager.stop();
        }
    }

    @Test
    void restrictedGuestUsesSuppliedToolsAndPreparesFilesystemBeforeRunning() throws Exception {
        for (String image : List.of("ubuntu:24.04", "custom/guest:latest", "floci-ami:captured")) {
            ManagedHarness managed = managedHarness("i-offline");
            LaunchHarness harness = managed.launch();
            Instance guest = managed.guest();
            when(managed.firewall().enabled()).thenReturn(true);
            doAnswer(call -> {
                assertEquals("pending", guest.getState().getName());
                int filesystem = commandIndex(harness.executedCommands, Ec2ContainerManager.prepareGuestFilesystemCommand());
                int shim = commandIndex(harness.executedCommands, Ec2ContainerManager.systemctlShimInstallCommand());
                assertTrue(filesystem >= 0 && filesystem < shim);
                return true;
            }).when(harness.manager).configureLinkLocalMetadataEndpoint(guest, managed.helperId());
            try {
                harness.manager.launch(guest, ResolvedAmiImage.minimal(image), null, "us-west-2",
                        Set.of(), List.of(), Map.of());
                awaitUntil(() -> commandIndex(harness.executedCommands, new String[]{"/usr/sbin/sshd"}) >= 0,
                        Duration.ofSeconds(2));
                assertEquals("running", guest.getState().getName());
                assertTrue(harness.executedCommands.stream().noneMatch(command ->
                        String.join(" ", command).matches("(?s).*\\b(apt-get|dnf|yum|apk)\\b.*")));
                verify(managed.firewall()).register(any(), eq(managed.helperId()), eq(Map.of()));
                verify(harness.builder).withNetworkMode("container:" + managed.helperId());
                verify(harness.builder).withPrivileged(false);
            } finally {
                harness.manager.stop();
            }
        }
    }

    @Test
    void launchLabelsContainerWithResourceIdentity() throws Exception {
        LaunchHarness harness = launchHarness();
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = inspectResponse("192.168.215.42");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);

        Instance instance = instance("i-labeltest");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        verify(harness.builder, timeout(2000)).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "ec2",
                "io.floci.resource-id", "i-labeltest",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-west-2"));
    }

    @Test
    void metadataProxyInstallCommandInstallsLinkLocalProxyDependencies() {
        String[] command = Ec2ContainerManager.metadataProxyInstallCommand();

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("iproute2"));
        assertTrue(command[2].contains("socat"));
        assertTrue(command[2].contains("curl"));
    }

    @Test
    void sshdProbeUsesSuppliedServerAndClientWithoutPackageDownloads() {
        assertEquals("command -v sshd >/dev/null 2>&1 || exit 1;"
                + "command -v scp >/dev/null 2>&1 || exit "
                + Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE,
                Ec2ContainerManager.sshdInstallProbeCommand()[2]);
    }

    @Test
    void metadataProxyInstallCommandHandlesYumForAmazonLinux2() {
        // The IMDS proxy dependency install had the same gap, and unlike the sshd probe it
        // ends in an explicit failure: on an ami-amazonlinux2 instance it reached the else
        // branch and exited 1 with "No supported package manager found for IMDS proxy
        // dependencies", leaving the instance without a link-local metadata endpoint.
        String script = Ec2ContainerManager.metadataProxyInstallCommand()[2];

        assertTrue(script.contains("command -v yum"), script);
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"), script);
    }

    @Test
    void metadataProxyInstallCommandLetsDnfReplaceCurlMinimalWithCurl() {
        // public.ecr.aws/amazonlinux/amazonlinux:2023 -- the fallback image AmiImageResolver
        // uses for any unrecognized AMI ID -- ships curl-minimal by default. Without
        // --allowerasing, "dnf install -y iproute socat curl ca-certificates" fails the whole
        // transaction on a curl/curl-minimal conflict (they both provide /usr/bin/curl), so
        // iproute and socat never install either, even though neither of them conflicts with
        // anything. Reproduced against the real image: dnf reported dozens of
        // "package curl-minimal-... conflicts with curl provided by curl-..." lines and the
        // instance was left with no link-local IMDS endpoint.
        String script = Ec2ContainerManager.metadataProxyInstallCommand()[2];

        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"),
                script);
    }

    @Test
    void metadataProxyStartCommandBindsAwsLinkLocalMetadataAddress() {
        String[] command = Ec2ContainerManager.metadataProxyStartCommand("floci", 9169);

        assertEquals("sh", command[0]);
        assertTrue(command[2].contains("169.254.169.254/32"));
        assertTrue(command[2].contains("TCP-LISTEN:80,bind=169.254.169.254"));
        assertTrue(command[2].contains("TCP:floci:9169"));
        assertTrue(command[2].contains("http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void instanceProfileEnvironmentLetsTheSdkUseImds() {
        List<String> environment = Ec2ContainerManager.localAwsEnvironment(
                "us-west-2", "http://floci:4566", "http://floci:9169", true);
        assertTrue(environment.contains("AWS_EC2_METADATA_SERVICE_ENDPOINT=http://floci:9169"));
        assertTrue(environment.contains("AWS_ENDPOINT_URL=http://floci:4566"));
        assertFalse(environment.stream().anyMatch(value -> value.startsWith("AWS_ACCESS_KEY_ID=")
                || value.startsWith("AWS_SECRET_ACCESS_KEY=") || value.startsWith("AWS_SESSION_TOKEN=")));
    }

    @Test
    void localAwsEnvironmentProvidesCliCredentialsAndFlociEndpoint() {
        assertEquals(
                java.util.List.of(
                        "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://floci:9169",
                        "AWS_ENDPOINT_URL=http://floci:4566",
                        "AWS_DEFAULT_REGION=us-west-2",
                        "AWS_REGION=us-west-2",
                        "AWS_ACCESS_KEY_ID=test",
                        "AWS_SECRET_ACCESS_KEY=test",
                        "AWS_SESSION_TOKEN=test-session-token"),
                Ec2ContainerManager.localAwsEnvironment(
                        "us-west-2",
                        "http://floci:4566",
                        "http://floci:9169"));
    }

    @Test
    void launchUsesTheSharedServiceEndpointAndImmutableLinkLocalImdsEndpoint() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.13");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-endpoint");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withEnv(List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                "AWS_ENDPOINT_URL=http://localhost.floci.io:4680",
                "AWS_DEFAULT_REGION=us-west-2",
                "AWS_REGION=us-west-2",
                "AWS_ACCESS_KEY_ID=test",
                "AWS_SECRET_ACCESS_KEY=test",
                "AWS_SESSION_TOKEN=test-session-token"));
    }

    @Test
    void launchSystemdGuestUsesInitInsteadOfTail() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.create(any(ContainerSpec.class), eq("linux/arm64")))
                .thenReturn(TEST_CONTAINER_ID);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.11");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-systemd");

        harness.manager.launch(instance,
                new ResolvedAmiImage("floci/ami-ubuntu:24.04-arm64", ResolvedAmiImage.SYSTEMD_RUNTIME, true,
                        "linux/arm64"),
                null,
                "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.builder).withCmd(List.of("/sbin/init"));
        verify(harness.builder).withCgroupnsMode("host");
        verify(harness.builder).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        verify(harness.lifecycleManager).create(any(ContainerSpec.class), eq("linux/arm64"));
    }

    @Test
    void preferredMetadataSourceIpUsesConfiguredNetworkBeforeBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");
        ContainerNetwork floci = new ContainerNetwork();
        floci.withIpv4Address("192.168.215.10");

        assertEquals(
                "192.168.215.10",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of(
                        "bridge", bridge,
                        "custom-floci-network", floci)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.8");

        assertEquals(
                "172.17.0.8",
                Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).orElseThrow());
    }

    @Test
    void preferredMetadataSourceIpIsEmptyWithoutUsableAddress() {
        ContainerNetwork bridge = new ContainerNetwork();

        assertTrue(Ec2ContainerManager.preferredMetadataSourceIp(Map.of("bridge", bridge)).isEmpty());
    }

    @Test
    void launchRegistersAllGuestAddressesBeforeUserData() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        InspectContainerResponse response = vpcAttachedInspectResponse("10.0.1.10", "172.17.0.4");
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>(response.getNetworkSettings().getNetworks());
        networks.put("shared", new ContainerNetwork().withIpv4Address("192.0.2.10"));
        when(response.getNetworkSettings().getNetworks()).thenReturn(networks);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(command);
        when(command.exec()).thenReturn(response);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance guest = instance("i-three-networks");

        harness.manager.launch(guest, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(guest.getState().getName()), Duration.ofSeconds(2));
        verify(harness.metadataServer).reconcileContainerAddresses(
                Set.of("10.0.1.10", "172.17.0.4", "192.0.2.10"), guest);
    }

    @Test
    void launchWaitsForContainerBridgeIpBeforeRegisteringImds() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 3;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        InspectContainerResponse withIp = inspectResponse("172.18.0.9");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-waitip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(TEST_CONTAINER_ID, instance.getDockerContainerId());
        assertEquals("172.18.0.9", instance.getContainerBridgeIp());
        assertEquals("172.18.0.9", instance.getPrivateIpAddress());
        verify(inspect, times(3)).exec();
        verify(harness.metadataServer).registerContainer("172.18.0.9", "i-waitip", instance);
    }

    @Test
    void launchTerminatesWhenContainerBridgeIpNeverAppears() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);

        Instance instance = instance("i-noip");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        assertNull(instance.getDockerContainerId());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchKeepsControlPlaneWhenContainerBridgeIpNeverAppears() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse noIp = inspectResponse(null);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(noIp);

        Instance instance = instance("i-noip");
        instance.setState(InstanceState.running());

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        assertNull(instance.getDockerContainerId());
        assertEquals("running", instance.getState().getName());
        verify(harness.metadataServer, never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchRetriesWithNextPortWhenDockerReportsHostPortCollision() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        when(harness.portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201, 2202);
        when(harness.lifecycleManager.create(any(ContainerSpec.class)))
                .thenReturn("container-conflict", TEST_CONTAINER_ID);
        when(harness.lifecycleManager.startCreated(anyString(), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Bind for 0.0.0.0:2201 failed: port is already allocated"))
                .thenReturn(null);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        Instance instance = instance("i-port-collision");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        assertEquals(2202, instance.getSshHostPort());
        verify(harness.lifecycleManager).removeIfExists("container-conflict");
        verify(harness.portAllocator).markReserved(2201);
        verify(harness.builder).withPortBinding(22, 2201);
        verify(harness.builder).withPortBinding(22, 2202);
    }

    @Test
    void launchReleasesPortAndCleansUpContainerAfterNonRetryableStartFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));

        Instance instance = instance("i-start-failure");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);
    }

    @Test
    void launchTerminatesBeforeCleanupFailure() throws Exception {
        LaunchHarness harness = launchHarness();
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("Docker daemon unavailable"));
        doThrow(new RuntimeException("port-forward cleanup failed"))
                .when(harness.portForwardManager).unpublishAll(any(Instance.class));

        Instance instance = instance("i-cleanup-failure");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
        verify(harness.portForwardManager).unpublishAll(instance);
    }

    @Test
    void cancelledLaunchRemovesContainerThatStartsAfterCancellation() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStart = new CountDownLatch(1);
        when(harness.lifecycleManager.startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class))).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(allowStart.await(2, TimeUnit.SECONDS));
            return null;
        });

        Instance instance = instance("i-cancelled-launch");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(startEntered.await(2, TimeUnit.SECONDS), "container startup should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        verify(harness.lifecycleManager).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator).release(2201);

        allowStart.countDown();
        verify(harness.portAllocator, after(500).times(1)).release(2201);
    }

    @Test
    void cancelledLaunchRemovesContainerCreatedAfterCancellation() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            assertTrue(allowCreate.await(2, TimeUnit.SECONDS));
            return TEST_CONTAINER_ID;
        });

        Instance instance = instance("i-cancelled-during-create");
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(createEntered.await(2, TimeUnit.SECONDS), "container creation should begin");
        assertTrue(harness.manager.cancelLaunch(instance));
        assertEquals("terminated", instance.getState().getName());

        allowCreate.countDown();
        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        verify(harness.lifecycleManager, never()).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
        assertEquals("terminated", instance.getState().getName());
    }

    @Test
    void cancelledGuestLaunchPreservesReadyControlPlaneAndReleasesItsLease() throws Exception {
        LaunchHarness harness = launchHarness();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        when(harness.lifecycleManager.create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            assertTrue(allowCreate.await(2, TimeUnit.SECONDS));
            return TEST_CONTAINER_ID;
        });

        Instance instance = leasedInstance("i-ready-cancelled");
        instance.setState(InstanceState.running());
        Ec2Service.assignAutoPublicAddress(instance);
        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        try {
            assertTrue(createEntered.await(2, TimeUnit.SECONDS), "container creation should begin");
            assertTrue(harness.manager.cancelLaunch(instance));
            assertEquals("running", instance.getState().getName());
            assertEquals("i-ready-cancelled.localhost.floci.io", instance.getPublicIpAddress());
            assertTrue(instance.isContainerLaunchFailed());
            assertFalse(instance.isContainerLaunchPending());
            verify(harness.vpcNetworkManager).releasePrivateIp("us-west-2", "subnet-lease", "10.0.1.10");
        } finally {
            allowCreate.countDown();
        }

        verify(harness.lifecycleManager, timeout(2_000)).removeIfExists(TEST_CONTAINER_ID);
        verify(harness.portAllocator, timeout(2_000)).release(2201);
        verify(harness.lifecycleManager, never()).startCreated(eq(TEST_CONTAINER_ID), any(ContainerSpec.class));
        assertEquals("running", instance.getState().getName());
    }

    @Test
    void launchMarksInstanceRunningBeforeUserDataCompletes() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 2;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        CountDownLatch finishUserData = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, finishUserData);
        Instance instance = instance("i-userdata");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
        assertEquals("running", instance.getState().getName());
        assertFalse(finishUserData.await(10, TimeUnit.MILLISECONDS), "user data should still be blocked");
        finishUserData.countDown();
        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    @Test
    void userDataIsCopiedAndExecutedOutsideGuestTemporaryMounts() throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.10");
        when(inspect.exec()).thenReturn(withIp);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        harness.stubSuccessfulExecs(userDataStarted, new CountDownLatch(0));
        CopyArchiveToContainerCmd copy = harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID);
        Instance instance = instance("i-userdata-persistent-path");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager.launch(instance, "amazonlinux:2023", null, "us-west-2");

        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
        verify(copy).withRemotePath("/var/lib");
        verify(copy, never()).withRemotePath("/tmp");
        assertTrue(harness.executedCommands.stream()
                .anyMatch(command -> Arrays.equals(command, new String[]{"/var/lib/user-data.sh"})));
    }

    @Test
    void launchAppliesBackpressureWhenDockerLaunchesAreSaturated() throws Exception {
        ThreadPoolExecutor launchExecutor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                Ec2ContainerManager.BLOCKING_BACKPRESSURE);
        LaunchHarness harness = launchHarness(launchExecutor, Duration.ofSeconds(1));
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(harness.lifecycleManager().create(any(ContainerSpec.class))).thenAnswer(invocation -> {
            createEntered.countDown();
            releaseCreate.await(2, TimeUnit.SECONDS);
            throw new RuntimeException("test launch failure");
        });

        try {
            harness.manager().launch(instance("i-backpressure-1"), "ubuntu:24.04", null, "us-west-2");
            harness.manager().launch(instance("i-backpressure-2"), "ubuntu:24.04", null, "us-west-2");
            Future<?> waitingLaunch = callerExecutor.submit(() ->
                    harness.manager().launch(instance("i-backpressure-3"), "ubuntu:24.04", null, "us-west-2"));

            assertTrue(createEntered.await(2, TimeUnit.SECONDS), "worker launch should enter Docker launch");
            assertFalse(waitingLaunch.isDone(), "saturated launch should wait for queue capacity");

            releaseCreate.countDown();
            waitingLaunch.get(2, TimeUnit.SECONDS);
        } finally {
            releaseCreate.countDown();
            harness.manager().stop();
            callerExecutor.shutdownNow();
            launchExecutor.shutdownNow();
        }
    }

    @Test
    void userDataTimeoutClosesDockerExecStream() throws Exception {
        LaunchHarness harness = launchHarness(new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.CallerRunsPolicy()), Duration.ofMillis(50));
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient().inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.30");
        when(inspect.exec()).thenReturn(withIp);
        Closeable stream = mock(Closeable.class);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        stubUserDataCallback(harness, stream, userDataStarted);
        Instance instance = instance("i-userdata-timeout");
        instance.setUserData("#!/bin/sh\necho ready\n");

        try {
            harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
            assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");
            verify(stream, timeout(2_000)).close();
        } finally {
            harness.manager().stop();
        }
    }

    @Test
    void shutdownClosesActiveUserDataExecStream() throws Exception {
        LaunchHarness harness = launchHarness();
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(harness.dockerClient().inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        InspectContainerResponse withIp = inspectResponse("172.18.0.31");
        when(inspect.exec()).thenReturn(withIp);
        Closeable stream = mock(Closeable.class);
        CountDownLatch userDataStarted = new CountDownLatch(1);
        stubUserDataCallback(harness, stream, userDataStarted);
        Instance instance = instance("i-userdata-shutdown");
        instance.setUserData("#!/bin/sh\necho ready\n");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        assertTrue(userDataStarted.await(2, TimeUnit.SECONDS), "user data should start");

        harness.manager().stop();

        verify(stream, timeout(2_000)).close();
    }

    @Test
    void launchCreatesSshdPrivilegeSeparationDirectoryBeforeStartingSshd() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));

        harness.manager.launch(instance("i-sshd"), "ubuntu:24.04", TEST_SSH_PUBLIC_KEY, "us-west-2");

        awaitUntil(() -> commandIndex(harness.executedCommands, "/usr/sbin/sshd") >= 0, Duration.ofSeconds(2));

        int mkdirIndex = commandIndex(harness.executedCommands, "mkdir", "-p", "/run/sshd");
        int sshdIndex = commandIndex(harness.executedCommands, "/usr/sbin/sshd");
        assertTrue(mkdirIndex >= 0,
                "sshd startup should create the /run/sshd privilege-separation directory");
        assertTrue(mkdirIndex < sshdIndex,
                "/run/sshd must be created before sshd starts, otherwise sshd exits with "
                        + "\"Missing privilege separation directory\"");
    }

    @Test
    void launchWithoutKeyPairStillStartsSshd() throws Exception {
        // Regression for #2184: sshd previously only started when a key pair was supplied, so
        // run-instances without --key-name left no daemon listening at all ("connection refused")
        // instead of matching real AWS AMIs, which run sshd as part of normal boot regardless of
        // whether a key pair is attached to the instance.
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.20");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-nokeypair");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        ExecCreateCmd execCreate = harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID);
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
        // No key pair was supplied, so nothing should have been written to authorized_keys.
        verify(harness.dockerClient, never()).copyArchiveToContainerCmd(TEST_CONTAINER_ID);
    }

    private static final String[] SSHD_INSTALL_PROBE_CMD = Ec2ContainerManager.sshdInstallProbeCommand();

    @Test
    void launchWhenSshdIsMissing_doesNotAttemptKeygenOrStart() throws Exception {
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(1, "i-sshdinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, never()).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, never()).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    @Test
    void launchWhenOnlySshClientIsMissing_stillStartsSshd() throws Exception {
        ExecCreateCmd execCreate = launchWithSshdInstallProbeExitCode(
                Ec2ContainerManager.SSH_CLIENT_MISSING_EXIT_CODE, "i-scpinstallfail");

        verify(execCreate, timeout(2000)).withCmd(eq(SSHD_INSTALL_PROBE_CMD));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"ssh-keygen", "-A"}));
        verify(execCreate, timeout(2000)).withCmd(eq(new String[]{"/usr/sbin/sshd"}));
    }

    /**
     * Launches an instance where every exec succeeds except the sshd install probe, which reports
     * {@code probeExitCode}. Returns the {@link ExecCreateCmd} the launch issued its commands
     * through so callers can assert on which steps were attempted.
     */
    private ExecCreateCmd launchWithSshdInstallProbeExitCode(int probeExitCode, String instanceId)
            throws Exception {
        LaunchHarness harness = launchHarness();
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.21");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        AtomicReference<String[]> currentCommand = new AtomicReference<>();
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            currentCommand.set(args.length == 1 && args[0] instanceof String[] cmd
                    ? cmd : Arrays.copyOf(args, args.length, String[].class));
            return execCreate;
        });
        ExecCreateCmdResponse execResponse = mock(ExecCreateCmdResponse.class);
        when(execResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execResponse);

        when(harness.dockerClient.execStartCmd(anyString())).thenAnswer(invocation -> {
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                @SuppressWarnings("unchecked")
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                callback.onComplete();
                return callback;
            });
            return execStart;
        });

        // Missing supplied SSH tools do not trigger package installation.
        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        when(harness.dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);
        when(inspectExec.exec()).thenAnswer(invocation -> {
            InspectExecResponse response = mock(InspectExecResponse.class);
            boolean isSshdInstallProbe = Arrays.equals(currentCommand.get(), SSHD_INSTALL_PROBE_CMD);
            when(response.getExitCodeLong()).thenReturn(isSshdInstallProbe ? (long) probeExitCode : 0L);
            return response;
        });

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

        Instance instance = instance(instanceId);

        harness.manager.launch(instance, "ubuntu:24.04", "ssh-ed25519 AAAAtest", "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        return execCreate;
    }

    private static LaunchHarness launchHarness() {
        return launchHarness(null, Duration.ofMinutes(30));
    }

    private static LaunchHarness launchHarness(ExecutorService executor, Duration userDataTimeout) {
        return launchHarness(executor, userDataTimeout, null);
    }

    private static LaunchHarness launchHarness(ExecutorService executor, Duration userDataTimeout,
                                                SecurityGroupFirewallManager firewallManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("ubuntu:24.04"));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any(ContainerSpec.class))).thenReturn(TEST_CONTAINER_ID);
        when(lifecycleManager.isContainerRunning(TEST_CONTAINER_ID)).thenReturn(true);

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci");
        ContainerReachableEndpoint reachableEndpoint = mock(ContainerReachableEndpoint.class);
        when(reachableEndpoint.baseUrl()).thenReturn("http://localhost.floci.io:4680");
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(2201);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.registryCredentials()).thenReturn(List.of());
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.sshPortRangeStart()).thenReturn(2200);
        when(ec2.sshPortRangeEnd()).thenReturn(2299);
        when(ec2.imdsPort()).thenReturn(9169);

        DockerClient dockerClient = mock(DockerClient.class);
        stubDockerPing(dockerClient, true);
        Ec2MetadataServer metadataServer = mock(Ec2MetadataServer.class);
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        Ec2PortForwardManager portForwardManager = mock(Ec2PortForwardManager.class);
        VpcNetworkManager vpcNetworkManager = mock(VpcNetworkManager.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        Ec2ContainerManager manager = executor == null
                ? new Ec2ContainerManager(
                        containerBuilder,
                        lifecycleManager,
                        logStreamer,
                        mock(ContainerDetector.class),
                        dockerHostResolver,
                        dockerClient,
                        portAllocator,
                        config,
                        metadataServer,
                        portForwardManager,
                        regionResolver,
                        mock(ContainerNetworkReachability.class),
                        vpcNetworkManager,
                        reachableEndpoint,
                        firewallManager)
                : new Ec2ContainerManager(
                        containerBuilder,
                        lifecycleManager,
                        logStreamer,
                        mock(ContainerDetector.class),
                        dockerHostResolver,
                        dockerClient,
                        portAllocator,
                        config,
                        metadataServer,
                        portForwardManager,
                        regionResolver,
                        mock(ContainerNetworkReachability.class),
                        vpcNetworkManager,
                        reachableEndpoint,
                        executor,
                        userDataTimeout,
                        firewallManager);
        Ec2ContainerManager isolated = spy(manager);
        // Image preparation and metadata provisioning have separate Docker boundary regressions.
        doAnswer(call -> call.getArgument(0)).when(isolated).prepareImage(any());
        doReturn(true).when(isolated).configureLinkLocalMetadataEndpoint(any(), anyString());
        return new LaunchHarness(isolated, lifecycleManager, dockerClient, metadataServer, logStreamer, builder,
                portAllocator, portForwardManager, config, vpcNetworkManager, new CopyOnWriteArrayList<>(), containerBuilder);
    }

    // ── startup reconciliation of EC2 containers orphaned by a previous run ──────

    @Test
    void launchStampsTheOwnerPortLabelOnTheInstanceContainer() throws Exception {
        Ec2ContainerManager.containerBridgeIpAttempts = 1;
        Ec2ContainerManager.containerBridgeIpPollMillis = 1;
        LaunchHarness harness = launchHarness();
        when(harness.config.port()).thenReturn(4680);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse withIp = inspectResponse("172.18.0.12");
        when(harness.dockerClient.inspectContainerCmd(TEST_CONTAINER_ID)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(withIp);
        harness.stubSuccessfulExecs(new CountDownLatch(0), new CountDownLatch(0));
        Instance instance = instance("i-owned");

        harness.manager.launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
        // Without this label the reconciler cannot tell one emulator's containers from another's
        // on a shared Docker daemon, so it could only ever be unsafe or a no-op.
        verify(harness.builder).withLabels(Map.of(Ec2ContainerManager.LABEL_OWNER_PORT, "4680"));
    }

    @Test
    void reconcileOrphanedContainersRemovesOnlyThisProcessesUnrecordedContainers() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient,
                labelled("c-orphan", "4680", "us-east-1", "i-orphan"),
                labelled("c-live", "4680", "us-east-1", "i-live"),
                labelled("c-sibling", "4620", "us-east-1", "i-sibling"));

        // Only i-live still has a record. i-sibling belongs to the Floci on :4620 sharing this
        // daemon and must not be touched, however orphaned it looks from here.
        int removed = harness.manager.reconcileOrphanedContainers(
                (region, instanceId) -> "i-live".equals(instanceId));

        verify(harness.lifecycleManager, never()).removeIfExists("c-sibling");
        verify(harness.lifecycleManager, never()).removeIfExists("c-live");
        verify(harness.lifecycleManager).removeIfExists("c-orphan");
        assertEquals(1, removed);
    }

    @Test
    void reconcileOrphanedContainersLeavesStoppedInstancesAlone() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        stubContainerListing(harness.dockerClient, labelled("c-stopped", "4680", "us-east-1", "i-stopped"));

        // Floci stops every running container on shutdown and keeps the id; the record comes
        // back as `stopped`, which stillDeclared answers true for. Sweeping these would break
        // stop/start across a restart.
        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> true);

        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
        assertEquals(0, removed);
    }

    @Test
    void reconcileOrphanedContainersIgnoresContainersPredatingTheOwnerLabel() {
        LaunchHarness harness = reconcileHarness(true, 4680);
        Container unowned = mock(Container.class);
        when(unowned.getLabels()).thenReturn(Map.of(
                Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE,
                Ec2ContainerManager.LABEL_REGION, "us-east-1",
                Ec2ContainerManager.LABEL_RESOURCE_ID, "i-legacy"));
        stubContainerListing(harness.dockerClient, unowned);

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        verify(harness.lifecycleManager, never()).removeIfExists(anyString());
        assertEquals(0, removed);
    }

    @Test
    void reconcileOrphanedContainersSparesAnotherNamespacesContainerOnTheSamePort() {
        // Two Flocis can share a daemon on the same internal port and are separated only by the
        // resource namespace. Keying ownership on the port alone made each reap the other's live
        // containers; the owner label carries the namespace so a sibling's container is not ours.
        LaunchHarness harness = reconcileHarness(true, 4566, "alpha");
        stubContainerListing(harness.dockerClient,
                labelled("c-ours", "alpha/4566", "us-east-1", "i-ours"),
                labelled("c-sibling", "beta/4566", "us-east-1", "i-sibling"));

        int removed = harness.manager.reconcileOrphanedContainers((region, instanceId) -> false);

        assertEquals(1, removed);
        verify(harness.lifecycleManager).removeIfExists("c-ours");
        verify(harness.lifecycleManager, never()).removeIfExists("c-sibling");
    }

    @Test
    void reconcileOrphanedContainersDoesNothingWhenDisabled() {
        LaunchHarness harness = reconcileHarness(false, 4680);

        assertEquals(0, harness.manager.reconcileOrphanedContainers((region, instanceId) -> false));

        verify(harness.dockerClient, never()).listContainersCmd();
    }

    private static Container labelled(String containerId, String ownerPort, String region, String instanceId) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(containerId);
        when(container.getLabels()).thenReturn(Map.of(
                Ec2ContainerManager.LABEL_SERVICE, Ec2ContainerManager.SERVICE_VALUE,
                Ec2ContainerManager.LABEL_OWNER_PORT, ownerPort,
                Ec2ContainerManager.LABEL_REGION, region,
                Ec2ContainerManager.LABEL_RESOURCE_ID, instanceId));
        return container;
    }

    private static void stubContainerListing(DockerClient dockerClient, Container... containers) {
        ListContainersCmd listCmd = mock(ListContainersCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(new ArrayList<>(Arrays.asList(containers)));
    }

    private static LaunchHarness reconcileHarness(boolean reconcileEnabled, int ownerPort) {
        LaunchHarness harness = launchHarness();
        when(harness.config.port()).thenReturn(ownerPort);
        when(harness.config.services().ec2().reconcileContainersOnStartup()).thenReturn(reconcileEnabled);
        return harness;
    }

    private static LaunchHarness reconcileHarness(boolean reconcileEnabled, int ownerPort, String namespace) {
        LaunchHarness harness = reconcileHarness(reconcileEnabled, ownerPort);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(docker.resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
        when(harness.config.docker()).thenReturn(docker);
        return harness;
    }

    private static void stubUserDataCallback(LaunchHarness harness, Closeable stream,
                                             CountDownLatch userDataStarted) throws Exception {
        AtomicReference<String[]> currentCommand = new AtomicReference<>();
        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
        ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
        when(metadataExec.getId()).thenReturn("metadata-exec");
        when(userDataExec.getId()).thenReturn("userdata-exec");
        when(harness.dockerClient().execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
        when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            currentCommand.set(args.length == 1 && args[0] instanceof String[] command
                    ? command : Arrays.copyOf(args, args.length, String[].class));
            return execCreate;
        });
        when(execCreate.exec()).thenAnswer(invocation -> {
            String[] command = currentCommand.get();
            return command != null && command.length == 1 && "/var/lib/user-data.sh".equals(command[0])
                    ? userDataExec : metadataExec;
        });

        when(harness.dockerClient().execStartCmd(anyString())).thenAnswer(invocation -> {
            String execId = invocation.getArgument(0);
            ExecStartCmd execStart = mock(ExecStartCmd.class);
            when(execStart.exec(any())).thenAnswer(startInvocation -> {
                @SuppressWarnings("unchecked")
                ResultCallback<Frame> callback = startInvocation.getArgument(0);
                if ("userdata-exec".equals(execId)) {
                    callback.onStart(stream);
                    userDataStarted.countDown();
                } else {
                    callback.onComplete();
                }
                return callback;
            });
            return execStart;
        });

        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(harness.dockerClient().inspectExecCmd(anyString())).thenReturn(inspectExec);

        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(harness.dockerClient().copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
        when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);
        when(harness.logStreamer().generateLogStreamName(anyString())).thenReturn(TEST_LOG_STREAM_NAME);
    }

    /**
     * Index of the first exec matching {@code expected} exactly, or -1 when it was never run.
     */
    private static int commandIndex(List<String[]> executedCommands, String... expected) {
        for (int i = 0; i < executedCommands.size(); i++) {
            if (Arrays.equals(executedCommands.get(i), expected)) {
                return i;
            }
        }
        return -1;
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        return instance;
    }

    private static InspectContainerResponse inspectResponse(String ipAddress) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings networkSettings = mock(NetworkSettings.class);
        when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        if (ipAddress != null) {
            ContainerNetwork bridge = new ContainerNetwork().withIpv4Address(ipAddress);
            when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", bridge));
        }
        return inspect;
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    @Test
    void launchRunsInstanceAsMetadataOnlyWhenNoDockerDaemonIsReachable() throws Exception {
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);

        Instance instance = instance("i-nodocker");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");

        assertEquals("running", instance.getState().getName());
        assertNull(instance.getDockerContainerId());
        verify(harness.lifecycleManager(), never()).create(any(ContainerSpec.class));
        verify(harness.metadataServer(), never()).registerContainer(anyString(), anyString(), any());
    }

    @Test
    void launchDegradesToRunningWhenDockerDisappearsMidLaunch() throws Exception {
        LaunchHarness harness = launchHarness();
        PingCmd ping = mock(PingCmd.class);
        when(harness.dockerClient().pingCmd()).thenReturn(ping);
        doNothing().doThrow(new RuntimeException("No such file or directory")).when(ping).exec();
        when(harness.lifecycleManager().create(any(ContainerSpec.class)))
                .thenThrow(new RuntimeException("No such file or directory"));

        Instance instance = instance("i-dockergone");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");

        awaitUntil(() -> "running".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    @Test
    void metadataOnlyInstanceStopsStartsAndTerminatesWithoutAContainer() throws Exception {
        LaunchHarness harness = launchHarness();
        stubDockerPing(harness.dockerClient(), false);
        Instance instance = instance("i-lifecycle");

        harness.manager().launch(instance, "ubuntu:24.04", null, "us-west-2");
        assertEquals("running", instance.getState().getName());

        harness.manager().stop(instance);
        assertEquals("stopped", instance.getState().getName());

        harness.manager().start(instance);
        assertEquals("running", instance.getState().getName());

        harness.manager().terminate(instance);
        awaitUntil(() -> "terminated".equals(instance.getState().getName()), Duration.ofSeconds(2));
    }

    /** Stubs the daemon reachability probe every launch makes before touching Docker. */
    private static void stubDockerPing(DockerClient dockerClient, boolean reachable) {
        PingCmd ping = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(ping);
        if (!reachable) {
            doThrow(new RuntimeException("No such file or directory")).when(ping).exec();
        }
    }

    private record LaunchHarness(Ec2ContainerManager manager,
                                 ContainerLifecycleManager lifecycleManager,
                                 DockerClient dockerClient,
                                 Ec2MetadataServer metadataServer,
                                 ContainerLogStreamer logStreamer,
                                 ContainerBuilder.Builder builder,
                                 PortAllocator portAllocator,
                                 Ec2PortForwardManager portForwardManager,
                                 EmulatorConfig config,
                                 VpcNetworkManager vpcNetworkManager,
                                 List<String[]> executedCommands,
                                 ContainerBuilder containerBuilder) {
        void stubSuccessfulExecs(CountDownLatch userDataStarted, CountDownLatch finishUserData) throws Exception {
            AtomicReference<String[]> currentCommand = new AtomicReference<>();
            ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            ExecCreateCmdResponse metadataExec = mock(ExecCreateCmdResponse.class);
            ExecCreateCmdResponse userDataExec = mock(ExecCreateCmdResponse.class);
            when(metadataExec.getId()).thenReturn("metadata-exec");
            when(userDataExec.getId()).thenReturn("userdata-exec");
            when(dockerClient.execCreateCmd(TEST_CONTAINER_ID)).thenReturn(execCreate);
            when(execCreate.withCmd(any(String[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                if (args.length == 1 && args[0] instanceof String[] command) {
                    currentCommand.set(command);
                } else {
                    currentCommand.set(Arrays.copyOf(args, args.length, String[].class));
                }
                executedCommands.add(currentCommand.get());
                return execCreate;
            });
            when(execCreate.exec()).thenAnswer(invocation -> {
                String[] command = currentCommand.get();
                if (command != null && command.length == 1 && "/var/lib/user-data.sh".equals(command[0])) {
                    return userDataExec;
                }
                return metadataExec;
            });

            when(dockerClient.execStartCmd(anyString())).thenAnswer(invocation -> {
                String execId = invocation.getArgument(0);
                ExecStartCmd execStart = mock(ExecStartCmd.class);
                when(execStart.exec(any())).thenAnswer(startInvocation -> {
                    @SuppressWarnings("unchecked")
                    ResultCallback<Frame> callback = startInvocation.getArgument(0);
                    if ("userdata-exec".equals(execId)) {
                        userDataStarted.countDown();
                        // test docker api frame
                        Frame frame = new Frame(StreamType.STDOUT, TEST_USER_DATA_OUTPUT.getBytes(StandardCharsets.UTF_8));
                        callback.onNext(frame);
                        finishUserData.await(2, TimeUnit.SECONDS);
                    }
                    callback.onComplete();
                    return callback;
                });
                return execStart;
            });

            InspectExecCmd inspectExec = mock(InspectExecCmd.class);
            InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
            when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
            when(inspectExec.exec()).thenReturn(inspectExecResponse);
            when(dockerClient.inspectExecCmd(anyString())).thenReturn(inspectExec);

            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(dockerClient.copyArchiveToContainerCmd(TEST_CONTAINER_ID)).thenReturn(copy);
            when(copy.withTarInputStream(any(InputStream.class))).thenReturn(copy);

            when(logStreamer.generateLogStreamName(anyString())).thenReturn(TEST_LOG_STREAM_NAME);
        }
    }
}
