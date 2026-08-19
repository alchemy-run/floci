package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AWS Lambda Durable Executions (checkpointed orchestrations with
 * suspend/resume), emulating the 2025-12-01 durable-execution API family.
 *
 * <p>Life of an execution:
 * <ol>
 *   <li><b>Start</b> — an {@code Invoke} carrying {@code X-Amz-Durable-Execution-Name}
 *       registers an execution (idempotent by name: the same name + payload
 *       reattaches, a different payload is rejected) whose operation log opens
 *       with an EXECUTION operation carrying the customer payload. The
 *       function is invoked asynchronously with the durable invocation
 *       envelope ({@code DurableExecutionArn}, {@code CheckpointToken},
 *       {@code InitialExecutionState}) and the Invoke response is 202 +
 *       {@code X-Amz-Durable-Execution-Arn}.</li>
 *   <li><b>Checkpoint</b> — inside the invocation, the Durable Execution SDK
 *       records operation transitions via {@code CheckpointDurableExecution}
 *       (STEP START/SUCCEED/FAIL/RETRY, WAIT START, CALLBACK START, ...).
 *       Floci applies each update to the log and returns the full refreshed
 *       state, which the SDK merges into its replay map.</li>
 *   <li><b>Suspend</b> — when only timed operations remain (a WAIT, a step
 *       retry backoff, a callback), the SDK returns a {@code PENDING}
 *       invocation envelope and the invocation ends. Floci has armed a timer
 *       for the earliest such operation.</li>
 *   <li><b>Resume</b> — when the timer fires (or a callback completes), the
 *       operation is transitioned (WAIT → SUCCEEDED, retry → READY, callback
 *       → SUCCEEDED/FAILED/TIMED_OUT) and the function is re-invoked with the
 *       full operation log plus {@code UpdatedOperationIds}, so the SDK
 *       replays memoized results and continues.</li>
 *   <li><b>Finish</b> — a {@code SUCCEEDED}/{@code FAILED} invocation envelope
 *       (or an EXECUTION SUCCEED/FAIL checkpoint for oversized results)
 *       finalizes the execution's status/result/error.</li>
 * </ol>
 *
 * <p>Invocation-level failures (function error payloads, container crashes)
 * are retried a bounded number of times before the execution is failed.
 */
@ApplicationScoped
public class LambdaDurableService {

    private static final Logger LOG = Logger.getLogger(LambdaDurableService.class);

    /** Invocation attempts per resume point before the execution is failed. */
    private static final int MAX_INVOCATION_FAILURES = 3;
    private static final long INVOCATION_RETRY_DELAY_MS = 1000;

    /** Resolves an Invoke-style function reference to a concrete function. */
    public interface FunctionResolver {
        ResolvedFunction resolve(String region, String functionNameParam, String qualifierParam);
    }

    public record ResolvedFunction(String functionName, String qualifier, String functionArn, String accountId) {}

    /** Executes one synchronous invocation of the durable function. */
    public interface FunctionInvoker {
        InvokeResult invoke(String region, String functionRef, byte[] payload);
    }

    /** Schedules a one-shot timer; production is Vert.x, tests fire manually. */
    public interface TimerScheduler {
        void schedule(long delayMs, Runnable task);
    }

    private final DurableExecutionStore store;
    private final ObjectMapper objectMapper;
    private final FunctionResolver resolver;
    private final FunctionInvoker invoker;
    private final TimerScheduler scheduler;
    private final ExecutorService workers;

    /** Per-execution mutation locks (never removed; executions are few). */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Serializes idempotent-start checks. */
    private final Object startLock = new Object();
    /** Executions with an invocation currently running. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Operation ids updated server-side since the last invocation, per execution. */
    private final ConcurrentHashMap<String, Set<String>> pendingUpdatedIds = new ConcurrentHashMap<>();
    /** CallbackId → execution ARN index for the callback data plane. */
    private final ConcurrentHashMap<String, String> callbackIndex = new ConcurrentHashMap<>();

    @Inject
    public LambdaDurableService(DurableExecutionStore store, ObjectMapper objectMapper,
                                LambdaService lambdaService, Vertx vertx) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.resolver = lambdaServiceResolver(lambdaService);
        this.invoker = (region, functionRef, payload) ->
                lambdaService.invoke(region, functionRef, payload, InvocationType.RequestResponse);
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "durable-execution");
            t.setDaemon(true);
            return t;
        });
        // Vert.x timers are non-blocking; the fired task runs on a worker
        // thread because it invokes Lambda synchronously.
        this.scheduler = (delayMs, task) -> vertx.setTimer(Math.max(1, delayMs), id -> workers.submit(task));
    }

    LambdaDurableService(DurableExecutionStore store, ObjectMapper objectMapper,
                         FunctionResolver resolver, FunctionInvoker invoker,
                         TimerScheduler scheduler, ExecutorService workers) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.resolver = resolver;
        this.invoker = invoker;
        this.workers = workers;
        this.scheduler = scheduler;
    }

    private static FunctionResolver lambdaServiceResolver(LambdaService lambdaService) {
        return (region, functionNameParam, qualifierParam) -> {
            LambdaArnUtils.ResolvedFunctionRef ref =
                    LambdaArnUtils.resolveWithQualifier(functionNameParam, qualifierParam);
            LambdaFunction fn = lambdaService.getFunction(region, ref.name(), ref.qualifier());
            String accountId = fn.getAccountId();
            if (accountId == null && fn.getFunctionArn() != null) {
                String[] parts = fn.getFunctionArn().split(":");
                if (parts.length > 4) {
                    accountId = parts[4];
                }
            }
            return new ResolvedFunction(ref.name(), ref.qualifier(), fn.getFunctionArn(),
                    accountId != null ? accountId : "000000000000");
        };
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    // ──────────────────────────── Start (durable Invoke) ────────────────────────────

    /**
     * Starts (or idempotently reattaches to) a named durable execution and
     * dispatches the first invocation asynchronously.
     */
    public DurableExecution startExecution(String region, String functionNameParam, String qualifierParam,
                                           String executionName, byte[] payload) {
        ResolvedFunction fn = resolver.resolve(region, functionNameParam, qualifierParam);
        String payloadText = payload == null || payload.length == 0
                ? "{}"
                : new String(payload, StandardCharsets.UTF_8);

        DurableExecution execution;
        synchronized (startLock) {
            Optional<DurableExecution> existing = store.getByName(region, fn.functionName(), executionName);
            if (existing.isPresent()) {
                if (!payloadText.equals(existing.get().getInputPayload())) {
                    throw new AwsException("DurableExecutionAlreadyStartedException",
                            "Durable execution '" + executionName + "' already started with a different input",
                            409);
                }
                return existing.get();
            }

            long now = System.currentTimeMillis();
            execution = new DurableExecution();
            execution.setExecutionArn("arn:aws:lambda:" + region + ":" + fn.accountId()
                    + ":durable-execution:" + fn.functionName() + ":" + executionName + ":"
                    + UUID.randomUUID().toString().substring(0, 8));
            execution.setExecutionName(executionName);
            execution.setRegion(region);
            execution.setAccountId(fn.accountId());
            execution.setFunctionName(fn.functionName());
            execution.setQualifier(fn.qualifier());
            execution.setFunctionArn(fn.functionArn());
            execution.setStatus(DurableExecution.STATUS_RUNNING);
            execution.setInputPayload(payloadText);
            execution.setStartTimestampMillis(now);

            DurableOperation root = new DurableOperation();
            root.setId(UUID.randomUUID().toString());
            root.setType(DurableOperation.TYPE_EXECUTION);
            root.setStatus(DurableOperation.STATUS_STARTED);
            root.setStartTimestampMillis(now);
            root.setInputPayload(payloadText);
            execution.getOperations().add(root);

            store.save(execution);
        }

        dispatch(execution.getExecutionArn());
        return execution;
    }

    // ──────────────────────────── Invocation loop ────────────────────────────

    private Object lock(String executionArn) {
        return locks.computeIfAbsent(executionArn, k -> new Object());
    }

    /** Launches an invocation unless one is already in flight or the execution is terminal. */
    private void dispatch(String executionArn) {
        byte[] event;
        synchronized (lock(executionArn)) {
            DurableExecution execution = store.get(executionArn).orElse(null);
            if (execution == null || !execution.isRunning()) {
                pendingUpdatedIds.remove(executionArn);
                return;
            }
            if (!inFlight.add(executionArn)) {
                return;
            }
            Set<String> updated = pendingUpdatedIds.remove(executionArn);
            String token = UUID.randomUUID().toString();
            execution.setCheckpointToken(token);
            store.save(execution);
            event = buildInvocationEvent(execution, token, updated);
        }

        DurableExecution snapshot = store.get(executionArn).orElseThrow();
        String functionRef = snapshot.getQualifier() != null
                ? snapshot.getFunctionName() + ":" + snapshot.getQualifier()
                : snapshot.getFunctionName();
        String region = snapshot.getRegion();

        workers.submit(() -> {
            InvokeResult result = null;
            Exception failure = null;
            try {
                result = invoker.invoke(region, functionRef, event);
            } catch (Exception e) {
                failure = e;
            }
            handleInvocationResult(executionArn, result, failure);
        });
    }

    private byte[] buildInvocationEvent(DurableExecution execution, String token, Set<String> updatedIds) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("DurableExecutionArn", execution.getExecutionArn());
        event.put("CheckpointToken", token);
        ArrayNode updated = event.putArray("UpdatedOperationIds");
        if (updatedIds != null) {
            updatedIds.forEach(updated::add);
        }
        ObjectNode state = event.putObject("InitialExecutionState");
        ArrayNode ops = state.putArray("Operations");
        for (DurableOperation op : execution.getOperations()) {
            ops.add(operationToWire(op));
        }
        return event.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void handleInvocationResult(String executionArn, InvokeResult result, Exception failure) {
        synchronized (lock(executionArn)) {
            inFlight.remove(executionArn);
            DurableExecution execution = store.get(executionArn).orElse(null);
            if (execution == null || !execution.isRunning()) {
                return;
            }

            JsonNode envelope = null;
            String problem = null;
            if (failure != null) {
                problem = failure.getMessage();
            } else if (result.getFunctionError() != null) {
                problem = "function error: " + payloadText(result);
            } else {
                try {
                    byte[] payload = result.getPayload();
                    envelope = payload != null && payload.length > 0
                            ? objectMapper.readTree(payload)
                            : null;
                } catch (Exception e) {
                    problem = "unparseable invocation envelope: " + e.getMessage();
                }
                if (problem == null && (envelope == null || !envelope.hasNonNull("Status"))) {
                    problem = "invocation did not return a durable execution envelope";
                }
            }

            if (problem != null) {
                execution.setInvocationFailures(execution.getInvocationFailures() + 1);
                if (execution.getInvocationFailures() < MAX_INVOCATION_FAILURES) {
                    LOG.warnv("Durable execution {0} invocation failed (attempt {1}): {2}",
                            executionArn, execution.getInvocationFailures(), problem);
                    store.save(execution);
                    scheduler.schedule(INVOCATION_RETRY_DELAY_MS, () -> dispatch(executionArn));
                } else {
                    LOG.warnv("Durable execution {0} failed after {1} invocation attempts: {2}",
                            executionArn, execution.getInvocationFailures(), problem);
                    failExecution(execution, errorObject("Lambda.InvocationError", problem));
                    store.save(execution);
                }
                return;
            }

            execution.setInvocationFailures(0);
            String status = envelope.get("Status").asText();
            switch (status) {
                case "SUCCEEDED" -> {
                    String resultPayload = envelope.hasNonNull("Result")
                            ? envelope.get("Result").asText()
                            : null;
                    // An empty Result means the (oversized) result was already
                    // checkpointed via an EXECUTION SUCCEED update.
                    if (resultPayload != null && !resultPayload.isEmpty() || execution.getResult() == null) {
                        execution.setResult(resultPayload);
                    }
                    execution.setStatus(DurableExecution.STATUS_SUCCEEDED);
                    execution.setEndTimestampMillis(System.currentTimeMillis());
                    store.save(execution);
                }
                case "FAILED" -> {
                    Map<String, Object> error = envelope.has("Error")
                            ? objectMapper.convertValue(envelope.get("Error"), Map.class)
                            : errorObject("DurableExecutionFailed", "execution failed");
                    failExecution(execution, error);
                    store.save(execution);
                }
                case "PENDING" -> {
                    store.save(execution);
                    if (pendingUpdatedIds.containsKey(executionArn)) {
                        // A timed operation completed while this invocation
                        // was finishing — resume immediately.
                        workers.submit(() -> dispatch(executionArn));
                    }
                }
                default -> {
                    LOG.warnv("Durable execution {0} returned unknown envelope status {1}", executionArn, status);
                    failExecution(execution, errorObject("Lambda.InvocationError",
                            "unknown envelope status " + status));
                    store.save(execution);
                }
            }
        }
    }

    private String payloadText(InvokeResult result) {
        byte[] payload = result.getPayload();
        return payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
    }

    private void failExecution(DurableExecution execution, Map<String, Object> error) {
        execution.setStatus(DurableExecution.STATUS_FAILED);
        execution.setError(error);
        execution.setEndTimestampMillis(System.currentTimeMillis());
    }

    private Map<String, Object> errorObject(String type, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("ErrorType", type);
        error.put("ErrorMessage", message);
        return error;
    }

    // ──────────────────────────── Checkpoint data plane ────────────────────────────

    /**
     * Applies a batch of operation updates and returns the refreshed
     * execution state (the SDK merges the returned operations into its
     * replay map, so the full log is always returned).
     */
    public ObjectNode checkpoint(String executionArn, JsonNode body) {
        synchronized (lock(executionArn)) {
            DurableExecution execution = requireExecution(executionArn);
            validateToken(execution, body.path("CheckpointToken").asText(null));

            JsonNode updates = body.path("Updates");
            if (updates.isArray()) {
                long now = System.currentTimeMillis();
                for (JsonNode update : updates) {
                    applyUpdate(execution, update, now);
                }
            }
            store.save(execution);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("CheckpointToken", execution.getCheckpointToken());
            ObjectNode state = response.putObject("NewExecutionState");
            ArrayNode ops = state.putArray("Operations");
            for (DurableOperation op : execution.getOperations()) {
                ops.add(operationToWire(op));
            }
            return response;
        }
    }

    private void applyUpdate(DurableExecution execution, JsonNode update, long now) {
        String id = update.path("Id").asText(null);
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "OperationUpdate.Id is required", 400);
        }
        String type = update.path("Type").asText(DurableOperation.TYPE_STEP);
        String action = update.path("Action").asText("START");

        DurableOperation op = execution.findOperation(id);
        if (op == null) {
            op = new DurableOperation();
            op.setId(id);
            op.setType(type);
            op.setStartTimestampMillis(now);
            execution.getOperations().add(op);
        }
        if (update.hasNonNull("ParentId")) {
            op.setParentId(update.get("ParentId").asText());
        }
        if (update.hasNonNull("Name")) {
            op.setName(update.get("Name").asText());
        }
        if (update.hasNonNull("SubType")) {
            op.setSubType(update.get("SubType").asText());
        }

        switch (action) {
            case "START" -> applyStart(execution, op, update, now);
            case "SUCCEED" -> {
                op.setStatus(DurableOperation.STATUS_SUCCEEDED);
                op.setEndTimestampMillis(now);
                if (update.hasNonNull("Payload")) {
                    op.setResult(update.get("Payload").asText());
                }
                if (DurableOperation.TYPE_EXECUTION.equals(op.getType()) && execution.isRunning()) {
                    execution.setStatus(DurableExecution.STATUS_SUCCEEDED);
                    execution.setResult(op.getResult());
                    execution.setEndTimestampMillis(now);
                }
            }
            case "FAIL" -> {
                op.setStatus(DurableOperation.STATUS_FAILED);
                op.setEndTimestampMillis(now);
                op.setError(errorFrom(update));
                if (DurableOperation.TYPE_EXECUTION.equals(op.getType()) && execution.isRunning()) {
                    failExecution(execution, op.getError());
                }
            }
            case "RETRY" -> {
                op.setStatus(DurableOperation.STATUS_PENDING);
                op.setError(errorFrom(update));
                int delaySeconds = Math.max(1, update.path("StepOptions").path("NextAttemptDelaySeconds").asInt(1));
                op.setNextAttemptTimestampMillis(now + delaySeconds * 1000L);
                armRetryTimer(execution.getExecutionArn(), op.getId(), delaySeconds * 1000L);
            }
            case "CANCEL" -> {
                op.setStatus(DurableOperation.STATUS_CANCELLED);
                op.setEndTimestampMillis(now);
            }
            default -> throw new AwsException("InvalidParameterValueException",
                    "Unsupported operation action: " + action, 400);
        }
    }

    private void applyStart(DurableExecution execution, DurableOperation op, JsonNode update, long now) {
        op.setStatus(DurableOperation.STATUS_STARTED);
        switch (op.getType()) {
            case DurableOperation.TYPE_STEP -> {
                op.setAttempt(op.getAttempt() == null ? 1 : op.getAttempt() + 1);
                op.setNextAttemptTimestampMillis(null);
            }
            case DurableOperation.TYPE_WAIT -> {
                long waitSeconds = Math.max(0, update.path("WaitOptions").path("WaitSeconds").asLong(0));
                op.setScheduledEndTimestampMillis(now + waitSeconds * 1000L);
                armWaitTimer(execution.getExecutionArn(), op.getId(), waitSeconds * 1000L);
            }
            case DurableOperation.TYPE_CALLBACK -> {
                String callbackId = UUID.randomUUID().toString();
                op.setCallbackId(callbackId);
                callbackIndex.put(callbackId, execution.getExecutionArn());
                long timeoutSeconds = update.path("CallbackOptions").path("TimeoutSeconds").asLong(0);
                if (timeoutSeconds > 0) {
                    armCallbackTimeout(execution.getExecutionArn(), op.getId(), timeoutSeconds * 1000L);
                }
            }
            case DurableOperation.TYPE_CHAINED_INVOKE -> {
                // Not emulated yet: fail the operation so the SDK surfaces a
                // typed error to user code instead of hanging the execution.
                op.setStatus(DurableOperation.STATUS_FAILED);
                op.setEndTimestampMillis(now);
                op.setError(errorObject("ChainedInvokeNotSupported",
                        "Floci does not emulate durable chained invokes yet"));
            }
            default -> {
                // EXECUTION restart or CONTEXT start: nothing beyond STARTED.
            }
        }
    }

    private Map<String, Object> errorFrom(JsonNode update) {
        if (update.hasNonNull("Error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = objectMapper.convertValue(update.get("Error"), Map.class);
            return error;
        }
        return errorObject("OperationFailed", "operation failed");
    }

    // ──────────────────────────── Timers / suspend-resume ────────────────────────────

    private void armWaitTimer(String executionArn, String opId, long delayMs) {
        scheduler.schedule(delayMs, () -> {
            synchronized (lock(executionArn)) {
                DurableExecution execution = store.get(executionArn).orElse(null);
                if (execution == null || !execution.isRunning()) {
                    return;
                }
                DurableOperation op = execution.findOperation(opId);
                if (op == null || !DurableOperation.STATUS_STARTED.equals(op.getStatus())) {
                    return;
                }
                op.setStatus(DurableOperation.STATUS_SUCCEEDED);
                op.setEndTimestampMillis(System.currentTimeMillis());
                store.save(execution);
            }
            resume(executionArn, opId);
        });
    }

    private void armRetryTimer(String executionArn, String opId, long delayMs) {
        scheduler.schedule(delayMs, () -> {
            synchronized (lock(executionArn)) {
                DurableExecution execution = store.get(executionArn).orElse(null);
                if (execution == null || !execution.isRunning()) {
                    return;
                }
                DurableOperation op = execution.findOperation(opId);
                if (op == null || !DurableOperation.STATUS_PENDING.equals(op.getStatus())) {
                    return;
                }
                op.setStatus(DurableOperation.STATUS_READY);
                store.save(execution);
            }
            resume(executionArn, opId);
        });
    }

    private void armCallbackTimeout(String executionArn, String opId, long delayMs) {
        scheduler.schedule(delayMs, () -> {
            synchronized (lock(executionArn)) {
                DurableExecution execution = store.get(executionArn).orElse(null);
                if (execution == null || !execution.isRunning()) {
                    return;
                }
                DurableOperation op = execution.findOperation(opId);
                if (op == null || !DurableOperation.STATUS_STARTED.equals(op.getStatus())) {
                    return;
                }
                op.setStatus(DurableOperation.STATUS_TIMED_OUT);
                op.setEndTimestampMillis(System.currentTimeMillis());
                op.setError(errorObject("CallbackTimedOut", "callback timed out"));
                store.save(execution);
            }
            resume(executionArn, opId);
        });
    }

    /** Queues an operation update for the next invocation and dispatches it if idle. */
    private void resume(String executionArn, String opId) {
        pendingUpdatedIds.computeIfAbsent(executionArn, k -> new LinkedHashSet<>()).add(opId);
        if (!inFlight.contains(executionArn)) {
            dispatch(executionArn);
        }
    }

    // ──────────────────────────── Callbacks ────────────────────────────

    public void callbackSucceed(String callbackId, byte[] result) {
        completeCallback(callbackId, op -> {
            op.setStatus(DurableOperation.STATUS_SUCCEEDED);
            op.setResult(result == null || result.length == 0
                    ? null
                    : new String(result, StandardCharsets.UTF_8));
        });
    }

    public void callbackFail(String callbackId, JsonNode error) {
        completeCallback(callbackId, op -> {
            op.setStatus(DurableOperation.STATUS_FAILED);
            op.setError(error != null && error.isObject()
                    ? objectMapper.convertValue(error, Map.class)
                    : errorObject("CallbackFailed", "callback failed"));
        });
    }

    public void callbackHeartbeat(String callbackId) {
        // Validates existence; Floci does not enforce heartbeat timeouts.
        findCallback(callbackId);
    }

    private void completeCallback(String callbackId, java.util.function.Consumer<DurableOperation> transition) {
        String executionArn = findCallback(callbackId);
        String opId;
        synchronized (lock(executionArn)) {
            DurableExecution execution = requireExecution(executionArn);
            DurableOperation op = execution.getOperations().stream()
                    .filter(o -> callbackId.equals(o.getCallbackId()))
                    .findFirst()
                    .orElseThrow(() -> callbackNotFound(callbackId));
            if (!DurableOperation.STATUS_STARTED.equals(op.getStatus())) {
                return; // already completed or timed out — idempotent
            }
            transition.accept(op);
            op.setEndTimestampMillis(System.currentTimeMillis());
            opId = op.getId();
            store.save(execution);
        }
        resume(executionArn, opId);
    }

    private String findCallback(String callbackId) {
        String executionArn = callbackIndex.get(callbackId);
        if (executionArn == null) {
            // Rebuild lazily (e.g. after the in-memory index was reset).
            for (DurableExecution execution : store.list()) {
                for (DurableOperation op : execution.getOperations()) {
                    if (callbackId.equals(op.getCallbackId())) {
                        callbackIndex.put(callbackId, execution.getExecutionArn());
                        return execution.getExecutionArn();
                    }
                }
            }
            throw callbackNotFound(callbackId);
        }
        return executionArn;
    }

    private AwsException callbackNotFound(String callbackId) {
        return new AwsException("ResourceNotFoundException", "Callback not found: " + callbackId, 404);
    }

    // ──────────────────────────── Management APIs ────────────────────────────

    public ObjectNode getExecution(String executionArn) {
        DurableExecution execution;
        synchronized (lock(executionArn)) {
            execution = requireExecution(executionArn);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("DurableExecutionArn", execution.getExecutionArn());
        node.put("DurableExecutionName", execution.getExecutionName());
        node.put("FunctionArn", execution.getFunctionArn());
        node.put("Status", execution.getStatus());
        node.put("StartTimestamp", toEpochSeconds(execution.getStartTimestampMillis()));
        if (execution.getEndTimestampMillis() != null) {
            node.put("EndTimestamp", toEpochSeconds(execution.getEndTimestampMillis()));
        }
        if (execution.getInputPayload() != null) {
            node.put("InputPayload", execution.getInputPayload());
        }
        if (execution.getResult() != null) {
            node.put("Result", execution.getResult());
        }
        if (execution.getError() != null) {
            node.set("Error", objectMapper.valueToTree(execution.getError()));
        }
        return node;
    }

    public ObjectNode getExecutionState(String executionArn, String checkpointToken) {
        synchronized (lock(executionArn)) {
            DurableExecution execution = requireExecution(executionArn);
            validateToken(execution, checkpointToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode ops = response.putArray("Operations");
            for (DurableOperation op : execution.getOperations()) {
                ops.add(operationToWire(op));
            }
            return response;
        }
    }

    public ObjectNode listExecutions(String region, String functionNameParam, String qualifierParam,
                                     String executionName, List<String> statuses, boolean reverseOrder) {
        ResolvedFunction fn = resolver.resolve(region, functionNameParam, qualifierParam);
        List<DurableExecution> executions = store.listByFunction(region, fn.functionName());
        if (executionName != null && !executionName.isBlank()) {
            executions.removeIf(e -> !executionName.equals(e.getExecutionName()));
        }
        if (statuses != null && !statuses.isEmpty()) {
            executions.removeIf(e -> !statuses.contains(e.getStatus()));
        }
        Comparator<DurableExecution> newestFirst =
                Comparator.comparingLong(DurableExecution::getStartTimestampMillis).reversed();
        executions.sort(reverseOrder ? newestFirst.reversed() : newestFirst);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("DurableExecutions");
        for (DurableExecution execution : executions) {
            ObjectNode node = items.addObject();
            node.put("DurableExecutionArn", execution.getExecutionArn());
            node.put("DurableExecutionName", execution.getExecutionName());
            node.put("FunctionArn", execution.getFunctionArn());
            node.put("Status", execution.getStatus());
            node.put("StartTimestamp", toEpochSeconds(execution.getStartTimestampMillis()));
            if (execution.getEndTimestampMillis() != null) {
                node.put("EndTimestamp", toEpochSeconds(execution.getEndTimestampMillis()));
            }
        }
        return response;
    }

    /**
     * Drops all executions of a function once the function itself is gone
     * (executions are function-scoped on AWS; keeping them would let a
     * recreated function reattach to a previous incarnation's results).
     * No-op while the function still exists (e.g. a version-only delete).
     */
    public void purgeExecutionsIfFunctionDeleted(String region, String functionNameParam) {
        String functionName;
        try {
            functionName = LambdaArnUtils.resolve(functionNameParam).name();
        } catch (AwsException e) {
            return;
        }
        try {
            resolver.resolve(region, functionName, null);
        } catch (AwsException e) {
            if (e.getHttpStatus() == 404) {
                for (DurableExecution execution : store.listByFunction(region, functionName)) {
                    synchronized (lock(execution.getExecutionArn())) {
                        store.delete(execution.getExecutionArn());
                        pendingUpdatedIds.remove(execution.getExecutionArn());
                    }
                }
            }
        }
    }

    public ObjectNode stopExecution(String executionArn, JsonNode errorBody) {
        long now = System.currentTimeMillis();
        synchronized (lock(executionArn)) {
            DurableExecution execution = requireExecution(executionArn);
            if (execution.isRunning()) {
                execution.setStatus(DurableExecution.STATUS_STOPPED);
                execution.setEndTimestampMillis(now);
                execution.setError(errorBody != null && errorBody.isObject() && !errorBody.isEmpty()
                        ? objectMapper.convertValue(errorBody, Map.class)
                        : errorObject("ExecutionStopped", "execution stopped"));
                store.save(execution);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StopTimestamp", toEpochSeconds(now));
        return response;
    }

    public ObjectNode getExecutionHistory(String executionArn, boolean includeExecutionData, boolean reverseOrder) {
        DurableExecution execution;
        synchronized (lock(executionArn)) {
            execution = requireExecution(executionArn);
        }
        List<ObjectNode> events = new ArrayList<>();
        for (DurableOperation op : execution.getOperations()) {
            ObjectNode started = historyEvent(op, eventType(op.getType(), "Started"),
                    op.getStartTimestampMillis());
            if (DurableOperation.TYPE_EXECUTION.equals(op.getType()) && includeExecutionData
                    && op.getInputPayload() != null) {
                ObjectNode details = started.putObject("ExecutionStartedDetails");
                details.putObject("Input").put("Payload", op.getInputPayload());
                details.put("ExecutionTimeout", 0);
            }
            events.add(started);

            String endType = terminalEventType(op);
            if (endType != null && op.getEndTimestampMillis() != null) {
                ObjectNode ended = historyEvent(op, endType, op.getEndTimestampMillis());
                if (includeExecutionData) {
                    if (op.getResult() != null) {
                        ended.putObject(endType + "Details").putObject("Result")
                                .put("Payload", op.getResult());
                    } else if (op.getError() != null) {
                        ended.putObject(endType + "Details").set("Error",
                                objectMapper.createObjectNode().set("Payload",
                                        objectMapper.valueToTree(op.getError())));
                    }
                }
                events.add(ended);
            }
        }
        events.sort(Comparator.comparingDouble(e -> e.get("EventTimestamp").asDouble()));
        if (reverseOrder) {
            java.util.Collections.reverse(events);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Events");
        int eventId = 1;
        for (ObjectNode event : events) {
            event.put("EventId", eventId++);
            array.add(event);
        }
        return response;
    }

    private ObjectNode historyEvent(DurableOperation op, String eventType, long timestampMillis) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("EventType", eventType);
        event.put("Id", op.getId());
        if (op.getName() != null) {
            event.put("Name", op.getName());
        }
        if (op.getParentId() != null) {
            event.put("ParentId", op.getParentId());
        }
        if (op.getSubType() != null) {
            event.put("SubType", op.getSubType());
        }
        event.put("EventTimestamp", toEpochSeconds(timestampMillis));
        return event;
    }

    private String eventType(String operationType, String suffix) {
        return switch (operationType) {
            case DurableOperation.TYPE_EXECUTION -> "Execution" + suffix;
            case DurableOperation.TYPE_CONTEXT -> "Context" + suffix;
            case DurableOperation.TYPE_STEP -> "Step" + suffix;
            case DurableOperation.TYPE_WAIT -> "Wait" + suffix;
            case DurableOperation.TYPE_CALLBACK -> "Callback" + suffix;
            case DurableOperation.TYPE_CHAINED_INVOKE -> "ChainedInvoke" + suffix;
            default -> operationType + suffix;
        };
    }

    private String terminalEventType(DurableOperation op) {
        return switch (op.getStatus()) {
            case DurableOperation.STATUS_SUCCEEDED -> eventType(op.getType(), "Succeeded");
            case DurableOperation.STATUS_FAILED -> eventType(op.getType(), "Failed");
            case DurableOperation.STATUS_CANCELLED -> eventType(op.getType(), "Cancelled");
            case DurableOperation.STATUS_TIMED_OUT -> eventType(op.getType(), "TimedOut");
            default -> null;
        };
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private DurableExecution requireExecution(String executionArn) {
        return store.get(executionArn).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Durable execution not found: " + executionArn, 404));
    }

    private void validateToken(DurableExecution execution, String token) {
        if (token == null || !token.equals(execution.getCheckpointToken())) {
            throw new AwsException("InvalidParameterValueException",
                    "Invalid CheckpointToken for durable execution " + execution.getExecutionArn(), 400);
        }
    }

    private ObjectNode operationToWire(DurableOperation op) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", op.getId());
        if (op.getParentId() != null) {
            node.put("ParentId", op.getParentId());
        }
        if (op.getName() != null) {
            node.put("Name", op.getName());
        }
        node.put("Type", op.getType());
        if (op.getSubType() != null) {
            node.put("SubType", op.getSubType());
        }
        node.put("Status", op.getStatus());
        node.put("StartTimestamp", toEpochSeconds(op.getStartTimestampMillis()));
        if (op.getEndTimestampMillis() != null) {
            node.put("EndTimestamp", toEpochSeconds(op.getEndTimestampMillis()));
        }
        switch (op.getType()) {
            case DurableOperation.TYPE_EXECUTION -> {
                ObjectNode details = node.putObject("ExecutionDetails");
                if (op.getInputPayload() != null) {
                    details.put("InputPayload", op.getInputPayload());
                }
            }
            case DurableOperation.TYPE_STEP -> {
                ObjectNode details = node.putObject("StepDetails");
                if (op.getAttempt() != null) {
                    details.put("Attempt", op.getAttempt());
                }
                if (op.getNextAttemptTimestampMillis() != null) {
                    details.put("NextAttemptTimestamp", toEpochSeconds(op.getNextAttemptTimestampMillis()));
                }
                if (op.getResult() != null) {
                    details.put("Result", op.getResult());
                }
                if (op.getError() != null) {
                    details.set("Error", objectMapper.valueToTree(op.getError()));
                }
            }
            case DurableOperation.TYPE_WAIT -> {
                ObjectNode details = node.putObject("WaitDetails");
                if (op.getScheduledEndTimestampMillis() != null) {
                    details.put("ScheduledEndTimestamp", toEpochSeconds(op.getScheduledEndTimestampMillis()));
                }
            }
            case DurableOperation.TYPE_CALLBACK -> {
                ObjectNode details = node.putObject("CallbackDetails");
                if (op.getCallbackId() != null) {
                    details.put("CallbackId", op.getCallbackId());
                }
                if (op.getResult() != null) {
                    details.put("Result", op.getResult());
                }
                if (op.getError() != null) {
                    details.set("Error", objectMapper.valueToTree(op.getError()));
                }
            }
            case DurableOperation.TYPE_CONTEXT -> {
                ObjectNode details = node.putObject("ContextDetails");
                if (op.getResult() != null) {
                    details.put("Result", op.getResult());
                }
                if (op.getError() != null) {
                    details.set("Error", objectMapper.valueToTree(op.getError()));
                }
            }
            case DurableOperation.TYPE_CHAINED_INVOKE -> {
                ObjectNode details = node.putObject("ChainedInvokeDetails");
                if (op.getResult() != null) {
                    details.put("Result", op.getResult());
                }
                if (op.getError() != null) {
                    details.set("Error", objectMapper.valueToTree(op.getError()));
                }
            }
            default -> {
                // no details block for unknown types
            }
        }
        return node;
    }

    private double toEpochSeconds(long millis) {
        return millis / 1000.0;
    }
}
