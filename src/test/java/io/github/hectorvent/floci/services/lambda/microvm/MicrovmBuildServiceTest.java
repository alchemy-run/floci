package io.github.hectorvent.floci.services.lambda.microvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void dockerUriPatternMatchesPrebuiltReferences() {
        // The docker:// form short-circuits the build — covered end-to-end by
        // the alchemy dev suite; here we pin the accepted shapes.
        assertEquals("alchemy-dev/microvm-app:abc123",
                MicrovmBuildService.localImageRef("docker://alchemy-dev/microvm-app:abc123"));
        assertEquals(null, MicrovmBuildService.localImageRef("s3://bucket/key.zip"));
        assertEquals(null, MicrovmBuildService.localImageRef(null));
    }
}
