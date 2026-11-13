package io.floci.aws.services.deadline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.floci.aws.services.deadline.DeadlineStore.conflict;
import static io.floci.aws.services.deadline.DeadlineStore.invalid;
import static io.floci.aws.services.deadline.DeadlineStore.notFound;
import static io.floci.aws.services.deadline.DeadlineStore.now;
import static io.floci.aws.services.deadline.DeadlineStore.oneOf;
import static io.floci.aws.services.deadline.DeadlineStore.optional;
import static io.floci.aws.services.deadline.DeadlineStore.present;
import static io.floci.aws.services.deadline.DeadlineStore.text;

/**
 * Deadline Cloud jobs: a job is submitted from an Open Job Description template, expands into steps and each step
 * into tasks; task run statuses roll up into their step's and job's, which publish {@code aws.deadline} events as
 * they change. Jobs, steps and tasks are reprioritized, requeued, suspended, canceled and archived through their
 * update operations, searched across queues, and their sessions and session actions (created by workers, see
 * {@link DeadlineWorkers}) read back. Session statistics aggregate a farm's session runtime by queue, fleet, job,
 * user and period.
 */
@ApplicationScoped
public class DeadlineJobs {

    static final String JOBS = DeadlineService.JOBS;
    static final String STEPS = "steps";
    static final String TASKS = "tasks";
    static final String SESSIONS = "sessions";
    static final String ACTIONS = "session-actions";
    static final String AGGREGATIONS = "statistics-aggregations";
    static final List<String> RUN_STATUSES = List.of("PENDING", "READY", "ASSIGNED", "STARTING", "SCHEDULED",
            "INTERRUPTING", "RUNNING", "SUSPENDED", "CANCELED", "FAILED", "SUCCEEDED", "NOT_COMPATIBLE");
    static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");
    static final Set<String> IN_FLIGHT = Set.of("ASSIGNED", "STARTING", "SCHEDULED", "RUNNING", "INTERRUPTING");
    private static final Set<String> TARGETS = Set.of("READY", "FAILED", "SUCCEEDED", "CANCELED", "SUSPENDED",
            "PENDING");

    private final DeadlineStore store;
    private final DeadlineService farms;
    private final Instance<S3Service> s3;
    private final DeadlineTemplates templates;

    @Inject
    public DeadlineJobs(DeadlineStore store, DeadlineService farms, Instance<S3Service> s3) {
        this.store = store;
        this.farms = farms;
        this.s3 = s3;
        this.templates = new DeadlineTemplates(store.mapper());
    }

    DeadlineTemplates templates() {
        return templates;
    }

    // ---------------------------------------------------------------- lookups

    ObjectNode job(String region, String farmId, String queueId, String jobId) {
        farms.queue(region, farmId, queueId);
        ObjectNode job = store.require(region, JOBS, jobId, "Job");
        if (!queueId.equals(job.path("_").path("queueId").asText())) {
            throw notFound("Job " + jobId + " not found.", JOBS, jobId);
        }
        settle(region, job);
        return job;
    }

    ObjectNode step(String region, ObjectNode job, String stepId) {
        ObjectNode step = store.require(region, STEPS, stepId, "Step");
        if (!job.path("jobId").asText().equals(step.path("_").path("jobId").asText())) {
            throw notFound("Step " + stepId + " not found.", STEPS, stepId);
        }
        return step;
    }

    ObjectNode task(String region, ObjectNode step, String taskId) {
        ObjectNode task = store.require(region, TASKS, taskId, "Task");
        if (!step.path("stepId").asText().equals(task.path("_").path("stepId").asText())) {
            throw notFound("Task " + taskId + " not found.", TASKS, taskId);
        }
        return task;
    }

    List<ObjectNode> steps(String region, String jobId) {
        List<ObjectNode> steps = store.children(region, STEPS, "jobId", jobId);
        steps.sort(Comparator.comparing(step -> step.path("_").path("index").asInt()));
        return steps;
    }

    List<ObjectNode> tasks(String region, String stepId) {
        List<ObjectNode> tasks = store.children(region, TASKS, "stepId", stepId);
        tasks.sort(Comparator.comparing(task -> task.path("_").path("index").asInt()));
        return tasks;
    }

    private ObjectNode job(String region, ObjectNode input) {
        return job(region, text(input, "farmId"), text(input, "queueId"), text(input, "jobId"));
    }

    // ---------------------------------------------------------------- submission

    public synchronized ObjectNode createJob(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        ObjectNode queue = farms.queue(region, farmId, queueId);
        String token = optional(input, "clientToken");
        if (token != null) {
            for (ObjectNode existing : store.children(region, JOBS, "queueId", queueId)) {
                if (token.equals(existing.path("_").path("clientToken").asText())) {
                    return store.object().put("jobId", existing.path("jobId").asText());
                }
            }
        }
        int priority = input.path("priority").asInt(-1);
        if (priority < 0 || priority > 100) {
            throw invalid("priority must be between 0 and 100.");
        }
        String templateText;
        String templateType;
        ObjectNode source = null;
        if (present(input, "sourceJobId")) {
            source = job(region, farmId, queueId, input.get("sourceJobId").asText());
            templateText = source.path("_").path("template").asText();
            templateType = source.path("_").path("templateType").asText();
        } else {
            templateText = text(input, "template");
            templateType = oneOf(input, "templateType", Set.of("JSON", "YAML"), null);
        }
        ObjectNode template = templates.parse(templateText, templateType);
        templates.validateJob(template);
        JsonNode submitted = input.has("parameters") ? input.get("parameters")
                : source != null ? source.path("parameters") : null;
        ObjectNode parameters = templates.resolveParameters(template, submitted);
        String storageProfile = optional(input, "storageProfileId");
        if (storageProfile != null) {
            boolean allowed = false;
            for (JsonNode id : queue.path("allowedStorageProfileIds")) {
                allowed |= storageProfile.equals(id.asText());
            }
            if (!allowed) {
                throw invalid("Storage profile " + storageProfile + " is not allowed on queue " + queueId + ".");
            }
        }
        if (input.path("attachments").isObject() && !queue.path("jobAttachmentSettings").isObject()) {
            throw invalid("Queue " + queueId + " has no job attachment settings for the job's attachments.");
        }
        String name = present(input, "nameOverride") ? input.get("nameOverride").asText()
                : DeadlineTemplates.resolve(template.path("name").asText(), parameters);
        String target = oneOf(input, "targetTaskRunStatus", Set.of("READY", "SUSPENDED"), "READY");

        String jobId = store.id("job");
        double now = now();
        ObjectNode job = store.record();
        job.put("jobId", jobId);
        job.put("name", name.length() > 128 ? name.substring(0, 128) : name);
        job.put("lifecycleStatus", farms.transitionMillis() <= 0 ? "CREATE_COMPLETE" : "CREATE_IN_PROGRESS");
        job.put("lifecycleStatusMessage", farms.transitionMillis() <= 0 ? "Job creation completed."
                : "Job creation in progress.");
        job.put("priority", priority);
        job.put("createdAt", now);
        job.put("createdBy", farms.caller());
        job.put("taskFailureRetryCount", 0);
        if (storageProfile != null) {
            job.put("storageProfileId", storageProfile);
        }
        job.put("maxFailedTasksCount", input.path("maxFailedTasksCount").asInt(100));
        job.put("maxRetriesPerTask", input.path("maxRetriesPerTask").asInt(5));
        job.put("maxWorkerCount", input.path("maxWorkerCount").asInt(-1));
        job.set("parameters", parameters);
        if (input.path("attachments").isObject()) {
            job.set("attachments", input.get("attachments").deepCopy());
        }
        String description = present(input, "descriptionOverride") ? input.get("descriptionOverride").asText()
                : template.hasNonNull("description") ? DeadlineTemplates.resolve(template.get("description").asText(),
                parameters) : null;
        if (description != null) {
            job.put("description", description);
        }
        if (source != null) {
            job.put("sourceJobId", source.path("jobId").asText());
        }
        if ("SUSPENDED".equals(target)) {
            job.put("targetTaskRunStatus", "SUSPENDED");
        }
        ObjectNode meta = (ObjectNode) job.get("_");
        meta.put("farmId", farmId).put("queueId", queueId).put("key", jobId).put("template", templateText)
                .put("templateType", templateType).put("arn", store.arn(region, "farm/" + farmId + "/queue/" + queueId
                        + "/job/" + jobId));
        if (token != null) {
            meta.put("clientToken", token);
        }
        meta.set("parsed", template);
        meta.put("readyAt", System.currentTimeMillis() + farms.transitionMillis());
        ObjectNode tags = meta.putObject("tags");
        input.path("tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        store.put(region, JOBS, jobId, job);

        Map<String, String> stepIds = new HashMap<>();
        int index = 0;
        for (JsonNode definition : template.path("steps")) {
            stepIds.put(definition.path("name").asText(), store.id("step"));
        }
        for (JsonNode definition : template.path("steps")) {
            createStep(region, job, definition, index++, stepIds, parameters, "SUSPENDED".equals(target));
        }
        refresh(region, job, false);
        return store.object().put("jobId", jobId);
    }

    private void createStep(String region, ObjectNode job, JsonNode definition, int index,
                            Map<String, String> stepIds, ObjectNode parameters, boolean suspended) {
        String stepId = stepIds.get(definition.path("name").asText());
        double now = now();
        ObjectNode step = store.record();
        step.put("stepId", stepId);
        step.put("name", definition.path("name").asText());
        step.put("lifecycleStatus", "CREATE_COMPLETE");
        step.put("taskFailureRetryCount", 0);
        step.put("createdAt", now);
        step.put("createdBy", job.path("createdBy").asText());
        step.set("requiredCapabilities", templates.requiredCapabilities(definition));
        JsonNode space = definition.path("parameterSpace");
        if (space.path("taskParameterDefinitions").isArray()) {
            ObjectNode parameterSpace = step.putObject("parameterSpace");
            ArrayNode list = parameterSpace.putArray("parameters");
            for (JsonNode parameter : space.get("taskParameterDefinitions")) {
                String type = parameter.path("type").asText();
                ObjectNode entry = list.addObject().put("name", parameter.path("name").asText())
                        .put("type", "CHUNK[INT]".equals(type) ? "CHUNK_INT" : type);
                if ("CHUNK[INT]".equals(type)) {
                    entry.putObject("chunks").put("defaultTaskCount", parameter.path("chunks")
                            .path("defaultTaskCount").asInt(1)).put("rangeConstraint", parameter.path("chunks")
                            .path("rangeConstraint").asText("CONTIGUOUS"));
                }
            }
            if (space.hasNonNull("combination")) {
                parameterSpace.put("combination", space.get("combination").asText());
            }
        }
        if (definition.hasNonNull("description")) {
            step.put("description", definition.get("description").asText());
        }
        ArrayNode dependsOn = store.array();
        for (JsonNode dependency : definition.path("dependencies")) {
            dependsOn.add(stepIds.get(dependency.path("dependsOn").asText()));
        }
        ObjectNode meta = (ObjectNode) step.get("_");
        meta.put("farmId", job.path("_").path("farmId").asText()).put("queueId", job.path("_").path("queueId")
                .asText()).put("jobId", job.path("jobId").asText()).put("key", stepId).put("index", index);
        meta.set("template", definition.deepCopy());
        meta.set("dependsOn", dependsOn);
        store.put(region, STEPS, stepId, step);
        List<ObjectNode> taskParameters = templates.tasks(definition, parameters);
        String hex = stepId.substring("step-".length());
        int taskIndex = 0;
        for (ObjectNode values : taskParameters) {
            String taskId = "task-" + hex + "-" + taskIndex;
            ObjectNode task = store.record();
            task.put("taskId", taskId);
            task.put("createdAt", now);
            task.put("createdBy", job.path("createdBy").asText());
            task.put("runStatus", suspended ? "SUSPENDED" : dependsOn.isEmpty() ? "READY" : "PENDING");
            task.put("failureRetryCount", 0);
            task.set("parameters", values);
            ((ObjectNode) task.get("_")).put("farmId", job.path("_").path("farmId").asText())
                    .put("queueId", job.path("_").path("queueId").asText()).put("jobId", job.path("jobId").asText())
                    .put("stepId", stepId).put("key", taskId).put("index", taskIndex++);
            store.put(region, TASKS, taskId, task);
        }
    }

    // ---------------------------------------------------------------- status

    /** Applies elapsed lifecycle transitions of a job. */
    void settle(String region, ObjectNode job) {
        String lifecycle = job.path("lifecycleStatus").asText();
        if (("CREATE_IN_PROGRESS".equals(lifecycle) || "UPDATE_IN_PROGRESS".equals(lifecycle))
                && System.currentTimeMillis() >= job.path("_").path("readyAt").asLong()) {
            String next = "CREATE_IN_PROGRESS".equals(lifecycle) ? "CREATE_COMPLETE" : "UPDATE_SUCCEEDED";
            job.put("lifecycleStatus", next);
            job.put("lifecycleStatusMessage", "CREATE_COMPLETE".equals(next) ? "Job creation completed."
                    : "Job update succeeded.");
            store.put(region, JOBS, job.path("jobId").asText(), job);
            lifecycleEvent(region, job, lifecycle, next);
        }
    }

    private void lifecycleEvent(String region, ObjectNode job, String previous, String next) {
        ObjectNode detail = ids(job);
        detail.put("previousLifecycleStatus", previous).put("lifecycleStatus", next);
        farms.publish(region, "Job Lifecycle Status Change", detail, job.path("_").path("arn").asText());
    }

    private ObjectNode ids(ObjectNode record) {
        JsonNode meta = record.path("_");
        ObjectNode detail = store.object();
        detail.put("farmId", meta.path("farmId").asText());
        detail.put("queueId", meta.path("queueId").asText());
        detail.put("jobId", record.has("jobId") ? record.path("jobId").asText() : meta.path("jobId").asText());
        if (record.has("stepId") || meta.has("stepId")) {
            detail.put("stepId", record.has("stepId") ? record.path("stepId").asText() : meta.path("stepId").asText());
        }
        return detail;
    }

    /** The status a set of task run statuses rolls up to. */
    static String rollUp(Map<String, Integer> counts) {
        for (String status : List.of("RUNNING", "ASSIGNED", "STARTING", "SCHEDULED", "INTERRUPTING", "READY",
                "PENDING", "NOT_COMPATIBLE", "SUSPENDED", "FAILED", "CANCELED")) {
            if (counts.getOrDefault(status, 0) > 0) {
                return status;
            }
        }
        return "SUCCEEDED";
    }

    private static ObjectNode counts(DeadlineStore store, Map<String, Integer> counts) {
        ObjectNode node = store.object();
        for (String status : RUN_STATUSES) {
            node.put(status, counts.getOrDefault(status, 0));
        }
        return node;
    }

    /**
     * Recomputes a job's step and job statuses from its tasks: pending tasks whose step dependencies have all
     * succeeded become ready, a step or job's run status and counts roll up, start and end times are stamped,
     * and changes are published as events.
     */
    void refresh(String region, ObjectNode job, boolean publish) {
        List<ObjectNode> steps = steps(region, job.path("jobId").asText());
        Map<String, String> stepStatus = new HashMap<>();
        for (ObjectNode step : steps) {
            stepStatus.put(step.path("stepId").asText(), step.path("taskRunStatus").asText(""));
        }
        Map<String, Integer> jobCounts = new LinkedHashMap<>();
        int jobRetries = 0;
        double now = now();
        boolean compatibleChecked = false;
        List<ObjectNode> fleets = null;
        for (ObjectNode step : steps) {
            boolean dependenciesMet = true;
            for (JsonNode dependency : step.path("_").path("dependsOn")) {
                dependenciesMet &= "SUCCEEDED".equals(stepStatus.get(dependency.asText()));
            }
            if (!compatibleChecked) {
                fleets = compatibleFleetsCheck(region, job);
                compatibleChecked = true;
            }
            boolean compatible = fleets == null || compatible(step.path("requiredCapabilities"), fleets);
            Map<String, Integer> counts = new LinkedHashMap<>();
            int retries = 0;
            for (ObjectNode task : tasks(region, step.path("stepId").asText())) {
                String status = task.path("runStatus").asText();
                String next = status;
                if (("PENDING".equals(status) || "NOT_COMPATIBLE".equals(status) || "READY".equals(status))
                        && !task.path("_").path("held").asBoolean(false)) {
                    next = !dependenciesMet ? "PENDING" : compatible ? "READY" : "NOT_COMPATIBLE";
                }
                if (!next.equals(status)) {
                    task.put("runStatus", next);
                    task.put("updatedAt", now);
                    store.put(region, TASKS, task.path("taskId").asText(), task);
                    if (publish) {
                        taskEvent(region, task, status, next);
                    }
                }
                counts.merge(next, 1, Integer::sum);
                retries += task.path("failureRetryCount").asInt();
            }
            String previous = step.path("taskRunStatus").asText(null);
            String rolled = rollUp(counts);
            step.put("taskRunStatus", rolled);
            step.set("taskRunStatusCounts", counts(store, counts));
            step.put("taskFailureRetryCount", retries);
            stamp(step, counts, now);
            step.set("dependencyCounts", dependencyCounts(step, steps, stepStatus));
            store.put(region, STEPS, step.path("stepId").asText(), step);
            stepStatus.put(step.path("stepId").asText(), rolled);
            if (publish && previous != null && !previous.equals(rolled)) {
                ObjectNode detail = ids(step);
                detail.put("previousTaskRunStatus", previous).put("taskRunStatus", rolled);
                detail.set("taskRunStatusCounts", step.get("taskRunStatusCounts").deepCopy());
                farms.publish(region, "Step Run Status Change", detail, job.path("_").path("arn").asText());
            }
            counts.forEach((status, count) -> jobCounts.merge(status, count, Integer::sum));
            jobRetries += retries;
        }
        String previous = job.path("taskRunStatus").asText(null);
        String rolled = rollUp(jobCounts);
        job.put("taskRunStatus", rolled);
        job.set("taskRunStatusCounts", counts(store, jobCounts));
        job.put("taskFailureRetryCount", jobRetries);
        stamp(job, jobCounts, now);
        if (job.has("targetTaskRunStatus") && !"SUSPENDED".equals(job.path("targetTaskRunStatus").asText())
                && rolled.equals(job.path("targetTaskRunStatus").asText())) {
            job.remove("targetTaskRunStatus");
        }
        store.put(region, JOBS, job.path("jobId").asText(), job);
        if (publish && previous != null && !previous.equals(rolled)) {
            ObjectNode detail = ids(job);
            detail.put("previousTaskRunStatus", previous).put("taskRunStatus", rolled);
            detail.set("taskRunStatusCounts", job.get("taskRunStatusCounts").deepCopy());
            farms.publish(region, "Job Run Status Change", detail, job.path("_").path("arn").asText());
        }
    }

    private static void stamp(ObjectNode record, Map<String, Integer> counts, double now) {
        boolean started = false;
        int terminal = 0;
        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            total += entry.getValue();
            started |= IN_FLIGHT.contains(entry.getKey()) || TERMINAL.contains(entry.getKey());
            terminal += TERMINAL.contains(entry.getKey()) ? entry.getValue() : 0;
        }
        if (started && !record.has("startedAt")) {
            record.put("startedAt", now);
        }
        if (total > 0 && terminal == total) {
            if (!record.has("endedAt")) {
                record.put("endedAt", now);
            }
        } else {
            record.remove("endedAt");
        }
    }

    private ObjectNode dependencyCounts(ObjectNode step, List<ObjectNode> steps, Map<String, String> statuses) {
        int resolved = 0;
        int unresolved = 0;
        for (JsonNode dependency : step.path("_").path("dependsOn")) {
            if ("SUCCEEDED".equals(statuses.get(dependency.asText()))) {
                resolved++;
            } else {
                unresolved++;
            }
        }
        int consumersResolved = 0;
        int consumersUnresolved = 0;
        String stepId = step.path("stepId").asText();
        String status = statuses.get(stepId);
        for (ObjectNode other : steps) {
            for (JsonNode dependency : other.path("_").path("dependsOn")) {
                if (stepId.equals(dependency.asText())) {
                    if ("SUCCEEDED".equals(status)) {
                        consumersResolved++;
                    } else {
                        consumersUnresolved++;
                    }
                }
            }
        }
        return store.object().put("dependenciesResolved", resolved).put("dependenciesUnresolved", unresolved)
                .put("consumersResolved", consumersResolved).put("consumersUnresolved", consumersUnresolved);
    }

    /** The fleets that schedule the job's queue, or null when none does (tasks then wait as READY). */
    private List<ObjectNode> compatibleFleetsCheck(String region, ObjectNode job) {
        String queueId = job.path("_").path("queueId").asText();
        List<ObjectNode> fleets = new ArrayList<>();
        for (ObjectNode association : store.children(region, DeadlineService.QUEUE_FLEETS, "queueId", queueId)) {
            if ("ACTIVE".equals(association.path("status").asText())) {
                store.get(region, DeadlineService.FLEETS, association.path("fleetId").asText()).ifPresent(fleets::add);
            }
        }
        return fleets.isEmpty() ? null : fleets;
    }

    private static boolean compatible(JsonNode required, List<ObjectNode> fleets) {
        for (ObjectNode fleet : fleets) {
            if (satisfies(fleet.path("capabilities"), required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether capabilities satisfy a step's requirements: each required amount overlaps the offered range, each
     * {@code anyOf} attribute shares a value with the offered ones and each {@code allOf} is offered in full.
     */
    static boolean satisfies(JsonNode offered, JsonNode required) {
        for (JsonNode amount : required.path("amounts")) {
            JsonNode match = null;
            for (JsonNode candidate : offered.path("amounts")) {
                if (amount.path("name").asText().equals(candidate.path("name").asText())) {
                    match = candidate;
                }
            }
            if (match == null) {
                return false;
            }
            double offeredMin = match.has("min") ? match.get("min").asDouble() : match.path("value").asDouble();
            double offeredMax = match.has("max") ? match.get("max").asDouble()
                    : match.has("value") ? match.get("value").asDouble() : Double.MAX_VALUE;
            if (amount.has("min") && offeredMax < amount.get("min").asDouble()) {
                return false;
            }
            if (amount.has("max") && offeredMin > amount.get("max").asDouble()) {
                return false;
            }
        }
        for (JsonNode attribute : required.path("attributes")) {
            List<String> values = new ArrayList<>();
            for (JsonNode candidate : offered.path("attributes")) {
                if (attribute.path("name").asText().equals(candidate.path("name").asText())) {
                    candidate.path("values").forEach(value -> values.add(value.asText().toLowerCase(Locale.ROOT)));
                }
            }
            if (attribute.has("anyOf")) {
                boolean any = false;
                for (JsonNode value : attribute.get("anyOf")) {
                    any |= values.contains(value.asText().toLowerCase(Locale.ROOT));
                }
                if (!any) {
                    return false;
                }
            }
            for (JsonNode value : attribute.path("allOf")) {
                if (!values.contains(value.asText().toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }

    void taskEvent(String region, ObjectNode task, String previous, String next) {
        ObjectNode detail = ids(task);
        detail.put("taskId", task.path("taskId").asText());
        detail.put("previousRunStatus", previous).put("runStatus", next);
        farms.publish(region, "Task Run Status Change", detail, store.arn(region, "farm/" + task.path("_")
                .path("farmId").asText() + "/queue/" + task.path("_").path("queueId").asText() + "/job/"
                + task.path("_").path("jobId").asText()));
    }

    // ---------------------------------------------------------------- reads

    private ObjectNode jobView(ObjectNode job) {
        return store.view(job);
    }

    public synchronized ObjectNode getJob(ObjectNode input, String region) {
        return jobView(job(region, input));
    }

    public synchronized ObjectNode listJobs(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        farms.queue(region, farmId, queueId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : store.children(region, JOBS, "queueId", queueId)) {
            settle(region, job);
            items.add(store.view(job, "jobId", "name", "lifecycleStatus", "lifecycleStatusMessage", "priority",
                    "createdAt", "createdBy", "updatedAt", "updatedBy", "startedAt", "endedAt", "taskRunStatus",
                    "targetTaskRunStatus", "taskRunStatusCounts", "taskFailureRetryCount", "maxFailedTasksCount",
                    "maxRetriesPerTask", "maxWorkerCount", "sourceJobId"));
        }
        return store.page(items, input, "jobs", item -> item.path("jobId").asText());
    }

    public synchronized ObjectNode listJobParameterDefinitions(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        List<ObjectNode> items = new ArrayList<>();
        job.path("_").path("parsed").path("parameterDefinitions").forEach(definition -> items.add(
                ((ObjectNode) definition).deepCopy()));
        return store.page(items, input, "jobParameterDefinitions", item -> item.path("name").asText());
    }

    public synchronized ObjectNode getStep(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        return store.view(step(region, job, text(input, "stepId")));
    }

    public synchronized ObjectNode listSteps(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode step : steps(region, job.path("jobId").asText())) {
            ObjectNode summary = store.view(step);
            summary.remove("requiredCapabilities");
            summary.remove("parameterSpace");
            summary.remove("description");
            items.add(summary);
        }
        return store.page(items, input, "steps", item -> item.path("stepId").asText());
    }

    private ObjectNode dependencyList(ObjectNode input, String region, boolean consumers) {
        ObjectNode job = job(region, input);
        ObjectNode step = step(region, job, text(input, "stepId"));
        List<ObjectNode> items = new ArrayList<>();
        Map<String, String> statuses = new HashMap<>();
        List<ObjectNode> steps = steps(region, job.path("jobId").asText());
        steps.forEach(other -> statuses.put(other.path("stepId").asText(), other.path("taskRunStatus").asText()));
        if (consumers) {
            String status = "SUCCEEDED".equals(step.path("taskRunStatus").asText()) ? "RESOLVED" : "UNRESOLVED";
            for (ObjectNode other : steps) {
                for (JsonNode dependency : other.path("_").path("dependsOn")) {
                    if (step.path("stepId").asText().equals(dependency.asText())) {
                        items.add(store.object().put("stepId", other.path("stepId").asText()).put("status", status));
                    }
                }
            }
        } else {
            for (JsonNode dependency : step.path("_").path("dependsOn")) {
                items.add(store.object().put("stepId", dependency.asText()).put("status",
                        "SUCCEEDED".equals(statuses.get(dependency.asText())) ? "RESOLVED" : "UNRESOLVED"));
            }
        }
        return store.page(items, input, consumers ? "consumers" : "dependencies", item -> item.path("stepId")
                .asText());
    }

    public synchronized ObjectNode listStepConsumers(ObjectNode input, String region) {
        return dependencyList(input, region, true);
    }

    public synchronized ObjectNode listStepDependencies(ObjectNode input, String region) {
        return dependencyList(input, region, false);
    }

    public synchronized ObjectNode getTask(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        return store.view(task(region, step(region, job, text(input, "stepId")), text(input, "taskId")));
    }

    public synchronized ObjectNode listTasks(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        ObjectNode step = step(region, job, text(input, "stepId"));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode task : tasks(region, step.path("stepId").asText())) {
            items.add(store.view(task));
        }
        return store.page(items, input, "tasks", item -> item.path("taskId").asText());
    }

    public synchronized ObjectNode getSession(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        String sessionId = text(input, "sessionId");
        ObjectNode session = store.require(region, SESSIONS, sessionId, "Session");
        if (!job.path("jobId").asText().equals(session.path("_").path("jobId").asText())) {
            throw notFound("Session " + sessionId + " not found.", SESSIONS, sessionId);
        }
        return store.view(session);
    }

    public synchronized ObjectNode listSessions(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode session : store.children(region, SESSIONS, "jobId", job.path("jobId").asText())) {
            items.add(store.view(session, "sessionId", "fleetId", "workerId", "startedAt", "lifecycleStatus",
                    "endedAt", "targetLifecycleStatus", "updatedAt", "updatedBy"));
        }
        return store.page(items, input, "sessions", item -> item.path("sessionId").asText());
    }

    public synchronized ObjectNode getSessionAction(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        String actionId = text(input, "sessionActionId");
        ObjectNode action = store.require(region, ACTIONS, actionId, "Session action");
        if (!job.path("jobId").asText().equals(action.path("_").path("jobId").asText())) {
            throw notFound("Session action " + actionId + " not found.", ACTIONS, actionId);
        }
        return store.view(action);
    }

    public synchronized ObjectNode listSessionActions(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        String sessionId = optional(input, "sessionId");
        String taskId = optional(input, "taskId");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode action : store.children(region, ACTIONS, "jobId", job.path("jobId").asText())) {
            if ((sessionId != null && !sessionId.equals(action.path("sessionId").asText()))
                    || (taskId != null && !taskId.equals(action.path("definition").path("taskRun").path("taskId")
                    .asText()))) {
                continue;
            }
            items.add(store.view(action, "sessionActionId", "status", "startedAt", "endedAt", "workerUpdatedAt",
                    "progressPercent", "manifests", "definition"));
        }
        return store.page(items, input, "sessionActions", item -> item.path("sessionActionId").asText());
    }

    // ---------------------------------------------------------------- updates

    public synchronized ObjectNode updateJob(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        applyJobUpdate(region, job, input);
        return store.object();
    }

    private void applyJobUpdate(String region, ObjectNode job, JsonNode input) {
        if ("ARCHIVED".equals(job.path("lifecycleStatus").asText())) {
            throw conflict("Job " + job.path("jobId").asText() + " is archived.", "STATUS_CONFLICT", JOBS,
                    job.path("jobId").asText());
        }
        if (present(input, "lifecycleStatus")) {
            oneOf(input, "lifecycleStatus", Set.of("ARCHIVED"), null);
            if (!TERMINAL.contains(job.path("taskRunStatus").asText())) {
                throw conflict("Only a job that has finished can be archived; job " + job.path("jobId").asText()
                        + " is " + job.path("taskRunStatus").asText() + ".", "STATUS_CONFLICT", JOBS,
                        job.path("jobId").asText());
            }
            String previous = job.path("lifecycleStatus").asText();
            job.put("lifecycleStatus", "ARCHIVED");
            job.put("lifecycleStatusMessage", "Job archived.");
            job.put("updatedAt", now());
            job.put("updatedBy", farms.caller());
            store.put(region, JOBS, job.path("jobId").asText(), job);
            lifecycleEvent(region, job, previous, "ARCHIVED");
            return;
        }
        if (present(input, "priority")) {
            int priority = input.get("priority").asInt(-1);
            if (priority < 0 || priority > 100) {
                throw invalid("priority must be between 0 and 100.");
            }
            job.put("priority", priority);
        }
        for (String field : List.of("maxFailedTasksCount", "maxRetriesPerTask", "maxWorkerCount")) {
            if (present(input, field)) {
                job.put(field, input.get(field).asInt());
            }
        }
        if (present(input, "name")) {
            job.put("name", input.get("name").asText());
        }
        if (present(input, "description")) {
            job.put("description", input.get("description").asText());
        }
        String previous = job.path("lifecycleStatus").asText();
        job.put("lifecycleStatus", farms.transitionMillis() <= 0 ? "UPDATE_SUCCEEDED" : "UPDATE_IN_PROGRESS");
        job.put("lifecycleStatusMessage", farms.transitionMillis() <= 0 ? "Job update succeeded."
                : "Job update in progress.");
        ((ObjectNode) job.get("_")).put("readyAt", System.currentTimeMillis() + farms.transitionMillis());
        job.put("updatedAt", now());
        job.put("updatedBy", farms.caller());
        store.put(region, JOBS, job.path("jobId").asText(), job);
        if (present(input, "targetTaskRunStatus")) {
            String target = oneOf(input, "targetTaskRunStatus", TARGETS, null);
            job.put("targetTaskRunStatus", target);
            store.put(region, JOBS, job.path("jobId").asText(), job);
            for (ObjectNode step : steps(region, job.path("jobId").asText())) {
                for (ObjectNode task : tasks(region, step.path("stepId").asText())) {
                    target(region, task, target);
                }
            }
            refresh(region, job, true);
        }
        lifecycleEvent(region, job, previous, job.path("lifecycleStatus").asText());
    }

    public synchronized ObjectNode updateStep(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        ObjectNode step = step(region, job, text(input, "stepId"));
        String target = oneOf(input, "targetTaskRunStatus", TARGETS, null);
        step.put("targetTaskRunStatus", target);
        step.put("updatedAt", now());
        step.put("updatedBy", farms.caller());
        store.put(region, STEPS, step.path("stepId").asText(), step);
        for (ObjectNode task : tasks(region, step.path("stepId").asText())) {
            target(region, task, target);
        }
        refresh(region, job, true);
        return store.object();
    }

    public synchronized ObjectNode updateTask(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        ObjectNode task = task(region, step(region, job, text(input, "stepId")), text(input, "taskId"));
        target(region, task, oneOf(input, "targetRunStatus", TARGETS, null));
        refresh(region, job, true);
        return store.object();
    }

    /**
     * Moves a task toward a target run status. A task with a session action in flight is interrupted (its action
     * is canceled on the worker's next schedule update) and settles to the target once the worker reports it;
     * any other task moves at once. A requeued task starts its retries afresh.
     */
    void target(String region, ObjectNode task, String target) {
        String status = task.path("runStatus").asText();
        String previous = status;
        double now = now();
        task.put("updatedAt", now);
        task.put("updatedBy", farms.caller());
        if (IN_FLIGHT.contains(status)) {
            task.put("targetRunStatus", target);
            if (!"READY".equals(target)) {
                task.put("runStatus", "INTERRUPTING");
                String actionId = task.path("latestSessionActionId").asText(null);
                if (actionId != null) {
                    store.get(region, ACTIONS, actionId).ifPresent(action -> {
                        if (Set.of("ASSIGNED", "RUNNING").contains(action.path("status").asText())) {
                            action.put("status", "CANCELING");
                            store.put(region, ACTIONS, actionId, action);
                        }
                    });
                }
            }
        } else {
            task.remove("targetRunStatus");
            ((ObjectNode) task.get("_")).put("held", "PENDING".equals(target));
            switch (target) {
                case "READY" -> {
                    task.put("runStatus", "READY");
                    task.put("failureRetryCount", 0);
                    task.remove("endedAt");
                    task.remove("startedAt");
                }
                case "PENDING" -> task.put("runStatus", "PENDING");
                default -> {
                    task.put("runStatus", target);
                    if (TERMINAL.contains(target)) {
                        task.put("endedAt", now);
                    }
                }
            }
        }
        store.put(region, TASKS, task.path("taskId").asText(), task);
        if (!previous.equals(task.path("runStatus").asText())) {
            taskEvent(region, task, previous, task.path("runStatus").asText());
        }
    }

    public synchronized ObjectNode batchUpdateJob(ObjectNode input, String region) {
        ArrayNode errors = store.array();
        for (JsonNode item : input.path("jobs")) {
            try {
                ObjectNode job = job(region, text(item, "farmId"), text(item, "queueId"), text(item, "jobId"));
                applyJobUpdate(region, job, item);
            } catch (AwsException e) {
                errors.addObject().put("farmId", item.path("farmId").asText()).put("queueId", item.path("queueId")
                        .asText()).put("jobId", item.path("jobId").asText()).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
            }
        }
        ObjectNode response = store.object();
        response.set("errors", errors);
        return response;
    }

    public synchronized ObjectNode batchUpdateTask(ObjectNode input, String region) {
        ArrayNode errors = store.array();
        for (JsonNode item : input.path("tasks")) {
            try {
                ObjectNode job = job(region, text(item, "farmId"), text(item, "queueId"), text(item, "jobId"));
                ObjectNode task = task(region, step(region, job, text(item, "stepId")), text(item, "taskId"));
                target(region, task, oneOf(item, "targetRunStatus", TARGETS, null));
                refresh(region, job, true);
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                for (String field : List.of("farmId", "queueId", "jobId", "stepId", "taskId")) {
                    error.put(field, item.path(field).asText());
                }
                error.put("code", e.getErrorCode()).put("message", e.getMessage());
            }
        }
        ObjectNode response = store.object();
        response.set("errors", errors);
        return response;
    }

    // ---------------------------------------------------------------- batch reads

    private ObjectNode batch(ObjectNode input, String field, String errorsField, Function<JsonNode, ObjectNode> read,
                             List<String> idFields) {
        ArrayNode found = store.array();
        ArrayNode errors = store.array();
        for (JsonNode identifier : input.path("identifiers")) {
            try {
                found.add(read.apply(identifier));
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                for (String id : idFields) {
                    if (identifier.hasNonNull(id)) {
                        error.put(id, identifier.get(id).asText());
                    }
                }
                error.put("code", e.getErrorCode()).put("message", e.getMessage());
            }
        }
        ObjectNode response = store.object();
        response.set(field, found);
        response.set(errorsField, errors);
        return response;
    }

    public synchronized ObjectNode batchGetJob(ObjectNode input, String region) {
        return batch(input, "jobs", "errors", identifier -> {
            ObjectNode view = jobView(job(region, text(identifier, "farmId"), text(identifier, "queueId"),
                    text(identifier, "jobId")));
            view.put("farmId", identifier.get("farmId").asText()).put("queueId", identifier.get("queueId").asText());
            return view;
        }, List.of("farmId", "queueId", "jobId"));
    }

    public synchronized ObjectNode batchGetStep(ObjectNode input, String region) {
        return batch(input, "steps", "errors", identifier -> {
            ObjectNode job = job(region, text(identifier, "farmId"), text(identifier, "queueId"),
                    text(identifier, "jobId"));
            ObjectNode view = store.view(step(region, job, text(identifier, "stepId")));
            view.put("farmId", identifier.get("farmId").asText()).put("queueId", identifier.get("queueId").asText())
                    .put("jobId", identifier.get("jobId").asText());
            return view;
        }, List.of("farmId", "queueId", "jobId", "stepId"));
    }

    public synchronized ObjectNode batchGetTask(ObjectNode input, String region) {
        return batch(input, "tasks", "errors", identifier -> {
            ObjectNode job = job(region, text(identifier, "farmId"), text(identifier, "queueId"),
                    text(identifier, "jobId"));
            ObjectNode view = store.view(task(region, step(region, job, text(identifier, "stepId")),
                    text(identifier, "taskId")));
            for (String field : List.of("farmId", "queueId", "jobId", "stepId")) {
                view.put(field, identifier.get(field).asText());
            }
            return view;
        }, List.of("farmId", "queueId", "jobId", "stepId", "taskId"));
    }

    public synchronized ObjectNode batchGetSession(ObjectNode input, String region) {
        return batch(input, "sessions", "errors", identifier -> {
            ObjectNode job = job(region, text(identifier, "farmId"), text(identifier, "queueId"),
                    text(identifier, "jobId"));
            String sessionId = text(identifier, "sessionId");
            ObjectNode session = store.require(region, SESSIONS, sessionId, "Session");
            if (!job.path("jobId").asText().equals(session.path("_").path("jobId").asText())) {
                throw notFound("Session " + sessionId + " not found.", SESSIONS, sessionId);
            }
            ObjectNode view = store.view(session);
            for (String field : List.of("farmId", "queueId", "jobId")) {
                view.put(field, identifier.get(field).asText());
            }
            return view;
        }, List.of("farmId", "queueId", "jobId", "sessionId"));
    }

    public synchronized ObjectNode batchGetSessionAction(ObjectNode input, String region) {
        return batch(input, "sessionActions", "errors", identifier -> {
            ObjectNode job = job(region, text(identifier, "farmId"), text(identifier, "queueId"),
                    text(identifier, "jobId"));
            String actionId = text(identifier, "sessionActionId");
            ObjectNode action = store.require(region, ACTIONS, actionId, "Session action");
            if (!job.path("jobId").asText().equals(action.path("_").path("jobId").asText())) {
                throw notFound("Session action " + actionId + " not found.", ACTIONS, actionId);
            }
            ObjectNode view = store.view(action);
            for (String field : List.of("farmId", "queueId", "jobId")) {
                view.put(field, identifier.get(field).asText());
            }
            return view;
        }, List.of("farmId", "queueId", "jobId", "sessionActionId"));
    }

    // ---------------------------------------------------------------- search

    private ObjectNode search(ObjectNode input, String region, String field, List<ObjectNode> candidates) {
        int offset = input.path("itemOffset").asInt(-1);
        if (offset < 0 || offset > 10000) {
            throw invalid("itemOffset must be between 0 and 10000.");
        }
        int size = input.path("pageSize").asInt(100);
        if (size < 1 || size > 100) {
            throw invalid("pageSize must be between 1 and 100.");
        }
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode candidate : candidates) {
            if (!input.path("filterExpressions").isObject() || group(candidate, input.get("filterExpressions"))) {
                matching.add(candidate);
            }
        }
        Comparator<ObjectNode> order = null;
        for (JsonNode sort : input.path("sortExpressions")) {
            Comparator<ObjectNode> next;
            if (sort.has("userJobsFirst")) {
                String user = sort.get("userJobsFirst").path("userIdentityId").asText();
                next = Comparator.comparing(item -> user.equals(item.path("createdBy").asText()) ? 0 : 1);
            } else {
                JsonNode by = sort.has("fieldSort") ? sort.get("fieldSort") : sort.get("parameterSort");
                boolean parameter = sort.has("parameterSort");
                String name = by.path("name").asText();
                next = Comparator.comparing((ObjectNode item) -> sortKey(parameter ? item.path("jobParameters")
                        .path(name) : item.path(name)));
                if ("DESCENDING".equals(by.path("sortOrder").asText())) {
                    next = next.reversed();
                }
            }
            order = order == null ? next : order.thenComparing(next);
        }
        if (order != null) {
            matching.sort(order);
        }
        ObjectNode response = store.object();
        ArrayNode page = response.putArray(field);
        for (int index = offset; index < Math.min(matching.size(), offset + size); index++) {
            page.add(matching.get(index));
        }
        if (offset + size < matching.size()) {
            response.put("nextItemOffset", offset + size);
        }
        response.put("totalResults", matching.size());
        return response;
    }

    private static String sortKey(JsonNode value) {
        if (value.isObject() && !value.isEmpty()) {
            value = value.elements().next();
        }
        if (value.isNumber()) {
            return String.format("%020.6f", value.asDouble());
        }
        return value.asText("");
    }

    private boolean group(ObjectNode item, JsonNode group) {
        boolean and = !"OR".equals(group.path("operator").asText("AND"));
        boolean any = false;
        for (JsonNode filter : group.path("filters")) {
            boolean matches = filter(item, filter);
            if (and && !matches) {
                return false;
            }
            any |= matches;
        }
        return and || any;
    }

    private boolean filter(ObjectNode item, JsonNode filter) {
        if (filter.has("groupFilter")) {
            return group(item, filter.get("groupFilter"));
        }
        if (filter.has("searchTermFilter")) {
            String term = filter.get("searchTermFilter").path("searchTerm").asText().toLowerCase(Locale.ROOT);
            String haystack = item.path("name").asText(item.path("taskId").asText(item.path("workerId")
                    .asText())).toLowerCase(Locale.ROOT);
            if ("FUZZY_MATCH".equals(filter.get("searchTermFilter").path("matchType").asText("FUZZY_MATCH"))) {
                int position = 0;
                for (char character : term.toCharArray()) {
                    position = haystack.indexOf(character, position);
                    if (position < 0) {
                        return false;
                    }
                    position++;
                }
                return true;
            }
            return haystack.contains(term);
        }
        if (filter.has("stringFilter")) {
            JsonNode string = filter.get("stringFilter");
            return compare(item.path(string.path("name").asText()).asText(), string.path("value").asText(),
                    string.path("operator").asText());
        }
        if (filter.has("stringListFilter")) {
            JsonNode list = filter.get("stringListFilter");
            String actual = item.path(list.path("name").asText()).asText();
            boolean contained = false;
            for (JsonNode value : list.path("values")) {
                contained |= actual.equals(value.asText());
            }
            return "ALL_NOT_EQUALS".equals(list.path("operator").asText()) || "NOT_EQUAL".equals(list
                    .path("operator").asText()) ? !contained : contained;
        }
        if (filter.has("dateTimeFilter")) {
            JsonNode date = filter.get("dateTimeFilter");
            JsonNode actual = item.path(date.path("name").asText());
            if (!actual.isNumber()) {
                return false;
            }
            return compareNumbers(actual.asDouble(), date.path("dateTime").asDouble(), date.path("operator").asText());
        }
        if (filter.has("parameterFilter")) {
            JsonNode parameter = filter.get("parameterFilter");
            JsonNode value = item.path("jobParameters").path(parameter.path("name").asText());
            if (!value.isObject() || value.isEmpty()) {
                value = item.path("parameters").path(parameter.path("name").asText());
            }
            if (!value.isObject() || value.isEmpty()) {
                return false;
            }
            String actual = value.elements().next().asText();
            String expected = parameter.path("value").path("string").asText(parameter.path("value").path("int")
                    .asText(parameter.path("value").path("float").asText(parameter.path("value").path("path")
                            .asText(""))));
            try {
                return compareNumbers(Double.parseDouble(actual), Double.parseDouble(expected),
                        parameter.path("operator").asText());
            } catch (NumberFormatException e) {
                return compare(actual, expected, parameter.path("operator").asText());
            }
        }
        return true;
    }

    private static boolean compare(String actual, String expected, String operator) {
        return switch (operator) {
            case "NOT_EQUAL", "ALL_NOT_EQUALS" -> !actual.equals(expected);
            case "GREATER_THAN" -> actual.compareTo(expected) > 0;
            case "GREATER_THAN_EQUAL_TO" -> actual.compareTo(expected) >= 0;
            case "LESS_THAN" -> actual.compareTo(expected) < 0;
            case "LESS_THAN_EQUAL_TO" -> actual.compareTo(expected) <= 0;
            default -> actual.equals(expected);
        };
    }

    private static boolean compareNumbers(double actual, double expected, String operator) {
        return switch (operator) {
            case "NOT_EQUAL", "ALL_NOT_EQUALS" -> actual != expected;
            case "GREATER_THAN" -> actual > expected;
            case "GREATER_THAN_EQUAL_TO" -> actual >= expected;
            case "LESS_THAN" -> actual < expected;
            case "LESS_THAN_EQUAL_TO" -> actual <= expected;
            default -> actual == expected;
        };
    }

    private List<String> queues(ObjectNode input, String region, String farmId) {
        if (!input.path("queueIds").isArray() || input.get("queueIds").isEmpty()) {
            throw invalid("queueIds is required.");
        }
        List<String> queues = new ArrayList<>();
        for (JsonNode queueId : input.get("queueIds")) {
            farms.queue(region, farmId, queueId.asText());
            queues.add(queueId.asText());
        }
        return queues;
    }

    public synchronized ObjectNode searchJobs(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        List<ObjectNode> candidates = new ArrayList<>();
        for (String queueId : queues(input, region, farmId)) {
            for (ObjectNode job : store.children(region, JOBS, "queueId", queueId)) {
                settle(region, job);
                ObjectNode summary = store.view(job, "jobId", "name", "lifecycleStatus", "lifecycleStatusMessage",
                        "taskRunStatus", "targetTaskRunStatus", "taskRunStatusCounts", "taskFailureRetryCount",
                        "priority", "maxFailedTasksCount", "maxRetriesPerTask", "createdBy", "createdAt", "endedAt",
                        "startedAt", "updatedAt", "updatedBy", "maxWorkerCount", "sourceJobId");
                summary.put("queueId", queueId);
                summary.set("jobParameters", job.path("parameters").deepCopy());
                candidates.add(summary);
            }
        }
        return search(input, region, "jobs", candidates);
    }

    public synchronized ObjectNode searchSteps(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String jobId = optional(input, "jobId");
        List<ObjectNode> candidates = new ArrayList<>();
        for (String queueId : queues(input, region, farmId)) {
            for (ObjectNode step : store.children(region, STEPS, "queueId", queueId)) {
                if (jobId != null && !jobId.equals(step.path("_").path("jobId").asText())) {
                    continue;
                }
                ObjectNode summary = store.view(step);
                summary.remove("requiredCapabilities");
                summary.remove("dependencyCounts");
                summary.remove("description");
                summary.put("jobId", step.path("_").path("jobId").asText()).put("queueId", queueId);
                candidates.add(summary);
            }
        }
        return search(input, region, "steps", candidates);
    }

    public synchronized ObjectNode searchTasks(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String jobId = optional(input, "jobId");
        List<ObjectNode> candidates = new ArrayList<>();
        for (String queueId : queues(input, region, farmId)) {
            for (ObjectNode task : store.children(region, TASKS, "queueId", queueId)) {
                if (jobId != null && !jobId.equals(task.path("_").path("jobId").asText())) {
                    continue;
                }
                ObjectNode summary = store.view(task, "taskId", "runStatus", "targetRunStatus", "parameters",
                        "failureRetryCount", "startedAt", "endedAt", "updatedAt", "updatedBy",
                        "latestSessionActionId");
                summary.put("stepId", task.path("_").path("stepId").asText())
                        .put("jobId", task.path("_").path("jobId").asText()).put("queueId", queueId);
                candidates.add(summary);
            }
        }
        return search(input, region, "tasks", candidates);
    }

    public synchronized ObjectNode searchWorkers(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        if (!input.path("fleetIds").isArray() || input.get("fleetIds").isEmpty()) {
            throw invalid("fleetIds is required.");
        }
        List<ObjectNode> candidates = new ArrayList<>();
        for (JsonNode fleetId : input.get("fleetIds")) {
            farms.fleet(region, farmId, fleetId.asText());
            for (ObjectNode worker : store.children(region, DeadlineService.WORKERS, "fleetId", fleetId.asText())) {
                candidates.add(store.view(worker, "fleetId", "workerId", "status", "hostProperties", "createdBy",
                        "createdAt", "updatedBy", "updatedAt"));
            }
        }
        return search(input, region, "workers", candidates);
    }

    // ---------------------------------------------------------------- templates

    public synchronized ObjectNode copyJobTemplate(ObjectNode input, String region) {
        ObjectNode job = job(region, input);
        JsonNode location = input.path("targetS3Location");
        String bucket = text(location, "bucketName");
        String key = text(location, "key");
        if (!s3.isResolvable()) {
            throw invalid("Amazon S3 is not enabled.");
        }
        String type = job.path("_").path("templateType").asText();
        s3.get().putObject(bucket, key, job.path("_").path("template").asText().getBytes(StandardCharsets.UTF_8),
                "YAML".equals(type) ? "application/yaml" : "application/json", Map.of());
        return store.object().put("templateType", type);
    }

    // ---------------------------------------------------------------- statistics

    public synchronized ObjectNode startSessionsStatisticsAggregation(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farms.farm(region, farmId);
        JsonNode resources = input.path("resourceIds");
        if (!resources.has("queueIds") && !resources.has("fleetIds")) {
            throw invalid("resourceIds must name queueIds or fleetIds.");
        }
        for (JsonNode queueId : resources.path("queueIds")) {
            farms.queue(region, farmId, queueId.asText());
        }
        for (JsonNode fleetId : resources.path("fleetIds")) {
            farms.fleet(region, farmId, fleetId.asText());
        }
        double start = input.path("startTime").asDouble(Double.NaN);
        double end = input.path("endTime").asDouble(Double.NaN);
        if (Double.isNaN(start) || Double.isNaN(end) || end <= start) {
            throw invalid("endTime must be after startTime.");
        }
        if (!input.path("groupBy").isArray() || input.get("groupBy").isEmpty()) {
            throw invalid("groupBy is required.");
        }
        if (!input.path("statistics").isArray() || input.get("statistics").isEmpty()) {
            throw invalid("statistics is required.");
        }
        if (present(input, "timezone")) {
            try {
                zone(input.get("timezone").asText());
            } catch (RuntimeException e) {
                throw invalid("timezone must be a UTC offset such as UTC+05:30.");
            }
        }
        String id = store.id("aggregation").substring("aggregation-".length());
        ObjectNode aggregation = store.record();
        aggregation.setAll(input);
        ((ObjectNode) aggregation.get("_")).put("farmId", farmId).put("readyAt", System.currentTimeMillis()
                + farms.transitionMillis());
        store.put(region, AGGREGATIONS, id, aggregation);
        return store.object().put("aggregationId", id);
    }

    private static ZoneId zone(String timezone) {
        String offset = timezone.startsWith("UTC") ? timezone.substring(3) : timezone;
        return offset.isEmpty() ? ZoneOffset.UTC : ZoneOffset.of(offset);
    }

    public synchronized ObjectNode getSessionsStatisticsAggregation(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farms.farm(region, farmId);
        String id = text(input, "aggregationId");
        ObjectNode aggregation = store.require(region, AGGREGATIONS, id, "Aggregation");
        if (!farmId.equals(aggregation.path("_").path("farmId").asText())) {
            throw notFound("Aggregation " + id + " not found.", AGGREGATIONS, id);
        }
        if (System.currentTimeMillis() < aggregation.path("_").path("readyAt").asLong()) {
            return store.object().put("status", "IN_PROGRESS");
        }
        List<ObjectNode> statistics = statistics(region, farmId, aggregation);
        ObjectNode response = store.page(statistics, input, "statistics", item -> item.toString());
        response.put("status", "COMPLETED");
        return response;
    }

    /** Session statistics: each session's runtime in the window, grouped and bucketed by the requested period. */
    private List<ObjectNode> statistics(String region, String farmId, ObjectNode request) {
        double start = request.path("startTime").asDouble();
        double end = request.path("endTime").asDouble();
        ZoneId zone = request.hasNonNull("timezone") ? zone(request.get("timezone").asText()) : ZoneOffset.UTC;
        String period = request.path("period").asText(null);
        List<String> groupBy = new ArrayList<>();
        request.path("groupBy").forEach(field -> groupBy.add(field.asText()));
        List<String> queueIds = new ArrayList<>();
        request.path("resourceIds").path("queueIds").forEach(queue -> queueIds.add(queue.asText()));
        List<String> fleetIds = new ArrayList<>();
        request.path("resourceIds").path("fleetIds").forEach(fleet -> fleetIds.add(fleet.asText()));
        Map<String, double[]> runtime = new LinkedHashMap<>();
        Map<String, ObjectNode> keys = new LinkedHashMap<>();
        for (ObjectNode session : store.children(region, SESSIONS, "farmId", farmId)) {
            String queueId = session.path("_").path("queueId").asText();
            String fleetId = session.path("fleetId").asText();
            if ((!queueIds.isEmpty() && !queueIds.contains(queueId)) || (!fleetIds.isEmpty()
                    && !fleetIds.contains(fleetId))) {
                continue;
            }
            double sessionStart = Math.max(start, session.path("startedAt").asDouble());
            double sessionEnd = Math.min(end, session.path("endedAt").asDouble(now()));
            if (sessionEnd <= sessionStart) {
                continue;
            }
            ObjectNode key = store.object();
            for (String field : groupBy) {
                switch (field) {
                    case "QUEUE_ID" -> key.put("queueId", queueId);
                    case "FLEET_ID" -> key.put("fleetId", fleetId);
                    case "JOB_ID" -> {
                        String jobId = session.path("_").path("jobId").asText();
                        key.put("jobId", jobId);
                        store.get(region, JOBS, jobId).ifPresent(job -> key.put("jobName", job.path("name").asText()));
                    }
                    case "USER_ID" -> key.put("userId", session.path("_").path("userId").asText());
                    case "USAGE_TYPE" -> key.put("usageType", "COMPUTE");
                    case "INSTANCE_TYPE" -> key.put("instanceType", "customer-managed");
                    default -> {
                    }
                }
            }
            if (period != null) {
                Instant bucketStart = bucket(Instant.ofEpochMilli((long) (sessionStart * 1000)), period, zone);
                key.put("aggregationStartTime", bucketStart.toEpochMilli() / 1000.0);
                key.put("aggregationEndTime", next(bucketStart, period, zone).toEpochMilli() / 1000.0);
            }
            String index = key.toString();
            keys.put(index, key);
            double[] stats = runtime.computeIfAbsent(index, ignored -> new double[] {0, Double.MAX_VALUE, 0, 0});
            double seconds = sessionEnd - sessionStart;
            stats[0] += 1;
            stats[1] = Math.min(stats[1], seconds);
            stats[2] = Math.max(stats[2], seconds);
            stats[3] += seconds;
        }
        Set<String> wanted = new HashSet<>();
        request.path("statistics").forEach(statistic -> wanted.add(statistic.asText()));
        List<ObjectNode> result = new ArrayList<>();
        runtime.forEach((index, stats) -> {
            ObjectNode item = keys.get(index).deepCopy();
            item.put("count", (int) stats[0]);
            item.set("costInUsd", stats(wanted, 0, 0, 0, 0));
            item.set("runtimeInSeconds", stats(wanted, stats[1], stats[2], stats[3] / stats[0], stats[3]));
            result.add(item);
        });
        return result;
    }

    private ObjectNode stats(Set<String> wanted, double min, double max, double avg, double sum) {
        ObjectNode stats = store.object();
        if (wanted.contains("MIN")) {
            stats.put("min", min);
        }
        if (wanted.contains("MAX")) {
            stats.put("max", max);
        }
        if (wanted.contains("AVG")) {
            stats.put("avg", avg);
        }
        if (wanted.contains("SUM")) {
            stats.put("sum", sum);
        }
        return stats;
    }

    private static Instant bucket(Instant instant, String period, ZoneId zone) {
        ZonedDateTime time = instant.atZone(zone);
        return switch (period) {
            case "HOURLY" -> time.truncatedTo(ChronoUnit.HOURS).toInstant();
            case "WEEKLY" -> time.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .toInstant();
            case "MONTHLY" -> time.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).toInstant();
            default -> time.truncatedTo(ChronoUnit.DAYS).toInstant();
        };
    }

    private static Instant next(Instant start, String period, ZoneId zone) {
        ZonedDateTime time = start.atZone(zone);
        return switch (period) {
            case "HOURLY" -> time.plusHours(1).toInstant();
            case "WEEKLY" -> time.plusWeeks(1).toInstant();
            case "MONTHLY" -> time.plusMonths(1).toInstant();
            default -> time.plusDays(1).toInstant();
        };
    }
}
