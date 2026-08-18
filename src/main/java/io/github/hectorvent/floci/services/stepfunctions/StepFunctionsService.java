package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.ActivityTask;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.MapRun;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineVersion;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class StepFunctionsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(StepFunctionsService.class);

    private final StorageBackend<String, StateMachine> stateMachineStore;
    private final StorageBackend<String, Execution> executionStore;
    private final StorageBackend<String, Activity> activityStore;
    private final Map<String, List<HistoryEvent>> historyCache = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<ActivityTask>> activityQueues = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> pendingTaskTokens = new ConcurrentHashMap<>();
    private final Map<String, MapRun> mapRunStore = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final AslExecutor aslExecutor;
    private final ObjectMapper objectMapper;

    // Fields that are valid only in JSONPath mode. Validated against real AWS:
    // creating a JSONata state machine with any of these fields returns SCHEMA_VALIDATION_FAILED.
    private static final Set<String> JSONPATH_ONLY_FIELDS = Set.of(
            "InputPath", "OutputPath", "ResultPath", "ResultSelector", "Parameters", "Result", "ItemsPath");
    private static final Set<String> ITEM_READER_RESOURCES = Set.of(
            "arn:aws:states:::s3:getObject",
            "arn:aws:states:::s3:listObjectsV2");
    private static final Set<String> ITEM_READER_INPUT_TYPES = Set.of(
            "MANIFEST", "JSON", "CSV", "JSONL", "PARQUET");

    @Inject
    public StepFunctionsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                AslExecutor aslExecutor, ObjectMapper objectMapper) {
        this.stateMachineStore = storageFactory.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {});
        this.executionStore = storageFactory.create("stepfunctions", "sfn-executions.json",
                new TypeReference<Map<String, Execution>>() {});
        this.activityStore = storageFactory.create("stepfunctions", "sfn-activities.json",
                new TypeReference<Map<String, Activity>>() {});
        this.regionResolver = regionResolver;
        this.aslExecutor = aslExecutor;
        this.objectMapper = objectMapper;
    }

    public void clear() {
        historyCache.clear();
        activityQueues.clear();
        pendingTaskTokens.values().forEach(f -> f.completeExceptionally(new RuntimeException("StepFunctionsService cleared")));
        pendingTaskTokens.clear();
        mapRunStore.clear();
    }

    // ──────────────────────────── State Machines ────────────────────────────

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type, String region, Map<String, String> tags) {
        return createStateMachine(name, definition, roleArn, type, region, tags, null, null);
    }

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type, String region,
                                           Map<String, String> tags, JsonNode loggingConfiguration,
                                           JsonNode tracingConfiguration) {
        String arn = regionResolver.buildArn("states", region, "stateMachine:" + name);
        if (stateMachineStore.get(arn).isPresent()) {
            throw new AwsException("StateMachineAlreadyExists", "State machine already exists: " + arn, 400);
        }

        validateDefinition(definition);

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(arn);
        sm.setName(name);
        sm.setDefinition(definition);
        sm.setRoleArn(roleArn);
        if (type != null && !type.isEmpty()) {
            sm.setType(type);
        }
        if (tags != null && !tags.isEmpty()) {
            sm.getTags().putAll(tags);
        }
        applyLogging(sm, loggingConfiguration);
        applyTracing(sm, tracingConfiguration);

        stateMachineStore.put(arn, sm);
        LOG.infov("Created State Machine: {0}", arn);
        return sm;
    }

    public StateMachine updateStateMachine(String arn, String definition, String roleArn,
                                           JsonNode loggingConfiguration, JsonNode tracingConfiguration) {
        StateMachine sm = describeStateMachine(arn);
        if (definition != null && !definition.isBlank()) {
            validateDefinition(definition);
            sm.setDefinition(definition);
        }
        if (roleArn != null && !roleArn.isBlank()) {
            sm.setRoleArn(roleArn);
        }
        applyLogging(sm, loggingConfiguration);
        applyTracing(sm, tracingConfiguration);
        sm.setRevisionId(UUID.randomUUID().toString());
        stateMachineStore.put(arn, sm);
        LOG.infov("Updated State Machine: {0} revision {1}", arn, sm.getRevisionId());
        return sm;
    }

    private static void applyLogging(StateMachine sm, JsonNode loggingConfiguration) {
        if (loggingConfiguration == null || loggingConfiguration.isNull() || loggingConfiguration.isMissingNode()) {
            return;
        }
        if (loggingConfiguration.has("level")) {
            sm.setLoggingLevel(loggingConfiguration.path("level").asText("OFF"));
        }
        if (loggingConfiguration.has("includeExecutionData")) {
            sm.setIncludeExecutionData(loggingConfiguration.path("includeExecutionData").asBoolean(false));
        }
        if (loggingConfiguration.has("destinations")) {
            JsonNode destinations = loggingConfiguration.get("destinations");
            sm.setLoggingDestinationsJson(destinations.isArray() && !destinations.isEmpty()
                    ? destinations.toString() : null);
        }
    }

    private static void applyTracing(StateMachine sm, JsonNode tracingConfiguration) {
        if (tracingConfiguration == null || tracingConfiguration.isNull() || tracingConfiguration.isMissingNode()) {
            return;
        }
        if (tracingConfiguration.has("enabled")) {
            sm.setTracingEnabled(tracingConfiguration.path("enabled").asBoolean(false));
        }
    }

    public StateMachine describeStateMachine(String arn) {
        return stateMachineStore.get(arn)
                .orElseThrow(() -> new AwsException("StateMachineDoesNotExist", "State machine does not exist", 400));
    }

    public List<StateMachine> listStateMachines(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return stateMachineStore.scan(k -> k.startsWith(prefix));
    }

    // ── State machine versions ──────────────────────────────────────────────

    public StateMachineVersion publishStateMachineVersion(String stateMachineArn) {
        StateMachine sm = describeStateMachine(stateMachineArn);
        int next = sm.getVersionCounter() + 1;
        sm.setVersionCounter(next);
        StateMachineVersion version = new StateMachineVersion(
                stateMachineArn + ":" + next, next, System.currentTimeMillis() / 1000.0);
        sm.getVersions().add(version);
        stateMachineStore.put(stateMachineArn, sm);
        return version;
    }

    public List<StateMachineVersion> listStateMachineVersions(String stateMachineArn) {
        // AWS returns InvalidArn for a non-existent state machine here — StateMachineDoesNotExist is
        // not one of ListStateMachineVersions' declared errors (Publish, which does declare it, keeps
        // using describeStateMachine).
        StateMachine sm = stateMachineStore.get(stateMachineArn)
                .orElseThrow(() -> new AwsException("InvalidArn",
                        "Invalid Arn: '" + stateMachineArn + "'", 400));
        // AWS lists versions newest first (descending by creationDate). creationDate is only
        // second-resolution, so tie-break on the version number (also descending) to keep the order
        // correct when several versions are published within the same second — otherwise the
        // Terraform provider can latch onto the wrong version ARN.
        List<StateMachineVersion> versions = new ArrayList<>(sm.getVersions());
        versions.sort(Comparator
                .comparingDouble(StateMachineVersion::getCreationDate)
                .thenComparingInt(StateMachineVersion::getVersion)
                .reversed());
        // Defensive copy so callers can't mutate (or trip over concurrent mutation of) the stored list.
        return List.copyOf(versions);
    }

    public void deleteStateMachineVersion(String stateMachineVersionArn) {
        int lastColon = stateMachineVersionArn.lastIndexOf(':');
        if (lastColon < 0) {
            return;
        }
        String baseArn = stateMachineVersionArn.substring(0, lastColon);
        stateMachineStore.get(baseArn).ifPresent(sm -> {
            sm.getVersions().removeIf(v -> stateMachineVersionArn.equals(v.getStateMachineVersionArn()));
            stateMachineStore.put(baseArn, sm);
        });
    }

    public void deleteStateMachine(String arn) {
        stateMachineStore.delete(arn);
    }

    // ──────────────────────────── Executions ────────────────────────────

    public Execution startExecution(String stateMachineArn, String name, String input, String region) {
        StateMachine sm = describeStateMachine(stateMachineArn);
        String execName = (name != null && !name.isBlank()) ? name : UUID.randomUUID().toString();
        String arn = regionResolver.buildArn("states", region, "execution:" + sm.getName() + ":" + execName);

        if (executionStore.get(arn).isPresent()) {
            throw new AwsException("ExecutionAlreadyExists", "Execution already exists: " + arn, 400);
        }

        Execution exec = new Execution();
        exec.setExecutionArn(arn);
        exec.setStateMachineArn(stateMachineArn);
        exec.setName(execName);
        exec.setInput(input);
        exec.setStatus("RUNNING");

        executionStore.put(arn, exec);

        List<HistoryEvent> history = new ArrayList<>();
        HistoryEvent startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                     "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : ""));
        history.add(startEvent);
        historyCache.put(arn, history);

        LOG.infov("Started execution: {0}", arn);

        aslExecutor.executeAsync(sm, exec, history, (updatedExec, updatedHistory) -> {
            executionStore.put(updatedExec.getExecutionArn(), updatedExec);
            historyCache.put(updatedExec.getExecutionArn(), updatedHistory);
            LOG.infov("Execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    public Execution startSyncExecution(String stateMachineArn, String name, String input, String region) {
        StateMachine sm = describeStateMachine(stateMachineArn);
        if (!"EXPRESS".equals(sm.getType())) {
            throw new AwsException("StateMachineTypeNotSupported",
                    "StartSyncExecution is only supported for EXPRESS state machines", 400);
        }

        String execName = (name != null && !name.isBlank()) ? name : UUID.randomUUID().toString();
        // Real AWS express execution ARN format: express:<smName>:<startDate>:<execName>
        // where startDate is ISO-8601 UTC, e.g. 2024-01-15T10:30:00.123Z
        String startDate = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String arn = regionResolver.buildArn("states", region,
                "express:" + sm.getName() + ":" + startDate + ":" + execName);

        Execution exec = new Execution();
        exec.setExecutionArn(arn);
        exec.setStateMachineArn(stateMachineArn);
        exec.setName(execName);
        exec.setInput(input);
        exec.setStatus("RUNNING");

        List<HistoryEvent> history = new ArrayList<>();
        HistoryEvent startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                     "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : ""));
        history.add(startEvent);

        aslExecutor.executeSync(sm, exec, history, (updatedExec, updatedHistory) -> {
            LOG.infov("Sync execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    public Execution describeExecution(String arn) {
        return executionStore.get(arn)
                .orElseThrow(() -> new AwsException("ExecutionDoesNotExist", "Execution does not exist", 400));
    }

    public List<Execution> listExecutions(String stateMachineArn) {
        return executionStore.scan(k -> executionStore.get(k)
                .map(e -> e.getStateMachineArn().equals(stateMachineArn)).orElse(false));
    }

    public void stopExecution(String arn, String cause, String error) {
        Execution exec = describeExecution(arn);
        if (!"RUNNING".equals(exec.getStatus())) {
            return;
        }
        exec.setStatus("ABORTED");
        exec.setStopDate(System.currentTimeMillis() / 1000.0);
        executionStore.put(arn, exec);

        List<HistoryEvent> history = historyCache.getOrDefault(arn, new ArrayList<>());
        HistoryEvent event = new HistoryEvent();
        event.setId(history.size() + 1L);
        event.setType("ExecutionAborted");
        Map<String, Object> details = new HashMap<>();
        if (error != null) details.put("error", error);
        if (cause != null) details.put("cause", cause);
        event.setDetails(details);
        history.add(event);
    }

    public List<HistoryEvent> getExecutionHistory(String arn) {
        return getExecutionHistory(arn, false, null);
    }

    public List<HistoryEvent> getExecutionHistory(String arn, boolean reverseOrder, Integer maxResults) {
        describeExecution(arn);
        List<HistoryEvent> events = new ArrayList<>(historyCache.getOrDefault(arn, Collections.emptyList()));
        if (reverseOrder) {
            Collections.reverse(events);
        }
        if (maxResults != null && maxResults > 0 && events.size() > maxResults) {
            return events.subList(0, maxResults);
        }
        return events;
    }

    /**
     * Restarts a failed/aborted/timed-out STANDARD execution. SUCCEEDED and RUNNING
     * executions are not redrivable — matching AWS {@code ExecutionNotRedrivable}.
     */
    public double redriveExecution(String executionArn) {
        Execution exec = describeExecution(executionArn);
        String status = exec.getStatus();
        if ("SUCCEEDED".equals(status) || "RUNNING".equals(status)) {
            throw new AwsException("ExecutionNotRedrivable",
                    "Execution " + executionArn + " is not redrivable: status is " + status, 400);
        }
        StateMachine sm = describeStateMachine(exec.getStateMachineArn());
        exec.setStatus("RUNNING");
        exec.setStopDate(null);
        exec.setError(null);
        exec.setCause(null);
        exec.setOutput(null);
        executionStore.put(executionArn, exec);

        List<HistoryEvent> history = new ArrayList<>();
        HistoryEvent startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", exec.getInput() != null ? exec.getInput() : "{}",
                "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : "",
                "redriveCount", 1));
        history.add(startEvent);
        historyCache.put(executionArn, history);

        aslExecutor.executeAsync(sm, exec, history, (updatedExec, updatedHistory) -> {
            executionStore.put(updatedExec.getExecutionArn(), updatedExec);
            historyCache.put(updatedExec.getExecutionArn(), updatedHistory);
        });
        return System.currentTimeMillis() / 1000.0;
    }

    public record TestStateResult(String status, String output, String error, String cause) {}

    /**
     * Execute a single ASL state without creating a state machine (AWS {@code TestState}).
     */
    public TestStateResult testState(String definition, String input) {
        if (definition == null || definition.isBlank()) {
            throw new AwsException("ValidationException", "definition is required.", 400);
        }
        JsonNode stateDef;
        try {
            stateDef = objectMapper.readTree(definition);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid state definition: " + e.getMessage(), 400);
        }
        if (!stateDef.isObject() || !stateDef.has("Type")) {
            throw new AwsException("ValidationException",
                    "State definition must be an object with a Type field.", 400);
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        if (stateDef.has("QueryLanguage")) {
            wrapper.put("QueryLanguage", stateDef.path("QueryLanguage").asText());
        }
        wrapper.put("StartAt", "TestState");
        ObjectNode states = objectMapper.createObjectNode();
        states.set("TestState", stateDef);
        wrapper.set("States", states);

        StateMachine sm = new StateMachine();
        sm.setName("TestState");
        sm.setDefinition(wrapper.toString());
        sm.setRoleArn("arn:aws:iam::000000000000:role/test-state");
        sm.setStateMachineArn(regionResolver.buildArn("states", regionResolver.getDefaultRegion(),
                "stateMachine:TestState"));
        sm.setType("EXPRESS");

        Execution exec = new Execution();
        exec.setName("test-state");
        exec.setInput(input != null && !input.isBlank() ? input : "{}");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setExecutionArn(regionResolver.buildArn("states", regionResolver.getDefaultRegion(),
                "express:TestState:test-state"));

        List<HistoryEvent> history = new ArrayList<>();
        aslExecutor.executeSync(sm, exec, history, (updated, ignored) -> {});
        return new TestStateResult(exec.getStatus(), exec.getOutput(), exec.getError(), exec.getCause());
    }

    // ──────────────────────────── Map Runs ────────────────────────────

    public MapRun startMapRun(String executionArn, String stateMachineName,
                              String executionName, String region, int itemCount, int maxConcurrency) {
        String uuid = UUID.randomUUID().toString();
        String mapRunArn = regionResolver.buildArn("states", region,
                "mapRun:" + stateMachineName + "/" + executionName + ":" + uuid);
        MapRun run = new MapRun();
        run.setMapRunArn(mapRunArn);
        run.setExecutionArn(executionArn);
        run.setStatus("RUNNING");
        run.setTotal(itemCount);
        run.setPending(itemCount);
        run.setMaxConcurrency(maxConcurrency);
        mapRunStore.put(mapRunArn, run);
        return run;
    }

    public void completeMapRun(String mapRunArn, int succeeded, int failed) {
        MapRun run = mapRunStore.get(mapRunArn);
        if (run == null) {
            return;
        }
        run.setStatus(failed > 0 ? "FAILED" : "SUCCEEDED");
        run.setSucceeded(succeeded);
        run.setFailed(failed);
        run.setPending(0);
        run.setRunning(0);
        run.setStopDate(System.currentTimeMillis() / 1000.0);
    }

    public void failMapRun(String mapRunArn) {
        MapRun run = mapRunStore.get(mapRunArn);
        if (run == null) {
            return;
        }
        run.setStatus("FAILED");
        run.setStopDate(System.currentTimeMillis() / 1000.0);
    }

    public List<MapRun> listMapRuns(String executionArn) {
        describeExecution(executionArn);
        return mapRunStore.values().stream()
                .filter(run -> executionArn.equals(run.getExecutionArn()))
                .toList();
    }

    public MapRun describeMapRun(String mapRunArn) {
        MapRun run = mapRunStore.get(mapRunArn);
        if (run == null) {
            throw new AwsException("ResourceNotFound", "Map Run does not exist: " + mapRunArn, 400);
        }
        return run;
    }

    public void updateMapRun(String mapRunArn, Integer maxConcurrency, Long toleratedFailureCount,
                             Double toleratedFailurePercentage) {
        MapRun run = describeMapRun(mapRunArn);
        if (!"RUNNING".equals(run.getStatus())) {
            throw new AwsException("ValidationException",
                    "Map Run " + mapRunArn + " is not running and cannot be updated.", 400);
        }
        if (maxConcurrency != null) {
            run.setMaxConcurrency(maxConcurrency);
        }
        if (toleratedFailureCount != null) {
            run.setToleratedFailureCount(toleratedFailureCount);
        }
        if (toleratedFailurePercentage != null) {
            run.setToleratedFailurePercentage(toleratedFailurePercentage);
        }
    }

    // ──────────────────────────── Activities ────────────────────────────

    public Activity createActivity(String name, String region, Map<String, String> tags) {
        String arn = regionResolver.buildArn("states", region, "activity:" + name);
        if (activityStore.get(arn).isPresent()) {
            throw new AwsException("ActivityAlreadyExists", "Activity already exists: " + arn, 400);
        }
        Activity activity = new Activity();
        activity.setActivityArn(arn);
        activity.setName(name);
        if (tags != null && !tags.isEmpty()) {
            activity.getTags().putAll(tags);
        }
        activityStore.put(arn, activity);
        LOG.infov("Created activity: {0}", arn);
        return activity;
    }

    public Activity describeActivity(String arn) {
        return activityStore.get(arn)
                .orElseThrow(() -> new AwsException("ActivityDoesNotExist", "Activity does not exist: " + arn, 400));
    }

    public List<Activity> listActivities(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return activityStore.scan(k -> k.startsWith(prefix) && k.contains(":activity:"));
    }

    public void deleteActivity(String arn) {
        activityStore.delete(arn);
        activityQueues.remove(arn);
    }

    /**
     * Long-poll: blocks up to 60 seconds waiting for a task to be enqueued for this activity.
     * Returns null if no task arrives within the timeout.
     */
    public ActivityTask getActivityTask(String activityArn, String workerName) {
        describeActivity(activityArn); // validate exists
        BlockingQueue<ActivityTask> queue = activityQueues.computeIfAbsent(activityArn,
                k -> new LinkedBlockingQueue<>());
        try {
            return queue.poll(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void enqueueActivityTask(String activityArn, String taskToken, String input) {
        BlockingQueue<ActivityTask> queue = activityQueues.computeIfAbsent(activityArn,
                k -> new LinkedBlockingQueue<>());
        queue.add(new ActivityTask(taskToken, input));
    }

    public CompletableFuture<JsonNode> registerPendingToken(String token) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingTaskTokens.put(token, future);
        return future;
    }

    // ──────────────────────────── Tasks ────────────────────────────

    public void sendTaskSuccess(String taskToken, String output) {
        CompletableFuture<JsonNode> future = pendingTaskTokens.remove(taskToken);
        if (future != null) {
            try {
                future.complete(objectMapper.readTree(output));
            } catch (Exception e) {
                future.completeExceptionally(new RuntimeException("Invalid JSON output: " + e.getMessage()));
            }
        } else {
            LOG.warnv("SendTaskSuccess: no pending task for token {0}", taskToken);
        }
    }

    public void sendTaskFailure(String taskToken, String cause, String error) {
        CompletableFuture<JsonNode> future = pendingTaskTokens.remove(taskToken);
        if (future != null) {
            future.completeExceptionally(new AslExecutor.FailStateException(error, cause));
        } else {
            LOG.warnv("SendTaskFailure: no pending task for token {0}", taskToken);
        }
    }

    public void sendTaskHeartbeat(String taskToken) {
        if (taskToken == null || taskToken.isBlank() || !pendingTaskTokens.containsKey(taskToken)) {
            throw new AwsException("InvalidToken", "Invalid token: '" + taskToken + "'", 400);
        }
        LOG.debugv("Task heartbeat for token {0}", taskToken);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String arn) {
        Optional<StateMachine> sm = stateMachineStore.get(arn);
        if (sm.isPresent()) {
            return sm.get().getTags();
        }
        Optional<Activity> activity = activityStore.get(arn);
        if (activity.isPresent()) {
            return activity.get().getTags();
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    public void tagResource(String arn, Map<String, String> tags) {
        Optional<StateMachine> smOpt = stateMachineStore.get(arn);
        if (smOpt.isPresent()) {
            StateMachine sm = smOpt.get();
            sm.getTags().putAll(tags);
            stateMachineStore.put(arn, sm);
            return;
        }
        Optional<Activity> actOpt = activityStore.get(arn);
        if (actOpt.isPresent()) {
            Activity activity = actOpt.get();
            activity.getTags().putAll(tags);
            activityStore.put(arn, activity);
            return;
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Optional<StateMachine> smOpt = stateMachineStore.get(arn);
        if (smOpt.isPresent()) {
            StateMachine sm = smOpt.get();
            tagKeys.forEach(sm.getTags()::remove);
            stateMachineStore.put(arn, sm);
            return;
        }
        Optional<Activity> actOpt = activityStore.get(arn);
        if (actOpt.isPresent()) {
            Activity activity = actOpt.get();
            tagKeys.forEach(activity.getTags()::remove);
            activityStore.put(arn, activity);
            return;
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    // ──────────────────────────── Validation ────────────────────────────

    public record Diagnostic(String severity, String code, String message, String location) {}
    public record ValidationResult(boolean valid, List<Diagnostic> diagnostics, boolean truncated) {}

    private static final int MAX_DEFINITION_LENGTH = 1_048_576;
    private static final String PARSE_ERROR_MARKER = "INVALID_JSON_DESCRIPTION:";

    // Parse the structured location out of validator flat error strings,
    // which currently encode it as "...field 'X' ... at /States/Y".
    // AWS's published Diagnostic.location format is "/States/<StateName>/<FieldName>".
    private static final Pattern FIELD_PATTERN = Pattern.compile("field '([^']+)'");
    private static final Pattern LOCATION_SUFFIX_PATTERN = Pattern.compile(" at (/States/\\S+)$");

    /**
     * Exposes the existing ASL validator as a public, non-throwing API for
     * AWS {@code ValidateStateMachineDefinition}. Errors are returned as
     * diagnostics rather than thrown; no state machine is created. Mirrors
     * the wire shape of the AWS API.
     */
    public ValidationResult validateStateMachineDefinition(String definition, String type,
                                                           String severity, Integer maxResults) {
        if (definition == null || definition.isBlank()) {
            throw new AwsException("ValidationException", "definition is required.", 400);
        }
        if (definition.length() > MAX_DEFINITION_LENGTH) {
            throw new AwsException("ValidationException",
                    "definition exceeds maximum length of " + MAX_DEFINITION_LENGTH + " characters.", 400);
        }
        if (maxResults != null && (maxResults < 0 || maxResults > 100)) {
            throw new AwsException("ValidationException",
                    "maxResults must be between 0 and 100.", 400);
        }
        if (severity != null && !severity.isBlank()
                && !"ERROR".equals(severity) && !"WARNING".equals(severity)) {
            throw new AwsException("ValidationException",
                    "severity must be ERROR or WARNING.", 400);
        }
        if (type != null && !type.isBlank()
                && !"STANDARD".equals(type) && !"EXPRESS".equals(type)) {
            throw new AwsException("ValidationException",
                    "type must be STANDARD or EXPRESS.", 400);
        }
        // Per AWS spec: maxResults=0 (or absent/null) → use default of 100.
        // Out-of-range values are rejected above; no clamping needed here.
        int cap = (maxResults == null || maxResults == 0) ? 100 : maxResults;
        List<String> errors = collectValidationErrors(definition);
        List<Diagnostic> all = errors.stream()
                .map(StepFunctionsService::toDiagnostic)
                .toList();

        // Apply the severity filter per spec: ERROR (the default) returns only
        // error diagnostics; WARNING returns both warnings and errors. Floci's
        // validator only emits ERROR today so the filter is currently a no-op,
        // but it's wired now so adding warning-level checks later doesn't break
        // the contract for callers who passed severity=ERROR.
        String effectiveSeverity = severity == null || severity.isBlank() ? "ERROR" : severity;
        List<Diagnostic> filtered = "WARNING".equals(effectiveSeverity)
                ? all
                : all.stream().filter(d -> "ERROR".equals(d.severity())).toList();

        boolean truncated = filtered.size() > cap;
        List<Diagnostic> page = truncated ? filtered.subList(0, cap) : filtered;
        // valid == "no ERROR-level diagnostics". Floci only produces ERRORs today;
        // explicit check future-proofs us when warnings are added.
        boolean valid = page.stream().noneMatch(d -> "ERROR".equals(d.severity()));
        return new ValidationResult(valid, page, truncated);
    }

    private static Diagnostic toDiagnostic(String error) {
        boolean isParseError = error.startsWith(PARSE_ERROR_MARKER);
        String code = isParseError ? "INVALID_JSON_DESCRIPTION" : "SCHEMA_VALIDATION_FAILED";
        String message = isParseError
                ? error.substring(PARSE_ERROR_MARKER.length()).trim() : error;
        // null when there's no specific location to point to — handler omits the
        // field from the response in that case, matching AWS's "optional" semantics.
        String location = null;
        if (!isParseError) {
            Matcher locM = LOCATION_SUFFIX_PATTERN.matcher(message);
            Matcher fieldM = FIELD_PATTERN.matcher(message);
            if (locM.find() && fieldM.find()) {
                // Build the structured location and strip the redundant suffix
                // from the message, matching AWS's wire format.
                location = locM.group(1);
                String field = fieldM.group(1);
                if (!location.endsWith("/" + field)) {
                    location = location + "/" + field;
                }
                message = message.substring(0, locM.start()).trim();
            }
        }
        return new Diagnostic("ERROR", code, message, location);
    }

    private void validateDefinition(String definition) {
        List<String> errors = collectValidationErrors(definition);
        if (errors.isEmpty()) {
            return;
        }
        String first = errors.get(0);
        if (first.startsWith(PARSE_ERROR_MARKER)) {
            // Preserve historical wire shape for parse errors triggered via CreateStateMachine.
            throw new AwsException("InvalidDefinition",
                    "Invalid State Machine Definition: '" + first.substring(PARSE_ERROR_MARKER.length()).trim() + "'", 400);
        }
        throw new AwsException("InvalidDefinition",
                "Invalid State Machine Definition: 'SCHEMA_VALIDATION_FAILED: "
                        + String.join(", ", errors) + "'", 400);
    }

    private List<String> collectValidationErrors(String definition) {
        List<String> errors = new ArrayList<>();
        JsonNode def;
        try {
            def = objectMapper.readTree(definition);
        } catch (Exception e) {
            errors.add(PARSE_ERROR_MARKER + e.getMessage());
            return errors;
        }

        String topLevelQL = def.path("QueryLanguage").asText("JSONPath");
        boolean topLevelJsonata = "JSONata".equals(topLevelQL);
        JsonNode states = def.path("States");

        if (!states.isObject() || states.isEmpty()) {
            errors.add("The field 'States' is required and must be a non-empty object.");
        } else {
            String startAt = def.path("StartAt").asText(null);
            if (startAt == null || startAt.isBlank()) {
                errors.add("The field 'StartAt' is required.");
            } else if (!states.has(startAt)) {
                errors.add("The field 'StartAt' ('" + startAt
                        + "') does not reference a state in 'States'.");
            }
            var fields = states.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                validateState(entry.getKey(), entry.getValue(), topLevelJsonata, errors);
            }
        }
        return errors;
    }

    private void validateState(String stateName, JsonNode stateDef, boolean topLevelJsonata, List<String> errors) {
        String stateQL = stateDef.path("QueryLanguage").asText(null);
        boolean stateIsJsonata = stateQL != null ? "JSONata".equals(stateQL) : topLevelJsonata;

        // JSONPath-only fields are not allowed when the state uses JSONata
        if (stateIsJsonata) {
            for (String field : JSONPATH_ONLY_FIELDS) {
                if (stateDef.has(field)) {
                    errors.add("The QueryLanguage is set to 'JSONata', but field '" + field
                            + "' is only supported for the 'JSONPath' QueryLanguage at /States/" + stateName);
                }
            }
        }

        if ("Map".equals(stateDef.path("Type").asText()) && stateDef.has("ItemReader")) {
            validateItemReader(stateName, stateDef, errors);
        }
    }

    private void validateItemReader(String stateName, JsonNode stateDef, List<String> errors) {
        JsonNode itemReader = stateDef.get("ItemReader");
        String resource = itemReader.path("Resource").asText(null);
        if (resource != null && !ITEM_READER_RESOURCES.contains(resource)) {
            errors.add("The field 'Resource' does not match any of the allowed values. Examples: "
                    + "[arn:<partition>:states:::s3:getObject, arn:<partition>:states:::s3:listObjectsV2]"
                    + " at /States/" + stateName + "/ItemReader/Resource");
        }

        String inputType = itemReader.path("ReaderConfig").path("InputType").asText(null);
        if (inputType != null && !ITEM_READER_INPUT_TYPES.contains(inputType)) {
            errors.add("The field 'InputType' should have one of these values: "
                    + "[MANIFEST, JSON, CSV, JSONL, PARQUET]"
                    + " at /States/" + stateName + "/ItemReader/ReaderConfig/InputType");
        }
    }
}
