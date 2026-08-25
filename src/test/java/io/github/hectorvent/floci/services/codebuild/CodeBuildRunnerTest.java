package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeBuildRunnerTest {

    @Test
    void curatedAwsImagesMapToLocalBusybox() {
        assertEquals("busybox:stable",
                CodeBuildRunner.resolveRuntimeImage("aws/codebuild/amazonlinux2-x86_64-standard:5.0"));
        assertEquals("busybox:stable",
                CodeBuildRunner.resolveRuntimeImage("aws/codebuild/standard:7.0"));
        assertEquals("busybox:stable", CodeBuildRunner.resolveRuntimeImage(null));
        assertEquals("busybox:stable", CodeBuildRunner.resolveRuntimeImage(""));
    }

    @Test
    void publicImagesAreUnchanged() {
        assertEquals("public.ecr.aws/docker/library/busybox:1.36",
                CodeBuildRunner.resolveRuntimeImage("public.ecr.aws/docker/library/busybox:1.36"));
        assertEquals("alpine:latest", CodeBuildRunner.resolveRuntimeImage("alpine:latest"));
    }
}
