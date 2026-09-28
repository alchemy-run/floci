package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.Deployment;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.HealthCheck;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceEvent;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ECS deployment circuit breaker and the steady state a deployment must reach before it is
 * COMPLETED. Task failures come from the task reconciler reading each container's exit code: the
 * mocked container manager reports a container as running for a set number of inspections and
 * then as exited with code 1. Time comes from a test clock that each {@link #tick()} advances by
 * the reconciler's 5-second interval.
 */
class EcsServiceCircuitBreakerTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "cb-cluster";
    private static final String SERVICE = "cb-svc";
    private static final String STABLE_IMAGE = "public.ecr.aws/nginx/nginx:stable";
    /** Exits before the first inspection after launch. */
    private static final String CRASHING_IMAGE = "public.ecr.aws/docker/library/alpine:3.20";
    /** Seen running by the first inspection after launch, exited by the second. */
    private static final String FLAKY_IMAGE = "public.ecr.aws/docker/library/busybox:stable";
    private static final int NEVER_EXITS = -1;
    private static final Duration TICK = Duration.ofSeconds(5);

    /** Inspections each container still reports as running before it reports its exit. */
    private final Map<String, AtomicInteger> inspectionsBeforeExit = new ConcurrentHashMap<>();
    /** Per-launch lifetimes that override the image's default, consumed in launch order. */
    private final Deque<Integer> nextLifetimes = new ConcurrentLinkedDeque<>();
    private final AtomicReference<String> containerHealth = new AtomicReference<>("UNKNOWN");
    private final MutableClock clock = new MutableClock();
    private EcsContainerManager containerManager;
    private EcsLoadBalancerRegistrar lbRegistrar;
    private EcsEventPublisher publisher;
    private EcsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        containerManager = mock(EcsContainerManager.class);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            TaskDefinition taskDef = invocation.getArgument(1);
            String containerId = "docker-" + task.getTaskArn().substring(task.getTaskArn().lastIndexOf('/') + 1);
            Integer lifetime = nextLifetimes.poll();
            if (lifetime == null) {
                lifetime = defaultLifetime(taskDef.getContainerDefinitions().getFirst().getImage());
            }
            if (lifetime != NEVER_EXITS) {
                inspectionsBeforeExit.put(containerId, new AtomicInteger(lifetime));
            }
            Container container = new Container();
            container.setName("app");
            task.setContainers(List.of(container));
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", containerId), Map.of());
        });
        when(containerManager.getExitCodeIfStopped(anyString())).thenAnswer(invocation -> {
            AtomicInteger left = inspectionsBeforeExit.get(invocation.<String>getArgument(0));
            return left != null && left.getAndDecrement() <= 0 ? 1 : null;
        });
        when(containerManager.ecsHealthStatus(anyString())).thenAnswer(invocation -> containerHealth.get());

        lbRegistrar = mock(EcsLoadBalancerRegistrar.class);
        publisher = mock(EcsEventPublisher.class);
        service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                lbRegistrar,
                new InMemoryStorageFactory(),
                publisher);
        service.setClock(clock);
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
    }

    private static int defaultLifetime(String image) {
        if (CRASHING_IMAGE.equals(image)) {
            return 0;
        }
        if (FLAKY_IMAGE.equals(image)) {
            return 1;
        }
        return NEVER_EXITS;
    }

    @Test
    void failedTasksReachingTheThresholdFailTheDeploymentAndANewTaskDefinitionReplacesIt() {
        TaskDefinition initial = registerTaskDef("cb-fam", STABLE_IMAGE);
        TaskDefinition crashing = registerTaskDef("cb-fam", CRASHING_IMAGE);
        TaskDefinition recovery = registerTaskDef("cb-fam", STABLE_IMAGE);
        createService(initial, 0, false);
        tick();

        service.updateService(CLUSTER, SERVICE, crashing.getTaskDefinitionArn(), 1, null, REGION);
        String failedDeploymentId = describe().getDeploymentId();
        for (int i = 0; i < 4; i++) {
            tick();
        }

        List<Deployment> failed = service.deploymentsFor(describe());
        assertEquals(1, failed.size());
        Deployment primary = failed.getFirst();
        assertEquals("PRIMARY", primary.getStatus());
        assertEquals(failedDeploymentId, primary.getId());
        assertEquals(crashing.getTaskDefinitionArn(), primary.getTaskDefinition());
        assertEquals("FAILED", primary.getRolloutState());
        assertEquals("ECS deployment circuit breaker: tasks failed to start.", primary.getRolloutStateReason());
        assertEquals(3, primary.getFailedTasks(), "desiredCount 1 gives the minimum threshold of 3");
        verify(publisher).emitDeploymentStateChange(any(), eq("ERROR"), eq("SERVICE_DEPLOYMENT_FAILED"),
                any(), eq(REGION));
        List<ServiceEvent> events = service.eventsFor(describe());
        assertTrue(events.getFirst().message().contains("(deployment " + failedDeploymentId + ") deployment failed"),
                "service events report the failed deployment: " + events);

        // A FAILED deployment launches no new tasks.
        tick();
        tick();
        verify(containerManager, times(3)).startTask(any(), any(), any(), anyString());

        service.updateService(CLUSTER, SERVICE, recovery.getTaskDefinitionArn(), 1, null, REGION);
        List<Deployment> overlapping = service.deploymentsFor(describe());
        assertEquals(2, overlapping.size());
        Deployment replacement = overlapping.getFirst();
        assertEquals("PRIMARY", replacement.getStatus());
        assertNotEquals(failedDeploymentId, replacement.getId());
        assertEquals(recovery.getTaskDefinitionArn(), replacement.getTaskDefinition());
        assertEquals("IN_PROGRESS", replacement.getRolloutState());
        assertEquals(0, replacement.getFailedTasks());
        Deployment superseded = overlapping.get(1);
        assertEquals("ACTIVE", superseded.getStatus());
        assertEquals(failedDeploymentId, superseded.getId());
        assertEquals(crashing.getTaskDefinitionArn(), superseded.getTaskDefinition());
        assertEquals("FAILED", superseded.getRolloutState());
        assertEquals(3, superseded.getFailedTasks());

        assertCompletesOnceSteady(replacement.getId());
        assertEquals(1, describe().getRunningCount());
    }

    /**
     * The Alchemy regression: an essential container that exits right after starting is RUNNING
     * when one reconcile tick looks at it, with runningCount equal to desiredCount. That must not
     * complete the deployment, must not reset the failure count, and the stopped task must still
     * count towards the breaker, which trips well within a deploy's stabilization timeout.
     */
    @Test
    void aTaskSeenRunningBeforeItsEssentialContainerExitsDoesNotCompleteTheDeployment() {
        TaskDefinition initial = registerTaskDef("flaky-fam", STABLE_IMAGE);
        TaskDefinition flaky = registerTaskDef("flaky-fam", FLAKY_IMAGE);
        TaskDefinition recovery = registerTaskDef("flaky-fam", STABLE_IMAGE);
        createService(initial, 0, false);
        tick();

        service.updateService(CLUSTER, SERVICE, flaky.getTaskDefinitionArn(), 1, null, REGION);
        String failedDeploymentId = describe().getDeploymentId();
        Instant updatedAt = clock.instant();

        tick(); // the first task starts
        tick(); // and is seen running: this is the tick that used to complete the deployment
        EcsServiceModel seenRunning = describe();
        assertEquals(1, seenRunning.getRunningCount());
        assertEquals(seenRunning.getDesiredCount(), seenRunning.getRunningCount());
        Deployment inProgress = service.deploymentsFor(seenRunning).getFirst();
        assertEquals(failedDeploymentId, inProgress.getId());
        assertEquals("IN_PROGRESS", inProgress.getRolloutState());
        assertEquals(0, inProgress.getFailedTasks());
        assertFalse(service.eventsFor(seenRunning).stream()
                        .anyMatch(e -> e.message().contains("has reached a steady state")),
                "no steady-state event while the task has not stayed up");

        List<Integer> failedTasksPerTick = new ArrayList<>();
        for (int i = 0; i < 5 && !"FAILED".equals(primary().getRolloutState()); i++) {
            tick();
            Deployment primary = primary();
            assertNotEquals("COMPLETED", primary.getRolloutState(), "tick " + i);
            failedTasksPerTick.add(primary.getFailedTasks());
        }

        Deployment failed = primary();
        assertEquals(failedDeploymentId, failed.getId());
        assertEquals("FAILED", failed.getRolloutState());
        assertEquals(3, failed.getFailedTasks());
        assertEquals(List.of(1, 1, 2, 2, 3), failedTasksPerTick,
                "each task seen running still counts when it exits, and never resets the count");
        verify(containerManager, times(3)).startTask(any(), any(), any(), anyString());
        assertTrue(Duration.between(updatedAt, clock.instant()).compareTo(Duration.ofMinutes(1)) <= 0,
                "the breaker trips within a minute, well inside an 8-minute stabilization timeout");
        // Only the initial, taskless deployment ever completed.
        verify(publisher, times(1)).emitDeploymentStateChange(any(), eq("SERVICE_DEPLOYMENT_COMPLETED"),
                any(), eq(REGION));

        service.updateService(CLUSTER, SERVICE, recovery.getTaskDefinitionArn(), 1, null, REGION);
        List<Deployment> overlapping = service.deploymentsFor(describe());
        assertEquals(2, overlapping.size());
        assertEquals("IN_PROGRESS", overlapping.getFirst().getRolloutState());
        assertEquals(failedDeploymentId, overlapping.get(1).getId());
        assertEquals("FAILED", overlapping.get(1).getRolloutState());

        assertCompletesOnceSteady(overlapping.getFirst().getId());
        assertEquals(recovery.getTaskDefinitionArn(), describe().getTaskDefinition());
    }

    @Test
    void resetOnHealthyTaskResetsOnlyOnceATaskHasReachedASteadyState() {
        TaskDefinition crashing = registerTaskDef("reset-fam", CRASHING_IMAGE);
        nextLifetimes.add(NEVER_EXITS); // the first task stays up, every other one crashes
        createService(crashing, 2, Map.of("enable", true, "rollback", false));

        tick(); // one stable and one crashing task start
        tick(); // the crash counts; the stable task is running but not yet steady
        tick(); // another crash: still no reset
        assertEquals(2, primary().getFailedTasks());
        assertEquals("IN_PROGRESS", primary().getRolloutState());

        tick(); // the stable task reaches steady state (reset) as a third crash is counted
        assertEquals(1, primary().getFailedTasks(),
                "without the reset this third failure would have tripped the threshold of 3");
        assertEquals("IN_PROGRESS", primary().getRolloutState());
    }

    @Test
    void withoutResetOnHealthyTaskFailuresAccumulateAcrossASteadyTask() {
        TaskDefinition crashing = registerTaskDef("noreset-fam", CRASHING_IMAGE);
        nextLifetimes.add(NEVER_EXITS);
        createService(crashing, 2, Map.of("enable", true, "rollback", false, "resetOnHealthyTask", false));

        for (int i = 0; i < 4; i++) {
            tick();
        }

        assertEquals("FAILED", primary().getRolloutState());
        assertEquals(3, primary().getFailedTasks());
    }

    @Test
    void anEssentialContainerHealthCheckMustReportHealthyBeforeTheDeploymentCompletes() {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(STABLE_IMAGE);
        cd.setHealthCheck(new HealthCheck(List.of("CMD-SHELL", "true"), 5, 2, 3, 0));
        TaskDefinition checked = service.registerTaskDefinition("hc-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        createService(checked, 1, false);

        tick(); // starts
        for (int i = 0; i < 4; i++) {
            tick(); // running past the window, but its health check has not passed
        }
        assertEquals("IN_PROGRESS", primary().getRolloutState());

        containerHealth.set("HEALTHY");
        tick();
        assertEquals("COMPLETED", primary().getRolloutState());
    }

    @Test
    void aLoadBalancedTaskCountsOnlyOnceItsTargetsAreHealthy() {
        TaskDefinition stable = registerTaskDef("lb-fam", STABLE_IMAGE);
        EcsLoadBalancer lb = new EcsLoadBalancer();
        lb.setTargetGroupArn("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/cb-tg/abc");
        lb.setContainerName("app");
        lb.setContainerPort(80);
        CreateServiceRequest request = serviceRequest(stable, 1);
        request.setLoadBalancers(List.of(lb));
        request.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker",
                Map.of("enable", true, "rollback", false)));
        service.createService(request, REGION);
        when(lbRegistrar.targetsHealthy(any(), any(), anyString())).thenReturn(false);

        tick(); // starts
        for (int i = 0; i < 4; i++) {
            tick(); // running past the window, but its target is not healthy yet
        }
        assertEquals("IN_PROGRESS", primary().getRolloutState());

        when(lbRegistrar.targetsHealthy(any(), any(), anyString())).thenReturn(true);
        tick();
        assertEquals("COMPLETED", primary().getRolloutState());
    }

    @Test
    void rollbackReturnsToTheLastCompletedTaskDefinitionInANewDeployment() {
        TaskDefinition stable = registerTaskDef("rb-fam", STABLE_IMAGE);
        TaskDefinition crashing = registerTaskDef("rb-fam", CRASHING_IMAGE);
        createService(stable, 1, true);
        String completedDeploymentId = describe().getDeploymentId();
        assertCompletesOnceSteady(completedDeploymentId);

        service.updateService(CLUSTER, SERVICE, crashing.getTaskDefinitionArn(), null, null, REGION);
        String failedDeploymentId = describe().getDeploymentId();
        for (int i = 0; i < 4; i++) {
            tick();
        }

        EcsServiceModel rolledBack = describe();
        assertEquals(stable.getTaskDefinitionArn(), rolledBack.getTaskDefinition());
        List<Deployment> deployments = service.deploymentsFor(rolledBack);
        assertEquals(2, deployments.size());
        Deployment primary = deployments.getFirst();
        assertEquals("PRIMARY", primary.getStatus());
        assertNotEquals(completedDeploymentId, primary.getId());
        assertNotEquals(failedDeploymentId, primary.getId());
        assertEquals(stable.getTaskDefinitionArn(), primary.getTaskDefinition());
        assertEquals("IN_PROGRESS", primary.getRolloutState());
        assertEquals("ECS deployment circuit breaker: rolling back to deploymentId " + completedDeploymentId + ".",
                primary.getRolloutStateReason());
        Deployment failed = deployments.get(1);
        assertEquals("ACTIVE", failed.getStatus());
        assertEquals(failedDeploymentId, failed.getId());
        assertEquals(crashing.getTaskDefinitionArn(), failed.getTaskDefinition());
        assertEquals("FAILED", failed.getRolloutState());
        assertTrue(service.eventsFor(rolledBack).stream()
                .anyMatch(e -> e.message().contains("rolling back to deployment " + completedDeploymentId)));

        // The rollback task starts beside the original stable task, which is drained on the next
        // tick; the rollback completes once its own task has stayed up.
        assertCompletesOnceSteady(primary.getId());
        List<EcsTask> running = runningTasks();
        assertEquals(1, running.size());
        assertEquals(primary.getId(), running.getFirst().getDeploymentId());
        // stable, three crashing attempts, then the rollback replacement
        verify(containerManager, times(5)).startTask(any(), any(), any(), anyString());
    }

    @Test
    void rollbackWithoutACompletedDeploymentStallsTheFailedDeployment() {
        TaskDefinition crashing = registerTaskDef("stall-fam", CRASHING_IMAGE);
        createService(crashing, 1, true);
        String deploymentId = describe().getDeploymentId();
        for (int i = 0; i < 6; i++) {
            tick();
        }

        List<Deployment> deployments = service.deploymentsFor(describe());
        assertEquals(1, deployments.size());
        assertEquals(deploymentId, deployments.getFirst().getId());
        assertEquals("FAILED", deployments.getFirst().getRolloutState());
        assertEquals(crashing.getTaskDefinitionArn(), describe().getTaskDefinition());
        verify(containerManager, times(3)).startTask(any(), any(), any(), anyString());
    }

    @Test
    void disabledCircuitBreakerKeepsReplacingFailedTasks() {
        TaskDefinition crashing = registerTaskDef("off-fam", CRASHING_IMAGE);
        CreateServiceRequest request = serviceRequest(crashing, 1);
        request.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker",
                Map.of("enable", false, "rollback", false)));
        service.createService(request, REGION);
        for (int i = 0; i < 6; i++) {
            tick();
        }

        Deployment primary = primary();
        assertEquals("IN_PROGRESS", primary.getRolloutState());
        assertEquals(0, primary.getFailedTasks());
        verify(containerManager, times(6)).startTask(any(), any(), any(), anyString());
        verify(publisher, never()).emitDeploymentStateChange(any(), eq("ERROR"), any(), any(), any());
    }

    @Test
    void failureThresholdFollowsTheConfiguredThresholdType() {
        EcsServiceModel svc = new EcsServiceModel();
        assertNull(EcsService.CircuitBreaker.of(svc));

        svc.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker", Map.of("enable", true)));
        EcsService.CircuitBreaker bounded = EcsService.CircuitBreaker.of(svc);
        assertEquals(3, bounded.threshold(1));
        assertEquals(13, bounded.threshold(25));
        assertEquals(200, bounded.threshold(400));
        assertTrue(bounded.resetOnHealthyTask());

        svc.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker", Map.of("enable", true,
                "resetOnHealthyTask", false,
                "thresholdConfiguration", Map.of("type", "COUNT", "value", 5))));
        EcsService.CircuitBreaker count = EcsService.CircuitBreaker.of(svc);
        assertEquals(5, count.threshold(800));
        assertFalse(count.resetOnHealthyTask());

        svc.setDeploymentConfiguration(Map.of("deploymentCircuitBreaker", Map.of("enable", true,
                "thresholdConfiguration", Map.of("type", "UNBOUNDED_PERCENT", "value", 50))));
        assertEquals(400, EcsService.CircuitBreaker.of(svc).threshold(800));

        svc.setDeploymentController("CODE_DEPLOY");
        assertNull(EcsService.CircuitBreaker.of(svc), "the breaker only applies to the ECS deployment controller");
    }

    /**
     * Starts the deployment's task, then checks it stays IN_PROGRESS while the task has been up for
     * less than the steady-state window and is COMPLETED, alone, on the tick the window elapses.
     */
    private void assertCompletesOnceSteady(String deploymentId) {
        tick(); // the deployment's task starts
        long ticksInWindow = EcsService.STEADY_STATE_WINDOW.dividedBy(TICK);
        for (long i = 1; i < ticksInWindow; i++) {
            tick();
            Deployment primary = primary();
            assertEquals(deploymentId, primary.getId());
            assertEquals("IN_PROGRESS", primary.getRolloutState(),
                    "running for " + TICK.multipliedBy(i) + " is not yet a steady state");
        }
        tick();
        List<Deployment> completed = service.deploymentsFor(describe());
        assertEquals(1, completed.size(), "failed deployments drain once the PRIMARY completes");
        assertEquals(deploymentId, completed.getFirst().getId());
        assertEquals("COMPLETED", completed.getFirst().getRolloutState());
    }

    private void tick() {
        clock.advance(TICK);
        service.reconcile();
    }

    private Deployment primary() {
        return service.deploymentsFor(describe()).getFirst();
    }

    private EcsServiceModel describe() {
        return service.describeServices(CLUSTER, List.of(SERVICE), REGION).getFirst();
    }

    private void createService(TaskDefinition taskDef, int desiredCount, boolean rollback) {
        createService(taskDef, desiredCount, Map.of("enable", true, "rollback", rollback));
    }

    private void createService(TaskDefinition taskDef, int desiredCount, Map<String, Object> breaker) {
        CreateServiceRequest request = serviceRequest(taskDef, desiredCount);
        request.setDeploymentConfiguration(Map.of(
                "minimumHealthyPercent", 0,
                "maximumPercent", 200,
                "deploymentCircuitBreaker", breaker));
        service.createService(request, REGION);
    }

    private static CreateServiceRequest serviceRequest(TaskDefinition taskDef, int desiredCount) {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(CLUSTER);
        request.setServiceName(SERVICE);
        request.setTaskDefinition(taskDef.getTaskDefinitionArn());
        request.setDesiredCount(desiredCount);
        request.setLaunchType(LaunchType.FARGATE);
        return request;
    }

    private TaskDefinition registerTaskDef(String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    private List<EcsTask> runningTasks() {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                        String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
