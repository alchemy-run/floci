package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class EmrServerlessSparkRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ContainerLifecycleManager lifecycle;
    private ContainerBuilder.Builder builder;
    private DockerClient docker;
    private IamService iam;
    private EmrServerlessSparkRunner runner;
    private ObjectNode job;

    @BeforeEach
    void setUp() throws Exception {
        lifecycle = mock(ContainerLifecycleManager.class);
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container-id");
        ContainerBuilder containers = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containers.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        iam = mock(IamService.class);
        runner = new EmrServerlessSparkRunner(containers, lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, iam, "spark:test", "/opt/spark");
        job = (ObjectNode) mapper.readTree("""
                {"_accountId":"111111111111","_region":"us-east-1","applicationId":"application",
                 "jobRunId":"job","executionRole":"arn:aws:iam::111111111111:role/worker",
                 "jobDriver":{"sparkSubmit":{"entryPoint":"local:///usr/lib/spark/examples/src/main/python/pi.py"}}}
                """);
        job.put("_deadline", System.currentTimeMillis() + 30_000);
        when(docker.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(false);
        when(docker.inspectContainerCmd("container-id").exec().getState().getStatus()).thenReturn("exited");
        when(docker.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(0L);
        when(docker.inspectContainerCmd(runner.containerName(job)).exec().getId()).thenReturn("container-id");
        when(docker.inspectContainerCmd(runner.containerName(job)).exec().getConfig().getLabels())
                .thenReturn(ContainerStorageHelper.resourceIdentityLabels("emrserverless", "job", "111111111111", "us-east-1"));
    }

    @Test
    void commandTranslatesSparkHomeWithoutShellEvaluationAndBoundsLocalCapacity() throws Exception {
        ObjectNode spark = mapper.createObjectNode()
                .put("entryPoint", "local:///usr/lib/spark/examples/src/main/python/pi.py")
                .put("sparkSubmitParameters", "--conf 'spark.executor.memory=2g' --name \"a b\"");
        spark.putArray("entryPointArguments").add("2").add("literal; $(not-a-shell)");
        List<String> command = EmrServerlessSparkRunner.command(spark, "/opt/spark");
        assertTrue(command.contains("a b"));
        assertTrue(command.contains("/opt/spark/examples/src/main/python/pi.py"));
        assertEquals("literal; $(not-a-shell)", command.getLast());
        assertTrue(command.lastIndexOf("spark.executor.memory=512m") > command.indexOf("spark.executor.memory=2g"));
        assertTrue(command.contains("local[1]"));
        assertThrows(AwsException.class, () -> EmrServerlessSparkRunner.tokenize("--name 'unterminated"));
        assertThrows(AwsException.class, () -> EmrServerlessSparkRunner.command(
                spark.deepCopy().put("entryPoint", "s3://bucket/job.py"), "/opt/spark"));
    }

    @Test
    void successfulExitUsesBoundedResourcesAndExpiringRoleCredentials() {
        assertEquals(0, runner.run(job, new EmrServerlessSparkRunner.Execution(), id -> {}, () -> {}).exitCode());
        verify(builder).withMemoryMb(1536);
        verify(builder).withCpuUnits(1024);
        verify(builder, never()).withEntrypoint(anyList());
        verify(builder).withCmd(argThat((List<String> command) ->
                "/opt/spark/bin/spark-submit".equals(command.getFirst())));
        verify(lifecycle).stopAndRemoveStrict("container-id", null);
        ArgumentCaptor<String> accessKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiration = ArgumentCaptor.forClass(Instant.class);
        verify(iam).registerSessionForAccount(eq("111111111111"), accessKey.capture(), anyString(), anyString(),
                eq("arn:aws:iam::111111111111:role/worker"), expiration.capture(), isNull());
        assertTrue(accessKey.getValue().startsWith("ASIA"));
        assertTrue(expiration.getValue().isAfter(Instant.now()));
        assertTrue(expiration.getValue().isBefore(Instant.now().plusSeconds(90)));
        verify(iam).unregisterSession("111111111111", accessKey.getValue());
    }

    @Test
    void cancellationDuringCreationRemovesTheContainerWithoutStartingIt() {
        EmrServerlessSparkRunner.Execution execution = new EmrServerlessSparkRunner.Execution();
        when(lifecycle.create(any(ContainerSpec.class))).thenAnswer(call -> {
            execution.cancelled.set(true);
            return "container-id";
        });
        assertNotEquals(0, runner.run(job, execution, id -> {}, () -> fail("Worker must not start")).exitCode());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemoveStrict("container-id", null);
    }

    @Test
    void cancellationOfARunningWorkerCompletesOnlyAfterStrictRemoval() throws Exception {
        when(docker.inspectContainerCmd("container-id").exec().getState().getRunning()).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<EmrServerlessSparkRunner.Result> result = new AtomicReference<>();
        EmrServerlessSparkRunner.Execution execution = new EmrServerlessSparkRunner.Execution();
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try {
                result.set(runner.run(job, execution, id -> {}, started::countDown));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        execution.thread = thread;
        thread.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
        } finally {
            execution.stop(false);
            thread.join(2000);
        }
        assertFalse(thread.isAlive());
        assertNull(failure.get());
        assertNotEquals(0, result.get().exitCode());
        verify(lifecycle).stopAndRemoveStrict("container-id", null);
    }

    @Test
    void missingExitCodeCannotBecomeSuccess() {
        when(docker.inspectContainerCmd("container-id").exec().getState().getExitCodeLong()).thenReturn(null);
        assertNotEquals(0, runner.run(job, new EmrServerlessSparkRunner.Execution(), id -> {}, () -> {}).exitCode());
    }

    @Test
    void anUnstartedContainerCannotReportSuccessfulCompletion() {
        when(docker.inspectContainerCmd("container-id").exec().getState().getStatus()).thenReturn("created");
        job.put("_deadline", System.currentTimeMillis() + 50);
        EmrServerlessSparkRunner.Execution execution = new EmrServerlessSparkRunner.Execution();
        assertNotEquals(0, runner.run(job, execution, id -> {}, () -> {}).exitCode());
        assertTrue(execution.timedOut.get());
        verify(lifecycle).stopAndRemoveStrict("container-id", null);
    }

    @Test
    void failedCleanupIsPropagatedEvenAfterSuccessfulExit() {
        doThrow(new IllegalStateException("remove failed")).when(lifecycle).stopAndRemoveStrict("container-id", null);
        assertThrows(IllegalStateException.class,
                () -> runner.run(job, new EmrServerlessSparkRunner.Execution(), id -> {}, () -> {}));
        verify(iam).unregisterSession(eq("111111111111"), anyString());
    }

    @Test
    void cleanupRefusesContainersOwnedByAnotherScope() {
        when(docker.inspectContainerCmd(runner.containerName(job)).exec().getConfig().getLabels())
                .thenReturn(Map.of("io.floci.account", "222222222222"));
        assertThrows(IllegalStateException.class, () -> runner.cleanup(job));
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any());
    }
}
