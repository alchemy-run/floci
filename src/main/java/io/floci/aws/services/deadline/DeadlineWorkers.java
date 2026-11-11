package io.floci.aws.services.deadline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.floci.aws.services.deadline.DeadlineStore.conflict;
import static io.floci.aws.services.deadline.DeadlineStore.invalid;
import static io.floci.aws.services.deadline.DeadlineStore.notFound;
import static io.floci.aws.services.deadline.DeadlineStore.now;
import static io.floci.aws.services.deadline.DeadlineStore.oneOf;
import static io.floci.aws.services.deadline.DeadlineStore.present;
import static io.floci.aws.services.deadline.DeadlineStore.quota;
import static io.floci.aws.services.deadline.DeadlineStore.text;

/**
 * Deadline Cloud workers and the scheduling protocol the Deadline worker agent speaks: a worker of a
 * customer-managed fleet registers ({@code CreateWorker}), starts with its capabilities ({@code UpdateWorker}) and
 * then polls {@code UpdateWorkerSchedule}, reporting the session actions it ran and receiving new ones.
 * <p>
 * A worker is assigned the highest-priority ready task (oldest job first) of a queue its fleet is actively
 * associated with, whose step's host requirements its capabilities satisfy and whose queue limits have room. The
 * task runs in a session: the job's attachments are synced, the queue's, job's and step's environments are
 * entered, and the session takes further ready tasks of the same step before its environments are exited in
 * reverse order and it ends. Reported results move tasks: a success completes it, a failure retries it until
 * the job's {@code maxRetriesPerTask}, an interruption or an action never attempted requeues it, and a task
 * being canceled has its action canceled on the worker's next update.
 */
@ApplicationScoped
public class DeadlineWorkers {

    static final String WORKERS = DeadlineService.WORKERS;
    private static final Set<String> RUNNING_WORKER = Set.of("STARTED", "RUNNING", "IDLE");
    private static final Set<String> OPEN_ACTION = Set.of("ASSIGNED", "RUNNING", "CANCELING");
    private static final int UPDATE_INTERVAL_BUSY = 5;
    private static final int UPDATE_INTERVAL_IDLE = 15;

    private final DeadlineStore store;
    private final DeadlineService farms;
    private final DeadlineJobs jobs;

    @Inject
    public DeadlineWorkers(DeadlineStore store, DeadlineService farms, DeadlineJobs jobs) {
        this.store = store;
        this.farms = farms;
        this.jobs = jobs;
    }

    // ---------------------------------------------------------------- workers

    ObjectNode worker(String region, String farmId, String fleetId, String workerId) {
        farms.fleet(region, farmId, fleetId);
        ObjectNode worker = store.require(region, WORKERS, workerId, "Worker");
        if (!fleetId.equals(worker.path("fleetId").asText())) {
            throw notFound("Worker " + workerId + " not found.", WORKERS, workerId);
        }
        return worker;
    }

    private ObjectNode worker(String region, ObjectNode input) {
        return worker(region, text(input, "farmId"), text(input, "fleetId"), text(input, "workerId"));
    }

    private ObjectNode logConfiguration(String group, String stream) {
        ObjectNode log = store.object();
        log.put("logDriver", "awslogs");
        log.putObject("options").put("logGroupName", group).put("logStreamName", stream);
        log.putObject("parameters").put("interval", "15");
        return log;
    }

    public synchronized ObjectNode createWorker(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String fleetId = text(input, "fleetId");
        ObjectNode fleet = farms.fleet(region, farmId, fleetId);
        if (!fleet.path("configuration").has("customerManaged")) {
            throw invalid("Workers of service-managed fleet " + fleetId + " are created by Deadline Cloud.");
        }
        if (!"ACTIVE".equals(fleet.path("status").asText())) {
            throw conflict("Fleet " + fleetId + " is " + fleet.path("status").asText() + ".", "STATUS_CONFLICT",
                    DeadlineService.FLEETS, fleetId);
        }
        long active = store.children(region, WORKERS, "fleetId", fleetId).stream()
                .filter(worker -> !"STOPPED".equals(worker.path("status").asText())).count();
        if (active >= fleet.path("maxWorkerCount").asInt()) {
            throw quota("Fleet " + fleetId + " already has its maxWorkerCount of " + fleet.path("maxWorkerCount")
                    .asInt() + " workers.", WORKERS);
        }
        String workerId = store.id("worker");
        double now = now();
        ObjectNode worker = store.record();
        worker.put("farmId", farmId);
        worker.put("fleetId", fleetId);
        worker.put("workerId", workerId);
        if (input.path("hostProperties").isObject()) {
            worker.set("hostProperties", input.get("hostProperties").deepCopy());
        }
        worker.put("status", "CREATED");
        worker.set("log", logConfiguration("/aws/deadline/" + farmId + "/" + fleetId, workerId));
        worker.put("createdAt", now);
        worker.put("createdBy", farms.caller());
        ObjectNode meta = (ObjectNode) worker.get("_");
        meta.put("farmId", farmId).put("fleetId", fleetId).put("key", workerId)
                .put("arn", store.arn(region, "farm/" + farmId + "/fleet/" + fleetId + "/worker/" + workerId));
        ObjectNode tags = meta.putObject("tags");
        input.path("tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        store.put(region, WORKERS, workerId, worker);
        return store.object().put("workerId", workerId);
    }

    public synchronized ObjectNode getWorker(ObjectNode input, String region) {
        return store.view(worker(region, input));
    }

    public synchronized ObjectNode listWorkers(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String fleetId = text(input, "fleetId");
        farms.fleet(region, farmId, fleetId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode worker : store.children(region, WORKERS, "fleetId", fleetId)) {
            items.add(store.view(worker));
        }
        return store.page(items, input, "workers", item -> item.path("workerId").asText());
    }

    public synchronized ObjectNode batchGetWorker(ObjectNode input, String region) {
        ArrayNode found = store.array();
        ArrayNode errors = store.array();
        for (JsonNode identifier : input.path("identifiers")) {
            try {
                found.add(store.view(worker(region, text(identifier, "farmId"), text(identifier, "fleetId"),
                        text(identifier, "workerId"))));
            } catch (AwsException e) {
                errors.addObject().put("farmId", identifier.path("farmId").asText())
                        .put("fleetId", identifier.path("fleetId").asText())
                        .put("workerId", identifier.path("workerId").asText()).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
            }
        }
        ObjectNode response = store.object();
        response.set("workers", found);
        response.set("errors", errors);
        return response;
    }

    public synchronized ObjectNode deleteWorker(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        if (!Set.of("STOPPED", "CREATED", "NOT_RESPONDING", "NOT_COMPATIBLE").contains(worker.path("status")
                .asText())) {
            throw conflict("Stop worker " + worker.path("workerId").asText() + " before deleting it; it is "
                    + worker.path("status").asText() + ".", "STATUS_CONFLICT", WORKERS,
                    worker.path("workerId").asText());
        }
        interruptSessions(region, worker);
        store.delete(region, WORKERS, worker.path("workerId").asText());
        return store.object();
    }

    public synchronized ObjectNode updateWorker(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        ObjectNode fleet = farms.fleet(region, worker.path("farmId").asText(), worker.path("fleetId").asText());
        if (input.path("hostProperties").isObject()) {
            worker.set("hostProperties", input.get("hostProperties").deepCopy());
        }
        if (input.path("capabilities").isObject()) {
            JsonNode capabilities = input.get("capabilities");
            if (!capabilities.path("amounts").isArray() || !capabilities.path("attributes").isArray()) {
                throw invalid("capabilities must list amounts and attributes.");
            }
            ((ObjectNode) worker.get("_")).set("capabilities", capabilities.deepCopy());
        }
        ObjectNode response = store.object();
        if (present(input, "status")) {
            String status = oneOf(input, "status", Set.of("STARTED", "STOPPING", "STOPPED"), null);
            if ("STARTED".equals(status)) {
                JsonNode capabilities = worker.path("_").path("capabilities");
                if (!capabilities.isObject()) {
                    throw invalid("A worker must report its capabilities to start.");
                }
                status = withinFleet(capabilities, fleet.path("capabilities")) ? "STARTED" : "NOT_COMPATIBLE";
            } else {
                interruptSessions(region, worker);
            }
            worker.put("status", status);
        }
        worker.put("updatedAt", now());
        worker.put("updatedBy", farms.caller());
        store.put(region, WORKERS, worker.path("workerId").asText(), worker);
        response.set("log", worker.path("log").deepCopy());
        if (fleet.path("hostConfiguration").isObject()) {
            response.set("hostConfiguration", fleet.get("hostConfiguration").deepCopy());
        }
        return response;
    }

    /** Whether a worker's reported capabilities lie within what its fleet declares. */
    private static boolean withinFleet(JsonNode worker, JsonNode fleet) {
        for (JsonNode amount : worker.path("amounts")) {
            for (JsonNode declared : fleet.path("amounts")) {
                if (amount.path("name").asText().equals(declared.path("name").asText())) {
                    double value = amount.path("value").asDouble();
                    if (value < declared.path("min").asDouble() || (declared.has("max")
                            && value > declared.get("max").asDouble())) {
                        return false;
                    }
                }
            }
        }
        for (JsonNode attribute : worker.path("attributes")) {
            for (JsonNode declared : fleet.path("attributes")) {
                if (attribute.path("name").asText().equals(declared.path("name").asText())) {
                    for (JsonNode value : attribute.path("values")) {
                        boolean known = false;
                        for (JsonNode allowed : declared.path("values")) {
                            known |= allowed.asText().equalsIgnoreCase(value.asText());
                        }
                        if (!known) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** Ends a stopped worker's sessions: actions not done are interrupted and their tasks requeued. */
    private void interruptSessions(String region, ObjectNode worker) {
        for (ObjectNode session : store.list(region, DeadlineJobs.SESSIONS, session -> worker.path("workerId")
                .asText().equals(session.path("workerId").asText()) && !"ENDED".equals(session
                .path("lifecycleStatus").asText()))) {
            for (ObjectNode action : actions(region, session)) {
                if (OPEN_ACTION.contains(action.path("status").asText())) {
                    finish(region, action, "INTERRUPTED", store.object());
                }
            }
            end(region, session);
        }
    }

    // ---------------------------------------------------------------- scheduling

    public synchronized ObjectNode updateWorkerSchedule(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        String workerId = worker.path("workerId").asText();
        if (!RUNNING_WORKER.contains(worker.path("status").asText())) {
            throw conflict("Worker " + workerId + " is " + worker.path("status").asText() + "; start it first.",
                    "STATUS_CONFLICT", WORKERS, workerId);
        }
        ObjectNode fleet = farms.fleet(region, worker.path("farmId").asText(), worker.path("fleetId").asText());
        if (input.path("updatedSessionActions").isObject()) {
            input.get("updatedSessionActions").properties().forEach(entry -> report(region, worker,
                    entry.getKey(), entry.getValue()));
        }
        ObjectNode response = store.object();
        ObjectNode assigned = response.putObject("assignedSessions");
        ObjectNode cancel = response.putObject("cancelSessionActions");
        List<ObjectNode> open = store.list(region, DeadlineJobs.SESSIONS, session -> workerId.equals(session
                .path("workerId").asText()) && !"ENDED".equals(session.path("lifecycleStatus").asText()));
        if (open.isEmpty() && !"STOPPING".equals(worker.path("status").asText())) {
            ObjectNode session = assign(region, worker, fleet);
            if (session != null) {
                open.add(session);
            }
        }
        for (ObjectNode session : open) {
            advance(region, worker, session);
            List<ObjectNode> actions = actions(region, session);
            ArrayNode canceled = store.array();
            ArrayNode pending = store.array();
            for (ObjectNode action : actions) {
                String status = action.path("status").asText();
                if ("CANCELING".equals(status)) {
                    canceled.add(action.path("sessionActionId").asText());
                } else if ("ASSIGNED".equals(status) || "RUNNING".equals(status)) {
                    pending.addObject().put("sessionActionId", action.path("sessionActionId").asText())
                            .set("definition", action.path("_").path("assigned").deepCopy());
                }
            }
            if (!canceled.isEmpty()) {
                cancel.set(session.path("sessionId").asText(), canceled);
            }
            if ("ENDED".equals(session.path("lifecycleStatus").asText())) {
                continue;
            }
            ObjectNode entry = assigned.putObject(session.path("sessionId").asText());
            entry.put("queueId", session.path("_").path("queueId").asText());
            entry.put("jobId", session.path("_").path("jobId").asText());
            entry.set("sessionActions", pending);
            entry.set("logConfiguration", session.path("log").deepCopy());
        }
        boolean busy = !assigned.isEmpty();
        worker.put("status", "STOPPING".equals(worker.path("status").asText()) ? "STOPPING" : busy ? "RUNNING"
                : "IDLE");
        worker.put("updatedAt", now());
        store.put(region, WORKERS, workerId, worker);
        if (!"ACTIVE".equals(fleet.path("status").asText()) && !busy) {
            response.put("desiredWorkerStatus", "STOPPED");
        }
        response.put("updateIntervalSeconds", busy ? UPDATE_INTERVAL_BUSY : UPDATE_INTERVAL_IDLE);
        return response;
    }

    private List<ObjectNode> actions(String region, ObjectNode session) {
        List<ObjectNode> actions = store.children(region, DeadlineJobs.ACTIONS, "sessionId",
                session.path("sessionId").asText());
        actions.sort(Comparator.comparing(action -> action.path("_").path("index").asInt()));
        return actions;
    }

    /** The next ready task the worker can run, in a new session, or null when there is none. */
    private ObjectNode assign(String region, ObjectNode worker, ObjectNode fleet) {
        JsonNode capabilities = worker.path("_").path("capabilities");
        String fleetId = fleet.path("fleetId").asText();
        List<ObjectNode> candidates = new ArrayList<>();
        for (ObjectNode association : store.children(region, DeadlineService.QUEUE_FLEETS, "fleetId", fleetId)) {
            if (!"ACTIVE".equals(association.path("status").asText())) {
                continue;
            }
            String queueId = association.path("queueId").asText();
            Optional<ObjectNode> queue = store.get(region, DeadlineService.QUEUES, queueId);
            if (queue.isEmpty() || farms.blockedReason(region, queue.get()) != null) {
                continue;
            }
            for (ObjectNode job : store.children(region, DeadlineService.JOBS, "queueId", queueId)) {
                jobs.settle(region, job);
                String lifecycle = job.path("lifecycleStatus").asText();
                if (Set.of("CREATE_COMPLETE", "UPDATE_SUCCEEDED", "UPDATE_IN_PROGRESS").contains(lifecycle)
                        && !"SUSPENDED".equals(job.path("targetTaskRunStatus").asText())
                        && (job.path("maxWorkerCount").asInt(-1) < 0 || workersOn(region, job) < job
                        .path("maxWorkerCount").asInt())) {
                    candidates.add(job);
                }
            }
        }
        candidates.sort(Comparator.comparing((ObjectNode job) -> -job.path("priority").asInt())
                .thenComparing(job -> job.path("createdAt").asDouble()));
        for (ObjectNode job : candidates) {
            for (ObjectNode step : jobs.steps(region, job.path("jobId").asText())) {
                if (!DeadlineJobs.satisfies(capabilities, step.path("requiredCapabilities"))) {
                    continue;
                }
                ObjectNode task = nextTask(region, step);
                if (task == null) {
                    continue;
                }
                ArrayNode limits = acquire(region, job, step);
                if (limits == null) {
                    continue;
                }
                return startSession(region, worker, fleet, job, step, task, limits);
            }
        }
        return null;
    }

    private long workersOn(String region, ObjectNode job) {
        return store.children(region, DeadlineJobs.SESSIONS, "jobId", job.path("jobId").asText()).stream()
                .filter(session -> !"ENDED".equals(session.path("lifecycleStatus").asText())).count();
    }

    private ObjectNode nextTask(String region, ObjectNode step) {
        for (ObjectNode task : jobs.tasks(region, step.path("stepId").asText())) {
            if ("READY".equals(task.path("runStatus").asText())) {
                return task;
            }
        }
        return null;
    }

    /**
     * The queue limits a step's amount requirements take: one of each limit whose requirement the step names,
     * or null when one of them is full. A limit at {@code maxCount} -1 has no ceiling.
     */
    private ArrayNode acquire(String region, ObjectNode job, ObjectNode step) {
        String queueId = job.path("_").path("queueId").asText();
        ArrayNode acquired = store.array();
        for (JsonNode amount : step.path("requiredCapabilities").path("amounts")) {
            for (ObjectNode association : store.children(region, DeadlineService.QUEUE_LIMITS, "queueId",
                    queueId)) {
                if (!"ACTIVE".equals(association.path("status").asText())) {
                    continue;
                }
                Optional<ObjectNode> limit = store.get(region, DeadlineService.LIMITS, association.path("limitId")
                        .asText());
                if (limit.isEmpty() || !amount.path("name").asText().equals(limit.get()
                        .path("amountRequirementName").asText())) {
                    continue;
                }
                int max = limit.get().path("maxCount").asInt();
                int held = 0;
                for (ObjectNode action : store.list(region, DeadlineJobs.ACTIONS, action -> OPEN_ACTION.contains(
                        action.path("status").asText()))) {
                    for (JsonNode taken : action.path("acquiredLimits")) {
                        held += limit.get().path("limitId").asText().equals(taken.path("limitId").asText())
                                ? taken.path("count").asInt(1) : 0;
                    }
                }
                if (max >= 0 && held + 1 > max) {
                    return null;
                }
                acquired.addObject().put("limitId", limit.get().path("limitId").asText()).put("count", 1);
            }
        }
        return acquired;
    }

    private ObjectNode startSession(String region, ObjectNode worker, ObjectNode fleet, ObjectNode job,
                                    ObjectNode step, ObjectNode task, ArrayNode limits) {
        String farmId = job.path("_").path("farmId").asText();
        String queueId = job.path("_").path("queueId").asText();
        String sessionId = store.id("session");
        double now = now();
        ObjectNode session = store.record();
        session.put("sessionId", sessionId);
        session.put("fleetId", fleet.path("fleetId").asText());
        session.put("workerId", worker.path("workerId").asText());
        session.put("startedAt", now);
        session.put("lifecycleStatus", "STARTED");
        session.put("updatedAt", now);
        session.set("log", logConfiguration("/aws/deadline/" + farmId + "/" + queueId, sessionId));
        if (worker.has("hostProperties")) {
            session.set("hostProperties", worker.get("hostProperties").deepCopy());
        }
        session.set("workerLog", worker.path("log").deepCopy());
        ObjectNode meta = (ObjectNode) session.get("_");
        meta.put("farmId", farmId).put("queueId", queueId).put("jobId", job.path("jobId").asText())
                .put("stepId", step.path("stepId").asText()).put("key", sessionId)
                .put("userId", job.path("createdBy").asText()).put("next", 0);
        ArrayNode entered = meta.putArray("environments");
        store.put(region, DeadlineJobs.SESSIONS, sessionId, session);

        if (job.path("attachments").isObject()) {
            addAction(region, session, assigned("syncInputJobAttachments", store.object().put("stepId",
                    step.path("stepId").asText())), null);
        }
        for (String environmentId : environments(region, job, step)) {
            addAction(region, session, assigned("envEnter", store.object().put("environmentId", environmentId)),
                    null);
            entered.add(environmentId);
        }
        store.put(region, DeadlineJobs.SESSIONS, sessionId, session);
        runTask(region, session, step, task, limits);
        return session;
    }

    /**
     * The environments a step's session enters, outermost first: the queue's
     * ({@code JOB:<jobId>:<queueEnvironmentId>}), the job's ({@code JOB:<jobId>:<name>}), then the step's
     * ({@code STEP:<stepId>:<name>}).
     */
    private List<String> environments(String region, ObjectNode job, ObjectNode step) {
        List<String> ids = new ArrayList<>();
        List<ObjectNode> queueEnvironments = store.children(region, DeadlineService.QUEUE_ENVIRONMENTS, "queueId",
                job.path("_").path("queueId").asText());
        queueEnvironments.sort(Comparator.comparing(environment -> environment.path("priority").asInt()));
        String jobId = job.path("jobId").asText();
        for (ObjectNode environment : queueEnvironments) {
            ids.add("JOB:" + jobId + ":" + environment.path("queueEnvironmentId").asText());
        }
        for (JsonNode environment : job.path("_").path("parsed").path("jobEnvironments")) {
            ids.add("JOB:" + jobId + ":" + environment.path("name").asText());
        }
        for (JsonNode environment : step.path("_").path("template").path("stepEnvironments")) {
            ids.add("STEP:" + step.path("stepId").asText() + ":" + environment.path("name").asText());
        }
        return ids;
    }

    private ObjectNode assigned(String kind, ObjectNode body) {
        ObjectNode definition = store.object();
        definition.set(kind, body);
        return definition;
    }

    private ObjectNode addAction(String region, ObjectNode session, ObjectNode definition, ArrayNode limits) {
        ObjectNode meta = (ObjectNode) session.get("_");
        int index = meta.path("next").asInt();
        meta.put("next", index + 1);
        store.put(region, DeadlineJobs.SESSIONS, session.path("sessionId").asText(), session);
        String actionId = "sessionaction-" + session.path("sessionId").asText().substring("session-".length())
                + "-" + index;
        ObjectNode action = store.record();
        action.put("sessionActionId", actionId);
        action.put("status", "ASSIGNED");
        action.put("sessionId", session.path("sessionId").asText());
        ObjectNode summary = definition.deepCopy();
        action.set("definition", summary);
        if (limits != null) {
            action.set("acquiredLimits", limits);
        }
        ObjectNode actionMeta = (ObjectNode) action.get("_");
        actionMeta.put("farmId", meta.path("farmId").asText()).put("queueId", meta.path("queueId").asText())
                .put("jobId", meta.path("jobId").asText()).put("sessionId", session.path("sessionId").asText())
                .put("fleetId", session.path("fleetId").asText()).put("workerId", session.path("workerId").asText())
                .put("key", actionId).put("index", index);
        actionMeta.set("assigned", definition);
        store.put(region, DeadlineJobs.ACTIONS, actionId, action);
        return action;
    }

    private void runTask(String region, ObjectNode session, ObjectNode step, ObjectNode task, ArrayNode limits) {
        ObjectNode body = store.object();
        body.put("taskId", task.path("taskId").asText());
        body.put("stepId", step.path("stepId").asText());
        body.set("parameters", task.path("parameters").deepCopy());
        ObjectNode action = addAction(region, session, assigned("taskRun", body), limits);
        String previous = task.path("runStatus").asText();
        task.put("runStatus", "ASSIGNED");
        task.put("latestSessionActionId", action.path("sessionActionId").asText());
        task.put("updatedAt", now());
        store.put(region, DeadlineJobs.TASKS, task.path("taskId").asText(), task);
        jobs.taskEvent(region, task, previous, "ASSIGNED");
        store.get(region, DeadlineService.JOBS, task.path("_").path("jobId").asText())
                .ifPresent(job -> jobs.refresh(region, job, true));
    }

    /**
     * Moves an open session on once its actions are done: another ready task of its step, or, when there is
     * none (or the session is ending), the exit of each environment it entered, innermost first, and the end.
     */
    private void advance(String region, ObjectNode worker, ObjectNode session) {
        List<ObjectNode> actions = actions(region, session);
        boolean busy = false;
        boolean failed = false;
        for (ObjectNode action : actions) {
            busy |= OPEN_ACTION.contains(action.path("status").asText());
            failed |= Set.of("FAILED", "INTERRUPTED").contains(action.path("status").asText())
                    && !action.path("definition").has("taskRun");
        }
        if (busy) {
            return;
        }
        ObjectNode meta = (ObjectNode) session.get("_");
        boolean exiting = meta.path("exiting").asBoolean(false);
        if (!exiting && !failed && !"ENDED".equals(session.path("targetLifecycleStatus").asText())
                && !"STOPPING".equals(worker.path("status").asText())) {
            Optional<ObjectNode> job = store.get(region, DeadlineService.JOBS, meta.path("jobId").asText());
            Optional<ObjectNode> step = store.get(region, DeadlineJobs.STEPS, meta.path("stepId").asText());
            if (job.isPresent() && step.isPresent() && !"SUSPENDED".equals(job.get().path("targetTaskRunStatus")
                    .asText())) {
                ObjectNode task = nextTask(region, step.get());
                if (task != null) {
                    ArrayNode limits = acquire(region, job.get(), step.get());
                    if (limits != null) {
                        runTask(region, session, step.get(), task, limits);
                        return;
                    }
                }
            }
        }
        if (!exiting) {
            meta.put("exiting", true);
            List<String> entered = new ArrayList<>();
            meta.path("environments").forEach(environment -> entered.add(environment.asText()));
            boolean anyExit = false;
            for (int index = entered.size() - 1; index >= 0; index--) {
                if (!failed || enteredSuccessfully(actions, entered.get(index))) {
                    addAction(region, session, assigned("envExit", store.object().put("environmentId",
                            entered.get(index))), null);
                    anyExit = true;
                }
            }
            store.put(region, DeadlineJobs.SESSIONS, session.path("sessionId").asText(), session);
            if (anyExit) {
                return;
            }
        }
        end(region, session);
    }

    private static boolean enteredSuccessfully(List<ObjectNode> actions, String environmentId) {
        for (ObjectNode action : actions) {
            if (environmentId.equals(action.path("definition").path("envEnter").path("environmentId").asText())) {
                return "SUCCEEDED".equals(action.path("status").asText());
            }
        }
        return false;
    }

    private void end(String region, ObjectNode session) {
        double now = now();
        for (ObjectNode action : actions(region, session)) {
            if ("ASSIGNED".equals(action.path("status").asText())) {
                finish(region, action, "NEVER_ATTEMPTED", store.object());
            }
        }
        session.put("lifecycleStatus", "ENDED");
        session.put("endedAt", now);
        session.put("updatedAt", now);
        store.put(region, DeadlineJobs.SESSIONS, session.path("sessionId").asText(), session);
    }

    // ---------------------------------------------------------------- reports

    private void report(String region, ObjectNode worker, String actionId, JsonNode update) {
        ObjectNode action = store.get(region, DeadlineJobs.ACTIONS, actionId).orElse(null);
        if (action == null || !worker.path("workerId").asText().equals(action.path("_").path("workerId").asText())) {
            throw invalid("Session action " + actionId + " is not assigned to worker " + worker.path("workerId")
                    .asText() + ".");
        }
        double now = now();
        action.put("workerUpdatedAt", now);
        for (String field : List.of("progressMessage")) {
            if (update.hasNonNull(field)) {
                action.put(field, update.get(field).asText());
            }
        }
        if (update.hasNonNull("progressPercent")) {
            action.put("progressPercent", update.get("progressPercent").asDouble());
        }
        if (update.hasNonNull("processExitCode")) {
            action.put("processExitCode", update.get("processExitCode").asInt());
        }
        if (update.path("manifests").isArray()) {
            action.set("manifests", update.get("manifests").deepCopy());
        }
        if (update.hasNonNull("startedAt") && !action.has("startedAt")) {
            action.put("startedAt", timestamp(update.get("startedAt"), now));
        }
        String completed = update.path("completedStatus").asText(null);
        if (completed == null) {
            if ("ASSIGNED".equals(action.path("status").asText())) {
                action.put("status", "RUNNING");
                store.put(region, DeadlineJobs.ACTIONS, actionId, action);
                taskStarted(region, action);
            } else {
                store.put(region, DeadlineJobs.ACTIONS, actionId, action);
            }
            return;
        }
        oneOf(update, "completedStatus", Set.of("SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELED",
                "NEVER_ATTEMPTED"), null);
        if (update.hasNonNull("endedAt")) {
            action.put("endedAt", timestamp(update.get("endedAt"), now));
        }
        finish(region, action, completed, update);
    }

    private static double timestamp(JsonNode value, double fallback) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return OffsetDateTime.parse(value.asText()).toInstant().toEpochMilli() / 1000.0;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private void taskStarted(String region, ObjectNode action) {
        String taskId = action.path("definition").path("taskRun").path("taskId").asText(null);
        if (taskId == null) {
            return;
        }
        store.get(region, DeadlineJobs.TASKS, taskId).ifPresent(task -> {
            String previous = task.path("runStatus").asText();
            if (Set.of("ASSIGNED", "STARTING", "SCHEDULED").contains(previous)) {
                task.put("runStatus", "RUNNING");
                task.put("startedAt", action.path("startedAt").asDouble(now()));
                task.put("updatedAt", now());
                store.put(region, DeadlineJobs.TASKS, taskId, task);
                jobs.taskEvent(region, task, previous, "RUNNING");
                store.get(region, DeadlineService.JOBS, task.path("_").path("jobId").asText())
                        .ifPresent(job -> jobs.refresh(region, job, true));
            }
        });
    }

    /** Records an action's outcome and moves its task accordingly. */
    private void finish(String region, ObjectNode action, String completed, JsonNode update) {
        double now = now();
        action.put("status", completed);
        if (!action.has("endedAt")) {
            action.put("endedAt", now);
        }
        store.put(region, DeadlineJobs.ACTIONS, action.path("sessionActionId").asText(), action);
        String taskId = action.path("definition").path("taskRun").path("taskId").asText(null);
        if (taskId == null) {
            return;
        }
        ObjectNode task = store.get(region, DeadlineJobs.TASKS, taskId).orElse(null);
        ObjectNode job = store.get(region, DeadlineService.JOBS, action.path("_").path("jobId").asText())
                .orElse(null);
        if (task == null || job == null || !action.path("sessionActionId").asText().equals(task
                .path("latestSessionActionId").asText())) {
            return;
        }
        String previous = task.path("runStatus").asText();
        String target = task.path("targetRunStatus").asText(null);
        String next = switch (completed) {
            case "SUCCEEDED" -> target != null && !"READY".equals(target) ? target : "SUCCEEDED";
            case "FAILED" -> {
                if (target != null) {
                    yield target;
                }
                int retries = task.path("failureRetryCount").asInt() + 1;
                task.put("failureRetryCount", retries);
                yield retries > job.path("maxRetriesPerTask").asInt(5) ? "FAILED" : "READY";
            }
            case "CANCELED" -> target != null ? target : "CANCELED";
            default -> target != null ? target : "READY";
        };
        task.remove("targetRunStatus");
        task.put("runStatus", next);
        task.put("updatedAt", now);
        if (DeadlineJobs.TERMINAL.contains(next)) {
            task.put("endedAt", now);
        }
        store.put(region, DeadlineJobs.TASKS, taskId, task);
        if (!previous.equals(next)) {
            jobs.taskEvent(region, task, previous, next);
        }
        if ("FAILED".equals(next)) {
            failJobIfTooManyFailures(region, job);
        }
        jobs.refresh(region, job, true);
    }

    /** A job whose failed tasks reach its {@code maxFailedTasksCount} cancels its remaining tasks. */
    private void failJobIfTooManyFailures(String region, ObjectNode job) {
        int failed = 0;
        List<ObjectNode> all = new ArrayList<>();
        for (ObjectNode step : jobs.steps(region, job.path("jobId").asText())) {
            for (ObjectNode task : jobs.tasks(region, step.path("stepId").asText())) {
                failed += "FAILED".equals(task.path("runStatus").asText()) ? 1 : 0;
                all.add(task);
            }
        }
        if (failed < job.path("maxFailedTasksCount").asInt(100)) {
            return;
        }
        for (ObjectNode task : all) {
            if (!DeadlineJobs.TERMINAL.contains(task.path("runStatus").asText())) {
                jobs.target(region, task, "CANCELED");
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    public synchronized ObjectNode listSessionsForWorker(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode session : store.list(region, DeadlineJobs.SESSIONS, session -> worker.path("workerId")
                .asText().equals(session.path("workerId").asText()))) {
            ObjectNode summary = store.view(session, "sessionId", "startedAt", "lifecycleStatus", "endedAt");
            summary.put("queueId", session.path("_").path("queueId").asText());
            summary.put("jobId", session.path("_").path("jobId").asText());
            items.add(summary);
        }
        return store.page(items, input, "sessions", item -> item.path("sessionId").asText());
    }

    public synchronized ObjectNode updateSession(ObjectNode input, String region) {
        ObjectNode job = jobs.job(region, text(input, "farmId"), text(input, "queueId"), text(input, "jobId"));
        String sessionId = text(input, "sessionId");
        ObjectNode session = store.require(region, DeadlineJobs.SESSIONS, sessionId, "Session");
        if (!job.path("jobId").asText().equals(session.path("_").path("jobId").asText())) {
            throw notFound("Session " + sessionId + " not found.", DeadlineJobs.SESSIONS, sessionId);
        }
        oneOf(input, "targetLifecycleStatus", Set.of("ENDED"), null);
        if ("ENDED".equals(session.path("lifecycleStatus").asText())) {
            throw conflict("Session " + sessionId + " has ended.", "STATUS_CONFLICT", DeadlineJobs.SESSIONS,
                    sessionId);
        }
        session.put("targetLifecycleStatus", "ENDED");
        session.put("updatedAt", now());
        session.put("updatedBy", farms.caller());
        store.put(region, DeadlineJobs.SESSIONS, sessionId, session);
        for (ObjectNode action : actions(region, session)) {
            if ("RUNNING".equals(action.path("status").asText())) {
                action.put("status", "CANCELING");
                store.put(region, DeadlineJobs.ACTIONS, action.path("sessionActionId").asText(), action);
            } else if ("ASSIGNED".equals(action.path("status").asText())) {
                finish(region, action, "NEVER_ATTEMPTED", store.object());
            }
        }
        return store.object();
    }

    // ---------------------------------------------------------------- credentials and job entities

    public synchronized ObjectNode assumeFleetRoleForWorker(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        ObjectNode fleet = farms.fleet(region, worker.path("farmId").asText(), worker.path("fleetId").asText());
        return farms.credentials(fleet.path("roleArn").asText(null), "Fleet " + fleet.path("fleetId").asText());
    }

    public synchronized ObjectNode assumeQueueRoleForWorker(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        String queueId = text(input, "queueId");
        ObjectNode queue = farms.queue(region, worker.path("farmId").asText(), queueId);
        if (store.get(region, DeadlineService.QUEUE_FLEETS, queueId + "_" + worker.path("fleetId").asText())
                .isEmpty()) {
            throw invalid("Worker " + worker.path("workerId").asText() + "'s fleet is not associated with queue "
                    + queueId + ".");
        }
        if (!queue.hasNonNull("roleArn")) {
            return store.object();
        }
        return farms.credentials(queue.get("roleArn").asText(), "Queue " + queueId);
    }

    public synchronized ObjectNode batchGetJobEntity(ObjectNode input, String region) {
        ObjectNode worker = worker(region, input);
        ArrayNode entities = store.array();
        ArrayNode errors = store.array();
        for (JsonNode identifier : input.path("identifiers")) {
            String kind = identifier.fieldNames().hasNext() ? identifier.fieldNames().next() : "";
            JsonNode ids = identifier.path(kind);
            try {
                entities.add(entity(region, worker, kind, ids));
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                ObjectNode details = error.putObject(kind);
                details.setAll((ObjectNode) ids.deepCopy());
                details.put("code", e.getErrorCode()).put("message", e.getMessage());
            }
        }
        ObjectNode response = store.object();
        response.set("entities", entities);
        response.set("errors", errors);
        return response;
    }

    private ObjectNode entity(String region, ObjectNode worker, String kind, JsonNode ids) {
        String jobId = text(ids, "jobId");
        ObjectNode job = store.require(region, DeadlineService.JOBS, jobId, "Job");
        boolean assignedHere = !store.list(region, DeadlineJobs.SESSIONS, session -> jobId.equals(session.path("_")
                .path("jobId").asText()) && worker.path("workerId").asText().equals(session.path("workerId")
                .asText())).isEmpty();
        if (!assignedHere) {
            throw invalid("Job " + jobId + " is not assigned to worker " + worker.path("workerId").asText() + ".");
        }
        ObjectNode queue = store.require(region, DeadlineService.QUEUES, job.path("_").path("queueId").asText(),
                "Queue");
        ObjectNode entity = store.object();
        switch (kind) {
            case "jobDetails" -> {
                ObjectNode details = entity.putObject("jobDetails");
                details.put("jobId", jobId);
                if (queue.path("jobAttachmentSettings").isObject()) {
                    details.set("jobAttachmentSettings", queue.get("jobAttachmentSettings").deepCopy());
                }
                details.set("jobRunAsUser", queue.path("jobRunAsUser").isObject() ? queue.get("jobRunAsUser")
                        .deepCopy() : store.object().put("runAs", "WORKER_AGENT_USER"));
                details.put("logGroupName", "/aws/deadline/" + queue.path("farmId").asText() + "/"
                        + queue.path("queueId").asText());
                if (queue.hasNonNull("roleArn")) {
                    details.put("queueRoleArn", queue.get("roleArn").asText());
                }
                details.set("parameters", job.path("parameters").deepCopy());
                details.put("schemaVersion", DeadlineTemplates.JOB_SPEC);
                details.set("pathMappingRules", pathMappingRules(region, job, worker));
            }
            case "jobAttachmentDetails" -> {
                if (!job.path("attachments").isObject()) {
                    throw notFound("Job " + jobId + " has no attachments.", "attachments", jobId);
                }
                entity.putObject("jobAttachmentDetails").put("jobId", jobId).set("attachments",
                        job.get("attachments").deepCopy());
            }
            case "stepDetails" -> {
                String stepId = text(ids, "stepId");
                ObjectNode step = jobs.step(region, job, stepId);
                ObjectNode details = entity.putObject("stepDetails");
                details.put("jobId", jobId).put("stepId", stepId).put("schemaVersion", DeadlineTemplates.JOB_SPEC);
                details.set("template", step.path("_").path("template").deepCopy());
                ArrayNode dependencies = details.putArray("dependencies");
                step.path("_").path("dependsOn").forEach(dependency -> dependencies.add(dependency.asText()));
            }
            case "environmentDetails" -> {
                String environmentId = text(ids, "environmentId");
                ObjectNode details = entity.putObject("environmentDetails");
                details.put("jobId", jobId).put("environmentId", environmentId);
                JsonNode template = environment(region, job, environmentId);
                details.put("schemaVersion", environmentId.contains(":queueenv-")
                        ? DeadlineTemplates.ENVIRONMENT_SPEC : DeadlineTemplates.JOB_SPEC);
                details.set("template", template);
            }
            default -> throw invalid("Unknown job entity " + kind + ".");
        }
        return entity;
    }

    private JsonNode environment(String region, ObjectNode job, String environmentId) {
        String jobPrefix = "JOB:" + job.path("jobId").asText() + ":";
        if (environmentId.startsWith(jobPrefix)) {
            String name = environmentId.substring(jobPrefix.length());
            if (name.startsWith("queueenv-")) {
                ObjectNode environment = store.require(region, DeadlineService.QUEUE_ENVIRONMENTS, name,
                        "Environment");
                return jobs.templates().parse(environment.path("template").asText(), environment
                        .path("templateType").asText()).path("environment").deepCopy();
            }
            for (JsonNode environment : job.path("_").path("parsed").path("jobEnvironments")) {
                if (name.equals(environment.path("name").asText())) {
                    return environment.deepCopy();
                }
            }
        } else if (environmentId.startsWith("STEP:")) {
            String[] parts = environmentId.split(":", 3);
            if (parts.length == 3) {
                ObjectNode step = jobs.step(region, job, parts[1]);
                for (JsonNode environment : step.path("_").path("template").path("stepEnvironments")) {
                    if (parts[2].equals(environment.path("name").asText())) {
                        return environment.deepCopy();
                    }
                }
            }
        }
        throw notFound("Environment " + environmentId + " not found.", "environments", environmentId);
    }

    /**
     * Path mapping from the job's storage profile to the worker fleet's: each shared file system location the
     * two profiles both name maps the job's path to the fleet's.
     */
    private ArrayNode pathMappingRules(String region, ObjectNode job, ObjectNode worker) {
        ArrayNode rules = store.array();
        String source = job.path("storageProfileId").asText(null);
        Optional<ObjectNode> fleet = store.get(region, DeadlineService.FLEETS, worker.path("fleetId").asText());
        if (source == null || fleet.isEmpty()) {
            return rules;
        }
        JsonNode configuration = fleet.get().path("configuration");
        String destination = configuration.path("customerManaged").path("storageProfileId").asText(
                configuration.path("serviceManagedEc2").path("storageProfileId").asText(null));
        if (destination == null || destination.equals(source)) {
            return rules;
        }
        Optional<ObjectNode> from = store.get(region, DeadlineService.PROFILES, source);
        Optional<ObjectNode> to = store.get(region, DeadlineService.PROFILES, destination);
        if (from.isEmpty() || to.isEmpty()) {
            return rules;
        }
        String format = "WINDOWS".equals(from.get().path("osFamily").asText()) ? "windows" : "posix";
        for (JsonNode location : from.get().path("fileSystemLocations")) {
            if (!"SHARED".equals(location.path("type").asText())) {
                continue;
            }
            for (JsonNode target : to.get().path("fileSystemLocations")) {
                if (location.path("name").asText().equals(target.path("name").asText())) {
                    rules.addObject().put("sourcePathFormat", format).put("sourcePath", location.path("path")
                            .asText()).put("destinationPath", target.path("path").asText());
                }
            }
        }
        return rules;
    }
}
