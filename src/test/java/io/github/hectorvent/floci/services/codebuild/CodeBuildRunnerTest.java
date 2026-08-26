package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeBuildRunnerTest {

    @Test
    void noSourceProjectsSkipDocker() {
        assertTrue(CodeBuildRunner.isNoSource(null));
        assertTrue(CodeBuildRunner.isNoSource(new Project()));
        Project blank = new Project();
        ProjectSource blankSource = new ProjectSource();
        blankSource.setType("");
        blank.setSource(blankSource);
        assertTrue(CodeBuildRunner.isNoSource(blank));
        Project noSource = new Project();
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        noSource.setSource(source);
        assertTrue(CodeBuildRunner.isNoSource(noSource));
        Project s3 = new Project();
        ProjectSource s3Source = new ProjectSource();
        s3Source.setType("S3");
        s3.setSource(s3Source);
        assertFalse(CodeBuildRunner.isNoSource(s3));
    }

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
