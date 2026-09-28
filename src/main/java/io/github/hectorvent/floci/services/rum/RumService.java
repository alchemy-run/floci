package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import io.github.hectorvent.floci.services.rum.model.RumEventLog;
import io.github.hectorvent.floci.services.rum.model.RumMetricDefinition;
import io.github.hectorvent.floci.services.rum.model.RumMetricsDestination;
import io.github.hectorvent.floci.services.rum.model.RumResourcePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudWatch RUM backed by the configured Floci storage mode: app monitors and their tags,
 * extended-metrics destinations and metric definitions, resource-based policies, and the
 * telemetry events ingested by the PutRumEvents data plane and read back by GetAppMonitorData.
 */
@ApplicationScoped
public class RumService implements ResourceProvider {

    private static final String SERVICE = "rum";
    private static final String RESOURCE_APP_MONITOR = "appmonitor";

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "rum:v1:";
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
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern ARN_PATTERN = Pattern.compile(".*arn:[^:]*:[^:]*:[^:]*:[^:]*:.*");
    private static final int MAX_TAGS = 50;
    private static final int MAX_DEFINITIONS_PER_BATCH = 200;
    private static final int MAX_DEFINITIONS_PER_DESTINATION = 2000;
    private static final int MAX_POLICY_BYTES = 4096;
    private static final int MAX_QUERY_RESULTS = 100;
    // RUM keeps collected telemetry for 30 days.
    private static final Duration EVENT_RETENTION = Duration.ofDays(30);
    // Bounds emulator memory; the oldest events of a monitor are evicted first.
    static final int MAX_STORED_EVENTS_PER_MONITOR = 10_000;
    private static final Set<String> POLICY_VERSIONS = Set.of("2012-10-17", "2008-10-17");
    private static final Set<String> QUERY_FILTER_NAMES =
            Set.of("Browser", "Device", "Country", "Page", "OS", "EventType", "Invert");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, AppMonitor> monitorStore;
    private final StorageBackend<String, RumMetricsDestination> destinationStore;
    private final StorageBackend<String, RumResourcePolicy> policyStore;
    private final StorageBackend<String, RumEventLog> eventStore;
    private final RegionResolver regionResolver;

    @Inject
    public RumService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                        "rum",
                        "rum-app-monitors.json",
                        new TypeReference<Map<String, AppMonitor>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-metrics-destinations.json",
                        new TypeReference<Map<String, RumMetricsDestination>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-resource-policies.json",
                        new TypeReference<Map<String, RumResourcePolicy>>() {
                        }),
                storageFactory.create(
                        "rum",
                        "rum-events.json",
                        new TypeReference<Map<String, RumEventLog>>() {
                        }),
                regionResolver);
    }

    RumService(StorageBackend<String, AppMonitor> monitorStore, RegionResolver regionResolver) {
        this(monitorStore, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver);
    }

    RumService(StorageBackend<String, AppMonitor> monitorStore,
               StorageBackend<String, RumMetricsDestination> destinationStore,
               StorageBackend<String, RumResourcePolicy> policyStore,
               StorageBackend<String, RumEventLog> eventStore,
               RegionResolver regionResolver) {
        this.monitorStore = monitorStore;
        this.destinationStore = destinationStore;
        this.policyStore = policyStore;
        this.eventStore = eventStore;
        this.regionResolver = regionResolver;
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
        monitor.setOwnerAccountId(regionResolver.getAccountId());
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
        updated.setOwnerAccountId(current.getOwnerAccountId());
        monitorStore.put(key, updated);
    }

    public synchronized void deleteAppMonitor(String region, String name) {
        validateName(name);
        String key = storageKey(region, name);
        AppMonitor monitor = monitorStore.get(key).orElseThrow(() -> resourceNotFound(name));
        monitorStore.delete(key);
        // The monitor's destinations, definitions, policy and collected data go with it.
        String destinationPrefix = key + "::";
        for (String destinationKey : new ArrayList<>(destinationStore.keys())) {
            if (destinationKey.startsWith(destinationPrefix)) {
                destinationStore.delete(destinationKey);
            }
        }
        policyStore.delete(key);
        eventStore.delete(eventKey(region, monitor.getId()));
    }

    // ---------------------------------------------------------------- tags

    public Map<String, String> listTagsForResource(String region, String resourceArn) {
        AppMonitor monitor = monitorForArn(region, resourceArn);
        return monitor.getTags() == null ? Map.of() : new LinkedHashMap<>(monitor.getTags());
    }

    public synchronized void tagResource(String region, String resourceArn, Map<String, String> tags) {
        AppMonitor current = monitorForArn(region, resourceArn);
        if (tags == null || tags.isEmpty()) {
            throw validation("Tags must contain at least one tag.");
        }
        tags.forEach(RumService::validateTag);
        Map<String, String> merged = current.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current.getTags());
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw validation("An app monitor can have at most " + MAX_TAGS + " tags.");
        }
        monitorStore.put(storageKey(region, current.getName()), withTags(current, merged));
    }

    public synchronized void untagResource(String region, String resourceArn, List<String> tagKeys) {
        AppMonitor current = monitorForArn(region, resourceArn);
        if (tagKeys == null || tagKeys.size() > MAX_TAGS) {
            throw validation("TagKeys must contain at most " + MAX_TAGS + " keys.");
        }
        for (String tagKey : tagKeys) {
            if (tagKey == null || tagKey.isEmpty() || tagKey.length() > 128
                    || !TAG_KEY_PATTERN.matcher(tagKey).matches()) {
                throw validation("TagKeys contains an invalid key.");
            }
        }
        if (current.getTags() == null || tagKeys.isEmpty()) {
            return;
        }
        Map<String, String> remaining = new LinkedHashMap<>(current.getTags());
        tagKeys.forEach(remaining::remove);
        monitorStore.put(storageKey(region, current.getName()), withTags(current, remaining));
    }

    private AppMonitor monitorForArn(String region, String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("ResourceArn " + resourceArn + " is not a valid ARN.");
        }
        String prefix = RESOURCE_APP_MONITOR + "/";
        if (!SERVICE.equals(arn.service()) || !arn.resource().startsWith(prefix)) {
            throw validation("ResourceArn " + resourceArn + " is not a CloudWatch RUM app monitor ARN.");
        }
        String name = arn.resource().substring(prefix.length());
        validateName(name);
        if (!region.equals(arn.region()) || !regionResolver.getAccountId().equals(arn.accountId())) {
            throw resourceNotFound(name);
        }
        return monitorStore.get(storageKey(region, name)).orElseThrow(() -> resourceNotFound(name));
    }

    private static AppMonitor withTags(AppMonitor current, Map<String, String> tags) {
        AppMonitor updated = new AppMonitor(
                current.getId(),
                current.getName(),
                current.getDomain(),
                current.getDomainList(),
                current.getState(),
                current.getPlatform(),
                current.getCreated(),
                current.getLastModified(),
                tags,
                current.getAppMonitorConfiguration(),
                current.getDataStorage(),
                current.getCustomEvents(),
                current.getDeobfuscationConfiguration());
        updated.setOwnerAccountId(current.getOwnerAccountId());
        return updated;
    }

    // ------------------------------------------------- metrics destinations

    public synchronized void putRumMetricsDestination(String region, String monitorName, JsonNode request) {
        validateName(monitorName);
        requireObject(request, "Request body");
        String destination = requireText(request, "Destination");
        String destinationArn = optionalText(request, "DestinationArn");
        String iamRoleArn = optionalText(request, "IamRoleArn");
        validateDestinationKey(destination, destinationArn);
        if (RumMetricDefinitionValidator.EVIDENTLY.equals(destination)) {
            requireArn(destinationArn, "DestinationArn", "evidently");
            if (!evidentlyExperiment(destinationArn)) {
                throw validation("DestinationArn must be the ARN of a CloudWatch Evidently experiment.");
            }
            if (iamRoleArn == null) {
                throw validation("IamRoleArn is required when Destination is Evidently.");
            }
            requireArn(iamRoleArn, "IamRoleArn", "iam");
            if (!AwsArnUtils.parse(iamRoleArn).resource().startsWith("role/")) {
                throw validation("IamRoleArn must be the ARN of an IAM role.");
            }
        } else if (iamRoleArn != null) {
            throw validation("IamRoleArn can only be specified when Destination is Evidently.");
        }
        requireMonitor(region, monitorName);

        String key = destinationKey(region, monitorName, destination, destinationArn);
        List<RumMetricDefinition> definitions = destinationStore.get(key)
                .map(RumMetricsDestination::getMetricDefinitions)
                .orElseGet(List::of);
        destinationStore.put(key, new RumMetricsDestination(
                monitorName, destination, destinationArn, iamRoleArn, definitions));
    }

    public Slice<RumMetricsDestination> listRumMetricsDestinations(
            String region, String monitorName, String maxResults, String nextToken) {
        validateName(monitorName);
        int limit = parseMaxResults(maxResults);
        requireMonitor(region, monitorName);
        String prefix = storageKey(region, monitorName) + "::";
        List<RumMetricsDestination> destinations = destinationStore.scan(key -> key.startsWith(prefix));
        destinations.sort(Comparator.comparing(RumMetricsDestination::getDestination)
                .thenComparing(d -> d.getDestinationArn() == null ? "" : d.getDestinationArn()));
        return slice(destinations, limit, nextToken);
    }

    public synchronized void deleteRumMetricsDestination(
            String region, String monitorName, String destination, String destinationArn) {
        validateName(monitorName);
        validateDestinationKey(destination, destinationArn);
        requireMonitor(region, monitorName);
        String key = destinationKey(region, monitorName, destination, destinationArn);
        if (destinationStore.get(key).isEmpty()) {
            throw destinationNotFound(destination, destinationArn);
        }
        destinationStore.delete(key);
    }

    public synchronized BatchCreateResult batchCreateRumMetricDefinitions(
            String region, String monitorName, JsonNode request) {
        validateName(monitorName);
        requireObject(request, "Request body");
        String destination = requireText(request, "Destination");
        String destinationArn = optionalText(request, "DestinationArn");
        validateDestinationKey(destination, destinationArn);
        JsonNode definitionsNode = request.get("MetricDefinitions");
        if (definitionsNode == null || !definitionsNode.isArray()) {
            throw validation("MetricDefinitions must be a list.");
        }
        if (definitionsNode.isEmpty() || definitionsNode.size() > MAX_DEFINITIONS_PER_BATCH) {
            throw validation("MetricDefinitions must contain between 1 and " + MAX_DEFINITIONS_PER_BATCH
                    + " definitions.");
        }
        List<RumMetricDefinition> requested = new ArrayList<>(definitionsNode.size());
        for (int i = 0; i < definitionsNode.size(); i++) {
            requested.add(RumMetricDefinitionValidator.parse(definitionsNode.get(i), "MetricDefinitions." + (i + 1)));
        }
        RumMetricsDestination current = requireDestination(region, monitorName, destination, destinationArn);

        List<RumMetricDefinition> stored = current.getMetricDefinitions();
        List<RumMetricDefinition> created = new ArrayList<>();
        List<BatchCreateError> errors = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            RumMetricDefinition definition = requested.get(i);
            JsonNode echo = definitionsNode.get(i).deepCopy();
            String problem = RumMetricDefinitionValidator.semanticError(definition, destination);
            if (problem != null) {
                errors.add(new BatchCreateError(echo, "ValidationException", problem));
            } else if (stored.stream().anyMatch(definition::sameDefinitionAs)) {
                errors.add(new BatchCreateError(echo, "ConflictException",
                        "An identical metric definition already exists for this destination."));
            } else if (stored.size() >= MAX_DEFINITIONS_PER_DESTINATION) {
                errors.add(new BatchCreateError(echo, "ServiceQuotaExceededException",
                        "A destination can contain at most " + MAX_DEFINITIONS_PER_DESTINATION
                                + " metric definitions."));
            } else {
                RumMetricDefinition saved = definition.withId(UUID.randomUUID().toString());
                stored.add(saved);
                created.add(saved);
            }
        }
        if (!created.isEmpty()) {
            current.setMetricDefinitions(stored);
            destinationStore.put(destinationKey(region, monitorName, destination, destinationArn), current);
        }
        return new BatchCreateResult(errors, created);
    }

    public synchronized BatchDeleteResult batchDeleteRumMetricDefinitions(
            String region, String monitorName, String destination, String destinationArn,
            List<String> metricDefinitionIds) {
        validateName(monitorName);
        validateDestinationKey(destination, destinationArn);
        if (metricDefinitionIds == null || metricDefinitionIds.isEmpty()
                || metricDefinitionIds.size() > MAX_DEFINITIONS_PER_BATCH) {
            throw validation("metricDefinitionIds must contain between 1 and " + MAX_DEFINITIONS_PER_BATCH
                    + " ids.");
        }
        RumMetricsDestination current = requireDestination(region, monitorName, destination, destinationArn);
        List<RumMetricDefinition> stored = current.getMetricDefinitions();
        List<String> deleted = new ArrayList<>();
        List<BatchDeleteError> errors = new ArrayList<>();
        for (String id : metricDefinitionIds) {
            boolean removed = stored.removeIf(d -> d.getMetricDefinitionId().equals(id));
            if (removed) {
                deleted.add(id);
            } else {
                errors.add(new BatchDeleteError(id, "ResourceNotFoundException",
                        "Metric definition " + id + " does not exist."));
            }
        }
        if (!deleted.isEmpty()) {
            current.setMetricDefinitions(stored);
            destinationStore.put(destinationKey(region, monitorName, destination, destinationArn), current);
        }
        return new BatchDeleteResult(errors, deleted);
    }

    public Slice<RumMetricDefinition> batchGetRumMetricDefinitions(
            String region, String monitorName, String destination, String destinationArn,
            String maxResults, String nextToken) {
        validateName(monitorName);
        validateDestinationKey(destination, destinationArn);
        int limit = parseMaxResults(maxResults);
        RumMetricsDestination current = requireDestination(region, monitorName, destination, destinationArn);
        return slice(current.getMetricDefinitions(), limit, nextToken);
    }

    public synchronized void updateRumMetricDefinition(String region, String monitorName, JsonNode request) {
        validateName(monitorName);
        requireObject(request, "Request body");
        String destination = requireText(request, "Destination");
        String destinationArn = optionalText(request, "DestinationArn");
        validateDestinationKey(destination, destinationArn);
        String id = requireText(request, "MetricDefinitionId");
        RumMetricDefinition definition = RumMetricDefinitionValidator.parse(
                request.get("MetricDefinition"), "MetricDefinition");
        RumMetricsDestination current = requireDestination(region, monitorName, destination, destinationArn);
        List<RumMetricDefinition> stored = current.getMetricDefinitions();
        int index = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).getMetricDefinitionId().equals(id)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new AwsException("ResourceNotFoundException",
                    "Metric definition " + id + " does not exist.", 404,
                    Map.of("resourceName", id, "resourceType", "MetricDefinition"));
        }
        String problem = RumMetricDefinitionValidator.semanticError(definition, destination);
        if (problem != null) {
            throw validation(problem);
        }
        for (RumMetricDefinition other : stored) {
            if (!other.getMetricDefinitionId().equals(id) && other.sameDefinitionAs(definition)) {
                throw new AwsException("ConflictException",
                        "An identical metric definition already exists for this destination.", 409,
                        Map.of("resourceName", other.getMetricDefinitionId(), "resourceType", "MetricDefinition"));
            }
        }
        stored.set(index, definition.withId(id));
        current.setMetricDefinitions(stored);
        destinationStore.put(destinationKey(region, monitorName, destination, destinationArn), current);
    }

    private RumMetricsDestination requireDestination(
            String region, String monitorName, String destination, String destinationArn) {
        requireMonitor(region, monitorName);
        return destinationStore.get(destinationKey(region, monitorName, destination, destinationArn))
                .orElseThrow(() -> destinationNotFound(destination, destinationArn));
    }

    private static void validateDestinationKey(String destination, String destinationArn) {
        if (!RumMetricDefinitionValidator.CLOUDWATCH.equals(destination)
                && !RumMetricDefinitionValidator.EVIDENTLY.equals(destination)) {
            throw validation("Destination must be CloudWatch or Evidently.");
        }
        if (destinationArn != null && (destinationArn.length() > 2048
                || !ARN_PATTERN.matcher(destinationArn).matches())) {
            throw validation("DestinationArn is not a valid ARN.");
        }
        if (RumMetricDefinitionValidator.EVIDENTLY.equals(destination) && destinationArn == null) {
            throw validation("DestinationArn is required when Destination is Evidently.");
        }
        if (RumMetricDefinitionValidator.CLOUDWATCH.equals(destination) && destinationArn != null) {
            throw validation("DestinationArn can only be specified when Destination is Evidently.");
        }
    }

    private static void requireArn(String value, String field, String service) {
        try {
            if (!service.equals(AwsArnUtils.parse(value).service())) {
                throw validation(field + " must be an ARN for service " + service + ".");
            }
        } catch (IllegalArgumentException e) {
            throw validation(field + " is not a valid ARN.");
        }
    }

    private static boolean evidentlyExperiment(String arn) {
        String[] segments = AwsArnUtils.parse(arn).resource().split("/");
        return segments.length == 4 && "project".equals(segments[0]) && !segments[1].isEmpty()
                && "experiment".equals(segments[2]) && !segments[3].isEmpty();
    }

    private static String destinationKey(String region, String monitorName, String destination,
                                         String destinationArn) {
        return storageKey(region, monitorName) + "::" + destination + "::"
                + (destinationArn == null ? "" : destinationArn);
    }

    private static AwsException destinationNotFound(String destination, String destinationArn) {
        String name = destinationArn == null ? destination : destinationArn;
        return new AwsException("ResourceNotFoundException",
                "Metrics destination " + name + " does not exist.", 404,
                Map.of("resourceName", name, "resourceType", "RumMetricsDestination"));
    }

    // -------------------------------------------------- resource policies

    public synchronized RumResourcePolicy putResourcePolicy(String region, String monitorName, JsonNode request) {
        validateName(monitorName);
        requireObject(request, "Request body");
        String document = requireText(request, "PolicyDocument");
        String revisionId = optionalText(request, "PolicyRevisionId");
        validateRevisionId(revisionId);
        requireMonitor(region, monitorName);
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_POLICY_BYTES) {
            throw new AwsException("PolicySizeLimitExceededException",
                    "The policy document is too large. The limit is 4 KB.", 400);
        }
        validatePolicyDocument(document);

        String key = storageKey(region, monitorName);
        Optional<RumResourcePolicy> existing = policyStore.get(key);
        if (revisionId != null && (existing.isEmpty()
                || !revisionId.equals(existing.get().getPolicyRevisionId()))) {
            throw invalidRevision();
        }
        RumResourcePolicy policy = new RumResourcePolicy(document, UUID.randomUUID().toString());
        policyStore.put(key, policy);
        return policy;
    }

    public RumResourcePolicy getResourcePolicy(String region, String monitorName) {
        validateName(monitorName);
        requireMonitor(region, monitorName);
        return policyStore.get(storageKey(region, monitorName)).orElseThrow(() -> policyNotFound(monitorName));
    }

    public synchronized RumResourcePolicy deleteResourcePolicy(
            String region, String monitorName, String revisionId) {
        validateName(monitorName);
        validateRevisionId(revisionId);
        requireMonitor(region, monitorName);
        String key = storageKey(region, monitorName);
        RumResourcePolicy existing = policyStore.get(key).orElseThrow(() -> policyNotFound(monitorName));
        if (revisionId != null && !revisionId.equals(existing.getPolicyRevisionId())) {
            throw invalidRevision();
        }
        policyStore.delete(key);
        return existing;
    }

    private static void validateRevisionId(String revisionId) {
        if (revisionId != null && (revisionId.isEmpty() || revisionId.length() > 255)) {
            throw validation("PolicyRevisionId must contain between 1 and 255 characters.");
        }
    }

    private static void validatePolicyDocument(String document) {
        JsonNode policy;
        try {
            policy = MAPPER.readTree(document);
        } catch (Exception e) {
            throw malformedPolicy("The policy document is not valid JSON.");
        }
        if (policy == null || !policy.isObject()) {
            throw malformedPolicy("The policy document must be a JSON object.");
        }
        JsonNode version = policy.get("Version");
        if (version != null && (!version.isTextual() || !POLICY_VERSIONS.contains(version.textValue()))) {
            throw malformedPolicy("The policy document has an unsupported Version.");
        }
        JsonNode statements = policy.get("Statement");
        if (statements == null || (!statements.isObject() && !statements.isArray())
                || (statements.isArray() && statements.isEmpty())) {
            throw malformedPolicy("The policy document must contain at least one Statement.");
        }
        List<JsonNode> statementList = new ArrayList<>();
        if (statements.isArray()) {
            statements.forEach(statementList::add);
        } else {
            statementList.add(statements);
        }
        for (JsonNode statement : statementList) {
            if (!statement.isObject()) {
                throw malformedPolicy("Each policy Statement must be a JSON object.");
            }
            JsonNode effect = statement.get("Effect");
            if (effect == null || !effect.isTextual()
                    || !("Allow".equals(effect.textValue()) || "Deny".equals(effect.textValue()))) {
                throw malformedPolicy("Each policy Statement must have an Effect of Allow or Deny.");
            }
            if (!statement.has("Principal") && !statement.has("NotPrincipal")) {
                throw malformedPolicy("Each policy Statement must specify a Principal.");
            }
            if (!statement.has("Resource") && !statement.has("NotResource")) {
                throw malformedPolicy("Each policy Statement must specify a Resource.");
            }
            JsonNode actions = statement.has("Action") ? statement.get("Action") : statement.get("NotAction");
            List<JsonNode> actionList = new ArrayList<>();
            if (actions != null && actions.isArray()) {
                actions.forEach(actionList::add);
            } else if (actions != null) {
                actionList.add(actions);
            }
            if (actionList.isEmpty()) {
                throw malformedPolicy("Each policy Statement must specify an Action.");
            }
            for (JsonNode action : actionList) {
                if (!action.isTextual() || !"rum:putrumevents".equals(action.textValue().toLowerCase(Locale.ROOT))) {
                    throw malformedPolicy("CloudWatch RUM resource-based policies only support the "
                            + "rum:PutRumEvents action.");
                }
            }
        }
    }

    private static AwsException malformedPolicy(String message) {
        return new AwsException("MalformedPolicyDocumentException", message, 400);
    }

    private static AwsException invalidRevision() {
        return new AwsException("InvalidPolicyRevisionIdException",
                "The policy revision ID that you provided doesn't match the latest policy revision ID.", 400);
    }

    private static AwsException policyNotFound(String monitorName) {
        return new AwsException("PolicyNotFoundException",
                "App monitor " + monitorName + " does not have a resource-based policy.", 404);
    }

    // ------------------------------------------------- telemetry data plane

    public synchronized void putRumEvents(String region, String id, JsonNode request) {
        requireUuid(id, "Id");
        requireObject(request, "Request body");
        String batchId = requireText(request, "BatchId");
        requireUuid(batchId, "BatchId");
        JsonNode details = request.get("AppMonitorDetails");
        requireObject(details, "AppMonitorDetails");
        String detailsId = optionalText(details, "id");
        String version = optionalText(details, "version");
        optionalText(details, "name");
        if (detailsId != null && !detailsId.equals(id)) {
            throw validation("AppMonitorDetails.id does not match the app monitor Id.");
        }
        JsonNode userDetails = request.get("UserDetails");
        requireObject(userDetails, "UserDetails");
        String userId = optionalText(userDetails, "userId");
        String sessionId = optionalText(userDetails, "sessionId");
        if (userId != null) {
            requireUuid(userId, "UserDetails.userId");
        }
        if (sessionId != null) {
            requireUuid(sessionId, "UserDetails.sessionId");
        }
        String alias = optionalText(request, "Alias");
        if (alias != null && (alias.isEmpty() || alias.length() > 255)) {
            throw validation("Alias must contain between 1 and 255 characters.");
        }
        JsonNode events = request.get("RumEvents");
        if (events == null || !events.isArray()) {
            throw validation("RumEvents must be a list.");
        }

        AppMonitor monitor = monitorStore.scan(key -> key.startsWith(region + "::")).stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "App monitor " + id + " does not exist.", 404,
                        Map.of("resourceName", id, "resourceType", "AppMonitor")));
        EventContext context = new EventContext(batchId, id, monitor.getName(), version, userId, sessionId);
        List<RumEventLog.Event> rendered = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            rendered.add(renderEvent(events.get(i), "RumEvents." + (i + 1), context));
        }
        if (rendered.isEmpty()) {
            return;
        }

        String key = eventKey(region, id);
        List<RumEventLog.Event> stored = eventStore.get(key).map(RumEventLog::getEvents).orElseGet(ArrayList::new);
        long oldestRetained = Instant.now().minus(EVENT_RETENTION).toEpochMilli();
        stored.removeIf(event -> event.getTimestampMillis() < oldestRetained);
        stored.addAll(rendered);
        if (stored.size() > MAX_STORED_EVENTS_PER_MONITOR) {
            stored = new ArrayList<>(stored.subList(stored.size() - MAX_STORED_EVENTS_PER_MONITOR, stored.size()));
        }
        eventStore.put(key, new RumEventLog(stored));
    }

    public Slice<String> getAppMonitorData(String region, String monitorName, JsonNode request) {
        validateName(monitorName);
        requireObject(request, "Request body");
        JsonNode timeRange = request.get("TimeRange");
        requireObject(timeRange, "TimeRange");
        JsonNode afterNode = timeRange.get("After");
        if (afterNode == null || !afterNode.isNumber()) {
            throw validation("TimeRange.After must be a timestamp in epoch milliseconds.");
        }
        long after = afterNode.longValue();
        long before = Instant.now().toEpochMilli();
        JsonNode beforeNode = timeRange.get("Before");
        if (beforeNode != null && !beforeNode.isNull()) {
            if (!beforeNode.isNumber()) {
                throw validation("TimeRange.Before must be a timestamp in epoch milliseconds.");
            }
            before = beforeNode.longValue();
        }
        if (after > before) {
            throw validation("TimeRange.After must not be later than TimeRange.Before.");
        }
        List<QueryFilter> filters = readQueryFilters(request.get("Filters"));
        int limit = MAX_QUERY_RESULTS;
        JsonNode maxResults = request.get("MaxResults");
        if (maxResults != null && !maxResults.isNull()) {
            if (!maxResults.canConvertToInt() || !maxResults.isIntegralNumber()
                    || maxResults.intValue() < 0 || maxResults.intValue() > MAX_QUERY_RESULTS) {
                throw validation("MaxResults must be between 0 and " + MAX_QUERY_RESULTS + ".");
            }
            if (maxResults.intValue() > 0) {
                limit = maxResults.intValue();
            }
        }
        String nextToken = optionalText(request, "NextToken");

        AppMonitor monitor = getAppMonitor(region, monitorName);
        long oldestRetained = Instant.now().minus(EVENT_RETENTION).toEpochMilli();
        long from = Math.max(after, oldestRetained);
        long to = before;
        List<RumEventLog.Event> matching = new ArrayList<>(eventStore.get(eventKey(region, monitor.getId()))
                .map(RumEventLog::getEvents)
                .orElseGet(List::of));
        matching.removeIf(event -> event.getTimestampMillis() < from || event.getTimestampMillis() > to
                || !matchesFilters(parseObject(event.getDocument()), filters));
        matching.sort(Comparator.comparingLong(RumEventLog.Event::getTimestampMillis));
        return slice(matching.stream().map(RumEventLog.Event::getDocument).toList(), limit, nextToken);
    }

    private record EventContext(String batchId, String appMonitorId, String appMonitorName, String version,
                                String userId, String sessionId) {
    }

    /** Renders an ingested event in the RUM event document format GetAppMonitorData returns. */
    private static RumEventLog.Event renderEvent(JsonNode event, String field, EventContext context) {
        requireObject(event, field);
        String eventId = requireText(event, "id");
        requireUuid(eventId, field + ".id");
        JsonNode timestamp = event.get("timestamp");
        if (timestamp == null || !timestamp.isNumber()) {
            throw validation(field + ".timestamp must be a timestamp in epoch seconds.");
        }
        long timestampMillis = timestamp.decimalValue().movePointRight(3)
                .setScale(0, RoundingMode.HALF_UP).longValue();
        String type = requireText(event, "type");
        if (type.isEmpty()) {
            throw validation(field + ".type must not be empty.");
        }
        JsonNode eventDetails = parseJsonValue(requireText(event, "details"), field + ".details");
        String metadataText = optionalText(event, "metadata");
        JsonNode metadata = metadataText == null ? null : parseJsonValue(metadataText, field + ".metadata");

        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("event_timestamp", timestampMillis);
        document.put("event_type", type);
        document.put("event_id", eventId);
        document.put("batch_id", context.batchId());
        document.put("application_id", context.appMonitorId());
        document.put("application_name", context.appMonitorName());
        if (context.version() != null) {
            document.put("application_version", context.version());
        }
        if (metadata != null) {
            document.set("metadata", metadata);
        }
        if (context.userId() != null || context.sessionId() != null) {
            ObjectNode user = document.putObject("user_details");
            if (context.userId() != null) {
                user.put("userId", context.userId());
            }
            if (context.sessionId() != null) {
                user.put("sessionId", context.sessionId());
            }
        }
        document.set("event_details", eventDetails);
        return new RumEventLog.Event(timestampMillis, document.toString());
    }

    private record QueryFilter(String name, Set<String> values) {
    }

    private static List<QueryFilter> readQueryFilters(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw validation("Filters must be a list.");
        }
        List<QueryFilter> filters = new ArrayList<>();
        for (JsonNode filter : node) {
            requireObject(filter, "Filters member");
            String name = optionalText(filter, "Name");
            if (name != null && !QUERY_FILTER_NAMES.contains(name)) {
                throw validation("Filter Name must be one of Browser, Device, Country, Page, OS, EventType, "
                        + "or Invert.");
            }
            JsonNode values = filter.get("Values");
            Set<String> valueSet = new HashSet<>();
            if (values != null && !values.isNull()) {
                if (!values.isArray()) {
                    throw validation("Filter Values must be a list of strings.");
                }
                for (JsonNode value : values) {
                    if (!value.isTextual()) {
                        throw validation("Filter Values must be a list of strings.");
                    }
                    valueSet.add(value.textValue());
                }
            }
            if (name != null && !valueSet.isEmpty()) {
                filters.add(new QueryFilter(name, valueSet));
            }
        }
        return filters;
    }

    private static boolean matchesFilters(JsonNode event, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if ("Invert".equals(filter.name())) {
                for (String attribute : List.of("Browser", "Device", "Country", "Page", "OS", "EventType")) {
                    String value = filterAttribute(event, attribute);
                    if (value != null && filter.values().contains(value)) {
                        return false;
                    }
                }
            } else {
                String value = filterAttribute(event, filter.name());
                if (value == null || !filter.values().contains(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String filterAttribute(JsonNode event, String filterName) {
        JsonNode value = switch (filterName) {
            case "Browser" -> event.path("metadata").get("browserName");
            case "Device" -> event.path("metadata").get("deviceType");
            case "Country" -> event.path("metadata").get("countryCode");
            case "Page" -> event.path("metadata").get("pageId");
            case "OS" -> event.path("metadata").get("osName");
            case "EventType" -> event.get("event_type");
            default -> null;
        };
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static JsonNode parseJsonValue(String text, String field) {
        try {
            JsonNode value = MAPPER.readTree(text);
            if (value == null || value.isMissingNode()) {
                throw validation(field + " must be a JSON document.");
            }
            return value;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation(field + " must be a JSON document.");
        }
    }

    private static ObjectNode parseObject(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored RUM event is not a JSON object", e);
        }
    }

    private static void requireUuid(String value, String field) {
        if (value == null || value.length() != 36 || !UUID_PATTERN.matcher(value).matches()) {
            throw validation(field + " must be a 36-character UUID.");
        }
    }

    private static String eventKey(String region, String appMonitorId) {
        return region + "::" + appMonitorId;
    }

    // ------------------------------------------------------------- shared

    private void requireMonitor(String region, String monitorName) {
        if (monitorStore.get(storageKey(region, monitorName)).isEmpty()) {
            throw resourceNotFound(monitorName);
        }
    }

    private static <T> Slice<T> slice(List<T> items, int limit, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + limit, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Slice<>(items.subList(offset, end), responseToken);
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
    public List<ExplorerResource> getResources() {
        // RUM's stored model carries neither an ARN nor a region (its GetAppMonitor response has no
        // such fields), so the region is recovered from the storage key and the ARN is rebuilt here.
        // The owning account is the one captured when the monitor was created, not the account of the
        // caller listing resources. Monitors reloaded from disk carry no owner account (the field is
        // transient), but monitorStore.keys() is already scoped to the calling account by the
        // account-aware backend, so a monitor reachable here is owned by that caller by construction.
        List<ExplorerResource> resources = new ArrayList<>();
        for (String key : monitorStore.keys()) {
            AppMonitor monitor = monitorStore.get(key).orElse(null);
            if (monitor == null) {
                continue;
            }
            String region = regionFromKey(key);
            String accountId = monitor.getOwnerAccountId() != null
                    ? monitor.getOwnerAccountId()
                    : regionResolver.getAccountId();
            String arn = AwsArnUtils.Arn.of(SERVICE, region, accountId,
                    RESOURCE_APP_MONITOR + "/" + monitor.getName()).toString();
            resources.add(new ExplorerResource(
                    arn, SERVICE + ":" + RESOURCE_APP_MONITOR, SERVICE,
                    region, accountId,
                    reportedAt(monitor.getCreated()),
                    monitor.getTags() != null ? monitor.getTags() : Map.of()));
        }
        return resources;
    }

    private static String regionFromKey(String key) {
        int separator = key.indexOf("::");
        return separator > 0 ? key.substring(0, separator) : key;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType(
                SERVICE + ":" + RESOURCE_APP_MONITOR, SERVICE, true));
    }

    private static Instant reportedAt(String created) {
        if (created == null) {
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(created, TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return Instant.now();
        }
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
            if (!valueNode.isTextual()) {
                throw validation("Tags contains an invalid key or value.");
            }
            validateTag(entry.getKey(), valueNode.textValue());
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
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

    public record Slice<T>(List<T> items, String nextToken) {
        public Slice {
            items = List.copyOf(items);
        }
    }

    public record BatchCreateError(JsonNode metricDefinition, String errorCode, String errorMessage) {
    }

    public record BatchCreateResult(List<BatchCreateError> errors, List<RumMetricDefinition> metricDefinitions) {
        public BatchCreateResult {
            errors = List.copyOf(errors);
            metricDefinitions = List.copyOf(metricDefinitions);
        }
    }

    public record BatchDeleteError(String metricDefinitionId, String errorCode, String errorMessage) {
    }

    public record BatchDeleteResult(List<BatchDeleteError> errors, List<String> metricDefinitionIds) {
        public BatchDeleteResult {
            errors = List.copyOf(errors);
            metricDefinitionIds = List.copyOf(metricDefinitionIds);
        }
    }

    private record DomainSelection(String domain, List<String> domainList) {
    }
}
