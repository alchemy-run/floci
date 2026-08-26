package io.github.hectorvent.floci.services.ssmincidents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssmincidents.model.IncidentRecord;
import io.github.hectorvent.floci.services.ssmincidents.model.RegionInfo;
import io.github.hectorvent.floci.services.ssmincidents.model.RelatedItem;
import io.github.hectorvent.floci.services.ssmincidents.model.ReplicationSet;
import io.github.hectorvent.floci.services.ssmincidents.model.ResponsePlan;
import io.github.hectorvent.floci.services.ssmincidents.model.TimelineEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Systems Manager Incident Manager (ssm-incidents) restJson1.
 *
 * <p>ARNs omit region: {@code arn:aws:ssm-incidents::<account>:incident-record/...}.
 */
@ApplicationScoped
public class SsmIncidentsService implements TagHandler {

    static final String SERVICE = "ssm-incidents";
    private static final String SOURCE_MANUAL = "aws.ssm-incidents.manual";
    private static final Pattern PLAN_NAME = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final int MIN_IMPACT = 1;
    private static final int MAX_IMPACT = 5;

    private final StorageBackend<String, ReplicationSet> sets;
    private final StorageBackend<String, ResponsePlan> plans;
    private final StorageBackend<String, IncidentRecord> records;
    private final StorageBackend<String, TimelineEvent> events;
    private final RegionResolver regionResolver;

    @Inject
    public SsmIncidentsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("ssm-incidents", "ssm-incidents-replication-sets.json",
                        new TypeReference<Map<String, ReplicationSet>>() {
                        }),
                storageFactory.create("ssm-incidents", "ssm-incidents-response-plans.json",
                        new TypeReference<Map<String, ResponsePlan>>() {
                        }),
                storageFactory.create("ssm-incidents", "ssm-incidents-incident-records.json",
                        new TypeReference<Map<String, IncidentRecord>>() {
                        }),
                storageFactory.create("ssm-incidents", "ssm-incidents-timeline-events.json",
                        new TypeReference<Map<String, TimelineEvent>>() {
                        }),
                regionResolver);
    }

    SsmIncidentsService(
            StorageBackend<String, ReplicationSet> sets,
            StorageBackend<String, ResponsePlan> plans,
            StorageBackend<String, IncidentRecord> records,
            StorageBackend<String, TimelineEvent> events,
            RegionResolver regionResolver) {
        this.sets = sets;
        this.plans = plans;
        this.records = records;
        this.events = events;
        this.regionResolver = regionResolver;
    }

    public List<String> listReplicationSets() {
        return sets.get(setKey()).map(set -> List.of(set.getArn())).orElse(List.of());
    }

    public ReplicationSet getReplicationSet(String arn) {
        return requireSet(arn);
    }

    public synchronized ReplicationSet createReplicationSet(JsonNode request) {
        requireObject(request, "Request body");
        if (sets.get(setKey()).isPresent()) {
            throw new AwsException("ConflictException", "A replication set already exists in this account.", 409);
        }
        JsonNode regions = request.get("regions");
        if (regions == null || !regions.isObject() || regions.isEmpty()) {
            throw validation("regions must be a non-empty object of Region names.");
        }
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        ReplicationSet set = new ReplicationSet();
        set.setArn("arn:aws:ssm-incidents::" + account + ":replication-set/" + UUID.randomUUID());
        set.setRegionMap(readRegions(regions, now));
        set.setStatus("ACTIVE");
        set.setDeletionProtected(false);
        set.setCreatedTime(now);
        set.setCreatedBy(callerPrincipal());
        set.setLastModifiedTime(now);
        set.setLastModifiedBy(callerPrincipal());
        set.setTags(readTags(request.get("tags")));
        sets.put(setKey(), set);
        return set;
    }

    public synchronized void updateReplicationSet(JsonNode request) {
        requireObject(request, "Request body");
        ReplicationSet set = requireSet(requireText(request, "arn"));
        JsonNode actions = request.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            throw validation("actions must be a non-empty array.");
        }
        long now = Instant.now().getEpochSecond();
        Map<String, RegionInfo> regionMap = new LinkedHashMap<>(set.getRegionMap());
        for (JsonNode action : actions) {
            if (action == null || !action.isObject()) {
                throw validation("Each action must be an object.");
            }
            if (action.has("addRegionAction") && !action.get("addRegionAction").isNull()) {
                JsonNode add = action.get("addRegionAction");
                String regionName = requireText(add, "regionName");
                if (regionMap.containsKey(regionName)) {
                    throw new AwsException(
                            "ConflictException",
                            "Region " + regionName + " is already in the replication set.",
                            409);
                }
                regionMap.put(regionName, regionInfo(optionalText(add, "sseKmsKeyId"), now));
            } else if (action.has("deleteRegionAction") && !action.get("deleteRegionAction").isNull()) {
                String regionName = requireText(action.get("deleteRegionAction"), "regionName");
                if (!regionMap.containsKey(regionName)) {
                    throw validation("Region " + regionName + " is not in the replication set.");
                }
                if (regionMap.size() <= 1) {
                    throw validation("Cannot remove the last Region from a replication set.");
                }
                regionMap.remove(regionName);
            } else {
                throw validation("Each action must contain addRegionAction or deleteRegionAction.");
            }
        }
        set.setRegionMap(regionMap);
        set.setLastModifiedTime(now);
        set.setLastModifiedBy(callerPrincipal());
        sets.put(setKey(), set);
    }

    public synchronized void updateDeletionProtection(JsonNode request) {
        requireObject(request, "Request body");
        ReplicationSet set = requireSet(requireText(request, "arn"));
        JsonNode value = request.get("deletionProtected");
        if (value == null || !value.isBoolean()) {
            throw validation("deletionProtected must be a boolean.");
        }
        set.setDeletionProtected(value.booleanValue());
        set.setLastModifiedTime(Instant.now().getEpochSecond());
        set.setLastModifiedBy(callerPrincipal());
        sets.put(setKey(), set);
    }

    public synchronized void deleteReplicationSet(String arn) {
        ReplicationSet set = requireSet(arn);
        if (set.isDeletionProtected()) {
            throw validation("Replication set is deletion protected.");
        }
        sets.delete(setKey());
    }

    public List<ResponsePlan> listResponsePlans() {
        List<ResponsePlan> listed = new ArrayList<>(plans.values());
        listed.sort(Comparator.comparing(ResponsePlan::getName, Comparator.nullsLast(String::compareTo)));
        return listed;
    }

    public ResponsePlan getResponsePlan(String arn) {
        String decoded = requireArn(arn, "arn");
        return plans.get(decoded).orElseThrow(() -> resourceNotFound(decoded, "RESPONSE_PLAN"));
    }

    public synchronized void updateResponsePlan(JsonNode request) {
        requireObject(request, "Request body");
        ResponsePlan plan = getResponsePlan(requireText(request, "arn"));
        if (request.has("displayName")) {
            plan.setDisplayName(optionalText(request, "displayName"));
        }
        if (request.has("incidentTemplateTitle")) {
            plan.setTitle(requireText(request, "incidentTemplateTitle"));
        }
        if (request.has("incidentTemplateImpact")) {
            plan.setImpact(requireImpact(request, "incidentTemplateImpact"));
        }
        if (request.has("incidentTemplateSummary")) {
            plan.setSummary(optionalText(request, "incidentTemplateSummary"));
        }
        if (request.has("incidentTemplateDedupeString")) {
            plan.setDedupeString(optionalText(request, "incidentTemplateDedupeString"));
        }
        plans.put(plan.getArn(), plan);
    }

    public synchronized void deleteResponsePlan(JsonNode request) {
        requireObject(request, "Request body");
        ResponsePlan plan = getResponsePlan(requireText(request, "arn"));
        plans.delete(plan.getArn());
    }

    public synchronized ResponsePlan createResponsePlan(JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (!PLAN_NAME.matcher(name).matches()) {
            throw validation("name must match [A-Za-z0-9_-]{1,128}.");
        }
        JsonNode template = request.get("incidentTemplate");
        requireObject(template, "incidentTemplate");
        String title = requireText(template, "title");
        int impact = requireImpact(template, "impact");
        String arn = planArn(name);
        if (plans.get(arn).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "A response plan with name " + name + " already exists.",
                    409,
                    Map.of("resourceIdentifier", arn, "resourceType", "RESPONSE_PLAN"));
        }
        ResponsePlan plan = new ResponsePlan();
        plan.setArn(arn);
        plan.setName(name);
        plan.setDisplayName(optionalText(request, "displayName"));
        plan.setTitle(title);
        plan.setImpact(impact);
        plan.setSummary(optionalText(template, "summary"));
        plan.setDedupeString(optionalText(template, "dedupeString"));
        plan.setTags(readTags(request.get("tags")));
        plans.put(arn, plan);
        return plan;
    }

    public synchronized IncidentRecord startIncident(JsonNode request) {
        requireObject(request, "Request body");
        String planArn = requireText(request, "responsePlanArn");
        ResponsePlan plan = plans.get(planArn).orElseThrow(() -> resourceNotFound(planArn, "RESPONSE_PLAN"));
        long now = Instant.now().getEpochSecond();
        String principal = callerPrincipal();
        String recordArn = recordArn(plan.getName());
        IncidentRecord record = new IncidentRecord();
        record.setArn(recordArn);
        record.setTitle(optionalText(request, "title") != null ? optionalText(request, "title") : plan.getTitle());
        record.setSummary(optionalText(request, "summary") != null
                ? optionalText(request, "summary")
                : plan.getSummary());
        record.setStatus("OPEN");
        record.setImpact(request.has("impact") ? requireImpact(request, "impact") : plan.getImpact());
        record.setCreationTime(now);
        record.setLastModifiedTime(now);
        record.setLastModifiedBy(principal);
        record.setCreatedBy(principal);
        record.setSource(SOURCE_MANUAL);
        record.setDedupeString(plan.getDedupeString() == null ? "" : plan.getDedupeString());
        records.put(recordArn, record);
        return record;
    }

    public List<IncidentRecord> listIncidentRecords() {
        String account = regionResolver.getAccountId();
        String marker = ":" + account + ":";
        List<IncidentRecord> listed = new ArrayList<>();
        for (IncidentRecord record : records.values()) {
            if (record.getArn() != null && record.getArn().contains(marker)) {
                listed.add(record);
            }
        }
        listed.sort(Comparator.comparing(IncidentRecord::getCreationTime).reversed());
        return listed;
    }

    public IncidentRecord getIncidentRecord(String arn) {
        String decoded = requireArn(arn, "arn");
        return records.get(decoded).orElseThrow(() -> resourceNotFound(decoded, "INCIDENT_RECORD"));
    }

    public synchronized IncidentRecord updateIncidentRecord(JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        IncidentRecord record = records.get(arn).orElseThrow(() -> resourceNotFound(arn, "INCIDENT_RECORD"));
        if (request.has("title")) {
            record.setTitle(requireText(request, "title"));
        }
        if (request.has("summary")) {
            record.setSummary(requireText(request, "summary"));
        }
        if (request.has("impact")) {
            record.setImpact(requireImpact(request, "impact"));
        }
        if (request.has("status")) {
            String status = requireText(request, "status");
            if (!"OPEN".equals(status) && !"RESOLVED".equals(status)) {
                throw validation("status must be OPEN or RESOLVED.");
            }
            record.setStatus(status);
            if ("RESOLVED".equals(status) && record.getResolvedTime() == null) {
                record.setResolvedTime(Instant.now().getEpochSecond());
            }
        }
        record.setLastModifiedTime(Instant.now().getEpochSecond());
        record.setLastModifiedBy(callerPrincipal());
        records.put(arn, record);
        return record;
    }

    public synchronized void deleteIncidentRecord(JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "arn");
        records.delete(arn);
        for (TimelineEvent event : events.scan(key -> key.startsWith(arn + "/"))) {
            events.delete(eventKey(event.getIncidentRecordArn(), event.getEventId()));
        }
    }

    public synchronized TimelineEvent createTimelineEvent(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        IncidentRecord record = records.get(recordArn)
                .orElseThrow(() -> resourceNotFound(recordArn, "INCIDENT_RECORD"));
        String eventType = requireText(request, "eventType");
        String eventData = requireText(request, "eventData");
        long eventTime = requireEpoch(request, "eventTime");
        long now = Instant.now().getEpochSecond();
        TimelineEvent event = new TimelineEvent();
        event.setIncidentRecordArn(record.getArn());
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(eventTime);
        event.setEventUpdatedTime(now);
        event.setEventType(eventType);
        event.setEventData(eventData);
        events.put(eventKey(record.getArn(), event.getEventId()), event);
        return event;
    }

    public TimelineEvent getTimelineEvent(String incidentRecordArn, String eventId) {
        String recordArn = requireArn(incidentRecordArn, "incidentRecordArn");
        String id = requireArn(eventId, "eventId");
        records.get(recordArn).orElseThrow(() -> resourceNotFound(recordArn, "INCIDENT_RECORD"));
        return events.get(eventKey(recordArn, id))
                .orElseThrow(() -> resourceNotFound(recordArn, "TIMELINE_EVENT"));
    }

    public synchronized TimelineEvent updateTimelineEvent(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        String eventId = requireText(request, "eventId");
        records.get(recordArn).orElseThrow(() -> resourceNotFound(recordArn, "INCIDENT_RECORD"));
        TimelineEvent event = events.get(eventKey(recordArn, eventId))
                .orElseThrow(() -> resourceNotFound(recordArn, "TIMELINE_EVENT"));
        if (request.has("eventData")) {
            event.setEventData(requireText(request, "eventData"));
        }
        if (request.has("eventType")) {
            event.setEventType(requireText(request, "eventType"));
        }
        if (request.has("eventTime")) {
            event.setEventTime(requireEpoch(request, "eventTime"));
        }
        event.setEventUpdatedTime(Instant.now().getEpochSecond());
        events.put(eventKey(recordArn, eventId), event);
        return event;
    }

    public synchronized void deleteTimelineEvent(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        String eventId = requireText(request, "eventId");
        events.delete(eventKey(recordArn, eventId));
    }

    public List<TimelineEvent> listTimelineEvents(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        if (records.get(recordArn).isEmpty()) {
            return List.of();
        }
        List<TimelineEvent> listed = events.scan(key -> key.startsWith(recordArn + "/"));
        listed.sort(Comparator.comparingLong(TimelineEvent::getEventTime));
        return listed;
    }

    public List<RelatedItem> listRelatedItems(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        return records.get(recordArn)
                .map(IncidentRecord::getRelatedItems)
                .orElse(List.of());
    }

    public synchronized IncidentRecord updateRelatedItems(JsonNode request) {
        requireObject(request, "Request body");
        String recordArn = requireText(request, "incidentRecordArn");
        IncidentRecord record = records.get(recordArn)
                .orElseThrow(() -> resourceNotFound(recordArn, "INCIDENT_RECORD"));
        JsonNode update = request.get("relatedItemsUpdate");
        requireObject(update, "relatedItemsUpdate");
        List<RelatedItem> items = new ArrayList<>(record.getRelatedItems());
        if (update.has("itemToAdd") && !update.get("itemToAdd").isNull()) {
            items.add(readRelatedItem(update.get("itemToAdd")));
        } else if (update.has("itemToRemove") && !update.get("itemToRemove").isNull()) {
            JsonNode identifier = update.get("itemToRemove");
            items.removeIf(item -> sameIdentifier(item, identifier));
        } else {
            throw validation("relatedItemsUpdate must contain itemToAdd or itemToRemove.");
        }
        record.setRelatedItems(items);
        record.setLastModifiedTime(Instant.now().getEpochSecond());
        record.setLastModifiedBy(callerPrincipal());
        records.put(recordArn, record);
        return record;
    }

    public IncidentRecord requireIncidentRecord(String arn) {
        String decoded = requireArn(arn, "incidentRecordArn");
        return records.get(decoded).orElseThrow(() -> resourceNotFound(decoded, "INCIDENT_RECORD"));
    }

    public IncidentRecord requireIncidentRecord(JsonNode request, String field) {
        requireObject(request, "Request body");
        String arn = requireText(request, field);
        return records.get(arn).orElseThrow(() -> resourceNotFound(arn, "INCIDENT_RECORD"));
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.applyTags(current);
    }

    private Tagged requireTagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw resourceNotFound(arn, "RESOURCE");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
            throw resourceNotFound(arn, "RESOURCE");
        }
        if (parsed.resource().startsWith("replication-set/")) {
            ReplicationSet set = requireSet(arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return set.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    set.setTags(tags);
                    sets.put(setKey(), set);
                }
            };
        }
        if (parsed.resource().startsWith("response-plan/")) {
            ResponsePlan plan = getResponsePlan(arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return plan.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    plan.setTags(tags);
                    plans.put(plan.getArn(), plan);
                }
            };
        }
        throw resourceNotFound(arn, "RESOURCE");
    }

    private ReplicationSet requireSet(String arn) {
        String decoded = requireArn(arn, "arn");
        ReplicationSet set = sets.get(setKey()).orElseThrow(() -> resourceNotFound(decoded, "REPLICATION_SET"));
        if (!decoded.equals(set.getArn())) {
            throw resourceNotFound(decoded, "REPLICATION_SET");
        }
        return set;
    }

    private static Map<String, RegionInfo> readRegions(JsonNode regions, long now) {
        Map<String, RegionInfo> map = new LinkedHashMap<>();
        regions.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw validation("Region names must be non-empty strings.");
            }
            JsonNode config = entry.getValue();
            String kms = null;
            if (config != null && config.isObject()) {
                kms = optionalText(config, "sseKmsKeyId");
            }
            map.put(name, regionInfo(kms, now));
        });
        return map;
    }

    private static RegionInfo regionInfo(String sseKmsKeyId, long now) {
        RegionInfo info = new RegionInfo();
        info.setSseKmsKeyId(sseKmsKeyId);
        info.setStatus("ACTIVE");
        info.setStatusUpdateDateTime(now);
        return info;
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }

    private RelatedItem readRelatedItem(JsonNode node) {
        requireObject(node, "itemToAdd");
        JsonNode identifier = node.get("identifier");
        requireObject(identifier, "identifier");
        RelatedItem item = new RelatedItem();
        item.setTitle(optionalText(node, "title"));
        item.setGeneratedId(UUID.randomUUID().toString());
        item.setType(requireText(identifier, "type"));
        JsonNode value = identifier.get("value");
        requireObject(value, "value");
        item.setValue(value);
        return item;
    }

    private static boolean sameIdentifier(RelatedItem item, JsonNode identifier) {
        if (identifier == null || !identifier.isObject()) {
            return false;
        }
        JsonNode type = identifier.get("type");
        JsonNode value = identifier.get("value");
        if (type == null || !type.isTextual() || value == null || !value.isObject()) {
            return false;
        }
        return type.textValue().equals(item.getType()) && value.equals(item.getValue());
    }

    private String setKey() {
        return regionResolver.getAccountId();
    }

    private String planArn(String name) {
        return "arn:aws:ssm-incidents::" + regionResolver.getAccountId() + ":response-plan/" + name;
    }

    private String recordArn(String planName) {
        return "arn:aws:ssm-incidents::" + regionResolver.getAccountId()
                + ":incident-record/" + planName + "/" + UUID.randomUUID();
    }

    private String callerPrincipal() {
        return "arn:aws:iam::" + regionResolver.getAccountId() + ":root";
    }

    private static String eventKey(String recordArn, String eventId) {
        return recordArn + "/" + eventId;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            if (entry.getValue() == null || !entry.getValue().isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
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
        return value.textValue();
    }

    private static int requireImpact(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) {
            throw validation(field + " must be an integer between 1 and 5.");
        }
        int impact = value.intValue();
        if (impact < MIN_IMPACT || impact > MAX_IMPACT) {
            throw validation(field + " must be an integer between 1 and 5.");
        }
        return impact;
    }

    private static long requireEpoch(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw validation(field + " must be a timestamp.");
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.textValue()).getEpochSecond();
            } catch (Exception e) {
                throw validation(field + " must be a timestamp.");
            }
        }
        throw validation(field + " must be a timestamp.");
    }

    private static String requireArn(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value;
    }

    static AwsException resourceNotFound(String identifier, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + identifier + " does not exist.",
                404,
                Map.of("resourceIdentifier", identifier, "resourceType", resourceType));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
