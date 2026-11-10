package io.floci.aws.services.ecs.container;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Nitro Sandbox task runs on the configured sandbox runtime (gVisor's runsc, say), so it does
 * not share the host kernel. Other tasks, and every task when no runtime is configured, keep the
 * daemon's default.
 */
class EcsContainerManagerSandboxRuntimeTest {

    private ContainerBuilder.Builder start(Optional<String> sandboxRuntime, String isolationMode) {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().sandboxRuntime()).thenReturn(sandboxRuntime);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));
        EcsContainerManager manager = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class), mock(SecretsManagerService.class),
                mock(S3Service.class), ecrRegistryManager, mock(HostVolumePolicy.class));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("busybox:stable");
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("sandbox-family");
        taskDef.setContainerDefinitions(List.of(app));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/sandbox-cluster/abc123");
        task.setIsolationMode(isolationMode);

        manager.startTask(task, taskDef, List.of(), "us-east-1");
        return builder;
    }

    @Test
    void aSandboxTaskRunsOnTheSandboxRuntime() {
        ContainerBuilder.Builder builder = start(Optional.of("runsc"), "ISOLATED_SANDBOX");
        verify(builder).withRuntime("runsc");
    }

    @Test
    void aSharedTaskKeepsTheDefaultRuntime() {
        verify(start(Optional.of("runsc"), null), never()).withRuntime(anyString());
    }

    @Test
    void withoutASandboxRuntimeASandboxTaskKeepsTheDefault() {
        verify(start(Optional.empty(), "ISOLATED_SANDBOX"), never()).withRuntime(anyString());
    }
}
