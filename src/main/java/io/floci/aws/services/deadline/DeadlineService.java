package io.floci.aws.services.deadline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.deadline.DeadlineStore.conflict;
import static io.floci.aws.services.deadline.DeadlineStore.invalid;
import static io.floci.aws.services.deadline.DeadlineStore.notFound;
import static io.floci.aws.services.deadline.DeadlineStore.now;
import static io.floci.aws.services.deadline.DeadlineStore.oneOf;
import static io.floci.aws.services.deadline.DeadlineStore.optional;
import static io.floci.aws.services.deadline.DeadlineStore.present;
import static io.floci.aws.services.deadline.DeadlineStore.text;

/**
 * Deadline Cloud farms and what they hold: queues, fleets, storage profiles, budgets, limits, queue-fleet and
 * queue-limit associations, queue environments and members, plus account-level monitors, license endpoints and
 * metered products, tags, and the role credentials Deadline vends.
 * <p>
 * A farm refuses deletion while any of its resources exists; a queue or limit association must be stopped (and
 * settle to {@code STOPPED}) before it is deleted; a storage profile a queue allows cannot be deleted. Creating a
 * queue or fleet creates its log group under {@code /aws/deadline/<farmId>/}, which, as on AWS, outlives it.
 */
@ApplicationScoped
public class DeadlineService {

    private static final Logger LOG = Logger.getLogger(DeadlineService.class);
    static final String FARMS = "farms";
    static final String QUEUES = "queues";
    static final String FLEETS = "fleets";
    static final String PROFILES = "storage-profiles";
    static final String BUDGETS = "budgets";
    static final String LIMITS = "limits";
    static final String QUEUE_FLEETS = "queue-fleet-associations";
    static final String QUEUE_LIMITS = "queue-limit-associations";
    static final String QUEUE_ENVIRONMENTS = "queue-environments";
    static final String MEMBERS = "members";
    static final String MONITORS = "monitors";
    static final String LICENSE_ENDPOINTS = "license-endpoints";
    static final String METERED = "metered-products";
    static final String WORKERS = "workers";
    static final String JOBS = "jobs";
    static final Set<String> ACTIVE_TASK_STATUSES = Set.of("READY", "ASSIGNED", "STARTING", "SCHEDULED", "RUNNING",
            "INTERRUPTING");

    /** The usage-based licensing products license endpoints serve: id, family, vendor, port. */
    private static final List<String[]> PRODUCTS = List.of(
            new String[] {"3dsmax-2025", "3dsmax", "Autodesk", "2701"},
            new String[] {"maya-2025", "maya", "Autodesk", "2701"},
            new String[] {"arnold-7", "arnold", "Autodesk", "2701"},
            new String[] {"houdini-20", "houdini", "SideFX", "1715"},
            new String[] {"nuke-15", "nuke", "Foundry", "6101"},
            new String[] {"katana-7", "katana", "Foundry", "6101"},
            new String[] {"redshift-3", "redshift", "Maxon", "7054"},
            new String[] {"cinema4d-2025", "cinema4d", "Maxon", "7057"},
            new String[] {"vray-6", "vray", "Chaos", "30304"},
            new String[] {"keyshot-2024", "keyshot", "Luxion", "2703"});

    private final DeadlineStore store;
    private final DeadlineEvents events;
    private final Instance<IamService> iam;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<SsoAdminService> sso;
    private final Instance<Ec2Service> ec2;
    private final long transitionMillis;

    @Inject
    public DeadlineService(DeadlineStore store, DeadlineEvents events, Instance<IamService> iam,
                           Instance<CloudWatchLogsService> logs, Instance<SsoAdminService> sso,
                           Instance<Ec2Service> ec2, EmulatorConfig config) {
        this.store = store;
        this.events = events;
        this.iam = iam;
        this.logs = logs;
        this.sso = sso;
        this.ec2 = ec2;
        this.transitionMillis = config.services().deadline().transitionMillis();
    }

    long transitionMillis() {
        return transitionMillis;
    }

    // ---------------------------------------------------------------- shared

    private ObjectNode created(ObjectNode record) {
        double now = now();
        record.put("createdAt", now);
        record.put("createdBy", caller());
        return record;
    }

    private void updated(ObjectNode record) {
        record.put("updatedAt", now());
        record.put("updatedBy", caller());
    }

    /** The caller as Deadline records it in createdBy and updatedBy. */
    String caller() {
        return store.regions().buildGlobalArn("iam", store.account(), "root");
    }

    private void tags(ObjectNode record, JsonNode tags) {
        ObjectNode stored = ((ObjectNode) record.get("_")).putObject("tags");
        if (tags != null && tags.isObject()) {
            tags.properties().forEach(entry -> stored.put(entry.getKey(), entry.getValue().asText()));
        }
    }

    private static void displayName(JsonNode input) {
        String name = text(input, "displayName");
        if (name.length() > 100) {
            throw invalid("displayName must be at most 100 characters.");
        }
    }

    /** A role ARN in the account must name an existing role. */
    private String role(String roleArn, String field) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (iam.isResolvable() && arn.accountId().equals(store.account())) {
            try {
                iam.get().getRole(arn.resource().substring(arn.resource().lastIndexOf('/') + 1));
            } catch (AwsException e) {
                throw invalid("Role " + roleArn + " does not exist.");
            }
        }
        return roleArn;
    }

    private void logGroup(String region, String name) {
        if (!logs.isResolvable()) {
            return;
        }
        try {
            if (!logs.get().logGroupExists(name, region)) {
                logs.get().createLogGroup(name, null, Map.of(), region);
            }
        } catch (AwsException e) {
            LOG.debugv("Could not create Deadline log group {0}: {1}", name, e.getMessage());
        }
    }

    /** Applies elapsed transitions of a record whose {@code _.readyAt} has passed. */
    private boolean due(ObjectNode record) {
        return System.currentTimeMillis() >= record.path("_").path("readyAt").asLong();
    }

    private void readyIn(ObjectNode record) {
        ((ObjectNode) record.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
    }

    // ---------------------------------------------------------------- farms

    ObjectNode farm(String region, String farmId) {
        return store.require(region, FARMS, farmId, "Farm");
    }

    public synchronized ObjectNode createFarm(ObjectNode input, String region) {
        displayName(input);
        double scale = input.path("costScaleFactor").asDouble(1.0);
        if (scale < 0 || scale > 100) {
            throw invalid("costScaleFactor must be between 0 and 100.");
        }
        String id = store.id("farm");
        ObjectNode farm = created(store.record());
        farm.put("farmId", id);
        farm.put("displayName", input.get("displayName").asText());
        if (present(input, "description")) {
            farm.put("description", input.get("description").asText());
        }
        if (present(input, "kmsKeyArn")) {
            farm.put("kmsKeyArn", input.get("kmsKeyArn").asText());
        }
        farm.put("costScaleFactor", scale);
        ((ObjectNode) farm.get("_")).put("arn", store.arn(region, "farm/" + id));
        tags(farm, input.get("tags"));
        store.put(region, FARMS, id, farm);
        return store.object().put("farmId", id);
    }

    public synchronized ObjectNode getFarm(ObjectNode input, String region) {
        return store.view(farm(region, text(input, "farmId")));
    }

    public synchronized ObjectNode updateFarm(ObjectNode input, String region) {
        ObjectNode farm = farm(region, text(input, "farmId"));
        if (present(input, "displayName")) {
            displayName(input);
            farm.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "description")) {
            farm.put("description", input.get("description").asText());
        }
        if (present(input, "costScaleFactor")) {
            double scale = input.get("costScaleFactor").asDouble();
            if (scale < 0 || scale > 100) {
                throw invalid("costScaleFactor must be between 0 and 100.");
            }
            farm.put("costScaleFactor", scale);
        }
        updated(farm);
        store.put(region, FARMS, farm.path("farmId").asText(), farm);
        return store.object();
    }

    public synchronized ObjectNode deleteFarm(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        for (String kind : List.of(QUEUES, FLEETS, PROFILES, BUDGETS, LIMITS, QUEUE_FLEETS, QUEUE_LIMITS)) {
            if (!store.children(region, kind, "farmId", farmId).isEmpty()) {
                throw conflict("Farm " + farmId + " still has " + kind.replace('-', ' ') + "; delete them first.",
                        "RESOURCE_IN_USE", FARMS, farmId);
            }
        }
        for (ObjectNode member : store.children(region, MEMBERS, "farmId", farmId)) {
            store.delete(region, MEMBERS, member.path("_").path("key").asText());
        }
        store.delete(region, FARMS, farmId);
        return store.object();
    }

    public synchronized ObjectNode listFarms(ObjectNode input, String region) {
        String principal = optional(input, "principalId");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode farm : store.list(region, FARMS)) {
            if (principal != null && memberLevel(region, "farm/" + farm.path("farmId").asText(), principal) == null) {
                continue;
            }
            ObjectNode summary = store.view(farm, "farmId", "displayName", "kmsKeyArn", "createdAt", "createdBy",
                    "updatedAt", "updatedBy");
            if (principal != null) {
                summary.put("membershipLevel", memberLevel(region, "farm/" + farm.path("farmId").asText(), principal));
            }
            items.add(summary);
        }
        return store.page(items, input, "farms", item -> item.path("farmId").asText());
    }

    // ---------------------------------------------------------------- queues

    ObjectNode queue(String region, String farmId, String queueId) {
        farm(region, farmId);
        ObjectNode queue = store.require(region, QUEUES, queueId, "Queue");
        if (!farmId.equals(queue.path("farmId").asText())) {
            throw notFound("Queue " + queueId + " not found.", QUEUES, queueId);
        }
        return queue;
    }

    public synchronized ObjectNode createQueue(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        displayName(input);
        String id = store.id("queue");
        ObjectNode queue = created(store.record());
        queue.put("farmId", farmId);
        queue.put("queueId", id);
        queue.put("displayName", input.get("displayName").asText());
        queue.put("defaultBudgetAction", oneOf(input, "defaultBudgetAction", Set.of("NONE",
                "STOP_SCHEDULING_AND_COMPLETE_TASKS", "STOP_SCHEDULING_AND_CANCEL_TASKS"), "NONE"));
        applyQueue(region, farmId, queue, input);
        queue.set("requiredFileSystemLocationNames", list(input.path("requiredFileSystemLocationNames")));
        queue.set("allowedStorageProfileIds", profiles(region, farmId, input.path("allowedStorageProfileIds")));
        ((ObjectNode) queue.get("_")).put("farmId", farmId).put("arn", store.arn(region, "farm/" + farmId
                + "/queue/" + id));
        tags(queue, input.get("tags"));
        store.put(region, QUEUES, id, queue);
        logGroup(region, "/aws/deadline/" + farmId + "/" + id);
        return store.object().put("queueId", id);
    }

    private void applyQueue(String region, String farmId, ObjectNode queue, JsonNode input) {
        if (present(input, "description")) {
            queue.put("description", input.get("description").asText());
        }
        if (input.path("jobAttachmentSettings").isObject()) {
            JsonNode settings = input.get("jobAttachmentSettings");
            text(settings, "s3BucketName");
            text(settings, "rootPrefix");
            queue.set("jobAttachmentSettings", settings.deepCopy());
        }
        if (present(input, "roleArn")) {
            queue.put("roleArn", role(input.get("roleArn").asText(), "roleArn"));
        }
        if (input.path("jobRunAsUser").isObject()) {
            JsonNode runAs = input.get("jobRunAsUser");
            String mode = oneOf(runAs, "runAs", Set.of("QUEUE_CONFIGURED_USER", "WORKER_AGENT_USER"), null);
            if ("QUEUE_CONFIGURED_USER".equals(mode) && !runAs.has("posix") && !runAs.has("windows")) {
                throw invalid("A QUEUE_CONFIGURED_USER jobRunAsUser must name a posix or windows user.");
            }
            queue.set("jobRunAsUser", runAs.deepCopy());
        }
        if (input.path("schedulingConfiguration").isObject()) {
            queue.set("schedulingConfiguration", input.get("schedulingConfiguration").deepCopy());
        }
        if (present(input, "defaultBudgetAction")) {
            queue.put("defaultBudgetAction", oneOf(input, "defaultBudgetAction", Set.of("NONE",
                    "STOP_SCHEDULING_AND_COMPLETE_TASKS", "STOP_SCHEDULING_AND_CANCEL_TASKS"), null));
        }
    }

    private ArrayNode list(JsonNode values) {
        ArrayNode result = store.array();
        if (values.isArray()) {
            Set<String> seen = new LinkedHashSet<>();
            values.forEach(value -> seen.add(value.asText()));
            seen.forEach(result::add);
        }
        return result;
    }

    private ArrayNode profiles(String region, String farmId, JsonNode ids) {
        ArrayNode result = list(ids);
        for (JsonNode id : result) {
            profile(region, farmId, id.asText());
        }
        return result;
    }

    /** A queue's status: blocked by its budgets, scheduling while it has active tasks, otherwise idle. */
    private ObjectNode queueView(String region, ObjectNode queue) {
        ObjectNode view = store.view(queue);
        String queueId = queue.path("queueId").asText();
        String blocked = blockedReason(region, queue);
        if (blocked != null) {
            view.put("status", "SCHEDULING_BLOCKED");
            view.put("blockedReason", blocked);
            return view;
        }
        boolean active = false;
        for (ObjectNode job : store.children(region, JOBS, "queueId", queueId)) {
            active |= ACTIVE_TASK_STATUSES.contains(job.path("taskRunStatus").asText());
        }
        view.put("status", active ? "SCHEDULING" : "IDLE");
        return view;
    }

    /** Why a queue cannot schedule: no budget although its default action needs one, or a budget reached. */
    String blockedReason(String region, ObjectNode queue) {
        String queueId = queue.path("queueId").asText();
        List<ObjectNode> budgets = store.list(region, BUDGETS, budget -> queueId.equals(budget
                .path("usageTrackingResource").path("queueId").asText()) && "ACTIVE".equals(budget.path("status")
                .asText()));
        for (ObjectNode budget : budgets) {
            if (budget.hasNonNull("queueStoppedAt")) {
                return "BUDGET_THRESHOLD_REACHED";
            }
        }
        if (!"NONE".equals(queue.path("defaultBudgetAction").asText("NONE")) && budgets.isEmpty()) {
            return "NO_BUDGET_CONFIGURED";
        }
        return null;
    }

    public synchronized ObjectNode getQueue(ObjectNode input, String region) {
        return queueView(region, queue(region, text(input, "farmId"), text(input, "queueId")));
    }

    public synchronized ObjectNode updateQueue(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode queue = queue(region, farmId, text(input, "queueId"));
        if (present(input, "displayName")) {
            displayName(input);
            queue.put("displayName", input.get("displayName").asText());
        }
        applyQueue(region, farmId, queue, input);
        edit(queue, "requiredFileSystemLocationNames", input.path("requiredFileSystemLocationNamesToAdd"),
                input.path("requiredFileSystemLocationNamesToRemove"));
        profiles(region, farmId, input.path("allowedStorageProfileIdsToAdd"));
        edit(queue, "allowedStorageProfileIds", input.path("allowedStorageProfileIdsToAdd"),
                input.path("allowedStorageProfileIdsToRemove"));
        updated(queue);
        store.put(region, QUEUES, queue.path("queueId").asText(), queue);
        return store.object();
    }

    private void edit(ObjectNode record, String field, JsonNode add, JsonNode remove) {
        Set<String> values = new LinkedHashSet<>();
        record.path(field).forEach(value -> values.add(value.asText()));
        add.forEach(value -> values.add(value.asText()));
        remove.forEach(value -> values.remove(value.asText()));
        ArrayNode result = record.putArray(field);
        values.forEach(result::add);
    }

    public synchronized ObjectNode deleteQueue(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode queue = queue(region, farmId, text(input, "queueId"));
        String queueId = queue.path("queueId").asText();
        for (String kind : List.of(QUEUE_FLEETS, QUEUE_LIMITS)) {
            if (!store.children(region, kind, "queueId", queueId).isEmpty()) {
                throw conflict("Queue " + queueId + " still has " + kind.replace('-', ' ') + "; delete them first.",
                        "RESOURCE_IN_USE", QUEUES, queueId);
            }
        }
        for (ObjectNode budget : store.list(region, BUDGETS, budget -> queueId.equals(budget
                .path("usageTrackingResource").path("queueId").asText()))) {
            throw conflict("Queue " + queueId + " is tracked by budget " + budget.path("budgetId").asText() + ".",
                    "RESOURCE_IN_USE", QUEUES, queueId);
        }
        for (String kind : List.of(JOBS, DeadlineJobs.STEPS, DeadlineJobs.TASKS, DeadlineJobs.SESSIONS,
                DeadlineJobs.ACTIONS, QUEUE_ENVIRONMENTS)) {
            for (ObjectNode child : store.children(region, kind, "queueId", queueId)) {
                store.delete(region, kind, child.path("_").path("key").asText());
            }
        }
        for (ObjectNode member : store.children(region, MEMBERS, "queueId", queueId)) {
            store.delete(region, MEMBERS, member.path("_").path("key").asText());
        }
        store.delete(region, QUEUES, queueId);
        return store.object();
    }

    public synchronized ObjectNode listQueues(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        String principal = optional(input, "principalId");
        String status = optional(input, "status");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode queue : store.children(region, QUEUES, "farmId", farmId)) {
            ObjectNode view = queueView(region, queue);
            if ((status != null && !status.equals(view.path("status").asText()))
                    || (principal != null && memberLevel(region, "queue/" + queue.path("queueId").asText(),
                    principal) == null)) {
                continue;
            }
            items.add(store.view(view, "farmId", "queueId", "displayName", "status", "defaultBudgetAction",
                    "blockedReason", "createdAt", "createdBy", "updatedAt", "updatedBy"));
        }
        return store.page(items, input, "queues", item -> item.path("queueId").asText());
    }

    // ---------------------------------------------------------------- fleets

    ObjectNode fleet(String region, String farmId, String fleetId) {
        farm(region, farmId);
        ObjectNode fleet = store.require(region, FLEETS, fleetId, "Fleet");
        if (!farmId.equals(fleet.path("farmId").asText())) {
            throw notFound("Fleet " + fleetId + " not found.", FLEETS, fleetId);
        }
        if ("CREATE_IN_PROGRESS".equals(fleet.path("status").asText()) && due(fleet)) {
            fleet.put("status", "ACTIVE");
            store.put(region, FLEETS, fleetId, fleet);
        } else if ("UPDATE_IN_PROGRESS".equals(fleet.path("status").asText()) && due(fleet)) {
            fleet.put("status", "ACTIVE");
            store.put(region, FLEETS, fleetId, fleet);
        }
        return fleet;
    }

    public synchronized ObjectNode createFleet(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        displayName(input);
        String role = role(text(input, "roleArn"), "roleArn");
        int min = input.path("minWorkerCount").asInt(0);
        int max = input.path("maxWorkerCount").asInt(-1);
        if (max < 0 || min < 0 || min > max) {
            throw invalid("maxWorkerCount must be at least minWorkerCount, and both at least 0.");
        }
        ObjectNode capabilities = capabilities(region, farmId, input.path("configuration"));
        String id = store.id("fleet");
        ObjectNode fleet = created(store.record());
        fleet.put("fleetId", id);
        fleet.put("farmId", farmId);
        fleet.put("displayName", input.get("displayName").asText());
        if (present(input, "description")) {
            fleet.put("description", input.get("description").asText());
        }
        fleet.put("status", transitionMillis <= 0 ? "ACTIVE" : "CREATE_IN_PROGRESS");
        fleet.put("autoScalingStatus", "STEADY");
        fleet.put("workerCount", 0);
        fleet.put("minWorkerCount", min);
        fleet.put("maxWorkerCount", max);
        fleet.set("configuration", input.get("configuration").deepCopy());
        fleet.set("capabilities", capabilities);
        fleet.put("roleArn", role);
        if (input.path("hostConfiguration").isObject()) {
            fleet.set("hostConfiguration", hostConfiguration(input.get("hostConfiguration")));
        }
        ((ObjectNode) fleet.get("_")).put("farmId", farmId).put("arn", store.arn(region, "farm/" + farmId
                + "/fleet/" + id));
        readyIn(fleet);
        tags(fleet, input.get("tags"));
        store.put(region, FLEETS, id, fleet);
        logGroup(region, "/aws/deadline/" + farmId + "/" + id);
        return store.object().put("fleetId", id);
    }

    private ObjectNode hostConfiguration(JsonNode input) {
        String script = text(input, "scriptBody");
        if (script.length() > 15000) {
            throw invalid("hostConfiguration.scriptBody must be at most 15000 characters.");
        }
        int timeout = input.path("scriptTimeoutSeconds").asInt(300);
        if (timeout < 300 || timeout > 3600) {
            throw invalid("hostConfiguration.scriptTimeoutSeconds must be between 300 and 3600.");
        }
        return store.object().put("scriptBody", script).put("scriptTimeoutSeconds", timeout);
    }

    /** The capabilities a fleet's workers offer, as Deadline derives them from its configuration. */
    private ObjectNode capabilities(String region, String farmId, JsonNode configuration) {
        JsonNode customer = configuration.path("customerManaged");
        JsonNode service = configuration.path("serviceManagedEc2");
        JsonNode worker;
        if (customer.isObject()) {
            oneOf(customer, "mode", Set.of("NO_SCALING", "EVENT_BASED_AUTO_SCALING"), null);
            worker = customer.path("workerCapabilities");
            if (present(customer, "storageProfileId")) {
                profile(region, farmId, customer.get("storageProfileId").asText());
            }
        } else if (service.isObject()) {
            worker = service.path("instanceCapabilities");
            oneOf(service.path("instanceMarketOptions"), "type", Set.of("on-demand", "spot", "wait-and-save"), null);
            if (present(service, "storageProfileId")) {
                profile(region, farmId, service.get("storageProfileId").asText());
            }
        } else {
            throw invalid("configuration must be customerManaged or serviceManagedEc2.");
        }
        int minCpu = worker.path("vCpuCount").path("min").asInt(0);
        int minMemory = worker.path("memoryMiB").path("min").asInt(0);
        if (minCpu < 1) {
            throw invalid("vCpuCount.min must be at least 1.");
        }
        if (minMemory < 512) {
            throw invalid("memoryMiB.min must be at least 512.");
        }
        String os = text(worker, "osFamily");
        String arch = text(worker, "cpuArchitectureType");
        ObjectNode capabilities = store.object();
        ArrayNode amounts = capabilities.putArray("amounts");
        range(amounts, "amount.worker.vcpu", worker.path("vCpuCount"));
        range(amounts, "amount.worker.memory", worker.path("memoryMiB"));
        if (worker.has("acceleratorCount")) {
            range(amounts, "amount.worker.gpu", worker.get("acceleratorCount"));
        } else if (worker.path("acceleratorCapabilities").has("count")) {
            range(amounts, "amount.worker.gpu", worker.path("acceleratorCapabilities").get("count"));
        }
        if (worker.has("acceleratorTotalMemoryMiB")) {
            range(amounts, "amount.worker.gpu.memory", worker.get("acceleratorTotalMemoryMiB"));
        }
        if (worker.path("rootEbsVolume").has("sizeGiB")) {
            amounts.addObject().put("name", "amount.worker.disk.scratch").put("min",
                    worker.get("rootEbsVolume").get("sizeGiB").asDouble() * 1024).put("max",
                    worker.get("rootEbsVolume").get("sizeGiB").asDouble() * 1024);
        }
        for (JsonNode custom : worker.path("customAmounts")) {
            ObjectNode amount = amounts.addObject().put("name", text(custom, "name"))
                    .put("min", custom.path("min").asDouble());
            if (custom.has("max")) {
                amount.put("max", custom.get("max").asDouble());
            }
        }
        ArrayNode attributes = capabilities.putArray("attributes");
        attributes.addObject().put("name", "attr.worker.os.family").putArray("values").add(os.toLowerCase());
        attributes.addObject().put("name", "attr.worker.cpu.arch").putArray("values").add(arch);
        for (JsonNode custom : worker.path("customAttributes")) {
            attributes.addObject().put("name", text(custom, "name")).set("values", custom.path("values").deepCopy());
        }
        return capabilities;
    }

    private static void range(ArrayNode amounts, String name, JsonNode range) {
        ObjectNode amount = amounts.addObject().put("name", name).put("min", range.path("min").asDouble());
        if (range.has("max")) {
            amount.put("max", range.get("max").asDouble());
        }
    }

    public synchronized ObjectNode getFleet(ObjectNode input, String region) {
        return fleetView(region, fleet(region, text(input, "farmId"), text(input, "fleetId")));
    }

    private ObjectNode fleetView(String region, ObjectNode fleet) {
        ObjectNode view = store.view(fleet);
        int workers = 0;
        for (ObjectNode worker : store.children(region, WORKERS, "fleetId", fleet.path("fleetId").asText())) {
            workers += Set.of("STARTED", "RUNNING", "IDLE").contains(worker.path("status").asText()) ? 1 : 0;
        }
        view.put("workerCount", workers);
        if (fleet.path("configuration").has("serviceManagedEc2")
                || "EVENT_BASED_AUTO_SCALING".equals(fleet.path("configuration").path("customerManaged")
                .path("mode").asText())) {
            view.put("targetWorkerCount", Math.max(fleet.path("minWorkerCount").asInt(), Math.min(workers,
                    fleet.path("maxWorkerCount").asInt())));
        }
        return view;
    }

    public synchronized ObjectNode updateFleet(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode fleet = fleet(region, farmId, text(input, "fleetId"));
        if (present(input, "displayName")) {
            displayName(input);
            fleet.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "description")) {
            fleet.put("description", input.get("description").asText());
        }
        if (present(input, "roleArn")) {
            fleet.put("roleArn", role(input.get("roleArn").asText(), "roleArn"));
        }
        int min = input.path("minWorkerCount").asInt(fleet.path("minWorkerCount").asInt());
        int max = input.path("maxWorkerCount").asInt(fleet.path("maxWorkerCount").asInt());
        if (min < 0 || min > max) {
            throw invalid("maxWorkerCount must be at least minWorkerCount, and both at least 0.");
        }
        fleet.put("minWorkerCount", min);
        fleet.put("maxWorkerCount", max);
        if (input.path("configuration").isObject()) {
            boolean wasCustomer = fleet.path("configuration").has("customerManaged");
            if (wasCustomer != input.get("configuration").has("customerManaged")) {
                throw invalid("A fleet cannot change between customer-managed and service-managed.");
            }
            fleet.set("capabilities", capabilities(region, farmId, input.get("configuration")));
            fleet.set("configuration", input.get("configuration").deepCopy());
        }
        if (input.path("hostConfiguration").isObject()) {
            fleet.set("hostConfiguration", hostConfiguration(input.get("hostConfiguration")));
        }
        if (transitionMillis > 0) {
            fleet.put("status", "UPDATE_IN_PROGRESS");
            readyIn(fleet);
        }
        updated(fleet);
        store.put(region, FLEETS, fleet.path("fleetId").asText(), fleet);
        return store.object();
    }

    public synchronized ObjectNode deleteFleet(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode fleet = fleet(region, farmId, text(input, "fleetId"));
        String fleetId = fleet.path("fleetId").asText();
        if (!store.children(region, QUEUE_FLEETS, "fleetId", fleetId).isEmpty()) {
            throw conflict("Fleet " + fleetId + " is associated with queues; delete the associations first.",
                    "RESOURCE_IN_USE", FLEETS, fleetId);
        }
        for (ObjectNode worker : store.children(region, WORKERS, "fleetId", fleetId)) {
            store.delete(region, WORKERS, worker.path("workerId").asText());
        }
        for (ObjectNode member : store.children(region, MEMBERS, "fleetId", fleetId)) {
            store.delete(region, MEMBERS, member.path("_").path("key").asText());
        }
        store.delete(region, FLEETS, fleetId);
        return store.object();
    }

    public synchronized ObjectNode listFleets(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        String principal = optional(input, "principalId");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.children(region, FLEETS, "farmId", farmId)) {
            ObjectNode fleet = fleetView(region, fleet(region, farmId, stored.path("fleetId").asText()));
            if ((present(input, "displayName") && !input.get("displayName").asText().equals(fleet.path("displayName")
                    .asText())) || (present(input, "status") && !input.get("status").asText().equals(fleet
                    .path("status").asText())) || (principal != null && memberLevel(region, "fleet/"
                    + fleet.path("fleetId").asText(), principal) == null)) {
                continue;
            }
            items.add(store.view(fleet, "fleetId", "farmId", "displayName", "status", "statusMessage",
                    "autoScalingStatus", "targetWorkerCount", "workerCount", "minWorkerCount", "maxWorkerCount",
                    "configuration", "createdAt", "createdBy", "updatedAt", "updatedBy"));
        }
        return store.page(items, input, "fleets", item -> item.path("fleetId").asText());
    }

    // ---------------------------------------------------------------- storage profiles

    ObjectNode profile(String region, String farmId, String profileId) {
        farm(region, farmId);
        ObjectNode profile = store.require(region, PROFILES, profileId, "Storage profile");
        if (!farmId.equals(profile.path("_").path("farmId").asText())) {
            throw notFound("Storage profile " + profileId + " not found.", PROFILES, profileId);
        }
        return profile;
    }

    private ArrayNode locations(JsonNode locations, String osFamily) {
        ArrayNode result = store.array();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode location : locations) {
            String name = text(location, "name");
            String path = text(location, "path");
            oneOf(location, "type", Set.of("SHARED", "LOCAL"), null);
            if (!names.add(name)) {
                throw invalid("File system location " + name + " is defined more than once.");
            }
            boolean windows = "WINDOWS".equals(osFamily);
            if (windows ? !path.matches("^[A-Za-z]:\\\\.*|^\\\\\\\\.*") : !path.startsWith("/")) {
                throw invalid("File system location " + name + " must be an absolute " + (windows ? "Windows"
                        : "POSIX") + " path.");
            }
            result.add(location.deepCopy());
        }
        return result;
    }

    public synchronized ObjectNode createStorageProfile(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        displayName(input);
        String os = oneOf(input, "osFamily", Set.of("WINDOWS", "LINUX", "MACOS"), null);
        String id = store.id("sp");
        ObjectNode profile = created(store.record());
        profile.put("storageProfileId", id);
        profile.put("displayName", input.get("displayName").asText());
        profile.put("osFamily", os);
        profile.set("fileSystemLocations", locations(input.path("fileSystemLocations"), os));
        ((ObjectNode) profile.get("_")).put("farmId", farmId);
        store.put(region, PROFILES, id, profile);
        return store.object().put("storageProfileId", id);
    }

    public synchronized ObjectNode getStorageProfile(ObjectNode input, String region) {
        return store.view(profile(region, text(input, "farmId"), text(input, "storageProfileId")));
    }

    public synchronized ObjectNode getStorageProfileForQueue(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode queue = queue(region, farmId, text(input, "queueId"));
        String profileId = text(input, "storageProfileId");
        boolean allowed = false;
        for (JsonNode id : queue.path("allowedStorageProfileIds")) {
            allowed |= profileId.equals(id.asText());
        }
        if (!allowed) {
            throw notFound("Storage profile " + profileId + " is not allowed on queue " + queue.path("queueId")
                    .asText() + ".", PROFILES, profileId);
        }
        return store.view(profile(region, farmId, profileId), "storageProfileId", "displayName", "osFamily",
                "fileSystemLocations");
    }

    public synchronized ObjectNode updateStorageProfile(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode profile = profile(region, farmId, text(input, "storageProfileId"));
        if (present(input, "displayName")) {
            displayName(input);
            profile.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "osFamily")) {
            profile.put("osFamily", oneOf(input, "osFamily", Set.of("WINDOWS", "LINUX", "MACOS"), null));
        }
        List<JsonNode> kept = new ArrayList<>();
        Set<String> removed = new LinkedHashSet<>();
        input.path("fileSystemLocationsToRemove").forEach(location -> removed.add(location.path("name").asText()));
        for (JsonNode location : profile.path("fileSystemLocations")) {
            if (!removed.contains(location.path("name").asText())) {
                kept.add(location);
            }
        }
        ArrayNode merged = store.array();
        kept.forEach(merged::add);
        input.path("fileSystemLocationsToAdd").forEach(merged::add);
        profile.set("fileSystemLocations", locations(merged, profile.path("osFamily").asText()));
        updated(profile);
        store.put(region, PROFILES, profile.path("storageProfileId").asText(), profile);
        return store.object();
    }

    public synchronized ObjectNode deleteStorageProfile(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String profileId = profile(region, farmId, text(input, "storageProfileId")).path("storageProfileId").asText();
        for (ObjectNode queue : store.children(region, QUEUES, "farmId", farmId)) {
            for (JsonNode allowed : queue.path("allowedStorageProfileIds")) {
                if (profileId.equals(allowed.asText())) {
                    throw conflict("Storage profile " + profileId + " is allowed on queue " + queue.path("queueId")
                            .asText() + ".", "RESOURCE_IN_USE", PROFILES, profileId);
                }
            }
        }
        for (ObjectNode fleet : store.children(region, FLEETS, "farmId", farmId)) {
            JsonNode configuration = fleet.path("configuration");
            if (profileId.equals(configuration.path("customerManaged").path("storageProfileId").asText())
                    || profileId.equals(configuration.path("serviceManagedEc2").path("storageProfileId").asText())) {
                throw conflict("Storage profile " + profileId + " is used by fleet " + fleet.path("fleetId").asText()
                        + ".", "RESOURCE_IN_USE", PROFILES, profileId);
            }
        }
        store.delete(region, PROFILES, profileId);
        return store.object();
    }

    public synchronized ObjectNode listStorageProfiles(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.children(region, PROFILES, "farmId", farmId)) {
            items.add(store.view(profile, "storageProfileId", "displayName", "osFamily"));
        }
        return store.page(items, input, "storageProfiles", item -> item.path("storageProfileId").asText());
    }

    public synchronized ObjectNode listStorageProfilesForQueue(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        ObjectNode queue = queue(region, farmId, text(input, "queueId"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode id : queue.path("allowedStorageProfileIds")) {
            store.get(region, PROFILES, id.asText()).ifPresent(profile -> items.add(store.view(profile,
                    "storageProfileId", "displayName", "osFamily")));
        }
        return store.page(items, input, "storageProfiles", item -> item.path("storageProfileId").asText());
    }

    // ---------------------------------------------------------------- budgets

    ObjectNode budget(String region, String farmId, String budgetId) {
        farm(region, farmId);
        ObjectNode budget = store.require(region, BUDGETS, budgetId, "Budget");
        if (!farmId.equals(budget.path("_").path("farmId").asText())) {
            throw notFound("Budget " + budgetId + " not found.", BUDGETS, budgetId);
        }
        return budget;
    }

    private ObjectNode schedule(JsonNode schedule) {
        JsonNode fixed = schedule.path("fixed");
        if (!fixed.isObject()) {
            throw invalid("schedule must be fixed.");
        }
        double start = timestamp(fixed.get("startTime"), "schedule.fixed.startTime");
        double end = timestamp(fixed.get("endTime"), "schedule.fixed.endTime");
        double now = now();
        if (end <= now) {
            throw invalid("The budget's endTime must be in the future.");
        }
        double effectiveStart = Math.max(start, now);
        if (end <= effectiveStart) {
            throw invalid("The budget's endTime must be after its startTime.");
        }
        if (end - effectiveStart > Duration.ofDays(120).toSeconds()) {
            throw invalid("The budget's schedule must be at most 120 days long.");
        }
        ObjectNode result = store.object();
        result.putObject("fixed").put("startTime", effectiveStart).put("endTime", end);
        return result;
    }

    private static double timestamp(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            throw invalid(field + " is required.");
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return OffsetDateTime.parse(value.asText()).toInstant().toEpochMilli() / 1000.0;
        } catch (DateTimeParseException e) {
            throw invalid(field + " is not a timestamp.");
        }
    }

    private ArrayNode budgetActions(JsonNode actions) {
        ArrayNode result = store.array();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode action : actions) {
            String type = oneOf(action, "type", Set.of("STOP_SCHEDULING_AND_COMPLETE_TASKS",
                    "STOP_SCHEDULING_AND_CANCEL_TASKS"), null);
            double threshold = action.path("thresholdPercentage").asDouble(-1);
            if (threshold < 0 || threshold > 100) {
                throw invalid("thresholdPercentage must be between 0 and 100.");
            }
            if (!seen.add(type + "@" + threshold)) {
                throw invalid("The budget already has a " + type + " action at " + threshold + "%.");
            }
            ObjectNode entry = result.addObject().put("type", type).put("thresholdPercentage", threshold);
            if (present(action, "description")) {
                entry.put("description", action.get("description").asText());
            }
        }
        return result;
    }

    public synchronized ObjectNode createBudget(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        displayName(input);
        String queueId = text(input.path("usageTrackingResource"), "queueId");
        queue(region, farmId, queueId);
        double limit = input.path("approximateDollarLimit").asDouble(-1);
        if (limit < 0.01) {
            throw invalid("approximateDollarLimit must be at least 0.01.");
        }
        if (!input.path("actions").isArray()) {
            throw invalid("actions is required.");
        }
        String id = store.id("budget");
        ObjectNode budget = created(store.record());
        budget.put("budgetId", id);
        budget.putObject("usageTrackingResource").put("queueId", queueId);
        budget.put("status", "ACTIVE");
        budget.put("displayName", input.get("displayName").asText());
        if (present(input, "description")) {
            budget.put("description", input.get("description").asText());
        }
        budget.put("approximateDollarLimit", limit);
        budget.set("actions", budgetActions(input.get("actions")));
        budget.set("schedule", schedule(input.path("schedule")));
        ((ObjectNode) budget.get("_")).put("farmId", farmId).put("arn", store.arn(region, "farm/" + farmId
                + "/budget/" + id));
        tags(budget, input.get("tags"));
        store.put(region, BUDGETS, id, budget);
        return store.object().put("budgetId", id);
    }

    public synchronized ObjectNode getBudget(ObjectNode input, String region) {
        ObjectNode budget = budget(region, text(input, "farmId"), text(input, "budgetId"));
        ObjectNode view = store.view(budget);
        view.putObject("usages").put("approximateDollarUsage", usage(region, budget));
        return view;
    }

    /**
     * What a budget's queue has spent in its window. floci bills nothing for worker time, so usage stays at zero
     * and no threshold is crossed.
     */
    private double usage(String region, ObjectNode budget) {
        return 0.0;
    }

    public synchronized ObjectNode updateBudget(ObjectNode input, String region) {
        ObjectNode budget = budget(region, text(input, "farmId"), text(input, "budgetId"));
        if (present(input, "displayName")) {
            displayName(input);
            budget.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "description")) {
            budget.put("description", input.get("description").asText());
        }
        if (present(input, "status")) {
            budget.put("status", oneOf(input, "status", Set.of("ACTIVE", "INACTIVE"), null));
        }
        if (present(input, "approximateDollarLimit")) {
            double limit = input.get("approximateDollarLimit").asDouble();
            if (limit < 0.01) {
                throw invalid("approximateDollarLimit must be at least 0.01.");
            }
            budget.put("approximateDollarLimit", limit);
        }
        if (input.has("actionsToRemove") || input.has("actionsToAdd")) {
            ArrayNode actions = store.array();
            for (JsonNode action : budget.path("actions")) {
                boolean remove = false;
                for (JsonNode removed : input.path("actionsToRemove")) {
                    remove |= removed.path("type").asText().equals(action.path("type").asText())
                            && removed.path("thresholdPercentage").asDouble() == action.path("thresholdPercentage")
                            .asDouble();
                }
                if (!remove) {
                    actions.add(action);
                }
            }
            input.path("actionsToAdd").forEach(actions::add);
            budget.set("actions", budgetActions(actions));
        }
        if (input.path("schedule").isObject()) {
            budget.set("schedule", schedule(input.get("schedule")));
        }
        updated(budget);
        store.put(region, BUDGETS, budget.path("budgetId").asText(), budget);
        return store.object();
    }

    public synchronized ObjectNode deleteBudget(ObjectNode input, String region) {
        ObjectNode budget = budget(region, text(input, "farmId"), text(input, "budgetId"));
        store.delete(region, BUDGETS, budget.path("budgetId").asText());
        return store.object();
    }

    public synchronized ObjectNode listBudgets(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode budget : store.children(region, BUDGETS, "farmId", farmId)) {
            if (present(input, "status") && !input.get("status").asText().equals(budget.path("status").asText())) {
                continue;
            }
            ObjectNode summary = store.view(budget, "budgetId", "usageTrackingResource", "status", "displayName",
                    "description", "approximateDollarLimit", "createdBy", "createdAt", "updatedBy", "updatedAt");
            summary.putObject("usages").put("approximateDollarUsage", usage(region, budget));
            items.add(summary);
        }
        return store.page(items, input, "budgets", item -> item.path("budgetId").asText());
    }

    // ---------------------------------------------------------------- limits

    ObjectNode limit(String region, String farmId, String limitId) {
        farm(region, farmId);
        ObjectNode limit = store.require(region, LIMITS, limitId, "Limit");
        if (!farmId.equals(limit.path("farmId").asText())) {
            throw notFound("Limit " + limitId + " not found.", LIMITS, limitId);
        }
        return limit;
    }

    public synchronized ObjectNode createLimit(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        displayName(input);
        String requirement = text(input, "amountRequirementName");
        if (!requirement.matches("^amount\\.[A-Za-z0-9_.:-]+$")) {
            throw invalid("amountRequirementName must name an amount capability, such as amount.license.maya.");
        }
        int max = input.path("maxCount").asInt(-2);
        if (max < -1) {
            throw invalid("maxCount must be at least -1 (unlimited).");
        }
        for (ObjectNode existing : store.children(region, LIMITS, "farmId", farmId)) {
            if (requirement.equals(existing.path("amountRequirementName").asText())) {
                throw conflict("Farm " + farmId + " already has a limit on " + requirement + ".",
                        "RESOURCE_ALREADY_EXISTS", LIMITS, existing.path("limitId").asText());
            }
        }
        String id = store.id("limit");
        ObjectNode limit = created(store.record());
        limit.put("farmId", farmId);
        limit.put("limitId", id);
        limit.put("currentCount", 0);
        limit.put("displayName", input.get("displayName").asText());
        limit.put("amountRequirementName", requirement);
        limit.put("maxCount", max);
        if (present(input, "description")) {
            limit.put("description", input.get("description").asText());
        }
        ((ObjectNode) limit.get("_")).put("farmId", farmId);
        store.put(region, LIMITS, id, limit);
        return store.object().put("limitId", id);
    }

    public synchronized ObjectNode getLimit(ObjectNode input, String region) {
        return limitView(region, limit(region, text(input, "farmId"), text(input, "limitId")));
    }

    /** A limit's current count: the limited amount its in-flight session actions hold. */
    private ObjectNode limitView(String region, ObjectNode limit) {
        ObjectNode view = store.view(limit);
        int held = 0;
        for (ObjectNode action : store.list(region, DeadlineJobs.ACTIONS, action -> Set.of("ASSIGNED", "RUNNING",
                "CANCELING").contains(action.path("status").asText()))) {
            for (JsonNode acquired : action.path("acquiredLimits")) {
                held += limit.path("limitId").asText().equals(acquired.path("limitId").asText())
                        ? acquired.path("count").asInt(1) : 0;
            }
        }
        view.put("currentCount", held);
        return view;
    }

    public synchronized ObjectNode updateLimit(ObjectNode input, String region) {
        ObjectNode limit = limit(region, text(input, "farmId"), text(input, "limitId"));
        if (present(input, "displayName")) {
            displayName(input);
            limit.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "description")) {
            limit.put("description", input.get("description").asText());
        }
        if (present(input, "maxCount")) {
            int max = input.get("maxCount").asInt();
            if (max < -1) {
                throw invalid("maxCount must be at least -1 (unlimited).");
            }
            limit.put("maxCount", max);
        }
        updated(limit);
        store.put(region, LIMITS, limit.path("limitId").asText(), limit);
        return store.object();
    }

    /** Deleting a limit is idempotent, as on AWS: a missing limit is already deleted. */
    public synchronized ObjectNode deleteLimit(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        String limitId = text(input, "limitId");
        if (!store.children(region, QUEUE_LIMITS, "limitId", limitId).isEmpty()) {
            throw conflict("Limit " + limitId + " is associated with queues; delete the associations first.",
                    "RESOURCE_IN_USE", LIMITS, limitId);
        }
        store.get(region, LIMITS, limitId).filter(limit -> farmId.equals(limit.path("farmId").asText()))
                .ifPresent(limit -> store.delete(region, LIMITS, limitId));
        return store.object();
    }

    public synchronized ObjectNode listLimits(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode limit : store.children(region, LIMITS, "farmId", farmId)) {
            items.add(limitView(region, limit));
        }
        return store.page(items, input, "limits", item -> item.path("limitId").asText());
    }

    // ---------------------------------------------------------------- associations

    /** A queue-fleet or queue-limit association, settling a requested stop to STOPPED once nothing runs. */
    private ObjectNode association(String region, String kind, String farmId, String queueId, String otherField,
                                   String otherId) {
        farm(region, farmId);
        String key = queueId + "_" + otherId;
        ObjectNode association = store.get(region, kind, key).orElseThrow(() -> notFound("Association of queue "
                + queueId + " and " + otherId + " not found.", kind, key));
        String status = association.path("status").asText();
        if (status.startsWith("STOP_") && due(association) && !running(region, queueId, otherField, otherId)) {
            association.put("status", "STOPPED");
            store.put(region, kind, key, association);
        }
        return association;
    }

    /** Whether a queue still has session actions in flight on a fleet (or holding a limit). */
    private boolean running(String region, String queueId, String otherField, String otherId) {
        for (ObjectNode action : store.children(region, DeadlineJobs.ACTIONS, "queueId", queueId)) {
            if (!Set.of("ASSIGNED", "RUNNING", "CANCELING").contains(action.path("status").asText())) {
                continue;
            }
            if ("fleetId".equals(otherField) && otherId.equals(action.path("_").path("fleetId").asText())) {
                return true;
            }
            if ("limitId".equals(otherField)) {
                for (JsonNode acquired : action.path("acquiredLimits")) {
                    if (otherId.equals(acquired.path("limitId").asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ObjectNode createAssociation(ObjectNode input, String region, String kind, String otherField) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        queue(region, farmId, queueId);
        String otherId = text(input, otherField);
        if ("fleetId".equals(otherField)) {
            fleet(region, farmId, otherId);
        } else {
            limit(region, farmId, otherId);
        }
        String key = queueId + "_" + otherId;
        if (store.get(region, kind, key).isPresent()) {
            throw conflict("Queue " + queueId + " is already associated with " + otherId + ".",
                    "RESOURCE_ALREADY_EXISTS", kind, key);
        }
        ObjectNode association = created(store.record());
        association.put("queueId", queueId);
        association.put(otherField, otherId);
        association.put("status", "ACTIVE");
        ((ObjectNode) association.get("_")).put("farmId", farmId).put("queueId", queueId).put(otherField, otherId)
                .put("key", key);
        store.put(region, kind, key, association);
        return store.object();
    }

    private ObjectNode updateAssociation(ObjectNode input, String region, String kind, String otherField,
                                         Set<String> statuses) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        String otherId = text(input, otherField);
        ObjectNode association = association(region, kind, farmId, queueId, otherField, otherId);
        String status = oneOf(input, "status", statuses, null);
        association.put("status", status);
        if (status.startsWith("STOP_")) {
            readyIn(association);
            if (status.endsWith("CANCEL_TASKS")) {
                ((ObjectNode) association.get("_")).put("cancel", true);
            }
        }
        updated(association);
        store.put(region, kind, queueId + "_" + otherId, association);
        association(region, kind, farmId, queueId, otherField, otherId);
        return store.object();
    }

    private ObjectNode deleteAssociation(ObjectNode input, String region, String kind, String otherField) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        String otherId = text(input, otherField);
        ObjectNode association = association(region, kind, farmId, queueId, otherField, otherId);
        if (!"STOPPED".equals(association.path("status").asText())) {
            throw conflict("Stop the association of queue " + queueId + " and " + otherId + " before deleting it; it "
                    + "is " + association.path("status").asText() + ".", "STATUS_CONFLICT", kind,
                    queueId + "_" + otherId);
        }
        store.delete(region, kind, queueId + "_" + otherId);
        return store.object();
    }

    private ObjectNode listAssociations(ObjectNode input, String region, String kind, String otherField,
                                        String field) {
        String farmId = text(input, "farmId");
        farm(region, farmId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.children(region, kind, "farmId", farmId)) {
            if ((present(input, "queueId") && !input.get("queueId").asText().equals(stored.path("queueId").asText()))
                    || (present(input, otherField) && !input.get(otherField).asText().equals(stored.path(otherField)
                    .asText()))) {
                continue;
            }
            items.add(store.view(association(region, kind, farmId, stored.path("queueId").asText(), otherField,
                    stored.path(otherField).asText())));
        }
        return store.page(items, input, field, item -> item.path("queueId").asText() + "_"
                + item.path(otherField).asText());
    }

    public synchronized ObjectNode createQueueFleetAssociation(ObjectNode input, String region) {
        return createAssociation(input, region, QUEUE_FLEETS, "fleetId");
    }

    public synchronized ObjectNode getQueueFleetAssociation(ObjectNode input, String region) {
        return store.view(association(region, QUEUE_FLEETS, text(input, "farmId"), text(input, "queueId"),
                "fleetId", text(input, "fleetId")));
    }

    public synchronized ObjectNode updateQueueFleetAssociation(ObjectNode input, String region) {
        return updateAssociation(input, region, QUEUE_FLEETS, "fleetId", Set.of("ACTIVE",
                "STOP_SCHEDULING_AND_COMPLETE_TASKS", "STOP_SCHEDULING_AND_CANCEL_TASKS"));
    }

    public synchronized ObjectNode deleteQueueFleetAssociation(ObjectNode input, String region) {
        return deleteAssociation(input, region, QUEUE_FLEETS, "fleetId");
    }

    public synchronized ObjectNode listQueueFleetAssociations(ObjectNode input, String region) {
        return listAssociations(input, region, QUEUE_FLEETS, "fleetId", "queueFleetAssociations");
    }

    public synchronized ObjectNode createQueueLimitAssociation(ObjectNode input, String region) {
        return createAssociation(input, region, QUEUE_LIMITS, "limitId");
    }

    public synchronized ObjectNode getQueueLimitAssociation(ObjectNode input, String region) {
        return store.view(association(region, QUEUE_LIMITS, text(input, "farmId"), text(input, "queueId"),
                "limitId", text(input, "limitId")));
    }

    public synchronized ObjectNode updateQueueLimitAssociation(ObjectNode input, String region) {
        return updateAssociation(input, region, QUEUE_LIMITS, "limitId", Set.of("ACTIVE",
                "STOP_LIMIT_USAGE_AND_COMPLETE_TASKS", "STOP_LIMIT_USAGE_AND_CANCEL_TASKS"));
    }

    public synchronized ObjectNode deleteQueueLimitAssociation(ObjectNode input, String region) {
        return deleteAssociation(input, region, QUEUE_LIMITS, "limitId");
    }

    public synchronized ObjectNode listQueueLimitAssociations(ObjectNode input, String region) {
        return listAssociations(input, region, QUEUE_LIMITS, "limitId", "queueLimitAssociations");
    }

    /** Whether a queue may schedule on a fleet: their association is ACTIVE. */
    boolean schedules(String region, String queueId, String fleetId) {
        return store.get(region, QUEUE_FLEETS, queueId + "_" + fleetId)
                .map(association -> "ACTIVE".equals(association.path("status").asText())).orElse(false);
    }

    // ---------------------------------------------------------------- queue environments

    public synchronized ObjectNode createQueueEnvironment(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        queue(region, farmId, queueId);
        int priority = input.path("priority").asInt(-1);
        if (priority < 0 || priority > 10000) {
            throw invalid("priority must be between 0 and 10000.");
        }
        String type = oneOf(input, "templateType", Set.of("JSON", "YAML"), null);
        DeadlineTemplates templates = new DeadlineTemplates(store.mapper());
        String name = templates.validateEnvironment(templates.parse(text(input, "template"), type));
        for (ObjectNode existing : store.children(region, QUEUE_ENVIRONMENTS, "queueId", queueId)) {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("Queue " + queueId + " already has an environment named " + name + ".",
                        "RESOURCE_ALREADY_EXISTS", QUEUE_ENVIRONMENTS, existing.path("queueEnvironmentId").asText());
            }
            if (priority == existing.path("priority").asInt()) {
                throw conflict("Queue " + queueId + " already has an environment with priority " + priority + ".",
                        "RESOURCE_ALREADY_EXISTS", QUEUE_ENVIRONMENTS, existing.path("queueEnvironmentId").asText());
            }
        }
        String id = store.id("queueenv");
        ObjectNode environment = created(store.record());
        environment.put("queueEnvironmentId", id);
        environment.put("name", name);
        environment.put("priority", priority);
        environment.put("templateType", type);
        environment.put("template", input.get("template").asText());
        ((ObjectNode) environment.get("_")).put("farmId", farmId).put("queueId", queueId).put("key", id);
        store.put(region, QUEUE_ENVIRONMENTS, id, environment);
        return store.object().put("queueEnvironmentId", id);
    }

    private ObjectNode queueEnvironment(String region, ObjectNode input) {
        String queueId = text(input, "queueId");
        queue(region, text(input, "farmId"), queueId);
        String id = text(input, "queueEnvironmentId");
        ObjectNode environment = store.require(region, QUEUE_ENVIRONMENTS, id, "Queue environment");
        if (!queueId.equals(environment.path("_").path("queueId").asText())) {
            throw notFound("Queue environment " + id + " not found.", QUEUE_ENVIRONMENTS, id);
        }
        return environment;
    }

    public synchronized ObjectNode getQueueEnvironment(ObjectNode input, String region) {
        return store.view(queueEnvironment(region, input));
    }

    public synchronized ObjectNode updateQueueEnvironment(ObjectNode input, String region) {
        ObjectNode environment = queueEnvironment(region, input);
        if (present(input, "priority")) {
            int priority = input.get("priority").asInt();
            if (priority < 0 || priority > 10000) {
                throw invalid("priority must be between 0 and 10000.");
            }
            environment.put("priority", priority);
        }
        if (present(input, "template")) {
            String type = oneOf(input, "templateType", Set.of("JSON", "YAML"), environment.path("templateType")
                    .asText());
            DeadlineTemplates templates = new DeadlineTemplates(store.mapper());
            environment.put("name", templates.validateEnvironment(templates.parse(input.get("template").asText(),
                    type)));
            environment.put("templateType", type);
            environment.put("template", input.get("template").asText());
        }
        updated(environment);
        store.put(region, QUEUE_ENVIRONMENTS, environment.path("queueEnvironmentId").asText(), environment);
        return store.object();
    }

    public synchronized ObjectNode deleteQueueEnvironment(ObjectNode input, String region) {
        ObjectNode environment = queueEnvironment(region, input);
        store.delete(region, QUEUE_ENVIRONMENTS, environment.path("queueEnvironmentId").asText());
        return store.object();
    }

    public synchronized ObjectNode listQueueEnvironments(ObjectNode input, String region) {
        String queueId = text(input, "queueId");
        queue(region, text(input, "farmId"), queueId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode environment : store.children(region, QUEUE_ENVIRONMENTS, "queueId", queueId)) {
            items.add(store.view(environment, "queueEnvironmentId", "name", "priority"));
        }
        items.sort((left, right) -> Integer.compare(left.path("priority").asInt(), right.path("priority").asInt()));
        return store.page(items, input, "environments", item -> item.path("queueEnvironmentId").asText());
    }

    // ---------------------------------------------------------------- members

    private ObjectNode associateMember(ObjectNode input, String region, String scope, ObjectNode ids) {
        String principalId = text(input, "principalId");
        String type = oneOf(input, "principalType", Set.of("USER", "GROUP"), null);
        String level = oneOf(input, "membershipLevel", Set.of("VIEWER", "CONTRIBUTOR", "OWNER", "MANAGER"), null);
        String key = scope + "|" + principalId;
        ObjectNode member = created(store.record());
        member.setAll(ids);
        member.put("principalId", principalId);
        member.put("principalType", type);
        member.put("identityStoreId", text(input, "identityStoreId"));
        member.put("membershipLevel", level);
        ObjectNode meta = (ObjectNode) member.get("_");
        meta.setAll(ids);
        meta.put("scope", scope).put("key", key).put("principalId", principalId);
        store.put(region, MEMBERS, key, member);
        return store.object();
    }

    private ObjectNode disassociateMember(ObjectNode input, String region, String scope) {
        String key = scope + "|" + text(input, "principalId");
        store.require(region, MEMBERS, key, "Member");
        store.delete(region, MEMBERS, key);
        return store.object();
    }

    private ObjectNode listMembers(ObjectNode input, String region, String scope) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode member : store.list(region, MEMBERS, member -> scope.equals(member.path("_").path("scope")
                .asText()))) {
            ObjectNode view = store.view(member);
            view.remove("createdAt");
            view.remove("createdBy");
            items.add(view);
        }
        return store.page(items, input, "members", item -> item.path("principalId").asText());
    }

    private String memberLevel(String region, String scope, String principalId) {
        return store.get(region, MEMBERS, scope + "|" + principalId).map(member -> member.path("membershipLevel")
                .asText()).orElse(null);
    }

    private ObjectNode ids(String... pairs) {
        ObjectNode ids = store.object();
        for (int index = 0; index < pairs.length; index += 2) {
            ids.put(pairs[index], pairs[index + 1]);
        }
        return ids;
    }

    public synchronized ObjectNode associateMemberToFarm(ObjectNode input, String region) {
        String farmId = farm(region, text(input, "farmId")).path("farmId").asText();
        return associateMember(input, region, "farm/" + farmId, ids("farmId", farmId));
    }

    public synchronized ObjectNode disassociateMemberFromFarm(ObjectNode input, String region) {
        return disassociateMember(input, region, "farm/" + farm(region, text(input, "farmId")).path("farmId")
                .asText());
    }

    public synchronized ObjectNode listFarmMembers(ObjectNode input, String region) {
        return listMembers(input, region, "farm/" + farm(region, text(input, "farmId")).path("farmId").asText());
    }

    public synchronized ObjectNode associateMemberToQueue(ObjectNode input, String region) {
        ObjectNode queue = queue(region, text(input, "farmId"), text(input, "queueId"));
        return associateMember(input, region, "queue/" + queue.path("queueId").asText(), ids("farmId",
                queue.path("farmId").asText(), "queueId", queue.path("queueId").asText()));
    }

    public synchronized ObjectNode disassociateMemberFromQueue(ObjectNode input, String region) {
        return disassociateMember(input, region, "queue/" + queue(region, text(input, "farmId"),
                text(input, "queueId")).path("queueId").asText());
    }

    public synchronized ObjectNode listQueueMembers(ObjectNode input, String region) {
        return listMembers(input, region, "queue/" + queue(region, text(input, "farmId"), text(input, "queueId"))
                .path("queueId").asText());
    }

    public synchronized ObjectNode associateMemberToFleet(ObjectNode input, String region) {
        ObjectNode fleet = fleet(region, text(input, "farmId"), text(input, "fleetId"));
        return associateMember(input, region, "fleet/" + fleet.path("fleetId").asText(), ids("farmId",
                fleet.path("farmId").asText(), "fleetId", fleet.path("fleetId").asText()));
    }

    public synchronized ObjectNode disassociateMemberFromFleet(ObjectNode input, String region) {
        return disassociateMember(input, region, "fleet/" + fleet(region, text(input, "farmId"),
                text(input, "fleetId")).path("fleetId").asText());
    }

    public synchronized ObjectNode listFleetMembers(ObjectNode input, String region) {
        return listMembers(input, region, "fleet/" + fleet(region, text(input, "farmId"), text(input, "fleetId"))
                .path("fleetId").asText());
    }

    public synchronized ObjectNode associateMemberToJob(ObjectNode input, String region) {
        String farmId = text(input, "farmId");
        String queueId = text(input, "queueId");
        queue(region, farmId, queueId);
        String jobId = text(input, "jobId");
        store.require(region, JOBS, jobId, "Job");
        return associateMember(input, region, "job/" + jobId, ids("farmId", farmId, "queueId", queueId, "jobId",
                jobId));
    }

    public synchronized ObjectNode disassociateMemberFromJob(ObjectNode input, String region) {
        return disassociateMember(input, region, "job/" + text(input, "jobId"));
    }

    public synchronized ObjectNode listJobMembers(ObjectNode input, String region) {
        queue(region, text(input, "farmId"), text(input, "queueId"));
        return listMembers(input, region, "job/" + store.require(region, JOBS, text(input, "jobId"), "Job")
                .path("jobId").asText());
    }

    public synchronized ObjectNode listMemberships(ObjectNode input, String region) {
        String principalId = text(input, "principalId");
        Set<String> types = new LinkedHashSet<>();
        input.path("resourceTypes").forEach(type -> types.add(type.asText().toLowerCase()));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode member : store.list(region, MEMBERS, member -> principalId.equals(member.path("_")
                .path("principalId").asText()))) {
            String scope = member.path("_").path("scope").asText();
            String type = scope.substring(0, scope.indexOf('/'));
            if (!types.isEmpty() && !types.contains(type)) {
                continue;
            }
            ObjectNode resource = store.object();
            for (String field : List.of("farmId", "queueId", "fleetId", "jobId")) {
                if (member.hasNonNull(field)) {
                    resource.put(field, member.get(field).asText());
                }
            }
            resource.put("membershipLevel", member.path("membershipLevel").asText());
            ObjectNode summary = store.object();
            summary.set(type, resource);
            items.add(summary);
        }
        return store.page(items, input, "memberships", JsonNode::toString);
    }

    // ---------------------------------------------------------------- monitors

    ObjectNode monitor(String region, String monitorId) {
        return store.require(region, MONITORS, monitorId, "Monitor");
    }

    public synchronized ObjectNode createMonitor(ObjectNode input, String region) {
        displayName(input);
        String subdomain = text(input, "subdomain");
        if (!subdomain.matches("^[a-z0-9-]{1,100}$")) {
            throw invalid("subdomain must be lowercase letters, digits and hyphens.");
        }
        for (ObjectNode existing : store.list(region, MONITORS)) {
            if (subdomain.equals(existing.path("subdomain").asText())) {
                throw conflict("Subdomain " + subdomain + " is already in use.", "RESOURCE_ALREADY_EXISTS",
                        MONITORS, existing.path("monitorId").asText());
            }
        }
        String role = role(text(input, "roleArn"), "roleArn");
        String instance = text(input, "identityCenterInstanceArn");
        String id = store.id("monitor");
        String application = identityCenterApplication(instance, id, region);
        ObjectNode monitor = created(store.record());
        monitor.put("monitorId", id);
        monitor.put("displayName", input.get("displayName").asText());
        monitor.put("subdomain", subdomain);
        monitor.put("url", monitorUrl(subdomain, region));
        monitor.put("roleArn", role);
        monitor.put("identityCenterInstanceArn", instance);
        if (present(input, "identityCenterRegion")) {
            monitor.put("identityCenterRegion", input.get("identityCenterRegion").asText());
        }
        monitor.put("identityCenterApplicationArn", application);
        ((ObjectNode) monitor.get("_")).put("arn", store.arn(region, "monitor/" + id)).putObject("settings");
        tags(monitor, input.get("tags"));
        store.put(region, MONITORS, id, monitor);
        return store.object().put("monitorId", id).put("identityCenterApplicationArn", application);
    }

    private static String monitorUrl(String subdomain, String region) {
        return "https://" + subdomain + "." + region + ".deadlinecloud." + AwsRegions.dnsSuffixFor(region);
    }

    /** The IAM Identity Center application a monitor signs users in through. */
    private String identityCenterApplication(String instanceArn, String monitorId, String region) {
        if (!sso.isResolvable()) {
            return instanceArn.replace(":instance/", ":application/") + "/apl-" + monitorId.substring(8, 24);
        }
        ObjectNode request = store.object();
        request.put("InstanceArn", instanceArn);
        String partition = AwsArnUtils.parse(instanceArn).partition();
        request.put("ApplicationProviderArn", AwsArnUtils.Arn.global(partition, "sso", "aws",
                "applicationProvider/custom").toString());
        request.put("Name", "DeadlineCloudMonitor-" + monitorId.substring(8, 24));
        request.put("Description", "Deadline Cloud monitor " + monitorId);
        try {
            return sso.get().createApplication(request, store.account(), region).applicationArn();
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw invalid("IAM Identity Center instance " + instanceArn + " does not exist.");
            }
            throw e;
        }
    }

    public synchronized ObjectNode getMonitor(ObjectNode input, String region) {
        return store.view(monitor(region, text(input, "monitorId")));
    }

    public synchronized ObjectNode updateMonitor(ObjectNode input, String region) {
        ObjectNode monitor = monitor(region, text(input, "monitorId"));
        if (present(input, "displayName")) {
            displayName(input);
            monitor.put("displayName", input.get("displayName").asText());
        }
        if (present(input, "subdomain")) {
            String subdomain = input.get("subdomain").asText();
            for (ObjectNode existing : store.list(region, MONITORS)) {
                if (subdomain.equals(existing.path("subdomain").asText())
                        && !existing.path("monitorId").asText().equals(monitor.path("monitorId").asText())) {
                    throw conflict("Subdomain " + subdomain + " is already in use.", "RESOURCE_ALREADY_EXISTS",
                            MONITORS, existing.path("monitorId").asText());
                }
            }
            monitor.put("subdomain", subdomain);
            monitor.put("url", monitorUrl(subdomain, region));
        }
        if (present(input, "roleArn")) {
            monitor.put("roleArn", role(input.get("roleArn").asText(), "roleArn"));
        }
        updated(monitor);
        store.put(region, MONITORS, monitor.path("monitorId").asText(), monitor);
        return store.object();
    }

    public synchronized ObjectNode deleteMonitor(ObjectNode input, String region) {
        ObjectNode monitor = monitor(region, text(input, "monitorId"));
        if (sso.isResolvable()) {
            try {
                sso.get().deleteApplication(monitor.path("identityCenterApplicationArn").asText());
            } catch (AwsException e) {
                LOG.debugv("Could not delete monitor application: {0}", e.getMessage());
            }
        }
        store.delete(region, MONITORS, monitor.path("monitorId").asText());
        return store.object();
    }

    public synchronized ObjectNode listMonitors(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode monitor : store.list(region, MONITORS)) {
            items.add(store.view(monitor));
        }
        return store.page(items, input, "monitors", item -> item.path("monitorId").asText());
    }

    public synchronized ObjectNode getMonitorSettings(ObjectNode input, String region) {
        ObjectNode monitor = monitor(region, text(input, "monitorId"));
        ObjectNode response = store.object();
        response.set("settings", monitor.path("_").path("settings").deepCopy());
        return response;
    }

    public synchronized ObjectNode updateMonitorSettings(ObjectNode input, String region) {
        ObjectNode monitor = monitor(region, text(input, "monitorId"));
        ObjectNode settings = (ObjectNode) monitor.get("_").get("settings");
        if (!input.path("settings").isObject()) {
            throw invalid("settings is required.");
        }
        input.get("settings").properties().forEach(entry -> {
            if (entry.getValue().isNull() || entry.getValue().asText().isEmpty()) {
                settings.remove(entry.getKey());
            } else {
                settings.put(entry.getKey(), entry.getValue().asText());
            }
        });
        store.put(region, MONITORS, monitor.path("monitorId").asText(), monitor);
        return store.object();
    }

    // ---------------------------------------------------------------- license endpoints

    ObjectNode licenseEndpoint(String region, String id) {
        ObjectNode endpoint = store.require(region, LICENSE_ENDPOINTS, id, "License endpoint");
        if ("CREATE_IN_PROGRESS".equals(endpoint.path("status").asText()) && due(endpoint)) {
            endpoint.put("status", "READY");
            endpoint.put("statusMessage", "The license endpoint is ready.");
            store.put(region, LICENSE_ENDPOINTS, id, endpoint);
        } else if ("DELETE_IN_PROGRESS".equals(endpoint.path("status").asText()) && due(endpoint)) {
            store.delete(region, LICENSE_ENDPOINTS, id);
            throw notFound("License endpoint " + id + " not found.", LICENSE_ENDPOINTS, id);
        }
        return endpoint;
    }

    public synchronized ObjectNode createLicenseEndpoint(ObjectNode input, String region) {
        String vpcId = text(input, "vpcId");
        if (!input.path("subnetIds").isArray() || input.get("subnetIds").isEmpty()) {
            throw invalid("subnetIds is required.");
        }
        if (!input.path("securityGroupIds").isArray() || input.get("securityGroupIds").isEmpty()) {
            throw invalid("securityGroupIds is required.");
        }
        if (ec2.isResolvable()) {
            try {
                if (ec2.get().describeVpcs(region, List.of(vpcId), Map.of()).isEmpty()) {
                    throw invalid("VPC " + vpcId + " does not exist.");
                }
            } catch (AwsException e) {
                if (!"ValidationException".equals(e.getErrorCode())) {
                    throw invalid("VPC " + vpcId + " does not exist.");
                }
                throw e;
            }
        }
        String id = store.id("le");
        ObjectNode endpoint = store.record();
        endpoint.put("licenseEndpointId", id);
        endpoint.put("status", transitionMillis <= 0 ? "READY" : "CREATE_IN_PROGRESS");
        endpoint.put("statusMessage", transitionMillis <= 0 ? "The license endpoint is ready."
                : "The license endpoint is being created.");
        endpoint.put("vpcId", vpcId);
        endpoint.put("dnsName", "vpce-" + id.substring(3, 20) + ".vpce-svc.deadline." + region + "."
                + AwsRegions.dnsSuffixFor(region));
        endpoint.set("subnetIds", input.get("subnetIds").deepCopy());
        endpoint.set("securityGroupIds", input.get("securityGroupIds").deepCopy());
        ((ObjectNode) endpoint.get("_")).put("arn", store.arn(region, "license-endpoint/" + id));
        readyIn(endpoint);
        tags(endpoint, input.get("tags"));
        store.put(region, LICENSE_ENDPOINTS, id, endpoint);
        return store.object().put("licenseEndpointId", id);
    }

    public synchronized ObjectNode getLicenseEndpoint(ObjectNode input, String region) {
        return store.view(licenseEndpoint(region, text(input, "licenseEndpointId")));
    }

    public synchronized ObjectNode deleteLicenseEndpoint(ObjectNode input, String region) {
        ObjectNode endpoint = licenseEndpoint(region, text(input, "licenseEndpointId"));
        String id = endpoint.path("licenseEndpointId").asText();
        for (ObjectNode product : store.children(region, METERED, "licenseEndpointId", id)) {
            store.delete(region, METERED, product.path("_").path("key").asText());
        }
        if (transitionMillis <= 0) {
            store.delete(region, LICENSE_ENDPOINTS, id);
        } else {
            endpoint.put("status", "DELETE_IN_PROGRESS");
            endpoint.put("statusMessage", "The license endpoint is being deleted.");
            readyIn(endpoint);
            store.put(region, LICENSE_ENDPOINTS, id, endpoint);
        }
        return store.object();
    }

    public synchronized ObjectNode listLicenseEndpoints(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, LICENSE_ENDPOINTS)) {
            try {
                items.add(store.view(licenseEndpoint(region, stored.path("licenseEndpointId").asText()),
                        "licenseEndpointId", "status", "statusMessage", "vpcId"));
            } catch (AwsException e) {
                continue;
            }
        }
        return store.page(items, input, "licenseEndpoints", item -> item.path("licenseEndpointId").asText());
    }

    private ObjectNode product(String productId) {
        for (String[] product : PRODUCTS) {
            if (product[0].equals(productId)) {
                return store.object().put("productId", product[0]).put("family", product[1])
                        .put("vendor", product[2]).put("port", Integer.parseInt(product[3]));
            }
        }
        throw notFound("Metered product " + productId + " not found.", METERED, productId);
    }

    public synchronized ObjectNode listAvailableMeteredProducts(ObjectNode input, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (String[] product : PRODUCTS) {
            items.add(product(product[0]));
        }
        return store.page(items, input, "meteredProducts", item -> item.path("productId").asText());
    }

    public synchronized ObjectNode putMeteredProduct(ObjectNode input, String region) {
        String endpointId = licenseEndpoint(region, text(input, "licenseEndpointId")).path("licenseEndpointId")
                .asText();
        ObjectNode product = product(text(input, "productId"));
        String key = endpointId + "_" + product.path("productId").asText();
        ObjectNode stored = store.record();
        stored.setAll(product);
        ((ObjectNode) stored.get("_")).put("licenseEndpointId", endpointId).put("key", key);
        store.put(region, METERED, key, stored);
        return store.object();
    }

    public synchronized ObjectNode deleteMeteredProduct(ObjectNode input, String region) {
        String endpointId = licenseEndpoint(region, text(input, "licenseEndpointId")).path("licenseEndpointId")
                .asText();
        String key = endpointId + "_" + text(input, "productId");
        store.require(region, METERED, key, "Metered product");
        store.delete(region, METERED, key);
        return store.object();
    }

    public synchronized ObjectNode listMeteredProducts(ObjectNode input, String region) {
        String endpointId = licenseEndpoint(region, text(input, "licenseEndpointId")).path("licenseEndpointId")
                .asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode product : store.children(region, METERED, "licenseEndpointId", endpointId)) {
            items.add(store.view(product));
        }
        return store.page(items, input, "meteredProducts", item -> item.path("productId").asText());
    }

    // ---------------------------------------------------------------- volumes

    /**
     * Persistent volumes belong to service-managed fleet workers, which floci does not launch: a fleet has none.
     */
    public synchronized ObjectNode listVolumes(ObjectNode input, String region) {
        fleet(region, text(input, "farmId"), text(input, "fleetId"));
        return store.page(new ArrayList<>(), input, "volumes", item -> item.path("volumeId").asText());
    }

    public synchronized ObjectNode getVolume(ObjectNode input, String region) {
        fleet(region, text(input, "farmId"), text(input, "fleetId"));
        String id = text(input, "volumeId");
        throw notFound("Volume " + id + " not found.", "volumes", id);
    }

    public synchronized ObjectNode deleteVolume(ObjectNode input, String region) {
        return getVolume(input, region);
    }

    // ---------------------------------------------------------------- tags

    private static final List<String> TAGGABLE = List.of(FARMS, QUEUES, FLEETS, BUDGETS, WORKERS, JOBS, MONITORS,
            LICENSE_ENDPOINTS);

    private ObjectNode taggable(String region, String resourceArn) {
        String target = resourceArn;
        for (String kind : TAGGABLE) {
            for (ObjectNode record : store.list(region, kind, record -> target.equals(record.path("_").path("arn")
                    .asText()))) {
                ((ObjectNode) record.get("_")).put("kind", kind);
                return record;
            }
        }
        throw notFound("Resource " + resourceArn + " not found.", "resources", resourceArn);
    }

    private void saveTagged(String region, ObjectNode record) {
        String kind = record.path("_").path("kind").asText();
        ((ObjectNode) record.get("_")).remove("kind");
        String id = switch (kind) {
            case FARMS -> record.path("farmId").asText();
            case QUEUES -> record.path("queueId").asText();
            case FLEETS -> record.path("fleetId").asText();
            case BUDGETS -> record.path("budgetId").asText();
            case WORKERS -> record.path("workerId").asText();
            case JOBS -> record.path("jobId").asText();
            case MONITORS -> record.path("monitorId").asText();
            default -> record.path("licenseEndpointId").asText();
        };
        store.put(region, kind, id, record);
    }

    public synchronized ObjectNode listTagsForResource(ObjectNode input, String region) {
        ObjectNode response = store.object();
        response.set("tags", taggable(region, text(input, "resourceArn")).path("_").path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(ObjectNode input, String region) {
        ObjectNode record = taggable(region, text(input, "resourceArn"));
        ObjectNode tags = ((ObjectNode) record.get("_")).withObject("tags");
        input.path("tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        if (tags.size() > 50) {
            throw invalid("A resource can have at most 50 tags.");
        }
        saveTagged(region, record);
        return store.object();
    }

    public synchronized ObjectNode untagResource(ObjectNode input, String region) {
        ObjectNode record = taggable(region, text(input, "resourceArn"));
        ObjectNode tags = ((ObjectNode) record.get("_")).withObject("tags");
        input.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        saveTagged(region, record);
        return store.object();
    }

    // ---------------------------------------------------------------- credentials

    /** Credentials for a role Deadline assumes on the caller's behalf: an hour, as Deadline vends them. */
    ObjectNode credentials(String roleArn, String what) {
        if (roleArn == null || roleArn.isEmpty()) {
            throw notFound(what + " has no role.", "roles", what);
        }
        if (!iam.isResolvable()) {
            throw notFound("IAM is not enabled.", "roles", roleArn);
        }
        IamService.RoleSessionCredentials minted = iam.get().mintRoleSession(roleArn);
        ObjectNode response = store.object();
        response.putObject("credentials").put("accessKeyId", minted.accessKeyId())
                .put("secretAccessKey", minted.secretAccessKey()).put("sessionToken", minted.sessionToken())
                .put("expiration", Instant.now().plusSeconds(3600).toEpochMilli() / 1000.0);
        return response;
    }

    public synchronized ObjectNode assumeQueueRoleForRead(ObjectNode input, String region) {
        ObjectNode queue = queue(region, text(input, "farmId"), text(input, "queueId"));
        return credentials(queue.path("roleArn").asText(null), "Queue " + queue.path("queueId").asText());
    }

    public synchronized ObjectNode assumeQueueRoleForUser(ObjectNode input, String region) {
        return assumeQueueRoleForRead(input, region);
    }

    public synchronized ObjectNode assumeFleetRoleForRead(ObjectNode input, String region) {
        ObjectNode fleet = fleet(region, text(input, "farmId"), text(input, "fleetId"));
        return credentials(fleet.path("roleArn").asText(null), "Fleet " + fleet.path("fleetId").asText());
    }

    void publish(String region, String detailType, ObjectNode detail, String arn) {
        events.publish(region, detailType, detail, arn);
    }
}
