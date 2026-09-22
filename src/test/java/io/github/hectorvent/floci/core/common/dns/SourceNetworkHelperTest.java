package io.github.hectorvent.floci.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceNetworkHelperTest {

    private EmulatorConfig config;
    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private SourceNetworkHelper helper;
    private ExecStartResultCallback readiness;
    private String dnsProbeScript;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.dns().sourceHelperImage()).thenReturn(SourceNetworkHelper.DEFAULT_IMAGE);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("source-test"));
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn("owned-helper");
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.9");
        when(docker.inspectContainerCmd("owned-helper").exec().getNetworkSettings().getNetworks())
                .thenReturn(Map.of("bridge", network));
        ExecCreateCmd command = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse response = mock(ExecCreateCmdResponse.class);
        when(docker.execCreateCmd("owned-helper")).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getId()).thenReturn("readiness");
        readiness = mock(ExecStartResultCallback.class);
        var start = docker.execStartCmd("readiness");
        doReturn(readiness).when(start).exec(any(ExecStartResultCallback.class));
        when(readiness.awaitCompletion(10, TimeUnit.SECONDS)).thenReturn(true);
        when(docker.inspectExecCmd("readiness").exec().getExitCodeLong()).thenReturn(0L);
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                mock(EmbeddedDnsServer.class));
        helper = new SourceNetworkHelper(config, builder, lifecycle);
    }

    @Test
    void bridgeHasNoHostPortsPrivilegesMountsOrPublicDnsAndOnlyForwardsToFloci() {
        assertEquals("172.18.0.9", helper.start(1053));
        ContainerSpec spec = createdSpec();
        assertTrue(spec.name().startsWith("floci-source-test-source-network-"));
        assertEquals("source-test", spec.labels().get("floci_namespace"));
        assertEquals("floci-aws", spec.labels().get("floci_emulator"));
        assertEquals("true", spec.labels().get("floci.source-network-helper"));
        assertNotNull(spec.labels().get("floci.source-network-owner"));
        assertFalse(spec.labels().containsKey("floci.security-group-helper"));
        assertFalse(spec.privileged());
        assertTrue(spec.portBindings().isEmpty());
        assertTrue(spec.binds().isEmpty());
        assertTrue(spec.mounts().isEmpty());
        assertTrue(spec.dnsServers().isEmpty());
        assertTrue(spec.extraHosts().isEmpty(), "Docker Desktop keeps its built-in host.docker.internal mapping");
        String script = spec.cmd().getFirst();
        assertTrue(script.contains("UDP4-RECVFROM:53,reuseaddr,fork UDP4-SENDTO:host.docker.internal:1053"));
        assertTrue(script.contains("TCP4-LISTEN:443,reuseaddr,fork TCP4:host.docker.internal:4566"));
        assertTrue(script.contains("TCP4-LISTEN:4566,reuseaddr,fork TCP4:host.docker.internal:4566"));
        assertFalse(script.contains("nft"));
        verify(docker.execCreateCmd("owned-helper")).withAttachStdout(true);
        verify(docker.execCreateCmd("owned-helper")).withAttachStderr(true);
        verify(docker, never()).listContainersCmd();
        helper.stop();
    }

    @Test
    void followsTheLambdaNetworkOverrideWithoutAttachingExistingContainers() {
        when(config.services().lambda().dockerNetwork()).thenReturn(Optional.of("lambda-bridge"));
        helper.start(1053);
        assertEquals("lambda-bridge", createdSpec().networkMode());
        verify(docker, never()).connectToNetworkCmd();
        helper.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "none", "container:unrelated"})
    void refusesNonBridgeNamespaceModesBeforeCreatingAContainer(String network) {
        when(config.services().lambda().dockerNetwork()).thenReturn(Optional.of(network));
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle, never()).create(any());
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any());
    }

    @Test
    void stopRemovesOnlyTheCreatedIdAndIsIdempotent() {
        helper.start(1053);
        helper.stop();
        helper.stop();
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
        verify(docker, never()).listContainersCmd();
        verify(lifecycle, never()).findByName(anyString());
        verify(lifecycle, never()).removeIfExists(anyString());
    }

    @Test
    void failedStartRemovesOnlyItsPartiallyCreatedContainer() {
        doThrow(new IllegalStateException("start failed")).when(lifecycle).startCreated(eq("owned-helper"), any());
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
        helper.stop();
        verify(lifecycle, times(1)).stopAndRemoveStrict(anyString(), any());
    }

    @Test
    void failedCreateNeverDeletesByNameOrSweepsOtherHelpers() {
        when(lifecycle.create(any())).thenThrow(new IllegalStateException("create failed"));
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any());
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(docker, never()).listContainersCmd();
    }

    @Test
    void readinessFailureRemovesTheHelperBeforeDnsCanAdvertiseIt() throws Exception {
        when(readiness.awaitCompletion(10, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
    }

    @Test
    void readinessNonzeroExitIsNotAcceptedAsReady() {
        when(docker.inspectExecCmd("readiness").exec().getExitCodeLong()).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
    }

    @Test
    void invalidBridgeAddressRemovesTheHelper() {
        when(docker.inspectContainerCmd("owned-helper").exec().getNetworkSettings().getNetworks()).thenReturn(Map.of());
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
    }

    @Test
    void cleanupFailureRetainsTheOwnedIdForRetry() {
        helper.start(1053);
        doThrow(new IllegalStateException("remove failed")).doNothing()
                .when(lifecycle).stopAndRemoveStrict("owned-helper", null);
        assertThrows(IllegalStateException.class, helper::stop);
        helper.stop();
        verify(lifecycle, times(2)).stopAndRemoveStrict("owned-helper", null);
    }

    @Test
    void gatewayOverrideChangesOnlyTheOwnedGatewayDestinationAndListener() {
        when(config.port()).thenReturn(14566);
        helper.start(11053);
        String script = createdSpec().cmd().getFirst();
        assertTrue(script.contains("UDP4-SENDTO:host.docker.internal:11053"));
        assertTrue(script.contains("TCP4-LISTEN:443,reuseaddr,fork TCP4:host.docker.internal:14566"));
        assertTrue(script.contains("TCP4-LISTEN:14566,reuseaddr,fork TCP4:host.docker.internal:14566"));
        assertFalse(script.contains(":4566"));
        helper.stop();
    }

    @Test
    void imageRegistryPrefixAppliesToBothImagePreparationAndContainerSpec() {
        when(config.docker().imageRegistryBase()).thenReturn(Optional.of("registry.example/floci-mirror"));
        helper.start(1053);
        String image = "registry.example/floci-mirror/" + SourceNetworkHelper.DEFAULT_IMAGE;
        verify(docker).inspectImageCmd(image);
        assertEquals(image, createdSpec().image());
        helper.stop();
    }

    @Test
    void missingDefaultImageIsBuiltFromTheBundledRecipe() throws Exception {
        when(docker.inspectImageCmd(SourceNetworkHelper.DEFAULT_IMAGE).exec()).thenThrow(new NotFoundException("missing"));
        BuildImageCmd build = mock(BuildImageCmd.class, RETURNS_SELF);
        BuildImageResultCallback callback = mock(BuildImageResultCallback.class);
        when(docker.buildImageCmd(any(InputStream.class))).thenReturn(build);
        when(build.exec(any(BuildImageResultCallback.class))).thenReturn(callback);
        when(callback.awaitImageId(2, TimeUnit.MINUTES)).thenReturn("built-image");
        helper.start(1053);
        verify(build).withTags(Set.of(SourceNetworkHelper.DEFAULT_IMAGE));
        ArgumentCaptor<InputStream> context = ArgumentCaptor.forClass(InputStream.class);
        verify(docker).buildImageCmd(context.capture());
        try (TarArchiveInputStream tar = new TarArchiveInputStream(context.getValue())) {
            assertEquals("Dockerfile", tar.getNextEntry().getName());
            String recipe = new String(tar.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(recipe.contains("socat"));
            assertTrue(recipe.contains("iproute2"));
        }
        helper.stop();
    }

    @Test
    void imageBuildFailureNeverCreatesAHelper() throws Exception {
        when(docker.inspectImageCmd(SourceNetworkHelper.DEFAULT_IMAGE).exec()).thenThrow(new NotFoundException("missing"));
        BuildImageCmd build = mock(BuildImageCmd.class, RETURNS_SELF);
        when(docker.buildImageCmd(any(InputStream.class))).thenReturn(build);
        when(build.exec(any(BuildImageResultCallback.class))).thenThrow(new IllegalStateException("build failed"));
        assertThrows(IllegalStateException.class, () -> helper.start(1053));
        verify(lifecycle, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 53, 1023, 65536})
    void rejectsInvalidHostPortsBeforeDockerAccess(int port) {
        assertThrows(IllegalArgumentException.class, () -> helper.start(port));
        verify(lifecycle, never()).create(any());
    }

    @Test
    void dnsRoundtripUsesTheOwnedUdpListenerAndValidatesItsAnswer() {
        stubDnsProbe(query -> Base64.getMimeEncoder().encodeToString(dnsResponse(query)));
        helper.awaitDns("172.18.0.9");
        assertTrue(dnsProbeScript.contains("printf '%b'"));
        assertTrue(dnsProbeScript.contains("timeout 8 socat -T 2 -t 2 - UDP4:172.18.0.9:53,shut-none | base64"));
        assertTrue(dnsProbeScript.contains("set -o pipefail"));
        assertFalse(dnsProbeScript.contains("TCP"), "the host gateway is not started during DNS initialization");
        assertTrue(createdSpec().cmd().getFirst().contains("UDP4-SENDTO:host.docker.internal:1053"));
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any());
        helper.stop();
    }

    @Test
    void dnsRoundtripTimeoutRemovesOnlyTheOwnedHelper() throws Exception {
        stubDnsProbe(query -> "");
        when(readiness.awaitCompletion(10, TimeUnit.SECONDS)).thenReturn(false);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertTrue(failure.getMessage().contains("timed out"));
        assertOwnedDnsFailureCleanup();
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 124L})
    void dnsRoundtripRejectsFailedExecEvenWithAValidAnswer(long exitCode) {
        stubDnsProbe(query -> Base64.getEncoder().encodeToString(dnsResponse(query)));
        when(docker.inspectExecCmd("readiness").exec().getExitCodeLong()).thenReturn(exitCode);
        assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertOwnedDnsFailureCleanup();
    }

    @Test
    void dnsRoundtripRejectsMissingExecExitStatus() {
        stubDnsProbe(query -> Base64.getEncoder().encodeToString(dnsResponse(query)));
        when(docker.inspectExecCmd("readiness").exec().getExitCodeLong()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertOwnedDnsFailureCleanup();
    }

    @Test
    void dnsExecStartFailureRemovesOnlyTheOwnedHelper() {
        helper.start(1053);
        when(docker.execStartCmd("readiness")).thenThrow(new IllegalStateException("exec failed"));
        assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertOwnedDnsFailureCleanup();
    }

    @Test
    void dnsRoundtripInterruptionPreservesTheInterruptAndCleansUp() throws Exception {
        stubDnsProbe(query -> "");
        when(readiness.awaitCompletion(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("cancelled"));
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> helper.awaitDns("172.18.0.9"));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertOwnedDnsFailureCleanup();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void listenerInterruptionPreservesTheInterruptAndCleansUp() throws Exception {
        when(readiness.awaitCompletion(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("cancelled"));
        try {
            assertThrows(IllegalStateException.class, () -> helper.start(1053));
            assertTrue(Thread.currentThread().isInterrupted());
            assertOwnedDnsFailureCleanup();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "short", "trailing", "transaction", "query", "opcode", "truncated", "status",
            "questions", "answers", "authority", "additional", "question-name", "question-type", "question-class",
            "answer-name", "answer-type", "answer-class", "answer-length", "address", "invalid-base64"})
    void dnsRoundtripRejectsMalformedOrMismatchedResponses(String defect) {
        stubDnsProbe(query -> {
            byte[] response = dnsResponse(query);
            ByteBuffer packet = ByteBuffer.wrap(response);
            byte[] invalid = switch (defect) {
                case "empty" -> new byte[0];
                case "short" -> Arrays.copyOf(response, response.length - 1);
                case "trailing" -> Arrays.copyOf(response, response.length + 1);
                case "transaction" -> packet.putShort(0, (short) (packet.getShort(0) + 1)).array();
                case "query" -> packet.putShort(2, (short) 0x0100).array();
                case "opcode" -> packet.putShort(2, (short) 0x8980).array();
                case "truncated" -> packet.putShort(2, (short) 0x8380).array();
                case "status" -> packet.putShort(2, (short) 0x8183).array();
                case "questions" -> packet.putShort(4, (short) 0).array();
                case "answers" -> packet.putShort(6, (short) 0).array();
                case "authority" -> packet.putShort(8, (short) 1).array();
                case "additional" -> packet.putShort(10, (short) 1).array();
                case "question-name" -> packet.put(13, (byte) 'x').array();
                case "question-type" -> packet.putShort(query.length - 4, (short) 28).array();
                case "question-class" -> packet.putShort(query.length - 2, (short) 3).array();
                case "answer-name" -> packet.putShort(query.length, (short) 0xc00d).array();
                case "answer-type" -> packet.putShort(query.length + 2, (short) 28).array();
                case "answer-class" -> packet.putShort(query.length + 4, (short) 3).array();
                case "answer-length" -> packet.putShort(query.length + 10, (short) 3).array();
                case "address" -> packet.put(response.length - 1, (byte) 10).array();
                case "invalid-base64" -> response;
                default -> throw new IllegalArgumentException(defect);
            };
            return defect.equals("invalid-base64") ? "!not-base64!" : Base64.getEncoder().encodeToString(invalid);
        });
        assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertOwnedDnsFailureCleanup();
    }

    @Test
    void dnsProbeCleanupFailureIsSuppressedAndRetainsTheExactIdForRetry() {
        stubDnsProbe(query -> "");
        doThrow(new IllegalStateException("remove failed")).doNothing()
                .when(lifecycle).stopAndRemoveStrict("owned-helper", null);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> helper.awaitDns("172.18.0.9"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("remove failed", failure.getSuppressed()[0].getMessage());
        helper.stop();
        verify(lifecycle, times(2)).stopAndRemoveStrict("owned-helper", null);
    }

    private void stubDnsProbe(Function<byte[], String> response) {
        helper.start(1053);
        ExecCreateCmd command = docker.execCreateCmd("owned-helper");
        when(command.withCmd(eq("sh"), eq("-c"), anyString())).thenAnswer(invocation -> {
            dnsProbeScript = invocation.getArgument(2);
            return command;
        });
        var start = docker.execStartCmd("readiness");
        doAnswer(invocation -> {
            ByteArrayOutputStream query = new ByteArrayOutputStream();
            Matcher octal = Pattern.compile("\\\\0([0-7]{3})").matcher(dnsProbeScript);
            while (octal.find()) {
                query.write(Integer.parseInt(octal.group(1), 8));
            }
            ExecStartResultCallback callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, response.apply(query.toByteArray())
                    .getBytes(StandardCharsets.US_ASCII)));
            callback.onNext(new Frame(StreamType.STDERR, "probe diagnostic".getBytes(StandardCharsets.US_ASCII)));
            return readiness;
        }).when(start).exec(any(ExecStartResultCallback.class));
    }

    private byte[] dnsResponse(byte[] query) {
        return new EmbeddedDnsServer(List.of()).buildAResponse(query, ByteBuffer.wrap(query).getShort(0),
                12, query.length, "172.18.0.9");
    }

    private void assertOwnedDnsFailureCleanup() {
        helper.stop();
        verify(lifecycle).stopAndRemoveStrict("owned-helper", null);
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(docker, never()).listContainersCmd();
    }

    private ContainerSpec createdSpec() {
        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycle).create(spec.capture());
        return spec.getValue();
    }
}
