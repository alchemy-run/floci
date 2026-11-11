package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.emrserverless.model.JobDriver;
import io.floci.aws.services.eventbridge.EventBridgeService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.emrcontainers.EmrContainersStore.JOB_RUN;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.VIRTUAL_CLUSTER;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.invalid;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.meta;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.notFound;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.now;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.optional;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.text;
import static io.floci.aws.services.emrcontainers.EmrContainersStore.view;

/**
 * EMR on EKS job runs on a virtual cluster: started from a request or a job template, queued by the virtual
 * cluster's scheduler limits, retried by their retry policy, cancelled, and announced as {@code aws.emr-containers}
 * {@code EMR Job Run State Change} events as each changes state.
 *
 * <p>In the {@code immediate} runner mode a run goes PENDING, SUBMITTED, RUNNING and COMPLETED, a transition
 * apart. In the {@code docker} mode a {@code spark-submit} run's driver runs in a container of the release's EMR
 * image through EMR Serverless's runner, with the run's execution role, its {@code spark-defaults}, and its driver
 * logs in its S3 and CloudWatch monitoring locations; its exit decides how it ends.
 */
@ApplicationScoped
public class EmrContainersJobRuns {

    private static final Logger LOG = Logger.getLogger(EmrContainersJobRuns.class);
    private static final String RUNNER = "emr-containers";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> ACTIVE = Set.of("SUBMITTED", "RUNNING", "CANCEL_PENDING");
    private static final String DRIVER_ENV_PREFIX = "spark.kubernetes.driverEnv.";
    private static final String CONTAINER_IMAGE = "spark.kubernetes.container.image";

    private final EmrContainersStore store;
    private final EmrContainersService service;
    private final Instance<EventBridgeService> events;
    private final SparkJobRunner runner;
    private final long transitionMillis;
    private final String imageTemplate;
    private final String dockerNetwork;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("emr-containers-runs").factory());

    @Inject
    public EmrContainersJobRuns(EmrContainersStore store, EmrContainersService service,
                                Instance<EventBridgeService> events, EmrServerlessSparkRunner sparkRunner,
                                EmulatorConfig config) {
        this.store = store;
        this.service = service;
        this.events = events;
        this.runner = "docker".equalsIgnoreCase(config.services().emrContainers().runnerMode()) ? sparkRunner : null;
        this.transitionMillis = config.services().emrContainers().transitionMillis();
        this.imageTemplate = config.services().emrContainers().imageTemplate().orElse(null);
        this.dockerNetwork = config.services().emrContainers().dockerNetwork().orElse(null);
        // Before any run of this process starts: a driver an earlier one left belongs to no run that goes on.
        if (runner != null) {
            runner.removeLeftoverDrivers(RUNNER);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- operations

    public synchronized ObjectNode startJobRun(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        ObjectNode vc = store.get(region, VIRTUAL_CLUSTER, vcId).orElseThrow(() -> invalid(
                "Virtual cluster " + vcId + " doesn't exist."));
        if (!"RUNNING".equals(service.state(region, vc))) {
            throw invalid("Virtual cluster " + vcId + " is not in RUNNING state.");
        }
        String token = text(input, "clientToken");
        for (ObjectNode existing : runs(region, vcId)) {
            if (token.equals(existing.path("clientToken").asText())) {
                return identity(existing);
            }
        }
        ObjectNode data = store.mapper().createObjectNode();
        String templateId = optional(input, "jobTemplateId");
        if (templateId != null) {
            data = service.resolveTemplate(service.jobTemplate(region, templateId), input.get("jobTemplateParameters"));
        } else if (input.hasNonNull("jobTemplateParameters") && !input.get("jobTemplateParameters").isEmpty()) {
            throw invalid("jobTemplateParameters can only be given with a jobTemplateId.");
        }
        String role = service.executionRole(first(input, data, "executionRoleArn"));
        String release = EmrContainersService.releaseLabel(first(input, data, "releaseLabel"));
        JsonNode driver = input.hasNonNull("jobDriver") ? input.get("jobDriver") : data.get("jobDriver");
        if (driver == null) {
            throw invalid("jobDriver is required.");
        }
        EmrContainersService.driver(driver);
        JsonNode overrides = input.hasNonNull("configurationOverrides") ? input.get("configurationOverrides")
                : data.get("configurationOverrides");
        int maxAttempts = input.path("retryPolicyConfiguration").path("maxAttempts").asInt(0);
        if (input.has("retryPolicyConfiguration") && (maxAttempts < 1 || maxAttempts > 10)) {
            throw invalid("retryPolicyConfiguration.maxAttempts must be between 1 and 10.");
        }
        JsonNode limits = vc.path("schedulerConfiguration");
        if (limits.has("maxInQueueJobRuns") && queued(region, vcId) >= limits.get("maxInQueueJobRuns").asInt()) {
            throw invalid("Virtual cluster " + vcId + " has reached its maximum number of queued job runs.");
        }
        String id = store.id(19);
        ObjectNode run = store.record();
        run.put("id", id);
        String name = optional(input, "name");
        if (name != null) {
            run.put("name", name);
        }
        run.put("virtualClusterId", vcId).put("arn", store.arn(region, "virtualclusters/" + vcId + "/jobruns/" + id))
                .put("state", "PENDING").put("clientToken", token).put("executionRoleArn", role)
                .put("releaseLabel", release);
        if (overrides != null) {
            run.set("configurationOverrides", overrides.deepCopy());
        }
        run.set("jobDriver", driver.deepCopy());
        run.put("createdAt", now()).put("createdBy", service.caller(region));
        ObjectNode tags = service.tags(data.get("jobTags"));
        input.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        run.set("tags", tags);
        if (maxAttempts > 0) {
            run.putObject("retryPolicyConfiguration").put("maxAttempts", maxAttempts);
            run.putObject("retryPolicyExecution").put("currentAttemptCount", 1);
        }
        if (templateId != null) {
            meta(run).put("jobTemplateId", templateId);
        }
        save(region, run);
        announce(region, run);
        advance(region, vcId, id);
        return identity(run);
    }

    public synchronized ObjectNode describeJobRun(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        String id = text(input, "id");
        if (store.get(region, VIRTUAL_CLUSTER, vcId).isEmpty()) {
            throw invalid("Virtual cluster " + vcId + " doesn't exist.");
        }
        ObjectNode run = store.get(region, JOB_RUN, vcId + "/" + id).orElseThrow(() -> notFound(
                "Job run " + id + " doesn't exist."));
        ObjectNode response = store.mapper().createObjectNode();
        response.set("jobRun", view(run));
        return response;
    }

    public synchronized ObjectNode listJobRuns(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        String name = optional(input, "name");
        Set<String> states = new LinkedHashSet<>();
        if (input.has("states")) {
            input.get("states").forEach(state -> states.add(state.asText().toUpperCase(Locale.ROOT)));
        }
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> runs = new ArrayList<>(runs(region, vcId));
        runs.sort(Comparator.comparingDouble((ObjectNode run) -> run.path("createdAt").asDouble()).reversed());
        for (ObjectNode run : runs) {
            if ((states.isEmpty() || states.contains(run.path("state").asText()))
                    && (name == null || name.equals(run.path("name").asText(null)))
                    && EmrContainersStore.createdWithin(run, input)) {
                items.add(view(run));
            }
        }
        return store.page(items, input, "jobRuns");
    }

    public synchronized ObjectNode cancelJobRun(JsonNode input, String region) {
        String vcId = text(input, "virtualClusterId");
        String id = text(input, "id");
        if (store.get(region, VIRTUAL_CLUSTER, vcId).isEmpty()) {
            throw invalid("Virtual cluster " + vcId + " doesn't exist.");
        }
        ObjectNode run = store.get(region, JOB_RUN, vcId + "/" + id).orElseThrow(() -> invalid(
                "Job run " + id + " doesn't exist."));
        String state = run.path("state").asText();
        if (TERMINAL.contains(state) || "CANCEL_PENDING".equals(state)) {
            throw invalid("Job run " + id + " is in " + state + " state and cannot be cancelled.");
        }
        cancel(region, run, "Job run was cancelled.");
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", id).put("virtualClusterId", vcId);
        return response;
    }

    /** Cancels every run of a virtual cluster that has not ended, as deleting the virtual cluster does. */
    synchronized void cancelAll(String region, String vcId) {
        for (ObjectNode run : runs(region, vcId)) {
            String state = run.path("state").asText();
            if (!TERMINAL.contains(state) && !"CANCEL_PENDING".equals(state)) {
                cancel(region, run, "The virtual cluster was deleted.");
            }
        }
    }

    /** How many of a virtual cluster's runs wait in its queue and how many run. */
    synchronized ObjectNode schedulerStatus(String region, String vcId) {
        int running = 0;
        for (ObjectNode run : runs(region, vcId)) {
            running += ACTIVE.contains(run.path("state").asText()) ? 1 : 0;
        }
        ObjectNode status = store.mapper().createObjectNode();
        status.put("currentInQueueJobRuns", queued(region, vcId)).put("currentConcurrentJobRuns", running);
        return status;
    }

    // ---------------------------------------------------------------- state machine

    /**
     * Moves a run on from its state: a PENDING run is submitted when its virtual cluster has room; in the
     * immediate mode a SUBMITTED run then runs and a RUNNING one completes, a transition apart, and a
     * CANCEL_PENDING run is cancelled; in the docker mode its driver decides.
     */
    private synchronized void advance(String region, String vcId, String id) {
        ObjectNode run = store.get(region, JOB_RUN, vcId + "/" + id).orElse(null);
        if (run == null) {
            return;
        }
        switch (run.path("state").asText()) {
            case "PENDING" -> {
                if (!hasRoom(region, vcId)) {
                    return;
                }
                transition(region, run, "SUBMITTED");
                if (runner != null) {
                    launch(region, run);
                } else {
                    later(region, vcId, id);
                }
            }
            case "SUBMITTED" -> {
                if (runner == null) {
                    transition(region, run, "RUNNING");
                    later(region, vcId, id);
                }
            }
            case "RUNNING" -> {
                if (runner == null) {
                    finish(region, run, "COMPLETED", null, null);
                }
            }
            case "CANCEL_PENDING" -> {
                if (runner == null) {
                    finish(region, run, "CANCELLED", null, run.path("stateDetails").asText(null));
                }
            }
            default -> {
            }
        }
    }

    /** Advances a run once a transition has passed: at once when transitions take no time. */
    private void later(String region, String vcId, String id) {
        String account = store.account();
        if (transitionMillis <= 0) {
            advance(region, vcId, id);
            return;
        }
        scheduler.schedule(() -> RequestScopes.runAs(account, region, () -> advance(region, vcId, id)),
                transitionMillis, TimeUnit.MILLISECONDS);
    }

    private void cancel(String region, ObjectNode run, String details) {
        String vcId = run.path("virtualClusterId").asText();
        String id = run.path("id").asText();
        if ("PENDING".equals(run.path("state").asText())) {
            finish(region, run, "CANCELLED", null, details);
            return;
        }
        run.put("stateDetails", details);
        transition(region, run, "CANCEL_PENDING");
        if (runner != null) {
            runner.stop(id);
        } else {
            later(region, vcId, id);
        }
    }

    private void transition(String region, ObjectNode run, String state) {
        run.put("state", state);
        save(region, run);
        announce(region, run);
    }

    /** Ends a run, then submits the next run its virtual cluster's queue holds, if any. */
    private void finish(String region, ObjectNode run, String state, String failureReason, String details) {
        run.put("state", state).put("finishedAt", now());
        if (failureReason != null) {
            run.put("failureReason", failureReason);
        }
        if (details != null) {
            run.put("stateDetails", details);
        }
        save(region, run);
        announce(region, run);
        String vcId = run.path("virtualClusterId").asText();
        for (ObjectNode queued : runs(region, vcId)) {
            if ("PENDING".equals(queued.path("state").asText())) {
                advance(region, vcId, queued.path("id").asText());
            }
        }
    }

    private boolean hasRoom(String region, String vcId) {
        JsonNode limits = store.get(region, VIRTUAL_CLUSTER, vcId).map(vc -> vc.path("schedulerConfiguration"))
                .orElse(store.mapper().createObjectNode());
        if (!limits.has("maxConcurrentJobRuns")) {
            return true;
        }
        int running = 0;
        for (ObjectNode run : runs(region, vcId)) {
            running += ACTIVE.contains(run.path("state").asText()) ? 1 : 0;
        }
        return running < limits.get("maxConcurrentJobRuns").asInt();
    }

    private int queued(String region, String vcId) {
        int queued = 0;
        for (ObjectNode run : runs(region, vcId)) {
            queued += "PENDING".equals(run.path("state").asText()) ? 1 : 0;
        }
        return queued;
    }

    // ---------------------------------------------------------------- docker runner

    /** Runs a submitted run's driver on a thread of its own, which records how it ends. */
    private void launch(String region, ObjectNode run) {
        String vcId = run.path("virtualClusterId").asText();
        String id = run.path("id").asText();
        JsonNode driver = run.path("jobDriver");
        if (!driver.hasNonNull("sparkSubmitJobDriver")) {
            finish(region, run, "FAILED", "VALIDATION_ERROR",
                    "floci runs sparkSubmitJobDriver job runs in containers; use the immediate runner mode for "
                            + "Spark SQL job drivers.");
            return;
        }
        SparkJobRunner.Job job = job(region, run);
        String account = store.account();
        Thread.ofPlatform().daemon().name("emr-containers-run-" + id).start(() -> {
            SparkJobRunner.Outcome outcome;
            try {
                outcome = runner.run(job, new Progress(account, region, vcId, id));
            } catch (RuntimeException e) {
                LOG.warnv(e, "EMR on EKS job run {0} could not run", id);
                outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage());
            }
            SparkJobRunner.Outcome ended = outcome;
            RequestScopes.runAs(account, region, () -> ended(region, vcId, id, ended));
        });
    }

    private synchronized void ended(String region, String vcId, String id, SparkJobRunner.Outcome outcome) {
        ObjectNode run = store.get(region, JOB_RUN, vcId + "/" + id).orElse(null);
        if (run == null || TERMINAL.contains(run.path("state").asText())) {
            return;
        }
        if (outcome.stopped() || "CANCEL_PENDING".equals(run.path("state").asText())) {
            finish(region, run, "CANCELLED", null, run.path("stateDetails").asText("Job run was cancelled."));
            return;
        }
        if (outcome.exitCode() != null && outcome.exitCode() == 0) {
            finish(region, run, "COMPLETED", null, null);
            return;
        }
        JsonNode retry = run.path("retryPolicyConfiguration");
        ObjectNode execution = run.withObject("/retryPolicyExecution");
        int attempt = execution.path("currentAttemptCount").asInt(1);
        if (retry.has("maxAttempts") && attempt < retry.get("maxAttempts").asInt()) {
            execution.put("currentAttemptCount", attempt + 1);
            transition(region, run, "SUBMITTED");
            launch(region, run);
            return;
        }
        if (!retry.has("maxAttempts")) {
            run.remove("retryPolicyExecution");
        }
        if (outcome.failure() != null) {
            finish(region, run, "FAILED", "INTERNAL_ERROR", outcome.failure());
        } else {
            finish(region, run, "FAILED", "USER_ERROR", "Job's driver exited with code " + outcome.exitCode() + ".");
        }
    }

    /** What a run tells the job runs while its driver goes. */
    private final class Progress implements SparkJobRunner.Progress {
        private final String account;
        private final String region;
        private final String vcId;
        private final String id;

        Progress(String account, String region, String vcId, String id) {
            this.account = account;
            this.region = region;
            this.vcId = vcId;
            this.id = id;
        }

        @Override
        public void started() {
            RequestScopes.runAs(account, region, () -> {
                synchronized (EmrContainersJobRuns.this) {
                    store.get(region, JOB_RUN, vcId + "/" + id).filter(run -> "SUBMITTED".equals(run.path("state")
                            .asText())).ifPresent(run -> transition(region, run, "RUNNING"));
                }
            });
        }

        @Override
        public void timedOut() {
        }

        @Override
        public boolean cancelled() {
            boolean[] cancelled = {true};
            RequestScopes.runAs(account, region, () -> cancelled[0] = store.get(region, JOB_RUN, vcId + "/" + id)
                    .map(run -> "CANCEL_PENDING".equals(run.path("state").asText())
                            || TERMINAL.contains(run.path("state").asText())).orElse(true));
            return cancelled[0];
        }
    }

    /** A run's driver as EMR Serverless's runner takes it. */
    private SparkJobRunner.Job job(String region, ObjectNode run) {
        String vcId = run.path("virtualClusterId").asText();
        String id = run.path("id").asText();
        JsonNode submit = run.path("jobDriver").path("sparkSubmitJobDriver");
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint(submit.path("entryPoint").asText());
        List<String> arguments = new ArrayList<>();
        submit.path("entryPointArguments").forEach(argument -> arguments.add(argument.asText()));
        sparkSubmit.setEntryPointArguments(arguments);
        sparkSubmit.setSparkSubmitParameters(submit.path("sparkSubmitParameters").asText(null));
        Map<String, String> defaults = new LinkedHashMap<>();
        for (JsonNode classification : run.path("configurationOverrides").path("applicationConfiguration")) {
            if ("spark-defaults".equals(classification.path("classification").asText())) {
                classification.path("properties").fields().forEachRemaining(entry ->
                        defaults.put(entry.getKey(), entry.getValue().asText()));
            }
        }
        Map<String, String> properties = EmrServerlessJobRunService.sparkProperties(defaults, sparkSubmit);
        Map<String, String> environment = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (key.startsWith(DRIVER_ENV_PREFIX) && key.length() > DRIVER_ENV_PREFIX.length()) {
                environment.put(key.substring(DRIVER_ENV_PREFIX.length()), value);
            }
        });
        JsonNode monitoring = run.path("configurationOverrides").path("monitoringConfiguration");
        String driver = vcId + "/jobs/" + id + "/containers/spark-" + id + "/spark-" + id + "-driver";
        String logFolder = EmrServerlessSparkRunner.logFolder(monitoring.path("s3MonitoringConfiguration")
                .path("logUri").asText(null), driver + "/");
        SparkJobRunner.CloudWatchLogs cloudWatch = null;
        JsonNode cloudWatchConfiguration = monitoring.path("cloudWatchMonitoringConfiguration");
        if (cloudWatchConfiguration.hasNonNull("logGroupName")) {
            String prefix = cloudWatchConfiguration.path("logStreamNamePrefix").asText("");
            String stream = (prefix.isEmpty() ? "" : prefix + "/") + driver;
            cloudWatch = new SparkJobRunner.CloudWatchLogs(cloudWatchConfiguration.get("logGroupName").asText(),
                    stream + "/stdout", stream + "/stderr");
        }
        String release = run.path("releaseLabel").asText().replaceFirst("-(latest|\\d{8})$", "");
        String arn = run.path("arn").asText();
        String account = AwsArnUtils.parse(arn).accountId();
        return new SparkJobRunner.Job(RUNNER, id, account, region, release, SparkJobRunner.Engine.SPARK,
                imageTemplate, properties.get(CONTAINER_IMAGE), dockerNetwork, run.path("executionRoleArn").asText(),
                defaults, EmrServerlessJobRunService.application(sparkSubmit), null, Duration.ZERO, logFolder,
                cloudWatch, environment, EmrServerlessJobRunService.archives(properties), null, Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of());
    }

    // ---------------------------------------------------------------- shared

    private List<ObjectNode> runs(String region, String vcId) {
        return store.list(region, JOB_RUN, vcId + "/", run -> true);
    }

    private void save(String region, ObjectNode run) {
        store.put(region, JOB_RUN, run.path("virtualClusterId").asText() + "/" + run.path("id").asText(), run);
    }

    private static String first(JsonNode input, JsonNode template, String field) {
        String value = optional(input, field);
        if (value == null) {
            value = optional(template, field);
        }
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private ObjectNode identity(ObjectNode run) {
        ObjectNode response = store.mapper().createObjectNode();
        response.put("id", run.path("id").asText());
        if (run.has("name")) {
            response.put("name", run.path("name").asText());
        }
        response.put("arn", run.path("arn").asText()).put("virtualClusterId", run.path("virtualClusterId").asText());
        return response;
    }

    /** Publishes an {@code EMR Job Run State Change} event for a run's state. */
    private void announce(String region, ObjectNode run) {
        if (!events.isResolvable()) {
            return;
        }
        String state = run.path("state").asText();
        ObjectNode detail = store.mapper().createObjectNode();
        detail.put("severity", "FAILED".equals(state) ? "ERROR" : "INFO");
        if (run.has("name")) {
            detail.put("name", run.path("name").asText());
        }
        detail.put("id", run.path("id").asText()).put("arn", run.path("arn").asText())
                .put("virtualClusterId", run.path("virtualClusterId").asText()).put("state", state)
                .put("createdBy", run.path("createdBy").asText())
                .put("releaseLabel", run.path("releaseLabel").asText())
                .put("executionRoleArn", run.path("executionRoleArn").asText());
        if (run.has("failureReason")) {
            detail.put("failureReason", run.path("failureReason").asText());
        }
        if (run.has("stateDetails")) {
            detail.put("stateDetails", run.path("stateDetails").asText());
        }
        try {
            events.get().publishServiceEvent("aws.emr-containers", "EMR Job Run State Change", detail.toString(),
                    run.path("arn").asText(), region);
        } catch (AwsException e) {
            LOG.debugv("Could not publish the state change of job run {0}: {1}", run.path("id").asText(),
                    e.getMessage());
        }
    }
}
