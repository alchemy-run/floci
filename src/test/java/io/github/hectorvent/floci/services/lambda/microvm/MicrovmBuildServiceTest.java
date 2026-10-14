package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MicrovmBuildServiceTest {

    @TempDir
    Path tempDir;

    private Path dockerfile(String... lines) throws IOException {
        Path file = tempDir.resolve("Dockerfile");
        Files.write(file, List.of(lines));
        return file;
    }

    @Test
    void rewritesAwsManagedBaseImage() throws IOException {
        Path file = dockerfile(
                "FROM public.ecr.aws/lambda/microvms:al2023-minimal",
                "RUN dnf install -y nodejs && dnf clean all",
                "COPY *.mjs /app/");
        MicrovmBuildService.rewriteBaseImage(file);
        List<String> lines = Files.readAllLines(file);
        assertEquals("FROM " + MicrovmBuildService.localBaseImage(), lines.get(0));
        assertEquals("RUN dnf install -y nodejs && dnf clean all", lines.get(1));
    }

    @Test
    void preservesStageAliases() throws IOException {
        Path file = dockerfile(
                "FROM public.ecr.aws/lambda/microvms:al2023-minimal AS base",
                "FROM base");
        MicrovmBuildService.rewriteBaseImage(file);
        List<String> lines = Files.readAllLines(file);
        assertEquals("FROM " + MicrovmBuildService.localBaseImage() + " AS base", lines.get(0));
        assertEquals("FROM base", lines.get(1));
    }

    @Test
    void leavesForeignBaseImagesAlone() throws IOException {
        Path file = dockerfile(
                "FROM amazonlinux:2023",
                "RUN echo hello");
        MicrovmBuildService.rewriteBaseImage(file);
        assertEquals(List.of("FROM amazonlinux:2023", "RUN echo hello"),
                Files.readAllLines(file));
    }

    @Test
    void artifactFetchUsesImageOwnerAndRestoresContextOnFailure() {
        RequestContext context = new RequestContext();
        context.setAccountId("000000000000");
        context.setRegion("us-east-1");
        S3Service s3 = mock(S3Service.class);
        when(s3.getObject("tenant-bucket", "code.zip")).thenAnswer(call -> {
            assertEquals("111122223333", context.getAccountId());
            assertEquals("eu-west-1", context.getRegion());
            throw new IllegalStateException("Missing artifact");
        });
        MicrovmBuildService builds = new MicrovmBuildService(s3, mock(ContainerLifecycleManager.class), context);
        assertThrows(IllegalStateException.class, () -> builds.buildForAccount(
                "111122223333", "eu-west-1", "s3://tenant-bucket/code.zip", "local/image:test"));
        verify(s3).getObject("tenant-bucket", "code.zip");
        assertEquals("000000000000", context.getAccountId());
        assertEquals("us-east-1", context.getRegion());
    }

    @Test
    void dockerUriPatternMatchesPrebuiltReferences() {
        // The docker:// form short-circuits the build — covered end-to-end by
        // the alchemy dev suite; here we pin the accepted shapes.
        assertEquals("alchemy-dev/microvm-app:abc123",
                MicrovmBuildService.localImageRef("docker://alchemy-dev/microvm-app:abc123"));
        assertEquals(null, MicrovmBuildService.localImageRef("s3://bucket/key.zip"));
        assertEquals(null, MicrovmBuildService.localImageRef(null));
    }
}
