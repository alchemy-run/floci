package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.AnomalyDetector;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import io.github.hectorvent.floci.services.aps.model.RuleGroupsNamespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@ApplicationScoped
public class ApsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(ApsService.class);
    // AMP's ListWorkspacesRequest declares maxResults with a default of 100 and a maximum of 1000.
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final int MAX_NAMESPACE_NAME_LENGTH = 128;
    private static final Pattern NAMESPACE_NAME = Pattern.compile(".*[0-9A-Za-z][-.0-9A-Z_a-z]*.*");

    private final StorageBackend<String, PrometheusWorkspace> storage;
    private final StorageBackend<String, RuleGroupsNamespace> namespaceStorage;
    private final StorageBackend<String, ObjectNode> scraperStorage;
    private final RegionResolver regionResolver;
    private final ApsPrometheusBackend backend;
    private final URI endpointBase;
    private final Object[] workspaceLocks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    @Inject
    public ApsService(StorageFactory storageFactory, RegionResolver regionResolver,
                      EmulatorConfig config, ApsPrometheusBackend backend) {
        this.storage = storageFactory.create("aps", "aps-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        this.namespaceStorage = storageFactory.create("aps", "aps-rule-groups-namespaces.json",
                new TypeReference<Map<String, RuleGroupsNamespace>>() {});
        this.scraperStorage = storageFactory.create("aps", "aps-scrapers.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.backend = backend;
        this.endpointBase = URI.create(config.effectiveBaseUrl());
    }

    public PrometheusWorkspace createWorkspace(String region, String alias, Map<String, String> tags,
                                               String kmsKeyArn) {
        String workspaceId = "ws-" + UUID.randomUUID();
        String arn = regionResolver.buildArn("aps", region, "workspace/" + workspaceId);

        PrometheusWorkspace workspace = new PrometheusWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setAlias(stripAlias(alias));
        workspace.setArn(arn);
        // The data-plane backend starts lazily on its first authenticated request.
        workspace.setStatus("ACTIVE");
        workspace.setPrometheusEndpoint(prometheusEndpoint(workspaceId));
        workspace.setCreatedAt(Instant.now());
        workspace.setKmsKeyArn(kmsKeyArn);
        if (tags != null) {
            workspace.getTags().putAll(tags);
        }

        storage.put(key(region, workspaceId), workspace);
        LOG.infov("Created AMP workspace: {0} in {1}", workspaceId, region);
        return workspace;
    }

    public PrometheusWorkspace describeWorkspace(String region, String workspaceId) {
        PrometheusWorkspace workspace = storage.get(key(region, workspaceId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Workspace not found: " + workspaceId, 404));
        workspace.setPrometheusEndpoint(prometheusEndpoint(workspaceId));
        return workspace;
    }

    // The alias parameter is a prefix filter, not an exact match: the terraform provider's
    // aws_prometheus_workspaces data source exposes it as alias_prefix.
    public PaginatedResult<PrometheusWorkspace> listWorkspaces(String region, String aliasPrefix,
                                                               Integer maxResults, String nextToken) {
        String prefix = stripAlias(aliasPrefix);
        String regionPrefix = keyPrefix(region);
        List<PrometheusWorkspace> all = storage.scan(k -> k.startsWith(regionPrefix)).stream()
                .filter(w -> prefix == null || prefix.isEmpty()
                        || (w.getAlias() != null && w.getAlias().startsWith(prefix)))
                .toList();
        return Pagination.paginate(all, PrometheusWorkspace::getWorkspaceId, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
    }

    public void deleteWorkspace(String region, String workspaceId) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            if (workspace.isPrometheusRuntimeOwned()) {
                backend.remove(workspace.getArn());
            }
            String namespacePrefix = namespaceKeyPrefix(region, workspaceId);
            for (RuleGroupsNamespace namespace : namespaceStorage.scan(k -> k.startsWith(namespacePrefix))) {
                namespaceStorage.delete(namespaceKey(region, workspaceId, namespace.getName()));
            }
            storage.delete(key(region, workspaceId));
            LOG.infov("Deleted AMP workspace: {0} in {1}", workspaceId, region);
        }
    }

    public ApsPrometheusBackend.BackendResponse forward(String region, String workspaceId, String method,
                                                        String path, String rawQuery,
                                                        Map<String, String> headers, byte[] body) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            if (!workspace.isPrometheusRuntimeOwned()) {
                // Persist cleanup ownership before Docker creates anything.
                workspace.setPrometheusRuntimeOwned(true);
                storage.put(key(region, workspaceId), workspace);
            }
            return backend.forward(workspace.getArn(), method, path, rawQuery, headers, body);
        }
    }

    private Object workspaceLock(String region, String workspaceId) {
        String identity = regionResolver.getAccountId() + ":" + key(region, workspaceId);
        return workspaceLocks[Math.floorMod(identity.hashCode(), workspaceLocks.length)];
    }

    private String prometheusEndpoint(String workspaceId) {
        String port = endpointBase.getPort() < 0 ? "" : ":" + endpointBase.getPort();
        return endpointBase.getScheme() + "://aps-workspaces-" + workspaceId + ".localhost.floci.io" + port
                + "/workspaces/" + workspaceId + "/";
    }

    public void updateWorkspaceAlias(String region, String workspaceId, String alias) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            workspace.setAlias(stripAlias(alias));
            storage.put(key(region, workspaceId), workspace);
        }
    }

    public RuleGroupsNamespace createRuleGroupsNamespace(String region, String workspaceId, String name,
                                                         String encodedData, Map<String, String> tags) {
        synchronized (workspaceLock(region, workspaceId)) {
            describeWorkspace(region, workspaceId);
            requireNamespaceName(name);
            requireNamespaceData(encodedData);
            if (namespaceStorage.get(namespaceKey(region, workspaceId, name)).isPresent()) {
                throw new AwsException("ConflictException",
                        "Rule groups namespace already exists: " + name, 409);
            }

            RuleGroupsNamespace namespace = new RuleGroupsNamespace();
            namespace.setName(name);
            namespace.setWorkspaceId(workspaceId);
            namespace.setArn(regionResolver.buildArn("aps", region,
                    "rulegroupsnamespace/" + workspaceId + "/" + name));
            namespace.setStatus("ACTIVE");
            namespace.setEncodedData(encodedData);
            Instant now = Instant.now();
            namespace.setCreatedAt(now);
            namespace.setModifiedAt(now);
            if (tags != null) {
                namespace.getTags().putAll(tags);
            }

            namespaceStorage.put(namespaceKey(region, workspaceId, name), namespace);
            LOG.infov("Created AMP rule groups namespace: {0} in workspace {1}", name, workspaceId);
            return namespace;
        }
    }

    public RuleGroupsNamespace describeRuleGroupsNamespace(String region, String workspaceId, String name) {
        describeWorkspace(region, workspaceId);
        return namespaceStorage.get(namespaceKey(region, workspaceId, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule groups namespace not found: " + name, 404));
    }

    public PaginatedResult<RuleGroupsNamespace> listRuleGroupsNamespaces(String region, String workspaceId,
                                                                         String namePrefix, Integer maxResults,
                                                                         String nextToken) {
        describeWorkspace(region, workspaceId);
        String prefix = namespaceKeyPrefix(region, workspaceId);
        List<RuleGroupsNamespace> all = namespaceStorage.scan(k -> k.startsWith(prefix)).stream()
                .filter(n -> namePrefix == null || namePrefix.isEmpty()
                        || (n.getName() != null && n.getName().startsWith(namePrefix)))
                .toList();
        return Pagination.paginate(all, RuleGroupsNamespace::getName, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
    }

    public RuleGroupsNamespace putRuleGroupsNamespace(String region, String workspaceId, String name,
                                                      String encodedData) {
        synchronized (workspaceLock(region, workspaceId)) {
            requireNamespaceData(encodedData);
            RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, workspaceId, name);
            namespace.setEncodedData(encodedData);
            namespace.setModifiedAt(Instant.now());
            namespaceStorage.put(namespaceKey(region, workspaceId, name), namespace);
            return namespace;
        }
    }

    public void deleteRuleGroupsNamespace(String region, String workspaceId, String name) {
        synchronized (workspaceLock(region, workspaceId)) {
            describeRuleGroupsNamespace(region, workspaceId, name);
            namespaceStorage.delete(namespaceKey(region, workspaceId, name));
            LOG.infov("Deleted AMP rule groups namespace: {0} in workspace {1}", name, workspaceId);
        }
    }

    public ObjectNode describeScraper(String region, String scraperId) {
        if (scraperId == null || !scraperId.matches("s-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw ApsConfigurationValidator.invalid("scraperId must identify an AMP scraper");
        }
        return scraperStorage.get(key(region, scraperId)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Scraper", scraperId));
    }

    public enum ConfigurationKind {
        ALERT_MANAGER("alertManagerDefinition", "Alertmanager definition stored; alert delivery is not implemented locally."),
        LOGGING("loggingConfiguration", "Logging configuration stored; rule and alert logs are not delivered locally."),
        QUERY_LOGGING("queryLoggingConfiguration", "Query logging configuration stored; query logs are not delivered locally."),
        WORKSPACE("workspaceConfiguration", "Workspace settings stored; local Prometheus runtime limits are unchanged."),
        POLICY("policy", "Resource policies are stored but do not modify local data-plane authorization.");

        private final String responseKey;
        private final String limitation;

        ConfigurationKind(String responseKey, String limitation) {
            this.responseKey = responseKey;
            this.limitation = limitation;
        }

        public String responseKey() { return responseKey; }
    }

    public ObjectNode describeConfiguration(String region, String workspaceId, ConfigurationKind kind) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            ObjectNode configuration = workspace.getConfigurations().get(kind.name());
            if (configuration == null && kind == ConfigurationKind.WORKSPACE) {
                ObjectNode defaults = JsonNodeFactory.instance.objectNode();
                defaults.put("retentionPeriodInDays", 150);
                defaults.put("outOfOrderTimeWindowInSeconds", 0);
                defaults.put("ruleQueryOffsetInSeconds", 0);
                defaults.putArray("limitsPerLabelSet");
                defaults.set("status", metadataStatus("ACTIVE", kind.limitation));
                return defaults;
            }
            if (configuration == null) {
                throw notFound(kind.responseKey, workspaceId);
            }
            return configuration.deepCopy();
        }
    }

    public ObjectNode writeConfiguration(String region, String workspaceId, ConfigurationKind kind,
                                          Map<String, Object> body, boolean create) {
        ObjectNode request = ApsConfigurationValidator.request(body);
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            ObjectNode previous = workspace.getConfigurations().get(kind.name());
            boolean upsert = kind == ConfigurationKind.WORKSPACE || kind == ConfigurationKind.POLICY;
            if (!upsert && create && previous != null) {
                throw conflict(kind.responseKey + " already exists");
            }
            if (!upsert && !create && previous == null) {
                throw notFound(kind.responseKey, workspaceId);
            }
            ObjectNode configuration = JsonNodeFactory.instance.objectNode();
            switch (kind) {
                case ALERT_MANAGER -> {
                    String data = ApsConfigurationValidator.text(request.get("data"), "data");
                    ApsConfigurationValidator.alertManager(data);
                    configuration.put("data", data);
                }
                case LOGGING -> {
                    String arn = ApsConfigurationValidator.text(request.get("logGroupArn"), "logGroupArn");
                    ApsConfigurationValidator.logGroup(arn, region, regionResolver.getAccountId());
                    configuration.put("logGroupArn", arn);
                    configuration.put("workspace", workspaceId);
                }
                case QUERY_LOGGING -> {
                    ApsConfigurationValidator.queryDestinations(request.get("destinations"), region,
                            regionResolver.getAccountId());
                    configuration.set("destinations", request.get("destinations").deepCopy());
                    configuration.put("workspace", workspaceId);
                }
                case WORKSPACE -> {
                    ApsConfigurationValidator.workspaceConfiguration(request);
                    configuration = describeConfiguration(region, workspaceId, kind);
                    copyFields(request, configuration, List.of("retentionPeriodInDays", "limitsPerLabelSet",
                            "outOfOrderTimeWindowInSeconds", "ruleQueryOffsetInSeconds"));
                }
                case POLICY -> {
                    String policy = ApsConfigurationValidator.text(request.get("policyDocument"), "policyDocument");
                    ApsConfigurationValidator.policy(policy);
                    checkRevision(previous, request.has("revisionId")
                            ? ApsConfigurationValidator.text(request.get("revisionId"), "revisionId") : null);
                    if (previous != null && policy.equals(previous.path("policyDocument").asText())) {
                        return previous.deepCopy();
                    }
                    configuration.put("policyDocument", policy);
                    configuration.put("policyStatus", "ACTIVE");
                    configuration.put("revisionId", UUID.randomUUID().toString());
                }
            }
            if (kind != ConfigurationKind.POLICY) {
                configuration.set("status", metadataStatus("ACTIVE", kind.limitation));
                if (kind != ConfigurationKind.WORKSPACE) {
                    double now = Instant.now().toEpochMilli() / 1000.0;
                    configuration.put("createdAt", previous == null ? now : previous.path("createdAt").asDouble());
                    configuration.put("modifiedAt", now);
                }
            }
            workspace.getConfigurations().put(kind.name(), configuration);
            storage.put(key(region, workspaceId), workspace);
            return configuration.deepCopy();
        }
    }

    public void deleteConfiguration(String region, String workspaceId, ConfigurationKind kind, String revisionId) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            ObjectNode previous = workspace.getConfigurations().get(kind.name());
            if (previous == null) {
                throw notFound(kind.responseKey, workspaceId);
            }
            if (kind == ConfigurationKind.POLICY) {
                checkRevision(previous, revisionId);
            }
            workspace.getConfigurations().remove(kind.name());
            storage.put(key(region, workspaceId), workspace);
        }
    }

    private static void checkRevision(ObjectNode previous, String revisionId) {
        if (revisionId != null && (previous == null || !revisionId.equals(previous.path("revisionId").asText()))) {
            throw conflict("The resource policy revision does not match the current revision");
        }
    }

    public ObjectNode createAnomalyDetector(String region, String workspaceId, Map<String, Object> body) {
        ObjectNode request = ApsConfigurationValidator.request(body);
        ApsConfigurationValidator.anomalyDetector(request, true);
        String token = request.has("clientToken")
                ? ApsConfigurationValidator.text(request.get("clientToken"), "clientToken") : null;
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            for (AnomalyDetector existing : workspace.getAnomalyDetectors().values()) {
                if (token != null && token.equals(existing.getCreationRequest().path("clientToken").asText())) {
                    if (!request.equals(existing.getCreationRequest())) {
                        throw conflict("clientToken was already used with a different detector request");
                    }
                    return detectorDescription(existing);
                }
            }
            String alias = request.get("alias").textValue();
            if (workspace.getAnomalyDetectors().values().stream()
                    .anyMatch(detector -> alias.equals(detector.getDescription().path("alias").asText()))) {
                throw conflict("An anomaly detector with this alias already exists");
            }
            String detectorId = "ad-" + UUID.randomUUID();
            AnomalyDetector detector = new AnomalyDetector();
            detector.setCreationRequest(request.deepCopy());
            ObjectNode description = JsonNodeFactory.instance.objectNode();
            description.put("anomalyDetectorId", detectorId);
            description.put("arn", regionResolver.buildArn("aps", region,
                    "anomalydetector/" + workspaceId + "/" + detectorId));
            description.put("alias", alias);
            description.put("createdAt", Instant.now().toEpochMilli() / 1000.0);
            detector.setDescription(description);
            updateDetectorMetadata(detector, request, true);
            if (request.has("tags")) {
                request.get("tags").fields().forEachRemaining(tag ->
                        detector.getTags().put(tag.getKey(), tag.getValue().textValue()));
            }
            workspace.getAnomalyDetectors().put(detectorId, detector);
            storage.put(key(region, workspaceId), workspace);
            return detectorDescription(detector);
        }
    }

    public ObjectNode describeAnomalyDetector(String region, String workspaceId, String detectorId) {
        synchronized (workspaceLock(region, workspaceId)) {
            return detectorDescription(requireDetector(describeWorkspace(region, workspaceId), detectorId));
        }
    }

    public ObjectNode putAnomalyDetector(String region, String workspaceId, String detectorId, Map<String, Object> body) {
        ObjectNode request = ApsConfigurationValidator.request(body);
        ApsConfigurationValidator.anomalyDetector(request, false);
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            AnomalyDetector detector = requireDetector(workspace, detectorId);
            updateDetectorMetadata(detector, request, false);
            storage.put(key(region, workspaceId), workspace);
            return detectorDescription(detector);
        }
    }

    public PaginatedResult<ObjectNode> listAnomalyDetectors(String region, String workspaceId, String alias,
                                                           Integer maxResults, String nextToken) {
        synchronized (workspaceLock(region, workspaceId)) {
            List<ObjectNode> summaries = describeWorkspace(region, workspaceId).getAnomalyDetectors().values().stream()
                    .filter(detector -> alias == null || detector.getDescription().path("alias").asText().startsWith(alias))
                    .map(detector -> {
                        ObjectNode summary = detectorDescription(detector);
                        summary.remove(List.of("configuration", "evaluationIntervalInSeconds", "missingDataAction", "labels"));
                        return summary;
                    }).toList();
            return Pagination.paginate(summaries, node -> node.path("anomalyDetectorId").asText(), maxResults,
                    nextToken, DEFAULT_PAGE, MAX_PAGE, "ValidationException");
        }
    }

    public void deleteAnomalyDetector(String region, String workspaceId, String detectorId) {
        synchronized (workspaceLock(region, workspaceId)) {
            PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
            workspace.getAnomalyDetectors().remove(detectorId);
            storage.put(key(region, workspaceId), workspace);
        }
    }

    private static AnomalyDetector requireDetector(PrometheusWorkspace workspace, String detectorId) {
        AnomalyDetector detector = workspace.getAnomalyDetectors().get(detectorId);
        if (detector == null) {
            throw notFound("Anomaly detector", detectorId);
        }
        return detector;
    }

    private static void updateDetectorMetadata(AnomalyDetector detector, ObjectNode request, boolean create) {
        ObjectNode description = detector.getDescription().deepCopy();
        copyFields(request, description, List.of("configuration", "evaluationIntervalInSeconds", "missingDataAction", "labels"));
        description.put("modifiedAt", Instant.now().toEpochMilli() / 1000.0);
        description.set("status", metadataStatus(create ? "CREATION_FAILED" : "UPDATE_FAILED",
                "Configuration stored only. Local anomaly detector training and evaluation are not implemented."));
        detector.setDescription(description);
    }

    private static ObjectNode detectorDescription(AnomalyDetector detector) {
        ObjectNode description = detector.getDescription().deepCopy();
        ObjectNode tags = description.putObject("tags");
        detector.getTags().forEach(tags::put);
        return description;
    }

    private static void copyFields(ObjectNode source, ObjectNode target, List<String> fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) {
                target.set(field, value.deepCopy());
            }
        }
    }

    private static ObjectNode metadataStatus(String code, String reason) {
        return JsonNodeFactory.instance.objectNode().put("statusCode", code).put("statusReason", reason);
    }

    private static AwsException notFound(String resource, String id) {
        return new AwsException("ResourceNotFoundException", resource + " not found: " + id, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    // ── TagHandler: the shared /tags/{resourceArn} dispatcher routes aps ARNs here ──

    @Override
    public String serviceKey() {
        return "aps";
    }

    // AMP defines TagResource/UntagResource with a 200 response, not the dispatcher's default 204.
    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        synchronized (tagLock(region, arn)) {
            return Map.copyOf(taggableByArn(region, arn).tags());
        }
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        // Per AMP's TagResource: keys must not begin with the reserved "aws:" prefix
        // (case-insensitive, matching the sibling checks in FisService and BatchService).
        for (String tagKey : tags.keySet()) {
            if (tagKey.regionMatches(true, 0, "aws:", 0, 4)) {
                throw new AwsException("ValidationException",
                        "Tag keys must not begin with aws:. Offending key: " + tagKey, 400);
            }
        }
        synchronized (tagLock(region, arn)) {
            Taggable taggable = taggableByArn(region, arn);
            taggable.tags().putAll(tags);
            taggable.persist().run();
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        synchronized (tagLock(region, arn)) {
            Taggable taggable = taggableByArn(region, arn);
            tagKeys.forEach(taggable.tags()::remove);
            taggable.persist().run();
        }
    }

    private Object tagLock(String region, String arn) {
        try {
            String[] parts = AwsArnUtils.parse(arn).resource().split("/", 3);
            return workspaceLock(region, parts.length > 1 ? parts[1] : arn);
        } catch (IllegalArgumentException e) {
            throw ApsConfigurationValidator.invalid("Invalid resource ARN: " + arn);
        }
    }

    private record Taggable(Map<String, String> tags, Runnable persist) {
    }

    private Taggable taggableByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        if (!"aps".equals(parsed.service()) || !region.equals(parsed.region())
                || !regionResolver.getAccountId().equals(parsed.accountId())) {
            throw new AwsException("ValidationException",
                    "The resource ARN does not belong to this AMP account and region: " + arn, 400);
        }
        String resource = parsed.resource();
        String workspacePrefix = "workspace/";
        if (resource.startsWith(workspacePrefix) && resource.length() > workspacePrefix.length()) {
            PrometheusWorkspace workspace =
                    describeWorkspace(region, resource.substring(workspacePrefix.length()));
            return new Taggable(workspace.getTags(),
                    () -> storage.put(key(region, workspace.getWorkspaceId()), workspace));
        }
        String namespacePrefix = "rulegroupsnamespace/";
        if (resource.startsWith(namespacePrefix)) {
            String[] parts = resource.substring(namespacePrefix.length()).split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, parts[0], parts[1]);
                return new Taggable(namespace.getTags(),
                        () -> namespaceStorage.put(namespaceKey(region, parts[0], parts[1]), namespace));
            }
        }
        String detectorPrefix = "anomalydetector/";
        if (resource.startsWith(detectorPrefix)) {
            String[] parts = resource.substring(detectorPrefix.length()).split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                PrometheusWorkspace workspace = describeWorkspace(region, parts[0]);
                AnomalyDetector detector = requireDetector(workspace, parts[1]);
                return new Taggable(detector.getTags(), () -> storage.put(key(region, parts[0]), workspace));
            }
        }
        throw new AwsException("ValidationException",
                "Tags are only supported on AMP workspaces, rule groups namespaces, and anomaly detectors: " + arn, 400);
    }

    // AMP is regional ("You can have one or more workspaces in each Region in your account"), so
    // the store is partitioned by request region, like CloudWatchLogsService's groupKey.
    private static String key(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId;
    }

    private static String keyPrefix(String region) {
        return region + "::";
    }

    private static String namespaceKey(String region, String workspaceId, String name) {
        return namespaceKeyPrefix(region, workspaceId) + name;
    }

    private static String namespaceKeyPrefix(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId + "::";
    }

    private static void requireNamespaceName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAMESPACE_NAME_LENGTH
                || !NAMESPACE_NAME.matcher(name).matches() || name.indexOf('/') >= 0) {
            throw new AwsException("ValidationException",
                    "name must be 1 to " + MAX_NAMESPACE_NAME_LENGTH
                            + " characters matching " + NAMESPACE_NAME.pattern()
                            + " and must not contain '/'.", 400);
        }
    }

    private static void requireNamespaceData(String encodedData) {
        ApsConfigurationValidator.ruleGroups(encodedData);
    }

    // AMP strips leading/trailing blanks from every alias it accepts, including the list filter.
    private static String stripAlias(String alias) {
        return alias == null ? null : alias.strip();
    }
}
