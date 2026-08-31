package io.github.hectorvent.floci.services.mwaaserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Amazon MWAA Serverless (JSON 1.0, {@code AmazonMWAAServerless.*}).
 *
 * <p>Workflows are in-memory; provisioning is instantaneous ({@code READY}).
 * Create auto-creates the per-workflow CloudWatch log group
 * {@code /aws/mwaa-serverless/{name}-{suffix}/} that live AWS creates and
 * {@code deleteWorkflow} leaves behind.
 */
@ApplicationScoped
public class MwaaServerlessService implements Resettable {

    private static final Logger LOG = Logger.getLogger(MwaaServerlessService.class);
    static final String SERVICE = "airflow-serverless";
    private static final String SUFFIX_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int SUFFIX_LENGTH = 10;
    private static final Pattern WORKFLOW_ARN = Pattern.compile(
            "^arn:aws:airflow-serverless:[a-z0-9-]+:\\d{12}:workflow/.+-[A-Za-z0-9]{10}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Workflow {
        String arn;
        String name;
        String resourceId;
        String region;
        String roleArn;
        String description;
        String status;
        String version;
        String revisionId;
        String createdAt;
        String modifiedAt;
        String triggerMode;
        Integer engineVersion;
        String clientToken;
        String logGroupName;
        JsonNode definitionS3Location;
        JsonNode encryptionConfiguration;
        JsonNode loggingConfiguration;
        JsonNode networkConfiguration;
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, Run> runs = new LinkedHashMap<>();
    }

    static final class Run {
        String runId;
        String workflowArn;
        String workflowVersion;
        String runType;
        String status;
        String createdAt;
        String startedAt;
        String completedOn;
        String modifiedAt;
        JsonNode overrideParameters;
        final List<TaskInstance> tasks = new ArrayList<>();
    }

    static final class TaskInstance {
        String taskInstanceId;
        String taskId;
        String workflowArn;
        String workflowVersion;
        String runId;
        String status;
        String operatorName;
        String startedAt;
        String endedAt;
        String modifiedAt;
        int attemptNumber;
        Integer durationInSeconds;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final Instance<CloudWatchLogsService> logsService;
    private final ConcurrentHashMap<String, Workflow> workflows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> startTokens = new ConcurrentHashMap<>();

    @Inject
    public MwaaServerlessService(
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            Instance<CloudWatchLogsService> logsService) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.logsService = logsService;
    }

    @Override
    public void clear() {
        workflows.clear();
        createTokens.clear();
        startTokens.clear();
    }

    public synchronized ObjectNode createWorkflow(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingArn = createTokens.get(token);
            if (existingArn != null) {
                Workflow existing = workflows.get(existingArn);
                if (existing != null) {
                    return createResponse(existing);
                }
            }
        }
        String name = requireText(request, "Name");
        Workflow duplicate = findByName(name);
        if (duplicate != null) {
            throw conflict(duplicate.arn, "Workflow " + name + " already exists.");
        }
        JsonNode definition = requireObject(request, "DefinitionS3Location");
        String roleArn = requireText(request, "RoleArn");
        String suffix = newSuffix();
        String resourceId = name + "-" + suffix;
        String arn = regionResolver.buildArn(SERVICE, region, "workflow/" + resourceId);
        String now = now();

        Workflow workflow = new Workflow();
        workflow.arn = arn;
        workflow.name = name;
        workflow.resourceId = resourceId;
        workflow.region = region;
        workflow.roleArn = roleArn;
        workflow.description = textOrNull(request, "Description");
        workflow.status = "READY";
        workflow.version = "1";
        workflow.revisionId = newRevision();
        workflow.createdAt = now;
        workflow.modifiedAt = now;
        workflow.triggerMode = textOrNull(request, "TriggerMode");
        workflow.engineVersion = intOrNull(request, "EngineVersion");
        if (workflow.engineVersion == null) {
            workflow.engineVersion = 1;
        }
        workflow.clientToken = token;
        workflow.definitionS3Location = copy(definition);
        workflow.encryptionConfiguration = encryptionOrDefault(request.get("EncryptionConfiguration"));
        workflow.loggingConfiguration = copy(request.get("LoggingConfiguration"));
        workflow.networkConfiguration = copy(request.get("NetworkConfiguration"));
        workflow.tags.putAll(readTagMap(request.get("Tags")));
        workflow.logGroupName = "/aws/mwaa-serverless/" + resourceId + "/";
        workflows.put(arn, workflow);
        if (token != null) {
            createTokens.put(token, arn);
        }
        ensureLogGroup(workflow.logGroupName, region);
        return createResponse(workflow);
    }

    public ObjectNode getWorkflow(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String requestedVersion = textOrNull(request, "WorkflowVersion");
        if (requestedVersion != null && !requestedVersion.equals(workflow.version)) {
            throw notFound(workflow.arn);
        }
        return getResponse(workflow);
    }

    public synchronized ObjectNode updateWorkflow(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        JsonNode definition = requireObject(request, "DefinitionS3Location");
        String roleArn = requireText(request, "RoleArn");
        workflow.definitionS3Location = copy(definition);
        workflow.roleArn = roleArn;
        if (request.hasNonNull("Description")) {
            workflow.description = request.get("Description").asText();
        }
        if (request.has("LoggingConfiguration")) {
            workflow.loggingConfiguration = copy(request.get("LoggingConfiguration"));
        }
        if (request.has("EngineVersion") && request.get("EngineVersion").isNumber()) {
            workflow.engineVersion = request.get("EngineVersion").asInt();
        }
        if (request.has("NetworkConfiguration")) {
            workflow.networkConfiguration = copy(request.get("NetworkConfiguration"));
        }
        if (request.hasNonNull("TriggerMode")) {
            workflow.triggerMode = request.get("TriggerMode").asText();
        }
        workflow.version = Integer.toString(Integer.parseInt(workflow.version) + 1);
        workflow.revisionId = newRevision();
        workflow.modifiedAt = now();
        workflows.put(workflow.arn, workflow);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WorkflowArn", workflow.arn);
        response.put("ModifiedAt", workflow.modifiedAt);
        response.put("WorkflowVersion", workflow.version);
        return response;
    }

    public synchronized ObjectNode deleteWorkflow(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String requestedVersion = textOrNull(request, "WorkflowVersion");
        if (requestedVersion != null && !requestedVersion.equals(workflow.version)) {
            throw notFound(workflow.arn);
        }
        workflows.remove(workflow.arn);
        if (workflow.clientToken != null) {
            createTokens.remove(workflow.clientToken);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WorkflowArn", workflow.arn);
        if (requestedVersion != null) {
            response.put("WorkflowVersion", requestedVersion);
        }
        return response;
    }

    public ObjectNode listWorkflows(JsonNode request) {
        List<Workflow> items = new ArrayList<>(workflows.values());
        items.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        ArrayNode list = objectMapper.createArrayNode();
        for (Workflow workflow : items) {
            list.add(summary(workflow));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Workflows", list);
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        Workflow workflow = requireWorkflowArn(requireText(request, "ResourceArn"));
        workflow.tags.putAll(readTagMap(request.get("Tags")));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Workflow workflow = requireWorkflowArn(requireText(request, "ResourceArn"));
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                workflow.tags.remove(key.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Workflow workflow = requireWorkflowArn(requireText(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tags = response.putObject("Tags");
        workflow.tags.forEach(tags::put);
        return response;
    }

    public ObjectNode listWorkflowVersions(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("WorkflowVersions");
        ObjectNode node = list.addObject();
        node.put("WorkflowVersion", workflow.version);
        node.put("WorkflowArn", workflow.arn);
        node.put("IsLatestVersion", true);
        node.put("CreatedAt", workflow.createdAt);
        node.put("ModifiedAt", workflow.modifiedAt);
        setIfPresent(node, "DefinitionS3Location", workflow.definitionS3Location);
        if (workflow.triggerMode != null) {
            node.put("TriggerMode", workflow.triggerMode);
        }
        return response;
    }

    public synchronized ObjectNode startWorkflowRun(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = startTokens.get(token);
            if (existingId != null) {
                Run existing = workflow.runs.get(existingId);
                if (existing != null) {
                    return startResponse(existing);
                }
            }
        }
        String now = now();
        Run run = new Run();
        run.runId = UUID.randomUUID().toString();
        run.workflowArn = workflow.arn;
        run.workflowVersion = workflow.version;
        run.runType = "ON_DEMAND";
        run.status = "RUNNING";
        run.createdAt = now;
        run.startedAt = now;
        run.modifiedAt = now;
        run.overrideParameters = copy(request.get("OverrideParameters"));

        TaskInstance task = new TaskInstance();
        task.taskInstanceId = UUID.randomUUID().toString();
        task.taskId = "list_definitions";
        task.workflowArn = workflow.arn;
        task.workflowVersion = workflow.version;
        task.runId = run.runId;
        task.status = "SUCCESS";
        task.operatorName = "S3ListOperator";
        task.startedAt = now;
        task.endedAt = now;
        task.modifiedAt = now;
        task.attemptNumber = 1;
        task.durationInSeconds = 0;
        run.tasks.add(task);
        workflow.runs.put(run.runId, run);
        if (token != null) {
            startTokens.put(token, run.runId);
        }
        return startResponse(run);
    }

    public ObjectNode getWorkflowRun(JsonNode request) {
        return getRunNode(requireRun(request));
    }

    public synchronized ObjectNode stopWorkflowRun(JsonNode request) {
        Run run = requireRun(request);
        if (!"RUNNING".equals(run.status) && !"STARTING".equals(run.status)
                && !"QUEUED".equals(run.status) && !"STOPPING".equals(run.status)) {
            throw invalid("Workflow run " + run.runId + " is not stoppable in status " + run.status + ".");
        }
        String now = now();
        run.status = "STOPPED";
        run.completedOn = now;
        run.modifiedAt = now;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WorkflowArn", run.workflowArn);
        response.put("WorkflowVersion", run.workflowVersion);
        response.put("RunId", run.runId);
        response.put("Status", run.status);
        return response;
    }

    public ObjectNode listWorkflowRuns(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String versionFilter = textOrNull(request, "WorkflowVersion");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("WorkflowRuns");
        for (Run run : workflow.runs.values()) {
            if (versionFilter != null && !versionFilter.equals(run.workflowVersion)) {
                continue;
            }
            ObjectNode node = list.addObject();
            node.put("RunId", run.runId);
            node.put("WorkflowArn", run.workflowArn);
            node.put("WorkflowVersion", run.workflowVersion);
            node.put("RunType", run.runType);
            ObjectNode detail = node.putObject("RunDetailSummary");
            detail.put("Status", run.status);
            detail.put("CreatedOn", run.createdAt);
            if (run.startedAt != null) {
                detail.put("StartedAt", run.startedAt);
            }
            if (run.completedOn != null) {
                detail.put("EndedAt", run.completedOn);
            }
        }
        return response;
    }

    public ObjectNode listTaskInstances(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String runId = requireText(request, "RunId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("TaskInstances");
        Run run = workflow.runs.get(runId);
        if (run == null) {
            return response;
        }
        for (TaskInstance task : run.tasks) {
            ObjectNode node = list.addObject();
            node.put("WorkflowArn", task.workflowArn);
            node.put("WorkflowVersion", task.workflowVersion);
            node.put("RunId", task.runId);
            node.put("TaskInstanceId", task.taskInstanceId);
            node.put("Status", task.status);
            if (task.durationInSeconds != null) {
                node.put("DurationInSeconds", task.durationInSeconds);
            }
            if (task.operatorName != null) {
                node.put("OperatorName", task.operatorName);
            }
        }
        return response;
    }

    public ObjectNode getTaskInstance(JsonNode request) {
        Run run = requireRun(request);
        String taskId = requireText(request, "TaskInstanceId");
        TaskInstance task = run.tasks.stream()
                .filter(item -> taskId.equals(item.taskInstanceId))
                .findFirst()
                .orElseThrow(() -> notFound(taskId, "TaskInstance"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("WorkflowArn", task.workflowArn);
        node.put("RunId", task.runId);
        node.put("TaskInstanceId", task.taskInstanceId);
        node.put("WorkflowVersion", task.workflowVersion);
        node.put("Status", task.status);
        if (task.durationInSeconds != null) {
            node.put("DurationInSeconds", task.durationInSeconds);
        }
        if (task.operatorName != null) {
            node.put("OperatorName", task.operatorName);
        }
        if (task.modifiedAt != null) {
            node.put("ModifiedAt", task.modifiedAt);
        }
        if (task.endedAt != null) {
            node.put("EndedAt", task.endedAt);
        }
        if (task.startedAt != null) {
            node.put("StartedAt", task.startedAt);
        }
        node.put("AttemptNumber", task.attemptNumber);
        if (task.taskId != null) {
            node.put("TaskId", task.taskId);
        }
        return node;
    }

    private ObjectNode createResponse(Workflow workflow) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WorkflowArn", workflow.arn);
        response.put("CreatedAt", workflow.createdAt);
        response.put("RevisionId", workflow.revisionId);
        response.put("WorkflowStatus", workflow.status);
        response.put("WorkflowVersion", workflow.version);
        response.put("IsLatestVersion", true);
        return response;
    }

    private ObjectNode getResponse(Workflow workflow) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("WorkflowArn", workflow.arn);
        response.put("WorkflowVersion", workflow.version);
        response.put("Name", workflow.name);
        if (workflow.description != null) {
            response.put("Description", workflow.description);
        }
        response.put("CreatedAt", workflow.createdAt);
        response.put("ModifiedAt", workflow.modifiedAt);
        setIfPresent(response, "EncryptionConfiguration", workflow.encryptionConfiguration);
        setIfPresent(response, "LoggingConfiguration", workflow.loggingConfiguration);
        if (workflow.engineVersion != null) {
            response.put("EngineVersion", workflow.engineVersion);
        }
        response.put("WorkflowStatus", workflow.status);
        setIfPresent(response, "DefinitionS3Location", workflow.definitionS3Location);
        if (workflow.roleArn != null) {
            response.put("RoleArn", workflow.roleArn);
        }
        setIfPresent(response, "NetworkConfiguration", workflow.networkConfiguration);
        if (workflow.triggerMode != null) {
            response.put("TriggerMode", workflow.triggerMode);
        }
        return response;
    }

    private ObjectNode startResponse(Run run) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RunId", run.runId);
        response.put("Status", run.status);
        if (run.startedAt != null) {
            response.put("StartedAt", run.startedAt);
        }
        return response;
    }

    private ObjectNode getRunNode(Run run) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("WorkflowArn", run.workflowArn);
        node.put("WorkflowVersion", run.workflowVersion);
        node.put("RunId", run.runId);
        node.put("RunType", run.runType);
        setIfPresent(node, "OverrideParameters", run.overrideParameters);
        ObjectNode detail = node.putObject("RunDetail");
        detail.put("WorkflowArn", run.workflowArn);
        detail.put("WorkflowVersion", run.workflowVersion);
        detail.put("RunId", run.runId);
        detail.put("RunType", run.runType);
        if (run.startedAt != null) {
            detail.put("StartedOn", run.startedAt);
        }
        detail.put("CreatedAt", run.createdAt);
        if (run.completedOn != null) {
            detail.put("CompletedOn", run.completedOn);
        }
        if (run.modifiedAt != null) {
            detail.put("ModifiedAt", run.modifiedAt);
        }
        ArrayNode tasks = detail.putArray("TaskInstances");
        for (TaskInstance task : run.tasks) {
            tasks.add(task.taskInstanceId);
        }
        detail.put("RunState", run.status);
        return node;
    }

    private ObjectNode summary(Workflow workflow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("WorkflowArn", workflow.arn);
        node.put("WorkflowVersion", workflow.version);
        node.put("Name", workflow.name);
        if (workflow.description != null) {
            node.put("Description", workflow.description);
        }
        node.put("CreatedAt", workflow.createdAt);
        node.put("ModifiedAt", workflow.modifiedAt);
        node.put("WorkflowStatus", workflow.status);
        if (workflow.triggerMode != null) {
            node.put("TriggerMode", workflow.triggerMode);
        }
        return node;
    }

    private Workflow requireWorkflow(JsonNode request, boolean validateArn) {
        String arn = requireText(request, "WorkflowArn");
        if (validateArn && !WORKFLOW_ARN.matcher(arn).matches()) {
            throw invalid("WorkflowArn '" + arn + "' is not a valid MWAA Serverless workflow ARN.");
        }
        return requireWorkflowArn(arn);
    }

    private Workflow requireWorkflowArn(String arn) {
        if (!WORKFLOW_ARN.matcher(arn).matches()) {
            throw invalid("ResourceArn '" + arn + "' is not a valid MWAA Serverless workflow ARN.");
        }
        Workflow workflow = workflows.get(arn);
        if (workflow == null) {
            throw notFound(arn);
        }
        return workflow;
    }

    private Run requireRun(JsonNode request) {
        Workflow workflow = requireWorkflow(request, true);
        String runId = requireText(request, "RunId");
        Run run = workflow.runs.get(runId);
        if (run == null) {
            throw notFound(runId, "WorkflowRun");
        }
        return run;
    }

    private Workflow findByName(String name) {
        for (Workflow workflow : workflows.values()) {
            if (name.equals(workflow.name)) {
                return workflow;
            }
        }
        return null;
    }

    private void ensureLogGroup(String logGroupName, String region) {
        if (logsService == null || !logsService.isResolvable()) {
            return;
        }
        try {
            logsService.get().createLogGroup(logGroupName, null, null, region);
        } catch (AwsException e) {
            LOG.debugf("Skipping auto-created log group %s: %s", logGroupName, e.getMessage());
        }
    }

    private JsonNode encryptionOrDefault(JsonNode node) {
        JsonNode copied = copy(node);
        if (copied != null) {
            return copied;
        }
        ObjectNode config = objectMapper.createObjectNode();
        config.put("Type", "AWS_MANAGED_KEY");
        return config;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            node.set(field, value);
        }
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private JsonNode requireObject(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            throw invalid(field + " is required.");
        }
        if (!node.hasNonNull("Bucket") || !node.hasNonNull("ObjectKey")) {
            throw invalid(field + " must include Bucket and ObjectKey.");
        }
        return node;
    }

    private static Map<String, String> readTagMap(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return tags;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                JsonNode value = node.get(key);
                if (value != null && !value.isNull()) {
                    tags.put(key, value.asText(""));
                }
            }
            return tags;
        }
        if (node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull() || !node.get(field).isNumber()) {
            return null;
        }
        return node.get(field).asInt();
    }

    private static String newSuffix() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return suffix.toString();
    }

    private static String newRevision() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400,
                Map.of("Reason", "fieldValidationFailed"));
    }

    private static AwsException notFound(String arn) {
        return notFound(arn, "Workflow");
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " not found.",
                404,
                Map.of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    private static AwsException conflict(String arn, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("ResourceId", arn, "ResourceType", "Workflow"));
    }
}
