package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import io.github.hectorvent.floci.services.rum.model.MetricDefinition;
import io.github.hectorvent.floci.services.rum.model.MetricDestination;
import io.github.hectorvent.floci.services.rum.model.ResourcePolicy;
import io.github.hectorvent.floci.services.rum.model.RumEventLog;
import io.github.hectorvent.floci.services.rum.model.StoredRumEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** CloudWatch RUM app-monitor lifecycle backed by the configured Floci storage mode. */
@ApplicationScoped
public class RumService implements TagHandler {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "rum:v1:";
    private static final String DATA_TOKEN_PREFIX = "rum-data:v1:";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final long MILLIS_THRESHOLD = 10_000_000_000L;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?!\\.)[.\\-_#A-Za-z0-9]+");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(localhost)$|^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}"
                    + "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$|"
                    + "(?=^[a-zA-Z0-9.\\*-]{4,253}$)(?!.*\\.-)(?!.*-\\.)(?!.*\\.\\.)"
                    + "(?!.*[^.]{64,})^(\\*\\.)?(?![-.\\*])[^\\*]{1,}\\."
                    + "(\\*|(?!.*--)(?=.*[a-zA-Z])[^\\*]{1,}[^\\*-])$");
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("(?!aws:)[a-zA-Z+-=._:/]+");
    private static final Pattern S3_URI_PATTERN = Pattern.compile(
            "s3://[a-z0-9][-.a-z0-9]{1,62}(?:/[-!_*'().a-z0-9A-Z]+(?:/[-!_*'().a-z0-9A-Z]+)*)?/?");
    private static final Set<String> TELEMETRIES = Set.of("errors", "performance", "http");
    private static final Set<String> ENABLED_DISABLED = Set.of("ENABLED", "DISABLED");
    private static final Set<String> PLATFORMS = Set.of("Web", "Android", "iOS");
    private static final Set<String> DESTINATIONS = Set.of("CloudWatch", "Evidently");
    private static final Pattern ARN_PATTERN = Pattern.compile(".*arn:[^:]*:[^:]*:[^:]*:[^:]*:.*");
    private static final int POLICY_SIZE_LIMIT = 4096;
    private static final int MAX_METRIC_DEFINITIONS_PER_BATCH = 200;
    private static final int MAX_METRIC_DEFINITIONS_PER_DESTINATION = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageBackend<String, AppMonitor> monitorStore;
    private final StorageBackend<String, RumEventLog> eventStore;
    private final StorageBackend<String, ResourcePolicy> policyStore;
    private final StorageBackend<String, MetricDestination> destinationStore;

    @Inject
    public RumService(StorageFactory storageFactory) {
        this(
                storageFactory.create(
                        "rum",
                        "rum-app-monitors.json",
                        new TypeReference<Map<String, AppMonitor>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-events.json",
                        new TypeReference<Map<String, RumEventLog>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-resource-policies.json",
                        new TypeReference<Map<String, ResourcePolicy>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-metric-destinations.json",
                        new TypeReference<Map<String, MetricDestination>>() {
                        }));
    }

    RumService(StorageBackend<String, AppMonitor> monitorStore) {
        this(monitorStore, new InMemoryStorage<>());
    }

    RumService(StorageBackend<String, AppMonitor> monitorStore, StorageBackend<String, RumEventLog> eventStore) {
        this(monitorStore, eventStore, new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    RumService(
            StorageBackend<String, AppMonitor> monitorStore,
            StorageBackend<String, RumEventLog> eventStore,
            StorageBackend<String, ResourcePolicy> policyStore,
            StorageBackend<String, MetricDestination> destinationStore) {
        this.monitorStore = monitorStore;
        this.eventStore = eventStore;
        this.policyStore = policyStore;
        this.destinationStore = destinationStore;
    }

    public synchronized AppMonitor createAppMonitor(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name);
        DomainSelection domains = readDomains(request);
        JsonNode configuration = readConfiguration(request);
        JsonNode customEvents = readCustomEvents(request);
        JsonNode deobfuscation = readDeobfuscationConfiguration(request);
        boolean cwLogEnabled = request.has("CwLogEnabled")
                ? requireBoolean(request, "CwLogEnabled")
                : false;
        Map<String, String> tags = readTags(request);
        String platform = readPlatform(request);

        String key = storageKey(region, name);
        if (monitorStore.get(key).isPresent()) {
            throw resourceConflict(name);
        }

        String now = timestamp();
        AppMonitor monitor = new AppMonitor(
                UUID.randomUUID().toString(),
                name,
                domains.domain(),
                domains.domainList(),
                "CREATED",
                platform,
                now,
                now,
                tags,
                configuration,
                dataStorage(cwLogEnabled),
                customEvents,
                deobfuscation);
        monitorStore.put(key, monitor);
        return monitor;
    }

    public AppMonitor getAppMonitor(String region, String name) {
        validateName(name);
        return monitorStore.get(storageKey(region, name)).orElseThrow(() -> resourceNotFound(name));
    }

    public synchronized void updateAppMonitor(String region, String name, JsonNode request) {
        validateName(name);
        requireObject(request, "Request body");
        String key = storageKey(region, name);
        AppMonitor current = monitorStore.get(key).orElseThrow(() -> resourceNotFound(name));

        boolean domainChanged = request.has("Domain") || request.has("DomainList");
        DomainSelection domains = domainChanged
                ? readDomains(request)
                : new DomainSelection(current.getDomain(), current.getDomainList());
        JsonNode configuration = request.has("AppMonitorConfiguration")
                ? mergeObjects(current.getAppMonitorConfiguration(), readConfiguration(request))
                : current.getAppMonitorConfiguration();
        JsonNode customEvents = request.has("CustomEvents")
                ? mergeObjects(current.getCustomEvents(), readCustomEvents(request))
                : current.getCustomEvents();
        JsonNode deobfuscation = request.has("DeobfuscationConfiguration")
                ? mergeObjects(current.getDeobfuscationConfiguration(), readDeobfuscationConfiguration(request))
                : current.getDeobfuscationConfiguration();
        JsonNode storage = request.has("CwLogEnabled")
                ? dataStorage(requireBoolean(request, "CwLogEnabled"))
                : current.getDataStorage();

        boolean changed = domainChanged
                || request.has("AppMonitorConfiguration")
                || request.has("CustomEvents")
                || request.has("DeobfuscationConfiguration")
                || request.has("CwLogEnabled");
        if (!changed) {
            return;
        }

        AppMonitor updated = new AppMonitor(
                current.getId(),
                current.getName(),
                domains.domain(),
                domains.domainList(),
                current.getState(),
                current.getPlatform(),
                current.getCreated(),
                timestamp(),
                current.getTags(),
                configuration,
                storage,
                customEvents,
                deobfuscation);
        monitorStore.put(key, updated);
    }

    public synchronized void deleteAppMonitor(String region, String name) {
        validateName(name);
        String key = storageKey(region, name);
        AppMonitor current = monitorStore.get(key).orElseThrow(() -> resourceNotFound(name));
        String destinationPrefix = region + "::" + name + "::";
        for (MetricDestination destination : destinationStore.scan(k -> k.startsWith(destinationPrefix))) {
            destinationStore.delete(destinationStorageKey(
                    region, name, destination.getDestination(), destination.getDestinationArn()));
        }
        policyStore.delete(key);
        monitorStore.delete(key);
        eventStore.delete(eventKey(region, current.getId()));
    }

    public synchronized void putRumEvents(String region, String id, JsonNode request) {
        requireObject(request, "Request body");
        AppMonitor monitor = requireMonitorById(region, id);
        String batchId = requireText(request, "BatchId");
        validateUuid(batchId, "BatchId");
        requireObject(request.get("AppMonitorDetails"), "AppMonitorDetails");
        JsonNode userDetails = request.get("UserDetails");
        requireObject(userDetails, "UserDetails");
        JsonNode rumEvents = request.get("RumEvents");
        if (rumEvents == null || !rumEvents.isArray()) {
            throw validation("RumEvents must be an array.");
        }
        String alias = null;
        if (request.has("Alias")) {
            alias = requireText(request, "Alias");
            if (alias.length() < 1 || alias.length() > 255) {
                throw validation("Alias must contain between 1 and 255 characters.");
            }
        }
        String userId = optionalText(userDetails, "userId");
        String sessionId = optionalText(userDetails, "sessionId");

        String key = eventKey(region, monitor.getId());
        RumEventLog log = eventStore.get(key).orElseGet(RumEventLog::new);
        List<StoredRumEvent> events = new ArrayList<>(log.getEvents());
        for (JsonNode event : rumEvents) {
            requireObject(event, "RumEvents members");
            StoredRumEvent stored = new StoredRumEvent();
            String eventId = requireText(event, "id");
            validateUuid(eventId, "RumEvents.id");
            stored.setId(eventId);
            stored.setTimestampMillis(readTimestampMillis(event.get("timestamp"), "RumEvents.timestamp"));
            stored.setType(requireText(event, "type"));
            stored.setDetails(requireText(event, "details"));
            if (event.hasNonNull("metadata") && event.get("metadata").isTextual()) {
                stored.setMetadata(event.get("metadata").textValue());
            }
            stored.setBatchId(batchId);
            stored.setUserId(userId);
            stored.setSessionId(sessionId);
            stored.setAlias(alias);
            events.add(stored);
        }
        log.setEvents(events);
        eventStore.put(key, log);
    }

    public synchronized EventPage getAppMonitorData(String region, String name, JsonNode request) {
        AppMonitor monitor = getAppMonitor(region, name);
        requireObject(request, "Request body");
        JsonNode timeRange = request.get("TimeRange");
        requireObject(timeRange, "TimeRange");
        long after = readTimeRangeBound(timeRange, "After");
        Long before = timeRange.has("Before") ? readTimeRangeBound(timeRange, "Before") : null;
        int maxResults = request.has("MaxResults")
                ? parseBodyMaxResults(request.get("MaxResults"))
                : DEFAULT_MAX_RESULTS;
        String nextToken = request.has("NextToken") ? requireText(request, "NextToken") : null;
        List<QueryFilter> filters = readFilters(request);

        RumEventLog log = eventStore.get(eventKey(region, monitor.getId())).orElseGet(RumEventLog::new);
        List<StoredRumEvent> matched = new ArrayList<>();
        for (StoredRumEvent event : log.getEvents()) {
            if (event.getTimestampMillis() < after) {
                continue;
            }
            if (before != null && event.getTimestampMillis() > before) {
                continue;
            }
            if (!matchesFilters(event, filters)) {
                continue;
            }
            matched.add(event);
        }
        matched.sort(Comparator.comparingLong(StoredRumEvent::getTimestampMillis)
                .thenComparing(StoredRumEvent::getId, Comparator.nullsLast(String::compareTo)));

        int offset = decodeOffset(nextToken, matched.size(), DATA_TOKEN_PREFIX);
        int end = Math.min(offset + maxResults, matched.size());
        String responseToken = end < matched.size() ? encodeOffset(end, DATA_TOKEN_PREFIX) : null;
        List<String> events = new ArrayList<>(end - offset);
        for (int i = offset; i < end; i++) {
            events.add(toEventData(matched.get(i)));
        }
        return new EventPage(events, responseToken);
    }

    public Page listAppMonitors(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AppMonitor> monitors = monitorStore.scan(key -> key.startsWith(region + "::"));
        monitors.sort(Comparator.comparing(AppMonitor::getName));

        int offset = decodeOffset(nextToken, monitors.size());
        int end = Math.min(offset + maxResults, monitors.size());
        String responseToken = end < monitors.size() ? encodeOffset(end) : null;
        return new Page(monitors.subList(offset, end), responseToken);
    }

    @Override
    public String serviceKey() {
        return "rum";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(copyTags(requireMonitorByArn(region, arn).getTags()));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        AppMonitor monitor = requireMonitorByArn(region, arn);
        Map<String, String> current = copyTags(monitor.getTags());
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                validateTag(entry.getKey(), entry.getValue());
                current.put(entry.getKey(), entry.getValue());
            }
        }
        if (current.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        persistTags(region, monitor, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        AppMonitor monitor = requireMonitorByArn(region, arn);
        Map<String, String> current = copyTags(monitor.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTags(region, monitor, current);
    }

    public ResourcePolicy getResourcePolicy(String region, String name) {
        requireMonitor(region, name);
        return policyStore.get(storageKey(region, name)).orElseThrow(RumService::policyNotFound);
    }

    public synchronized ResourcePolicy putResourcePolicy(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        requireMonitor(region, name);
        String document = requireText(request, "PolicyDocument");
        validatePolicyDocument(document);
        String requestedRevision = optionalText(request, "PolicyRevisionId");
        if (requestedRevision != null && (requestedRevision.isBlank() || requestedRevision.length() > 255)) {
            throw validation("PolicyRevisionId must contain between 1 and 255 characters.");
        }
        String key = storageKey(region, name);
        ResourcePolicy current = policyStore.get(key).orElse(null);
        if (requestedRevision != null
                && (current == null || !requestedRevision.equals(current.getPolicyRevisionId()))) {
            throw invalidPolicyRevision();
        }
        ResourcePolicy policy = new ResourcePolicy(document, UUID.randomUUID().toString());
        policyStore.put(key, policy);
        return policy;
    }

    public synchronized ResourcePolicy deleteResourcePolicy(
            String region, String name, String policyRevisionId) {
        requireMonitor(region, name);
        String key = storageKey(region, name);
        ResourcePolicy current = policyStore.get(key).orElseThrow(RumService::policyNotFound);
        if (policyRevisionId != null && !policyRevisionId.equals(current.getPolicyRevisionId())) {
            throw invalidPolicyRevision();
        }
        policyStore.delete(key);
        return current;
    }

    public synchronized void putRumMetricsDestination(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        requireMonitor(region, name);
        DestinationKey destination = readDestination(request);
        String key = destinationStorageKey(region, name, destination.destination(), destination.destinationArn());
        MetricDestination stored = destinationStore.get(key).orElseGet(MetricDestination::new);
        stored.setDestination(destination.destination());
        stored.setDestinationArn(destination.destinationArn());
        if (request.has("IamRoleArn") && !request.get("IamRoleArn").isNull()) {
            stored.setIamRoleArn(requireArn(request, "IamRoleArn"));
        }
        if ("Evidently".equals(destination.destination())
                && (stored.getIamRoleArn() == null || stored.getIamRoleArn().isBlank())) {
            throw validation("IamRoleArn is required when Destination is Evidently.");
        }
        destinationStore.put(key, stored);
    }

    public DestinationPage listRumMetricsDestinations(
            String region, String name, String maxResultsValue, String nextToken) {
        requireMonitor(region, name);
        int maxResults = parseMaxResults(maxResultsValue);
        List<MetricDestination> destinations = destinationStore.scan(
                key -> key.startsWith(region + "::" + name + "::"));
        destinations.sort(Comparator
                .comparing(MetricDestination::getDestination)
                .thenComparing(destination -> destination.getDestinationArn() == null
                        ? ""
                        : destination.getDestinationArn()));
        int offset = decodeOffset(nextToken, destinations.size());
        int end = Math.min(offset + maxResults, destinations.size());
        String responseToken = end < destinations.size() ? encodeOffset(end) : null;
        return new DestinationPage(destinations.subList(offset, end), responseToken);
    }

    public synchronized void deleteRumMetricsDestination(
            String region, String name, String destination, String destinationArn) {
        requireMonitor(region, name);
        DestinationKey key = destinationKey(destination, destinationArn);
        String storageKey = destinationStorageKey(region, name, key.destination(), key.destinationArn());
        if (destinationStore.get(storageKey).isEmpty()) {
            throw destinationNotFound(name);
        }
        destinationStore.delete(storageKey);
    }

    public synchronized ObjectNode batchCreateRumMetricDefinitions(
            String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        requireMonitor(region, name);
        DestinationKey destination = readDestination(request);
        MetricDestination stored = requireDestination(region, name, destination);
        JsonNode definitionsNode = request.get("MetricDefinitions");
        if (definitionsNode == null || !definitionsNode.isArray()) {
            throw validation("MetricDefinitions must be an array.");
        }
        if (definitionsNode.size() > MAX_METRIC_DEFINITIONS_PER_BATCH) {
            throw validation("The maximum number of metric definitions that you can specify is 200.");
        }

        List<MetricDefinition> existing = new ArrayList<>(stored.getMetricDefinitions());
        Map<String, MetricDefinition> byName = new LinkedHashMap<>();
        for (MetricDefinition definition : existing) {
            byName.put(definition.getName(), definition);
        }

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode errors = response.putArray("Errors");
        ArrayNode created = response.putArray("MetricDefinitions");
        int added = 0;
        for (JsonNode item : definitionsNode) {
            try {
                requireObject(item, "MetricDefinitions members");
                MetricDefinition definition = readMetricDefinition(item, true);
                if (byName.containsKey(definition.getName())) {
                    throw new AwsException(
                            "ConflictException",
                            "Metric definition " + definition.getName() + " already exists.",
                            409);
                }
                if (existing.size() + added + 1 > MAX_METRIC_DEFINITIONS_PER_DESTINATION) {
                    throw new AwsException(
                            "ServiceQuotaExceededException",
                            "The maximum number of metric definitions that one destination can contain is 2000.",
                            402);
                }
                existing.add(definition);
                byName.put(definition.getName(), definition);
                created.add(JSON.valueToTree(definition));
                added++;
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.set("MetricDefinition", item.deepCopy());
                error.put("ErrorCode", e.getErrorCode());
                error.put("ErrorMessage", e.getMessage());
            }
        }
        stored.setMetricDefinitions(existing);
        destinationStore.put(
                destinationStorageKey(region, name, destination.destination(), destination.destinationArn()),
                stored);
        return response;
    }

    public DefinitionPage batchGetRumMetricDefinitions(
            String region,
            String name,
            String destination,
            String destinationArn,
            String maxResultsValue,
            String nextToken) {
        requireMonitor(region, name);
        DestinationKey key = destinationKey(destination, destinationArn);
        MetricDestination stored = destinationStore
                .get(destinationStorageKey(region, name, key.destination(), key.destinationArn()))
                .orElse(null);
        List<MetricDefinition> definitions = stored == null
                ? List.of()
                : new ArrayList<>(stored.getMetricDefinitions());
        definitions.sort(Comparator.comparing(MetricDefinition::getName));
        int maxResults = parseMaxResults(maxResultsValue);
        int offset = decodeOffset(nextToken, definitions.size());
        int end = Math.min(offset + maxResults, definitions.size());
        String responseToken = end < definitions.size() ? encodeOffset(end) : null;
        return new DefinitionPage(definitions.subList(offset, end), responseToken);
    }

    public synchronized void updateRumMetricDefinition(String region, String name, JsonNode request) {
        requireObject(request, "Request body");
        requireMonitor(region, name);
        DestinationKey destination = readDestination(request);
        MetricDestination stored = requireDestination(region, name, destination);
        String definitionId = requireText(request, "MetricDefinitionId");
        if (definitionId.length() < 1 || definitionId.length() > 255) {
            throw validation("MetricDefinitionId must contain between 1 and 255 characters.");
        }
        JsonNode definitionNode = request.get("MetricDefinition");
        requireObject(definitionNode, "MetricDefinition");
        MetricDefinition updated = readMetricDefinition(definitionNode, false);

        List<MetricDefinition> definitions = new ArrayList<>(stored.getMetricDefinitions());
        int index = -1;
        for (int i = 0; i < definitions.size(); i++) {
            if (definitionId.equals(definitions.get(i).getMetricDefinitionId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw definitionNotFound(definitionId);
        }
        for (int i = 0; i < definitions.size(); i++) {
            if (i != index && updated.getName().equals(definitions.get(i).getName())) {
                throw new AwsException(
                        "ConflictException",
                        "Metric definition " + updated.getName() + " already exists.",
                        409,
                        Map.of("resourceName", updated.getName(), "resourceType", "MetricDefinition"));
            }
        }
        updated.setMetricDefinitionId(definitionId);
        definitions.set(index, updated);
        stored.setMetricDefinitions(definitions);
        destinationStore.put(
                destinationStorageKey(region, name, destination.destination(), destination.destinationArn()),
                stored);
    }

    public synchronized ObjectNode batchDeleteRumMetricDefinitions(
            String region,
            String name,
            String destination,
            String destinationArn,
            List<String> metricDefinitionIds) {
        requireMonitor(region, name);
        DestinationKey key = destinationKey(destination, destinationArn);
        MetricDestination stored = requireDestination(region, name, key);
        if (metricDefinitionIds == null || metricDefinitionIds.isEmpty()) {
            throw validation("metricDefinitionIds is required.");
        }
        if (metricDefinitionIds.size() > MAX_METRIC_DEFINITIONS_PER_BATCH) {
            throw validation("The maximum number of metric definitions that you can specify is 200.");
        }

        Map<String, MetricDefinition> byId = new LinkedHashMap<>();
        for (MetricDefinition definition : stored.getMetricDefinitions()) {
            byId.put(definition.getMetricDefinitionId(), definition);
        }

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode errors = response.putArray("Errors");
        ArrayNode deleted = response.putArray("MetricDefinitionIds");
        for (String id : metricDefinitionIds) {
            if (id == null || id.isBlank() || id.length() > 255) {
                ObjectNode error = errors.addObject();
                error.put("MetricDefinitionId", id == null ? "" : id);
                error.put("ErrorCode", "ValidationException");
                error.put("ErrorMessage", "MetricDefinitionId must contain between 1 and 255 characters.");
                continue;
            }
            if (byId.remove(id) == null) {
                ObjectNode error = errors.addObject();
                error.put("MetricDefinitionId", id);
                error.put("ErrorCode", "ResourceNotFoundException");
                error.put("ErrorMessage", "Metric definition " + id + " does not exist.");
                continue;
            }
            deleted.add(id);
        }
        stored.setMetricDefinitions(new ArrayList<>(byId.values()));
        destinationStore.put(
                destinationStorageKey(region, name, key.destination(), key.destinationArn()),
                stored);
        return response;
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String timestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.now());
    }

    private static DomainSelection readDomains(JsonNode request) {
        boolean hasDomain = request.has("Domain");
        boolean hasDomainList = request.has("DomainList");
        if (hasDomain == hasDomainList) {
            throw validation("Specify exactly one of Domain or DomainList.");
        }
        if (hasDomain) {
            String domain = requireText(request, "Domain");
            validateDomain(domain, "Domain");
            return new DomainSelection(domain, null);
        }

        JsonNode node = request.get("DomainList");
        if (node == null || !node.isArray() || node.size() < 1 || node.size() > 5) {
            throw validation("DomainList must contain between 1 and 5 domains.");
        }
        List<String> domains = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode domainNode = node.get(i);
            if (!domainNode.isTextual()) {
                throw validation("DomainList members must be strings.");
            }
            String domain = domainNode.textValue();
            validateDomain(domain, "DomainList");
            domains.add(domain);
        }
        return new DomainSelection(null, domains);
    }

    private static void validateName(String name) {
        if (name == null || name.length() < 1 || name.length() > 255 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("Name must match (?!\\.)[.\\-_#A-Za-z0-9]+ and contain at most 255 characters.");
        }
    }

    private static void validateDomain(String domain, String field) {
        if (domain == null || domain.length() < 1 || domain.length() > 253
                || !DOMAIN_PATTERN.matcher(domain).matches()) {
            throw validation(field + " contains an invalid domain.");
        }
    }

    private static JsonNode readConfiguration(JsonNode request) {
        JsonNode configuration = optionalObject(request, "AppMonitorConfiguration");
        if (configuration == null) {
            return null;
        }
        requireOptionalBoolean(configuration, "AllowCookies");
        requireOptionalBoolean(configuration, "EnableXRay");
        requireOptionalText(configuration, "GuestRoleArn");
        requireOptionalText(configuration, "IdentityPoolId");
        if (configuration.has("SessionSampleRate")) {
            JsonNode rate = configuration.get("SessionSampleRate");
            if (!rate.isNumber() || rate.doubleValue() < 0 || rate.doubleValue() > 1) {
                throw validation("SessionSampleRate must be between 0 and 1.");
            }
        }
        if (configuration.has("ExcludedPages") && configuration.has("IncludedPages")) {
            throw validation("ExcludedPages and IncludedPages cannot both be specified.");
        }
        validateStringArray(configuration, "ExcludedPages", 50, null);
        validateStringArray(configuration, "IncludedPages", 50, null);
        validateStringArray(configuration, "FavoritePages", 50, null);
        validateStringArray(configuration, "Telemetries", Integer.MAX_VALUE, TELEMETRIES);
        return configuration.deepCopy();
    }

    private static JsonNode readCustomEvents(JsonNode request) {
        JsonNode customEvents = optionalObject(request, "CustomEvents");
        if (customEvents == null) {
            return null;
        }
        if (customEvents.has("Status")) {
            String status = requireText(customEvents, "Status");
            if (!ENABLED_DISABLED.contains(status)) {
                throw validation("CustomEvents.Status must be ENABLED or DISABLED.");
            }
        }
        return customEvents.deepCopy();
    }

    private static JsonNode readDeobfuscationConfiguration(JsonNode request) {
        JsonNode configuration = optionalObject(request, "DeobfuscationConfiguration");
        if (configuration == null) {
            return null;
        }
        if (configuration.has("JavaScriptSourceMaps")) {
            JsonNode sourceMaps = configuration.get("JavaScriptSourceMaps");
            requireObject(sourceMaps, "JavaScriptSourceMaps");
            String status = requireText(sourceMaps, "Status");
            if (!ENABLED_DISABLED.contains(status)) {
                throw validation("JavaScriptSourceMaps.Status must be ENABLED or DISABLED.");
            }
            if (sourceMaps.has("S3Uri")) {
                String s3Uri = requireText(sourceMaps, "S3Uri");
                if (s3Uri.length() > 1024 || !S3_URI_PATTERN.matcher(s3Uri).matches()) {
                    throw validation("JavaScriptSourceMaps.S3Uri is invalid.");
                }
            } else if ("ENABLED".equals(status)) {
                throw validation("JavaScriptSourceMaps.S3Uri is required when Status is ENABLED.");
            }
        }
        return configuration.deepCopy();
    }

    private static String readPlatform(JsonNode request) {
        if (!request.has("Platform")) {
            return "Web";
        }
        String platform = requireText(request, "Platform");
        if (!PLATFORMS.contains(platform)) {
            throw validation("Platform must be Web, Android, or iOS.");
        }
        return platform;
    }

    private static JsonNode mergeObjects(JsonNode current, JsonNode update) {
        ObjectNode merged = current != null && current.isObject()
                ? (ObjectNode) current.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        update.fields().forEachRemaining(entry -> {
            JsonNode existingValue = merged.get(entry.getKey());
            JsonNode updateValue = entry.getValue();
            if (existingValue != null && existingValue.isObject() && updateValue.isObject()) {
                merged.set(entry.getKey(), mergeObjects(existingValue, updateValue));
            } else {
                merged.set(entry.getKey(), updateValue.deepCopy());
            }
        });
        return merged;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("Tags")) {
            return null;
        }
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isTextual()) {
                throw validation("Tags contains an invalid key or value.");
            }
            validateTag(entry.getKey(), valueNode.textValue());
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private void persistTags(String region, AppMonitor monitor, Map<String, String> tags) {
        AppMonitor updated = new AppMonitor(
                monitor.getId(),
                monitor.getName(),
                monitor.getDomain(),
                monitor.getDomainList(),
                monitor.getState(),
                monitor.getPlatform(),
                monitor.getCreated(),
                timestamp(),
                tags,
                monitor.getAppMonitorConfiguration(),
                monitor.getDataStorage(),
                monitor.getCustomEvents(),
                monitor.getDeobfuscationConfiguration());
        monitorStore.put(storageKey(region, monitor.getName()), updated);
    }

    private AppMonitor requireMonitorByArn(String region, String arn) {
        return getAppMonitor(region, monitorNameFromArn(arn));
    }

    static String monitorNameFromArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"rum".equals(parsed.service())) {
                throw resourceNotFound(arn);
            }
            String resource = parsed.resource();
            if (resource == null || !resource.startsWith("appmonitor/")) {
                throw resourceNotFound(arn);
            }
            String name = resource.substring("appmonitor/".length());
            if (name.isBlank()) {
                throw resourceNotFound(arn);
            }
            return name;
        } catch (IllegalArgumentException e) {
            throw resourceNotFound(arn);
        }
    }

    private static Map<String, String> copyTags(Map<String, String> tags) {
        return tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    private static void validateTag(String key, String value) {
        if (key == null || key.length() < 1 || key.length() > 128 || !TAG_KEY_PATTERN.matcher(key).matches()
                || value == null || value.length() > 256) {
            throw validation("Tags contains an invalid key or value.");
        }
    }

    private static ObjectNode dataStorage(boolean enabled) {
        ObjectNode cwLog = JsonNodeFactory.instance.objectNode();
        cwLog.put("CwLogEnabled", enabled);
        ObjectNode dataStorage = JsonNodeFactory.instance.objectNode();
        dataStorage.set("CwLog", cwLog);
        return dataStorage;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (!parent.has(field)) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static void requireOptionalBoolean(JsonNode parent, String field) {
        if (parent.has(field)) {
            requireBoolean(parent, field);
        }
    }

    private static void requireOptionalText(JsonNode parent, String field) {
        if (parent.has(field)) {
            requireText(parent, field);
        }
    }

    private static void validateStringArray(JsonNode parent, String field, int maxSize, Set<String> values) {
        if (!parent.has(field)) {
            return;
        }
        JsonNode array = parent.get(field);
        if (!array.isArray() || array.size() > maxSize) {
            throw validation(field + " must be an array with at most " + maxSize + " entries.");
        }
        for (JsonNode value : array) {
            if (!value.isTextual() || (values != null && !values.contains(value.textValue()))) {
                throw validation(field + " contains an invalid value.");
            }
        }
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        return decodeOffset(token, resultSize, TOKEN_PREFIX);
    }

    private static int decodeOffset(String token, int resultSize, String prefix) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(prefix)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(prefix.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return encodeOffset(offset, TOKEN_PREFIX);
    }

    private static String encodeOffset(int offset, String prefix) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((prefix + offset).getBytes(StandardCharsets.UTF_8));
    }

    private AppMonitor requireMonitorById(String region, String id) {
        validateUuid(id, "Id");
        return monitorStore.scan(key -> key.startsWith(region + "::")).stream()
                .filter(monitor -> id.equals(monitor.getId()))
                .findFirst()
                .orElseThrow(() -> resourceNotFound(id));
    }

    private static String eventKey(String region, String monitorId) {
        return region + "::id::" + monitorId;
    }

    private static void validateUuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw validation(field + " must be a UUID.");
        }
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static long readTimestampMillis(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw validation(field + " is required.");
        }
        if (!node.isNumber()) {
            throw validation(field + " must be a Unix timestamp.");
        }
        double value = node.doubleValue();
        return value > MILLIS_THRESHOLD ? (long) value : (long) (value * 1000d);
    }

    private static long readTimeRangeBound(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw validation("TimeRange." + field + " is required.");
        }
        if (!node.isNumber()) {
            throw validation("TimeRange." + field + " must be a Unix timestamp.");
        }
        double value = node.doubleValue();
        return value > MILLIS_THRESHOLD ? (long) value : (long) (value * 1000d);
    }

    private static int parseBodyMaxResults(JsonNode node) {
        if (node == null || !node.isNumber() || !node.canConvertToInt()) {
            throw validation("MaxResults must be an integer between 1 and 100.");
        }
        int parsed = node.intValue();
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        return parsed;
    }

    private static List<QueryFilter> readFilters(JsonNode request) {
        if (!request.has("Filters")) {
            return List.of();
        }
        JsonNode filters = request.get("Filters");
        if (filters == null || !filters.isArray()) {
            throw validation("Filters must be an array.");
        }
        List<QueryFilter> result = new ArrayList<>(filters.size());
        for (JsonNode filter : filters) {
            requireObject(filter, "Filters members");
            String name = requireText(filter, "Name");
            JsonNode valuesNode = filter.get("Values");
            if (valuesNode == null || !valuesNode.isArray()) {
                throw validation("Filters.Values must be an array.");
            }
            List<String> values = new ArrayList<>(valuesNode.size());
            for (JsonNode value : valuesNode) {
                if (!value.isTextual()) {
                    throw validation("Filters.Values members must be strings.");
                }
                values.add(value.textValue());
            }
            result.add(new QueryFilter(name, values));
        }
        return result;
    }

    private static boolean matchesFilters(StoredRumEvent event, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if ("EventType".equals(filter.name()) && !filter.values().isEmpty()
                    && !filter.values().contains(event.getType())) {
                return false;
            }
        }
        return true;
    }

    private static String toEventData(StoredRumEvent event) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", event.getId());
        node.put("timestamp", event.getTimestampMillis() / 1000d);
        node.put("type", event.getType());
        node.put("details", event.getDetails());
        if (event.getMetadata() != null) {
            node.put("metadata", event.getMetadata());
        }
        return node.toString();
    }

    private AppMonitor requireMonitor(String region, String name) {
        return getAppMonitor(region, name);
    }

    private MetricDestination requireDestination(String region, String name, DestinationKey destination) {
        return destinationStore
                .get(destinationStorageKey(region, name, destination.destination(), destination.destinationArn()))
                .orElseThrow(() -> destinationNotFound(name));
    }

    private static DestinationKey readDestination(JsonNode request) {
        return destinationKey(requireText(request, "Destination"), optionalText(request, "DestinationArn"));
    }

    private static DestinationKey destinationKey(String destination, String destinationArn) {
        if (destination == null || !DESTINATIONS.contains(destination)) {
            throw validation("Destination must be CloudWatch or Evidently.");
        }
        if ("CloudWatch".equals(destination)) {
            if (destinationArn != null && !destinationArn.isBlank()) {
                throw validation("DestinationArn must not be set when Destination is CloudWatch.");
            }
            return new DestinationKey("CloudWatch", null);
        }
        if (destinationArn == null || destinationArn.isBlank()) {
            throw validation("DestinationArn is required when Destination is Evidently.");
        }
        if (destinationArn.length() > 2048 || !ARN_PATTERN.matcher(destinationArn).matches()) {
            throw validation("DestinationArn is invalid.");
        }
        return new DestinationKey("Evidently", destinationArn);
    }

    private static String destinationStorageKey(
            String region, String name, String destination, String destinationArn) {
        if ("Evidently".equals(destination)) {
            return region + "::" + name + "::Evidently::" + destinationArn;
        }
        return region + "::" + name + "::CloudWatch";
    }

    private static String requireArn(JsonNode parent, String field) {
        String arn = requireText(parent, field);
        if (!ARN_PATTERN.matcher(arn).matches()) {
            throw validation(field + " is invalid.");
        }
        return arn;
    }

    private MetricDefinition readMetricDefinition(JsonNode node, boolean assignId) {
        MetricDefinition definition = new MetricDefinition();
        String name = requireText(node, "Name");
        if (name.length() < 1 || name.length() > 255) {
            throw validation("Name must contain between 1 and 255 characters.");
        }
        definition.setName(name);
        if (assignId) {
            definition.setMetricDefinitionId(UUID.randomUUID().toString());
        }
        if (node.has("ValueKey") && !node.get("ValueKey").isNull()) {
            String valueKey = requireText(node, "ValueKey");
            if (valueKey.length() < 1 || valueKey.length() > 280) {
                throw validation("ValueKey must contain between 1 and 280 characters.");
            }
            definition.setValueKey(valueKey);
        }
        if (node.has("UnitLabel") && !node.get("UnitLabel").isNull()) {
            String unit = requireText(node, "UnitLabel");
            if (unit.length() < 1 || unit.length() > 256) {
                throw validation("UnitLabel must contain between 1 and 256 characters.");
            }
            definition.setUnitLabel(unit);
        }
        if (node.has("EventPattern") && !node.get("EventPattern").isNull()) {
            String pattern = requireText(node, "EventPattern");
            if (pattern.length() > 4000) {
                throw validation("EventPattern must contain at most 4000 characters.");
            }
            try {
                JsonNode parsed = JSON.readTree(pattern);
                if (parsed == null || !parsed.isObject()) {
                    throw validation("EventPattern must be a JSON object.");
                }
            } catch (AwsException e) {
                throw e;
            } catch (Exception e) {
                throw validation("EventPattern is not valid JSON.");
            }
            definition.setEventPattern(pattern);
        }
        if (node.has("Namespace") && !node.get("Namespace").isNull()) {
            String namespace = requireText(node, "Namespace");
            if (namespace.length() < 1 || namespace.length() > 237) {
                throw validation("Namespace must contain between 1 and 237 characters.");
            }
            if (namespace.startsWith("AWS/") && !"AWS/RUM".equals(namespace)) {
                throw validation("Namespace cannot start with AWS/.");
            }
            if (!"AWS/RUM".equals(namespace)) {
                definition.setNamespace(namespace);
            }
        }
        if (node.has("DimensionKeys") && !node.get("DimensionKeys").isNull()) {
            definition.setDimensionKeys(readDimensionKeys(node.get("DimensionKeys")));
        }
        return definition;
    }

    private static Map<String, String> readDimensionKeys(JsonNode node) {
        if (node == null || !node.isObject() || node.size() > 29) {
            throw validation("DimensionKeys must be an object with at most 29 entries.");
        }
        Map<String, String> keys = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() == null || !entry.getValue().isTextual()) {
                throw validation("DimensionKeys values must be strings.");
            }
            String key = entry.getKey();
            String value = entry.getValue().textValue();
            if (key.isEmpty() || key.length() > 280 || value.isEmpty() || value.length() > 255) {
                throw validation("DimensionKeys contains an invalid key or value.");
            }
            keys.put(key, value);
        });
        return keys;
    }

    private static void validatePolicyDocument(String document) {
        if (document.getBytes(StandardCharsets.UTF_8).length > POLICY_SIZE_LIMIT) {
            throw new AwsException(
                    "PolicySizeLimitExceededException",
                    "The policy document is too large. The limit is 4 KB.",
                    400);
        }
        try {
            JsonNode parsed = JSON.readTree(document);
            if (parsed == null || !parsed.isObject()) {
                throw malformedPolicy();
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw malformedPolicy();
        }
    }

    private static AwsException policyNotFound() {
        return new AwsException(
                "PolicyNotFoundException",
                "The resource-based policy doesn't exist on this app monitor.",
                404);
    }

    private static AwsException invalidPolicyRevision() {
        return new AwsException(
                "InvalidPolicyRevisionIdException",
                "The policy revision ID that you provided doesn't match the latest policy revision ID.",
                400);
    }

    private static AwsException malformedPolicy() {
        return new AwsException(
                "MalformedPolicyDocumentException",
                "The policy document that you specified is not formatted correctly.",
                400);
    }

    private static AwsException destinationNotFound(String name) {
        return new AwsException(
                "ResourceNotFoundException",
                "Metrics destination does not exist on app monitor " + name + ".",
                404,
                Map.of("resourceName", name, "resourceType", "RumMetricsDestination"));
    }

    private static AwsException definitionNotFound(String id) {
        return new AwsException(
                "ResourceNotFoundException",
                "Metric definition " + id + " does not exist.",
                404,
                Map.of("resourceName", id, "resourceType", "MetricDefinition"));
    }

    private static AwsException resourceConflict(String name) {
        return new AwsException(
                "ConflictException",
                "App monitor " + name + " already exists.",
                409,
                Map.of("resourceName", name, "resourceType", "AppMonitor"));
    }

    private static AwsException resourceNotFound(String name) {
        return new AwsException(
                "ResourceNotFoundException",
                "App monitor " + name + " does not exist.",
                404,
                Map.of("resourceName", name, "resourceType", "AppMonitor"));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page(List<AppMonitor> monitors, String nextToken) {
        public Page {
            monitors = List.copyOf(monitors);
        }
    }

    public record EventPage(List<String> events, String nextToken) {
        public EventPage {
            events = List.copyOf(events);
        }
    }

    public record DestinationPage(List<MetricDestination> destinations, String nextToken) {
        public DestinationPage {
            destinations = List.copyOf(destinations);
        }
    }

    public record DefinitionPage(List<MetricDefinition> definitions, String nextToken) {
        public DefinitionPage {
            definitions = List.copyOf(definitions);
        }
    }

    private record DestinationKey(String destination, String destinationArn) {
    }

    private record DomainSelection(String domain, List<String> domainList) {
    }

    private record QueryFilter(String name, List<String> values) {
        private QueryFilter {
            values = List.copyOf(values);
        }
    }
}
