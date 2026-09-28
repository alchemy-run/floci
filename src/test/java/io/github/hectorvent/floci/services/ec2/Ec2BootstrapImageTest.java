package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.TagImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.quarkus.arc.ClientProxy;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class Ec2BootstrapImageTest {
    private static final String SOURCE = "sha256:" + "a".repeat(64);
    private static final String NEXT_SOURCE = "sha256:" + "b".repeat(64);
    private static final String BUILT = "sha256:" + "c".repeat(64);
    private static final String HOST_SOURCE = "sha256:" + "d".repeat(64);
    private static final String CONTAINER_ID = "e".repeat(64);

    @Test
    void onlyExactSupportedCatalogDefaultsAreBuilt() throws Exception {
        Set<String> catalog = new Ec2ImageCatalog().images().stream().map(image -> image.dockerImage)
                .collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, String> stock : Ec2BootstrapImage.STOCK_IMAGES.entrySet()) {
            assertTrue(catalog.contains(stock.getValue()));
            for (String reference : List.of(stock.getKey(), stock.getValue())) {
                Harness harness = harness(reference, Optional.empty());
                ResolvedAmiImage result = harness.helper.prepare(image(reference));
                assertTrue(result.dockerImage().startsWith("floci/ec2-bootstrap:"));
                verify(harness.build).exec(any(BuildImageResultCallback.class));
                verifySelectorLifecycle(harness, 1);
            }
        }
    }

    @Test
    void customCapturedAndCloudImagesBypassAllDockerPreparation() {
        DockerClient docker = mock(DockerClient.class);
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        Ec2BootstrapImage helper = new Ec2BootstrapImage(docker, builder, lifecycle, mock(ImageCacheService.class));
        for (String reference : List.of("custom/ubuntu:24.04", "ubuntu:latest", "ubuntu:24.04-custom",
                "registry.example/ubuntu:24.04", "floci/ami-ubuntu:24.04-arm64", "floci-ami:captured", SOURCE,
                "ubuntu@sha256:" + "a".repeat(64), "alpine:3.21")) {
            ResolvedAmiImage image = image(reference);
            assertSame(image, helper.prepare(image));
        }
        ResolvedAmiImage cloud = new ResolvedAmiImage("ubuntu:24.04", "systemd", true, "linux/amd64");
        assertSame(cloud, helper.prepare(cloud));
        verifyNoInteractions(docker, builder, lifecycle);
    }

    @Test
    void buildUsesImmutableSourceRecipePlatformAndRegistryPrefixAndCachesInDaemon() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.of("registry.example/mirror/"));
        ResolvedAmiImage input = image("ubuntu:24.04");
        ResolvedAmiImage result = harness.helper.prepare(input);
        assertTrue(result.dockerImage().startsWith("registry.example/mirror/floci/ec2-bootstrap:"));
        assertEquals(input.dockerPlatform(), result.dockerPlatform());
        assertEquals(input.guestRuntime(), result.guestRuntime());
        String pinnedSource = "registry.example/mirror/floci/ec2-bootstrap-source:" + "a".repeat(64);
        assertEquals(Ec2BootstrapImage.RECIPE.formatted("--platform=linux/amd64 ", pinnedSource), harness.recipes.getFirst());
        verify(harness.docker).tagImageCmd(SOURCE, "registry.example/mirror/floci/ec2-bootstrap-source", "a".repeat(64));
        verify(harness.docker).inspectImageCmd("registry.example/mirror/ubuntu:24.04");
        verify(harness.build).withPlatform("linux/amd64");
        verify(harness.build).withPull(false);
        verify(harness.build).withRemove(true);
        verify(harness.result).awaitImageId(2, TimeUnit.MINUTES);
        verify(harness.build).close();
        assertEquals(result, harness.helper.prepare(input));
        // A new manager also reuses the daemon's derived-image cache.
        Ec2BootstrapImage restarted = new Ec2BootstrapImage(harness.docker, harness.builder, harness.lifecycle, harness.imageCache);
        assertEquals(result, restarted.prepare(input));
        verify(harness.docker, times(1)).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 3);
    }

    @Test
    void crossPlatformSourceUsesSelectedManifestInsteadOfHostImageFields() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        InspectImageResponse hostImage = inspected(HOST_SOURCE);
        when(hostImage.getArch()).thenReturn("arm64");
        when(harness.docker.infoCmd().exec().getArchitecture()).thenReturn("arm64");
        harness.images.put("ubuntu:24.04", hostImage);
        PullImageCmd pull = mock(PullImageCmd.class, RETURNS_SELF);
        PullImageResultCallback pulled = mock(PullImageResultCallback.class);
        when(harness.docker.pullImageCmd("ubuntu:24.04")).thenReturn(pull);
        doReturn(pulled).when(pull).exec(any(PullImageResultCallback.class));
        when(pulled.awaitCompletion(5, TimeUnit.MINUTES)).thenReturn(true);

        ResolvedAmiImage result = harness.helper.prepare(image("ubuntu:24.04"));

        verify(pull).withPlatform("linux/amd64");
        assertEquals(HOST_SOURCE, hostImage.getId());
        assertEquals("arm64", hostImage.getArch());
        assertEquals("linux/amd64", result.dockerPlatform());
        assertEquals("floci/ec2-bootstrap:" + Ec2BootstrapImage.cacheKey(SOURCE, "linux/amd64",
                Ec2BootstrapImage.RECIPE), result.dockerImage());
        verify(harness.create).withPlatform("linux/amd64");
        verify(harness.build).withPlatform("linux/amd64");
        verify(harness.docker).tagImageCmd(SOURCE, "floci/ec2-bootstrap-source", "a".repeat(64));
        verify(harness.docker, never()).tagImageCmd(eq(HOST_SOURCE), anyString(), anyString());
        assertEquals(Ec2BootstrapImage.RECIPE.formatted("--platform=linux/amd64 ",
                "floci/ec2-bootstrap-source:" + "a".repeat(64)), harness.recipes.getFirst());
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void selectorIsLifecycleOwnedNetworkIsolatedAndRemovedBeforeBuilding() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.of("registry.example/mirror/"));
        harness.helper.prepare(image("ubuntu:24.04"));

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(harness.lifecycle).create(spec.capture(), eq("linux/amd64"));
        assertEquals("registry.example/mirror/ubuntu:24.04", spec.getValue().image());
        assertNull(spec.getValue().name());
        assertEquals("none", spec.getValue().networkMode());
        assertEquals(List.of("true"), spec.getValue().cmd());
        assertFalse(spec.getValue().privileged());
        assertTrue(spec.getValue().portBindings().isEmpty());
        assertTrue(spec.getValue().mounts().isEmpty());
        assertTrue(spec.getValue().binds().isEmpty());
        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(harness.create).withHostConfig(host.capture());
        assertEquals("none", host.getValue().getNetworkMode());
        verify(harness.create).withCmd(List.of("true"));
        verify(harness.create).withLabels(Map.of("floci", "true", "floci_emulator", "floci-aws",
                "floci_namespace", "bootstrap-test", "floci.ec2-bootstrap-source", "true"));
        InOrder order = inOrder(harness.lifecycle, harness.docker, harness.http, harness.response, harness.remove);
        order.verify(harness.lifecycle).create(any(ContainerSpec.class), eq("linux/amd64"));
        ArgumentCaptor<DockerHttpClient.Request> request = ArgumentCaptor.forClass(DockerHttpClient.Request.class);
        order.verify(harness.http).execute(request.capture());
        assertEquals("GET", request.getValue().method());
        assertEquals("/containers/" + CONTAINER_ID + "/json", request.getValue().path());
        order.verify(harness.response).close();
        order.verify(harness.lifecycle).removeIfExistsStrict(CONTAINER_ID);
        order.verify(harness.remove).exec();
        order.verify(harness.docker).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void absentPlatformLeavesSelectionToDaemonWithoutPinningBuild() throws Exception {
        for (String platform : Arrays.asList(null, "", " ")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            ResolvedAmiImage input = new ResolvedAmiImage("ubuntu:24.04", "minimal", false, platform);
            ResolvedAmiImage result = harness.helper.prepare(input);
            assertEquals(platform, result.dockerPlatform());
            verify(harness.lifecycle).create(any(ContainerSpec.class), eq(platform));
            verify(harness.create, never()).withPlatform(anyString());
            verify(harness.build, never()).withPlatform(anyString());
            assertEquals(Ec2BootstrapImage.RECIPE.formatted("",
                    "floci/ec2-bootstrap-source:" + "a".repeat(64)), harness.recipes.getFirst());
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void inspectionFailureStillRemovesExactCreatedContainer() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        IllegalStateException failure = new IllegalStateException("inspect failed");
        when(harness.http.execute(any(DockerHttpClient.Request.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> harness.helper.prepare(image("ubuntu:24.04"))));
        verify(harness.response, never()).close();
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void invalidSelectedDigestIsRejectedAndSelectorIsRemoved() throws Exception {
        for (String source : Arrays.asList(null, "", "ubuntu:24.04", "sha256:invalid")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            when(harness.container.getImageId()).thenReturn(source);
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.docker, never()).tagImageCmd(anyString(), anyString(), anyString());
            verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
            verify(harness.response).close();
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void oldDaemonWithoutDescriptorUsesSinglePlatformImageId() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        when(harness.response.getBody()).thenAnswer(call -> body("{\"Image\":\"" + SOURCE + "\"}"));

        ResolvedAmiImage result = harness.helper.prepare(image("ubuntu:24.04"));

        assertEquals("floci/ec2-bootstrap:" + Ec2BootstrapImage.cacheKey(SOURCE, "linux/amd64",
                Ec2BootstrapImage.RECIPE), result.dockerImage());
        verify(harness.docker).tagImageCmd(SOURCE, "floci/ec2-bootstrap-source", "a".repeat(64));
        verify(harness.response).close();
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void missingOrInvalidFallbackImageIsRejected() throws Exception {
        for (String json : List.of("{}", "{\"Image\":null}", "{\"Image\":123}",
                "{\"Image\":\"ubuntu:24.04\"}")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            when(harness.response.getBody()).thenAnswer(call -> body(json));
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
            verify(harness.response).close();
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void malformedDescriptorNeverFallsBackToHostImage() throws Exception {
        for (String descriptor : List.of("null", "[]", "\"manifest\"", "{}",
                "{\"digest\":\"" + SOURCE + "\"}",
                "{\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}",
                "{\"digest\":123,\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            when(harness.response.getBody()).thenAnswer(call -> body(
                    "{\"Image\":\"" + HOST_SOURCE + "\",\"ImageManifestDescriptor\":" + descriptor + "}"));
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.docker, never()).tagImageCmd(anyString(), anyString(), anyString());
            verify(harness.response).close();
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void selectedPlatformMustBeValidAndMatchRequestedOsAndArchitecture() throws Exception {
        for (String platform : List.of("null", "[]", "{}",
                "{\"os\":\"linux\",\"architecture\":\"arm64\"}",
                "{\"os\":\"windows\",\"architecture\":\"amd64\"}",
                "{\"os\":\"linux\"}",
                "{\"os\":\"\",\"architecture\":\"amd64\"}",
                "{\"os\":123,\"architecture\":\"amd64\"}",
                "{\"os\":\"linux\",\"architecture\":\"amd64\",\"variant\":null}",
                "{\"os\":\"linux\",\"architecture\":\"amd64\",\"variant\":2}")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            when(harness.response.getBody()).thenAnswer(call -> body(
                    "{\"Image\":\"" + HOST_SOURCE + "\",\"ImageManifestDescriptor\":{\"digest\":\""
                            + SOURCE + "\",\"platform\":" + platform + "}}"));
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.docker, never()).tagImageCmd(anyString(), anyString(), anyString());
            verify(harness.response).close();
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void requestedVariantMustMatchDescriptor() throws Exception {
        for (String variant : Arrays.asList(null, "v2", "v3")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            PullImageCmd pull = mock(PullImageCmd.class, RETURNS_SELF);
            PullImageResultCallback pulled = mock(PullImageResultCallback.class);
            when(harness.docker.pullImageCmd("ubuntu:24.04")).thenReturn(pull);
            doReturn(pulled).when(pull).exec(any(PullImageResultCallback.class));
            when(pulled.awaitCompletion(5, TimeUnit.MINUTES)).thenReturn(true);
            when(harness.response.getBody()).thenAnswer(call -> {
                ObjectNode inspection = new ObjectMapper().createObjectNode();
                inspection.put("Image", HOST_SOURCE);
                ObjectNode descriptor = inspection.putObject("ImageManifestDescriptor");
                descriptor.put("digest", SOURCE);
                ObjectNode platform = descriptor.putObject("platform")
                        .put("os", "linux").put("architecture", "amd64");
                if (variant != null) {
                    platform.put("variant", variant);
                }
                return body(inspection.toString());
            });
            ResolvedAmiImage input = new ResolvedAmiImage("ubuntu:24.04", "minimal", false, "linux/amd64/v2");
            if ("v2".equals(variant)) {
                assertEquals(input.dockerPlatform(), harness.helper.prepare(input).dockerPlatform());
                verify(harness.build).withPlatform("linux/amd64/v2");
            } else {
                assertThrows(IllegalStateException.class, () -> harness.helper.prepare(input));
                verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
            }
            verify(pull).withPlatform("linux/amd64/v2");
            verify(harness.response).close();
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void malformedInspectionResponseClosesResponseAndRemovesSelector() throws Exception {
        for (String json : List.of("", "{", "null", "[]", "\"image\"")) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            when(harness.response.getBody()).thenAnswer(call -> body(json));
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.response).close();
            verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
            verifySelectorLifecycle(harness, 1);
        }
    }

    @Test
    void responseReadFailureClosesResponseAndRemovesSelector() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        InputStream stream = mock(InputStream.class);
        IOException failure = new IOException("connection lost");
        when(stream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(failure);
        when(harness.response.getBody()).thenReturn(stream);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> harness.helper.prepare(image("ubuntu:24.04")));
        assertSame(failure, thrown.getCause());
        verify(harness.response).close();
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void errorResponseIsClosedWithoutParsingOrBuilding() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        when(harness.response.getStatusCode()).thenReturn(500);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> harness.helper.prepare(image("ubuntu:24.04")));
        assertTrue(thrown.getMessage().contains("HTTP 500"));
        verify(harness.response, never()).getBody();
        verify(harness.response).close();
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void selectedManifestIsMaterializedByRepositoryBeforeTagging() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.of("registry.example:5000/mirror/"));
        harness.helper.prepare(image("ubuntu:24.04"));
        InOrder order = inOrder(harness.imageCache, harness.docker);
        order.verify(harness.imageCache).ensureImageExists(
                "registry.example:5000/mirror/ubuntu@" + SOURCE, "linux/amd64");
        order.verify(harness.docker).tagImageCmd(eq(SOURCE), anyString(), anyString());
    }

    @Test
    void failedManifestPullRemovesSelectorWithoutBuilding() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        when(harness.imageCache.ensureImageExists("ubuntu@" + SOURCE, "linux/amd64"))
                .thenThrow(new IllegalStateException("pull failed"));
        assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
        verifySelectorLifecycle(harness, 1);
        verify(harness.docker, never()).tagImageCmd(anyString(), anyString(), anyString());
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
    }

    @Test
    void contextualDockerProxyUsesUnderlyingSharedTransport() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        DockerClient proxy = mock(DockerClient.class, withSettings().extraInterfaces(ClientProxy.class)
                .defaultAnswer(delegatesTo(harness.docker)));
        doReturn(harness.docker).when((ClientProxy) proxy).arc_contextualInstance();
        Ec2BootstrapImage helper = new Ec2BootstrapImage(proxy, harness.builder, harness.lifecycle, harness.imageCache);

        helper.prepare(image("ubuntu:24.04"));

        verify(harness.http).execute(any(DockerHttpClient.Request.class));
        verify(harness.response).close();
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void cleanupFailurePreventsBuildingOrReturningAnImage() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        IllegalStateException failure = new IllegalStateException("remove failed");
        doThrow(failure).when(harness.remove).exec();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> harness.helper.prepare(image("ubuntu:24.04")));
        assertSame(failure, thrown.getCause());
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
        verifySelectorLifecycle(harness, 1);
    }

    @Test
    void failedCreateDoesNotRemoveAnUnownedContainer() throws Exception {
        Harness harness = harness("ubuntu:24.04", Optional.empty());
        IllegalStateException failure = new IllegalStateException("create failed");
        when(harness.create.exec()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> harness.helper.prepare(image("ubuntu:24.04"))));
        verify(harness.lifecycle, never()).removeIfExistsStrict(anyString());
        verify(harness.docker, never()).removeContainerCmd(anyString());
        verify(harness.docker, never()).startContainerCmd(anyString());
        verify(harness.docker, never()).buildImageCmd(any(InputStream.class));
    }

    @Test
    void sourceAndRecipeChangesInvalidateCacheAndDeletedCacheIsRebuilt() throws Exception {
        Harness harness = harness("amazonlinux:2023", Optional.empty());
        ResolvedAmiImage first = harness.helper.prepare(image("amazonlinux:2023"));
        harness.images.put("amazonlinux:2023", inspected(NEXT_SOURCE));
        when(harness.container.getImageId()).thenReturn(NEXT_SOURCE);
        ResolvedAmiImage next = harness.helper.prepare(image("amazonlinux:2023"));
        assertNotEquals(first.dockerImage(), next.dockerImage());
        assertTrue(harness.recipes.getLast().startsWith("FROM --platform=linux/amd64 floci/ec2-bootstrap-source:" + "b".repeat(64) + "\n"));
        verify(harness.docker).tagImageCmd(NEXT_SOURCE, "floci/ec2-bootstrap-source", "b".repeat(64));
        harness.images.remove(next.dockerImage());
        assertEquals(next, harness.helper.prepare(image("amazonlinux:2023")));
        verify(harness.docker, times(3)).buildImageCmd(any(InputStream.class));
        assertNotEquals(Ec2BootstrapImage.cacheKey(SOURCE, "linux/amd64", Ec2BootstrapImage.RECIPE),
                Ec2BootstrapImage.cacheKey(SOURCE, "linux/amd64", Ec2BootstrapImage.RECIPE + "\n"));
        assertNotEquals(Ec2BootstrapImage.cacheKey(SOURCE, "linux/amd64", Ec2BootstrapImage.RECIPE),
                Ec2BootstrapImage.cacheKey(SOURCE, "linux/arm64", Ec2BootstrapImage.RECIPE));
    }

    @Test
    void failedAndTimedOutBuildsDoNotReturnALaunchableImageOrCacheSuccess() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            Harness harness = harness("ubuntu:24.04", Optional.empty());
            if (timeout) {
                when(harness.result.awaitImageId(2, TimeUnit.MINUTES)).thenReturn(null);
            } else {
                when(harness.result.awaitImageId(2, TimeUnit.MINUTES))
                        .thenThrow(new IllegalStateException("package download failed"));
            }
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            assertThrows(IllegalStateException.class, () -> harness.helper.prepare(image("ubuntu:24.04")));
            verify(harness.docker, times(2)).buildImageCmd(any(InputStream.class));
            verify(harness.build, times(2)).close();
            verifySelectorLifecycle(harness, 2);
        }
    }

    @Test
    void invalidPlatformNeverReachesDocker() {
        DockerClient docker = mock(DockerClient.class);
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        Ec2BootstrapImage helper = new Ec2BootstrapImage(docker, builder, lifecycle, mock(ImageCacheService.class));
        ResolvedAmiImage image = new ResolvedAmiImage("ubuntu:24.04", "minimal", false,
                "linux/amd64\nRUN false");
        assertThrows(IllegalArgumentException.class, () -> helper.prepare(image));
        verifyNoInteractions(docker, builder, lifecycle);
    }

    @Test
    void recipePreparesCurlTrustSshAndMetadataWithoutBakingHostKeys() {
        String recipe = Ec2BootstrapImage.RECIPE;
        assertTrue(recipe.contains("--no-install-recommends curl ca-certificates openssh-server openssh-client"));
        assertTrue(recipe.contains("dnf install -y --allowerasing curl ca-certificates openssh-server openssh-clients"));
        assertTrue(recipe.contains("yum install -y curl ca-certificates openssh-server openssh-clients"));
        assertTrue(recipe.contains("apk add --no-cache curl ca-certificates openssh iproute2 python3"));
        assertTrue(recipe.indexOf("command -v dnf") < recipe.indexOf("command -v yum"));
        assertTrue(recipe.contains("rm -f /etc/ssh/ssh_host_*"));
        assertTrue(recipe.contains("test -s /etc/ssl/certs/ca-certificates.crt"));
    }

    private static void verifySelectorLifecycle(Harness harness, int count) throws IOException {
        verify(harness.lifecycle, times(count)).create(any(ContainerSpec.class), nullable(String.class));
        verify(harness.lifecycle, times(count)).removeIfExistsStrict(CONTAINER_ID);
        verify(harness.docker, times(count)).removeContainerCmd(CONTAINER_ID);
        verify(harness.remove, times(count)).withForce(true);
        verify(harness.remove, times(count)).exec();
        verify(harness.lifecycle, never()).createAndStart(any(ContainerSpec.class));
        verify(harness.lifecycle, never()).startCreated(anyString(), any(ContainerSpec.class));
        verify(harness.docker, never()).startContainerCmd(anyString());
        verify(harness.docker, never()).stopContainerCmd(anyString());
        verify(harness.docker, never()).execCreateCmd(anyString());
        verify(harness.docker, never()).inspectContainerCmd(anyString());
        verify(harness.http, never()).close();
    }

    private static InputStream body(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ResolvedAmiImage image(String reference) {
        return new ResolvedAmiImage(reference, "minimal", false, "linux/amd64");
    }

    private static InspectImageResponse inspected(String id) {
        InspectImageResponse response = mock(InspectImageResponse.class);
        when(response.getId()).thenReturn(id);
        when(response.getOs()).thenReturn("linux");
        when(response.getArch()).thenReturn("amd64");
        return response;
    }

    private static Harness harness(String reference, Optional<String> registry) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().imageRegistryBase()).thenReturn(registry);
        when(config.docker().registryCredentials()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("bootstrap-test"));
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.tls().enabled()).thenReturn(false);
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), null);
        DockerClientImpl docker = mock(DockerClientImpl.class);
        DockerHttpClient http = mock(DockerHttpClient.class);
        DockerHttpClient.Response response = mock(DockerHttpClient.Response.class);
        when(docker.getHttpClient()).thenReturn(http);
        when(http.execute(any(DockerHttpClient.Request.class))).thenReturn(response);
        when(response.getStatusCode()).thenReturn(200);
        Info info = mock(Info.class);
        when(info.getOsType()).thenReturn("linux");
        when(info.getArchitecture()).thenReturn("amd64");
        InfoCmd infoCommand = mock(InfoCmd.class);
        when(infoCommand.exec()).thenReturn(info);
        when(docker.infoCmd()).thenReturn(infoCommand);
        ContainerLifecycleManager lifecycle = spy(new ContainerLifecycleManager(docker,
                new ImageCacheService(docker, config), mock(ContainerDetector.class), mock(PortAllocator.class), config));
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(CONTAINER_ID);
        when(create.exec()).thenReturn(created);
        when(docker.createContainerCmd(anyString())).thenReturn(create);
        InspectContainerResponse container = mock(InspectContainerResponse.class);
        when(container.getImageId()).thenReturn(SOURCE);
        when(response.getBody()).thenAnswer(call -> {
            ObjectNode inspection = new ObjectMapper().createObjectNode();
            inspection.put("Image", HOST_SOURCE);
            ObjectNode descriptor = inspection.putObject("ImageManifestDescriptor");
            descriptor.put("digest", container.getImageId());
            descriptor.putObject("platform").put("os", "linux").put("architecture", "amd64");
            return body(inspection.toString());
        });
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(CONTAINER_ID)).thenReturn(remove);
        when(docker.tagImageCmd(anyString(), anyString(), anyString())).thenReturn(mock(TagImageCmd.class));
        Map<String, InspectImageResponse> images = new HashMap<>();
        images.put(builder.resolveImage(reference), inspected(SOURCE));
        when(docker.inspectImageCmd(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            InspectImageCmd inspect = mock(InspectImageCmd.class);
            when(inspect.exec()).thenAnswer(ignored -> {
                if (!images.containsKey(name)) {
                    throw new NotFoundException(name);
                }
                return images.get(name);
            });
            return inspect;
        });
        List<String> recipes = new ArrayList<>();
        BuildImageCmd build = mock(BuildImageCmd.class, RETURNS_SELF);
        BuildImageResultCallback result = mock(BuildImageResultCallback.class);
        AtomicReference<String> target = new AtomicReference<>();
        when(build.withTags(anySet())).thenAnswer(call -> {
            Set<String> tags = call.getArgument(0);
            target.set(tags.iterator().next());
            return build;
        });
        when(docker.buildImageCmd(any(InputStream.class))).thenAnswer(call -> {
            try (TarArchiveInputStream archive = new TarArchiveInputStream(call.getArgument(0))) {
                assertEquals("Dockerfile", archive.getNextEntry().getName());
                recipes.add(new String(archive.readAllBytes(), StandardCharsets.UTF_8));
                assertNull(archive.getNextEntry());
            }
            return build;
        });
        doReturn(result).when(build).exec(any(BuildImageResultCallback.class));
        when(result.awaitImageId(2, TimeUnit.MINUTES)).thenAnswer(call -> {
            images.put(target.get(), inspected(BUILT));
            return BUILT;
        });
        ImageCacheService imageCache = mock(ImageCacheService.class);
        return new Harness(new Ec2BootstrapImage(docker, builder, lifecycle, imageCache), docker, builder, lifecycle,
                create, http, response, container, remove, build, result, images, recipes, imageCache);
    }

    private record Harness(Ec2BootstrapImage helper, DockerClient docker, ContainerBuilder builder,
                           ContainerLifecycleManager lifecycle, CreateContainerCmd create,
                           DockerHttpClient http, DockerHttpClient.Response response, InspectContainerResponse container,
                           RemoveContainerCmd remove, BuildImageCmd build, BuildImageResultCallback result,
                           Map<String, InspectImageResponse> images, List<String> recipes, ImageCacheService imageCache) {}
}
