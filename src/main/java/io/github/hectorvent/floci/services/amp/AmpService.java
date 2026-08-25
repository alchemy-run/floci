package io.github.hectorvent.floci.services.amp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amp.model.AlertManagerDefinition;
import io.github.hectorvent.floci.services.amp.model.AmpAnomalyDetector;
import io.github.hectorvent.floci.services.amp.model.AmpWorkspace;
import io.github.hectorvent.floci.services.amp.model.RuleGroupsNamespace;
import io.github.hectorvent.floci.services.amp.model.Scraper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.hectorvent.floci.services.amp.AmpRemoteWriteCodec.Sample;
import io.github.hectorvent.floci.services.amp.AmpRemoteWriteCodec.Series;

/**
 * Amazon Managed Service for Prometheus scraper control plane (restJson1, signed as {@code aps}).
 */
@ApplicationScoped
public class AmpService {

    static final String DEFAULT_SCRAPER_CONFIGURATION = """
            global:
              scrape_interval: 30s
              evaluation_interval: 30s
            scrape_configs:
              - job_name: kubernetes-pods
                kubernetes_sd_configs:
                  - role: pod
            """;

    private static final String[] DEFAULT_LOGGING_COMPONENTS = {
            "SERVICE_DISCOVERY", "COLLECTOR", "EXPORTER"
    };

    private static final Pattern LABEL_PAIR =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"((?:\\\\.|[^\"])*)\"");

    private final StorageBackend<String, Scraper> scraperStore;
    private final StorageBackend<String, AmpWorkspace> workspaceStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, List<Series>> seriesByWorkspace = new ConcurrentHashMap<>();

    @Inject
    public AmpService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create(
                "amp",
                "amp-scrapers.json",
                new TypeReference<Map<String, Scraper>>() {
                }),
                storageFactory.create(
                        "amp",
                        "amp-workspaces.json",
                        new TypeReference<Map<String, AmpWorkspace>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    AmpService(
            StorageBackend<String, Scraper> scraperStore,
            StorageBackend<String, AmpWorkspace> workspaceStore,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.scraperStore = scraperStore == null ? new InMemoryStorage<>() : scraperStore;
        this.workspaceStore = workspaceStore == null ? new InMemoryStorage<>() : workspaceStore;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public byte[] defaultScraperConfiguration() {
        return DEFAULT_SCRAPER_CONFIGURATION.getBytes(StandardCharsets.UTF_8);
    }

    public Scraper describeScraper(String region, String scraperId) {
        if (scraperId == null || scraperId.isBlank()) {
            throw validation("scraperId is required.");
        }
        return scraperStore.get(storageKey(region, scraperId)).orElseThrow(() -> scraperNotFound(scraperId));
    }

    public synchronized Scraper createScraper(String region, JsonNode request) {
        requireObject(request, "Request body");
        String blob = requireConfigurationBlob(request);
        JsonNode source = requireSource(request);
        JsonNode destination = requireDestination(request);
        String scraperId = "s-" + UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        Scraper scraper = new Scraper();
        scraper.setScraperId(scraperId);
        scraper.setRegion(region);
        scraper.setArn(regionResolver.buildArn("aps", region, "scraper/" + scraperId));
        scraper.setRoleArn("arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/scraper.aps.amazonaws.com/AWSServiceRoleForAmazonPrometheus");
        scraper.setAlias(textOrNull(request, "alias"));
        scraper.setStatusCode("ACTIVE");
        scraper.setCreatedAt(now);
        scraper.setLastModifiedAt(now);
        scraper.setTags(readTags(request));
        scraper.setConfigurationBlob(blob);
        scraper.setSource(source.deepCopy());
        scraper.setDestination(destination.deepCopy());
        JsonNode roleConfiguration = request.get("roleConfiguration");
        if (roleConfiguration != null && roleConfiguration.isObject()) {
            scraper.setRoleConfiguration(roleConfiguration.deepCopy());
        }
        scraperStore.put(storageKey(region, scraperId), scraper);
        return scraper;
    }

    public synchronized Scraper updateScraper(String region, String scraperId, JsonNode request) {
        Scraper scraper = describeScraper(region, scraperId);
        requireObject(request, "Request body");
        boolean changed = false;
        if (request.has("alias")) {
            scraper.setAlias(textOrNull(request, "alias"));
            changed = true;
        }
        if (request.has("scrapeConfiguration")) {
            scraper.setConfigurationBlob(requireConfigurationBlob(request));
            changed = true;
        }
        if (request.has("destination")) {
            scraper.setDestination(requireDestination(request).deepCopy());
            changed = true;
        }
        if (request.has("roleConfiguration")) {
            JsonNode roleConfiguration = request.get("roleConfiguration");
            scraper.setRoleConfiguration(
                    roleConfiguration != null && roleConfiguration.isObject() ? roleConfiguration.deepCopy() : null);
            changed = true;
        }
        if (changed) {
            scraper.setLastModifiedAt(Instant.now().getEpochSecond());
            scraperStore.put(storageKey(region, scraperId), scraper);
        }
        return scraper;
    }

    public synchronized Scraper deleteScraper(String region, String scraperId) {
        Scraper scraper = describeScraper(region, scraperId);
        scraper.setStatusCode("DELETING");
        scraperStore.delete(storageKey(region, scraperId));
        return scraper;
    }

    public List<Scraper> listScrapers(String region) {
        List<Scraper> scrapers = scraperStore.scan(key -> key.startsWith(region + "::"));
        scrapers.sort(Comparator.comparing(Scraper::getScraperId));
        return scrapers;
    }

    public synchronized Scraper updateScraperLoggingConfiguration(String region, String scraperId, JsonNode request) {
        Scraper scraper = describeScraper(region, scraperId);
        requireObject(request, "Request body");
        JsonNode destination = request.get("loggingDestination");
        if (destination == null || !destination.isObject()
                || textOrNull(destination.path("cloudWatchLogs"), "logGroupArn") == null) {
            throw validation("loggingDestination.cloudWatchLogs.logGroupArn is required.");
        }
        JsonNode components = request.get("scraperComponents");
        if (components == null || !components.isArray()) {
            components = defaultLoggingComponents();
        }
        scraper.setLoggingDestination(destination.deepCopy());
        scraper.setScraperComponents(components.deepCopy());
        scraper.setLoggingStatusCode("ACTIVE");
        scraper.setLoggingModifiedAt(Instant.now().getEpochSecond());
        scraperStore.put(storageKey(region, scraperId), scraper);
        return scraper;
    }

    public Scraper describeScraperLoggingConfiguration(String region, String scraperId) {
        Scraper scraper = describeScraper(region, scraperId);
        if (!scraper.hasLoggingConfiguration()) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "Resource of type scraper logging configuration with identifier "
                            + scraperId + " is not found.",
                    404,
                    Map.of("resourceId", scraperId, "resourceType", "scraper-logging-configuration"));
        }
        return scraper;
    }

    public synchronized void deleteScraperLoggingConfiguration(String region, String scraperId) {
        Scraper scraper = describeScraperLoggingConfiguration(region, scraperId);
        scraper.setLoggingDestination(null);
        scraper.setScraperComponents(null);
        scraper.setLoggingStatusCode(null);
        scraper.setLoggingModifiedAt(0);
        scraperStore.put(storageKey(region, scraperId), scraper);
    }

    public Map<String, String> listTags(String region, String arn) {
        if (isWorkspaceFamilyArn(arn)) {
            AmpWorkspace workspace = describeWorkspaceByArn(region, arn);
            return Map.copyOf(tagsForArn(workspace, arn));
        }
        Scraper scraper = describeScraperByArn(region, arn);
        return scraper.getTags() == null ? Map.of() : Map.copyOf(scraper.getTags());
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isWorkspaceFamilyArn(arn)) {
            AmpWorkspace workspace = describeWorkspaceByArn(region, arn);
            Map<String, String> merged = tagsForArn(workspace, arn);
            if (tags != null) {
                merged.putAll(tags);
            }
            persist(workspace);
            return;
        }
        Scraper scraper = describeScraperByArn(region, arn);
        Map<String, String> merged = scraper.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(scraper.getTags());
        if (tags != null) {
            merged.putAll(tags);
        }
        scraper.setTags(merged);
        scraperStore.put(storageKey(scraper.getRegion(), scraper.getScraperId()), scraper);
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isWorkspaceFamilyArn(arn)) {
            if (tagKeys == null || tagKeys.isEmpty()) {
                return;
            }
            AmpWorkspace workspace = describeWorkspaceByArn(region, arn);
            Map<String, String> tags = tagsForArn(workspace, arn);
            tagKeys.forEach(tags::remove);
            persist(workspace);
            return;
        }
        Scraper scraper = describeScraperByArn(region, arn);
        if (scraper.getTags() == null || tagKeys == null || tagKeys.isEmpty()) {
            return;
        }
        Map<String, String> tags = new LinkedHashMap<>(scraper.getTags());
        tagKeys.forEach(tags::remove);
        scraper.setTags(tags);
        scraperStore.put(storageKey(scraper.getRegion(), scraper.getScraperId()), scraper);
    }

    public ObjectNode toDescription(Scraper scraper) {
        ObjectNode node = toSummary(scraper);
        ObjectNode scrapeConfiguration = node.putObject("scrapeConfiguration");
        scrapeConfiguration.put("configurationBlob", scraper.getConfigurationBlob());
        return node;
    }

    public ObjectNode toSummary(Scraper scraper) {
        ObjectNode node = objectMapper.createObjectNode();
        if (scraper.getAlias() != null) {
            node.put("alias", scraper.getAlias());
        }
        node.put("scraperId", scraper.getScraperId());
        node.put("arn", scraper.getArn());
        node.put("roleArn", scraper.getRoleArn());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", scraper.getStatusCode());
        node.put("createdAt", scraper.getCreatedAt());
        node.put("lastModifiedAt", scraper.getLastModifiedAt());
        if (scraper.getTags() != null && !scraper.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            scraper.getTags().forEach(tags::put);
        }
        if (scraper.getSource() != null) {
            node.set("source", scraper.getSource());
        }
        if (scraper.getDestination() != null) {
            node.set("destination", scraper.getDestination());
        }
        if (scraper.getRoleConfiguration() != null) {
            node.set("roleConfiguration", scraper.getRoleConfiguration());
        }
        return node;
    }

    public ObjectNode createResponse(Scraper scraper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scraperId", scraper.getScraperId());
        node.put("arn", scraper.getArn());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", scraper.getStatusCode());
        if (scraper.getTags() != null && !scraper.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            scraper.getTags().forEach(tags::put);
        }
        return node;
    }

    public ObjectNode loggingDescription(Scraper scraper) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode status = node.putObject("status");
        status.put("statusCode", scraper.getLoggingStatusCode());
        node.put("scraperId", scraper.getScraperId());
        node.set("loggingDestination", scraper.getLoggingDestination());
        node.set("scraperComponents", scraper.getScraperComponents());
        node.put("modifiedAt", scraper.getLoggingModifiedAt());
        return node;
    }

    private Scraper describeScraperByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN: " + arn);
        }
        if (!"aps".equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith("scraper/")) {
            throw scraperNotFound(arn);
        }
        String scraperId = parsed.resource().substring("scraper/".length());
        String lookupRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        return describeScraper(lookupRegion, scraperId);
    }

    private ArrayNode defaultLoggingComponents() {
        ArrayNode components = objectMapper.createArrayNode();
        for (String type : DEFAULT_LOGGING_COMPONENTS) {
            ObjectNode component = components.addObject();
            component.put("type", type);
        }
        return components;
    }

    private static String storageKey(String region, String scraperId) {
        return region + "::" + scraperId;
    }

    private static String requireConfigurationBlob(JsonNode request) {
        JsonNode scrapeConfiguration = request.get("scrapeConfiguration");
        requireObject(scrapeConfiguration, "scrapeConfiguration");
        JsonNode blob = scrapeConfiguration.get("configurationBlob");
        if (blob == null || blob.isNull() || !blob.isTextual() || blob.asText().isBlank()) {
            throw validation("scrapeConfiguration.configurationBlob is required.");
        }
        return blob.asText();
    }

    private static JsonNode requireSource(JsonNode request) {
        JsonNode source = request.get("source");
        requireObject(source, "source");
        boolean eks = source.has("eksConfiguration");
        boolean vpc = source.has("vpcConfiguration");
        if (eks == vpc) {
            throw validation("source must specify exactly one of eksConfiguration or vpcConfiguration.");
        }
        return source;
    }

    private static JsonNode requireDestination(JsonNode request) {
        JsonNode destination = request.get("destination");
        requireObject(destination, "destination");
        if (textOrNull(destination.path("ampConfiguration"), "workspaceArn") == null) {
            throw validation("destination.ampConfiguration.workspaceArn is required.");
        }
        return destination;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    static AwsException scraperNotFound(String scraperId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource of type scraper with identifier " + scraperId + " is not found.",
                404,
                Map.of("resourceId", scraperId, "resourceType", "scraper"));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public AmpWorkspace describeWorkspace(String region, String workspaceId) {
        return workspaceStore.get(workspaceKey(region, workspaceId))
                .orElseThrow(() -> notFound(workspaceId, "workspace"));
    }

    public synchronized RuleGroupsNamespace createRuleGroupsNamespace(
            String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        String name = requireText(request, "name");
        String data = requireText(request, "data");
        if (workspace.getRuleGroupsNamespaces().containsKey(name)) {
            throw conflict(name, "rulegroupsnamespace");
        }
        long now = Instant.now().getEpochSecond();
        RuleGroupsNamespace ns = new RuleGroupsNamespace();
        ns.setName(name);
        ns.setArn("arn:aws:aps:" + region + ":" + regionResolver.getAccountId()
                + ":rulegroupsnamespace/" + workspaceId + "/" + name);
        ns.setDataBase64(data);
        ns.setStatusCode("ACTIVE");
        ns.setCreatedAt(now);
        ns.setModifiedAt(now);
        ns.setTags(readTags(request));
        workspace.getRuleGroupsNamespaces().put(name, ns);
        persist(workspace);
        return ns;
    }

    public RuleGroupsNamespace describeRuleGroupsNamespace(String region, String workspaceId, String name) {
        RuleGroupsNamespace ns = describeWorkspace(region, workspaceId).getRuleGroupsNamespaces().get(name);
        if (ns == null) {
            throw notFound(name, "rulegroupsnamespace");
        }
        return ns;
    }

    public synchronized RuleGroupsNamespace putRuleGroupsNamespace(
            String region, String workspaceId, String name, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        RuleGroupsNamespace ns = workspace.getRuleGroupsNamespaces().get(name);
        if (ns == null) {
            throw notFound(name, "rulegroupsnamespace");
        }
        ns.setDataBase64(requireText(request, "data"));
        ns.setModifiedAt(Instant.now().getEpochSecond());
        ns.setStatusCode("ACTIVE");
        persist(workspace);
        return ns;
    }

    public synchronized void deleteRuleGroupsNamespace(String region, String workspaceId, String name) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getRuleGroupsNamespaces().remove(name) == null) {
            throw notFound(name, "rulegroupsnamespace");
        }
        persist(workspace);
    }

    public synchronized AlertManagerDefinition createAlertManagerDefinition(
            String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getAlertManager() != null) {
            throw conflict(workspaceId, "alertmanager");
        }
        long now = Instant.now().getEpochSecond();
        AlertManagerDefinition def = new AlertManagerDefinition();
        def.setDataBase64(requireText(request, "data"));
        def.setStatusCode("ACTIVE");
        def.setCreatedAt(now);
        def.setModifiedAt(now);
        workspace.setAlertManager(def);
        persist(workspace);
        return def;
    }

    public AlertManagerDefinition describeAlertManagerDefinition(String region, String workspaceId) {
        AlertManagerDefinition def = describeWorkspace(region, workspaceId).getAlertManager();
        if (def == null) {
            throw notFound(workspaceId, "alertmanager");
        }
        return def;
    }

    public synchronized AlertManagerDefinition putAlertManagerDefinition(
            String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        AlertManagerDefinition def = workspace.getAlertManager();
        if (def == null) {
            throw notFound(workspaceId, "alertmanager");
        }
        def.setDataBase64(requireText(request, "data"));
        def.setModifiedAt(Instant.now().getEpochSecond());
        def.setStatusCode("ACTIVE");
        persist(workspace);
        return def;
    }

    public synchronized void deleteAlertManagerDefinition(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getAlertManager() == null) {
            throw notFound(workspaceId, "alertmanager");
        }
        workspace.setAlertManager(null);
        persist(workspace);
    }

    public synchronized AmpWorkspace createWorkspace(String region, JsonNode request) {
        requireObject(request, "Request body");
        String workspaceId = "ws-" + UUID.randomUUID();
        AmpWorkspace workspace = new AmpWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setAlias(textOrNull(request, "alias"));
        workspace.setKmsKeyArn(textOrNull(request, "kmsKeyArn"));
        workspace.setTags(readTags(request));
        workspace.setStatusCode("ACTIVE");
        workspace.setCreatedAt(Instant.now().getEpochSecond());
        workspace.setRegion(region);
        workspace.setArn(regionResolver.buildArn("aps", region, "workspace/" + workspaceId));
        workspace.setRetentionPeriodInDays(AmpWorkspace.DEFAULT_RETENTION_DAYS);
        persist(workspace);
        return workspace;
    }

    public List<AmpWorkspace> listWorkspaces(String region, String alias) {
        List<AmpWorkspace> workspaces = workspaceStore.scan(key -> key.startsWith(region + ":"));
        if (alias != null && !alias.isBlank()) {
            workspaces.removeIf(workspace -> !alias.equals(workspace.getAlias()));
        }
        workspaces.sort(Comparator.comparing(AmpWorkspace::getWorkspaceId));
        return workspaces;
    }

    public void deleteWorkspace(String region, String workspaceId) {
        describeWorkspace(region, workspaceId);
        workspaceStore.delete(workspaceKey(region, workspaceId));
        seriesByWorkspace.remove(workspaceId);
    }

    public void updateWorkspaceAlias(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (request != null && request.has("alias")) {
            workspace.setAlias(textOrNull(request, "alias"));
            persist(workspace);
        }
    }

    public ObjectNode describeWorkspaceConfiguration(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        ObjectNode configuration = objectMapper.createObjectNode();
        ObjectNode status = configuration.putObject("status");
        status.put("statusCode", "ACTIVE");
        configuration.put("retentionPeriodInDays", workspace.getRetentionPeriodInDays());
        if (workspace.getLimitsPerLabelSet() != null) {
            configuration.set("limitsPerLabelSet", workspace.getLimitsPerLabelSet());
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("workspaceConfiguration", configuration);
        return wrapper;
    }

    public ObjectNode updateWorkspaceConfiguration(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        requireObject(request, "Request body");
        if (request.has("retentionPeriodInDays") && !request.get("retentionPeriodInDays").isNull()) {
            workspace.setRetentionPeriodInDays(request.get("retentionPeriodInDays").asInt());
        }
        if (request.has("limitsPerLabelSet")) {
            workspace.setLimitsPerLabelSet(request.get("limitsPerLabelSet"));
        }
        persist(workspace);
        return statusOnly("ACTIVE");
    }

    public ObjectNode createWorkspaceLoggingConfiguration(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getLogGroupArn() != null) {
            throw conflict(workspaceId, "LoggingConfiguration");
        }
        String logGroupArn = requireText(request, "logGroupArn");
        long now = Instant.now().getEpochSecond();
        workspace.setLogGroupArn(logGroupArn);
        workspace.setLoggingCreatedAt(now);
        workspace.setLoggingModifiedAt(now);
        persist(workspace);
        return statusOnly("ACTIVE");
    }

    public ObjectNode describeWorkspaceLoggingConfiguration(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getLogGroupArn() == null) {
            throw notFound(workspaceId, "LoggingConfiguration");
        }
        ObjectNode meta = objectMapper.createObjectNode();
        ObjectNode status = meta.putObject("status");
        status.put("statusCode", "ACTIVE");
        meta.put("workspace", workspaceId);
        meta.put("logGroupArn", workspace.getLogGroupArn());
        meta.put("createdAt", workspace.getLoggingCreatedAt());
        meta.put("modifiedAt", workspace.getLoggingModifiedAt());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("loggingConfiguration", meta);
        return response;
    }

    public ObjectNode updateWorkspaceLoggingConfiguration(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getLogGroupArn() == null) {
            throw notFound(workspaceId, "LoggingConfiguration");
        }
        workspace.setLogGroupArn(requireText(request, "logGroupArn"));
        workspace.setLoggingModifiedAt(Instant.now().getEpochSecond());
        persist(workspace);
        return statusOnly("ACTIVE");
    }

    public void deleteWorkspaceLoggingConfiguration(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getLogGroupArn() == null) {
            throw notFound(workspaceId, "LoggingConfiguration");
        }
        workspace.setLogGroupArn(null);
        workspace.setLoggingCreatedAt(null);
        workspace.setLoggingModifiedAt(null);
        persist(workspace);
    }

    public ObjectNode createQueryLoggingConfiguration(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getQueryDestinations() != null) {
            throw conflict(workspaceId, "QueryLoggingConfiguration");
        }
        JsonNode destinations = request == null ? null : request.get("destinations");
        if (destinations == null || !destinations.isArray()) {
            throw validation("destinations is required.");
        }
        long now = Instant.now().getEpochSecond();
        workspace.setQueryDestinations(destinations);
        workspace.setQueryLoggingCreatedAt(now);
        workspace.setQueryLoggingModifiedAt(now);
        persist(workspace);
        return statusOnly("ACTIVE");
    }

    public ObjectNode describeQueryLoggingConfiguration(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getQueryDestinations() == null) {
            throw notFound(workspaceId, "QueryLoggingConfiguration");
        }
        ObjectNode meta = objectMapper.createObjectNode();
        ObjectNode status = meta.putObject("status");
        status.put("statusCode", "ACTIVE");
        meta.put("workspace", workspaceId);
        meta.set("destinations", workspace.getQueryDestinations());
        meta.put("createdAt", workspace.getQueryLoggingCreatedAt());
        meta.put("modifiedAt", workspace.getQueryLoggingModifiedAt());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("queryLoggingConfiguration", meta);
        return response;
    }

    public ObjectNode updateQueryLoggingConfiguration(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getQueryDestinations() == null) {
            throw notFound(workspaceId, "QueryLoggingConfiguration");
        }
        JsonNode destinations = request == null ? null : request.get("destinations");
        if (destinations == null || !destinations.isArray()) {
            throw validation("destinations is required.");
        }
        workspace.setQueryDestinations(destinations);
        workspace.setQueryLoggingModifiedAt(Instant.now().getEpochSecond());
        persist(workspace);
        return statusOnly("ACTIVE");
    }

    public void deleteQueryLoggingConfiguration(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getQueryDestinations() == null) {
            throw notFound(workspaceId, "QueryLoggingConfiguration");
        }
        workspace.setQueryDestinations(null);
        workspace.setQueryLoggingCreatedAt(null);
        workspace.setQueryLoggingModifiedAt(null);
        persist(workspace);
    }

    public ObjectNode putResourcePolicy(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        String document = requireText(request, "policyDocument");
        if (document.equals(workspace.getPolicyDocument())) {
            return policyPutResponse(workspace);
        }
        workspace.setPolicyDocument(document);
        workspace.setPolicyStatus("ACTIVE");
        workspace.setRevisionId(nextRevision(workspace.getRevisionId()));
        persist(workspace);
        return policyPutResponse(workspace);
    }

    public ObjectNode describeResourcePolicy(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getPolicyDocument() == null) {
            throw notFound(workspaceId, "ResourcePolicy");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyDocument", workspace.getPolicyDocument());
        response.put("policyStatus", workspace.getPolicyStatus());
        response.put("revisionId", workspace.getRevisionId());
        return response;
    }

    public void deleteResourcePolicy(String region, String workspaceId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getPolicyDocument() == null) {
            throw notFound(workspaceId, "ResourcePolicy");
        }
        workspace.setPolicyDocument(null);
        workspace.setPolicyStatus(null);
        workspace.setRevisionId(null);
        persist(workspace);
    }

    public ObjectNode createAnomalyDetector(String region, String workspaceId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        String alias = requireText(request, "alias");
        for (AmpAnomalyDetector existing : workspace.getDetectors().values()) {
            if (alias.equals(existing.getAlias())) {
                throw conflict(existing.getAnomalyDetectorId(), "AnomalyDetector");
            }
        }
        JsonNode configuration = request.get("configuration");
        if (configuration == null || !configuration.isObject()) {
            throw validation("configuration is required.");
        }
        String detectorId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        AmpAnomalyDetector detector = new AmpAnomalyDetector();
        detector.setAnomalyDetectorId(detectorId);
        detector.setAlias(alias);
        detector.setArn(regionResolver.buildArn(
                "aps", region, "anomaly-detector/" + workspaceId + "/" + detectorId));
        if (request.has("evaluationIntervalInSeconds") && request.get("evaluationIntervalInSeconds").isNumber()) {
            detector.setEvaluationIntervalInSeconds(request.get("evaluationIntervalInSeconds").intValue());
        }
        detector.setMissingDataAction(request.get("missingDataAction"));
        detector.setConfiguration(configuration);
        detector.setLabels(readStringMap(request, "labels"));
        detector.setStatusCode("ACTIVE");
        detector.setCreatedAt(now);
        detector.setModifiedAt(now);
        detector.setTags(readTags(request));
        workspace.getDetectors().put(detectorId, detector);
        persist(workspace);
        return detectorCreateResponse(detector);
    }

    public ObjectNode describeAnomalyDetector(String region, String workspaceId, String anomalyDetectorId) {
        AmpAnomalyDetector found = describeWorkspace(region, workspaceId).getDetectors().get(anomalyDetectorId);
        if (found == null) {
            throw notFound(anomalyDetectorId, "AnomalyDetector");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("anomalyDetector", detectorNode(found));
        return response;
    }

    public ObjectNode putAnomalyDetector(
            String region, String workspaceId, String anomalyDetectorId, JsonNode request) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        AmpAnomalyDetector detector = workspace.getDetectors().get(anomalyDetectorId);
        if (detector == null) {
            throw notFound(anomalyDetectorId, "AnomalyDetector");
        }
        if (request.has("evaluationIntervalInSeconds") && request.get("evaluationIntervalInSeconds").isNumber()) {
            detector.setEvaluationIntervalInSeconds(request.get("evaluationIntervalInSeconds").intValue());
        }
        if (request.has("missingDataAction")) {
            detector.setMissingDataAction(request.get("missingDataAction"));
        }
        if (request.has("configuration") && request.get("configuration").isObject()) {
            detector.setConfiguration(request.get("configuration"));
        }
        if (request.has("labels")) {
            detector.setLabels(readStringMap(request, "labels"));
        }
        detector.setStatusCode("ACTIVE");
        detector.setModifiedAt(Instant.now().getEpochSecond());
        persist(workspace);
        return detectorCreateResponse(detector);
    }

    public void deleteAnomalyDetector(String region, String workspaceId, String anomalyDetectorId) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        if (workspace.getDetectors().remove(anomalyDetectorId) == null) {
            throw notFound(anomalyDetectorId, "AnomalyDetector");
        }
        persist(workspace);
    }

    public ObjectNode listAnomalyDetectors(String region, String workspaceId, String alias) {
        AmpWorkspace workspace = describeWorkspace(region, workspaceId);
        ArrayNode detectors = objectMapper.createArrayNode();
        workspace.getDetectors().values().stream()
                .sorted(Comparator.comparing(AmpAnomalyDetector::getAnomalyDetectorId))
                .filter(d -> alias == null || alias.isBlank() || alias.equals(d.getAlias()))
                .forEach(d -> detectors.add(detectorSummary(d)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("anomalyDetectors", detectors);
        return response;
    }

    public ObjectNode workspaceDescription(AmpWorkspace workspace) {
        ObjectNode node = workspaceSummary(workspace);
        node.put("prometheusEndpoint", prometheusEndpoint(workspace));
        return node;
    }

    public String prometheusEndpoint(AmpWorkspace workspace) {
        String region = workspace.getRegion() == null || workspace.getRegion().isBlank()
                ? "us-east-1"
                : workspace.getRegion();
        return "https://aps-workspaces." + region + ".amazonaws.com/workspaces/"
                + workspace.getWorkspaceId() + "/";
    }

    public ObjectNode workspaceSummary(AmpWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspace.getWorkspaceId());
        if (workspace.getAlias() != null) {
            node.put("alias", workspace.getAlias());
        }
        node.put("arn", workspace.getArn());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", workspace.getStatusCode());
        node.put("createdAt", workspace.getCreatedAt());
        if (workspace.getTags() != null && !workspace.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            workspace.getTags().forEach(tags::put);
        }
        if (workspace.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return node;
    }

    public ObjectNode createWorkspaceResponse(AmpWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspace.getWorkspaceId());
        node.put("arn", workspace.getArn());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", workspace.getStatusCode());
        if (workspace.getTags() != null && !workspace.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            workspace.getTags().forEach(tags::put);
        }
        if (workspace.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return node;
    }

    private void persist(AmpWorkspace workspace) {
        workspaceStore.put(workspaceKey(workspace.getRegion(), workspace.getWorkspaceId()), workspace);
    }

    private ObjectNode statusOnly(String statusCode) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode status = response.putObject("status");
        status.put("statusCode", statusCode);
        return response;
    }

    private static String nextRevision(String current) {
        if (current == null) {
            return "1";
        }
        try {
            return Integer.toString(Integer.parseInt(current) + 1);
        } catch (NumberFormatException e) {
            return "1";
        }
    }

    private ObjectNode policyPutResponse(AmpWorkspace workspace) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStatus", workspace.getPolicyStatus());
        response.put("revisionId", workspace.getRevisionId());
        return response;
    }

    private Map<String, String> readStringMap(JsonNode request, String field) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request == null) {
            return values;
        }
        JsonNode node = request.get(field);
        if (node == null || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return values;
    }

    private ObjectNode detectorCreateResponse(AmpAnomalyDetector detector) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("anomalyDetectorId", detector.getAnomalyDetectorId());
        response.put("arn", detector.getArn());
        ObjectNode status = response.putObject("status");
        status.put("statusCode", detector.getStatusCode());
        if (detector.getTags() != null && !detector.getTags().isEmpty()) {
            ObjectNode tags = response.putObject("tags");
            detector.getTags().forEach(tags::put);
        }
        return response;
    }

    private ObjectNode detectorSummary(AmpAnomalyDetector detector) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", detector.getArn());
        node.put("anomalyDetectorId", detector.getAnomalyDetectorId());
        node.put("alias", detector.getAlias());
        ObjectNode status = node.putObject("status");
        status.put("statusCode", detector.getStatusCode());
        node.put("createdAt", detector.getCreatedAt());
        node.put("modifiedAt", detector.getModifiedAt());
        if (detector.getTags() != null && !detector.getTags().isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            detector.getTags().forEach(tags::put);
        }
        return node;
    }

    private Map<String, String> workspaceTags(String region, String arn) {
        AmpWorkspace workspace = workspaceForArn(region, arn);
        if (workspace == null) {
            return null;
        }
        return tagsForArn(workspace, arn);
    }

    private static boolean isWorkspaceFamilyArn(String arn) {
        if (arn == null) {
            return false;
        }
        String resource = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        return resource.startsWith("workspace/")
                || resource.startsWith("anomaly-detector/")
                || resource.startsWith("rulegroupsnamespace/");
    }

    private AmpWorkspace describeWorkspaceByArn(String region, String arn) {
        AmpWorkspace workspace = workspaceForArn(region, arn);
        if (workspace == null) {
            throw validation("Invalid resource ARN: " + arn);
        }
        return workspace;
    }

    private AmpWorkspace workspaceForArn(String region, String arn) {
        if (arn == null) {
            return null;
        }
        String resource = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        if (resource.startsWith("workspace/")) {
            return describeWorkspace(region, resource.substring("workspace/".length()));
        }
        if (resource.startsWith("anomaly-detector/") || resource.startsWith("rulegroupsnamespace/")) {
            String prefix = resource.startsWith("anomaly-detector/")
                    ? "anomaly-detector/"
                    : "rulegroupsnamespace/";
            String rest = resource.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return null;
            }
            return describeWorkspace(region, rest.substring(0, slash));
        }
        return null;
    }

    private Map<String, String> tagsForArn(AmpWorkspace workspace, String arn) {
        String resource = arn.substring(arn.lastIndexOf(':') + 1);
        if (resource.startsWith("anomaly-detector/")) {
            String detectorId = resource.substring(resource.lastIndexOf('/') + 1);
            AmpAnomalyDetector detector = workspace.getDetectors().get(detectorId);
            if (detector == null) {
                throw notFound(detectorId, "AnomalyDetector");
            }
            if (detector.getTags() == null) {
                detector.setTags(new LinkedHashMap<>());
            }
            return detector.getTags();
        }
        if (resource.startsWith("rulegroupsnamespace/")) {
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            RuleGroupsNamespace ns = workspace.getRuleGroupsNamespaces().get(name);
            if (ns == null) {
                throw notFound(name, "rulegroupsnamespace");
            }
            if (ns.getTags() == null) {
                ns.setTags(new LinkedHashMap<>());
            }
            return ns.getTags();
        }
        if (workspace.getTags() == null) {
            workspace.setTags(new LinkedHashMap<>());
        }
        return workspace.getTags();
    }

    private ObjectNode detectorNode(AmpAnomalyDetector detector) {
        ObjectNode node = detectorSummary(detector);
        if (detector.getEvaluationIntervalInSeconds() != null) {
            node.put("evaluationIntervalInSeconds", detector.getEvaluationIntervalInSeconds());
        }
        if (detector.getMissingDataAction() != null) {
            node.set("missingDataAction", detector.getMissingDataAction());
        }
        if (detector.getConfiguration() != null) {
            node.set("configuration", detector.getConfiguration());
        }
        if (detector.getLabels() != null && !detector.getLabels().isEmpty()) {
            ObjectNode labels = node.putObject("labels");
            detector.getLabels().forEach(labels::put);
        }
        return node;
    }

    public void remoteWrite(String region, String workspaceId, byte[] body, String contentEncoding) {
        describeWorkspace(region, workspaceId);
        if (body == null || body.length == 0) {
            return;
        }
        byte[] protobuf = body;
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("snappy")) {
            protobuf = AmpRemoteWriteCodec.snappyDecompress(body);
        } else {
            try {
                protobuf = AmpRemoteWriteCodec.snappyDecompress(body);
            } catch (IllegalArgumentException e) {
                protobuf = body;
            }
        }
        List<Series> incoming = AmpRemoteWriteCodec.decodeWriteRequest(protobuf);
        seriesByWorkspace.compute(workspaceId, (id, existing) -> mergeSeries(existing, incoming));
    }

    public ObjectNode instantQuery(String region, String workspaceId, String query, String time) {
        describeWorkspace(region, workspaceId);
        long at = parsePromTime(time, System.currentTimeMillis());
        ArrayNode result = objectMapper.createArrayNode();
        for (Series series : matchSeries(workspaceId, query)) {
            Sample latest = latestAt(series.samples(), at);
            if (latest == null) {
                continue;
            }
            ObjectNode sample = objectMapper.createObjectNode();
            sample.set("metric", labelsNode(series.labels()));
            ArrayNode value = sample.putArray("value");
            value.add(latest.timestampMs() / 1000.0);
            value.add(Double.toString(latest.value()));
            result.add(sample);
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resultType", "vector");
        data.set("result", result);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", "success");
        envelope.set("data", data);
        return envelope;
    }

    public ObjectNode rangeQuery(
            String region, String workspaceId, String query, String start, String end, String step) {
        describeWorkspace(region, workspaceId);
        long startMs = parsePromTime(start, System.currentTimeMillis() - 15 * 60_000L);
        long endMs = parsePromTime(end, System.currentTimeMillis());
        ArrayNode result = objectMapper.createArrayNode();
        for (Series series : matchSeries(workspaceId, query)) {
            ObjectNode sample = objectMapper.createObjectNode();
            sample.set("metric", labelsNode(series.labels()));
            ArrayNode values = sample.putArray("values");
            for (Sample point : series.samples()) {
                if (point.timestampMs() < startMs || point.timestampMs() > endMs) {
                    continue;
                }
                ArrayNode pair = values.addArray();
                pair.add(point.timestampMs() / 1000.0);
                pair.add(Double.toString(point.value()));
            }
            result.add(sample);
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resultType", "matrix");
        data.set("result", result);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", "success");
        envelope.set("data", data);
        return envelope;
    }

    public ObjectNode labelNames(String region, String workspaceId, List<String> matchers) {
        describeWorkspace(region, workspaceId);
        Set<String> names = new LinkedHashSet<>();
        names.add("__name__");
        for (Series series : matchingStored(workspaceId, matchers)) {
            names.addAll(series.labels().keySet());
        }
        ArrayNode data = objectMapper.createArrayNode();
        names.forEach(data::add);
        return successData(data);
    }

    public ObjectNode labelValues(String region, String workspaceId, String label, List<String> matchers) {
        describeWorkspace(region, workspaceId);
        Set<String> values = new LinkedHashSet<>();
        for (Series series : matchingStored(workspaceId, matchers)) {
            String value = series.labels().get(label);
            if (value != null) {
                values.add(value);
            }
        }
        ArrayNode data = objectMapper.createArrayNode();
        values.forEach(data::add);
        return successData(data);
    }

    public ObjectNode series(String region, String workspaceId, List<String> matchers) {
        describeWorkspace(region, workspaceId);
        ArrayNode data = objectMapper.createArrayNode();
        for (Series stored : matchingStored(workspaceId, matchers)) {
            data.add(labelsNode(stored.labels()));
        }
        return successData(data);
    }

    public ObjectNode metadata() {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", "success");
        envelope.set("data", objectMapper.createObjectNode());
        return envelope;
    }

    private List<Series> matchingStored(String workspaceId, List<String> matchers) {
        List<Series> stored = seriesByWorkspace.getOrDefault(workspaceId, List.of());
        if (matchers == null || matchers.isEmpty()) {
            return stored;
        }
        List<Series> matched = new ArrayList<>();
        for (Series series : stored) {
            boolean all = true;
            for (String matcher : matchers) {
                if (!matches(series, parseSelector(matcher))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matched.add(series);
            }
        }
        return matched;
    }

    private List<Series> matchSeries(String workspaceId, String query) {
        Map<String, String> selector = parseSelector(query);
        List<Series> matched = new ArrayList<>();
        for (Series series : seriesByWorkspace.getOrDefault(workspaceId, List.of())) {
            if (matches(series, selector)) {
                matched.add(series);
            }
        }
        return matched;
    }

    private static boolean matches(Series series, Map<String, String> selector) {
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            if (!entry.getValue().equals(series.labels().get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> parseSelector(String query) {
        Map<String, String> selector = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return selector;
        }
        String trimmed = query.trim();
        int brace = trimmed.indexOf('{');
        if (brace < 0) {
            selector.put("__name__", trimmed);
            return selector;
        }
        if (brace > 0) {
            selector.put("__name__", trimmed.substring(0, brace).trim());
        }
        int end = trimmed.lastIndexOf('}');
        String inside = end > brace ? trimmed.substring(brace + 1, end) : trimmed.substring(brace + 1);
        Matcher matcher = LABEL_PAIR.matcher(inside);
        while (matcher.find()) {
            selector.put(matcher.group(1), matcher.group(2).replace("\\\"", "\""));
        }
        return selector;
    }

    private static Sample latestAt(List<Sample> samples, long atMs) {
        Sample latest = null;
        for (Sample sample : samples) {
            if (sample.timestampMs() > atMs) {
                continue;
            }
            if (latest == null || sample.timestampMs() >= latest.timestampMs()) {
                latest = sample;
            }
        }
        return latest;
    }

    private static List<Series> mergeSeries(List<Series> existing, List<Series> incoming) {
        Map<String, Series> byKey = new LinkedHashMap<>();
        if (existing != null) {
            for (Series series : existing) {
                byKey.put(labelKey(series.labels()), series);
            }
        }
        for (Series series : incoming) {
            String key = labelKey(series.labels());
            Series prior = byKey.get(key);
            if (prior == null) {
                byKey.put(key, new Series(series.labels(), new ArrayList<>(series.samples())));
                continue;
            }
            List<Sample> samples = new ArrayList<>(prior.samples());
            samples.addAll(series.samples());
            samples.sort(Comparator.comparingLong(Sample::timestampMs));
            byKey.put(key, new Series(prior.labels(), samples));
        }
        return new ArrayList<>(byKey.values());
    }

    private static String labelKey(Map<String, String> labels) {
        StringBuilder builder = new StringBuilder();
        labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        return builder.toString();
    }

    private ObjectNode labelsNode(Map<String, String> labels) {
        ObjectNode node = objectMapper.createObjectNode();
        labels.forEach(node::put);
        return node;
    }

    private ObjectNode successData(JsonNode data) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("status", "success");
        envelope.set("data", data);
        return envelope;
    }

    private static long parsePromTime(String value, long fallbackMs) {
        if (value == null || value.isBlank()) {
            return fallbackMs;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException isoFailed) {
            try {
                return (long) (Double.parseDouble(value) * 1000.0);
            } catch (NumberFormatException e) {
                throw new AwsException(
                        "ValidationException",
                        "Invalid Prometheus timestamp: " + value + " (" + isoFailed.getMessage() + ")",
                        400);
            }
        }
    }

    private static String workspaceKey(String region, String workspaceId) {
        return region + ":" + workspaceId;
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || !request.isObject()) {
            throw validation(field + " is required.");
        }
        JsonNode node = request.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return node.asText();
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " not found: " + resourceId,
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType) {
        return new AwsException(
                "ConflictException",
                resourceType + " already exists: " + resourceId,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }
}
