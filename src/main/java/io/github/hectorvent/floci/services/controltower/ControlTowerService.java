package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.controltower.model.ControlTowerOperation;
import io.github.hectorvent.floci.services.controltower.model.EnabledBaseline;
import io.github.hectorvent.floci.services.controltower.model.EnabledControl;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Control Tower restJson1. The baseline catalog is served without a
 * landing zone; landing zones, enabled baselines/controls, and operations
 * are stored per account and region.
 */
@ApplicationScoped
public class ControlTowerService implements TagHandler {

    static final String SERVICE = "controltower";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 100;
    private static final String LATEST_LANDING_ZONE_VERSION = "3.3";

    public record CatalogBaseline(String id, String name, String description) {
    }

    /**
     * Published Control Tower baseline catalog. ARNs are region-scoped with an
     * empty account ({@code arn:aws:controltower:{region}::baseline/{id}}).
     */
    private static final List<CatalogBaseline> CATALOG = List.of(
            new CatalogBaseline("17ITK3uBWTQ4nNOteAFJ2B", "AWSControlTowerBaseline",
                    "Sets up the core AWS Control Tower resources on the target."),
            new CatalogBaseline("4T4zxn7S3V5kP9mN8xR5tY", "AuditBaseline",
                    "Configures the Audit account for AWS Control Tower."),
            new CatalogBaseline("8K2mPqR6nW1sL4vB7cD9eF", "LogArchiveBaseline",
                    "Configures the Log Archive account for AWS Control Tower."),
            new CatalogBaseline("2H5jKlM9oP3qR7sT1uV4wX", "IdentityCenterBaseline",
                    "Enables IAM Identity Center for AWS Control Tower."));

    public record Page<T>(List<T> items, String nextToken) {
    }

    public record EnableResult(String arn, ControlTowerOperation operation) {
    }

    public record CreateLandingZoneResult(String arn, ControlTowerOperation operation) {
    }

    private final StorageBackend<String, LandingZone> landingZones;
    private final StorageBackend<String, EnabledBaseline> enabledBaselines;
    private final StorageBackend<String, EnabledControl> enabledControls;
    private final StorageBackend<String, ControlTowerOperation> operations;
    private final RegionResolver regionResolver;

    @Inject
    public ControlTowerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("controltower", "controltower-landing-zones.json",
                        new TypeReference<Map<String, LandingZone>>() {
                        }),
                storageFactory.create("controltower", "controltower-enabled-baselines.json",
                        new TypeReference<Map<String, EnabledBaseline>>() {
                        }),
                storageFactory.create("controltower", "controltower-enabled-controls.json",
                        new TypeReference<Map<String, EnabledControl>>() {
                        }),
                storageFactory.create("controltower", "controltower-operations.json",
                        new TypeReference<Map<String, ControlTowerOperation>>() {
                        }),
                regionResolver);
    }

    ControlTowerService(
            StorageBackend<String, LandingZone> landingZones,
            StorageBackend<String, EnabledBaseline> enabledBaselines,
            StorageBackend<String, EnabledControl> enabledControls,
            StorageBackend<String, ControlTowerOperation> operations,
            RegionResolver regionResolver) {
        this.landingZones = landingZones;
        this.enabledBaselines = enabledBaselines;
        this.enabledControls = enabledControls;
        this.operations = operations;
        this.regionResolver = regionResolver;
    }

    public Page<CatalogBaseline> listBaselines(String region, JsonNode request) {
        List<CatalogBaseline> items = new ArrayList<>(CATALOG);
        return paginate(items, request);
    }

    public CatalogBaseline getBaseline(String region, JsonNode request) {
        String identifier = requireText(request, "baselineIdentifier");
        for (CatalogBaseline baseline : CATALOG) {
            String arn = baselineArn(region, baseline.id());
            if (identifier.equals(arn) || identifier.equals(baseline.id()) || identifier.equals(baseline.name())) {
                return baseline;
            }
        }
        throw notFound("Baseline not found.");
    }

    public String baselineArn(String region, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, "", "baseline/" + id).toString();
    }

    public Page<EnabledBaseline> listEnabledBaselines(String region, JsonNode request) {
        String prefix = scopePrefix(region);
        List<EnabledBaseline> items = enabledBaselines.scan(key -> key.startsWith(prefix));
        items.sort(Comparator.comparing(EnabledBaseline::getArn));
        JsonNode filter = request.path("filter");
        if (filter.isObject()) {
            items.removeIf(item -> !matchesEnabledBaselineFilter(item, filter));
        }
        return paginate(items, request);
    }

    public EnabledBaseline getEnabledBaseline(String region, JsonNode request) {
        return requireEnabledBaseline(region, requireText(request, "enabledBaselineIdentifier"));
    }

    public synchronized EnableResult enableBaseline(String region, JsonNode request) {
        String baselineIdentifier = requireText(request, "baselineIdentifier");
        getBaseline(region, request);
        String targetIdentifier = requireText(request, "targetIdentifier");
        String version = requireText(request, "baselineVersion");
        String arn = resourceArn(region, "enabledbaseline", newId());
        EnabledBaseline enabled = new EnabledBaseline();
        enabled.setArn(arn);
        enabled.setBaselineIdentifier(baselineIdentifier);
        enabled.setBaselineVersion(version);
        enabled.setTargetIdentifier(targetIdentifier);
        enabled.setStatus("SUCCEEDED");
        enabled.setParameters(request.get("parameters"));
        enabled.setTags(readTags(request.get("tags")));
        enabled.setAccountId(accountId());
        enabled.setRegion(region);
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_BASELINE,
                "ENABLE_BASELINE", arn, targetIdentifier, null);
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabledBaselines.put(storageKey(region, arn), enabled);
        return new EnableResult(arn, operation);
    }

    public synchronized ControlTowerOperation disableBaseline(String region, JsonNode request) {
        EnabledBaseline enabled = requireEnabledBaseline(region, requireText(request, "enabledBaselineIdentifier"));
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_BASELINE,
                "DISABLE_BASELINE", enabled.getArn(), enabled.getTargetIdentifier(), null);
        enabledBaselines.delete(storageKey(region, enabled.getArn()));
        return operation;
    }

    public synchronized ControlTowerOperation updateEnabledBaseline(String region, JsonNode request) {
        EnabledBaseline enabled = requireEnabledBaseline(region, requireText(request, "enabledBaselineIdentifier"));
        enabled.setBaselineVersion(requireText(request, "baselineVersion"));
        if (request.has("parameters") && !request.get("parameters").isNull()) {
            enabled.setParameters(request.get("parameters"));
        }
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_BASELINE,
                "UPDATE_ENABLED_BASELINE", enabled.getArn(), enabled.getTargetIdentifier(), null);
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabled.setStatus("SUCCEEDED");
        enabledBaselines.put(storageKey(region, enabled.getArn()), enabled);
        return operation;
    }

    public synchronized ControlTowerOperation resetEnabledBaseline(String region, JsonNode request) {
        EnabledBaseline enabled = requireEnabledBaseline(region, requireText(request, "enabledBaselineIdentifier"));
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_BASELINE,
                "RESET_ENABLED_BASELINE", enabled.getArn(), enabled.getTargetIdentifier(), null);
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabled.setStatus("SUCCEEDED");
        enabledBaselines.put(storageKey(region, enabled.getArn()), enabled);
        return operation;
    }

    public ControlTowerOperation getBaselineOperation(String region, JsonNode request) {
        return requireOperation(region, requireText(request, "operationIdentifier"),
                ControlTowerOperation.FAMILY_BASELINE);
    }

    public Page<EnabledControl> listEnabledControls(String region, JsonNode request) {
        String prefix = scopePrefix(region);
        List<EnabledControl> items = enabledControls.scan(key -> key.startsWith(prefix));
        items.sort(Comparator.comparing(EnabledControl::getArn, Comparator.nullsLast(String::compareTo)));
        if (request.hasNonNull("targetIdentifier")) {
            String target = request.get("targetIdentifier").asText();
            items.removeIf(item -> !target.equals(item.getTargetIdentifier()));
        }
        JsonNode filter = request.path("filter");
        if (filter.isObject()) {
            items.removeIf(item -> !matchesEnabledControlFilter(item, filter));
        }
        return paginate(items, request);
    }

    public EnabledControl getEnabledControl(String region, JsonNode request) {
        return requireEnabledControl(region, requireText(request, "enabledControlIdentifier"));
    }

    public synchronized EnableResult enableControl(String region, JsonNode request) {
        String controlIdentifier = requireText(request, "controlIdentifier");
        String targetIdentifier = requireText(request, "targetIdentifier");
        String arn = resourceArn(region, "enabledcontrol", newId());
        EnabledControl enabled = new EnabledControl();
        enabled.setArn(arn);
        enabled.setControlIdentifier(controlIdentifier);
        enabled.setTargetIdentifier(targetIdentifier);
        enabled.setStatus("SUCCEEDED");
        enabled.setDriftStatus("IN_SYNC");
        enabled.setParameters(request.get("parameters"));
        enabled.setTags(readTags(request.get("tags")));
        enabled.setAccountId(accountId());
        enabled.setRegion(region);
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_CONTROL,
                "ENABLE_CONTROL", arn, targetIdentifier, controlIdentifier);
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabledControls.put(storageKey(region, arn), enabled);
        return new EnableResult(arn, operation);
    }

    public synchronized ControlTowerOperation disableControl(String region, JsonNode request) {
        EnabledControl enabled;
        if (request.hasNonNull("enabledControlIdentifier")) {
            enabled = requireEnabledControl(region, request.get("enabledControlIdentifier").asText());
        } else {
            String controlIdentifier = requireText(request, "controlIdentifier");
            String targetIdentifier = requireText(request, "targetIdentifier");
            enabled = enabledControls.scan(key -> key.startsWith(scopePrefix(region))).stream()
                    .filter(item -> controlIdentifier.equals(item.getControlIdentifier())
                            && targetIdentifier.equals(item.getTargetIdentifier()))
                    .findFirst()
                    .orElseThrow(() -> notFound("Enabled control not found."));
        }
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_CONTROL,
                "DISABLE_CONTROL", enabled.getArn(), enabled.getTargetIdentifier(), enabled.getControlIdentifier());
        enabledControls.delete(storageKey(region, enabled.getArn()));
        return operation;
    }

    public synchronized ControlTowerOperation updateEnabledControl(String region, JsonNode request) {
        EnabledControl enabled = requireEnabledControl(region, requireText(request, "enabledControlIdentifier"));
        if (!request.has("parameters") || request.get("parameters").isNull()) {
            throw validation("parameters is required.");
        }
        enabled.setParameters(request.get("parameters"));
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_CONTROL,
                "UPDATE_ENABLED_CONTROL", enabled.getArn(), enabled.getTargetIdentifier(),
                enabled.getControlIdentifier());
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabled.setStatus("SUCCEEDED");
        enabledControls.put(storageKey(region, enabled.getArn()), enabled);
        return operation;
    }

    public synchronized ControlTowerOperation resetEnabledControl(String region, JsonNode request) {
        EnabledControl enabled = requireEnabledControl(region, requireText(request, "enabledControlIdentifier"));
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_CONTROL,
                "RESET_ENABLED_CONTROL", enabled.getArn(), enabled.getTargetIdentifier(),
                enabled.getControlIdentifier());
        enabled.setLastOperationIdentifier(operation.getOperationIdentifier());
        enabled.setStatus("SUCCEEDED");
        enabledControls.put(storageKey(region, enabled.getArn()), enabled);
        return operation;
    }

    public ControlTowerOperation getControlOperation(String region, JsonNode request) {
        return requireOperation(region, requireText(request, "operationIdentifier"),
                ControlTowerOperation.FAMILY_CONTROL);
    }

    public Page<ControlTowerOperation> listControlOperations(String region, JsonNode request) {
        List<ControlTowerOperation> items = operations.scan(key -> key.startsWith(scopePrefix(region))).stream()
                .filter(op -> ControlTowerOperation.FAMILY_CONTROL.equals(op.getFamily()))
                .sorted(Comparator.comparing(ControlTowerOperation::getStartTime, Comparator.nullsLast(String::compareTo))
                        .reversed())
                .toList();
        List<ControlTowerOperation> filtered = new ArrayList<>(items);
        JsonNode filter = request.path("filter");
        if (filter.isObject()) {
            filtered.removeIf(op -> !matchesControlOperationFilter(op, filter));
        }
        return paginate(filtered, request);
    }

    public Page<LandingZone> listLandingZones(String region, JsonNode request) {
        List<LandingZone> items = landingZones.scan(key -> key.startsWith(scopePrefix(region)));
        items.sort(Comparator.comparing(LandingZone::getArn));
        return paginate(items, request);
    }

    public LandingZone getLandingZone(String region, JsonNode request) {
        return requireLandingZone(region, requireText(request, "landingZoneIdentifier"));
    }

    public synchronized CreateLandingZoneResult createLandingZone(String region, JsonNode request) {
        String version = requireText(request, "version");
        if (!landingZones.scan(key -> key.startsWith(scopePrefix(region))).isEmpty()) {
            throw new AwsException("ConflictException", "A landing zone already exists in this Region.", 409);
        }
        String arn = resourceArn(region, "landingzone", newId());
        LandingZone zone = new LandingZone();
        zone.setArn(arn);
        zone.setVersion(version);
        zone.setLatestAvailableVersion(LATEST_LANDING_ZONE_VERSION);
        zone.setStatus("ACTIVE");
        zone.setDriftStatus("IN_SYNC");
        zone.setRemediationTypes(readStringList(request.get("remediationTypes")));
        zone.setManifest(request.get("manifest"));
        zone.setTags(readTags(request.get("tags")));
        zone.setAccountId(accountId());
        zone.setRegion(region);
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_LANDING_ZONE,
                "CREATE", arn, null, null);
        landingZones.put(storageKey(region, arn), zone);
        return new CreateLandingZoneResult(arn, operation);
    }

    public synchronized ControlTowerOperation deleteLandingZone(String region, JsonNode request) {
        LandingZone zone = requireLandingZone(region, requireText(request, "landingZoneIdentifier"));
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_LANDING_ZONE,
                "DELETE", zone.getArn(), null, null);
        landingZones.delete(storageKey(region, zone.getArn()));
        return operation;
    }

    public synchronized ControlTowerOperation updateLandingZone(String region, JsonNode request) {
        LandingZone zone = requireLandingZone(region, requireText(request, "landingZoneIdentifier"));
        zone.setVersion(requireText(request, "version"));
        if (request.has("manifest") && !request.get("manifest").isNull()) {
            zone.setManifest(request.get("manifest"));
        }
        if (request.has("remediationTypes") && !request.get("remediationTypes").isNull()) {
            zone.setRemediationTypes(readStringList(request.get("remediationTypes")));
        }
        zone.setStatus("ACTIVE");
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_LANDING_ZONE,
                "UPDATE", zone.getArn(), null, null);
        landingZones.put(storageKey(region, zone.getArn()), zone);
        return operation;
    }

    public synchronized ControlTowerOperation resetLandingZone(String region, JsonNode request) {
        LandingZone zone = requireLandingZone(region, requireText(request, "landingZoneIdentifier"));
        zone.setStatus("ACTIVE");
        zone.setDriftStatus("IN_SYNC");
        ControlTowerOperation operation = recordOperation(region, ControlTowerOperation.FAMILY_LANDING_ZONE,
                "RESET", zone.getArn(), null, null);
        landingZones.put(storageKey(region, zone.getArn()), zone);
        return operation;
    }

    public ControlTowerOperation getLandingZoneOperation(String region, JsonNode request) {
        return requireOperation(region, requireText(request, "operationIdentifier"),
                ControlTowerOperation.FAMILY_LANDING_ZONE);
    }

    public Page<ControlTowerOperation> listLandingZoneOperations(String region, JsonNode request) {
        List<ControlTowerOperation> items = operations.scan(key -> key.startsWith(scopePrefix(region))).stream()
                .filter(op -> ControlTowerOperation.FAMILY_LANDING_ZONE.equals(op.getFamily()))
                .sorted(Comparator.comparing(ControlTowerOperation::getStartTime, Comparator.nullsLast(String::compareTo))
                        .reversed())
                .toList();
        List<ControlTowerOperation> filtered = new ArrayList<>(items);
        JsonNode filter = request.path("filter");
        if (filter.isObject()) {
            filtered.removeIf(op -> !matchesLandingZoneOperationFilter(op, filter));
        }
        return paginate(filtered, request);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(tagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        TaggedResource resource = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        resource.setTags(current);
        resource.store();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        TaggedResource resource = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        resource.setTags(current);
        resource.store();
    }

    private TaggedResource tagged(String region, String arn) {
        String decoded = arn;
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource.startsWith("landingzone/")) {
            LandingZone zone = requireLandingZone(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return zone.getTags() == null ? Map.of() : zone.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    zone.setTags(tags);
                }

                @Override
                public void store() {
                    landingZones.put(storageKey(region, zone.getArn()), zone);
                }
            };
        }
        if (resource.startsWith("enabledbaseline/")) {
            EnabledBaseline enabled = requireEnabledBaseline(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return enabled.getTags() == null ? Map.of() : enabled.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    enabled.setTags(tags);
                }

                @Override
                public void store() {
                    enabledBaselines.put(storageKey(region, enabled.getArn()), enabled);
                }
            };
        }
        if (resource.startsWith("enabledcontrol/")) {
            EnabledControl enabled = requireEnabledControl(region, decoded);
            return new TaggedResource() {
                @Override
                public Map<String, String> tags() {
                    return enabled.getTags() == null ? Map.of() : enabled.getTags();
                }

                @Override
                public void setTags(Map<String, String> tags) {
                    enabled.setTags(tags);
                }

                @Override
                public void store() {
                    enabledControls.put(storageKey(region, enabled.getArn()), enabled);
                }
            };
        }
        throw notFound("Resource not found.");
    }

    private interface TaggedResource {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);

        void store();
    }

    private EnabledBaseline requireEnabledBaseline(String region, String identifier) {
        return enabledBaselines.get(storageKey(region, identifier))
                .orElseThrow(() -> notFound("Enabled baseline not found."));
    }

    private EnabledControl requireEnabledControl(String region, String identifier) {
        return enabledControls.get(storageKey(region, identifier))
                .orElseThrow(() -> notFound("Enabled control not found."));
    }

    private LandingZone requireLandingZone(String region, String identifier) {
        return landingZones.get(storageKey(region, identifier))
                .orElseGet(() -> landingZones.scan(key -> key.startsWith(scopePrefix(region))).stream()
                        .filter(zone -> identifier.equals(zone.getArn()))
                        .findFirst()
                        .orElseThrow(() -> notFound("Landing zone not found.")));
    }

    private ControlTowerOperation requireOperation(String region, String operationIdentifier, String family) {
        ControlTowerOperation operation = operations.get(operationKey(region, operationIdentifier)).orElse(null);
        if (operation == null || !family.equals(operation.getFamily())) {
            throw notFound("Operation not found.");
        }
        return operation;
    }

    private ControlTowerOperation recordOperation(
            String region, String family, String type, String resourceArn, String targetIdentifier,
            String controlIdentifier) {
        Instant now = Instant.now();
        ControlTowerOperation operation = new ControlTowerOperation();
        operation.setOperationIdentifier(UUID.randomUUID().toString());
        operation.setFamily(family);
        operation.setOperationType(type);
        operation.setStatus("SUCCEEDED");
        operation.setStartTime(now.toString());
        operation.setEndTime(now.toString());
        operation.setEnabledControlIdentifier(resourceArn);
        operation.setTargetIdentifier(targetIdentifier);
        operation.setControlIdentifier(controlIdentifier);
        operation.setAccountId(accountId());
        operation.setRegion(region);
        operations.put(operationKey(region, operation.getOperationIdentifier()), operation);
        return operation;
    }

    private boolean matchesEnabledBaselineFilter(EnabledBaseline item, JsonNode filter) {
        if (!containsOrAbsent(filter, "targetIdentifiers", item.getTargetIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "baselineIdentifiers", item.getBaselineIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "parentIdentifiers", item.getParentIdentifier())) {
            return false;
        }
        return containsOrAbsent(filter, "statuses", item.getStatus());
    }

    private boolean matchesEnabledControlFilter(EnabledControl item, JsonNode filter) {
        if (!containsOrAbsent(filter, "controlIdentifiers", item.getControlIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "statuses", item.getStatus())) {
            return false;
        }
        if (!containsOrAbsent(filter, "parentIdentifiers", item.getParentIdentifier())) {
            return false;
        }
        return containsOrAbsent(filter, "driftStatuses", item.getDriftStatus());
    }

    private boolean matchesControlOperationFilter(ControlTowerOperation op, JsonNode filter) {
        if (!containsOrAbsent(filter, "controlIdentifiers", op.getControlIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "targetIdentifiers", op.getTargetIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "enabledControlIdentifiers", op.getEnabledControlIdentifier())) {
            return false;
        }
        if (!containsOrAbsent(filter, "statuses", op.getStatus())) {
            return false;
        }
        return containsOrAbsent(filter, "controlOperationTypes", op.getOperationType());
    }

    private boolean matchesLandingZoneOperationFilter(ControlTowerOperation op, JsonNode filter) {
        if (!containsOrAbsent(filter, "types", op.getOperationType())) {
            return false;
        }
        return containsOrAbsent(filter, "statuses", op.getStatus());
    }

    private static boolean containsOrAbsent(JsonNode filter, String field, String value) {
        if (!filter.has(field) || filter.get(field).isNull()) {
            return true;
        }
        JsonNode node = filter.get(field);
        if (!node.isArray()) {
            return true;
        }
        for (JsonNode entry : node) {
            if (entry.isTextual() && entry.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private <T> Page<T> paginate(List<T> items, JsonNode request) {
        int maxResults = parseMaxResults(request);
        int offset = parseOffset(request);
        if (offset > items.size()) {
            offset = items.size();
        }
        int end = Math.min(offset + maxResults, items.size());
        String next = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), next);
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.hasNonNull("maxResults")) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode node = request.get("maxResults");
        if (!node.isNumber() && !node.isTextual()) {
            throw validation("maxResults is invalid.");
        }
        int value = node.isNumber() ? node.intValue() : Integer.parseInt(node.asText());
        if (value < 1 || value > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and " + MAX_RESULTS + ".");
        }
        return value;
    }

    private static int parseOffset(JsonNode request) {
        if (request == null || !request.hasNonNull("nextToken")) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(request.get("nextToken").asText())));
        } catch (RuntimeException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getEncoder().encodeToString(Integer.toString(offset).getBytes());
    }

    private String resourceArn(String region, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId(), kind + "/" + id).toString();
    }

    private String storageKey(String region, String arn) {
        return accountId() + "::" + region + "::" + arn;
    }

    private String operationKey(String region, String operationIdentifier) {
        return accountId() + "::" + region + "::op::" + operationIdentifier;
    }

    private String scopePrefix(String region) {
        return accountId() + "::" + region + "::";
    }

    private String accountId() {
        return regionResolver.getAccountId();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return values;
        }
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                values.add(entry.asText());
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        requireObject(request);
        if (!request.hasNonNull(field) || !request.get(field).isTextual() || request.get(field).asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return request.get(field).asText();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("Request body must be a JSON object.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
