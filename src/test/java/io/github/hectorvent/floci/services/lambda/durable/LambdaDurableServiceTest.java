package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the durable execution state machine: checkpointed steps,
 * suspend/resume around a durable wait, idempotent reattach by execution
 * name, token validation, and bounded invocation retries.
 */
class LambdaDurableServiceTest {

    private static final String REGION = "us-east-1";
    private static final String FUNCTION = "durable-fn";

    private ObjectMapper mapper;
    private DurableExecutionStore store;
    private ManualScheduler scheduler;
    private ScriptedInvoker invoker;
    private LambdaDurableService service;
    private boolean functionDeleted;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = new DurableExecutionStore(new InMemoryStorage<>());
        scheduler = new ManualScheduler();
        invoker = new ScriptedInvoker(mapper);
        functionDeleted = false;
        LambdaDurableService.FunctionResolver resolver = (region, name, qualifier) -> {
            if (functionDeleted || (!FUNCTION.equals(name) && !name.startsWith("arn:"))) {
                throw new AwsException("ResourceNotFoundException", "Function not found: " + name, 404);
            }
            return new LambdaDurableService.ResolvedFunction(FUNCTION, qualifier,
                    "arn:aws:lambda:" + REGION + ":000000000000:function:" + FUNCTION, "000000000000");
        };
        service = new LambdaDurableService(store, mapper, resolver, invoker, scheduler, new DirectExecutor());
    }

    @Test
    void runsCheckpointedStepsAroundDurableSleepToCompletion() {
        // Invocation #1: the SDK checkpoints step "reserve", starts a 5s wait,
        // then suspends by returning a PENDING envelope.
        invoker.enqueue(event -> {
            String arn = event.get("DurableExecutionArn").asText();
            String token = event.get("CheckpointToken").asText();

            JsonNode operations = event.get("InitialExecutionState").get("Operations");
            assertEquals(1, operations.size(), "first invocation sees only the EXECUTION operation");
            assertEquals("EXECUTION", operations.get(0).get("Type").asText());
            assertEquals("{\"orderId\":\"o1\"}",
                    operations.get(0).get("ExecutionDetails").get("InputPayload").asText());

            service.checkpoint(arn, checkpointBody(token,
                    update("step-1", "STEP", "START", u -> u.put("Name", "reserve").put("SubType", "Step"))));
            service.checkpoint(arn, checkpointBody(token,
                    update("step-1", "STEP", "SUCCEED",
                            u -> u.put("Name", "reserve").put("SubType", "Step")
                                    .put("Payload", "{\"reserved\":true}"))));
            ObjectNode waitResponse = (ObjectNode) service.checkpoint(arn, checkpointBody(token,
                    update("wait-1", "WAIT", "START", u -> {
                        u.put("Name", "cooldown").put("SubType", "Wait");
                        u.putObject("WaitOptions").put("WaitSeconds", 5);
                    })));

            JsonNode waitOp = findOperation(waitResponse.get("NewExecutionState").get("Operations"), "wait-1");
            assertEquals("STARTED", waitOp.get("Status").asText());
            assertTrue(waitOp.get("WaitDetails").get("ScheduledEndTimestamp").asDouble() > 0);

            return invocationEnvelope("PENDING", null);
        });

        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{\"orderId\":\"o1\"}".getBytes(StandardCharsets.UTF_8));
        String arn = execution.getExecutionArn();

        assertEquals(1, invoker.invocationCount());
        assertEquals(DurableExecution.STATUS_RUNNING, store.get(arn).orElseThrow().getStatus());
        assertEquals(1, scheduler.pending(), "one wait timer armed");
        assertEquals(5000, scheduler.lastDelayMs());

        // Invocation #2 (the resume): the wait is SUCCEEDED, step "reserve"
        // replays from its checkpointed result, step "price" runs, and the
        // handler completes.
        invoker.enqueue(event -> {
            String arn2 = event.get("DurableExecutionArn").asText();
            String token = event.get("CheckpointToken").asText();

            List<String> updatedIds = new ArrayList<>();
            event.get("UpdatedOperationIds").forEach(n -> updatedIds.add(n.asText()));
            assertTrue(updatedIds.contains("wait-1"), "resume carries the completed wait id");

            JsonNode operations = event.get("InitialExecutionState").get("Operations");
            assertEquals("SUCCEEDED", findOperation(operations, "wait-1").get("Status").asText());
            assertEquals("{\"reserved\":true}",
                    findOperation(operations, "step-1").get("StepDetails").get("Result").asText());

            service.checkpoint(arn2, checkpointBody(token,
                    update("step-2", "STEP", "START", u -> u.put("Name", "price").put("SubType", "Step"))));
            service.checkpoint(arn2, checkpointBody(token,
                    update("step-2", "STEP", "SUCCEED",
                            u -> u.put("Name", "price").put("SubType", "Step").put("Payload", "42"))));

            return invocationEnvelope("SUCCEEDED", "{\"orderId\":\"o1\",\"reserved\":true,\"total\":42}");
        });

        scheduler.fireNext();

        assertEquals(2, invoker.invocationCount());
        DurableExecution finished = store.get(arn).orElseThrow();
        assertEquals(DurableExecution.STATUS_SUCCEEDED, finished.getStatus());
        assertEquals("{\"orderId\":\"o1\",\"reserved\":true,\"total\":42}", finished.getResult());
        assertNotNull(finished.getEndTimestampMillis());

        JsonNode get = service.getExecution(arn);
        assertEquals("SUCCEEDED", get.get("Status").asText());
        assertEquals("{\"orderId\":\"o1\",\"reserved\":true,\"total\":42}", get.get("Result").asText());
    }

    @Test
    void reattachesIdempotentlyToExistingExecutionByName() {
        invoker.enqueue(event -> invocationEnvelope("PENDING", null));
        DurableExecution first = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{\"orderId\":\"o1\"}".getBytes(StandardCharsets.UTF_8));

        DurableExecution second = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{\"orderId\":\"o1\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(first.getExecutionArn(), second.getExecutionArn());
        assertEquals(1, invoker.invocationCount(), "reattach must not dispatch a new invocation");
    }

    @Test
    void rejectsSameNameWithDifferentPayload() {
        invoker.enqueue(event -> invocationEnvelope("PENDING", null));
        service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{\"orderId\":\"o1\"}".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () ->
                service.startExecution(REGION, FUNCTION, null, "flow-1",
                        "{\"orderId\":\"DIFFERENT\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("DurableExecutionAlreadyStartedException", e.getErrorCode());
    }

    @Test
    void rejectsCheckpointWithStaleToken() {
        invoker.enqueue(event -> invocationEnvelope("PENDING", null));
        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () ->
                service.checkpoint(execution.getExecutionArn(), checkpointBody("stale-token",
                        update("step-1", "STEP", "START", u -> u.put("Name", "reserve")))));
        assertEquals("InvalidParameterValueException", e.getErrorCode());
    }

    @Test
    void failsExecutionAfterBoundedInvocationRetries() {
        invoker.enqueue(event -> functionError("boom"));
        invoker.enqueue(event -> functionError("boom"));
        invoker.enqueue(event -> functionError("boom"));

        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));
        String arn = execution.getExecutionArn();

        // Two retry timers fire (attempts 2 and 3); the third failure is terminal.
        scheduler.fireNext();
        scheduler.fireNext();

        assertEquals(3, invoker.invocationCount());
        DurableExecution failed = store.get(arn).orElseThrow();
        assertEquals(DurableExecution.STATUS_FAILED, failed.getStatus());
        assertEquals("Lambda.InvocationError", failed.getError().get("ErrorType"));
        assertEquals(0, scheduler.pending());
    }

    @Test
    void failedEnvelopeFailsTheExecution() {
        invoker.enqueue(event -> {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("Status", "FAILED");
            envelope.putObject("Error")
                    .put("ErrorType", "OrderRejected")
                    .put("ErrorMessage", "no inventory");
            return toResult(envelope);
        });

        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));

        DurableExecution failed = store.get(execution.getExecutionArn()).orElseThrow();
        assertEquals(DurableExecution.STATUS_FAILED, failed.getStatus());
        assertEquals("OrderRejected", failed.getError().get("ErrorType"));
    }

    @Test
    void stoppedExecutionIgnoresLateWaitTimer() {
        invoker.enqueue(event -> {
            String arn = event.get("DurableExecutionArn").asText();
            String token = event.get("CheckpointToken").asText();
            service.checkpoint(arn, checkpointBody(token,
                    update("wait-1", "WAIT", "START", u -> {
                        u.put("Name", "cooldown");
                        u.putObject("WaitOptions").put("WaitSeconds", 5);
                    })));
            return invocationEnvelope("PENDING", null);
        });

        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));
        String arn = execution.getExecutionArn();

        service.stopExecution(arn, null);
        assertEquals(DurableExecution.STATUS_STOPPED, store.get(arn).orElseThrow().getStatus());

        scheduler.fireNext();

        assertEquals(1, invoker.invocationCount(), "stopped execution must not resume");
        DurableExecution stopped = store.get(arn).orElseThrow();
        assertEquals(DurableExecution.STATUS_STOPPED, stopped.getStatus());
        DurableOperation wait = stopped.findOperation("wait-1");
        assertEquals(DurableOperation.STATUS_STARTED, wait.getStatus(), "wait op untouched after stop");
    }

    @Test
    void listFiltersByExecutionNameAndStatus() {
        invoker.enqueue(event -> invocationEnvelope("SUCCEEDED", "\"a\""));
        invoker.enqueue(event -> invocationEnvelope("PENDING", null));
        service.startExecution(REGION, FUNCTION, null, "done-flow", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        service.startExecution(REGION, FUNCTION, null, "running-flow", "{\"b\":2}".getBytes(StandardCharsets.UTF_8));

        JsonNode byName = service.listExecutions(REGION, FUNCTION, null, "done-flow", null, false);
        assertEquals(1, byName.get("DurableExecutions").size());
        assertEquals("done-flow", byName.get("DurableExecutions").get(0).get("DurableExecutionName").asText());
        assertEquals("SUCCEEDED", byName.get("DurableExecutions").get(0).get("Status").asText());

        JsonNode running = service.listExecutions(REGION, FUNCTION, null, null,
                List.of("RUNNING"), false);
        assertEquals(1, running.get("DurableExecutions").size());
        assertEquals("running-flow", running.get("DurableExecutions").get(0).get("DurableExecutionName").asText());

        AwsException e = assertThrows(AwsException.class, () ->
                service.listExecutions(REGION, "missing-fn", null, null, null, false));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void stepRetryTransitionsToReadyAndResumes() {
        invoker.enqueue(event -> {
            String arn = event.get("DurableExecutionArn").asText();
            String token = event.get("CheckpointToken").asText();
            service.checkpoint(arn, checkpointBody(token,
                    update("step-1", "STEP", "START", u -> u.put("Name", "flaky"))));
            service.checkpoint(arn, checkpointBody(token,
                    update("step-1", "STEP", "RETRY", u -> {
                        u.put("Name", "flaky");
                        u.putObject("Error").put("ErrorType", "Transient").put("ErrorMessage", "try again");
                        u.putObject("StepOptions").put("NextAttemptDelaySeconds", 3);
                    })));
            return invocationEnvelope("PENDING", null);
        });

        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));
        String arn = execution.getExecutionArn();

        assertEquals(DurableOperation.STATUS_PENDING,
                store.get(arn).orElseThrow().findOperation("step-1").getStatus());
        assertEquals(3000, scheduler.lastDelayMs());

        invoker.enqueue(event -> {
            List<String> updatedIds = new ArrayList<>();
            event.get("UpdatedOperationIds").forEach(n -> updatedIds.add(n.asText()));
            assertTrue(updatedIds.contains("step-1"));
            assertEquals("READY", findOperation(
                    event.get("InitialExecutionState").get("Operations"), "step-1").get("Status").asText());
            return invocationEnvelope("SUCCEEDED", "\"done\"");
        });

        scheduler.fireNext();

        assertEquals(DurableExecution.STATUS_SUCCEEDED, store.get(arn).orElseThrow().getStatus());
    }

    @Test
    void purgesExecutionsOnlyOnceFunctionIsDeleted() {
        invoker.enqueue(event -> invocationEnvelope("SUCCEEDED", "\"done\""));
        DurableExecution execution = service.startExecution(REGION, FUNCTION, null, "flow-1",
                "{}".getBytes(StandardCharsets.UTF_8));
        String arn = execution.getExecutionArn();

        service.purgeExecutionsIfFunctionDeleted(REGION, FUNCTION);
        assertTrue(store.get(arn).isPresent(), "executions survive while the function exists");

        functionDeleted = true;
        service.purgeExecutionsIfFunctionDeleted(REGION, FUNCTION);
        assertTrue(store.get(arn).isEmpty(), "executions are dropped with the function");
        AwsException e = assertThrows(AwsException.class, () -> service.getExecution(arn));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    // ──────────────────────────── helpers ────────────────────────────

    private ObjectNode checkpointBody(String token, ObjectNode... updates) {
        ObjectNode body = mapper.createObjectNode();
        body.put("CheckpointToken", token);
        var array = body.putArray("Updates");
        for (ObjectNode update : updates) {
            array.add(update);
        }
        return body;
    }

    private ObjectNode update(String id, String type, String action,
                              java.util.function.Consumer<ObjectNode> customize) {
        ObjectNode update = mapper.createObjectNode();
        update.put("Id", id);
        update.put("Type", type);
        update.put("Action", action);
        customize.accept(update);
        return update;
    }

    private JsonNode findOperation(JsonNode operations, String id) {
        for (JsonNode op : operations) {
            if (id.equals(op.get("Id").asText())) {
                return op;
            }
        }
        assertNull(id, "operation " + id + " not found");
        return null;
    }

    private InvokeResult invocationEnvelope(String status, String result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("Status", status);
        if (result != null) {
            envelope.put("Result", result);
        }
        return toResult(envelope);
    }

    private InvokeResult toResult(ObjectNode envelope) {
        return new InvokeResult(200, null,
                envelope.toString().getBytes(StandardCharsets.UTF_8), null, "req-" + System.nanoTime());
    }

    private InvokeResult functionError(String message) {
        return new InvokeResult(200, "Unhandled",
                ("{\"errorMessage\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8),
                null, "req-" + System.nanoTime());
    }

    /** Runs invocations on the caller thread so tests are deterministic. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** Captures scheduled timers so tests fire them manually. */
    private static final class ManualScheduler implements LambdaDurableService.TimerScheduler {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private long lastDelayMs = -1;

        @Override
        public void schedule(long delayMs, Runnable task) {
            lastDelayMs = delayMs;
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        long lastDelayMs() {
            return lastDelayMs;
        }

        void fireNext() {
            Runnable task = tasks.poll();
            assertNotNull(task, "no timer scheduled");
            task.run();
        }
    }

    /** Scripted invoker: each enqueued handler serves one invocation, in order. */
    private static final class ScriptedInvoker implements LambdaDurableService.FunctionInvoker {
        private final ObjectMapper mapper;
        private final Deque<Function<JsonNode, InvokeResult>> handlers = new ArrayDeque<>();
        private int invocations;

        ScriptedInvoker(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void enqueue(Function<JsonNode, InvokeResult> handler) {
            handlers.add(handler);
        }

        int invocationCount() {
            return invocations;
        }

        @Override
        public InvokeResult invoke(String region, String functionRef, byte[] payload) {
            invocations++;
            Function<JsonNode, InvokeResult> handler = handlers.poll();
            if (handler == null) {
                throw new IllegalStateException("unexpected invocation #" + invocations);
            }
            try {
                return handler.apply(mapper.readTree(payload));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
