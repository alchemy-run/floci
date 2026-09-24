package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class EmrServerlessJobService implements ContainerTeardown, Resettable {

    private static final Logger LOG = Logger.getLogger(EmrServerlessJobService.class);
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "CANCELLED");
    private static final Set<String> STATES = Set.of("SUBMITTED", "PENDING", "SCHEDULED", "RUNNING",
            "SUCCESS", "FAILED", "CANCELLING", "CANCELLED", "QUEUED");
    static final int QUEUE_LIMIT = 32;
    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final EmrServerlessSparkRunner runner;
    private final ObjectMapper mapper;
    private final IamService iam;
    private final IamPolicyEvaluator policies;
    private final EmulatorConfig config;
    private final int timeoutSeconds;
    private ScheduledExecutorService dispatcher;
    private EmrServerlessSparkRunner.Execution active;
    private ObjectNode activeJob;
    private boolean stopping;

    @Inject
    public EmrServerlessJobService(StorageFactory storageFactory, EmrServerlessSparkRunner runner,
                                  ObjectMapper mapper, IamService iam, IamPolicyEvaluator policies,
                                  EmulatorConfig config,
                                  @ConfigProperty(name = "floci.services.emrserverless.job-timeout-seconds",
                                          defaultValue = "300") int timeoutSeconds) {
        this.jobs = storageFactory.create("emrserverless", "emr-serverless-job-runs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.runner = runner;
        this.mapper = mapper;
        this.iam = iam;
        this.policies = policies;
        this.config = config;
        if (timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new IllegalArgumentException("EMR Serverless job timeout must be between 1 and 900 seconds");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @PostConstruct
    synchronized void startDispatcher() {
        if (!config.services().emrserverless().enabled() || dispatcher != null) {
            return;
        }
        stopping = false;
        dispatcher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("emrserverless-dispatcher").factory());
        dispatcher.scheduleWithFixedDelay(() -> {
            try {
                dispatch();
            } catch (Exception e) {
                LOG.warn("EMR Serverless dispatch failed", e);
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    synchronized ObjectNode start(Application app, JsonNode request, String account, String region, String authorization) {
        if (stopping) {
            throw new AwsException("ServiceUnavailableException", "EMR Serverless is shutting down", 503);
        }
        String token = requireText(request, "clientToken");
        String role = requireText(request, "executionRoleArn");
        if (token.length() > 64) {
            throw invalid("clientToken must not exceed 64 characters");
        }
        ObjectNode identity = ((ObjectNode) request).deepCopy();
        identity.remove("clientToken");
        for (ObjectNode existing : inApplication(account, region, app.getApplicationId())) {
            if (token.equals(existing.path("_clientToken").asText())) {
                if (!identity.equals(existing.get("_request"))) {
                    throw invalid("clientToken was already used with different parameters");
                }
                return startResponse(existing);
            }
        }
        if (!"Spark".equalsIgnoreCase(app.getType()) || request.path("jobDriver").has("hive")) {
            throw unsupported("Only Spark batch jobs are supported");
        }
        if (app.getArchitecture() != null && !Set.of("ARM64", "X86_64").contains(app.getArchitecture())) {
            throw invalid("Invalid application architecture");
        }
        if (app.getNetworkConfiguration() != null
                && ((app.getNetworkConfiguration().getSubnetIds() != null && !app.getNetworkConfiguration().getSubnetIds().isEmpty())
                || (app.getNetworkConfiguration().getSecurityGroupIds() != null
                && !app.getNetworkConfiguration().getSecurityGroupIds().isEmpty()))) {
            throw unsupported("VPC-attached Spark workers are not supported");
        }
        if (app.getWorkerTypeSpecifications() != null && !app.getWorkerTypeSpecifications().isEmpty()) {
            throw unsupported("Per-worker type specifications are not supported by the local Spark worker");
        }
        if (!"BATCH".equals(request.path("mode").asText("BATCH"))) {
            throw unsupported("Streaming jobs are not supported");
        }
        if (request.has("executionIamPolicy") || request.path("retryPolicy").path("maxAttempts").asInt(1) != 1
                || request.path("retryPolicy").has("maxFailedAttemptsPerHour")) {
            throw unsupported("Execution policy overrides and automatic retries are not supported");
        }
        if (request.path("configurationOverrides").size() > 0
                || (app.getRuntimeConfiguration() != null && !app.getRuntimeConfiguration().isEmpty())) {
            throw unsupported("Application and job configuration overrides are not supported by the local Spark worker");
        }
        if (request.has("executionTimeoutMinutes") && (!request.get("executionTimeoutMinutes").canConvertToInt()
                || !request.get("executionTimeoutMinutes").isIntegralNumber()
                || request.get("executionTimeoutMinutes").asInt() < 1)) {
            throw invalid("executionTimeoutMinutes must be a positive integer");
        }
        if (request.has("tags")) {
            if (!request.get("tags").isObject()) {
                throw invalid("tags must be an object");
            }
            request.get("tags").forEach(value -> {
                if (!value.isTextual()) {
                    throw invalid("Tag values must be strings");
                }
            });
        }
        runner.validate(request);
        validateRole(account, app.getArn(), role);
        if (allJobs().stream().filter(job -> !terminal(job)).count() >= QUEUE_LIMIT) {
            throw invalid("Local EMR Serverless queue capacity of " + QUEUE_LIMIT + " jobs has been reached");
        }
        long now = System.currentTimeMillis();
        ObjectNode job = mapper.createObjectNode();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        job.put("applicationId", app.getApplicationId()).put("jobRunId", id)
                .put("arn", app.getArn() + "/jobruns/" + id)
                .put("createdBy", creator(account, authorization))
                .put("createdAt", now / 1000.0).put("updatedAt", now / 1000.0)
                .put("executionRole", role).put("releaseLabel", app.getReleaseLabel())
                .put("state", "SUBMITTED").put("stateDetails", "Waiting for the local Spark worker")
                .put("mode", "BATCH").put("attempt", 1)
                .put("attemptCreatedAt", now / 1000.0).put("attemptUpdatedAt", now / 1000.0)
                .put("_accountId", account).put("_region", region).put("_clientToken", token)
                .put("_submittedAt", now).put("_sequence", allJobs().stream()
                        .mapToLong(value -> value.path("_sequence").asLong()).max().orElse(0) + 1)
                .put("_applicationArn", app.getArn())
                .put("_architecture", app.getArchitecture() == null ? "" : app.getArchitecture());
        job.put("_containerName", runner.containerName(job));
        job.set("_request", identity);
        job.set("jobDriver", request.get("jobDriver").deepCopy());
        for (String field : List.of("name", "tags", "executionTimeoutMinutes", "retryPolicy")) {
            if (request.has(field)) {
                job.set(field, request.get(field).deepCopy());
            }
        }
        if (app.getImageConfiguration() != null) {
            job.set("imageConfiguration", mapper.valueToTree(app.getImageConfiguration()));
        }
        save(job);
        return startResponse(job);
    }

    synchronized ObjectNode get(String account, String region, String appId, String jobId, String attempt) {
        ObjectNode job = requireJob(account, region, appId, jobId);
        if (attempt != null && !"1".equals(attempt)) {
            throw new AwsException("ResourceNotFoundException", "Job run attempt not found", 404);
        }
        return publicJob(job);
    }

    synchronized ObjectNode cancel(String account, String region, String appId, String jobId) {
        ObjectNode job = requireJob(account, region, appId, jobId);
        if ("CANCELLED".equals(job.path("state").asText())) {
            return startResponse(job).without("arn");
        }
        if (terminal(job)) {
            throw invalid("A completed job run cannot be cancelled");
        }
        if ("SUBMITTED".equals(job.path("state").asText())) {
            finish(job, "CANCELLED", "Cancelled before the worker was allocated");
        } else {
            job.put("_recoveryState", "CANCELLED").put("_cleanupAttempts", 0).put("_cleanupAfter", 0);
            transition(job, "CANCELLING", "Cancellation requested; waiting for worker removal");
            if (activeJob != null && job.path("arn").equals(activeJob.path("arn"))) {
                active.stop(false);
            }
        }
        return startResponse(job).without("arn");
    }

    synchronized PaginatedResult<ObjectNode> list(String account, String region, String appId,
                                                  Integer maxResults, String nextToken, List<String> states,
                                                  Double after, Double before, String mode) {
        if (states != null && !STATES.containsAll(states)) {
            throw invalid("Invalid job run state");
        }
        List<ObjectNode> selected = inApplication(account, region, appId).stream()
                .filter(job -> states == null || states.isEmpty() || states.contains(job.path("state").asText()))
                .filter(job -> after == null || job.path("createdAt").asDouble() > after)
                .filter(job -> before == null || job.path("createdAt").asDouble() < before)
                .filter(job -> mode == null || mode.equals(job.path("mode").asText()))
                .map(this::summary).toList();
        return Pagination.paginate(selected, job -> job.path("id").asText(), maxResults, nextToken, 50, "ValidationException");
    }

    synchronized PaginatedResult<ObjectNode> attempts(String account, String region, String appId, String jobId,
                                                      Integer maxResults, String nextToken) {
        ObjectNode job = requireJob(account, region, appId, jobId);
        ObjectNode attempt = summary(job);
        attempt.set("jobCreatedAt", job.get("createdAt"));
        attempt.put("type", "Spark");
        return Pagination.paginate(List.of(attempt), value -> "1", maxResults, nextToken, 50, "ValidationException");
    }

    synchronized void requireIdle(String account, String region, String appId) {
        if (inApplication(account, region, appId).stream().anyMatch(job -> !terminal(job))) {
            throw invalid("Application has active job runs; cancel them and wait for worker termination first");
        }
    }

    synchronized void deleteApplicationJobs(String account, String region, String appId) {
        requireIdle(account, region, appId);
        for (ObjectNode job : inApplication(account, region, appId)) {
            jobs.deleteForAccount(account, key(job));
        }
    }

    void dispatch() {
        synchronized (this) {
            if (stopping) {
                return;
            }
            for (ObjectNode job : allJobs()) {
                if ("SUBMITTED".equals(job.path("state").asText())
                        && System.currentTimeMillis() - job.path("_submittedAt").asLong() > TimeUnit.MINUTES.toMillis(5)) {
                    finish(job, "FAILED", "Local worker queue deadline exceeded");
                }
            }
            if (active != null) {
                if (System.currentTimeMillis() >= activeJob.path("_deadline").asLong()) {
                    active.stop(true);
                }
                return;
            }
            List<ObjectNode> pending = allJobs().stream().filter(job -> !terminal(job))
                    .sorted(Comparator.comparingLong(job -> job.path("_sequence").asLong())).toList();
            // Interrupted workers are removed before another job is admitted to the single worker slot.
            for (ObjectNode job : pending) {
                if (!"SUBMITTED".equals(job.path("state").asText())) {
                    recover(job);
                    return;
                }
            }
            if (pending.isEmpty()) {
                return;
            }
            ObjectNode job = pending.getFirst();
            long now = System.currentTimeMillis();
            if (now - job.path("_submittedAt").asLong() > TimeUnit.MINUTES.toMillis(5)) {
                finish(job, "FAILED", "Local worker queue deadline exceeded");
                return;
            }
            long duration = Math.min(timeoutSeconds * 1000L,
                    job.path("executionTimeoutMinutes").asLong(15) * 60_000L);
            job.put("_deadline", now + duration).put("queuedDurationMilliseconds", now - job.path("_submittedAt").asLong());
            transition(job, "SCHEDULED", "Provisioning the local Spark worker");
            activeJob = job;
            active = new EmrServerlessSparkRunner.Execution();
            EmrServerlessSparkRunner.Execution execution = active;
            Thread thread = Thread.ofVirtual().unstarted(() -> execute(job, execution));
            execution.thread = thread;
            thread.start();
        }
    }

    private void execute(ObjectNode job, EmrServerlessSparkRunner.Execution execution) {
        boolean executionAttempted = false;
        try {
            execution.check();
            validateRole(job.path("_accountId").asText(), job.path("_applicationArn").asText(), job.path("executionRole").asText());
            ObjectNode snapshot;
            synchronized (this) {
                snapshot = job.deepCopy();
            }
            executionAttempted = true;
            EmrServerlessSparkRunner.Result result = runner.run(snapshot, execution, containerId -> {
                synchronized (this) {
                    job.put("_containerId", containerId);
                    save(job);
                }
            }, () -> {
                synchronized (this) {
                    job.put("startedAt", System.currentTimeMillis() / 1000.0);
                    if (!execution.cancelled.get() && !execution.timedOut.get()) {
                        transition(job, "RUNNING", "Spark worker is running");
                    } else {
                        save(job);
                    }
                }
            });
            synchronized (this) {
                finish(job, execution.cancelled.get() ? "CANCELLED"
                                : execution.timedOut.get() || result.exitCode() != 0 ? "FAILED" : "SUCCESS",
                        execution.cancelled.get() ? "Cancelled; Spark worker removed"
                                : execution.timedOut.get() ? "Local Spark execution deadline exceeded; worker removed" : result.details());
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                finish(job, execution.cancelled.get() ? "CANCELLED" : "FAILED", "Stopped before worker creation");
            }
        } catch (Exception e) {
            synchronized (this) {
                if (!executionAttempted) {
                    finish(job, execution.cancelled.get() ? "CANCELLED" : "FAILED", e.getMessage());
                } else {
                    job.put("_recoveryState", execution.cancelled.get() ? "CANCELLED" : "FAILED");
                    transition(job, "CANCELLING", "Worker cleanup requires retry: " + e.getMessage());
                }
            }
        } finally {
            synchronized (this) {
                active = null;
                activeJob = null;
            }
        }
    }

    private void recover(ObjectNode job) {
        int attempts = job.path("_cleanupAttempts").asInt();
        if (attempts >= 8 || System.currentTimeMillis() < job.path("_cleanupAfter").asLong()) {
            return;
        }
        if (!job.has("_recoveryState")) {
            job.put("_recoveryState", "CANCELLING".equals(job.path("state").asText()) ? "CANCELLED" : "FAILED");
            save(job);
        }
        try {
            runner.cleanup(job);
            finish(job, job.path("_recoveryState").asText(), "Interrupted worker removed; execution was not replayed");
        } catch (Exception e) {
            job.put("_cleanupAttempts", attempts + 1).put("_cleanupAfter", System.currentTimeMillis() + 5000);
            transition(job, "CANCELLING", "Worker removal failed; operator cleanup required after 8 attempts: " + e.getMessage());
        }
    }

    @Override
    public void stopManagedContainers() {
        Thread worker;
        synchronized (this) {
            stopping = true;
            if (dispatcher != null) {
                dispatcher.shutdownNow();
                dispatcher = null;
            }
            if (active != null) {
                active.stop(false);
            }
            worker = active == null ? null : active.thread;
        }
        if (worker != null) {
            try {
                worker.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                throw new IllegalStateException("EMR Serverless worker did not stop; retaining job state");
            }
        }
        synchronized (this) {
            for (ObjectNode job : allJobs()) {
                if (!terminal(job) && !"SUBMITTED".equals(job.path("state").asText())) {
                    runner.cleanup(job);
                    finish(job, "CANCELLED", "Worker removed during emulator shutdown");
                }
            }
        }
    }

    @PreDestroy
    void close() {
        stopManagedContainers();
    }

    @Override
    public void beforeReset() {
        stopManagedContainers();
    }

    @Override
    public synchronized void clear() {
        if (active != null) {
            throw new IllegalStateException("Cannot reset EMR Serverless while a worker remains active");
        }
    }

    @Override
    public void afterReset() {
        startDispatcher();
    }

    void validateRole(String account, String applicationArn, String arn) {
        String[] parts = arn.split(":", 6);
        if (parts.length != 6 || !"iam".equals(parts[2]) || !parts[3].isEmpty()
                || !account.equals(parts[4]) || !parts[5].startsWith("role/")) {
            throw invalid("executionRoleArn must name an IAM role in the application's account");
        }
        IamRole role = iam.findRole(account, arn.substring(arn.lastIndexOf('/') + 1))
                .filter(value -> arn.equals(value.getArn()))
                .orElseThrow(() -> invalid("The execution role does not exist"));
        try {
            JsonNode trust = mapper.readTree(role.getAssumeRolePolicyDocument());
            if (trust == null || !trust.isObject()) {
                throw invalid("EMR Serverless cannot assume the execution role");
            }
            JsonNode statements = trust.path("Statement");
            List<JsonNode> entries = new ArrayList<>();
            if (statements.isArray()) {
                statements.forEach(entries::add);
            } else if (statements.isObject()) {
                entries.add(statements);
            }
            for (JsonNode entry : entries) {
                if (entry.isObject() && !entry.has("Resource") && !entry.has("NotResource")) {
                    ((ObjectNode) entry).put("Resource", arn);
                }
            }
            if (policies.evaluateResourcePolicy(List.of(trust.toString()), "emr-serverless.amazonaws.com",
                    "sts:AssumeRole", arn, Map.of("aws:SourceAccount", List.of(account),
                            "aws:SourceArn", List.of(applicationArn))) != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW) {
                throw invalid("EMR Serverless cannot assume the execution role");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid("EMR Serverless cannot assume the execution role");
        }
    }

    String creator(String account, String authorization) {
        if (authorization != null && authorization.contains("Credential=")) {
            String key = authorization.substring(authorization.indexOf("Credential=") + 11).split("/", 2)[0];
            CallerContext caller = iam.resolveCallerContext(key);
            if (caller != null && caller.principalArn() != null) {
                return caller.principalArn();
            }
        }
        return "arn:aws:iam::" + account + ":root";
    }

    private ObjectNode requireJob(String account, String region, String appId, String jobId) {
        return jobs.getForAccount(account, region + "/" + appId + "/" + jobId)
                .filter(job -> account.equals(job.path("_accountId").asText()) && region.equals(job.path("_region").asText()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Job run not found", 404));
    }

    private List<ObjectNode> inApplication(String account, String region, String appId) {
        return jobs.scanForAccount(account, key -> key.startsWith(region + "/" + appId + "/"));
    }

    private List<ObjectNode> allJobs() {
        return jobs.scanAllAccountEntries(key -> true).stream()
                .filter(entry -> entry.accountId().equals(entry.value().path("_accountId").asText())
                        && entry.key().equals(key(entry.value())))
                .map(AccountAwareStorageBackend.AccountEntry::value).toList();
    }

    private static String key(JsonNode job) {
        return job.path("_region").asText() + "/" + job.path("applicationId").asText() + "/" + job.path("jobRunId").asText();
    }

    private void save(ObjectNode job) {
        jobs.putForAccount(job.path("_accountId").asText(), key(job), job);
    }

    private void transition(ObjectNode job, String state, String details) {
        job.put("state", state).put("stateDetails", details)
                .put("updatedAt", System.currentTimeMillis() / 1000.0)
                .put("attemptUpdatedAt", System.currentTimeMillis() / 1000.0);
        save(job);
    }

    private void finish(ObjectNode job, String state, String details) {
        job.put("endedAt", System.currentTimeMillis() / 1000.0);
        if (job.has("startedAt")) {
            job.put("totalExecutionDurationSeconds", Math.max(0,
                    job.path("endedAt").asDouble() - job.path("startedAt").asDouble()));
        }
        transition(job, state, details);
    }

    static boolean terminal(JsonNode job) {
        return TERMINAL.contains(job.path("state").asText());
    }

    private ObjectNode publicJob(ObjectNode job) {
        ObjectNode result = job.deepCopy();
        List<String> internal = new ArrayList<>();
        result.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("_")) {
                internal.add(name);
            }
        });
        result.remove(internal);
        return result;
    }

    private ObjectNode summary(ObjectNode job) {
        ObjectNode result = publicJob(job);
        result.set("id", result.remove("jobRunId"));
        return result;
    }

    private ObjectNode startResponse(ObjectNode job) {
        return mapper.createObjectNode().put("applicationId", job.path("applicationId").asText())
                .put("jobRunId", job.path("jobRunId").asText()).put("arn", job.path("arn").asText());
    }

    static String requireText(JsonNode request, String field) {
        if (request == null || !request.isObject() || !request.path(field).isTextual() || request.path(field).asText().isBlank()) {
            throw invalid(field + " is required");
        }
        return request.path(field).asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException unsupported(String message) {
        return new AwsException("UnsupportedOperationException", message, 501);
    }
}
