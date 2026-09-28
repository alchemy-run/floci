package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.SageMakerHyperPodNodeLauncher.NodeLaunch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class SageMakerHyperPodNodeRunnerTest {

    @Test
    void missingOnCreateScriptFailsTheNodeBeforeAnyContainerStarts() throws InterruptedException {
        SageMakerHyperPodNodeRunner runner = runner();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicBoolean succeeded = new AtomicBoolean(true);
        AtomicReference<String> message = new AtomicReference<>();
        runner.launch(launch("i-0123456789abcdef0"), (ok, detail) -> {
            succeeded.set(ok);
            message.set(detail);
            reported.countDown();
        });

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        assertFalse(succeeded.get());
        assertTrue(message.get().contains("on_create.sh was not found under s3://lifecycle-bucket/lifecycle"));
        await().atMost(Duration.ofSeconds(5)).until(() -> !runner.inFlight("i-0123456789abcdef0"));
        runner.stopManagedContainers();
    }

    @Test
    void teardownStopsThePoolAndAfterResetRestoresIt() {
        SageMakerHyperPodNodeRunner runner = runner();
        assertTrue(runner.acceptsWork());
        runner.stopManagedContainers();
        assertFalse(runner.acceptsWork());
        runner.afterReset();
        assertTrue(runner.acceptsWork());
        runner.stopManagedContainers();
    }

    @Test
    void lifecycleFilesCannotEscapeTheLifecycleDirectory() {
        assertNull(SageMakerHyperPodNodeRunner.safeRelativePath("../outside.sh"));
        assertNull(SageMakerHyperPodNodeRunner.safeRelativePath("/etc/profile"));
        assertEquals("on_create.sh", SageMakerHyperPodNodeRunner.safeRelativePath("scripts/../on_create.sh"));
        assertEquals("utils/setup.sh", SageMakerHyperPodNodeRunner.safeRelativePath("utils/setup.sh"));
    }

    private static NodeLaunch launch(String instanceId) {
        return new NodeLaunch("000000000000", "us-east-1", "arn:aws:sagemaker:us-east-1:000000000000:cluster/abcdef012345",
                "abcdef012345", "slurm", "controller", instanceId, "ml.t3.medium",
                "s3://lifecycle-bucket/lifecycle", "on_create.sh", "{}");
    }

    private static SageMakerHyperPodNodeRunner runner() {
        return new SageMakerHyperPodNodeRunner(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(ContainerDetector.class), mock(S3Service.class));
    }
}
