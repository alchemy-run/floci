package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appflow.model.ConnectorProfile;
import io.github.hectorvent.floci.services.appflow.model.Flow;
import io.github.hectorvent.floci.services.appflow.model.FlowExecution;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon AppFlow restJson1 — connector profiles, flow lifecycle, and S3-to-S3 execution.
 *
 * <p>Live AppFlow validates the vendor connection at
 * {@code CreateConnectorProfile}/{@code UpdateConnectorProfile}. Floci does not
 * speak Salesforce/Snowflake/etc., so any profile whose properties include an
 * {@code instanceUrl} fails with {@code ConnectorServerException} — the same
 * typed error AWS returns for an unreachable connector host.
 *
 * <p>S3-to-S3 flows validate the source prefix at create/update by listing the
 * bucket. {@code StartFlow} copies objects into the destination prefix and
 * records an execution. {@code StopFlow} on an OnDemand flow raises
 * {@code UnsupportedOperationException}. Tag APIs share {@code /tags/{arn}}
 * via {@link TagHandler} using ARN service {@code appflow}.
 */
@ApplicationScoped
public class AppFlowService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AppFlowService.class);

    static final String SERVICE = "appflow";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "appflow:v1:";
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w/!@#+=.-]+");
    private static final Pattern FLOW_NAME_PATTERN = Pattern.compile("[\\w!@#.-]+");
    private static final Set<String> CONNECTION_MODES = Set.of("Public", "Private");
    private static final Set<String> TRIGGER_TYPES = Set.of("OnDemand", "Scheduled", "Event");
    private static final Set<String> IN_PROGRESS = Set.of("InProgress", "CancelStarted");

    private final StorageBackend<String, ConnectorProfile> store;
    private final StorageBackend<String, Flow> flowStore;
    private final StorageBackend<String, FlowExecution> executionStore;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public AppFlowService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this(storageFactory.create("appflow", "appflow-connector-profiles.json",
                        new TypeReference<Map<String, ConnectorProfile>>() {
                        }),
                storageFactory.create("appflow", "appflow-flows.json",
                        new TypeReference<Map<String, Flow>>() {
                        }),
                storageFactory.create("appflow", "appflow-executions.json",
                        new TypeReference<Map<String, FlowExecution>>() {
                        }),
                regionResolver, s3Service, objectMapper);
    }

    AppFlowService(StorageBackend<String, ConnectorProfile> store, RegionResolver regionResolver) {
        this(store, null, null, regionResolver, null, new ObjectMapper());
    }

    AppFlowService(
            StorageBackend<String, ConnectorProfile> store,
            StorageBackend<String, Flow> flowStore,
            StorageBackend<String, FlowExecution> executionStore,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this.store = store;
        this.flowStore = flowStore;
        this.executionStore = executionStore;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    public synchronized ConnectorProfile createConnectorProfile(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "connectorProfileName");
        validateName(name);
        String connectorType = requireText(request, "connectorType");
        String connectionMode = requireConnectionMode(request);
        JsonNode config = requireObjectField(request, "connectorProfileConfig");
        JsonNode properties = requireObjectField(config, "connectorProfileProperties");
        validateVendorConnection(properties);

        String key = storageKey(region, name);
        if (store.get(key).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "Connector profile " + name + " already exists.",
                    409);
        }

        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        ConnectorProfile profile = new ConnectorProfile();
        profile.setConnectorProfileName(name);
        profile.setConnectorProfileArn(arn(region, account, "connectorprofile/" + name));
        profile.setConnectorType(connectorType);
        profile.setConnectorLabel(optionalText(request, "connectorLabel"));
        profile.setConnectionMode(connectionMode);
        profile.setKmsArn(optionalText(request, "kmsArn"));
        profile.setCredentialsArn(
                "arn:aws:secretsmanager:" + region + ":" + account + ":secret:appflow/" + name);
        profile.setConnectorProfileProperties(properties.deepCopy());
        profile.setCreatedAt(now);
        profile.setLastUpdatedAt(now);
        store.put(key, profile);
        return profile;
    }

    public Page describeConnectorProfiles(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        String type = optionalText(request, "connectorType");
        String label = optionalText(request, "connectorLabel");
        List<String> names = readNameFilter(request);

        List<ConnectorProfile> profiles = store.scan(key -> key.startsWith(region + "::"));
        if (names != null) {
            Map<String, ConnectorProfile> byName = new LinkedHashMap<>();
            for (ConnectorProfile profile : profiles) {
                byName.put(profile.getConnectorProfileName(), profile);
            }
            List<ConnectorProfile> filtered = new ArrayList<>();
            for (String name : names) {
                ConnectorProfile match = byName.get(name);
                if (match != null) {
                    filtered.add(match);
                }
            }
            profiles = filtered;
        } else {
            profiles.sort(Comparator.comparing(
                    ConnectorProfile::getConnectorProfileName, Comparator.nullsLast(String::compareTo)));
        }
        if (type != null) {
            profiles = profiles.stream().filter(p -> type.equals(p.getConnectorType())).toList();
        }
        if (label != null) {
            profiles = profiles.stream().filter(p -> label.equals(p.getConnectorLabel())).toList();
        }

        int offset = decodeOffset(optionalText(request, "nextToken"), profiles.size());
        int end = Math.min(offset + maxResults, profiles.size());
        String responseToken = end < profiles.size() ? encodeOffset(end) : null;
        return new Page(profiles.subList(offset, end), responseToken);
    }

    public synchronized ConnectorProfile updateConnectorProfile(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "connectorProfileName");
        validateName(name);
        String connectionMode = requireConnectionMode(request);
        JsonNode config = requireObjectField(request, "connectorProfileConfig");
        JsonNode properties = requireObjectField(config, "connectorProfileProperties");
        validateVendorConnection(properties);

        String key = storageKey(region, name);
        ConnectorProfile profile = store.get(key).orElseThrow(() -> notFound(name));
        profile.setConnectionMode(connectionMode);
        profile.setConnectorProfileProperties(properties.deepCopy());
        profile.setLastUpdatedAt(Instant.now().getEpochSecond());
        store.put(key, profile);
        return profile;
    }

    public synchronized void deleteConnectorProfile(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "connectorProfileName");
        validateName(name);
        String key = storageKey(region, name);
        if (store.get(key).isEmpty()) {
            throw notFound(name);
        }
        store.delete(key);
    }

    public synchronized Flow createFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "flowName");
        validateFlowName(name);
        String key = storageKey(region, name);
        if (flowStore.get(key).isPresent()) {
            throw new AwsException("ConflictException", "Flow " + name + " already exists.", 409);
        }

        JsonNode trigger = requireObjectField(request, "triggerConfig");
        validateTriggerType(requireText(trigger, "triggerType"));
        JsonNode source = requireObjectField(request, "sourceFlowConfig");
        JsonNode destinations = requireArrayField(request, "destinationFlowConfigList");
        JsonNode tasks = requireArrayField(request, "tasks");
        validateS3Source(source);

        long now = Instant.now().getEpochSecond();
        String accountId = regionResolver.getAccountId();
        Flow flow = new Flow();
        flow.setFlowName(name);
        flow.setFlowArn(arn(region, accountId, "flow/" + name));
        flow.setDescription(optionalText(request, "description"));
        flow.setKmsArn(optionalText(request, "kmsArn"));
        flow.setFlowStatus("Active");
        flow.setTriggerConfig(trigger.deepCopy());
        flow.setSourceFlowConfig(source.deepCopy());
        flow.setDestinationFlowConfigList(destinations.deepCopy());
        flow.setTasks(tasks.deepCopy());
        if (request.has("metadataCatalogConfig") && request.get("metadataCatalogConfig").isObject()) {
            flow.setMetadataCatalogConfig(request.get("metadataCatalogConfig").deepCopy());
        }
        flow.setTags(readTags(request.get("tags")));
        flow.setCreatedAt(now);
        flow.setLastUpdatedAt(now);
        flow.setCreatedBy("arn:aws:iam::" + accountId + ":root");
        flow.setLastUpdatedBy(flow.getCreatedBy());
        flow.setSchemaVersion(1);
        flowStore.put(key, flow);
        LOG.infov("Created AppFlow flow {0} ({1})", name, flow.getFlowArn());
        return flow;
    }

    public Flow describeFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireFlow(region, requireText(request, "flowName"));
    }

    public synchronized Flow updateFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "flowName");
        Flow existing = requireFlow(region, name);

        JsonNode trigger = requireObjectField(request, "triggerConfig");
        validateTriggerType(requireText(trigger, "triggerType"));
        JsonNode source = requireObjectField(request, "sourceFlowConfig");
        JsonNode destinations = requireArrayField(request, "destinationFlowConfigList");
        JsonNode tasks = requireArrayField(request, "tasks");
        validateS3Source(source);

        existing.setDescription(optionalText(request, "description"));
        existing.setTriggerConfig(trigger.deepCopy());
        existing.setSourceFlowConfig(source.deepCopy());
        existing.setDestinationFlowConfigList(destinations.deepCopy());
        existing.setTasks(tasks.deepCopy());
        if (request.has("metadataCatalogConfig") && request.get("metadataCatalogConfig").isObject()) {
            existing.setMetadataCatalogConfig(request.get("metadataCatalogConfig").deepCopy());
        }
        existing.setLastUpdatedAt(Instant.now().getEpochSecond());
        existing.setLastUpdatedBy("arn:aws:iam::" + regionResolver.getAccountId() + ":root");
        existing.setSchemaVersion(existing.getSchemaVersion() + 1);
        flowStore.put(storageKey(region, name), existing);
        return existing;
    }

    public synchronized void deleteFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "flowName");
        String key = storageKey(region, name);
        if (flowStore.get(key).isEmpty()) {
            throw flowNotFound(name);
        }
        flowStore.delete(key);
        String execPrefix = executionPrefix(region, name);
        for (String execKey : new ArrayList<>(executionStore.keys())) {
            if (execKey.startsWith(execPrefix)) {
                executionStore.delete(execKey);
            }
        }
        LOG.infov("Deleted AppFlow flow {0}", name);
    }

    public FlowPage listFlows(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        List<Flow> flows = new ArrayList<>(flowStore.scan(key -> key.startsWith(region + "::")));
        flows.sort(Comparator.comparing(Flow::getFlowName, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), flows.size());
        int end = Math.min(offset + maxResults, flows.size());
        String next = end < flows.size() ? encodeOffset(end) : null;
        return new FlowPage(flows.subList(offset, end), next);
    }

    public synchronized StartResult startFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, requireText(request, "flowName"));
        String triggerType = triggerTypeOf(flow);
        if ("Scheduled".equals(triggerType) || "Event".equals(triggerType)) {
            flow.setFlowStatus("Active");
            flow.setLastUpdatedAt(Instant.now().getEpochSecond());
            flowStore.put(storageKey(region, flow.getFlowName()), flow);
        }

        String executionId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        FlowExecution execution = new FlowExecution();
        execution.setFlowName(flow.getFlowName());
        execution.setExecutionId(executionId);
        execution.setExecutionStatus("InProgress");
        execution.setStartedAt(now);
        execution.setLastUpdatedAt(now);
        runS3ToS3(flow, execution);
        execution.setLastUpdatedAt(Instant.now().getEpochSecond());
        executionStore.put(executionKey(region, flow.getFlowName(), executionId), execution);

        ObjectNode details = objectMapper.createObjectNode();
        details.put("mostRecentExecutionMessage",
                execution.getExecutionMessage() == null ? "" : execution.getExecutionMessage());
        details.put("mostRecentExecutionTime", execution.getLastUpdatedAt());
        details.put("mostRecentExecutionStatus", execution.getExecutionStatus());
        flow.setLastRunExecutionDetails(details);
        flow.setLastUpdatedAt(Instant.now().getEpochSecond());
        flowStore.put(storageKey(region, flow.getFlowName()), flow);
        return new StartResult(flow, executionId);
    }

    public synchronized Flow stopFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, requireText(request, "flowName"));
        if ("OnDemand".equals(triggerTypeOf(flow))) {
            throw new AwsException(
                    "UnsupportedOperationException",
                    "StopFlow is not supported for OnDemand flows.",
                    400);
        }
        flow.setFlowStatus("Draft");
        flow.setLastUpdatedAt(Instant.now().getEpochSecond());
        flowStore.put(storageKey(region, flow.getFlowName()), flow);
        return flow;
    }

    public synchronized CancelResult cancelFlowExecutions(String region, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, requireText(request, "flowName"));
        List<String> requested = readStringArray(request.get("executionIds"));
        List<FlowExecution> candidates = executionsFor(region, flow.getFlowName());
        List<String> invalid = new ArrayList<>();
        if (requested.isEmpty()) {
            for (FlowExecution execution : candidates) {
                if (!IN_PROGRESS.contains(execution.getExecutionStatus())) {
                    invalid.add(execution.getExecutionId());
                    continue;
                }
                cancel(region, execution);
            }
        } else {
            Map<String, FlowExecution> byId = new LinkedHashMap<>();
            for (FlowExecution execution : candidates) {
                byId.put(execution.getExecutionId(), execution);
            }
            for (String id : requested) {
                FlowExecution execution = byId.get(id);
                if (execution == null || !IN_PROGRESS.contains(execution.getExecutionStatus())) {
                    invalid.add(id);
                    continue;
                }
                cancel(region, execution);
            }
        }
        return new CancelResult(invalid);
    }

    public ExecutionPage describeFlowExecutionRecords(String region, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, requireText(request, "flowName"));
        int maxResults = parseMaxResults(request);
        List<FlowExecution> executions = executionsFor(region, flow.getFlowName());
        executions.sort(Comparator.comparingLong(FlowExecution::getStartedAt).reversed());
        int offset = decodeOffset(optionalText(request, "nextToken"), executions.size());
        int end = Math.min(offset + maxResults, executions.size());
        String next = end < executions.size() ? encodeOffset(end) : null;
        return new ExecutionPage(executions.subList(offset, end), next);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Flow flow = requireFlowByArn(region, arn);
        return flow.getTags() == null ? Map.of() : Map.copyOf(flow.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Flow flow = requireFlowByArn(region, arn);
        Map<String, String> current = flow.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(flow.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        flow.setTags(current);
        flow.setLastUpdatedAt(Instant.now().getEpochSecond());
        flowStore.put(storageKey(region, flow.getFlowName()), flow);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Flow flow = requireFlowByArn(region, arn);
        if (flow.getTags() != null && tagKeys != null) {
            tagKeys.forEach(flow.getTags()::remove);
        }
        flow.setLastUpdatedAt(Instant.now().getEpochSecond());
        flowStore.put(storageKey(region, flow.getFlowName()), flow);
    }

    /**
     * AppFlow talks to the vendor at create/update. Connectors that carry an
     * {@code instanceUrl} (Salesforce, ServiceNow, Slack, …) cannot be reached
     * from the emulator, matching AWS's {@code ConnectorServerException} for an
     * unreachable host.
     */
    static void validateVendorConnection(JsonNode properties) {
        String instanceUrl = findInstanceUrl(properties);
        if (instanceUrl != null) {
            throw new AwsException(
                    "ConnectorServerException",
                    "Failed to connect to the connector.",
                    400);
        }
    }

    static String findInstanceUrl(JsonNode properties) {
        if (properties == null || !properties.isObject()) {
            return null;
        }
        var fields = properties.fields();
        while (fields.hasNext()) {
            JsonNode node = fields.next().getValue();
            if (node != null && node.hasNonNull("instanceUrl") && node.get("instanceUrl").isTextual()) {
                String value = node.get("instanceUrl").textValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private void cancel(String region, FlowExecution execution) {
        execution.setExecutionStatus("Canceled");
        execution.setLastUpdatedAt(Instant.now().getEpochSecond());
        executionStore.put(executionKey(region, execution.getFlowName(), execution.getExecutionId()), execution);
    }

    private void runS3ToS3(Flow flow, FlowExecution execution) {
        JsonNode source = flow.getSourceFlowConfig();
        if (source == null || !"S3".equals(source.path("connectorType").asText()) || s3Service == null) {
            execution.setExecutionStatus("Successful");
            execution.setExecutionMessage("Execution Successful");
            return;
        }
        JsonNode srcS3 = source.path("sourceConnectorProperties").path("S3");
        String srcBucket = textOrNull(srcS3, "bucketName");
        String srcPrefix = srcS3.path("bucketPrefix").asText("");
        JsonNode destinations = flow.getDestinationFlowConfigList();
        String dstBucket = srcBucket;
        String dstPrefix = "output";
        if (destinations != null && destinations.isArray() && destinations.size() > 0) {
            JsonNode first = destinations.get(0);
            JsonNode dstS3 = first.path("destinationConnectorProperties").path("S3");
            if (dstS3.isObject()) {
                dstBucket = textOrNull(dstS3, "bucketName");
                dstPrefix = dstS3.path("bucketPrefix").asText("");
            }
        }
        try {
            List<S3Object> objects = s3Service.listObjects(srcBucket, srcPrefix, null, 1000);
            long bytes = 0;
            long records = 0;
            for (S3Object object : objects) {
                String destKey = destinationKey(srcPrefix, dstPrefix, object.getKey());
                s3Service.copyObject(srcBucket, object.getKey(), dstBucket, destKey);
                bytes += object.getSize();
                records += 1;
            }
            execution.setBytesProcessed(bytes);
            execution.setBytesWritten(bytes);
            execution.setRecordsProcessed(records);
            execution.setExecutionStatus("Successful");
            execution.setExecutionMessage("Execution Successful");
        } catch (AwsException e) {
            LOG.warnv(e, "AppFlow execution {0} failed copying S3 objects", execution.getExecutionId());
            execution.setExecutionStatus("Error");
            execution.setExecutionMessage(e.getMessage());
        }
    }

    private static String destinationKey(String srcPrefix, String dstPrefix, String key) {
        String relative = key;
        if (srcPrefix != null && !srcPrefix.isEmpty() && key.startsWith(srcPrefix)) {
            relative = key.substring(srcPrefix.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
        }
        if (dstPrefix == null || dstPrefix.isEmpty()) {
            return relative;
        }
        if (dstPrefix.endsWith("/")) {
            return dstPrefix + relative;
        }
        return dstPrefix + "/" + relative;
    }

    private void validateS3Source(JsonNode source) {
        String connectorType = optionalText(source, "connectorType");
        if (!"S3".equals(connectorType) || s3Service == null) {
            return;
        }
        JsonNode s3 = source.path("sourceConnectorProperties").path("S3");
        if (!s3.isObject()) {
            throw validation("sourceConnectorProperties.S3 is required for an S3 source.");
        }
        String bucket = textOrNull(s3, "bucketName");
        if (bucket == null || bucket.isEmpty()) {
            throw validation("sourceConnectorProperties.S3.bucketName is required.");
        }
        String prefix = s3.path("bucketPrefix").asText("");
        try {
            List<S3Object> objects = s3Service.listObjects(bucket, prefix, null, 1);
            if (objects.isEmpty()) {
                throw connectorServer(
                        "Failed to list objects in the S3 source. The source prefix must contain at least one object.");
            }
        } catch (AwsException e) {
            if ("ConnectorServerException".equals(e.getErrorCode())
                    || "ValidationException".equals(e.getErrorCode())) {
                throw e;
            }
            throw connectorServer("Failed to list objects in the S3 source: " + e.getMessage());
        }
    }

    private Flow requireFlow(String region, String name) {
        validateFlowName(name);
        return flowStore.get(storageKey(region, name)).orElseThrow(() -> flowNotFound(name));
    }

    private Flow requireFlowByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource == null || !resource.startsWith("flow/")) {
            throw flowNotFound(arn);
        }
        String name = resource.substring("flow/".length());
        String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
        return requireFlow(lookupRegion, name);
    }

    private List<FlowExecution> executionsFor(String region, String flowName) {
        String prefix = executionPrefix(region, flowName);
        return new ArrayList<>(executionStore.scan(key -> key.startsWith(prefix)));
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String executionKey(String region, String flowName, String executionId) {
        return executionPrefix(region, flowName) + executionId;
    }

    private static String executionPrefix(String region, String flowName) {
        return region + "::" + flowName + "::";
    }

    private static String arn(String region, String account, String resource) {
        return "arn:aws:appflow:" + region + ":" + account + ":" + resource;
    }

    private static String triggerTypeOf(Flow flow) {
        if (flow.getTriggerConfig() == null) {
            return "OnDemand";
        }
        String type = flow.getTriggerConfig().path("triggerType").asText(null);
        return type == null || type.isEmpty() ? "OnDemand" : type;
    }

    private static void validateName(String name) {
        if (name.length() < 1 || name.length() > 256 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("connectorProfileName must match [\\w/!@#+=.-]+ and contain at most 256 characters.");
        }
    }

    private static void validateFlowName(String name) {
        if (name == null || name.isEmpty() || name.length() > 256 || !FLOW_NAME_PATTERN.matcher(name).matches()) {
            throw validation("flowName must match [\\w!@#.-]+ and contain at most 256 characters.");
        }
    }

    private static void validateTriggerType(String triggerType) {
        if (!TRIGGER_TYPES.contains(triggerType)) {
            throw validation("triggerType must be OnDemand, Scheduled, or Event.");
        }
    }

    private static String requireConnectionMode(JsonNode request) {
        String mode = requireText(request, "connectionMode");
        if (!CONNECTION_MODES.contains(mode)) {
            throw validation("connectionMode must be Public or Private.");
        }
        return mode;
    }

    private static List<String> readNameFilter(JsonNode request) {
        if (!request.has("connectorProfileNames") || request.get("connectorProfileNames").isNull()) {
            return null;
        }
        JsonNode array = request.get("connectorProfileNames");
        if (!array.isArray()) {
            throw validation("connectorProfileNames must be an array.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation("connectorProfileNames members must be strings.");
            }
            names.add(value.textValue());
        }
        return List.copyOf(names);
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return values;
        }
        for (JsonNode entry : node) {
            if (entry != null && entry.isTextual()) {
                values.add(entry.asText());
            }
        }
        return values;
    }

    private static int parseMaxResults(JsonNode request) {
        if (!request.has("maxResults") || request.get("maxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("maxResults");
        if (!value.isNumber()) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
        int parsed = value.intValue();
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and 100.");
        }
        return parsed;
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
        return value;
    }

    private static JsonNode requireArrayField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        return parent.get(field).asText();
    }

    private static AwsException notFound(String name) {
        return new AwsException(
                "ResourceNotFoundException",
                "Connector profile " + name + " does not exist.",
                404);
    }

    private static AwsException flowNotFound(String name) {
        return new AwsException("ResourceNotFoundException", "Flow " + name + " not found.", 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException connectorServer(String message) {
        return new AwsException("ConnectorServerException", message, 400);
    }

    public record Page(List<ConnectorProfile> profiles, String nextToken) {
        public Page {
            profiles = List.copyOf(profiles);
        }
    }

    public record FlowPage(List<Flow> items, String nextToken) {
        public FlowPage {
            items = List.copyOf(items);
        }
    }

    public record ExecutionPage(List<FlowExecution> items, String nextToken) {
        public ExecutionPage {
            items = List.copyOf(items);
        }
    }

    public record StartResult(Flow flow, String executionId) {
    }

    public record CancelResult(List<String> invalidExecutions) {
        public CancelResult {
            invalidExecutions = List.copyOf(invalidExecutions);
        }
    }
}
