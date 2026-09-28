package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.Scheduler;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.StateEvents;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.FeatureGroupResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.FeatureRecordResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.epoch;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.listMap;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.map;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.required;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.tagsFromList;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.text;
import static io.github.hectorvent.floci.services.sagemaker.SageMakerService.validation;

/**
 * SageMaker Feature Store: the {@code FeatureGroup} control plane (JSON protocol, SageMaker
 * target prefix) and the online-store data plane served by
 * {@link SageMakerFeatureStoreRuntimeController} (featurestore-runtime, REST-JSON).
 *
 * <p>The online store is genuinely stored per feature group and validated against the group's
 * feature definitions. Floci does not replicate records to an offline store (AWS writes Parquet
 * to S3 and registers a Glue table); a group created with an {@code OfflineStoreConfig} reports
 * its offline store as {@code Blocked} with a {@code BlockedReason} saying so.
 */
@ApplicationScoped
public class SageMakerFeatureStoreService {
    static final Duration CREATE_DURATION = Duration.ofSeconds(2);
    static final Duration DELETE_DURATION = Duration.ofSeconds(2);
    static final String STATE_CHANGE = "SageMaker Feature Group State Change";
    static final String OFFLINE_STORE_BLOCKED_REASON =
            "Floci does not replicate records to the offline store; only the online store is emulated.";

    private static final Pattern GROUP_NAME = Pattern.compile("^[a-zA-Z0-9]([_-]*[a-zA-Z0-9]){0,63}$");
    private static final Pattern FEATURE_NAME = Pattern.compile("^[a-zA-Z0-9]([-_]*[a-zA-Z0-9]){0,63}$");
    private static final Pattern ISO_EVENT_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$");
    private static final Set<String> RESERVED_FEATURE_NAMES = Set.of("is_deleted", "write_time", "api_invocation_time");
    private static final Set<String> FEATURE_TYPES = Set.of("Integral", "Fractional", "String");
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Set", "Vector");
    private static final Set<String> TARGET_STORES = Set.of("OnlineStore", "OfflineStore");
    private static final Map<String, Long> TTL_UNIT_MILLIS = Map.of(
            "Seconds", 1_000L,
            "Minutes", 60_000L,
            "Hours", 3_600_000L,
            "Days", 86_400_000L,
            "Weeks", 604_800_000L);
    private static final int MAX_FEATURE_DEFINITIONS = 2500;
    private static final int MAX_BATCH_GET_IDENTIFIERS = 10;
    private static final int MAX_BATCH_GET_RECORDS = 100;

    private final StorageBackend<String, FeatureGroupResource> groups;
    private final StorageBackend<String, FeatureRecordResource> records;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Predicate<String> bucketExists;
    private final Predicate<String> roleExists;
    private final StateEvents events;
    private final Scheduler scheduler;

    @Inject
    public SageMakerFeatureStoreService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper,
                                        Instance<S3Service> s3, Instance<IamService> iam,
                                        Instance<EventBridgeService> eventBridge) {
        this(storageFactory.create("sagemaker", "sagemaker-feature-groups.json",
                        new TypeReference<Map<String, FeatureGroupResource>>() {}),
                storageFactory.create("sagemaker", "sagemaker-feature-records.json",
                        new TypeReference<Map<String, FeatureRecordResource>>() {}),
                regionResolver, mapper, Clock.systemUTC(),
                SageMakerStateSupport.bucketExists(s3),
                SageMakerStateSupport.roleExists(iam, regionResolver::getAccountId),
                SageMakerStateSupport.eventBridge(eventBridge, mapper),
                Scheduler.DELAYED);
    }

    SageMakerFeatureStoreService(StorageBackend<String, FeatureGroupResource> groups,
                                 StorageBackend<String, FeatureRecordResource> records,
                                 RegionResolver regionResolver, ObjectMapper mapper, Clock clock,
                                 Predicate<String> bucketExists, Predicate<String> roleExists,
                                 StateEvents events, Scheduler scheduler) {
        this.groups = groups;
        this.records = records;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
        this.bucketExists = bucketExists;
        this.roleExists = roleExists;
        this.events = events;
        this.scheduler = scheduler;
    }

    // ─────────────────────────── Control plane ───────────────────────────

    public synchronized ObjectNode createFeatureGroup(JsonNode request, String region) {
        String name = required(request, "FeatureGroupName");
        if (!GROUP_NAME.matcher(name).matches()) {
            throw validation("1 validation error detected: Value '" + name
                    + "' at 'featureGroupName' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + GROUP_NAME.pattern());
        }
        if (group(region, name).isPresent()) {
            throw new AwsException("ResourceInUse", "Resource Already Exists: FeatureGroup with name " + name
                    + " already exists. Choose a different name.", 400);
        }
        String recordIdName = required(request, "RecordIdentifierFeatureName");
        String eventTimeName = required(request, "EventTimeFeatureName");
        JsonNode onlineConfig = request.path("OnlineStoreConfig");
        JsonNode offlineConfig = request.path("OfflineStoreConfig");
        boolean online = onlineConfig.path("EnableOnlineStore").asBoolean(false);
        boolean offline = offlineConfig.isObject();
        if (!online && !offline) {
            throw validation("At least one of OnlineStoreConfig with EnableOnlineStore set to true or OfflineStoreConfig must be specified.");
        }
        String storageType = onlineConfig.isObject() ? text(onlineConfig, "StorageType") : null;
        if (storageType != null && !Set.of("Standard", "InMemory").contains(storageType)) {
            throw validation("OnlineStoreConfig.StorageType must be one of [Standard, InMemory].");
        }
        if (onlineConfig.path("TtlDuration").isObject()) {
            ttlMillis(onlineConfig.path("TtlDuration"), "OnlineStoreConfig.TtlDuration");
        }

        List<Map<String, Object>> definitions = listMap(request.path("FeatureDefinitions"));
        if (definitions.isEmpty() || definitions.size() > MAX_FEATURE_DEFINITIONS) {
            throw validation("FeatureDefinitions must contain between 1 and " + MAX_FEATURE_DEFINITIONS + " features.");
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> def : definitions) {
            validateDefinition(def, "InMemory".equals(storageType));
            if (!seen.add(lower(featureName(def)))) {
                throw validation("The feature name " + featureName(def)
                        + " is duplicated in FeatureDefinitions. Feature names are not case sensitive.");
            }
        }
        Map<String, Object> recordIdDef = definition(definitions, recordIdName)
                .orElseThrow(() -> validation("The RecordIdentifierFeatureName " + recordIdName
                        + " must be one of the features in FeatureDefinitions."));
        if (!Set.of("String", "Integral").contains(featureType(recordIdDef)) || recordIdDef.get("CollectionType") != null) {
            throw validation("The RecordIdentifierFeatureName " + recordIdName + " must be of FeatureType String or Integral.");
        }
        Map<String, Object> eventTimeDef = definition(definitions, eventTimeName)
                .orElseThrow(() -> validation("The EventTimeFeatureName " + eventTimeName
                        + " must be one of the features in FeatureDefinitions."));
        if (!Set.of("String", "Fractional").contains(featureType(eventTimeDef)) || eventTimeDef.get("CollectionType") != null) {
            throw validation("The EventTimeFeatureName " + eventTimeName + " must be of FeatureType String or Fractional.");
        }

        String roleArn = text(request, "RoleArn");
        if (offline) {
            String s3Uri = text(offlineConfig.path("S3StorageConfig"), "S3Uri");
            if (s3Uri == null || s3Uri.isBlank()) {
                throw validation("OfflineStoreConfig.S3StorageConfig.S3Uri is required.");
            }
            S3Uri parsed;
            try {
                parsed = S3Uri.parse(s3Uri);
            } catch (IllegalArgumentException e) {
                throw validation("OfflineStoreConfig.S3StorageConfig.S3Uri: " + e.getMessage());
            }
            String tableFormat = text(offlineConfig, "TableFormat");
            if (tableFormat != null && !Set.of("Glue", "Iceberg").contains(tableFormat)) {
                throw validation("OfflineStoreConfig.TableFormat must be one of [Glue, Iceberg].");
            }
            if (roleArn == null || roleArn.isBlank()) {
                throw validation("RoleArn is required when OfflineStoreConfig is specified.");
            }
            validateRole(roleArn);
            if (!bucketExists.test(parsed.bucket())) {
                throw validation("The S3 bucket " + parsed.bucket() + " specified in OfflineStoreConfig does not exist.");
            }
        } else if (roleArn != null) {
            validateRole(roleArn);
        }
        JsonNode throughput = request.path("ThroughputConfig");
        if (throughput.isObject()) {
            validateThroughput(throughput);
        }

        long now = clock.millis();
        FeatureGroupResource fg = new FeatureGroupResource();
        fg.featureGroupName = name;
        fg.featureGroupArn = "arn:aws:sagemaker:" + region + ":" + regionResolver.getAccountId()
                + ":feature-group/" + lower(name);
        fg.recordIdentifierFeatureName = featureName(recordIdDef);
        fg.eventTimeFeatureName = featureName(eventTimeDef);
        fg.featureDefinitions = definitions;
        fg.onlineStoreConfig = onlineConfig.isObject() ? map(onlineConfig) : null;
        fg.offlineStoreConfig = offline ? map(offlineConfig) : null;
        fg.throughputConfig = throughput.isObject() ? map(throughput) : null;
        fg.roleArn = roleArn;
        fg.description = text(request, "Description");
        fg.featureGroupStatus = "Creating";
        fg.creationTime = now;
        fg.region = region;
        fg.accountId = regionResolver.getAccountId();
        fg.tags = tagsFromList(request.path("Tags"));
        SageMakerStateSupport.putFor(groups, fg.accountId, key(region, name), fg);
        scheduleSettle(fg, CREATE_DURATION);
        ObjectNode out = mapper.createObjectNode();
        out.put("FeatureGroupArn", fg.featureGroupArn);
        return out;
    }

    public synchronized ObjectNode describeFeatureGroup(JsonNode request, String region) {
        String nameOrArn = required(request, "FeatureGroupName");
        FeatureGroupResource fg = group(region, nameOrArn).orElseThrow(() -> notFound(nameOrArn));
        ObjectNode out = mapper.createObjectNode();
        out.put("FeatureGroupArn", fg.featureGroupArn);
        out.put("FeatureGroupName", fg.featureGroupName);
        out.put("RecordIdentifierFeatureName", fg.recordIdentifierFeatureName);
        out.put("EventTimeFeatureName", fg.eventTimeFeatureName);
        out.set("FeatureDefinitions", mapper.valueToTree(fg.featureDefinitions));
        out.put("CreationTime", epoch(fg.creationTime));
        if (fg.lastModifiedTime > 0) {
            out.put("LastModifiedTime", epoch(fg.lastModifiedTime));
        }
        if (fg.onlineStoreConfig != null) {
            out.set("OnlineStoreConfig", mapper.valueToTree(fg.onlineStoreConfig));
        }
        if (fg.offlineStoreConfig != null) {
            ObjectNode offline = mapper.valueToTree(fg.offlineStoreConfig);
            ObjectNode s3 = offline.get("S3StorageConfig") instanceof ObjectNode existing
                    ? existing : offline.putObject("S3StorageConfig");
            s3.put("ResolvedOutputS3Uri", resolvedOfflineUri(fg));
            out.set("OfflineStoreConfig", offline);
        }
        ObjectNode throughput = fg.throughputConfig != null
                ? mapper.valueToTree(fg.throughputConfig) : mapper.createObjectNode();
        if (!throughput.has("ThroughputMode")) {
            throughput.put("ThroughputMode", "OnDemand");
        }
        out.set("ThroughputConfig", throughput);
        if (fg.roleArn != null) {
            out.put("RoleArn", fg.roleArn);
        }
        out.put("FeatureGroupStatus", fg.featureGroupStatus);
        if (fg.offlineStoreStatus != null) {
            ObjectNode status = out.putObject("OfflineStoreStatus");
            status.put("Status", fg.offlineStoreStatus);
            if (fg.offlineStoreBlockedReason != null) {
                status.put("BlockedReason", fg.offlineStoreBlockedReason);
            }
        }
        if (fg.lastUpdateStatus != null) {
            out.putObject("LastUpdateStatus").put("Status", fg.lastUpdateStatus);
        }
        if (fg.failureReason != null) {
            out.put("FailureReason", fg.failureReason);
        }
        if (fg.description != null) {
            out.put("Description", fg.description);
        }
        if (onlineEnabled(fg)) {
            out.put("OnlineStoreTotalSizeBytes", onlineStoreSizeBytes(fg));
        }
        return out;
    }

    public synchronized ObjectNode deleteFeatureGroup(JsonNode request, String region) {
        String nameOrArn = required(request, "FeatureGroupName");
        FeatureGroupResource fg = group(region, nameOrArn).orElseThrow(() -> notFound(nameOrArn));
        switch (fg.featureGroupStatus) {
            case "Deleting" -> {
                return mapper.createObjectNode();
            }
            case "Creating" -> throw new AwsException("ResourceInUse", "FeatureGroup " + fg.featureGroupName
                    + " is in Creating status and cannot be deleted until creation completes.", 400);
            default -> { }
        }
        fg.featureGroupStatus = "Deleting";
        fg.lastModifiedTime = clock.millis();
        SageMakerStateSupport.putFor(groups, fg.accountId, key(fg.region, fg.featureGroupName), fg);
        publishStateChange(fg);
        scheduleSettle(fg, DELETE_DURATION);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode updateFeatureGroup(JsonNode request, String region) {
        String nameOrArn = required(request, "FeatureGroupName");
        FeatureGroupResource fg = group(region, nameOrArn).orElseThrow(() -> notFound(nameOrArn));
        if (!"Created".equals(fg.featureGroupStatus)) {
            throw new AwsException("ResourceInUse", "FeatureGroup " + fg.featureGroupName + " is in "
                    + fg.featureGroupStatus + " status and cannot be updated.", 400);
        }
        List<Map<String, Object>> additions = listMap(request.path("FeatureAdditions"));
        if (additions.size() > 100) {
            throw validation("FeatureAdditions can contain at most 100 features.");
        }
        Set<String> existing = new HashSet<>();
        fg.featureDefinitions.forEach(d -> existing.add(lower(featureName(d))));
        if (fg.featureDefinitions.size() + additions.size() > MAX_FEATURE_DEFINITIONS) {
            throw validation("A FeatureGroup can contain at most " + MAX_FEATURE_DEFINITIONS + " features.");
        }
        String storageType = fg.onlineStoreConfig == null
                ? null : SageMakerEndpointManager.string(fg.onlineStoreConfig.get("StorageType"));
        for (Map<String, Object> def : additions) {
            validateDefinition(def, "InMemory".equals(storageType));
            if (!existing.add(lower(featureName(def)))) {
                throw validation("The feature name " + featureName(def) + " already exists in FeatureGroup "
                        + fg.featureGroupName + ".");
            }
        }
        JsonNode onlineUpdate = request.path("OnlineStoreConfig");
        if (onlineUpdate.isObject()) {
            if (!onlineEnabled(fg)) {
                throw validation("OnlineStoreConfig can only be updated for a FeatureGroup with an online store.");
            }
            if (onlineUpdate.path("TtlDuration").isObject()) {
                ttlMillis(onlineUpdate.path("TtlDuration"), "OnlineStoreConfig.TtlDuration");
                fg.onlineStoreConfig.put("TtlDuration", map(onlineUpdate.path("TtlDuration")));
            }
        }
        JsonNode throughputUpdate = request.path("ThroughputConfig");
        if (throughputUpdate.isObject()) {
            validateThroughput(throughputUpdate);
            fg.throughputConfig = map(throughputUpdate);
        }
        fg.featureDefinitions.addAll(additions);
        fg.lastModifiedTime = clock.millis();
        fg.lastUpdateStatus = "Successful";
        SageMakerStateSupport.putFor(groups, fg.accountId, key(fg.region, fg.featureGroupName), fg);
        ObjectNode out = mapper.createObjectNode();
        out.put("FeatureGroupArn", fg.featureGroupArn);
        return out;
    }

    public synchronized ObjectNode listFeatureGroups(JsonNode request, String region) {
        List<FeatureGroupResource> all = new ArrayList<>();
        for (FeatureGroupResource fg : groups.scan(k -> k.startsWith(region + "::"))) {
            settle(fg).ifPresent(all::add);
        }
        String nameContains = text(request, "NameContains");
        String statusEquals = text(request, "FeatureGroupStatusEquals");
        String offlineEquals = text(request, "OfflineStoreStatusEquals");
        JsonNode after = request.path("CreationTimeAfter");
        JsonNode before = request.path("CreationTimeBefore");
        List<FeatureGroupResource> filtered = all.stream()
                .filter(fg -> nameContains == null || fg.featureGroupName.contains(nameContains))
                .filter(fg -> statusEquals == null || statusEquals.equals(fg.featureGroupStatus))
                .filter(fg -> offlineEquals == null || offlineEquals.equals(fg.offlineStoreStatus))
                .filter(fg -> !after.isNumber() || fg.creationTime > (long) (after.asDouble() * 1000))
                .filter(fg -> !before.isNumber() || fg.creationTime < (long) (before.asDouble() * 1000))
                .toList();
        String sortBy = Optional.ofNullable(text(request, "SortBy")).orElse("CreationTime");
        Comparator<FeatureGroupResource> comparator = switch (sortBy) {
            case "Name" -> Comparator.comparing(fg -> fg.featureGroupName);
            case "FeatureGroupStatus" -> Comparator.comparing(fg -> fg.featureGroupStatus);
            case "OfflineStoreStatus" -> Comparator.comparing(fg -> Objects.toString(fg.offlineStoreStatus, ""));
            case "CreationTime" -> Comparator.comparingLong(fg -> fg.creationTime);
            default -> throw validation("SortBy must be one of [Name, FeatureGroupStatus, OfflineStoreStatus, CreationTime].");
        };
        String sortOrder = Optional.ofNullable(text(request, "SortOrder")).orElse("Descending");
        if (!Set.of("Ascending", "Descending").contains(sortOrder)) {
            throw validation("SortOrder must be one of [Ascending, Descending].");
        }
        if ("Descending".equals(sortOrder)) {
            comparator = comparator.reversed();
        }
        List<FeatureGroupResource> sorted = filtered.stream()
                .sorted(comparator.thenComparing(fg -> fg.featureGroupName)).toList();
        int max = boundedInt(request, "MaxResults", 10, 1, 100);
        int start = offsetToken(text(request, "NextToken"));
        ArrayNode summaries = mapper.createArrayNode();
        sorted.stream().skip(start).limit(max).forEach(fg -> {
            ObjectNode n = summaries.addObject();
            n.put("FeatureGroupName", fg.featureGroupName);
            n.put("FeatureGroupArn", fg.featureGroupArn);
            n.put("CreationTime", epoch(fg.creationTime));
            n.put("FeatureGroupStatus", fg.featureGroupStatus);
            if (fg.offlineStoreStatus != null) {
                ObjectNode status = n.putObject("OfflineStoreStatus");
                status.put("Status", fg.offlineStoreStatus);
                if (fg.offlineStoreBlockedReason != null) {
                    status.put("BlockedReason", fg.offlineStoreBlockedReason);
                }
            }
        });
        ObjectNode out = mapper.createObjectNode();
        out.set("FeatureGroupSummaries", summaries);
        if (start + max < sorted.size()) {
            out.put("NextToken", Integer.toString(start + max));
        }
        return out;
    }

    /** Applies a tag action when {@code ResourceArn} names a feature group; empty for other ARNs. */
    synchronized Optional<ObjectNode> tags(String action, JsonNode request) {
        return tagTarget(required(request, "ResourceArn")).map(t -> t.apply(action, request, mapper));
    }

    private Optional<SageMakerTagTarget> tagTarget(String arn) {
        if (!arn.contains(":feature-group/")) {
            return Optional.empty();
        }
        FeatureGroupResource fg = group(arn.split(":", 6)[3], arn).orElseThrow(() -> notFound(arn));
        return Optional.of(new SageMakerTagTarget(fg.tags,
                () -> SageMakerStateSupport.putFor(groups, fg.accountId, key(fg.region, fg.featureGroupName), fg)));
    }

    // ─────────────────────────── Online store ───────────────────────────

    public synchronized ObjectNode putRecord(String nameOrArn, JsonNode request, String region) {
        FeatureGroupResource fg = runtimeGroup(region, nameOrArn);
        Long ttl = request.path("TtlDuration").isObject() ? runtimeTtlMillis(request.path("TtlDuration")) : null;
        writeRecord(fg, request.path("Record"), targetStores(fg, request.path("TargetStores")), ttl);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode getRecord(String nameOrArn, String recordId, List<String> featureNames,
                                             String expirationTimeResponse, String region) {
        FeatureGroupResource fg = runtimeGroup(region, nameOrArn);
        if (recordId == null || recordId.isEmpty()) {
            throw runtimeValidation("RecordIdentifierValueAsString is required.");
        }
        requireOnlineStore(fg);
        validateExpirationTimeResponse(expirationTimeResponse);
        Set<String> selected = selectFeatures(fg, featureNames);
        ObjectNode out = mapper.createObjectNode();
        liveRecord(fg, recordId).ifPresent(record -> {
            out.set("Record", renderRecord(fg, record, selected));
            if ("Enabled".equals(expirationTimeResponse) && record.expiresAtMillis != null) {
                out.put("ExpiresAt", Instant.ofEpochMilli(record.expiresAtMillis).toString());
            }
        });
        return out;
    }

    public synchronized ObjectNode deleteRecord(String nameOrArn, String recordId, String eventTime,
                                                List<String> targetStores, String deletionMode, String region) {
        FeatureGroupResource fg = runtimeGroup(region, nameOrArn);
        if (recordId == null || recordId.isEmpty()) {
            throw runtimeValidation("RecordIdentifierValueAsString is required.");
        }
        if (eventTime == null || eventTime.isEmpty()) {
            throw runtimeValidation("EventTime is required.");
        }
        String mode = deletionMode == null ? "SoftDelete" : deletionMode;
        if (!Set.of("SoftDelete", "HardDelete").contains(mode)) {
            throw runtimeValidation("DeletionMode must be one of [SoftDelete, HardDelete].");
        }
        long deleteTime = parseEventTime(fg, eventTime, "EventTime");
        ArrayNode stores = mapper.createArrayNode();
        if (targetStores != null) {
            targetStores.forEach(stores::add);
        }
        if (!targetStores(fg, stores).contains("OnlineStore")) {
            return mapper.createObjectNode();
        }
        FeatureRecordResource existing = currentRecord(fg, recordId).orElse(null);
        if (existing == null) {
            return mapper.createObjectNode();
        }
        if (deleteTime <= existing.eventTimeMillis) {
            if ("HardDelete".equals(mode)) {
                throw runtimeValidation("The EventTime " + eventTime + " of the delete request must be later than the "
                        + "EventTime of the existing record " + recordId + " in the OnlineStore.");
            }
            // SoftDelete with a stale EventTime leaves the existing online record in place.
            return mapper.createObjectNode();
        }
        String recordKey = recordKey(fg, recordId);
        if ("HardDelete".equals(mode)) {
            SageMakerStateSupport.deleteFor(records, fg.accountId, recordKey);
        } else {
            existing.values = new LinkedHashMap<>();
            existing.deleted = true;
            existing.eventTimeMillis = deleteTime;
            existing.expiresAtMillis = null;
            SageMakerStateSupport.putFor(records, fg.accountId, recordKey, existing);
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listRecords(String nameOrArn, JsonNode request, String region) {
        FeatureGroupResource fg = group(region, nameOrArn)
                .filter(g -> !"Deleting".equals(g.featureGroupStatus))
                .orElseThrow(() -> new AwsException("ResourceNotFound", resourceNotFoundMessage(nameOrArn), 404));
        requireCreated(fg);
        requireOnlineStore(fg);
        int max;
        try {
            max = boundedInt(request, "MaxResults", 100, 1, 1000);
        } catch (AwsException e) {
            throw runtimeValidation(e.getMessage());
        }
        boolean includeSoftDeleted = request.path("IncludeSoftDeletedRecords").asBoolean(false);
        String nextToken = text(request, "NextToken");
        long now = clock.millis();
        List<String> ids = groupRecords(fg).stream()
                .filter(r -> includeSoftDeleted || !r.deleted)
                .filter(r -> r.expiresAtMillis == null || r.expiresAtMillis > now)
                .map(r -> r.recordIdentifier)
                .sorted()
                .filter(id -> nextToken == null || id.compareTo(nextToken) > 0)
                .toList();
        ArrayNode out = mapper.createArrayNode();
        ids.stream().limit(max).forEach(out::add);
        ObjectNode response = mapper.createObjectNode();
        response.set("RecordIdentifiers", out);
        if (ids.size() > max) {
            response.put("NextToken", ids.get(max - 1));
        }
        return response;
    }

    public synchronized ObjectNode batchGetRecord(JsonNode request, String region) {
        JsonNode identifiers = request.path("Identifiers");
        if (!identifiers.isArray() || identifiers.isEmpty() || identifiers.size() > MAX_BATCH_GET_IDENTIFIERS) {
            throw runtimeValidation("Identifiers must contain between 1 and " + MAX_BATCH_GET_IDENTIFIERS + " items.");
        }
        String expirationTimeResponse = text(request, "ExpirationTimeResponse");
        validateExpirationTimeResponse(expirationTimeResponse);
        ArrayNode results = mapper.createArrayNode();
        ArrayNode errors = mapper.createArrayNode();
        for (JsonNode identifier : identifiers) {
            String nameOrArn = text(identifier, "FeatureGroupName");
            JsonNode ids = identifier.path("RecordIdentifiersValueAsString");
            if (nameOrArn == null || nameOrArn.isBlank()) {
                throw runtimeValidation("Identifiers[].FeatureGroupName is required.");
            }
            if (!ids.isArray() || ids.isEmpty() || ids.size() > MAX_BATCH_GET_RECORDS) {
                throw runtimeValidation("RecordIdentifiersValueAsString must contain between 1 and "
                        + MAX_BATCH_GET_RECORDS + " items.");
            }
            FeatureGroupResource fg;
            Set<String> selected;
            try {
                fg = runtimeGroup(region, nameOrArn);
                requireOnlineStore(fg);
                List<String> featureNames = new ArrayList<>();
                identifier.path("FeatureNames").forEach(n -> featureNames.add(n.asText()));
                selected = selectFeatures(fg, featureNames);
            } catch (AwsException e) {
                ids.forEach(id -> {
                    ObjectNode error = errors.addObject();
                    error.put("FeatureGroupName", nameOrArn);
                    error.put("RecordIdentifierValueAsString", id.asText());
                    error.put("ErrorCode", e.getErrorCode());
                    error.put("ErrorMessage", e.getMessage());
                });
                continue;
            }
            for (JsonNode id : ids) {
                liveRecord(fg, id.asText()).ifPresent(record -> {
                    ObjectNode result = results.addObject();
                    result.put("FeatureGroupName", nameOrArn);
                    result.put("RecordIdentifierValueAsString", record.recordIdentifier);
                    result.set("Record", renderRecord(fg, record, selected));
                    if ("Enabled".equals(expirationTimeResponse) && record.expiresAtMillis != null) {
                        result.put("ExpiresAt", Instant.ofEpochMilli(record.expiresAtMillis).toString());
                    }
                });
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Records", results);
        out.set("Errors", errors);
        out.set("UnprocessedIdentifiers", mapper.createArrayNode());
        return out;
    }

    public synchronized ObjectNode batchWriteRecord(JsonNode request, String region) {
        JsonNode entries = request.path("Entries");
        if (!entries.isArray() || entries.isEmpty()) {
            throw runtimeValidation("Entries must contain at least 1 item.");
        }
        Long defaultTtl = request.path("TtlDuration").isObject()
                ? runtimeTtlMillis(request.path("TtlDuration")) : null;
        ArrayNode errors = mapper.createArrayNode();
        for (JsonNode entry : entries) {
            try {
                String nameOrArn = text(entry, "FeatureGroupName");
                if (nameOrArn == null || nameOrArn.isBlank()) {
                    throw runtimeValidation("Entries[].FeatureGroupName is required.");
                }
                FeatureGroupResource fg = runtimeGroup(region, nameOrArn);
                Long ttl = entry.path("TtlDuration").isObject()
                        ? Long.valueOf(runtimeTtlMillis(entry.path("TtlDuration"))) : defaultTtl;
                writeRecord(fg, entry.path("Record"), targetStores(fg, entry.path("TargetStores")), ttl);
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.set("Entry", entry.deepCopy());
                error.put("ErrorCode", e.getErrorCode());
                error.put("ErrorMessage", e.getMessage());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Errors", errors);
        out.set("UnprocessedEntries", mapper.createArrayNode());
        return out;
    }

    // ─────────────────────────── Status transitions ───────────────────────────

    /**
     * Applies any status transition whose time has come: {@code Creating} becomes {@code Created}
     * and a finished {@code Deleting} group is removed together with its online-store records.
     * Returns empty once the group is gone.
     */
    private Optional<FeatureGroupResource> settle(FeatureGroupResource fg) {
        long now = clock.millis();
        if ("Creating".equals(fg.featureGroupStatus) && now >= fg.creationTime + CREATE_DURATION.toMillis()) {
            fg.featureGroupStatus = "Created";
            if (fg.offlineStoreConfig != null) {
                fg.offlineStoreStatus = "Blocked";
                fg.offlineStoreBlockedReason = OFFLINE_STORE_BLOCKED_REASON;
            }
            SageMakerStateSupport.putFor(groups, fg.accountId, key(fg.region, fg.featureGroupName), fg);
            publishStateChange(fg);
        } else if ("Deleting".equals(fg.featureGroupStatus)
                && now >= fg.lastModifiedTime + DELETE_DURATION.toMillis()) {
            String prefix = recordPrefix(fg);
            SageMakerStateSupport.keysFor(records, fg.accountId, k -> k.startsWith(prefix))
                    .forEach(k -> SageMakerStateSupport.deleteFor(records, fg.accountId, k));
            SageMakerStateSupport.deleteFor(groups, fg.accountId, key(fg.region, fg.featureGroupName));
            return Optional.empty();
        }
        return Optional.of(fg);
    }

    private void scheduleSettle(FeatureGroupResource fg, Duration delay) {
        String accountId = fg.accountId;
        String groupKey = key(fg.region, fg.featureGroupName);
        scheduler.schedule(delay.plusMillis(50), () -> settleInBackground(accountId, groupKey));
    }

    /** Runs outside any request, so every storage access names the owning account explicitly. */
    synchronized void settleInBackground(String accountId, String groupKey) {
        SageMakerStateSupport.getFor(groups, accountId, groupKey).ifPresent(this::settle);
    }

    private void publishStateChange(FeatureGroupResource fg) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("FeatureGroupArn", fg.featureGroupArn);
        detail.put("FeatureGroupName", fg.featureGroupName);
        detail.put("RecordIdentifierFeatureName", fg.recordIdentifierFeatureName);
        detail.put("EventTimeFeatureName", fg.eventTimeFeatureName);
        detail.put("FeatureGroupStatus", fg.featureGroupStatus);
        if (fg.offlineStoreStatus != null) {
            ObjectNode status = detail.putObject("OfflineStoreStatus");
            status.put("Status", fg.offlineStoreStatus);
            if (fg.offlineStoreBlockedReason != null) {
                status.put("BlockedReason", fg.offlineStoreBlockedReason);
            }
        }
        detail.put("CreationTime", Instant.ofEpochMilli(fg.creationTime).toString());
        events.publish(fg.region, fg.accountId, STATE_CHANGE, fg.featureGroupArn, detail);
    }

    // ─────────────────────────── Records ───────────────────────────

    private void writeRecord(FeatureGroupResource fg, JsonNode record, Set<String> targets, Long recordTtl) {
        Map<String, Object> values = validateRecord(fg, record);
        String recordId = (String) values.get(fg.recordIdentifierFeatureName);
        long eventTime = parseEventTime(fg, (String) values.get(fg.eventTimeFeatureName), fg.eventTimeFeatureName);
        if (!targets.contains("OnlineStore")) {
            return;
        }
        FeatureRecordResource existing = currentRecord(fg, recordId).orElse(null);
        // A record whose EventTime is not newer than the stored one is historic: AWS writes it to
        // the offline store only and leaves the online store unchanged.
        if (existing != null && eventTime <= existing.eventTimeMillis) {
            return;
        }
        Long ttl = recordTtl != null ? recordTtl : groupTtl(fg);
        FeatureRecordResource stored = new FeatureRecordResource();
        stored.featureGroupArn = fg.featureGroupArn;
        stored.featureGroupCreationTime = fg.creationTime;
        stored.recordIdentifier = recordId;
        stored.eventTimeMillis = eventTime;
        stored.values = values;
        stored.deleted = false;
        stored.expiresAtMillis = ttl == null ? null : eventTime + ttl;
        SageMakerStateSupport.putFor(records, fg.accountId, recordKey(fg, recordId), stored);
    }

    private Map<String, Object> validateRecord(FeatureGroupResource fg, JsonNode record) {
        if (!record.isArray() || record.isEmpty()) {
            throw runtimeValidation("Record must contain at least one FeatureValue.");
        }
        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        fg.featureDefinitions.forEach(d -> definitions.put(lower(featureName(d)), d));
        Map<String, Object> values = new LinkedHashMap<>();
        for (JsonNode value : record) {
            String name = text(value, "FeatureName");
            if (name == null || name.isEmpty()) {
                throw runtimeValidation("FeatureName is required for every FeatureValue in Record.");
            }
            Map<String, Object> def = definitions.get(lower(name));
            if (def == null) {
                throw runtimeValidation("The feature " + name + " is not defined in FeatureGroup "
                        + fg.featureGroupName + ".");
            }
            String canonical = featureName(def);
            if (values.containsKey(canonical)) {
                throw runtimeValidation("The feature " + name + " appears more than once in Record.");
            }
            JsonNode scalar = value.path("ValueAsString");
            JsonNode list = value.path("ValueAsStringList");
            boolean hasScalar = !scalar.isMissingNode() && !scalar.isNull();
            boolean hasList = !list.isMissingNode() && !list.isNull();
            if (hasScalar == hasList) {
                throw runtimeValidation("Exactly one of ValueAsString or ValueAsStringList must be set for feature "
                        + name + ".");
            }
            String type = featureType(def);
            String collectionType = SageMakerEndpointManager.string(def.get("CollectionType"));
            if (collectionType == null) {
                if (hasList) {
                    throw runtimeValidation("The feature " + name + " is not a collection feature; use ValueAsString.");
                }
                validateScalar(name, type, scalar.asText());
                values.put(canonical, scalar.asText());
            } else {
                if (hasScalar || !list.isArray()) {
                    throw runtimeValidation("The feature " + name + " is a " + collectionType
                            + " collection feature; use ValueAsStringList.");
                }
                List<String> items = new ArrayList<>();
                for (JsonNode item : list) {
                    validateScalar(name, type, item.asText());
                    items.add(item.asText());
                }
                if ("Set".equals(collectionType) && new HashSet<>(items).size() != items.size()) {
                    throw runtimeValidation("The Set feature " + name + " contains duplicate values.");
                }
                if ("Vector".equals(collectionType)) {
                    int dimension = vectorDimension(def);
                    if (items.size() != dimension) {
                        throw runtimeValidation("The Vector feature " + name + " must contain exactly " + dimension
                                + " values but contains " + items.size() + ".");
                    }
                }
                values.put(canonical, items);
            }
        }
        if (!(values.get(fg.recordIdentifierFeatureName) instanceof String id) || id.isEmpty()) {
            throw runtimeValidation("Record must contain a value for the RecordIdentifierFeatureName "
                    + fg.recordIdentifierFeatureName + ".");
        }
        if (!(values.get(fg.eventTimeFeatureName) instanceof String time) || time.isEmpty()) {
            throw runtimeValidation("Record must contain a value for the EventTimeFeatureName "
                    + fg.eventTimeFeatureName + ".");
        }
        return values;
    }

    private static void validateScalar(String name, String type, String value) {
        switch (type) {
            case "Integral" -> {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw runtimeValidation("The value " + value + " of feature " + name + " is not a valid Integral.");
                }
            }
            case "Fractional" -> {
                boolean valid;
                try {
                    valid = Double.isFinite(Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    valid = false;
                }
                if (!valid) {
                    throw runtimeValidation("The value " + value + " of feature " + name + " is not a valid Fractional.");
                }
            }
            default -> { }
        }
    }

    /** Event times are ISO-8601 ({@code yyyy-MM-dd'T'HH:mm:ss[.SSS]Z}) for String features, epoch seconds for Fractional. */
    private long parseEventTime(FeatureGroupResource fg, String value, String field) {
        String type = definition(fg.featureDefinitions, fg.eventTimeFeatureName)
                .map(SageMakerFeatureStoreService::featureType).orElse("String");
        try {
            if ("Fractional".equals(type)) {
                double seconds = Double.parseDouble(value);
                if (!Double.isFinite(seconds) || seconds < 0) {
                    throw new NumberFormatException(value);
                }
                return (long) (seconds * 1000);
            }
            if (!ISO_EVENT_TIME.matcher(value).matches()) {
                throw new DateTimeParseException("Unsupported event time format", value, 0);
            }
            return Instant.parse(value).toEpochMilli();
        } catch (NumberFormatException | DateTimeParseException e) {
            throw runtimeValidation("The " + field + " value " + value + " is invalid. "
                    + ("Fractional".equals(type)
                    ? "It must be a Unix epoch time in seconds."
                    : "It must be an ISO-8601 string in the format yyyy-MM-dd'T'HH:mm:ssZ or yyyy-MM-dd'T'HH:mm:ss.SSSZ."));
        }
    }

    private Optional<FeatureRecordResource> currentRecord(FeatureGroupResource fg, String recordId) {
        return SageMakerStateSupport.getFor(records, fg.accountId, recordKey(fg, recordId))
                .filter(r -> r.featureGroupCreationTime == fg.creationTime);
    }

    /** The record as {@code GetRecord} sees it: present, not soft-deleted, and not expired. */
    private Optional<FeatureRecordResource> liveRecord(FeatureGroupResource fg, String recordId) {
        long now = clock.millis();
        return currentRecord(fg, recordId)
                .filter(r -> !r.deleted)
                .filter(r -> r.expiresAtMillis == null || r.expiresAtMillis > now);
    }

    private List<FeatureRecordResource> groupRecords(FeatureGroupResource fg) {
        String prefix = recordPrefix(fg);
        List<FeatureRecordResource> out = new ArrayList<>();
        for (String k : SageMakerStateSupport.keysFor(records, fg.accountId, key -> key.startsWith(prefix))) {
            SageMakerStateSupport.getFor(records, fg.accountId, k)
                    .filter(r -> r.featureGroupCreationTime == fg.creationTime)
                    .ifPresent(out::add);
        }
        return out;
    }

    private ArrayNode renderRecord(FeatureGroupResource fg, FeatureRecordResource record, Set<String> selected) {
        ArrayNode out = mapper.createArrayNode();
        for (Map<String, Object> def : fg.featureDefinitions) {
            String name = featureName(def);
            Object value = record.values.get(name);
            if (value == null || (selected != null && !selected.contains(lower(name)))) {
                continue;
            }
            ObjectNode n = out.addObject();
            n.put("FeatureName", name);
            if (value instanceof List<?> list) {
                ArrayNode items = n.putArray("ValueAsStringList");
                list.forEach(item -> items.add(String.valueOf(item)));
            } else {
                n.put("ValueAsString", String.valueOf(value));
            }
        }
        return out;
    }

    private long onlineStoreSizeBytes(FeatureGroupResource fg) {
        long total = 0;
        for (FeatureRecordResource record : groupRecords(fg)) {
            if (record.deleted) {
                continue;
            }
            try {
                total += mapper.writeValueAsBytes(record.values).length;
            } catch (Exception ignored) {
                // Size is informational; an unserializable value is not counted.
            }
        }
        return total;
    }

    // ─────────────────────────── Lookups and validation ───────────────────────────

    /** Resolves a feature group by name or ARN, applying any due status transition. */
    private Optional<FeatureGroupResource> group(String region, String nameOrArn) {
        String name = nameOrArn;
        String lookupRegion = region;
        if (nameOrArn.startsWith("arn:")) {
            String[] parts = nameOrArn.split(":", 6);
            if (parts.length < 6 || !parts[5].startsWith("feature-group/")
                    || !parts[4].equals(regionResolver.getAccountId())) {
                return Optional.empty();
            }
            lookupRegion = parts[3];
            name = parts[5].substring("feature-group/".length());
        }
        return groups.get(key(lookupRegion, name)).flatMap(this::settle);
    }

    private FeatureGroupResource runtimeGroup(String region, String nameOrArn) {
        FeatureGroupResource fg = group(region, nameOrArn)
                .filter(g -> !"Deleting".equals(g.featureGroupStatus))
                .orElseThrow(() -> runtimeValidation(resourceNotFoundMessage(nameOrArn)));
        requireCreated(fg);
        return fg;
    }

    private static void requireCreated(FeatureGroupResource fg) {
        if (!"Created".equals(fg.featureGroupStatus)) {
            throw runtimeValidation("The FeatureGroup " + fg.featureGroupName + " is in " + fg.featureGroupStatus
                    + " status. Records can only be accessed when the FeatureGroup is in Created status.");
        }
    }

    private static void requireOnlineStore(FeatureGroupResource fg) {
        if (!onlineEnabled(fg)) {
            throw runtimeValidation("The FeatureGroup " + fg.featureGroupName + " does not have an OnlineStore.");
        }
    }

    private static boolean onlineEnabled(FeatureGroupResource fg) {
        return fg.onlineStoreConfig != null && Boolean.TRUE.equals(fg.onlineStoreConfig.get("EnableOnlineStore"));
    }

    private Set<String> targetStores(FeatureGroupResource fg, JsonNode requested) {
        Set<String> targets = new HashSet<>();
        if (requested.isArray() && !requested.isEmpty()) {
            for (JsonNode store : requested) {
                if (!TARGET_STORES.contains(store.asText())) {
                    throw runtimeValidation("TargetStores must contain only OnlineStore or OfflineStore.");
                }
                targets.add(store.asText());
            }
            if (targets.contains("OnlineStore") && !onlineEnabled(fg)) {
                throw runtimeValidation("The FeatureGroup " + fg.featureGroupName + " does not have an OnlineStore.");
            }
            if (targets.contains("OfflineStore") && fg.offlineStoreConfig == null) {
                throw runtimeValidation("The FeatureGroup " + fg.featureGroupName + " does not have an OfflineStore.");
            }
            return targets;
        }
        if (onlineEnabled(fg)) {
            targets.add("OnlineStore");
        }
        if (fg.offlineStoreConfig != null) {
            targets.add("OfflineStore");
        }
        return targets;
    }

    private static Set<String> selectFeatures(FeatureGroupResource fg, List<String> featureNames) {
        if (featureNames == null || featureNames.isEmpty()) {
            return null;
        }
        Set<String> defined = new HashSet<>();
        fg.featureDefinitions.forEach(d -> defined.add(lower(featureName(d))));
        Set<String> selected = new HashSet<>();
        for (String name : featureNames) {
            if (!defined.contains(lower(name))) {
                throw runtimeValidation("The feature " + name + " is not defined in FeatureGroup "
                        + fg.featureGroupName + ".");
            }
            selected.add(lower(name));
        }
        return selected;
    }

    private static void validateExpirationTimeResponse(String value) {
        if (value != null && !Set.of("Enabled", "Disabled").contains(value)) {
            throw runtimeValidation("ExpirationTimeResponse must be one of [Enabled, Disabled].");
        }
    }

    private Long groupTtl(FeatureGroupResource fg) {
        if (fg.onlineStoreConfig == null || !(fg.onlineStoreConfig.get("TtlDuration") instanceof Map<?, ?> ttl)) {
            return null;
        }
        return ttlMillis(mapper.valueToTree(ttl), "OnlineStoreConfig.TtlDuration");
    }

    private static long runtimeTtlMillis(JsonNode ttl) {
        try {
            return ttlMillis(ttl, "TtlDuration");
        } catch (AwsException e) {
            throw runtimeValidation(e.getMessage());
        }
    }

    private static long ttlMillis(JsonNode ttl, String field) {
        String unit = text(ttl, "Unit");
        JsonNode value = ttl.path("Value");
        Long unitMillis = unit == null ? null : TTL_UNIT_MILLIS.get(unit);
        if (unitMillis == null) {
            throw validation(field + ".Unit must be one of [Seconds, Minutes, Hours, Days, Weeks].");
        }
        if (!value.canConvertToLong() || value.asLong() < 1) {
            throw validation(field + ".Value must be a positive integer.");
        }
        return value.asLong() * unitMillis;
    }

    private static void validateThroughput(JsonNode throughput) {
        String mode = text(throughput, "ThroughputMode");
        if (mode != null && !Set.of("OnDemand", "Provisioned").contains(mode)) {
            throw validation("ThroughputConfig.ThroughputMode must be one of [OnDemand, Provisioned].");
        }
        boolean hasUnits = throughput.has("ProvisionedReadCapacityUnits") || throughput.has("ProvisionedWriteCapacityUnits");
        if ("Provisioned".equals(mode) && (!throughput.path("ProvisionedReadCapacityUnits").canConvertToInt()
                || !throughput.path("ProvisionedWriteCapacityUnits").canConvertToInt())) {
            throw validation("ProvisionedReadCapacityUnits and ProvisionedWriteCapacityUnits are required when "
                    + "ThroughputMode is Provisioned.");
        }
        if (!"Provisioned".equals(mode) && hasUnits) {
            throw validation("Provisioned capacity units can only be set when ThroughputMode is Provisioned.");
        }
    }

    private void validateRole(String roleArn) {
        if (!SageMakerStateSupport.isRoleArn(roleArn)) {
            throw validation("RoleArn " + roleArn + " is not a valid IAM role ARN.");
        }
        if (!roleExists.test(roleArn)) {
            throw validation("Could not access RoleArn " + roleArn
                    + ". Please ensure the role exists and grants SageMaker permission to assume it.");
        }
    }

    private static void validateDefinition(Map<String, Object> def, boolean inMemoryStore) {
        String name = featureName(def);
        if (name == null || !FEATURE_NAME.matcher(name).matches()) {
            throw validation("FeatureName " + name + " must satisfy regular expression pattern: " + FEATURE_NAME.pattern());
        }
        if (RESERVED_FEATURE_NAMES.contains(lower(name))) {
            throw validation("The feature name " + name + " is reserved. Reserved feature names are "
                    + String.join(", ", RESERVED_FEATURE_NAMES.stream().sorted().toList()) + ".");
        }
        String type = featureType(def);
        if (type == null || !FEATURE_TYPES.contains(type)) {
            throw validation("FeatureType of feature " + name + " must be one of [Integral, Fractional, String].");
        }
        String collectionType = SageMakerEndpointManager.string(def.get("CollectionType"));
        if (collectionType != null) {
            if (!COLLECTION_TYPES.contains(collectionType)) {
                throw validation("CollectionType of feature " + name + " must be one of [List, Set, Vector].");
            }
            if (!inMemoryStore) {
                throw validation("Collection type features are only supported by an InMemory OnlineStore. Feature "
                        + name + " has CollectionType " + collectionType + ".");
            }
            if ("Vector".equals(collectionType)) {
                vectorDimension(def);
            }
        }
    }

    private static int vectorDimension(Map<String, Object> def) {
        Object config = def.get("CollectionConfig");
        Object vector = config instanceof Map<?, ?> c ? c.get("VectorConfig") : null;
        Object dimension = vector instanceof Map<?, ?> v ? v.get("Dimension") : null;
        if (!(dimension instanceof Number n) || n.intValue() < 1 || n.intValue() > 8192) {
            throw validation("Vector feature " + featureName(def)
                    + " requires CollectionConfig.VectorConfig.Dimension between 1 and 8192.");
        }
        return n.intValue();
    }

    private static Optional<Map<String, Object>> definition(List<Map<String, Object>> definitions, String name) {
        return definitions.stream().filter(d -> lower(name).equals(lower(featureName(d)))).findFirst();
    }

    private static String featureName(Map<String, Object> def) {
        return SageMakerEndpointManager.string(def.get("FeatureName"));
    }

    private static String featureType(Map<String, Object> def) {
        return SageMakerEndpointManager.string(def.get("FeatureType"));
    }

    private static String resolvedOfflineUri(FeatureGroupResource fg) {
        Object s3 = fg.offlineStoreConfig.get("S3StorageConfig");
        String base = s3 instanceof Map<?, ?> m ? String.valueOf(m.get("S3Uri")) : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + fg.accountId + "/sagemaker/" + fg.region + "/offline-store/" + fg.featureGroupName + "-"
                + (fg.creationTime / 1000) + "/data";
    }

    static int boundedInt(JsonNode request, String field, int defaultValue, int min, int max) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
            throw validation(field + " must be between " + min + " and " + max + ".");
        }
        return node.asInt();
    }

    static int offsetToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw new NumberFormatException(token);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw validation("The NextToken " + token + " is invalid.");
        }
    }

    private static AwsException notFound(String nameOrArn) {
        return new AwsException("ResourceNotFound", resourceNotFoundMessage(nameOrArn), 400);
    }

    static String resourceNotFoundMessage(String nameOrArn) {
        return "Resource Not Found: Amazon SageMaker can't find a FeatureGroup with name " + nameOrArn;
    }

    /** Feature Store runtime reports request validation failures as {@code ValidationError}. */
    static AwsException runtimeValidation(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    private static String key(String region, String name) {
        return region + "::" + lower(name);
    }

    private static String recordPrefix(FeatureGroupResource fg) {
        return key(fg.region, fg.featureGroupName) + "::";
    }

    private static String recordKey(FeatureGroupResource fg, String recordId) {
        return recordPrefix(fg) + recordId;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
