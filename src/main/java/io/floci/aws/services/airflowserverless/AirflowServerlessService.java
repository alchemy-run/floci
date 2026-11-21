package io.floci.aws.services.airflowserverless;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MWAA Serverless workflows and their runs. Creating or updating a workflow snapshots its YAML definition (and code
 * package) from S3 as a new version, after checking it defines one DAG whose tasks name operators and depend on tasks
 * that exist; the workflow's log group is created with it. Runs execute the version's DAG in Apache Airflow
 * ({@link AirflowServerlessRunner}) against Floci; their task instances, states, XComs and logs are recorded as the run
 * reports them, task logs go to the log group, and state changes go to EventBridge. A workflow whose definition has a
 * schedule runs on it.
 */
@ApplicationScoped
public class AirflowServerlessService {

    private static final Logger LOG = Logger.getLogger(AirflowServerlessService.class);
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "TIMEOUT", "STOPPED");
    private static final Map<String, String> PRESETS = Map.of("@hourly", "0 * * * *", "@daily", "0 0 * * *",
            "@midnight", "0 0 * * *", "@weekly", "0 0 * * 0", "@monthly", "0 0 1 * *", "@yearly", "0 0 1 1 *",
            "@annually", "0 0 1 1 *");

    private final AirflowServerlessStore store;
    private final AirflowServerlessRunner runner;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<EventBridgeService> eventBridge;
    private final YAMLMapper yaml = new YAMLMapper();
    private final CronParser cron = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private volatile ScheduledExecutorService scheduler;

    @Inject
    public AirflowServerlessService(AirflowServerlessStore store, AirflowServerlessRunner runner,
                                    Provider<S3Service> s3, Provider<CloudWatchLogsService> logs,
                                    Provider<EventBridgeService> eventBridge) {
        this.store = store;
        this.runner = runner;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.logs = logs;
        this.eventBridge = eventBridge;
    }

    // ================================================================ workflows

    public ObjectNode createWorkflow(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "CreateWorkflow", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.path("Name").asText();
        for (ObjectNode other : store.list(region, AirflowServerlessStore.WORKFLOW, "")) {
            if (name.equals(other.path("Name").asText())) {
                throw AirflowServerlessStore.conflict("A workflow named " + name + " already exists.", name,
                        "Workflow");
            }
        }
        if (store.list(region, AirflowServerlessStore.WORKFLOW, "").size() >= store.config().maxWorkflows()) {
            throw AirflowServerlessStore.quota("The account has the maximum number of workflows.");
        }
        checkRole(in.path("RoleArn").asText());
        Definition definition = definition(in);
        String id = name + "-" + suffix();
        String arn = AwsArnUtils.Arn.of(AirflowServerlessStore.SERVICE, region, store.account(), "workflow/" + id)
                .toString();
        double now = AirflowServerlessStore.epoch();
        ObjectNode workflow = store.record();
        workflow.put("WorkflowArn", arn);
        workflow.put("Name", name);
        copy(in, workflow, "Description", "RoleArn", "NetworkConfiguration", "TriggerMode");
        workflow.set("EncryptionConfiguration", in.has("EncryptionConfiguration")
                ? in.get("EncryptionConfiguration").deepCopy()
                : mapper.createObjectNode().put("Type", "AWS_MANAGED_KEY"));
        String logGroup = in.path("LoggingConfiguration").path("LogGroupName").asText("/aws/mwaa-serverless/"
                + id + "/");
        workflow.putObject("LoggingConfiguration").put("LogGroupName", logGroup);
        workflow.put("EngineVersion", in.path("EngineVersion").asInt(1));
        workflow.put("WorkflowStatus", "READY");
        workflow.put("CreatedAt", now);
        workflow.put("ModifiedAt", now);
        workflow.put("RevisionId", UUID.randomUUID().toString());
        if (in.get("Tags") instanceof ObjectNode tags) {
            AirflowServerlessStore.tags(workflow).setAll(tags);
        }
        String version = writeVersion(region, id, arn, in, definition, now);
        workflow.put("WorkflowVersion", version);
        store.put(region, AirflowServerlessStore.WORKFLOW, id, workflow);
        createLogGroup(logGroup, region);
        ensureScheduler();
        ObjectNode out = mapper.createObjectNode();
        out.put("WorkflowArn", arn);
        out.put("CreatedAt", now);
        out.put("RevisionId", workflow.path("RevisionId").asText());
        out.put("WorkflowStatus", "READY");
        out.put("WorkflowVersion", version);
        out.put("IsLatestVersion", true);
        out.set("Warnings", definition.warnings());
        store.remember(region, "CreateWorkflow", in, out);
        return out;
    }

    private void checkRole(String roleArn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
            if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
                throw AirflowServerlessStore.validation("RoleArn must be an IAM role ARN.");
            }
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw AirflowServerlessStore.validation("RoleArn must be an IAM role ARN.");
        }
    }

    private static String suffix() {
        StringBuilder suffix = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return suffix.toString();
    }

    private void createLogGroup(String name, String region) {
        try {
            logs.get().createLogGroup(name, null, Map.of(), region);
        } catch (AwsException e) {
            if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                LOG.debugv("MWAA Serverless log group {0} could not be created: {1}", name, e.getMessage());
            }
        }
    }

    /** A workflow's checked definition: its YAML, DAG id, schedule, code package and warnings. */
    record Definition(String text, String dagId, String cron, byte[] code, ArrayNode warnings) {
    }

    private Definition definition(JsonNode in) {
        JsonNode location = in.path("DefinitionS3Location");
        byte[] bytes = read(location, "definition");
        String text = new String(bytes, StandardCharsets.UTF_8);
        byte[] code = in.path("Code").has("S3Location") ? read(in.path("Code").path("S3Location"), "code package")
                : null;
        if (code != null) {
            checkZip(code);
        }
        JsonNode document;
        try {
            document = yaml.readTree(text);
        } catch (IOException e) {
            throw AirflowServerlessStore.validation("The workflow definition is not valid YAML: " + e.getMessage());
        }
        if (document == null || !document.isObject()) {
            throw AirflowServerlessStore.validation("The workflow definition must be a YAML mapping of a DAG id to "
                    + "its configuration.");
        }
        List<String> dagIds = new ArrayList<>();
        document.fieldNames().forEachRemaining(key -> {
            if (!"default".equals(key) && document.get(key).isObject()) {
                dagIds.add(key);
            }
        });
        if (dagIds.size() != 1) {
            throw AirflowServerlessStore.validation("The workflow definition must define exactly one DAG; it defines "
                    + dagIds.size() + ".");
        }
        String dagId = dagIds.get(0);
        JsonNode dag = document.get(dagId);
        Map<String, JsonNode> tasks = new LinkedHashMap<>();
        JsonNode taskNode = dag.path("tasks");
        if (taskNode.isObject()) {
            taskNode.fields().forEachRemaining(e -> tasks.put(e.getKey(), e.getValue()));
        } else if (taskNode.isArray()) {
            for (JsonNode task : taskNode) {
                tasks.put(task.path("task_id").asText(), task);
            }
        }
        if (tasks.isEmpty()) {
            throw AirflowServerlessStore.validation("The DAG " + dagId + " defines no tasks.");
        }
        ArrayNode warnings = mapper.createArrayNode();
        for (Map.Entry<String, JsonNode> task : tasks.entrySet()) {
            String operator = task.getValue().path("operator").asText("");
            if (operator.isEmpty()) {
                throw AirflowServerlessStore.validation("Task " + task.getKey() + " names no operator.");
            }
            if (!operator.startsWith("airflow.") && code == null) {
                throw AirflowServerlessStore.validation("Task " + task.getKey() + " uses the operator " + operator
                        + ", which needs a code package.");
            }
            for (JsonNode upstream : task.getValue().path("dependencies")) {
                if (!tasks.containsKey(upstream.asText())) {
                    throw AirflowServerlessStore.validation("Task " + task.getKey() + " depends on "
                            + upstream.asText() + ", which the DAG doesn't define.");
                }
            }
        }
        checkAcyclic(tasks);
        String cronExpression = null;
        JsonNode schedule = dag.has("schedule") ? dag.get("schedule") : dag.path("schedule_interval");
        if (schedule.isTextual() && !"None".equalsIgnoreCase(schedule.asText()) && !"@once".equals(
                schedule.asText())) {
            String expression = PRESETS.getOrDefault(schedule.asText(), schedule.asText());
            try {
                cron.parse(expression).validate();
            } catch (IllegalArgumentException e) {
                throw AirflowServerlessStore.validation("The schedule " + schedule.asText() + " is not a cron "
                        + "expression or preset: " + e.getMessage());
            }
            cronExpression = expression;
        }
        return new Definition(text, dagId, cronExpression, code, warnings);
    }

    private static void checkAcyclic(Map<String, JsonNode> tasks) {
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String task : tasks.keySet()) {
            visit(task, tasks, done, visiting);
        }
    }

    private static void visit(String task, Map<String, JsonNode> tasks, Set<String> done, Set<String> visiting) {
        if (done.contains(task)) {
            return;
        }
        if (!visiting.add(task)) {
            throw AirflowServerlessStore.validation("The DAG's dependencies form a cycle through " + task + ".");
        }
        for (JsonNode upstream : tasks.get(task).path("dependencies")) {
            visit(upstream.asText(), tasks, done, visiting);
        }
        visiting.remove(task);
        done.add(task);
    }

    private byte[] read(JsonNode location, String what) {
        String bucket = location.path("Bucket").asText();
        String key = location.path("ObjectKey").asText();
        try {
            return location.hasNonNull("VersionId")
                    ? s3.get().getObject(bucket, key, location.get("VersionId").asText()).getData()
                    : s3.get().getObject(bucket, key).getData();
        } catch (AwsException e) {
            throw AirflowServerlessStore.validation("The " + what + " s3://" + bucket + "/" + key
                    + " can't be read: " + e.getMessage());
        }
    }

    private static void checkZip(byte[] code) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(code))) {
            if (zip.getNextEntry() == null) {
                throw AirflowServerlessStore.validation("The code package is not a zip archive.");
            }
        } catch (IOException e) {
            throw AirflowServerlessStore.validation("The code package is not a zip archive.");
        }
    }

    private String writeVersion(String region, String id, String arn, JsonNode in, Definition definition,
                                double now) {
        String version = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .substring(0, 32);
        for (ObjectNode other : store.list(region, AirflowServerlessStore.VERSION, id + "/")) {
            other.put("IsLatestVersion", false);
            store.put(region, AirflowServerlessStore.VERSION, other.path("_").path("path").asText(), other);
        }
        ObjectNode record = store.record();
        record.put("WorkflowVersion", version);
        record.put("WorkflowArn", arn);
        record.put("IsLatestVersion", true);
        record.put("CreatedAt", now);
        record.put("ModifiedAt", now);
        record.set("DefinitionS3Location", in.path("DefinitionS3Location").deepCopy());
        if (definition.cron() != null) {
            record.putObject("ScheduleConfiguration").put("CronExpression", definition.cron());
        }
        copy(in, record, "TriggerMode", "RoleArn", "Code");
        if (definition.code() != null) {
            record.put("CodeSnapshottedAt", now);
            AirflowServerlessStore.meta(record).put("code", Base64.getEncoder().encodeToString(
                    definition.code()));
        }
        AirflowServerlessStore.meta(record).put("definition", definition.text());
        AirflowServerlessStore.meta(record).put("dagId", definition.dagId());
        store.put(region, AirflowServerlessStore.VERSION, id + "/" + version, record);
        return version;
    }

    /** A workflow by ARN, in the caller's account; {@code ResourceNotFoundException} otherwise. */
    ObjectNode workflow(String arn, String region) {
        String id = workflowId(arn);
        ObjectNode workflow = store.get(region, AirflowServerlessStore.WORKFLOW, id)
                .orElseThrow(() -> AirflowServerlessStore.notFound("The workflow " + arn + " does not exist.", arn,
                        "Workflow"));
        ObjectNode meta = AirflowServerlessStore.meta(workflow);
        if (meta.has("deleteAt") && meta.get("deleteAt").asLong() <= System.currentTimeMillis()) {
            purge(region, id);
            throw AirflowServerlessStore.notFound("The workflow " + arn + " does not exist.", arn, "Workflow");
        }
        return workflow;
    }

    private static String workflowId(String arn) {
        int index = arn == null ? -1 : arn.indexOf(":workflow/");
        if (index < 0) {
            throw AirflowServerlessStore.validation("The workflow ARN " + arn + " is not valid.");
        }
        return arn.substring(index + ":workflow/".length());
    }

    private void purge(String region, String id) {
        for (String kind : List.of(AirflowServerlessStore.VERSION, AirflowServerlessStore.RUN,
                AirflowServerlessStore.TASK)) {
            for (ObjectNode record : store.list(region, kind, id + "/")) {
                store.deleteForAccount(store.account(), region, kind, record.path("_").path("path").asText());
            }
        }
        store.deleteForAccount(store.account(), region, AirflowServerlessStore.WORKFLOW, id);
    }

    private ObjectNode version(String region, String id, String version) {
        if (version == null) {
            for (ObjectNode record : store.list(region, AirflowServerlessStore.VERSION, id + "/")) {
                if (record.path("IsLatestVersion").asBoolean(false)) {
                    return record;
                }
            }
        }
        return store.get(region, AirflowServerlessStore.VERSION, id + "/" + version)
                .orElseThrow(() -> AirflowServerlessStore.notFound("The workflow has no version " + version + ".",
                        String.valueOf(version), "WorkflowVersion"));
    }

    public ObjectNode getWorkflow(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        ObjectNode version = version(region, id, in.path("WorkflowVersion").asText(null));
        ObjectNode out = AirflowServerlessStore.view(workflow);
        out.remove("RevisionId");
        copy(version, out, "WorkflowVersion", "DefinitionS3Location", "Code", "CodeSnapshottedAt",
                "ScheduleConfiguration", "TriggerMode");
        out.put("WorkflowDefinition", version.path("_").path("dagId").asText());
        return out;
    }

    public ObjectNode updateWorkflow(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        if (!"READY".equals(workflow.path("WorkflowStatus").asText())) {
            throw AirflowServerlessStore.conflict("The workflow is being deleted.", workflow.path("WorkflowArn")
                    .asText(), "Workflow");
        }
        checkRole(in.path("RoleArn").asText());
        Definition definition = definition(in);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        double now = AirflowServerlessStore.epoch();
        for (String field : List.of("Description", "NetworkConfiguration", "TriggerMode")) {
            workflow.remove(field);
        }
        copy(in, workflow, "Description", "RoleArn", "NetworkConfiguration", "TriggerMode", "EngineVersion");
        if (in.has("LoggingConfiguration")) {
            workflow.set("LoggingConfiguration", in.get("LoggingConfiguration").deepCopy());
            createLogGroup(in.path("LoggingConfiguration").path("LogGroupName").asText(), region);
        }
        workflow.put("ModifiedAt", now);
        workflow.put("RevisionId", UUID.randomUUID().toString());
        String version = writeVersion(region, id, workflow.path("WorkflowArn").asText(), in, definition, now);
        workflow.put("WorkflowVersion", version);
        store.put(region, AirflowServerlessStore.WORKFLOW, id, workflow);
        ObjectNode out = mapper.createObjectNode();
        out.put("WorkflowArn", workflow.path("WorkflowArn").asText());
        out.put("ModifiedAt", now);
        out.put("WorkflowVersion", version);
        out.set("Warnings", definition.warnings());
        return out;
    }

    public ObjectNode deleteWorkflow(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String arn = workflow.path("WorkflowArn").asText();
        String id = workflowId(arn);
        String requested = in.path("WorkflowVersion").asText(null);
        List<ObjectNode> versions = store.list(region, AirflowServerlessStore.VERSION, id + "/");
        if (requested != null && versions.size() > 1) {
            ObjectNode version = version(region, id, requested);
            if (version.path("IsLatestVersion").asBoolean(false)) {
                throw AirflowServerlessStore.validation("The latest version can't be deleted while older versions "
                        + "remain; delete the workflow instead.");
            }
            store.deleteForAccount(store.account(), region, AirflowServerlessStore.VERSION, id + "/" + requested);
            ObjectNode out = mapper.createObjectNode();
            out.put("WorkflowArn", arn);
            out.put("WorkflowVersion", requested);
            return out;
        }
        for (ObjectNode run : store.list(region, AirflowServerlessStore.RUN, id + "/")) {
            if (!TERMINAL.contains(run.path("RunDetail").path("RunState").asText())) {
                runner.stop(run.path("RunId").asText());
            }
        }
        workflow.put("WorkflowStatus", "DELETING");
        AirflowServerlessStore.meta(workflow).put("deleteAt", System.currentTimeMillis()
                + store.config().transitionMillis());
        store.put(region, AirflowServerlessStore.WORKFLOW, id, workflow);
        ObjectNode out = mapper.createObjectNode();
        out.put("WorkflowArn", arn);
        out.put("WorkflowVersion", workflow.path("WorkflowVersion").asText());
        return out;
    }

    public ObjectNode listWorkflows(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AirflowServerlessStore.WORKFLOW, "")) {
            ObjectNode meta = AirflowServerlessStore.meta(record);
            if (meta.has("deleteAt") && meta.get("deleteAt").asLong() <= System.currentTimeMillis()) {
                purge(region, meta.path("path").asText());
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "WorkflowArn", "WorkflowVersion", "Name", "Description", "CreatedAt", "ModifiedAt",
                    "WorkflowStatus", "TriggerMode");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        AirflowServerlessStore.page(items, in, out, "Workflows");
        return out;
    }

    public ObjectNode listWorkflowVersions(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, AirflowServerlessStore.VERSION, id + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(record, summary, "WorkflowVersion", "WorkflowArn", "IsLatestVersion", "CreatedAt", "ModifiedAt",
                    "DefinitionS3Location", "ScheduleConfiguration", "TriggerMode");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        AirflowServerlessStore.page(items, in, out, "WorkflowVersions");
        return out;
    }

    // ================================================================ tags

    private ObjectNode tagged(String arn, String region) {
        return workflow(arn, region);
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode workflow = tagged(in.path("ResourceArn").asText(), region);
        AirflowServerlessStore.tags(workflow).setAll((ObjectNode) in.get("Tags"));
        if (AirflowServerlessStore.tags(workflow).size() > 50) {
            throw AirflowServerlessStore.validation("A workflow can have at most 50 tags.");
        }
        store.put(region, AirflowServerlessStore.WORKFLOW, workflowId(workflow.path("WorkflowArn").asText()),
                workflow);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode workflow = tagged(in.path("ResourceArn").asText(), region);
        ObjectNode tags = AirflowServerlessStore.tags(workflow);
        in.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        store.put(region, AirflowServerlessStore.WORKFLOW, workflowId(workflow.path("WorkflowArn").asText()),
                workflow);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode workflow = tagged(in.path("ResourceArn").asText(), region);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", AirflowServerlessStore.tags(workflow).deepCopy());
        return out;
    }

    // ================================================================ runs

    public ObjectNode startWorkflowRun(JsonNode in, String region) {
        ObjectNode repeated = store.repeated(region, "StartWorkflowRun", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        if (!"READY".equals(workflow.path("WorkflowStatus").asText())) {
            throw AirflowServerlessStore.conflict("The workflow is being deleted.", workflow.path("WorkflowArn")
                    .asText(), "Workflow");
        }
        int active = 0;
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                AirflowServerlessStore.RUN)) {
            if (store.account().equals(entry.accountId()) && !TERMINAL.contains(entry.value().path("RunDetail")
                    .path("RunState").asText())) {
                active++;
            }
        }
        if (active >= store.config().maxConcurrentRuns()) {
            throw AirflowServerlessStore.quota("The account has the maximum number of concurrent runs.");
        }
        ObjectNode out = start(region, workflow, in.path("WorkflowVersion").asText(null),
                in.get("OverrideParameters"), "ON_DEMAND");
        store.remember(region, "StartWorkflowRun", in, out);
        return out;
    }

    private ObjectNode start(String region, ObjectNode workflow, String requestedVersion, JsonNode overrides,
                             String type) {
        String arn = workflow.path("WorkflowArn").asText();
        String id = workflowId(arn);
        ObjectNode version = version(region, id, requestedVersion);
        String runId = UUID.randomUUID().toString();
        double now = AirflowServerlessStore.epoch();
        ObjectNode run = store.record();
        run.put("WorkflowArn", arn);
        run.put("WorkflowVersion", version.path("WorkflowVersion").asText());
        run.put("RunId", runId);
        run.put("RunType", type);
        if (overrides != null && overrides.isObject()) {
            run.set("OverrideParameters", overrides.deepCopy());
        }
        ObjectNode detail = run.putObject("RunDetail");
        detail.put("WorkflowArn", arn);
        detail.put("WorkflowVersion", version.path("WorkflowVersion").asText());
        detail.put("RunId", runId);
        detail.put("RunType", type);
        detail.put("CreatedAt", now);
        detail.put("StartedOn", now);
        detail.put("ModifiedAt", now);
        detail.putArray("TaskInstances");
        detail.put("RunState", "STARTING");
        store.put(region, AirflowServerlessStore.RUN, id + "/" + runId, run);
        String account = store.account();
        publish(region, arn, runId, null, null, "STARTING");
        byte[] code = version.path("_").hasNonNull("code") ? Base64.getDecoder().decode(version.path("_")
                .path("code").asText()) : null;
        runner.submit(new AirflowServerlessRunner.Job(account, region, arn, runId, workflow.path("RoleArn")
                .asText(), version.path("_").path("definition").asText(), code, overrides,
                Instant.ofEpochMilli((long) (now * 1000)).toString()), new Progress(account, region, id, runId));
        ObjectNode out = mapper.createObjectNode();
        out.put("RunId", runId);
        out.put("Status", "STARTING");
        out.put("StartedAt", now);
        return out;
    }

    private ObjectNode run(String region, JsonNode in) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        String runId = in.path("RunId").asText();
        return store.get(region, AirflowServerlessStore.RUN, id + "/" + runId)
                .orElseThrow(() -> AirflowServerlessStore.notFound("The workflow run " + runId + " does not exist.",
                        runId, "WorkflowRun"));
    }

    public ObjectNode getWorkflowRun(JsonNode in, String region) {
        ObjectNode run = AirflowServerlessStore.view(run(region, in));
        double started = run.path("RunDetail").path("StartedOn").asDouble();
        double ended = run.path("RunDetail").path("CompletedOn").asDouble(AirflowServerlessStore.epoch());
        ((ObjectNode) run.get("RunDetail")).put("Duration", (int) Math.max(0, ended - started));
        return run;
    }

    public ObjectNode stopWorkflowRun(JsonNode in, String region) {
        ObjectNode run = run(region, in);
        String state = run.path("RunDetail").path("RunState").asText();
        if (TERMINAL.contains(state)) {
            throw AirflowServerlessStore.validation("The workflow run is already " + state + ".");
        }
        ((ObjectNode) run.get("RunDetail")).put("RunState", "STOPPING");
        String id = workflowId(run.path("WorkflowArn").asText());
        store.put(region, AirflowServerlessStore.RUN, id + "/" + run.path("RunId").asText(), run);
        runner.stop(run.path("RunId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("WorkflowArn", run.path("WorkflowArn").asText());
        out.put("WorkflowVersion", run.path("WorkflowVersion").asText());
        out.put("RunId", run.path("RunId").asText());
        out.put("Status", "STOPPING");
        return out;
    }

    public ObjectNode listWorkflowRuns(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        String version = in.path("WorkflowVersion").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode run : store.list(region, AirflowServerlessStore.RUN, id + "/")) {
            if (version != null && !version.equals(run.path("WorkflowVersion").asText())) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            copy(run, summary, "RunId", "WorkflowArn", "WorkflowVersion", "RunType");
            JsonNode detail = run.path("RunDetail");
            ObjectNode brief = summary.putObject("RunDetailSummary");
            brief.put("Status", detail.path("RunState").asText());
            brief.set("CreatedOn", detail.path("CreatedAt").deepCopy());
            if (detail.has("StartedOn")) {
                brief.set("StartedAt", detail.get("StartedOn").deepCopy());
            }
            if (detail.has("CompletedOn")) {
                brief.set("EndedAt", detail.get("CompletedOn").deepCopy());
            }
            items.add(summary);
        }
        items.sort((a, b) -> Double.compare(b.path("RunDetailSummary").path("CreatedOn").asDouble(),
                a.path("RunDetailSummary").path("CreatedOn").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        AirflowServerlessStore.page(items, in, out, "WorkflowRuns");
        return out;
    }

    public ObjectNode listTaskInstances(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        String runId = in.path("RunId").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode task : store.list(region, AirflowServerlessStore.TASK, id + "/" + runId + "/")) {
            ObjectNode summary = mapper.createObjectNode();
            copy(task, summary, "WorkflowArn", "WorkflowVersion", "RunId", "TaskInstanceId", "Status",
                    "DurationInSeconds", "OperatorName");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        AirflowServerlessStore.page(items, in, out, "TaskInstances");
        return out;
    }

    public ObjectNode getTaskInstance(JsonNode in, String region) {
        ObjectNode workflow = workflow(in.path("WorkflowArn").asText(), region);
        String id = workflowId(workflow.path("WorkflowArn").asText());
        String runId = in.path("RunId").asText();
        String taskInstanceId = in.path("TaskInstanceId").asText();
        if (store.get(region, AirflowServerlessStore.RUN, id + "/" + runId).isEmpty()) {
            throw AirflowServerlessStore.notFound("The workflow run " + runId + " does not exist.", runId,
                    "WorkflowRun");
        }
        ObjectNode task = store.get(region, AirflowServerlessStore.TASK, id + "/" + runId + "/" + taskInstanceId)
                .orElseThrow(() -> AirflowServerlessStore.notFound("The task instance " + taskInstanceId
                        + " does not exist.", taskInstanceId, "TaskInstance"));
        return AirflowServerlessStore.view(task);
    }

    // ================================================================ run progress

    /** Records what a run reports, in the run's account and Region. */
    final class Progress implements AirflowServerlessRunner.Listener {
        private final String account;
        private final String region;
        private final String workflowId;
        private final String runId;
        private final Map<String, String> instances = new LinkedHashMap<>();
        private final Map<String, String> streams = new LinkedHashMap<>();

        Progress(String account, String region, String workflowId, String runId) {
            this.account = account;
            this.region = region;
            this.workflowId = workflowId;
            this.runId = runId;
        }

        private ObjectNode run() {
            return store.getForAccount(account, region, AirflowServerlessStore.RUN, workflowId + "/" + runId)
                    .orElse(null);
        }

        @Override
        public void run(String state, String error) {
            RequestScopes.runAs(account, region, () -> {
                ObjectNode run = run();
                if (run == null) {
                    return;
                }
                ObjectNode detail = (ObjectNode) run.get("RunDetail");
                String current = detail.path("RunState").asText();
                if (TERMINAL.contains(current)) {
                    return;
                }
                String next = "STOPPING".equals(current) && TERMINAL.contains(state) ? "STOPPED" : state;
                if ("STOPPING".equals(current) && !TERMINAL.contains(state)) {
                    return;
                }
                detail.put("RunState", next);
                double now = AirflowServerlessStore.epoch();
                detail.put("ModifiedAt", now);
                if (TERMINAL.contains(next)) {
                    detail.put("CompletedOn", now);
                    detail.put("Duration", (int) Math.max(0, now - detail.path("StartedOn").asDouble()));
                    if (error != null && !error.isBlank()) {
                        detail.put("ErrorMessage", error.length() > 255 ? error.substring(0, 255) : error);
                    }
                }
                store.putForAccount(account, region, AirflowServerlessStore.RUN, workflowId + "/" + runId, run);
                publish(region, run.path("WorkflowArn").asText(), runId, null, null, next);
            });
        }

        @Override
        public void task(JsonNode event) {
            RequestScopes.runAs(account, region, () -> {
                ObjectNode run = run();
                if (run == null) {
                    return;
                }
                String taskId = event.path("task").asText();
                String instanceId = instances.computeIfAbsent(taskId, t -> UUID.randomUUID().toString());
                String path = workflowId + "/" + runId + "/" + instanceId;
                ObjectNode task = store.getForAccount(account, region, AirflowServerlessStore.TASK, path)
                        .orElseGet(() -> {
                            ObjectNode created = store.record();
                            created.put("WorkflowArn", run.path("WorkflowArn").asText());
                            created.put("RunId", runId);
                            created.put("TaskInstanceId", instanceId);
                            created.put("WorkflowVersion", run.path("WorkflowVersion").asText());
                            created.put("TaskId", taskId);
                            created.put("AttemptNumber", 1);
                            created.put("LogStream", stream(run, taskId, 1));
                            ArrayNode ids = ((ObjectNode) run.get("RunDetail")).withArray("TaskInstances");
                            ids.add(instanceId);
                            store.putForAccount(account, region, AirflowServerlessStore.RUN, workflowId + "/" + runId,
                                    run);
                            return created;
                        });
                String state = taskState(event.path("state").asText());
                String previous = task.path("Status").asText("");
                task.put("Status", state);
                if (event.hasNonNull("operator")) {
                    task.put("OperatorName", event.get("operator").asText());
                }
                if (event.hasNonNull("try")) {
                    task.put("AttemptNumber", Math.max(1, event.get("try").asInt()));
                }
                double now = AirflowServerlessStore.epoch();
                task.put("ModifiedAt", now);
                if ("RUNNING".equals(state) && !task.has("StartedAt")) {
                    task.put("StartedAt", now);
                }
                if (event.hasNonNull("started")) {
                    task.put("StartedAt", seconds(event.get("started").asText(), now));
                }
                if (event.hasNonNull("ended")) {
                    task.put("EndedAt", seconds(event.get("ended").asText(), now));
                }
                if (task.has("StartedAt") && task.has("EndedAt")) {
                    task.put("DurationInSeconds", (int) Math.max(0, task.get("EndedAt").asDouble()
                            - task.get("StartedAt").asDouble()));
                }
                if (event.hasNonNull("xcom")) {
                    task.putObject("Xcom").put("return_value", event.get("xcom").asText());
                }
                if (event.hasNonNull("error")) {
                    task.put("ErrorMessage", event.get("error").asText());
                }
                store.putForAccount(account, region, AirflowServerlessStore.TASK, path, task);
                if (!state.equals(previous)) {
                    publish(region, run.path("WorkflowArn").asText(), runId, instanceId, taskId, state);
                }
            });
        }

        private String stream(ObjectNode run, String taskId, int attempt) {
            return streams.computeIfAbsent(taskId, t -> "workflow_id=" + workflowId + "/run_id=" + runId
                    + "/task_id=" + taskId + "/attempt=" + attempt + ".log");
        }

        @Override
        public void log(String taskId, long timestamp, String message) {
            RequestScopes.runAs(account, region, () -> {
                ObjectNode workflow = store.getForAccount(account, region, AirflowServerlessStore.WORKFLOW,
                        workflowId).orElse(null);
                ObjectNode run = run();
                if (workflow == null || run == null) {
                    return;
                }
                String group = workflow.path("LoggingConfiguration").path("LogGroupName").asText();
                String stream = stream(run, taskId, 1);
                try {
                    try {
                        logs.get().createLogStream(group, stream, region);
                    } catch (AwsException e) {
                        if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                            createLogGroup(group, region);
                            logs.get().createLogStream(group, stream, region);
                        } else if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", timestamp, "message",
                            message)), region);
                } catch (AwsException e) {
                    LOG.debugv("MWAA Serverless task log for {0} could not be written: {1}", taskId,
                            e.getMessage());
                }
            });
        }
    }

    private static double seconds(String iso, double fallback) {
        try {
            return Instant.parse(iso.replace("+00:00", "Z")).toEpochMilli() / 1000.0;
        } catch (RuntimeException e) {
            try {
                return ZonedDateTime.parse(iso).toInstant().toEpochMilli() / 1000.0;
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    private static String taskState(String airflow) {
        return switch (airflow.toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            case "UPSTREAM_FAILED" -> "UPSTREAM_FAILED";
            case "UP_FOR_RETRY" -> "UP_FOR_RETRY";
            case "UP_FOR_RESCHEDULE" -> "UP_FOR_RESCHEDULE";
            case "RUNNING" -> "RUNNING";
            case "QUEUED" -> "QUEUED";
            case "SCHEDULED" -> "SCHEDULED";
            case "DEFERRED" -> "DEFERRED";
            case "RESTARTING" -> "RESTARTING";
            case "REMOVED" -> "REMOVED";
            case "CANCELLED" -> "CANCELLED";
            case "TIMEOUT" -> "TIMEOUT";
            default -> "NONE";
        };
    }

    /** The EventBridge event of a run's or task's new state. */
    private void publish(String region, String workflowArn, String runId, String taskInstanceId, String taskId,
                         String state) {
        String label = switch (state) {
            case "STARTING" -> "Started";
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "Running";
            case "SUCCESS" -> "Succeeded";
            case "FAILED" -> "Failed";
            case "STOPPED" -> "Stopped";
            case "TIMEOUT" -> "Timeout";
            case "SCHEDULED" -> "Scheduled";
            case "UPSTREAM_FAILED" -> "Upstream Failed";
            case "UP_FOR_RETRY" -> "Up For Retry";
            default -> null;
        };
        if (label == null || (taskInstanceId == null && "Scheduled".equals(label))) {
            return;
        }
        ObjectNode detail = mapper.createObjectNode();
        detail.put("workflowArn", workflowArn);
        detail.put("runId", runId);
        if (taskInstanceId != null) {
            detail.put("taskInstanceId", taskInstanceId);
            detail.put("taskId", taskId);
        }
        detail.put("status", state);
        String type = taskInstanceId == null ? "MWAA Serverless Workflow Run " + label : "MWAA Serverless Task " + label;
        try {
            eventBridge.get().publishServiceEvent("aws.airflow-serverless", type, detail.toString(), workflowArn,
                    region);
        } catch (RuntimeException e) {
            LOG.debugv("MWAA Serverless event {0} could not be published: {1}", type, e.getMessage());
        }
    }

    // ================================================================ schedules

    private void ensureScheduler() {
        if (scheduler != null) {
            return;
        }
        synchronized (this) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "airflow-serverless-scheduler");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(this::tick, 15, 15, TimeUnit.SECONDS);
            }
        }
    }

    /** Starts the scheduled runs that are due: each workflow version's cron, once per fire time. */
    void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(
                AirflowServerlessStore.WORKFLOW)) {
            ObjectNode workflow = entry.value();
            String region = workflow.path("_").path("region").asText();
            String id = workflow.path("_").path("path").asText();
            if (!"READY".equals(workflow.path("WorkflowStatus").asText())) {
                continue;
            }
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    ObjectNode version = version(region, id, null);
                    String expression = version.path("ScheduleConfiguration").path("CronExpression").asText(null);
                    if (expression == null) {
                        return;
                    }
                    ExecutionTime times = ExecutionTime.forCron(cron.parse(expression));
                    Optional<ZonedDateTime> last = times.lastExecution(now);
                    ObjectNode meta = AirflowServerlessStore.meta(workflow);
                    long fired = meta.path("lastScheduled").asLong(0);
                    long created = (long) (version.path("CreatedAt").asDouble() * 1000);
                    if (last.isPresent() && last.get().toInstant().toEpochMilli() > Math.max(fired, created)) {
                        meta.put("lastScheduled", last.get().toInstant().toEpochMilli());
                        store.put(region, AirflowServerlessStore.WORKFLOW, id, workflow);
                        start(region, workflow, null, null, "SCHEDULED");
                    }
                });
            } catch (RuntimeException e) {
                LOG.debugv("MWAA Serverless schedule for {0} failed: {1}", id, e.getMessage());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull()) {
                to.set(field, value.deepCopy());
            }
        }
    }
}
