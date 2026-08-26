package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedFieldSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.InsightSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudTrailService {

    private static final Logger LOG = Logger.getLogger(CloudTrailService.class);

    private static final String EVENT_VERSION = "1.11";
    private static final String S3_EVENT_SOURCE = "s3.amazonaws.com";
    private static final int EVENT_HISTORY_CAP = 2000;
    private static final String BILLING_EXTENDABLE = "EXTENDABLE_RETENTION_PRICING";
    private static final String BILLING_FIXED = "FIXED_RETENTION_PRICING";
    private static final int RETENTION_MIN_DAYS = 7;
    private static final int RETENTION_MAX_EXTENDABLE = 3653;
    private static final int RETENTION_MAX_FIXED = 2557;
    private static final int RETENTION_DEFAULT_EXTENDABLE = 366;
    private static final int RETENTION_DEFAULT_FIXED = 2557;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern FROM_UUID = Pattern.compile(
            "(?i)\\bFROM\\s+[`'\"]?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final List<AdvancedEventSelector> DEFAULT_MANAGEMENT_SELECTORS = List.of(
            new AdvancedEventSelector("Default", List.of(
                    new AdvancedFieldSelector("eventCategory", List.of("Management"),
                            null, null, null, null, null))));

    private final StorageBackend<String, CloudTrailEntry> store;
    private final StorageBackend<String, EventDataStoreEntry> eventDataStores;
    private final ConcurrentHashMap<String, LakeQuery> queries = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final IamService iamService;
    private final ObjectMapper mapper;
    private final Instance<EventBridgeService> eventBridgeService;

    /** Per-trail pending record buffers — ephemeral, never persisted. */
    private final ConcurrentHashMap<TrailKey, ConcurrentLinkedQueue<ObjectNode>> pendingRecordsByTrail =
            new ConcurrentHashMap<>();

    /** 90-day Event History stand-in — newest events are at the tail. */
    private final ConcurrentLinkedQueue<ObjectNode> eventHistory = new ConcurrentLinkedQueue<>();

    @Inject
    public CloudTrailService(StorageFactory storageFactory, RegionResolver regionResolver,
                             IamService iamService, ObjectMapper mapper,
                             Instance<EventBridgeService> eventBridgeService) {
        this.store = storageFactory.create("cloudtrail", "cloudtrail-trails.json",
                new TypeReference<Map<String, CloudTrailEntry>>() {});
        this.eventDataStores = storageFactory.create("cloudtrail", "cloudtrail-event-data-stores.json",
                new TypeReference<Map<String, EventDataStoreEntry>>() {});
        this.regionResolver = regionResolver;
        this.iamService = iamService;
        this.mapper = mapper;
        this.eventBridgeService = eventBridgeService;
    }

    // --- Control plane ---

    public Trail createTrail(String region, String name, String s3BucketName, String s3KeyPrefix,
                             String snsTopicArn, boolean includeGlobalServiceEvents,
                             boolean isMultiRegionTrail, boolean enableLogFileValidation,
                             boolean isOrganizationTrail) {
        validateTrailName(name);
        if (s3BucketName == null || s3BucketName.isEmpty()) {
            throw new AwsException("S3BucketDoesNotExistException", "S3 bucket name is required.", 400);
        }
        String key = regionKey(region, name);
        if (store.get(key).isPresent()) {
            throw new AwsException("TrailAlreadyExistsException",
                    "Trail " + name + " already exists.", 400);
        }
        String arn = AwsArnUtils.Arn.of("cloudtrail", region, regionResolver.getAccountId(),
                "trail/" + name).toString();
        Trail trail = new Trail(
                name, arn, s3BucketName, s3KeyPrefix, snsTopicArn,
                includeGlobalServiceEvents, isMultiRegionTrail, region,
                enableLogFileValidation, false, false, isOrganizationTrail);
        store.put(key, new CloudTrailEntry(trail, List.of(), List.of(), List.of(), false, null, null, Map.of()));
        return trail;
    }

    public void deleteTrail(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        store.delete(regionKey(trail.homeRegion(), trail.name()));
        pendingRecordsByTrail.keySet().removeIf(k -> k.trailName().equals(trail.name()));
    }

    public Trail updateTrail(String region, String trailNameOrArn,
                             String s3BucketName, String s3KeyPrefix, String snsTopicArn,
                             Boolean includeGlobalServiceEvents, Boolean isMultiRegionTrail,
                             Boolean enableLogFileValidation, Boolean isOrganizationTrail) {
        Trail existing = findTrailOrThrow(region, trailNameOrArn);
        Trail updated = new Trail(
                existing.name(),
                existing.trailArn(),
                s3BucketName != null ? s3BucketName : existing.s3BucketName(),
                s3KeyPrefix != null ? s3KeyPrefix : existing.s3KeyPrefix(),
                snsTopicArn != null ? snsTopicArn : existing.snsTopicArn(),
                includeGlobalServiceEvents != null ? includeGlobalServiceEvents : existing.includeGlobalServiceEvents(),
                isMultiRegionTrail != null ? isMultiRegionTrail : existing.isMultiRegionTrail(),
                existing.homeRegion(),
                enableLogFileValidation != null ? enableLogFileValidation : existing.logFileValidationEnabled(),
                existing.hasCustomEventSelectors(),
                existing.hasInsightSelectors(),
                isOrganizationTrail != null ? isOrganizationTrail : existing.isOrganizationTrail());
        String key = regionKey(existing.homeRegion(), existing.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.withTrail(updated)));
        return updated;
    }

    public List<Trail> describeTrails(String region, List<String> trailNameOrArnList) {
        if (trailNameOrArnList == null || trailNameOrArnList.isEmpty()) {
            List<Trail> results = new ArrayList<>();
            for (String k : store.keys()) {
                String trailRegion = regionFromKey(k);
                CloudTrailEntry entry = store.get(k).orElse(null);
                if (entry == null) continue;
                Trail t = entry.trail();
                if (trailRegion.equals(region) || t.isMultiRegionTrail()) {
                    results.add(t);
                }
            }
            return results;
        }
        List<Trail> results = new ArrayList<>();
        for (String nameOrArn : trailNameOrArnList) {
            Trail t = findTrail(region, nameOrArn);
            if (t != null) results.add(t);
        }
        return results;
    }

    public List<EventSelector> putEventSelectors(String region, String trailNameOrArn, List<EventSelector> selectors) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        List<EventSelector> normalized = selectors == null ? List.of() : List.copyOf(selectors);
        String key = regionKey(trail.homeRegion(), trail.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.withSelectors(normalized, true)));
        return normalized;
    }

    public List<EventSelector> getEventSelectors(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        return store.get(regionKey(trail.homeRegion(), trail.name()))
                .map(e -> e.selectors() != null ? e.selectors() : List.<EventSelector>of())
                .orElse(List.of());
    }

    public List<AdvancedEventSelector> getAdvancedEventSelectors(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        return store.get(regionKey(trail.homeRegion(), trail.name()))
                .map(e -> e.advancedSelectors() != null ? e.advancedSelectors() : List.<AdvancedEventSelector>of())
                .orElse(List.of());
    }

    public List<AdvancedEventSelector> putAdvancedEventSelectors(String region, String trailNameOrArn,
                                                                 List<AdvancedEventSelector> selectors) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        List<AdvancedEventSelector> normalized = selectors == null ? List.of() : List.copyOf(selectors);
        String key = regionKey(trail.homeRegion(), trail.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.withAdvancedSelectors(normalized)));
        return normalized;
    }

    public List<InsightSelector> putInsightSelectors(String region, String trailNameOrArn,
                                                     List<InsightSelector> selectors) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        List<InsightSelector> normalized = selectors == null ? List.of() : List.copyOf(selectors);
        String key = regionKey(trail.homeRegion(), trail.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.withInsightSelectors(normalized)));
        return normalized;
    }

    public List<InsightSelector> getInsightSelectors(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        List<InsightSelector> selectors = store.get(regionKey(trail.homeRegion(), trail.name()))
                .map(e -> e.insightSelectors() != null ? e.insightSelectors() : List.<InsightSelector>of())
                .orElse(List.of());
        if (selectors.isEmpty()) {
            throw new AwsException("InsightNotEnabledException",
                    "Insight selectors are not enabled for trail " + trail.name() + ".", 400);
        }
        return selectors;
    }

    public void startLogging(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        String key = regionKey(trail.homeRegion(), trail.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.startLogging(System.currentTimeMillis())));
    }

    public void stopLogging(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        String key = regionKey(trail.homeRegion(), trail.name());
        store.get(key).ifPresent(entry -> store.put(key, entry.stopLogging(System.currentTimeMillis())));
    }

    public TrailStatus getTrailStatus(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        return store.get(regionKey(trail.homeRegion(), trail.name()))
                .map(e -> new TrailStatus(e.logging(), e.startLoggingTime(), e.stopLoggingTime()))
                .orElse(new TrailStatus(false, null, null));
    }

    // --- Data plane: called by S3 (and other services) when an op happens ---

    public void emitS3DataEvent(S3EventInput in) {
        try {
            String region = in.region() != null ? in.region() : regionResolver.getDefaultRegion();
            List<MatchedTrail> matched = trailsMatching(region, in);
            if (matched.isEmpty()) {
                return;
            }
            ObjectNode record = buildS3Record(in);
            for (MatchedTrail mt : matched) {
                ObjectNode copy = record.deepCopy();
                copy.put("recipientAccountId", regionResolver.getAccountId());
                queueFor(new TrailKey(mt.region(), mt.trail().name(), region)).add(copy);
                LOG.tracev("Emitted CloudTrail event {0} for trail {1}", in.eventName(), mt.trail().name());
            }
        } catch (Exception e) {
            // Never let emission take down an S3 op.
            LOG.warnv(e, "Failed to emit CloudTrail event for {0} {1}/{2}",
                    in.eventName(), in.bucketName(), in.key());
        }
    }

    public void requeueRecords(TrailKey key, List<ObjectNode> records) {
        if (!records.isEmpty()) {
            queueFor(key).addAll(records);
        }
    }

    public List<ObjectNode> drainPendingRecords(TrailKey key) {
        ConcurrentLinkedQueue<ObjectNode> q = pendingRecordsByTrail.get(key);
        if (q == null) return List.of();
        List<ObjectNode> drained = new ArrayList<>();
        ObjectNode r;
        while ((r = q.poll()) != null) {
            drained.add(r);
        }
        return drained;
    }

    public List<TrailKey> trailsWithPendingRecords() {
        List<TrailKey> result = new ArrayList<>();
        for (Map.Entry<TrailKey, ConcurrentLinkedQueue<ObjectNode>> e : pendingRecordsByTrail.entrySet()) {
            if (!e.getValue().isEmpty()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    public Trail getTrail(String region, String trailName) {
        return store.get(regionKey(region, trailName))
                .map(CloudTrailEntry::trail)
                .orElse(null);
    }

    public Trail getTrailOrThrow(String region, String trailNameOrArn) {
        return findTrailOrThrow(region, trailNameOrArn);
    }

    /**
     * Records a management API call into Event History ({@code LookupEvents}) and,
     * when at least one trail is logging in the region, publishes it to the
     * default EventBridge bus as {@code AWS API Call via CloudTrail}.
     */
    public void emitManagementEvent(ManagementEvent in) {
        try {
            if (in == null || in.eventName() == null || in.eventName().isEmpty()) {
                return;
            }
            ObjectNode record = buildManagementRecord(in);
            ObjectNode lookup = mapper.createObjectNode();
            lookup.put("EventId", record.path("eventID").asText());
            lookup.put("EventName", in.eventName());
            lookup.put("ReadOnly", in.readOnly() ? "true" : "false");
            lookup.put("EventTime", (in.eventTimeMillis() == 0L
                    ? System.currentTimeMillis() : in.eventTimeMillis()) / 1000.0);
            lookup.put("EventSource", in.eventSource());
            if (in.accessKeyId() != null) {
                lookup.put("AccessKeyId", in.accessKeyId());
            }
            lookup.put("Username", record.path("userIdentity").path("userName").asText("root"));
            if (in.requestParameters() != null && in.requestParameters().containsKey("bucketName")) {
                ArrayNode resources = mapper.createArrayNode();
                ObjectNode res = mapper.createObjectNode();
                res.put("ResourceType", "AWS::S3::Bucket");
                res.put("ResourceName", in.requestParameters().get("bucketName"));
                resources.add(res);
                lookup.set("Resources", resources);
            }
            lookup.put("CloudTrailEvent", record.toString());
            eventHistory.add(lookup);
            while (eventHistory.size() > EVENT_HISTORY_CAP) {
                eventHistory.poll();
            }
            if (!in.readOnly()) {
                String region = in.region() != null ? in.region() : regionResolver.getDefaultRegion();
                if (hasLoggingTrail(region)) {
                    publishToEventBridge(region, record);
                }
            }
        } catch (Exception e) {
            LOG.warnv(e, "Failed to emit CloudTrail management event {0}", in.eventName());
        }
    }

    public ObjectNode lookupEvents(JsonNode req) {
        int max = req != null && req.has("MaxResults") ? req.path("MaxResults").asInt(50) : 50;
        if (max <= 0) {
            max = 50;
        }
        if (max > 50) {
            max = 50;
        }
        List<LookupAttr> attrs = new ArrayList<>();
        if (req != null && req.has("LookupAttributes") && req.path("LookupAttributes").isArray()) {
            for (JsonNode a : req.path("LookupAttributes")) {
                attrs.add(new LookupAttr(a.path("AttributeKey").asText(""), a.path("AttributeValue").asText("")));
            }
        }
        List<ObjectNode> newestFirst = new ArrayList<>(eventHistory);
        java.util.Collections.reverse(newestFirst);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode events = resp.putArray("Events");
        int count = 0;
        for (ObjectNode ev : newestFirst) {
            if (count >= max) {
                break;
            }
            if (matchesLookup(ev, attrs)) {
                events.add(ev);
                count++;
            }
        }
        return resp;
    }

    private boolean matchesLookup(ObjectNode ev, List<LookupAttr> attrs) {
        if (attrs.isEmpty()) {
            return true;
        }
        for (LookupAttr attr : attrs) {
            String actual = switch (attr.key()) {
                case "EventName" -> ev.path("EventName").asText("");
                case "EventSource" -> ev.path("EventSource").asText("");
                case "EventId" -> ev.path("EventId").asText("");
                case "ReadOnly" -> ev.path("ReadOnly").asText("");
                case "Username" -> ev.path("Username").asText("");
                case "AccessKeyId" -> ev.path("AccessKeyId").asText("");
                default -> null;
            };
            if (actual == null || !actual.equals(attr.value())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasLoggingTrail(String region) {
        for (String k : store.keys()) {
            CloudTrailEntry entry = store.get(k).orElse(null);
            if (entry == null || !entry.logging()) {
                continue;
            }
            Trail t = entry.trail();
            if (region.equals(t.homeRegion()) || t.isMultiRegionTrail()) {
                return true;
            }
        }
        return false;
    }

    private void publishToEventBridge(String region, ObjectNode record) {
        if (eventBridgeService == null || !eventBridgeService.isResolvable()) {
            LOG.debug("EventBridge is not resolvable; skipping CloudTrail API-call delivery");
            return;
        }
        String eventSource = record.path("eventSource").asText("s3.amazonaws.com");
        String source = eventSource.endsWith(".amazonaws.com")
                ? "aws." + eventSource.substring(0, eventSource.length() - ".amazonaws.com".length())
                : "aws.cloudtrail";
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Source", source);
        entry.put("DetailType", "AWS API Call via CloudTrail");
        entry.put("Detail", record.toString());
        eventBridgeService.get().putEvents(List.of(entry), region);
    }

    private ObjectNode buildManagementRecord(ManagementEvent in) {
        ObjectNode record = mapper.createObjectNode();
        record.put("eventVersion", EVENT_VERSION);
        record.set("userIdentity", buildUserIdentity(in.accessKeyId()));
        long millis = in.eventTimeMillis() == 0L ? System.currentTimeMillis() : in.eventTimeMillis();
        record.put("eventTime", DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(millis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        record.put("eventSource", in.eventSource());
        record.put("eventName", in.eventName());
        record.put("awsRegion", in.region() != null ? in.region() : regionResolver.getDefaultRegion());
        record.put("sourceIPAddress", in.sourceIp() == null ? "127.0.0.1" : in.sourceIp());
        record.put("userAgent", in.userAgent() == null ? "" : in.userAgent());
        ObjectNode reqParams = mapper.createObjectNode();
        if (in.requestParameters() != null) {
            in.requestParameters().forEach(reqParams::put);
        }
        record.set("requestParameters", reqParams);
        record.set("responseElements", mapper.nullNode());
        record.put("requestID", UUID.randomUUID().toString());
        record.put("eventID", UUID.randomUUID().toString());
        record.put("readOnly", in.readOnly());
        record.put("eventType", "AwsApiCall");
        record.put("managementEvent", true);
        record.put("recipientAccountId", regionResolver.getAccountId());
        record.put("eventCategory", "Management");
        return record;
    }

    public record ManagementEvent(
            String region,
            String eventSource,
            String eventName,
            boolean readOnly,
            String accessKeyId,
            String sourceIp,
            String userAgent,
            Map<String, String> requestParameters,
            long eventTimeMillis) {}

    private record LookupAttr(String key, String value) {}

    // --- Event data stores ---

    public EventDataStoreEntry createEventDataStore(String region, String name,
                                                    List<AdvancedEventSelector> selectors,
                                                    Boolean multiRegionEnabled,
                                                    Boolean organizationEnabled,
                                                    Integer retentionPeriod,
                                                    Boolean terminationProtectionEnabled,
                                                    String billingMode,
                                                    String kmsKeyId,
                                                    Boolean startIngestion,
                                                    Map<String, String> tags) {
        validateTrailName(name);
        if (findEdsByName(region, name) != null) {
            throw new AwsException("EventDataStoreAlreadyExistsException",
                    "An event data store with name " + name + " already exists.", 400);
        }
        String billing = billingMode == null || billingMode.isEmpty() ? BILLING_EXTENDABLE : billingMode;
        if (!BILLING_EXTENDABLE.equals(billing) && !BILLING_FIXED.equals(billing)) {
            throw new AwsException("InvalidParameterException",
                    "BillingMode must be EXTENDABLE_RETENTION_PRICING or FIXED_RETENTION_PRICING.", 400);
        }
        int retention = retentionPeriod != null ? retentionPeriod
                : (BILLING_FIXED.equals(billing) ? RETENTION_DEFAULT_FIXED : RETENTION_DEFAULT_EXTENDABLE);
        validateRetention(retention, billing);
        List<AdvancedEventSelector> resolvedSelectors =
                selectors == null || selectors.isEmpty() ? DEFAULT_MANAGEMENT_SELECTORS : List.copyOf(selectors);
        Map<String, String> resolvedTags = tags == null ? Map.of() : new LinkedHashMap<>(tags);
        if (resolvedTags.size() > 50) {
            throw new AwsException("TagsLimitExceededException",
                    "The number of tags per resource cannot exceed 50.", 400);
        }
        boolean ingest = startIngestion == null || startIngestion;
        String id = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("cloudtrail", region, regionResolver.getAccountId(),
                "eventdatastore/" + id).toString();
        long now = epochSeconds();
        EventDataStoreEntry entry = new EventDataStoreEntry(
                id, region, arn, name,
                ingest ? EventDataStoreEntry.STATUS_ENABLED : EventDataStoreEntry.STATUS_STOPPED_INGESTION,
                resolvedSelectors,
                multiRegionEnabled == null || multiRegionEnabled,
                organizationEnabled != null && organizationEnabled,
                retention,
                terminationProtectionEnabled == null || terminationProtectionEnabled,
                billing, kmsKeyId, now, now, resolvedTags);
        eventDataStores.put(entry.storageKey(), entry);
        return entry;
    }

    public EventDataStoreEntry getEventDataStore(String region, String idOrArn) {
        EventDataStoreEntry entry = findEds(region, idOrArn);
        if (entry == null) {
            throw edsNotFound(idOrArn);
        }
        return entry;
    }

    public List<EventDataStoreEntry> listEventDataStores(String region) {
        List<EventDataStoreEntry> result = new ArrayList<>();
        String prefix = region + ":";
        for (String k : eventDataStores.keys()) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            eventDataStores.get(k).ifPresent(result::add);
        }
        return result;
    }

    public EventDataStoreEntry updateEventDataStore(String region, String idOrArn, String name,
                                                    List<AdvancedEventSelector> selectors,
                                                    Boolean multiRegionEnabled,
                                                    Boolean organizationEnabled,
                                                    Integer retentionPeriod,
                                                    Boolean terminationProtectionEnabled,
                                                    String billingMode,
                                                    String kmsKeyId) {
        EventDataStoreEntry existing = requireActiveEds(region, idOrArn);
        if (name != null) {
            validateTrailName(name);
            EventDataStoreEntry holder = findEdsByName(region, name);
            if (holder != null && !holder.id().equals(existing.id())) {
                throw new AwsException("EventDataStoreAlreadyExistsException",
                        "An event data store with name " + name + " already exists.", 400);
            }
        }
        String billing = billingMode != null ? billingMode : existing.billingMode();
        if (retentionPeriod != null) {
            validateRetention(retentionPeriod, billing);
        }
        EventDataStoreEntry updated = existing.withUpdates(name, selectors, multiRegionEnabled,
                organizationEnabled, retentionPeriod, terminationProtectionEnabled, kmsKeyId, billingMode,
                epochSeconds());
        eventDataStores.put(updated.storageKey(), updated);
        return updated;
    }

    public void deleteEventDataStore(String region, String idOrArn) {
        EventDataStoreEntry existing = getEventDataStore(region, idOrArn);
        if (existing.pendingDeletion()) {
            throw new AwsException("InactiveEventDataStoreException",
                    "Event data store is pending deletion: " + existing.arn(), 400);
        }
        if (existing.terminationProtectionEnabled()) {
            throw new AwsException("EventDataStoreTerminationProtectedException",
                    "Event data store is termination protected: " + existing.arn(), 400);
        }
        eventDataStores.put(existing.storageKey(),
                existing.withStatus(EventDataStoreEntry.STATUS_PENDING_DELETION, epochSeconds()));
    }

    public EventDataStoreEntry restoreEventDataStore(String region, String idOrArn) {
        EventDataStoreEntry existing = getEventDataStore(region, idOrArn);
        if (!existing.pendingDeletion()) {
            throw new AwsException("InvalidEventDataStoreStatusException",
                    "Event data store is not pending deletion: " + existing.arn(), 400);
        }
        EventDataStoreEntry restored = existing.withStatus(EventDataStoreEntry.STATUS_ENABLED, epochSeconds());
        eventDataStores.put(restored.storageKey(), restored);
        return restored;
    }

    public void startEventDataStoreIngestion(String region, String idOrArn) {
        EventDataStoreEntry existing = requireActiveEds(region, idOrArn);
        if (EventDataStoreEntry.STATUS_ENABLED.equals(existing.status())) {
            throw new AwsException("InvalidEventDataStoreStatusException",
                    "Event data store is already ingesting: " + existing.arn(), 400);
        }
        eventDataStores.put(existing.storageKey(),
                existing.withStatus(EventDataStoreEntry.STATUS_ENABLED, epochSeconds()));
    }

    public void stopEventDataStoreIngestion(String region, String idOrArn) {
        EventDataStoreEntry existing = requireActiveEds(region, idOrArn);
        if (EventDataStoreEntry.STATUS_STOPPED_INGESTION.equals(existing.status())) {
            throw new AwsException("InvalidEventDataStoreStatusException",
                    "Event data store ingestion is already stopped: " + existing.arn(), 400);
        }
        eventDataStores.put(existing.storageKey(),
                existing.withStatus(EventDataStoreEntry.STATUS_STOPPED_INGESTION, epochSeconds()));
    }

    public LakeQuery startQuery(String region, String queryStatement) {
        if (queryStatement == null || queryStatement.isBlank()) {
            throw new AwsException("InvalidParameterException", "QueryStatement is required.", 400);
        }
        Matcher matcher = FROM_UUID.matcher(queryStatement);
        if (!matcher.find()) {
            throw new AwsException("InvalidQueryStatementException",
                    "QueryStatement must reference an event data store ID in the FROM clause.", 400);
        }
        EventDataStoreEntry store = requireActiveEds(region, matcher.group(1));
        String queryId = UUID.randomUUID().toString();
        LakeQuery query = new LakeQuery(queryId, store.id(), store.arn(), queryStatement,
                "FINISHED", System.currentTimeMillis(), null);
        queries.put(queryId, query);
        return query;
    }

    public LakeQuery describeQuery(String queryId) {
        return requireQuery(queryId);
    }

    public LakeQuery getQueryResults(String queryId) {
        return requireQuery(queryId);
    }

    public List<LakeQuery> listQueries(String region, String eventDataStore, String queryStatus) {
        EventDataStoreEntry store = getEventDataStore(region, eventDataStore);
        List<LakeQuery> result = new ArrayList<>();
        for (LakeQuery q : queries.values()) {
            if (!store.id().equals(q.storeId())) {
                continue;
            }
            if (queryStatus != null && !queryStatus.isEmpty() && !queryStatus.equals(q.status())) {
                continue;
            }
            result.add(q);
        }
        return result;
    }

    public LakeQuery cancelQuery(String queryId) {
        LakeQuery query = requireQuery(queryId);
        if ("FINISHED".equals(query.status()) || "FAILED".equals(query.status())
                || "CANCELLED".equals(query.status()) || "TIMED_OUT".equals(query.status())) {
            throw new AwsException("InactiveQueryException",
                    "The query is not in a cancellable state: " + query.status(), 400);
        }
        LakeQuery cancelled = query.withStatus("CANCELLED");
        queries.put(queryId, cancelled);
        return cancelled;
    }

    public String generateQuery(String region, List<String> eventDataStoreIds, String prompt) {
        if (eventDataStoreIds == null || eventDataStoreIds.isEmpty()) {
            throw new AwsException("InvalidParameterException", "EventDataStores is required.", 400);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new AwsException("InvalidParameterException", "Prompt is required.", 400);
        }
        EventDataStoreEntry store = requireActiveEds(region, eventDataStoreIds.get(0));
        return "SELECT eventID FROM " + store.id() + " LIMIT 1";
    }

    private LakeQuery requireQuery(String queryId) {
        if (queryId == null || queryId.isEmpty()) {
            throw new AwsException("InvalidParameterException", "QueryId is required.", 400);
        }
        LakeQuery query = queries.get(queryId);
        if (query == null) {
            throw new AwsException("QueryIdNotFoundException", "Query not found: " + queryId, 404);
        }
        return query;
    }

    public void addTags(String region, String resourceId, Map<String, String> tags) {
        if (looksLikeEdsResource(resourceId)) {
            EventDataStoreEntry entry = requireActiveEds(region, resourceId);
            Map<String, String> merged = new LinkedHashMap<>(entry.tags());
            if (tags != null) {
                merged.putAll(tags);
            }
            if (merged.size() > 50) {
                throw new AwsException("TagsLimitExceededException",
                        "The number of tags per resource cannot exceed 50.", 400);
            }
            eventDataStores.put(entry.storageKey(), entry.withTags(merged));
            return;
        }
        CloudTrailEntry entry = requireTaggedTrail(region, resourceId);
        Map<String, String> merged = new LinkedHashMap<>(entry.tags());
        if (tags != null) {
            merged.putAll(tags);
        }
        if (merged.size() > 50) {
            throw new AwsException("TagsLimitExceededException",
                    "The number of tags per trail cannot exceed 50.", 400);
        }
        String key = regionKey(entry.trail().homeRegion(), entry.trail().name());
        store.put(key, entry.withTags(merged));
    }

    public void removeTags(String region, String resourceId, List<String> keys) {
        if (looksLikeEdsResource(resourceId)) {
            EventDataStoreEntry entry = requireActiveEds(region, resourceId);
            Map<String, String> merged = new LinkedHashMap<>(entry.tags());
            if (keys != null) {
                for (String k : keys) {
                    merged.remove(k);
                }
            }
            eventDataStores.put(entry.storageKey(), entry.withTags(merged));
            return;
        }
        CloudTrailEntry entry = requireTaggedTrail(region, resourceId);
        Map<String, String> merged = new LinkedHashMap<>(entry.tags());
        if (keys != null) {
            for (String k : keys) {
                merged.remove(k);
            }
        }
        String key = regionKey(entry.trail().homeRegion(), entry.trail().name());
        store.put(key, entry.withTags(merged));
    }

    public List<ResourceTagSet> listTags(String region, List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            throw new AwsException("InvalidParameterException", "ResourceIdList is required.", 400);
        }
        List<ResourceTagSet> result = new ArrayList<>();
        for (String id : resourceIds) {
            if (looksLikeEdsResource(id)) {
                EventDataStoreEntry entry = getEventDataStore(region, id);
                result.add(new ResourceTagSet(entry.arn(), entry.tags()));
            } else {
                CloudTrailEntry entry = requireTaggedTrail(region, id);
                result.add(new ResourceTagSet(entry.trail().trailArn(), entry.tags()));
            }
        }
        return result;
    }

    private CloudTrailEntry requireTaggedTrail(String region, String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) {
            throw new AwsException("CloudTrailARNInvalidException", "ResourceId is required.", 400);
        }
        Trail trail = findTrail(region, resourceId);
        if (trail == null) {
            throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceId, 400);
        }
        return store.get(regionKey(trail.homeRegion(), trail.name()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceId, 400));
    }

    private EventDataStoreEntry requireActiveEds(String region, String idOrArn) {
        EventDataStoreEntry entry = getEventDataStore(region, idOrArn);
        if (entry.pendingDeletion()) {
            throw new AwsException("InactiveEventDataStoreException",
                    "Event data store is pending deletion: " + entry.arn(), 400);
        }
        return entry;
    }

    private EventDataStoreEntry findEds(String region, String idOrArn) {
        if (idOrArn == null || idOrArn.isEmpty()) {
            throw new AwsException("InvalidParameterException", "EventDataStore is required.", 400);
        }
        if (idOrArn.startsWith("arn:")) {
            if (!isValidEdsArn(idOrArn)) {
                throw new AwsException("EventDataStoreARNInvalidException",
                        "Invalid event data store ARN: " + idOrArn, 400);
            }
            for (String k : eventDataStores.keys()) {
                EventDataStoreEntry entry = eventDataStores.get(k).orElse(null);
                if (entry != null && idOrArn.equals(entry.arn())) {
                    return entry;
                }
            }
            return null;
        }
        EventDataStoreEntry byId = eventDataStores.get(regionKey(region, idOrArn)).orElse(null);
        if (byId != null) {
            return byId;
        }
        return findEdsByName(region, idOrArn);
    }

    private EventDataStoreEntry findEdsByName(String region, String name) {
        String prefix = region + ":";
        for (String k : eventDataStores.keys()) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            EventDataStoreEntry entry = eventDataStores.get(k).orElse(null);
            if (entry != null && name.equals(entry.name())) {
                return entry;
            }
        }
        return null;
    }

    private static boolean looksLikeEdsResource(String resourceId) {
        return resourceId != null && resourceId.contains(":eventdatastore/");
    }

    private static boolean isValidEdsArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return "cloudtrail".equals(parsed.service())
                    && parsed.resource() != null
                    && parsed.resource().startsWith("eventdatastore/")
                    && parsed.resource().length() > "eventdatastore/".length();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AwsException edsNotFound(String idOrArn) {
        return new AwsException("EventDataStoreNotFoundException",
                "Event data store not found: " + idOrArn, 400);
    }

    private static void validateRetention(int days, String billing) {
        int max = BILLING_FIXED.equals(billing) ? RETENTION_MAX_FIXED : RETENTION_MAX_EXTENDABLE;
        if (days < RETENTION_MIN_DAYS || days > max) {
            throw new AwsException("InvalidParameterException",
                    "RetentionPeriod must be between " + RETENTION_MIN_DAYS + " and " + max + " days.", 400);
        }
    }

    private static long epochSeconds() {
        return Instant.now().getEpochSecond();
    }

    public record ResourceTagSet(String resourceId, Map<String, String> tags) {}

    private ConcurrentLinkedQueue<ObjectNode> queueFor(TrailKey key) {
        return pendingRecordsByTrail.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
    }

    /**
     * Identifies a pending-records queue.
     * {@code region} is the trail's home region (used for trail store lookups).
     * {@code eventRegion} is the region where the event occurred (used for the S3 delivery path).
     * For single-region trails these are the same; for multi-region trails they differ.
     */
    public record TrailKey(String region, String trailName, String eventRegion) {}

    // --- Helpers ---

    private List<MatchedTrail> trailsMatching(String region, S3EventInput in) {
        List<MatchedTrail> result = new ArrayList<>();
        for (String k : store.keys()) {
            String trailRegion = regionFromKey(k);
            boolean sameRegion = trailRegion.equals(region);
            CloudTrailEntry entry = store.get(k).orElse(null);
            if (entry == null) continue;
            Trail trail = entry.trail();
            if (!sameRegion && !trail.isMultiRegionTrail()) continue;
            if (!entry.logging()) continue;
            List<EventSelector> selectors = entry.selectors() != null ? entry.selectors() : List.of();
            if (matchesAnySelector(selectors, in)) {
                result.add(new MatchedTrail(trail, trailRegion));
            }
        }
        return result;
    }

    private boolean matchesAnySelector(List<EventSelector> selectors, S3EventInput in) {
        if (selectors.isEmpty()) {
            return false;
        }
        boolean isRead = isReadOnlyEvent(in.eventName());
        for (EventSelector sel : selectors) {
            String rwt = sel.readWriteType() == null ? "All" : sel.readWriteType();
            if ("ReadOnly".equalsIgnoreCase(rwt) && !isRead) continue;
            if ("WriteOnly".equalsIgnoreCase(rwt) && isRead) continue;

            List<DataResource> dataResources = sel.dataResources();
            if (dataResources == null || dataResources.isEmpty()) {
                continue;
            }
            for (DataResource dr : dataResources) {
                if (!"AWS::S3::Object".equals(dr.type())) continue;
                if (dr.values() == null) continue;
                for (String v : dr.values()) {
                    if (matchesS3DataResourceArn(v, in.bucketName(), in.key())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Package-private for unit testing.
    static boolean matchesS3DataResourceArn(String configured, String bucketName, String key) {
        if (configured == null) return false;
        // "arn:aws:s3" (bare, no ":::") is shorthand for all buckets + all objects.
        if (configured.equals("arn:aws:s3")) return true;
        // Forms accepted:
        //   arn:aws:s3:::                    → all buckets, all keys
        //   arn:aws:s3:::*                   → all buckets (wildcard)
        //   arn:aws:s3:::bucket/             → all keys in bucket
        //   arn:aws:s3:::bucket/prefix       → keys with the given prefix in bucket
        //   arn:aws:s3:::*/*                 → all objects (wildcard bucket + any key)
        String prefix = "arn:aws:s3:::";
        if (!configured.startsWith(prefix)) return false;
        String tail = configured.substring(prefix.length());
        if (tail.isEmpty() || tail.equals("/")) {
            return true;
        }
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return tail.equals("*") || tail.equals(bucketName);
        }
        String configBucket = tail.substring(0, slash);
        if (!configBucket.equals("*") && !configBucket.equals(bucketName)) return false;
        String configKeyPart = tail.substring(slash + 1);
        if (configKeyPart.isEmpty()) {
            return true;
        }
        if (configKeyPart.equals("*") || configKeyPart.equals("*/*")) {
            return key != null;
        }
        if (key == null) return false;
        return key.startsWith(configKeyPart);
    }

    // Package-private for unit testing.
    static boolean isReadOnlyEvent(String eventName) {
        if (eventName == null) return true;
        return switch (eventName) {
            case "GetObject", "HeadObject", "ListObjects", "ListObjectsV2",
                 "GetObjectAcl", "GetObjectTagging", "ListMultipartUploads" -> true;
            default -> false;
        };
    }

    private ObjectNode buildS3Record(S3EventInput in) {
        ObjectNode record = mapper.createObjectNode();
        record.put("eventVersion", EVENT_VERSION);
        record.set("userIdentity", buildUserIdentity(in.accessKeyId()));
        record.put("eventTime", DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli((in.eventTimeMillis() == 0L
                        ? System.currentTimeMillis() : in.eventTimeMillis()))
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        record.put("eventSource", S3_EVENT_SOURCE);
        record.put("eventName", in.eventName());
        record.put("awsRegion", in.region());
        record.put("sourceIPAddress", in.sourceIp() == null ? "127.0.0.1" : in.sourceIp());
        record.put("userAgent", in.userAgent() == null ? "" : in.userAgent());

        if (in.errorCode() != null) {
            record.put("errorCode", in.errorCode());
            if (in.errorMessage() != null) {
                record.put("errorMessage", in.errorMessage());
            }
        }

        ObjectNode reqParams = mapper.createObjectNode();
        if (in.bucketName() != null) reqParams.put("bucketName", in.bucketName());
        reqParams.put("Host", in.bucketName() == null
                ? "s3.amazonaws.com"
                : in.bucketName() + ".s3.amazonaws.com");
        if (in.key() != null) reqParams.put("key", in.key());
        record.set("requestParameters", reqParams);
        record.set("responseElements", mapper.nullNode());

        ObjectNode addl = mapper.createObjectNode();
        addl.put("SignatureVersion", "SigV4");
        addl.put("CipherSuite", "TLS_AES_128_GCM_SHA256");
        addl.put("bytesTransferredIn", in.bytesIn());
        addl.put("AuthenticationMethod", "AuthHeader");
        addl.put("bytesTransferredOut", in.bytesOut());
        record.set("additionalEventData", addl);

        record.put("requestID", UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        record.put("eventID", UUID.randomUUID().toString());
        record.put("readOnly", isReadOnlyEvent(in.eventName()));

        if (in.bucketName() != null) {
            ArrayNode resources = mapper.createArrayNode();
            ObjectNode bucketRes = mapper.createObjectNode();
            bucketRes.put("accountId", regionResolver.getAccountId());
            bucketRes.put("type", "AWS::S3::Bucket");
            bucketRes.put("ARN", "arn:aws:s3:::" + in.bucketName());
            resources.add(bucketRes);
            if (in.key() != null) {
                ObjectNode objRes = mapper.createObjectNode();
                objRes.put("type", "AWS::S3::Object");
                objRes.put("ARN", "arn:aws:s3:::" + in.bucketName() + "/" + in.key());
                resources.add(objRes);
            }
            record.set("resources", resources);
        }

        record.put("eventType", "AwsApiCall");
        record.put("managementEvent", false);
        record.put("eventCategory", "Data");

        ObjectNode tls = mapper.createObjectNode();
        tls.put("tlsVersion", "TLSv1.3");
        tls.put("cipherSuite", "TLS_AES_128_GCM_SHA256");
        tls.put("clientProvidedHostHeader", in.bucketName() == null
                ? "s3.amazonaws.com"
                : in.bucketName() + ".s3.amazonaws.com");
        record.set("tlsDetails", tls);

        return record;
    }

    private ObjectNode buildUserIdentity(String accessKeyId) {
        ObjectNode identity = mapper.createObjectNode();
        String accountId = regionResolver.getAccountId();

        if (accessKeyId == null || "test".equals(accessKeyId)) {
            identity.put("type", "IAMUser");
            identity.put("principalId", "AIDA" + repeat('A', 17));
            identity.put("arn", "arn:aws:iam::" + accountId + ":root");
            identity.put("accountId", accountId);
            identity.put("accessKeyId", accessKeyId == null ? "" : accessKeyId);
            identity.put("userName", "root");
            return identity;
        }

        AccessKey key = iamService.findAccessKey(accessKeyId).orElse(null);
        if (key != null) {
            IamUser user = iamService.findUser(key.getUserName()).orElse(null);
            if (user != null) {
                identity.put("type", "IAMUser");
                identity.put("principalId", user.getUserId());
                identity.put("arn", user.getArn());
                identity.put("accountId", accountId);
                identity.put("accessKeyId", accessKeyId);
                identity.put("userName", user.getUserName());
                return identity;
            }
        }

        identity.put("type", "IAMUser");
        identity.put("principalId", "AIDA" + repeat('A', 17));
        identity.put("arn", "arn:aws:iam::" + accountId + ":user/anonymous");
        identity.put("accountId", accountId);
        identity.put("accessKeyId", accessKeyId);
        identity.put("userName", "anonymous");
        return identity;
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }

    private Trail findTrail(String region, String nameOrArn) {
        // ARN → cross-region scan is valid (callers use ARN to target another Region)
        if (nameOrArn != null && nameOrArn.startsWith("arn:")) {
            for (String k : store.keys()) {
                CloudTrailEntry entry = store.get(k).orElse(null);
                if (entry == null) continue;
                if (nameOrArn.equals(entry.trail().trailArn())) {
                    return entry.trail();
                }
            }
            return null;
        }
        // Name → region-scoped only (AWS resolves a name only in the current Region)
        return store.get(regionKey(region, nameOrArn))
                .map(CloudTrailEntry::trail)
                .orElse(null);
    }

    private Trail findTrailOrThrow(String region, String nameOrArn) {
        Trail t = findTrail(region, nameOrArn);
        if (t == null) {
            throw new AwsException("TrailNotFoundException",
                    "Unknown trail: " + nameOrArn, 400);
        }
        return t;
    }

    private static void validateTrailName(String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("InvalidTrailNameException", "Trail name is required.", 400);
        }
        if (name.length() < 3) {
            throw new AwsException("InvalidTrailNameException",
                    "Trail name too short. Minimum allowed length: 3 characters.", 400);
        }
        if (name.length() > 128) {
            throw new AwsException("InvalidTrailNameException",
                    "Trail name too long. Maximum allowed length: 128 characters.", 400);
        }
        if (!Character.isLetterOrDigit(name.charAt(0))) {
            throw new AwsException("InvalidTrailNameException",
                    "Trail name must starts with a letter or number.", 400);
        }
        if (!Character.isLetterOrDigit(name.charAt(name.length() - 1))) {
            throw new AwsException("InvalidTrailNameException",
                    "Trail name must end with a letter or number.", 400);
        }
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '-') {
                throw new AwsException("InvalidTrailNameException",
                        "Trail name must only contain letters, numbers, periods, underscores, and hyphens.", 400);
            }
        }
    }

    private static String regionKey(String region, String name) {
        return region + ":" + name;
    }

    private static String regionFromKey(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    public record TrailStatus(boolean logging, Long startLoggingTime, Long stopLoggingTime) {}

    private record MatchedTrail(Trail trail, String region) {}

    /** Input describing a single S3 op for emission. Use the builder for clarity. */
    public record S3EventInput(
            String region,
            String eventName,
            String bucketName,
            String key,
            String accessKeyId,
            String sourceIp,
            String userAgent,
            long bytesIn,
            long bytesOut,
            String errorCode,
            String errorMessage,
            long eventTimeMillis) {

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String region;
            private String eventName;
            private String bucketName;
            private String key;
            private String accessKeyId;
            private String sourceIp;
            private String userAgent;
            private long bytesIn;
            private long bytesOut;
            private String errorCode;
            private String errorMessage;
            private long eventTimeMillis;

            public Builder region(String v) { this.region = v; return this; }
            public Builder eventName(String v) { this.eventName = v; return this; }
            public Builder bucketName(String v) { this.bucketName = v; return this; }
            public Builder key(String v) { this.key = v; return this; }
            public Builder accessKeyId(String v) { this.accessKeyId = v; return this; }
            public Builder sourceIp(String v) { this.sourceIp = v; return this; }
            public Builder userAgent(String v) { this.userAgent = v; return this; }
            public Builder bytesIn(long v) { this.bytesIn = v; return this; }
            public Builder bytesOut(long v) { this.bytesOut = v; return this; }
            public Builder errorCode(String v) { this.errorCode = v; return this; }
            public Builder errorMessage(String v) { this.errorMessage = v; return this; }
            public Builder eventTimeMillis(long v) { this.eventTimeMillis = v; return this; }

            public S3EventInput build() {
                return new S3EventInput(region, eventName, bucketName, key, accessKeyId,
                        sourceIp, userAgent, bytesIn, bytesOut,
                        errorCode, errorMessage, eventTimeMillis);
            }
        }
    }
}
