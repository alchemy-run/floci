package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeBuildRunnerTest {
    private static final String IMAGE = "aws/codebuild/amazonlinux2-x86_64-standard:5.0";
    private static final String RESOLVED_IMAGE = "public.ecr.aws/codebuild/amazonlinux-x86_64-standard:5.0";
    private static final String BUILDSPEC = """
            version: 0.2
            phases:
              install:
                commands:
                  - echo install
              pre_build:
                commands:
                  - echo pre-build
              build:
                commands:
                  - echo first
                  - echo second
              post_build:
                commands:
                  - echo post-build
            """;

    @Test
    void resolvesManagedImagesWithoutReplacingCustomImages() {
        assertEquals(RESOLVED_IMAGE, CodeBuildRunner.resolveBuildImage(IMAGE));
        assertEquals("public.ecr.aws/codebuild/standard:7.0",
                CodeBuildRunner.resolveBuildImage("aws/codebuild/standard:7.0"));
        assertEquals("alpine:3.21", CodeBuildRunner.resolveBuildImage("alpine:3.21"));
        assertEquals(RESOLVED_IMAGE, CodeBuildRunner.resolveBuildImage(RESOLVED_IMAGE));
    }

    @Test
    void runsEveryCommandAndRequiresSuccessfulExecExitCodes() {
        Harness harness = new Harness();
        harness.run();
        assertEquals("SUCCEEDED", harness.build.getBuildStatus());
        verify(harness.containerBuilder).newContainer(RESOLVED_IMAGE);
        verify(harness.builder).withEntrypoint(List.of("sh", "-c"));
        verify(harness.lifecycle).create(harness.spec, "linux/amd64");
        verify(harness.execCreate).withCmd(new String[]{"sh", "-e", "-c", "echo install"});
        verify(harness.execCreate).withCmd(new String[]{"sh", "-e", "-c", "echo pre-build"});
        verify(harness.execCreate).withCmd(new String[]{"sh", "-e", "-c", "echo first\necho second"});
        verify(harness.execCreate).withCmd(new String[]{"sh", "-e", "-c", "echo post-build"});
        verify(harness.logStreamer, never()).attach(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(harness.lifecycle).stopAndRemove("worker", null);
    }

    @Test
    void usesHostPlatformUnlessEnvironmentTypeIsHonoured() {
        Harness harness = new Harness(false);
        harness.run();
        assertEquals("SUCCEEDED", harness.build.getBuildStatus());
        verify(harness.lifecycle).create(harness.spec);
        verify(harness.lifecycle, never()).create(eq(harness.spec), anyString());

        Harness arm = new Harness(true);
        arm.build.getEnvironment().setType("ARM_CONTAINER");
        when(arm.lifecycle.create(arm.spec, "linux/arm64")).thenReturn("worker");
        arm.run();
        assertEquals("SUCCEEDED", arm.build.getBuildStatus());
        verify(arm.lifecycle).create(arm.spec, "linux/arm64");
    }

    @Test
    void pullFailurePreservesFaultAndDiagnosticContext() {
        Harness harness = new Harness();
        when(harness.lifecycle.create(harness.spec, "linux/amd64"))
                .thenThrow(new IllegalStateException("pull access denied for managed image"));
        harness.run();
        assertEquals("FAULT", harness.build.getBuildStatus());
        assertTrue(harness.build.getBuildComplete());
        assertTrue(harness.build.getPhases().getLast().getContexts().getFirst().get("message")
                .contains("pull access denied"));
        verify(harness.execCreate, never()).exec();
    }

    @Test
    void stopDuringPullDoesNotBecomeFaultAndDoesNotRunCommands() {
        Harness harness = new Harness();
        when(harness.lifecycle.create(harness.spec, "linux/amd64")).thenAnswer(invocation -> {
            harness.stop.set(true);
            throw new IllegalStateException("pull cancelled");
        });
        harness.run();
        assertEquals("STOPPED", harness.build.getBuildStatus());
        verify(harness.execCreate, never()).exec();
    }

    @Test
    void cancellationAfterCreateCleansUpWithoutStartingWorker() {
        Harness harness = new Harness();
        when(harness.lifecycle.create(harness.spec, "linux/amd64")).thenAnswer(invocation -> {
            harness.stop.set(true);
            return "worker";
        });
        harness.run();
        assertEquals("STOPPED", harness.build.getBuildStatus());
        verify(harness.lifecycle, never()).startCreated(anyString(), any());
        verify(harness.lifecycle).stopAndRemove("worker", null);
    }

    @Test
    void commandFailureNeverBecomesSuccess() {
        Harness harness = new Harness();
        when(harness.inspection.getExitCodeLong()).thenReturn(0L, 9L, 0L);
        harness.run();
        assertEquals("FAILED", harness.build.getBuildStatus());
        verify(harness.execCreate).withCmd(new String[]{"sh", "-e", "-c", "echo post-build"});
        verify(harness.execCreate, never()).withCmd(new String[]{"sh", "-e", "-c", "echo first\necho second"});
    }

    @Test
    void missingExitCodeAndStreamErrorsFailThePhase() {
        Harness harness = new Harness();
        when(harness.inspection.getExitCodeLong()).thenReturn(null);
        CodeBuildRunner.PhaseResult missingExit = harness.runner.runPhase("worker", "/", List.of(),
                List.of("echo hello"), 1, harness.stop);
        assertTrue(missingExit.failed());
        assertTrue(missingExit.errorMessage().contains("without an exit code"));

        doAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onError(new IllegalStateException("stream disconnected"));
            return callback;
        }).when(harness.execStart).exec(any());
        CodeBuildRunner.PhaseResult streamFailure = harness.runner.runPhase("worker", "/", List.of(),
                List.of("echo hello"), 1, harness.stop);
        assertTrue(streamFailure.failed());
        assertTrue(streamFailure.errorMessage().contains("stream disconnected"));
    }

    @Test
    void unsupportedExecutionIsRejectedRatherThanSilentlySkipped() {
        Harness harness = new Harness();
        harness.project.getSource().setType("GITHUB");
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> harness.runner.validateExecution(harness.build, harness.project)).getErrorCode());
        for (String spec : List.of(
                BUILDSPEC + "reports:\n  reports: {}\n",
                "version: 0.2\nphases:\n  build:\n    finally:\n      - echo required\n",
                "version: 0.2\nphases:\n  build:\n    commands:\n      - key: value\n")) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> BuildspecParser.parse(spec)).getErrorCode());
        }
    }

    private static final class Harness {
        final DockerClient docker = mock(DockerClient.class);
        final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        final ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        final ContainerSpec spec = mock(ContainerSpec.class);
        final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        final ExecCreateCmd execCreate = mock(ExecCreateCmd.class, RETURNS_SELF);
        final ExecStartCmd execStart = mock(ExecStartCmd.class);
        final InspectExecResponse inspection = mock(InspectExecResponse.class);
        final AtomicBoolean stop = new AtomicBoolean();
        final Build build = new Build();
        final Project project = new Project();
        final CodeBuildRunner runner;

        Harness() {
            this(true);
        }

        Harness(boolean honourEnvironmentType) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().codebuild().honourEnvironmentType()).thenReturn(honourEnvironmentType);
            RegionResolver regionResolver = mock(RegionResolver.class);
            when(regionResolver.getAccountId()).thenReturn("000000000000");
            when(logStreamer.generateLogStreamName(anyString())).thenReturn("build-log");
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(spec);
            when(lifecycle.create(spec, "linux/amd64")).thenReturn("worker");
            when(lifecycle.create(spec)).thenReturn("worker");
            when(docker.execCreateCmd("worker")).thenReturn(execCreate);
            ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
            when(created.getId()).thenReturn("exec");
            when(execCreate.exec()).thenReturn(created);
            when(docker.execStartCmd("exec")).thenReturn(execStart);
            when(execStart.exec(any())).thenAnswer(invocation -> {
                ResultCallback<Frame> callback = invocation.getArgument(0);
                callback.onComplete();
                return callback;
            });
            InspectExecCmd inspect = mock(InspectExecCmd.class);
            when(docker.inspectExecCmd("exec")).thenReturn(inspect);
            when(inspect.exec()).thenReturn(inspection);
            when(inspection.getExitCodeLong()).thenReturn(0L);
            CopyArchiveFromContainerCmd copy = mock(CopyArchiveFromContainerCmd.class);
            when(docker.copyArchiveFromContainerCmd("worker", "/codebuild/output/src/src")).thenReturn(copy);
            when(copy.exec()).thenAnswer(invocation -> new ByteArrayInputStream(new byte[1024]));
            runner = new CodeBuildRunner(docker, containerBuilder, lifecycle, logStreamer,
                    mock(S3Service.class), mock(SsmService.class), mock(SecretsManagerService.class),
                    config, mock(ContainerDetector.class), regionResolver);
            ProjectEnvironment environment = new ProjectEnvironment();
            environment.setImage(IMAGE);
            environment.setType("LINUX_CONTAINER");
            project.setName("worker-test");
            project.setEnvironment(environment);
            ProjectSource source = new ProjectSource();
            source.setType("NO_SOURCE");
            source.setBuildspec(BUILDSPEC);
            project.setSource(source);
            project.setLogsConfig(Map.of("cloudWatchLogs", Map.of("status", "DISABLED")));
            build.setId("worker-test:1");
            build.setArn("arn:aws:codebuild:us-east-1:000000000000:build/worker-test:1");
            build.setEnvironment(environment);
            build.setPhases(new CopyOnWriteArrayList<>());
        }

        void run() {
            runner.runBuild("us-east-1", build, project, null, stop, build.getArn());
        }
    }
}
