package io.github.hectorvent.floci.services.applicationsignals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.applicationsignals.model.AccountSignalsState;
import io.github.hectorvent.floci.services.applicationsignals.model.GroupingAttributeDefinition;
import io.github.hectorvent.floci.services.applicationsignals.model.InstrumentationConfiguration;
import io.github.hectorvent.floci.services.applicationsignals.model.ServiceLevelObjective;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CloudWatch Application Signals restJson1 — SLOs, grouping, discovery,
 * and instrumentation-configuration lifecycle.
 *
 * <p>Instrumentation configurations are immutable after create. Identity is
 * {@code InstrumentationType + Service + Environment + SignalType + location hash}.
 * The location hash is a stable 16-character hex digest of the code location.
 */
@ApplicationScoped
public class ApplicationSignalsService implements TagHandler {

    static final String SERVICE = "application-signals";
    private static final String RESOURCE_TYPE = "instrumentationConfig";
    private static final String RESOURCE_SLO = "ServiceLevelObjective";
    private static final Set<String> INSTRUMENTATION_TYPES = Set.of("PROBE", "BREAKPOINT");
    private static final Set<String> SIGNAL_TYPES = Set.of("SNAPSHOT");
    private static final long BREAKPOINT_DEFAULT_TTL_SECONDS = 24 * 3600L;
    private static final Pattern SLO_NAME_PATTERN = Pattern.compile("[0-9A-Za-z][-._A-Za-z0-9 ]{0,126}");

    private final StorageBackend<String, AccountSignalsState> accountStore;
    private final StorageBackend<String, InstrumentationConfiguration> configurationStore;
    private final StorageBackend<String, ServiceLevelObjective> sloStore;
    private final RegionResolver regionResolver;

    @Inject
    public ApplicationSignalsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        "applicationsignals",
                        "application-signals-account.json",
                        new TypeReference<Map<String, AccountSignalsState>>() {
                        }),
                storageFactory.create(
                        "applicationsignals",
                        "application-signals-instrumentation-configurations.json",
                        new TypeReference<Map<String, InstrumentationConfiguration>>() {
                        }),
                storageFactory.create(
                        "applicationsignals",
                        "application-signals-slos.json",
                        new TypeReference<Map<String, ServiceLevelObjective>>() {
                        }),
                regionResolver);
    }

    ApplicationSignalsService(
            StorageBackend<String, AccountSignalsState> accountStore,
            StorageBackend<String, InstrumentationConfiguration> configurationStore,
            RegionResolver regionResolver) {
        this(accountStore, configurationStore, new io.github.hectorvent.floci.core.storage.InMemoryStorage<>(),
                regionResolver);
    }

    ApplicationSignalsService(
            StorageBackend<String, AccountSignalsState> accountStore,
            StorageBackend<String, InstrumentationConfiguration> configurationStore,
            StorageBackend<String, ServiceLevelObjective> sloStore,
            RegionResolver regionResolver) {
        this.accountStore = accountStore;
        this.configurationStore = configurationStore;
        this.sloStore = sloStore;
        this.regionResolver = regionResolver;
    }

    public synchronized AccountSignalsState putGroupingConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode definitionsNode = request.get("GroupingAttributeDefinitions");
        if (definitionsNode == null || !definitionsNode.isArray()) {
            throw validation("GroupingAttributeDefinitions is required.");
        }
        List<GroupingAttributeDefinition> definitions = new ArrayList<>(definitionsNode.size());
        for (JsonNode entry : definitionsNode) {
            requireObject(entry, "GroupingAttributeDefinitions members");
            GroupingAttributeDefinition definition = new GroupingAttributeDefinition();
            definition.setGroupingName(requireBoundedText(entry, "GroupingName", 1, 255));
            if (entry.has("GroupingSourceKeys") && !entry.get("GroupingSourceKeys").isNull()) {
                JsonNode keys = entry.get("GroupingSourceKeys");
                if (!keys.isArray()) {
                    throw validation("GroupingSourceKeys must be an array.");
                }
                List<String> sourceKeys = new ArrayList<>(keys.size());
                for (JsonNode key : keys) {
                    if (!key.isTextual()) {
                        throw validation("GroupingSourceKeys members must be strings.");
                    }
                    sourceKeys.add(key.textValue());
                }
                definition.setGroupingSourceKeys(sourceKeys);
            }
            if (entry.has("DefaultGroupingValue") && entry.get("DefaultGroupingValue").isTextual()) {
                definition.setDefaultGroupingValue(entry.get("DefaultGroupingValue").textValue());
            }
            definitions.add(definition);
        }
        AccountSignalsState state = loadAccount(region);
        state.setGroupingAttributeDefinitions(definitions);
        state.setGroupingUpdatedAt(Instant.now().getEpochSecond());
        accountStore.put(region, state);
        return state;
    }

    public AccountSignalsState listGroupingAttributeDefinitions(String region) {
        return accountStore.get(region).orElseGet(AccountSignalsState::new);
    }

    public synchronized void deleteGroupingConfiguration(String region) {
        AccountSignalsState state = loadAccount(region);
        state.setGroupingAttributeDefinitions(null);
        state.setGroupingUpdatedAt(null);
        accountStore.put(region, state);
    }

    public synchronized void startDiscovery(String region) {
        AccountSignalsState state = loadAccount(region);
        state.setDiscoveryEnabled(true);
        accountStore.put(region, state);
    }

    public synchronized ServiceLevelObjective createSlo(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireBoundedText(request, "Name", 1, 127);
        if (!SLO_NAME_PATTERN.matcher(name).matches()) {
            throw validation("Name must match [0-9A-Za-z][-._A-Za-z0-9 ]*.");
        }
        String key = sloKey(region, name);
        if (sloStore.get(key).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "A service level objective named " + name + " already exists.",
                    409);
        }
        boolean period = request.has("SliConfig") && !request.get("SliConfig").isNull();
        boolean requestBased = request.has("RequestBasedSliConfig")
                && !request.get("RequestBasedSliConfig").isNull();
        if (period == requestBased) {
            throw validation("Exactly one of SliConfig or RequestBasedSliConfig must be provided.");
        }
        long now = Instant.now().getEpochSecond();
        ServiceLevelObjective slo = new ServiceLevelObjective();
        slo.setName(name);
        slo.setArn(sloArn(region, name));
        if (request.has("Description") && !request.get("Description").isNull()) {
            slo.setDescription(requireBoundedText(request, "Description", 1, 1024));
        }
        slo.setCreatedTime(now);
        slo.setLastUpdatedTime(now);
        slo.setEvaluationType(period ? "PeriodBased" : "RequestBased");
        slo.setGoal(request.has("Goal") && request.get("Goal").isObject()
                ? request.get("Goal").deepCopy()
                : defaultGoal());
        if (request.has("BurnRateConfigurations") && !request.get("BurnRateConfigurations").isNull()) {
            slo.setBurnRateConfigurations(request.get("BurnRateConfigurations").deepCopy());
        }
        if (request.has("AutoInvestigationEnabled") && request.get("AutoInvestigationEnabled").isBoolean()) {
            slo.setAutoInvestigationEnabled(request.get("AutoInvestigationEnabled").booleanValue());
        }
        if (period) {
            JsonNode config = requireObjectField(request, "SliConfig");
            slo.setSli(toSli(config));
            slo.setMetricSourceType(metricSourceType(config.get("SliMetricConfig")));
        } else {
            JsonNode config = requireObjectField(request, "RequestBasedSliConfig");
            slo.setRequestBasedSli(toRequestBasedSli(config));
            slo.setMetricSourceType(metricSourceType(config.get("RequestBasedSliMetricConfig")));
        }
        slo.setTags(readTagList(request.get("Tags")));
        slo.setExclusionWindows(new ArrayList<>());
        sloStore.put(key, slo);
        return slo;
    }

    public ServiceLevelObjective getSlo(String region, String id) {
        return requireSlo(region, id);
    }

    public synchronized ServiceLevelObjective updateSlo(String region, String id, JsonNode request) {
        requireObject(request, "Request body");
        ServiceLevelObjective slo = requireSlo(region, id);
        if (request.has("Description") && !request.get("Description").isNull()) {
            slo.setDescription(requireBoundedText(request, "Description", 1, 1024));
        }
        if (request.has("Goal") && request.get("Goal").isObject()) {
            slo.setGoal(request.get("Goal").deepCopy());
        }
        if (request.has("BurnRateConfigurations")) {
            JsonNode value = request.get("BurnRateConfigurations");
            slo.setBurnRateConfigurations(value == null || value.isNull() ? null : value.deepCopy());
        }
        if (request.has("AutoInvestigationEnabled") && request.get("AutoInvestigationEnabled").isBoolean()) {
            slo.setAutoInvestigationEnabled(request.get("AutoInvestigationEnabled").booleanValue());
        }
        if (request.has("SliConfig") && request.get("SliConfig").isObject()) {
            if ("RequestBased".equals(slo.getEvaluationType())) {
                throw validation("Cannot change an SLO from RequestBased to PeriodBased.");
            }
            JsonNode config = request.get("SliConfig");
            slo.setSli(toSli(config));
            slo.setMetricSourceType(metricSourceType(config.get("SliMetricConfig")));
        }
        if (request.has("RequestBasedSliConfig") && request.get("RequestBasedSliConfig").isObject()) {
            if ("PeriodBased".equals(slo.getEvaluationType())) {
                throw validation("Cannot change an SLO from PeriodBased to RequestBased.");
            }
            JsonNode config = request.get("RequestBasedSliConfig");
            slo.setRequestBasedSli(toRequestBasedSli(config));
            slo.setMetricSourceType(metricSourceType(config.get("RequestBasedSliMetricConfig")));
        }
        slo.setLastUpdatedTime(Instant.now().getEpochSecond());
        sloStore.put(sloKey(region, slo.getName()), slo);
        return slo;
    }

    public synchronized void deleteSlo(String region, String id) {
        ServiceLevelObjective slo = requireSlo(region, id);
        sloStore.delete(sloKey(region, slo.getName()));
    }

    public List<ServiceLevelObjective> listSlos(String region) {
        List<ServiceLevelObjective> slos = sloStore.scan(key -> key.startsWith(region + "::"));
        slos.sort(Comparator.comparing(ServiceLevelObjective::getName, Comparator.nullsLast(String::compareTo)));
        return slos;
    }

    public ObjectNode budgetReport(String region, JsonNode request) {
        requireObject(request, "Request body");
        long timestamp = request.has("Timestamp") && !request.get("Timestamp").isNull()
                ? asEpoch(request.get("Timestamp"), "Timestamp")
                : Instant.now().getEpochSecond();
        JsonNode idsNode = request.get("SloIds");
        if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
            throw validation("SloIds must contain at least one identifier.");
        }
        ObjectNode response = objectNode();
        response.put("Timestamp", timestamp);
        ArrayNode reports = response.putArray("Reports");
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode idNode : idsNode) {
            if (idNode == null || !idNode.isTextual()) {
                throw validation("SloIds members must be strings.");
            }
            String id = idNode.textValue();
            try {
                ServiceLevelObjective slo = requireSlo(region, id);
                ObjectNode report = reports.addObject();
                report.put("Arn", slo.getArn());
                report.put("Name", slo.getName());
                if (slo.getEvaluationType() != null) {
                    report.put("EvaluationType", slo.getEvaluationType());
                }
                report.put("BudgetStatus", "INSUFFICIENT_DATA");
                if (slo.getSli() != null) {
                    report.set("Sli", slo.getSli());
                }
                if (slo.getRequestBasedSli() != null) {
                    report.set("RequestBasedSli", slo.getRequestBasedSli());
                }
                if (slo.getGoal() != null) {
                    report.set("Goal", slo.getGoal());
                }
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("Arn", id.startsWith("arn:") ? id : sloArn(region, id));
                error.put("Name", sloNameFromId(id));
                error.put("ErrorCode", e.getErrorCode());
                error.put("ErrorMessage", e.getMessage());
            }
        }
        return response;
    }

    public ObjectNode listExclusionWindows(String region, String id) {
        ServiceLevelObjective slo = requireSlo(region, id);
        ObjectNode response = objectNode();
        ArrayNode windows = response.putArray("ExclusionWindows");
        List<JsonNode> stored = slo.getExclusionWindows() == null ? List.of() : slo.getExclusionWindows();
        for (JsonNode window : stored) {
            windows.add(window.deepCopy());
        }
        return response;
    }

    public synchronized ObjectNode updateExclusionWindows(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode idsNode = request.get("SloIds");
        if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
            throw validation("SloIds must contain at least one identifier.");
        }
        List<JsonNode> add = readWindowList(request.get("AddExclusionWindows"));
        List<JsonNode> remove = readWindowList(request.get("RemoveExclusionWindows"));
        ObjectNode response = objectNode();
        ArrayNode sloIds = response.putArray("SloIds");
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode idNode : idsNode) {
            if (idNode == null || !idNode.isTextual()) {
                throw validation("SloIds members must be strings.");
            }
            String id = idNode.textValue();
            try {
                ServiceLevelObjective slo = requireSlo(region, id);
                List<JsonNode> windows = slo.getExclusionWindows() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(slo.getExclusionWindows());
                windows.addAll(add);
                windows.removeIf(existing -> remove.stream().anyMatch(candidate -> windowsEqual(existing, candidate)));
                slo.setExclusionWindows(windows);
                slo.setLastUpdatedTime(Instant.now().getEpochSecond());
                sloStore.put(sloKey(region, slo.getName()), slo);
                sloIds.add(slo.getArn());
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("SloId", id);
                error.put("ErrorCode", e.getErrorCode());
                error.put("ErrorMessage", e.getMessage());
            }
        }
        return response;
    }

    public ObjectNode getInstrumentationStatus(String region, JsonNode request) {
        InstrumentationConfiguration config = requireConfig(region, request);
        ObjectNode response = objectNode();
        response.put("Service", config.getService());
        response.put("Environment", config.getEnvironment());
        response.put("SignalType", config.getSignalType());
        response.set("Location", config.getLocation() == null ? objectNode() : config.getLocation());
        response.put("Status", "READY");
        response.putArray("Events");
        return response;
    }

    public ObjectNode emptyDiscovery(JsonNode request, String arrayField) {
        long[] window = timeWindow(request);
        ObjectNode response = objectNode();
        response.put("StartTime", window[0]);
        response.put("EndTime", window[1]);
        response.putArray(arrayField);
        return response;
    }

    public ObjectNode getDiscoveredService(JsonNode request) {
        requireObject(request, "Request body");
        if (!request.has("KeyAttributes") || !request.get("KeyAttributes").isObject()) {
            throw validation("KeyAttributes must be an object.");
        }
        long[] window = timeWindow(request);
        ObjectNode response = objectNode();
        response.set("Service", objectNode());
        response.put("StartTime", window[0]);
        response.put("EndTime", window[1]);
        return response;
    }

    public ObjectNode listAuditFindings(JsonNode request) {
        long[] window = timeWindow(request);
        ObjectNode response = objectNode();
        response.put("StartTime", window[0]);
        response.put("EndTime", window[1]);
        response.putArray("AuditFindings");
        return response;
    }

    public ObjectNode listTagsForResource(String region, String resourceArn) {
        Map<String, String> tags = tagsFor(region, resourceArn);
        ObjectNode response = objectNode();
        ArrayNode list = response.putArray("Tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return response;
    }

    public synchronized InstrumentationConfiguration createInstrumentationConfiguration(
            String region, JsonNode request) {
        requireObject(request, "Request body");
        String type = requireEnum(request, "InstrumentationType", INSTRUMENTATION_TYPES);
        String service = requireBoundedText(request, "Service", 1, 255);
        String environment = requireBoundedText(request, "Environment", 1, 255);
        String signalType = requireEnum(request, "SignalType", SIGNAL_TYPES);
        JsonNode location = requireObjectField(request, "Location");
        JsonNode codeLocation = requireObjectField(location, "CodeLocation");
        requireBoundedText(codeLocation, "Language", 1, 64);
        requireBoundedText(codeLocation, "FilePath", 1, 1024);
        JsonNode capture = requireObjectField(request, "CaptureConfiguration");
        requireObjectField(capture, "CodeCapture");
        requireObjectField(capture.get("CodeCapture"), "CaptureLimits");

        Long expiresAt = readExpiresAt(request);
        if ("PROBE".equals(type) && expiresAt != null) {
            throw validation("ExpiresAt cannot be set for PROBE configurations.");
        }
        if ("BREAKPOINT".equals(type) && expiresAt == null) {
            expiresAt = Instant.now().getEpochSecond() + BREAKPOINT_DEFAULT_TTL_SECONDS;
        }

        String locationHash = locationHash(codeLocation);
        String key = storageKey(region, type, service, environment, signalType, locationHash);
        if (configurationStore.get(key).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "An instrumentation configuration already exists for this location.",
                    409);
        }

        InstrumentationConfiguration config = new InstrumentationConfiguration();
        config.setInstrumentationType(type);
        config.setService(service);
        config.setEnvironment(environment);
        config.setSignalType(signalType);
        config.setLocationHash(locationHash);
        config.setLocation(location.deepCopy());
        config.setCaptureConfiguration(capture.deepCopy());
        if (request.has("Description") && !request.get("Description").isNull()) {
            config.setDescription(requireBoundedText(request, "Description", 1, 1024));
        }
        if (request.has("AttributeFilters") && !request.get("AttributeFilters").isNull()) {
            JsonNode filters = request.get("AttributeFilters");
            if (!filters.isArray()) {
                throw validation("AttributeFilters must be an array.");
            }
            config.setAttributeFilters(filters.deepCopy());
        }
        config.setExpiresAt(expiresAt);
        config.setCreatedAt(Instant.now().getEpochSecond());
        config.setTags(readTagList(request.get("Tags")));
        config.setArn(arn(region, service, environment, signalType, locationHash));
        configurationStore.put(key, config);
        return config;
    }

    public InstrumentationConfiguration getInstrumentationConfiguration(String region, JsonNode request) {
        return requireConfig(region, request);
    }

    public synchronized void deleteInstrumentationConfiguration(String region, JsonNode request) {
        InstrumentationConfiguration config = requireConfig(region, request);
        configurationStore.delete(storageKey(
                region,
                config.getInstrumentationType(),
                config.getService(),
                config.getEnvironment(),
                config.getSignalType(),
                config.getLocationHash()));
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagsFor(region, arn));
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        applyTags(region, arn, tags, null);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        applyTags(region, arn, null, tagKeys);
    }

    static String locationHash(JsonNode codeLocation) {
        String canonical = String.join("|",
                textOrEmpty(codeLocation, "Language"),
                textOrEmpty(codeLocation, "CodeUnit"),
                textOrEmpty(codeLocation, "ClassName"),
                textOrEmpty(codeLocation, "MethodName"),
                textOrEmpty(codeLocation, "FilePath"),
                codeLocation != null && codeLocation.has("LineNumber") && codeLocation.get("LineNumber").isNumber()
                        ? Integer.toString(codeLocation.get("LineNumber").intValue())
                        : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private AccountSignalsState loadAccount(String region) {
        return accountStore.get(region).orElseGet(AccountSignalsState::new);
    }

    private InstrumentationConfiguration requireConfig(String region, JsonNode request) {
        requireObject(request, "Request body");
        String type = requireEnum(request, "InstrumentationType", INSTRUMENTATION_TYPES);
        String service = requireBoundedText(request, "Service", 1, 255);
        String environment = requireBoundedText(request, "Environment", 1, 255);
        String signalType = requireEnum(request, "SignalType", SIGNAL_TYPES);
        JsonNode identifier = requireObjectField(request, "LocationIdentifier");
        String hash;
        if (identifier.has("LocationHash") && !identifier.get("LocationHash").isNull()) {
            hash = requireText(identifier, "LocationHash");
            if (hash.length() != 16 || !hash.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw validation("LocationHash must be a 16-character hex string.");
            }
        } else if (identifier.has("CodeLocation") && identifier.get("CodeLocation").isObject()) {
            hash = locationHash(identifier.get("CodeLocation"));
        } else {
            throw validation("LocationIdentifier must contain CodeLocation or LocationHash.");
        }
        return configurationStore.get(storageKey(region, type, service, environment, signalType, hash))
                .orElseThrow(() -> resourceNotFound(hash));
    }

    private InstrumentationConfiguration requireByArn(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw validation("ResourceArn is required.");
        }
        InstrumentationConfiguration found = null;
        for (InstrumentationConfiguration config : configurationStore.scan(key -> key.startsWith(region + "::"))) {
            if (arn.equals(config.getArn())) {
                found = config;
                break;
            }
        }
        if (found == null) {
            throw resourceNotFound(arn);
        }
        return found;
    }

    private String arn(String region, String service, String environment, String signalType, String hash) {
        String resource = RESOURCE_TYPE + "/" + service + "/" + environment + "/" + signalType + "/" + hash;
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String storageKey(
            String region, String type, String service, String environment, String signalType, String hash) {
        return region + "::" + type + "::" + service + "::" + environment + "::" + signalType + "::" + hash;
    }

    private static String storageKeyOf(String region, InstrumentationConfiguration config) {
        return storageKey(
                region,
                config.getInstrumentationType(),
                config.getService(),
                config.getEnvironment(),
                config.getSignalType(),
                config.getLocationHash());
    }

    private static Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray() || tagsNode.size() > 50) {
            throw validation("Tags must be an array with at most 50 entries.");
        }
        for (JsonNode entry : tagsNode) {
            if (entry == null || !entry.isObject()) {
                throw validation("Tags entries must be objects with Key and Value.");
            }
            String key = requireText(entry, "Key");
            String value = requireText(entry, "Value");
            if (key.length() < 1 || key.length() > 128 || value.length() > 256) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(key, value);
        }
        return tags;
    }

    private static Long readExpiresAt(JsonNode request) {
        if (!request.has("ExpiresAt") || request.get("ExpiresAt").isNull()) {
            return null;
        }
        JsonNode value = request.get("ExpiresAt");
        if (!value.isNumber()) {
            throw validation("ExpiresAt must be an epoch-seconds timestamp.");
        }
        return value.longValue();
    }

    private static String requireEnum(JsonNode parent, String field, Set<String> allowed) {
        String value = requireText(parent, field);
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static String requireBoundedText(JsonNode parent, String field, int min, int max) {
        String value = requireText(parent, field);
        if (value.length() < min || value.length() > max) {
            throw validation(field + " must be between " + min + " and " + max + " characters.");
        }
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String textOrEmpty(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return "";
        }
        JsonNode value = parent.get(field);
        return value.isTextual() ? value.textValue() : "";
    }

    private ServiceLevelObjective requireSlo(String region, String id) {
        String decoded = decode(id);
        if (decoded.startsWith("arn:")) {
            ServiceLevelObjective slo = findSloByArn(region, decoded);
            if (slo == null) {
                throw sloNotFound(decoded);
            }
            return slo;
        }
        return sloStore.get(sloKey(region, decoded)).orElseThrow(() -> sloNotFound(decoded));
    }

    private ServiceLevelObjective findSloByArn(String region, String arn) {
        for (ServiceLevelObjective slo : sloStore.scan(key -> key.startsWith(region + "::"))) {
            if (arn.equals(slo.getArn())) {
                return slo;
            }
        }
        return null;
    }

    private Map<String, String> tagsFor(String region, String resourceArn) {
        String decoded = decode(resourceArn);
        ServiceLevelObjective slo = findSloByArn(region, decoded);
        if (slo != null) {
            return slo.getTags() == null ? Map.of() : slo.getTags();
        }
        return requireByArn(region, decoded).getTags();
    }

    private void applyTags(String region, String arn, Map<String, String> upsert, List<String> removed) {
        String decoded = decode(arn);
        ServiceLevelObjective slo = findSloByArn(region, decoded);
        if (slo != null) {
            Map<String, String> tags = slo.getTags() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slo.getTags());
            if (upsert != null) {
                tags.putAll(upsert);
            }
            if (removed != null) {
                removed.forEach(tags::remove);
            }
            slo.setTags(tags);
            sloStore.put(sloKey(region, slo.getName()), slo);
            return;
        }
        InstrumentationConfiguration config = requireByArn(region, decoded);
        Map<String, String> current = new LinkedHashMap<>(config.getTags());
        if (upsert != null) {
            current.putAll(upsert);
        }
        if (removed != null) {
            removed.forEach(current::remove);
        }
        config.setTags(current);
        configurationStore.put(storageKeyOf(region, config), config);
    }

    private String sloArn(String region, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "slo/" + name).toString();
    }

    private static String sloKey(String region, String name) {
        return region + "::" + name;
    }

    private static String sloNameFromId(String id) {
        String decoded = decode(id);
        if (decoded.startsWith("arn:")) {
            int slash = decoded.lastIndexOf('/');
            return slash >= 0 ? decoded.substring(slash + 1) : decoded;
        }
        return decoded;
    }

    private static JsonNode toSli(JsonNode config) {
        JsonNode metricConfig = config.get("SliMetricConfig");
        if (metricConfig == null || !metricConfig.isObject()) {
            throw validation("SliMetricConfig is required.");
        }
        ObjectNode sli = objectNode();
        sli.set("SliMetric", metricConfig.deepCopy());
        if (config.has("MetricThreshold") && config.get("MetricThreshold").isNumber()) {
            sli.put("MetricThreshold", config.get("MetricThreshold").doubleValue());
        }
        if (config.has("ComparisonOperator") && config.get("ComparisonOperator").isTextual()) {
            sli.put("ComparisonOperator", config.get("ComparisonOperator").textValue());
        }
        return sli;
    }

    private static JsonNode toRequestBasedSli(JsonNode config) {
        JsonNode metricConfig = config.get("RequestBasedSliMetricConfig");
        if (metricConfig == null || !metricConfig.isObject()) {
            throw validation("RequestBasedSliMetricConfig is required.");
        }
        ObjectNode sli = objectNode();
        sli.set("RequestBasedSliMetric", metricConfig.deepCopy());
        if (config.has("MetricThreshold") && config.get("MetricThreshold").isNumber()) {
            sli.put("MetricThreshold", config.get("MetricThreshold").doubleValue());
        }
        if (config.has("ComparisonOperator") && config.get("ComparisonOperator").isTextual()) {
            sli.put("ComparisonOperator", config.get("ComparisonOperator").textValue());
        }
        return sli;
    }

    private static JsonNode defaultGoal() {
        ObjectNode rolling = objectNode();
        rolling.put("DurationUnit", "DAY");
        rolling.put("Duration", 7);
        ObjectNode interval = objectNode();
        interval.set("RollingInterval", rolling);
        ObjectNode goal = objectNode();
        goal.set("Interval", interval);
        goal.put("AttainmentGoal", 99);
        goal.put("WarningThreshold", 30);
        return goal;
    }

    private static String metricSourceType(JsonNode metricConfig) {
        if (metricConfig != null && metricConfig.has("KeyAttributes")
                && metricConfig.get("KeyAttributes").isObject()
                && !metricConfig.get("KeyAttributes").isEmpty()) {
            return "ServiceOperation";
        }
        return "CloudWatchMetric";
    }

    private static long[] timeWindow(JsonNode request) {
        long end = Instant.now().getEpochSecond();
        long start = end - 3600;
        if (request != null) {
            if (request.has("StartTime") && !request.get("StartTime").isNull()) {
                start = asEpoch(request.get("StartTime"), "StartTime");
            }
            if (request.has("EndTime") && !request.get("EndTime").isNull()) {
                end = asEpoch(request.get("EndTime"), "EndTime");
            }
        }
        return new long[] {start, end};
    }

    private static List<JsonNode> readWindowList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw validation("Exclusion windows must be an array.");
        }
        List<JsonNode> windows = new ArrayList<>();
        for (JsonNode window : node) {
            requireObject(window, "Exclusion window");
            if (!window.has("Window") || !window.get("Window").isObject()) {
                throw validation("Exclusion window Window is required.");
            }
            windows.add(window.deepCopy());
        }
        return windows;
    }

    private static boolean windowsEqual(JsonNode left, JsonNode right) {
        return canonicalWindow(left).equals(canonicalWindow(right));
    }

    private static String canonicalWindow(JsonNode window) {
        JsonNode spec = window.get("Window");
        String durationUnit = spec != null && spec.has("DurationUnit") ? spec.get("DurationUnit").asText() : "";
        String duration = spec != null && spec.has("Duration")
                ? Long.toString(spec.get("Duration").asLong())
                : "";
        String start = "";
        if (window.has("StartTime") && !window.get("StartTime").isNull()) {
            try {
                start = Long.toString(asEpoch(window.get("StartTime"), "StartTime"));
            } catch (RuntimeException e) {
                start = window.get("StartTime").asText();
            }
        }
        String reason = window.has("Reason") ? window.get("Reason").asText() : "";
        String recurrence = "";
        if (window.has("RecurrenceRule") && window.get("RecurrenceRule").has("Expression")) {
            recurrence = window.get("RecurrenceRule").get("Expression").asText();
        }
        return durationUnit + "|" + duration + "|" + start + "|" + reason + "|" + recurrence;
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static long asEpoch(JsonNode value, String field) {
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            String text = value.textValue();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                try {
                    return (long) Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw validation(field + " must be an epoch timestamp.");
                }
            }
        }
        throw validation(field + " must be an epoch timestamp.");
    }

    private static AwsException sloNotFound(String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Service level objective " + resourceId + " was not found.",
                404,
                Map.of("ResourceType", RESOURCE_SLO, "ResourceId", resourceId));
    }

    private static AwsException resourceNotFound(String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Instrumentation configuration " + resourceId + " does not exist.",
                404,
                Map.of("ResourceType", RESOURCE_TYPE, "ResourceId", resourceId));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
