package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class EmrServerlessJobServiceTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String APP = "00abcdefabcdef01";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/worker";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Principal":{"Service":"emr-serverless.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<ObjectNode> store;
    private EmrServerlessSparkRunner runner;
    private IamService iam;
    private IamRole role;
    private EmrServerlessJobService service;

    @BeforeEach
    void setUp() {
        store = AccountAwareStorageBackend.inMemory(ACCOUNT);
        runner = mock(EmrServerlessSparkRunner.class);
        iam = mock(IamService.class);
        role = new IamRole("role-id", "worker", "/", ROLE, TRUST);
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.of(role));
        service = makeService(store);
    }

    @AfterEach
    void close() {
        service.stopManagedContainers();
    }

    @Test
    void queuedCancellationIsIdempotentAndNeverStartsAWorker() throws Exception {
        String id = start("queued");
        assertEquals(id, start("queued"));
        service.cancel(ACCOUNT, REGION, APP, id);
        service.cancel(ACCOUNT, REGION, APP, id);
        service.dispatch();
        assertEquals("CANCELLED", get(id).path("state").asText());
        assertFalse(get(id).has("startedAt"));
        verify(runner, never()).run(any(), any(), any(), any());
        assertEquals(id, service.attempts(ACCOUNT, REGION, APP, id, 10, null).items().getFirst().path("id").asText());
        AwsException changed = assertThrows(AwsException.class, () -> service.start(application(REGION),
                request("queued").put("name", "changed"), ACCOUNT, REGION, null));
        assertEquals("ValidationException", changed.getErrorCode());
    }

    @Test
    void runningCancellationWaitsForWorkerExitAndDoesNotAdmitASecondWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runner.run(any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<String> created = call.getArgument(2);
            created.accept("container-id");
            ((Runnable) call.getArgument(3)).run();
            entered.countDown();
            for (int i = 0; i < 20; i++) {
                try {
                    if (release.await(100, TimeUnit.MILLISECONDS)) {
                        return new EmrServerlessSparkRunner.Result(0, "Worker exited");
                    }
                } catch (InterruptedException ignored) {
                    // The test holds cleanup open after the worker receives cancellation.
                }
            }
            throw new IllegalStateException("Test worker release timed out");
        });
        String first = start("running");
        String second = start("queued-behind-running");
        service.dispatch();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            service.dispatch();
            verify(runner, times(1)).run(any(), any(), any(), any());
            assertThrows(AwsException.class, () -> service.requireIdle(ACCOUNT, REGION, APP));
            service.cancel(ACCOUNT, REGION, APP, second);
            service.cancel(ACCOUNT, REGION, APP, first);
            assertEquals("CANCELLING", get(first).path("state").asText());
            assertEquals("CANCELLED", get(second).path("state").asText());
        } finally {
            release.countDown();
        }
        awaitState(first, "CANCELLED");
        service.requireIdle(ACCOUNT, REGION, APP);
    }

    @Test
    void containerExitCodeDeterminesSuccessOrFailure() throws Exception {
        when(runner.run(any(), any(), any(), any())).thenReturn(new EmrServerlessSparkRunner.Result(7, "exit 7"));
        String failed = start("failed");
        service.dispatch();
        awaitState(failed, "FAILED");
        assertEquals("exit 7", get(failed).path("stateDetails").asText());
        when(runner.run(any(), any(), any(), any())).thenReturn(new EmrServerlessSparkRunner.Result(0, "exit 0"));
        String succeeded = start("succeeded");
        dispatchUntilTerminal(succeeded);
        assertEquals("SUCCESS", get(succeeded).path("state").asText());
        assertThrows(AwsException.class, () -> service.cancel(ACCOUNT, REGION, APP, succeeded));
    }

    @Test
    void watchdogInterruptsAWorkerAndMarksTimeoutFailed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(runner.run(any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                assertFalse(new CountDownLatch(1).await(2, TimeUnit.SECONDS));
            } catch (InterruptedException ignored) {
                // The watchdog interrupts the blocked worker.
            }
            return new EmrServerlessSparkRunner.Result(-1, "interrupted");
        });
        String id = start("timeout");
        service.dispatch();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        stored(id).put("_deadline", 0);
        service.dispatch();
        awaitState(id, "FAILED");
        assertTrue(get(id).path("stateDetails").asText().contains("deadline"));
    }

    @Test
    void queueCapacityAndQueueDeadlineAreBounded() throws Exception {
        for (int i = 0; i < EmrServerlessJobService.QUEUE_LIMIT; i++) {
            start("capacity-" + i);
        }
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> start("over-capacity")).getErrorCode());
        store.scanForAccount(ACCOUNT, key -> true).forEach(job ->
                job.put("_submittedAt", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(6)));
        service.dispatch();
        assertTrue(store.scanAllAccounts().stream().allMatch(job -> "FAILED".equals(job.path("state").asText())));
        verify(runner, never()).run(any(), any(), any(), any());
    }

    @Test
    void filtersPaginationAndIsolationUseStoredScope() throws Exception {
        String one = start("one");
        start("two");
        PaginatedResult<ObjectNode> first = service.list(ACCOUNT, REGION, APP, 1, null, List.of(), null, null, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        PaginatedResult<ObjectNode> second = service.list(ACCOUNT, REGION, APP, 1, first.nextToken(), List.of(), null, null, "BATCH");
        assertNotEquals(first.items().getFirst().path("id"), second.items().getFirst().path("id"));
        assertFalse(first.items().getFirst().has("_request"));
        assertTrue(get(one).path("createdAt").asDouble() < System.currentTimeMillis());
        assertTrue(service.list(ACCOUNT, "us-west-2", APP, 10, null, List.of(), null, null, null).items().isEmpty());
        assertTrue(service.list("222222222222", REGION, APP, 10, null, List.of(), null, null, null).items().isEmpty());
        assertThrows(AwsException.class, () -> service.cancel(ACCOUNT, "us-west-2", APP, one));
        assertThrows(AwsException.class, () -> service.get("222222222222", REGION, APP, one, null));
        String otherRegion = service.start(application("us-west-2"), request("one"), ACCOUNT, "us-west-2", null)
                .path("jobRunId").asText();
        assertNotEquals(one, otherRegion);
        service.cancel(ACCOUNT, REGION, APP, one);
        assertEquals(1, service.list(ACCOUNT, REGION, APP, 10, null, List.of("CANCELLED"), null, null, null).items().size());
        assertTrue(service.list(ACCOUNT, REGION, APP, 10, null, List.of(), System.currentTimeMillis() / 1000.0 + 1,
                null, null).items().isEmpty());
    }

    @Test
    void actualRoleTrustRejectsMissingForeignWrongPrincipalAndExplicitDeny() throws Exception {
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> start("missing"));
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.of(role));
        assertThrows(AwsException.class, () -> service.start(application(REGION),
                request("foreign").put("executionRoleArn", ROLE.replace(ACCOUNT, "222222222222")), ACCOUNT, REGION, null));
        role.setAssumeRolePolicyDocument(TRUST.replace("emr-serverless.amazonaws.com", "lambda.amazonaws.com"));
        assertThrows(AwsException.class, () -> start("principal"));
        role.setAssumeRolePolicyDocument(TRUST.replace("Allow", "Deny"));
        assertThrows(AwsException.class, () -> start("deny"));
        role.setAssumeRolePolicyDocument("""
                {"Statement":{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
                 "Action":"sts:AssumeRole","Condition":{"StringEquals":{"aws:SourceAccount":"222222222222"}}}}
                """);
        assertThrows(AwsException.class, () -> start("condition"));
        assertTrue(store.scanAllAccounts().isEmpty());
        role.setAssumeRolePolicyDocument(TRUST);
        assertNotNull(start("valid"));
    }

    @Test
    void unsupportedVpcPlacementIsNotSilentlyRunOnTheDefaultNetwork() throws Exception {
        Application app = application(REGION);
        NetworkConfiguration network = new NetworkConfiguration();
        network.setSubnetIds(List.of("subnet-required"));
        app.setNetworkConfiguration(network);
        assertEquals("UnsupportedOperationException", assertThrows(AwsException.class,
                () -> service.start(app, request("vpc"), ACCOUNT, REGION, null)).getErrorCode());
        assertTrue(store.scanAllAccounts().isEmpty());
        verify(runner, never()).run(any(), any(), any(), any());
    }

    @Test
    void removingTheRoleWhileQueuedPreventsWorkerCreation() throws Exception {
        String id = start("removed-role");
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.empty());
        service.dispatch();
        awaitState(id, "FAILED");
        assertTrue(get(id).path("stateDetails").asText().contains("role does not exist"));
        verify(runner, never()).run(any(), any(), any(), any());
        verify(runner, never()).cleanup(any());
    }

    @Test
    void persistedInterruptedWorkerIsCleanedWithoutReplayingItsJob(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jobs.json");
        PersistentStorage<String, ObjectNode> disk = new PersistentStorage<String, ObjectNode>(file, new TypeReference<Map<String, ObjectNode>>() {});
        store = new AccountAwareStorageBackend<>(disk, null, ACCOUNT);
        service = makeService(store);
        String id = start("persisted");
        ObjectNode job = stored(id);
        job.put("state", "RUNNING").put("_containerId", "old-container");
        store.putForAccount(ACCOUNT, REGION + "/" + APP + "/" + id, job);
        PersistentStorage<String, ObjectNode> reopened = new PersistentStorage<String, ObjectNode>(file, new TypeReference<Map<String, ObjectNode>>() {});
        reopened.load();
        store = new AccountAwareStorageBackend<>(reopened, null, ACCOUNT);
        service = makeService(store);
        assertEquals(id, start("persisted"));
        service.dispatch();
        assertEquals("FAILED", get(id).path("state").asText());
        verify(runner).cleanup(any());
        verify(runner, never()).run(any(), any(), any(), any());
    }

    @Test
    void failedCleanupNeverClaimsCancellationAndRetriesAtMostEightTimes() throws Exception {
        String id = start("cleanup");
        stored(id).put("state", "RUNNING");
        doThrow(new IllegalStateException("daemon unavailable")).when(runner).cleanup(any());
        for (int i = 0; i < 12; i++) {
            stored(id).put("_cleanupAfter", 0);
            service.dispatch();
        }
        assertEquals("CANCELLING", get(id).path("state").asText());
        assertFalse(EmrServerlessJobService.terminal(get(id)));
        verify(runner, times(8)).cleanup(any());
        verify(runner, never()).run(any(), any(), any(), any());
        doNothing().when(runner).cleanup(any());
    }

    private EmrServerlessJobService makeService(AccountAwareStorageBackend<ObjectNode> storage) {
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(anyString(), anyString(), any());
        return new EmrServerlessJobService(factory, runner, mapper, iam, new IamPolicyEvaluator(mapper),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), 60);
    }

    private Application application(String region) {
        Application app = new Application();
        app.setApplicationId(APP);
        app.setArn("arn:aws:emr-serverless:" + region + ":" + ACCOUNT + ":/applications/" + APP);
        app.setType("Spark");
        app.setReleaseLabel("emr-7.5.0");
        return app;
    }

    private ObjectNode request(String token) throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"clientToken":"%s","executionRoleArn":"%s",
                 "jobDriver":{"sparkSubmit":{"entryPoint":"local:///usr/lib/spark/examples/src/main/python/pi.py"}}}
                """.formatted(token, ROLE));
    }

    private String start(String token) throws Exception {
        return service.start(application(REGION), request(token), ACCOUNT, REGION, null).path("jobRunId").asText();
    }

    private ObjectNode get(String id) {
        return service.get(ACCOUNT, REGION, APP, id, null);
    }

    private ObjectNode stored(String id) {
        return store.getForAccount(ACCOUNT, REGION + "/" + APP + "/" + id).orElseThrow();
    }

    private void awaitState(String id, String state) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (state.equals(get(id).path("state").asText())) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Expected " + state + ", observed " + get(id));
    }

    private void dispatchUntilTerminal(String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            service.dispatch();
            if (EmrServerlessJobService.terminal(get(id))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Job did not terminate: " + get(id));
    }
}
