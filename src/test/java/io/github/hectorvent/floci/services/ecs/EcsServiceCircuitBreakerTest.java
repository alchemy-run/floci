package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.Deployment;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceEvent;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * The ECS deployment circuit breaker. Task failures come from the task reconciler reading each
 * container's exit code, which the mocked container manager reports as 1 for the crashing image
 * and "still running" for the stable one.
 */
class EcsServiceCircuitBreakerTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "cb-cluster";
    private static final String SERVICE = "cb-svc";
    private static final String STABLE_IMAGE = "public.ecr.aws/nginx/nginx:stable";
    private static final String CRASHING_IMAGE = "public.ecr.aws/docker/library/alpine:3.20";

    private final Set<String> exitedContainers = ConcurrentHashMap.newKeySet();
    private EcsContainerManager containerManager;
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
            if (CRASHING_IMAGE.equals(taskDef.getContainerDefinitions().getFirst().getImage())) {
                exitedContainers.add(containerId);
            }
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", containerId), Map.of());
        });
        when(containerManager.getExitCodeIfStopped(anyString())).thenAnswer(invocation ->
                exitedContainers.contains(invocation.<String>getArgument(0)) ? 1 : null);

        publisher = mock(EcsEventPublisher.class);
        service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                publisher);
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
    }

    @Test
    void failedTasksReachingTheThresholdFailTheDeploymentAndANewTaskDefinitionReplacesIt() {
        TaskDefinition initial = registerTaskDef("cb-fam", STABLE_IMAGE);
        TaskDefinition crashing = registerTaskDef("cb-fam", CRASHING_IMAGE);
        TaskDefinition recovery = registerTaskDef("cb-fam", STABLE_IMAGE);
        createService(initial, 0, false);
        service.reconcile();

        service.updateService(CLUSTER, SERVICE, crashing.getTaskDefinitionArn(), 1, null, REGION);
        String failedDeploymentId = describe().getDeploymentId();
        for (int tick = 0; tick < 4; tick++) {
            service.reconcile();
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
        service.reconcile();
        service.reconcile();
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

        service.reconcile(); // the replacement task starts
        service.reconcile(); // and is observed running: the deployment completes

        List<Deployment> recovered = service.deploymentsFor(describe());
        assertEquals(1, recovered.size());
        assertEquals(replacement.getId(), recovered.getFirst().getId());
        assertEquals("COMPLETED", recovered.getFirst().getRolloutState());
        assertEquals(1, describe().getRunningCount());
    }

    @Test
    void rollbackReturnsToTheLastCompletedTaskDefinitionInANewDeployment() {
        TaskDefinition stable = registerTaskDef("rb-fam", STABLE_IMAGE);
        TaskDefinition crashing = registerTaskDef("rb-fam", CRASHING_IMAGE);
        createService(stable, 1, true);
        service.reconcile(); // stable task starts
        service.reconcile(); // deployment completes
        String completedDeploymentId = describe().getDeploymentId();
        assertEquals("COMPLETED", service.deploymentsFor(describe()).getFirst().getRolloutState());

        service.updateService(CLUSTER, SERVICE, crashing.getTaskDefinitionArn(), null, null, REGION);
        String failedDeploymentId = describe().getDeploymentId();
        for (int tick = 0; tick < 4; tick++) {
            service.reconcile();
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

        service.reconcile(); // rollback task starts beside the original stable task
        service.reconcile(); // rollback completes and the original task is drained

        List<Deployment> recovered = service.deploymentsFor(describe());
        assertEquals(1, recovered.size());
        assertEquals(primary.getId(), recovered.getFirst().getId());
        assertEquals("COMPLETED", recovered.getFirst().getRolloutState());
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
        for (int tick = 0; tick < 6; tick++) {
            service.reconcile();
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
        for (int tick = 0; tick < 6; tick++) {
            service.reconcile();
        }

        Deployment primary = service.deploymentsFor(describe()).getFirst();
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

    private EcsServiceModel describe() {
        return service.describeServices(CLUSTER, List.of(SERVICE), REGION).getFirst();
    }

    private void createService(TaskDefinition taskDef, int desiredCount, boolean rollback) {
        CreateServiceRequest request = serviceRequest(taskDef, desiredCount);
        request.setDeploymentConfiguration(Map.of(
                "minimumHealthyPercent", 0,
                "maximumPercent", 200,
                "deploymentCircuitBreaker", Map.of("enable", true, "rollback", rollback)));
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
