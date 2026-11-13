package io.floci.aws.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The rest of MediaLive: reservations and offerings, the account configuration, engine versions and tags; the
 * workflow monitor's CloudWatch alarm and EventBridge rule templates, their groups, and signal maps; and MediaLive
 * Anywhere's clusters, nodes, networks, SDI sources and channel placement groups. Workflow monitor deployments and
 * signal map discovery finish after a transition; nodes are on-premises hardware that never connects to Floci.
 */
@ApplicationScoped
public class MediaLiveResources {

    static final String ENGINE_VERSION = "medialive_channel_engine_v2025_09";
    static final String ALARM_TEMPLATE = "alarmtemplate";
    static final String ALARM_GROUP = "alarmgroup";
    static final String RULE_TEMPLATE = "ruletemplate";
    static final String RULE_GROUP = "rulegroup";
    static final String SIGNAL_MAP = "signalmap";
    static final String NETWORK = "network";
    static final String CLUSTER = "cluster";
    static final String NODE = "node";
    static final String SDI_SOURCE = "sdisource";
    static final String PLACEMENT_GROUP = "placementgroup";
    static final List<String> TAGGABLE = List.of(MediaLiveStore.CHANNEL, MediaLiveStore.INPUT,
            MediaLiveStore.SECURITY_GROUP, MediaLiveStore.MULTIPLEX, MediaLiveStore.RESERVATION, ALARM_TEMPLATE,
            ALARM_GROUP, RULE_TEMPLATE, RULE_GROUP, SIGNAL_MAP, NETWORK, CLUSTER, NODE, SDI_SOURCE, PLACEMENT_GROUP);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final MediaLiveStore store;
    private final ObjectMapper mapper;

    @Inject
    public MediaLiveResources(MediaLiveStore store) {
        this.store = store;
        this.mapper = store.mapper();
    }

    private ObjectNode wrap(String member, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(member, value);
        return out;
    }

    private ObjectNode list(String region, String kind, JsonNode in, String member, Predicate<ObjectNode> keep) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind)) {
            settle(region, kind, record);
            store.get(region, kind, MediaLiveStore.meta(record).path("id").asText()).filter(keep).ifPresent(found ->
                    items.add(MediaLiveStore.view(found)));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(items, in, out, member, 100);
        return out;
    }

    /** A record of a kind by its id or (for workflow monitor resources) its name. */
    private ObjectNode find(String region, String kind, String identifier, String what) {
        ObjectNode record = store.get(region, kind, identifier).orElse(null);
        if (record == null) {
            for (ObjectNode candidate : store.list(region, kind)) {
                if (identifier.equals(candidate.path("Name").asText())) {
                    record = candidate;
                }
            }
        }
        if (record == null) {
            throw MediaLiveStore.notFound("The " + what + " " + identifier + " doesn't exist.");
        }
        settle(region, kind, record);
        String id = MediaLiveStore.meta(record).path("id").asText();
        return store.get(region, kind, id).orElseThrow(() -> MediaLiveStore.notFound("The " + what + " "
                + identifier + " doesn't exist."));
    }

    /** Applies a transition recorded on a record whose time has come: its member set, or the record removed. */
    private void settle(String region, String kind, ObjectNode record) {
        ObjectNode meta = MediaLiveStore.meta(record);
        long at = meta.path("transitionAt").asLong(0);
        if (at == 0 || at > System.currentTimeMillis()) {
            return;
        }
        String id = meta.path("id").asText();
        meta.remove("transitionAt");
        if (meta.path("gone").asBoolean(false)) {
            store.delete(region, kind, id);
            return;
        }
        meta.path("then").properties().forEach(entry -> {
            String[] path = entry.getKey().split("\\.");
            ObjectNode target = record;
            for (int i = 0; i < path.length - 1; i++) {
                target = target.withObject("/" + path[i]);
            }
            JsonNode value = entry.getValue();
            if ("true".equals(value.asText()) || "false".equals(value.asText())) {
                value = mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value.asText()));
            }
            target.set(path[path.length - 1], value);
        });
        meta.remove("then");
        record.put("ModifiedAt", MediaLiveStore.epoch());
        store.put(region, kind, id, record);
    }

    private void later(ObjectNode record, Map<String, String> then) {
        ObjectNode meta = MediaLiveStore.meta(record);
        meta.put("transitionAt", System.currentTimeMillis() + Math.max(1, store.config().transitionMillis()));
        ObjectNode values = meta.putObject("then");
        then.forEach(values::put);
    }

    private void gone(ObjectNode record) {
        MediaLiveStore.meta(record).put("transitionAt", System.currentTimeMillis()
                + Math.max(1, store.config().transitionMillis())).put("gone", true);
    }

    private ObjectNode created(String region, String kind, String id, String arnResource, JsonNode in) {
        boolean uniqueNames = kind.startsWith("alarm") || kind.startsWith("rule") || SIGNAL_MAP.equals(kind);
        if (uniqueNames && in.hasNonNull("Name")) {
            for (ObjectNode existing : store.list(region, kind)) {
                if (in.get("Name").asText().equals(existing.path("Name").asText())) {
                    throw MediaLiveStore.conflict("A resource named " + in.get("Name").asText() + " already exists.");
                }
            }
        }
        ObjectNode record = store.record();
        record.put("Id", id);
        record.put("Arn", store.arn(region, arnResource));
        MediaLiveStore.copy(in, record, "Name", "Description");
        record.putObject("Tags");
        MediaLiveInputs.setTags(record, in.path("Tags"));
        MediaLiveStore.meta(record).put("id", id);
        return record;
    }

    private void save(String region, String kind, ObjectNode record) {
        store.put(region, kind, MediaLiveStore.meta(record).path("id").asText(), record);
    }

    // ================================================================ reservations and offerings

    private static final String[][] OFFERINGS = {
        {"87654321", "CHANNEL", "AVC", "HD", "MAX_10_MBPS", "STANDARD"},
        {"87654322", "CHANNEL", "HEVC", "UHD", "MAX_50_MBPS", "STANDARD"},
        {"87654323", "INPUT", "AVC", "HD", "MAX_20_MBPS", "STANDARD"},
        {"87654324", "OUTPUT", "AVC", "HD", "MAX_10_MBPS", "STANDARD"},
        {"87654325", "CHANNEL", "AVC", "SD", "MAX_10_MBPS", "SINGLE_PIPELINE"},
    };

    private List<ObjectNode> offerings(String region) {
        List<ObjectNode> offerings = new ArrayList<>();
        for (String[] offering : OFFERINGS) {
            ObjectNode item = mapper.createObjectNode();
            item.put("OfferingId", offering[0]);
            item.put("Arn", store.arn(region, "offering:" + offering[0]));
            item.put("CurrencyCode", "USD");
            item.put("Duration", 12);
            item.put("DurationUnits", "MONTHS");
            item.put("FixedPrice", 0.0);
            item.put("UsagePrice", 1.25);
            item.put("OfferingType", "NO_UPFRONT");
            item.put("Region", region);
            item.put("OfferingDescription", offering[5] + " " + offering[1] + " " + offering[2] + " "
                    + offering[3] + " " + offering[4] + " in " + region);
            ObjectNode specification = item.putObject("ResourceSpecification");
            specification.put("ResourceType", offering[1]);
            specification.put("Codec", offering[2]);
            specification.put("Resolution", offering[3]);
            specification.put("MaximumBitrate", offering[4]);
            specification.put("ChannelClass", offering[5]);
            specification.put("MaximumFramerate", "MAX_30_FPS");
            specification.put("SpecialFeature", "NONE");
            specification.put("VideoQuality", "STANDARD");
            offerings.add(item);
        }
        return offerings;
    }

    public ObjectNode listOfferings(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode offering : offerings(region)) {
            JsonNode specification = offering.path("ResourceSpecification");
            boolean keep = true;
            for (String filter : List.of("ChannelClass", "Codec", "MaximumBitrate", "MaximumFramerate", "Resolution",
                    "ResourceType", "SpecialFeature", "VideoQuality")) {
                keep &= !in.hasNonNull(filter) || in.get(filter).asText().equals(specification.path(filter).asText());
            }
            keep &= !in.hasNonNull("Duration") || in.get("Duration").asInt() == offering.path("Duration").asInt();
            if (keep) {
                items.add(offering);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(items, in, out, "Offerings", 100);
        return out;
    }

    public ObjectNode describeOffering(JsonNode in, String region) {
        for (ObjectNode offering : offerings(region)) {
            if (offering.path("OfferingId").asText().equals(in.path("OfferingId").asText())) {
                return offering;
            }
        }
        throw MediaLiveStore.notFound("The offering " + in.path("OfferingId").asText() + " doesn't exist.");
    }

    public ObjectNode purchaseOffering(JsonNode in, String region) {
        ObjectNode offering = describeOffering(in, region);
        int count = in.path("Count").asInt(0);
        if (count < 1) {
            throw MediaLiveStore.badRequest("PurchaseOffering needs a Count of at least 1.");
        }
        OffsetDateTime start;
        try {
            start = in.hasNonNull("Start") ? OffsetDateTime.parse(in.get("Start").asText())
                    : OffsetDateTime.now(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw MediaLiveStore.badRequest("Start is ISO 8601, YYYY-MM-DDTHH:MM:SSZ.");
        }
        String id = store.numericId(region, MediaLiveStore.RESERVATION);
        ObjectNode reservation = store.record();
        reservation.setAll(offering.deepCopy());
        reservation.remove("Arn");
        reservation.put("ReservationId", id);
        reservation.put("Arn", store.arn(region, "reservation:" + id));
        reservation.put("Count", count);
        reservation.put("Name", in.path("Name").asText(""));
        reservation.put("Start", TIME.format(start.withOffsetSameInstant(ZoneOffset.UTC)));
        reservation.put("End", TIME.format(start.withOffsetSameInstant(ZoneOffset.UTC).plusMonths(12)));
        reservation.put("State", "ACTIVE");
        reservation.set("RenewalSettings", in.path("RenewalSettings").isObject() ? in.get("RenewalSettings")
                .deepCopy() : mapper.createObjectNode().put("AutomaticRenewal", "DISABLED"));
        reservation.putObject("Tags");
        MediaLiveInputs.setTags(reservation, in.path("Tags"));
        store.put(region, MediaLiveStore.RESERVATION, id, reservation);
        return wrap("Reservation", MediaLiveStore.view(reservation));
    }

    private ObjectNode reservation(String region, String id) {
        ObjectNode reservation = store.get(region, MediaLiveStore.RESERVATION, id).orElseThrow(() ->
                MediaLiveStore.notFound("The reservation " + id + " doesn't exist."));
        if ("ACTIVE".equals(reservation.path("State").asText())
                && Instant.parse(reservation.path("End").asText()).isBefore(Instant.now())) {
            reservation.put("State", "EXPIRED");
        }
        return reservation;
    }

    public ObjectNode describeReservation(JsonNode in, String region) {
        return MediaLiveStore.view(reservation(region, in.path("ReservationId").asText()));
    }

    public ObjectNode updateReservation(JsonNode in, String region) {
        ObjectNode reservation = reservation(region, in.path("ReservationId").asText());
        MediaLiveStore.copy(in, reservation, "Name", "RenewalSettings");
        store.put(region, MediaLiveStore.RESERVATION, in.path("ReservationId").asText(), reservation);
        return wrap("Reservation", MediaLiveStore.view(reservation));
    }

    public ObjectNode deleteReservation(JsonNode in, String region) {
        ObjectNode reservation = reservation(region, in.path("ReservationId").asText());
        if ("ACTIVE".equals(reservation.path("State").asText())) {
            throw MediaLiveStore.badRequest("An ACTIVE reservation can't be deleted.");
        }
        store.delete(region, MediaLiveStore.RESERVATION, in.path("ReservationId").asText());
        ObjectNode out = MediaLiveStore.view(reservation);
        out.put("State", "DELETED");
        return out;
    }

    public ObjectNode listReservations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode reservation : store.list(region, MediaLiveStore.RESERVATION)) {
            items.add(MediaLiveStore.view(reservation(region, reservation.path("ReservationId").asText())));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(items, in, out, "Reservations", 100);
        return out;
    }

    // ================================================================ account, versions, tags

    public ObjectNode describeAccountConfiguration(JsonNode in, String region) {
        ObjectNode configuration = store.get(region, MediaLiveStore.ACCOUNT, "configuration")
                .map(MediaLiveStore::view).orElseGet(mapper::createObjectNode);
        return wrap("AccountConfiguration", configuration);
    }

    public ObjectNode updateAccountConfiguration(JsonNode in, String region) {
        ObjectNode configuration = store.record();
        String key = in.path("AccountConfiguration").path("KmsKeyId").asText(null);
        if (key != null) {
            configuration.put("KmsKeyId", key);
        }
        store.put(region, MediaLiveStore.ACCOUNT, "configuration", configuration);
        return wrap("AccountConfiguration", MediaLiveStore.view(configuration));
    }

    public ObjectNode listVersions(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.putArray("Versions").addObject().put("Version", ENGINE_VERSION).put("ExpirationDate",
                Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli() / 1000.0);
        return out;
    }

    /** The record an ARN names, of any taggable kind, with its kind. */
    private Object[] tagged(String region, String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            throw MediaLiveStore.badRequest("The resource ARN " + arn + " is not valid.");
        }
        for (String kind : TAGGABLE) {
            for (ObjectNode record : store.list(region, kind)) {
                if (arn.equals(record.path("Arn").asText())) {
                    return new Object[] {kind, record};
                }
            }
        }
        throw MediaLiveStore.notFound("The resource " + arn + " doesn't exist.");
    }

    public ObjectNode createTags(JsonNode in, String region) {
        Object[] tagged = tagged(region, in.path("ResourceArn").asText());
        ObjectNode record = (ObjectNode) tagged[1];
        MediaLiveInputs.setTags(record, in.path("Tags"));
        save(region, (String) tagged[0], record);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteTags(JsonNode in, String region) {
        Object[] tagged = tagged(region, in.path("ResourceArn").asText());
        ObjectNode record = (ObjectNode) tagged[1];
        in.path("TagKeys").forEach(key -> MediaLiveInputs.tagsOf(record).remove(key.asText()));
        save(region, (String) tagged[0], record);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode record = (ObjectNode) tagged(region, in.path("ResourceArn").asText())[1];
        return wrap("Tags", MediaLiveInputs.tagsOf(record).deepCopy());
    }

    // ================================================================ workflow monitor: templates and groups

    private ObjectNode group(String region, String kind, String identifier) {
        return find(region, kind, identifier, ALARM_GROUP.equals(kind) ? "CloudWatch alarm template group"
                : "EventBridge rule template group");
    }

    private ObjectNode createGroup(JsonNode in, String region, String kind, String prefix, String arnType) {
        String id = MediaLiveStore.prefixedId(prefix);
        ObjectNode group = created(region, kind, id, arnType + ":" + id, in);
        double now = MediaLiveStore.epoch();
        group.put("CreatedAt", now);
        group.put("ModifiedAt", now);
        save(region, kind, group);
        return MediaLiveStore.view(group);
    }

    private ObjectNode groupSummary(String region, String groupKind, String templateKind, ObjectNode group) {
        ObjectNode view = MediaLiveStore.view(group);
        int count = 0;
        for (ObjectNode template : store.list(region, templateKind)) {
            count += group.path("Id").asText().equals(template.path("GroupId").asText()) ? 1 : 0;
        }
        view.put("TemplateCount", count);
        return view;
    }

    private ObjectNode deleteGroup(JsonNode in, String region, String groupKind, String templateKind) {
        ObjectNode group = group(region, groupKind, in.path("Identifier").asText());
        if (groupSummary(region, groupKind, templateKind, group).path("TemplateCount").asInt() > 0) {
            throw MediaLiveStore.conflict("The group has templates; delete them first.");
        }
        for (ObjectNode map : store.list(region, SIGNAL_MAP)) {
            for (JsonNode used : map.path(ALARM_GROUP.equals(groupKind) ? "CloudWatchAlarmTemplateGroupIds"
                    : "EventBridgeRuleTemplateGroupIds")) {
                if (group.path("Id").asText().equals(used.asText())) {
                    throw MediaLiveStore.conflict("The signal map " + map.path("Name").asText() + " uses the group.");
                }
            }
        }
        store.delete(region, groupKind, group.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode createCloudWatchAlarmTemplateGroup(JsonNode in, String region) {
        return createGroup(in, region, ALARM_GROUP, "awg-", "cloudwatch-alarm-template-group");
    }

    public ObjectNode getCloudWatchAlarmTemplateGroup(JsonNode in, String region) {
        return MediaLiveStore.view(group(region, ALARM_GROUP, in.path("Identifier").asText()));
    }

    public ObjectNode updateCloudWatchAlarmTemplateGroup(JsonNode in, String region) {
        ObjectNode group = group(region, ALARM_GROUP, in.path("Identifier").asText());
        MediaLiveStore.copy(in, group, "Description");
        group.put("ModifiedAt", MediaLiveStore.epoch());
        save(region, ALARM_GROUP, group);
        return MediaLiveStore.view(group);
    }

    public ObjectNode deleteCloudWatchAlarmTemplateGroup(JsonNode in, String region) {
        return deleteGroup(in, region, ALARM_GROUP, ALARM_TEMPLATE);
    }

    public ObjectNode listCloudWatchAlarmTemplateGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(region, ALARM_GROUP)) {
            items.add(groupSummary(region, ALARM_GROUP, ALARM_TEMPLATE, group));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(items, in, out, "CloudWatchAlarmTemplateGroups", 100);
        return out;
    }

    public ObjectNode createEventBridgeRuleTemplateGroup(JsonNode in, String region) {
        return createGroup(in, region, RULE_GROUP, "ebg-", "eventbridge-rule-template-group");
    }

    public ObjectNode getEventBridgeRuleTemplateGroup(JsonNode in, String region) {
        return MediaLiveStore.view(group(region, RULE_GROUP, in.path("Identifier").asText()));
    }

    public ObjectNode updateEventBridgeRuleTemplateGroup(JsonNode in, String region) {
        ObjectNode group = group(region, RULE_GROUP, in.path("Identifier").asText());
        MediaLiveStore.copy(in, group, "Description");
        group.put("ModifiedAt", MediaLiveStore.epoch());
        save(region, RULE_GROUP, group);
        return MediaLiveStore.view(group);
    }

    public ObjectNode deleteEventBridgeRuleTemplateGroup(JsonNode in, String region) {
        return deleteGroup(in, region, RULE_GROUP, RULE_TEMPLATE);
    }

    public ObjectNode listEventBridgeRuleTemplateGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode group : store.list(region, RULE_GROUP)) {
            items.add(groupSummary(region, RULE_GROUP, RULE_TEMPLATE, group));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(items, in, out, "EventBridgeRuleTemplateGroups", 100);
        return out;
    }

    private static final String[] ALARM_FIELDS = {"ComparisonOperator", "DatapointsToAlarm", "EvaluationPeriods",
        "MetricName", "Period", "Statistic", "TargetResourceType", "Threshold", "TreatMissingData"};

    public ObjectNode createCloudWatchAlarmTemplate(JsonNode in, String region) {
        ObjectNode group = group(region, ALARM_GROUP, in.path("GroupIdentifier").asText());
        String id = MediaLiveStore.prefixedId("aw-");
        ObjectNode template = created(region, ALARM_TEMPLATE, id, "cloudwatch-alarm-template:" + id, in);
        MediaLiveStore.copy(in, template, ALARM_FIELDS);
        template.put("GroupId", group.path("Id").asText());
        double now = MediaLiveStore.epoch();
        template.put("CreatedAt", now);
        template.put("ModifiedAt", now);
        save(region, ALARM_TEMPLATE, template);
        return MediaLiveStore.view(template);
    }

    public ObjectNode getCloudWatchAlarmTemplate(JsonNode in, String region) {
        return MediaLiveStore.view(find(region, ALARM_TEMPLATE, in.path("Identifier").asText(),
                "CloudWatch alarm template"));
    }

    public ObjectNode updateCloudWatchAlarmTemplate(JsonNode in, String region) {
        ObjectNode template = find(region, ALARM_TEMPLATE, in.path("Identifier").asText(), "CloudWatch alarm "
                + "template");
        MediaLiveStore.copy(in, template, ALARM_FIELDS);
        MediaLiveStore.copy(in, template, "Description", "Name");
        if (in.hasNonNull("GroupIdentifier")) {
            template.put("GroupId", group(region, ALARM_GROUP, in.get("GroupIdentifier").asText()).path("Id")
                    .asText());
        }
        template.put("ModifiedAt", MediaLiveStore.epoch());
        save(region, ALARM_TEMPLATE, template);
        return MediaLiveStore.view(template);
    }

    public ObjectNode deleteCloudWatchAlarmTemplate(JsonNode in, String region) {
        ObjectNode template = find(region, ALARM_TEMPLATE, in.path("Identifier").asText(), "CloudWatch alarm "
                + "template");
        store.delete(region, ALARM_TEMPLATE, template.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listCloudWatchAlarmTemplates(JsonNode in, String region) {
        String group = in.hasNonNull("GroupIdentifier") ? group(region, ALARM_GROUP, in.get("GroupIdentifier")
                .asText()).path("Id").asText() : null;
        return list(region, ALARM_TEMPLATE, in, "CloudWatchAlarmTemplates", template -> group == null
                || group.equals(template.path("GroupId").asText()));
    }

    public ObjectNode createEventBridgeRuleTemplate(JsonNode in, String region) {
        ObjectNode group = group(region, RULE_GROUP, in.path("GroupIdentifier").asText());
        String id = MediaLiveStore.prefixedId("eb-");
        ObjectNode template = created(region, RULE_TEMPLATE, id, "eventbridge-rule-template:" + id, in);
        MediaLiveStore.copy(in, template, "EventType", "EventTargets");
        template.put("GroupId", group.path("Id").asText());
        double now = MediaLiveStore.epoch();
        template.put("CreatedAt", now);
        template.put("ModifiedAt", now);
        save(region, RULE_TEMPLATE, template);
        return MediaLiveStore.view(template);
    }

    public ObjectNode getEventBridgeRuleTemplate(JsonNode in, String region) {
        return MediaLiveStore.view(find(region, RULE_TEMPLATE, in.path("Identifier").asText(),
                "EventBridge rule template"));
    }

    public ObjectNode updateEventBridgeRuleTemplate(JsonNode in, String region) {
        ObjectNode template = find(region, RULE_TEMPLATE, in.path("Identifier").asText(), "EventBridge rule "
                + "template");
        MediaLiveStore.copy(in, template, "Description", "Name", "EventType", "EventTargets");
        if (in.hasNonNull("GroupIdentifier")) {
            template.put("GroupId", group(region, RULE_GROUP, in.get("GroupIdentifier").asText()).path("Id")
                    .asText());
        }
        template.put("ModifiedAt", MediaLiveStore.epoch());
        save(region, RULE_TEMPLATE, template);
        return MediaLiveStore.view(template);
    }

    public ObjectNode deleteEventBridgeRuleTemplate(JsonNode in, String region) {
        ObjectNode template = find(region, RULE_TEMPLATE, in.path("Identifier").asText(), "EventBridge rule "
                + "template");
        store.delete(region, RULE_TEMPLATE, template.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listEventBridgeRuleTemplates(JsonNode in, String region) {
        String group = in.hasNonNull("GroupIdentifier") ? group(region, RULE_GROUP, in.get("GroupIdentifier")
                .asText()).path("Id").asText() : null;
        return list(region, RULE_TEMPLATE, in, "EventBridgeRuleTemplates", template -> group == null
                || group.equals(template.path("GroupId").asText()));
    }

    // ================================================================ workflow monitor: signal maps

    private ArrayNode groupIds(String region, JsonNode identifiers, String kind) {
        ArrayNode ids = mapper.createArrayNode();
        for (JsonNode identifier : identifiers) {
            ids.add(group(region, kind, identifier.asText()).path("Id").asText());
        }
        return ids;
    }

    /** The media resources a signal map discovers from its entry point: the entry point itself, by ARN. */
    private ObjectNode discover(String arn) {
        ObjectNode map = mapper.createObjectNode();
        ObjectNode resource = map.putObject(arn);
        resource.put("Name", AwsArnUtils.isArn(arn) ? AwsArnUtils.parse(arn).resource() : arn);
        resource.putArray("Destinations");
        resource.putArray("Sources");
        return map;
    }

    public ObjectNode createSignalMap(JsonNode in, String region) {
        String entry = in.path("DiscoveryEntryPointArn").asText("");
        if (!AwsArnUtils.isArn(entry)) {
            throw MediaLiveStore.badRequest("DiscoveryEntryPointArn must be an ARN.");
        }
        String id = MediaLiveStore.prefixedId("sm-");
        ObjectNode map = created(region, SIGNAL_MAP, id, "signal-map:" + id, in);
        map.put("DiscoveryEntryPointArn", entry);
        map.set("CloudWatchAlarmTemplateGroupIds", groupIds(region, in.path("CloudWatchAlarmTemplateGroupIdentifiers"),
                ALARM_GROUP));
        map.set("EventBridgeRuleTemplateGroupIds", groupIds(region, in.path("EventBridgeRuleTemplateGroupIdentifiers"),
                RULE_GROUP));
        double now = MediaLiveStore.epoch();
        map.put("CreatedAt", now);
        map.put("ModifiedAt", now);
        map.put("Status", "CREATE_IN_PROGRESS");
        map.put("MonitorChangesPendingDeployment", !map.path("CloudWatchAlarmTemplateGroupIds").isEmpty()
                || !map.path("EventBridgeRuleTemplateGroupIds").isEmpty());
        map.putObject("FailedMediaResourceMap");
        map.set("MediaResourceMap", discover(entry));
        map.put("LastDiscoveredAt", now);
        later(map, Map.of("Status", "CREATE_COMPLETE"));
        save(region, SIGNAL_MAP, map);
        return MediaLiveStore.view(map);
    }

    private ObjectNode signalMap(String region, String identifier) {
        return find(region, SIGNAL_MAP, identifier, "signal map");
    }

    public ObjectNode getSignalMap(JsonNode in, String region) {
        return MediaLiveStore.view(signalMap(region, in.path("Identifier").asText()));
    }

    public ObjectNode startUpdateSignalMap(JsonNode in, String region) {
        ObjectNode map = signalMap(region, in.path("Identifier").asText());
        MediaLiveStore.copy(in, map, "Description", "Name");
        if (in.hasNonNull("DiscoveryEntryPointArn")) {
            map.put("DiscoveryEntryPointArn", in.get("DiscoveryEntryPointArn").asText());
            map.set("MediaResourceMap", discover(in.get("DiscoveryEntryPointArn").asText()));
        }
        if (in.has("CloudWatchAlarmTemplateGroupIdentifiers")) {
            map.set("CloudWatchAlarmTemplateGroupIds", groupIds(region, in.get(
                    "CloudWatchAlarmTemplateGroupIdentifiers"), ALARM_GROUP));
            map.put("MonitorChangesPendingDeployment", true);
        }
        if (in.has("EventBridgeRuleTemplateGroupIdentifiers")) {
            map.set("EventBridgeRuleTemplateGroupIds", groupIds(region, in.get(
                    "EventBridgeRuleTemplateGroupIdentifiers"), RULE_GROUP));
            map.put("MonitorChangesPendingDeployment", true);
        }
        map.put("Status", "UPDATE_IN_PROGRESS");
        map.put("LastDiscoveredAt", MediaLiveStore.epoch());
        later(map, Map.of("Status", "UPDATE_COMPLETE"));
        save(region, SIGNAL_MAP, map);
        return MediaLiveStore.view(map);
    }

    public ObjectNode startMonitorDeployment(JsonNode in, String region) {
        ObjectNode map = signalMap(region, in.path("Identifier").asText());
        if (in.path("DryRun").asBoolean(false)) {
            ObjectNode view = MediaLiveStore.view(map);
            view.withObject("/MonitorDeployment").put("Status", "DRY_RUN_DEPLOYMENT_COMPLETE");
            return view;
        }
        ObjectNode deployment = map.putObject("MonitorDeployment");
        deployment.put("Status", "DEPLOYMENT_IN_PROGRESS");
        deployment.put("DetailsUri", "s3://medialive-workflow-monitor-" + region + "/" + map.path("Id").asText()
                + "/monitor.json");
        later(map, Map.of("MonitorDeployment.Status", "DEPLOYMENT_COMPLETE", "MonitorChangesPendingDeployment",
                "false"));
        map.withObject("/LastSuccessfulMonitorDeployment").put("DetailsUri", deployment.path("DetailsUri").asText())
                .put("Status", "DEPLOYMENT_COMPLETE");
        save(region, SIGNAL_MAP, map);
        return MediaLiveStore.view(map);
    }

    public ObjectNode startDeleteMonitorDeployment(JsonNode in, String region) {
        ObjectNode map = signalMap(region, in.path("Identifier").asText());
        map.withObject("/MonitorDeployment").put("Status", "DELETE_IN_PROGRESS");
        later(map, Map.of("MonitorDeployment.Status", "DELETE_COMPLETE"));
        save(region, SIGNAL_MAP, map);
        return MediaLiveStore.view(map);
    }

    public ObjectNode deleteSignalMap(JsonNode in, String region) {
        ObjectNode map = signalMap(region, in.path("Identifier").asText());
        String deployment = map.path("MonitorDeployment").path("Status").asText("");
        if (deployment.startsWith("DEPLOYMENT") && !"DELETE_COMPLETE".equals(deployment)) {
            throw MediaLiveStore.conflict("The signal map has a monitor deployment; delete it first.");
        }
        store.delete(region, SIGNAL_MAP, map.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listSignalMaps(JsonNode in, String region) {
        String alarms = in.hasNonNull("CloudWatchAlarmTemplateGroupIdentifier") ? group(region, ALARM_GROUP,
                in.get("CloudWatchAlarmTemplateGroupIdentifier").asText()).path("Id").asText() : null;
        String rules = in.hasNonNull("EventBridgeRuleTemplateGroupIdentifier") ? group(region, RULE_GROUP,
                in.get("EventBridgeRuleTemplateGroupIdentifier").asText()).path("Id").asText() : null;
        return list(region, SIGNAL_MAP, in, "SignalMaps", map -> contains(map, "CloudWatchAlarmTemplateGroupIds",
                alarms) && contains(map, "EventBridgeRuleTemplateGroupIds", rules));
    }

    private static boolean contains(ObjectNode record, String member, String id) {
        if (id == null) {
            return true;
        }
        for (JsonNode value : record.path(member)) {
            if (id.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ MediaLive Anywhere

    private ObjectNode network(String region, String id) {
        return find(region, NETWORK, id, "network");
    }

    public ObjectNode createNetwork(JsonNode in, String region) {
        for (JsonNode pool : in.path("IpPools")) {
            if (!MediaLiveInputs.validCidr(pool.path("Cidr").asText(""))) {
                throw MediaLiveStore.badRequest("Each IP pool needs a valid Cidr.");
            }
        }
        String id = store.numericId(region, NETWORK);
        ObjectNode network = created(region, NETWORK, id, "network:" + id, in);
        network.set("IpPools", in.path("IpPools").isArray() ? in.get("IpPools").deepCopy() : mapper.createArrayNode());
        network.set("Routes", in.path("Routes").isArray() ? in.get("Routes").deepCopy() : mapper.createArrayNode());
        network.putArray("AssociatedClusterIds");
        network.put("State", "CREATING");
        later(network, Map.of("State", "ACTIVE"));
        save(region, NETWORK, network);
        return MediaLiveStore.view(network);
    }

    public ObjectNode describeNetwork(JsonNode in, String region) {
        return MediaLiveStore.view(network(region, in.path("NetworkId").asText()));
    }

    public ObjectNode updateNetwork(JsonNode in, String region) {
        ObjectNode network = network(region, in.path("NetworkId").asText());
        MediaLiveStore.copy(in, network, "Name", "IpPools", "Routes");
        save(region, NETWORK, network);
        return MediaLiveStore.view(network);
    }

    public ObjectNode deleteNetwork(JsonNode in, String region) {
        ObjectNode network = network(region, in.path("NetworkId").asText());
        if (!network.path("AssociatedClusterIds").isEmpty()) {
            throw MediaLiveStore.conflict("The network is used by clusters.");
        }
        network.put("State", "DELETING");
        gone(network);
        save(region, NETWORK, network);
        return MediaLiveStore.view(network);
    }

    public ObjectNode listNetworks(JsonNode in, String region) {
        return list(region, NETWORK, in, "Networks", network -> true);
    }

    private ObjectNode cluster(String region, String id) {
        return find(region, CLUSTER, id, "cluster");
    }

    public ObjectNode createCluster(JsonNode in, String region) {
        String id = store.numericId(region, CLUSTER);
        ObjectNode cluster = created(region, CLUSTER, id, "cluster:" + id, in);
        cluster.put("ClusterType", in.path("ClusterType").asText("ON_PREMISES"));
        MediaLiveStore.copy(in, cluster, "InstanceRoleArn", "NetworkSettings");
        cluster.putArray("ChannelIds");
        for (JsonNode mapping : in.path("NetworkSettings").path("InterfaceMappings")) {
            ObjectNode network = network(region, mapping.path("NetworkId").asText());
            network.withArray("AssociatedClusterIds").add(id);
            network.put("State", "IN_USE");
            save(region, NETWORK, network);
        }
        cluster.put("State", "CREATING");
        later(cluster, Map.of("State", "ACTIVE"));
        save(region, CLUSTER, cluster);
        return MediaLiveStore.view(cluster);
    }

    public ObjectNode describeCluster(JsonNode in, String region) {
        return MediaLiveStore.view(cluster(region, in.path("ClusterId").asText()));
    }

    public ObjectNode updateCluster(JsonNode in, String region) {
        ObjectNode cluster = cluster(region, in.path("ClusterId").asText());
        MediaLiveStore.copy(in, cluster, "Name", "NetworkSettings");
        save(region, CLUSTER, cluster);
        return MediaLiveStore.view(cluster);
    }

    public ObjectNode deleteCluster(JsonNode in, String region) {
        ObjectNode cluster = cluster(region, in.path("ClusterId").asText());
        for (ObjectNode node : store.list(region, NODE)) {
            if (cluster.path("Id").asText().equals(node.path("ClusterId").asText())) {
                throw MediaLiveStore.conflict("The cluster has nodes; delete them first.");
            }
        }
        for (ObjectNode network : store.list(region, NETWORK)) {
            ArrayNode ids = (ArrayNode) network.withArray("AssociatedClusterIds");
            for (int i = ids.size() - 1; i >= 0; i--) {
                if (cluster.path("Id").asText().equals(ids.get(i).asText())) {
                    ids.remove(i);
                }
            }
            network.put("State", ids.isEmpty() ? "ACTIVE" : "IN_USE");
            save(region, NETWORK, network);
        }
        cluster.put("State", "DELETING");
        gone(cluster);
        save(region, CLUSTER, cluster);
        return MediaLiveStore.view(cluster);
    }

    public ObjectNode listClusters(JsonNode in, String region) {
        return list(region, CLUSTER, in, "Clusters", cluster -> true);
    }

    public ObjectNode listClusterAlerts(JsonNode in, String region) {
        cluster(region, in.path("ClusterId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.putArray("Alerts");
        return out;
    }

    private ObjectNode node(String region, String clusterId, String nodeId) {
        ObjectNode node = find(region, NODE, nodeId, "node");
        if (!clusterId.equals(node.path("ClusterId").asText())) {
            throw MediaLiveStore.notFound("The node " + nodeId + " isn't in the cluster " + clusterId + ".");
        }
        return node;
    }

    public ObjectNode createNode(JsonNode in, String region) {
        ObjectNode cluster = cluster(region, in.path("ClusterId").asText());
        String id = store.numericId(region, NODE);
        ObjectNode node = created(region, NODE, id, "node:" + cluster.path("Id").asText() + "/" + id, in);
        node.put("ClusterId", cluster.path("Id").asText());
        node.put("Role", in.path("Role").asText("ACTIVE"));
        node.set("NodeInterfaceMappings", in.path("NodeInterfaceMappings").isArray()
                ? in.get("NodeInterfaceMappings").deepCopy() : mapper.createArrayNode());
        node.putArray("ChannelPlacementGroups");
        node.putArray("SdiSourceMappings");
        node.put("ConnectionState", "DISCONNECTED");
        node.put("State", "CREATED");
        save(region, NODE, node);
        return MediaLiveStore.view(node);
    }

    public ObjectNode createNodeRegistrationScript(JsonNode in, String region) {
        ObjectNode cluster = cluster(region, in.path("ClusterId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("NodeRegistrationScript", "#!/bin/bash\n# MediaLive Anywhere node registration for cluster "
                + cluster.path("Id").asText() + " (" + region + ").\n# Floci doesn't run on-premises nodes; this "
                + "script registers nothing.\necho \"cluster " + cluster.path("Id").asText() + ", node "
                + in.path("Name").asText("") + "\"\n");
        return out;
    }

    public ObjectNode describeNode(JsonNode in, String region) {
        return MediaLiveStore.view(node(region, in.path("ClusterId").asText(), in.path("NodeId").asText()));
    }

    public ObjectNode updateNode(JsonNode in, String region) {
        ObjectNode node = node(region, in.path("ClusterId").asText(), in.path("NodeId").asText());
        MediaLiveStore.copy(in, node, "Name", "Role", "SdiSourceMappings");
        save(region, NODE, node);
        return MediaLiveStore.view(node);
    }

    public ObjectNode updateNodeState(JsonNode in, String region) {
        ObjectNode node = node(region, in.path("ClusterId").asText(), in.path("NodeId").asText());
        if ("ACTIVE".equals(in.path("State").asText()) && "CREATED".equals(node.path("State").asText())) {
            throw MediaLiveStore.badRequest("The node hasn't registered; run its registration script on the "
                    + "node's hardware first.");
        }
        node.put("State", in.path("State").asText());
        save(region, NODE, node);
        return MediaLiveStore.view(node);
    }

    public ObjectNode deleteNode(JsonNode in, String region) {
        ObjectNode node = node(region, in.path("ClusterId").asText(), in.path("NodeId").asText());
        store.delete(region, NODE, node.path("Id").asText());
        ObjectNode out = MediaLiveStore.view(node);
        out.put("State", "DEREGISTERED");
        return out;
    }

    public ObjectNode listNodes(JsonNode in, String region) {
        String cluster = cluster(region, in.path("ClusterId").asText()).path("Id").asText();
        return list(region, NODE, in, "Nodes", node -> cluster.equals(node.path("ClusterId").asText()));
    }

    private ObjectNode placementGroup(String region, String clusterId, String id) {
        ObjectNode group = find(region, PLACEMENT_GROUP, id, "channel placement group");
        if (!clusterId.equals(group.path("ClusterId").asText())) {
            throw MediaLiveStore.notFound("The channel placement group " + id + " isn't in the cluster "
                    + clusterId + ".");
        }
        return group;
    }

    public ObjectNode createChannelPlacementGroup(JsonNode in, String region) {
        ObjectNode cluster = cluster(region, in.path("ClusterId").asText());
        for (JsonNode node : in.path("Nodes")) {
            node(region, cluster.path("Id").asText(), node.asText());
        }
        String id = store.numericId(region, PLACEMENT_GROUP);
        ObjectNode group = created(region, PLACEMENT_GROUP, id, "channelPlacementGroup:" + cluster.path("Id").asText()
                + "/" + id, in);
        group.put("ClusterId", cluster.path("Id").asText());
        group.set("Nodes", in.path("Nodes").isArray() ? in.get("Nodes").deepCopy() : mapper.createArrayNode());
        group.putArray("Channels");
        group.put("State", group.path("Nodes").isEmpty() ? "UNASSIGNED" : "ASSIGNING");
        if (!group.path("Nodes").isEmpty()) {
            later(group, Map.of("State", "ASSIGNED"));
        }
        save(region, PLACEMENT_GROUP, group);
        return MediaLiveStore.view(group);
    }

    public ObjectNode describeChannelPlacementGroup(JsonNode in, String region) {
        return MediaLiveStore.view(placementGroup(region, in.path("ClusterId").asText(),
                in.path("ChannelPlacementGroupId").asText()));
    }

    public ObjectNode updateChannelPlacementGroup(JsonNode in, String region) {
        ObjectNode group = placementGroup(region, in.path("ClusterId").asText(),
                in.path("ChannelPlacementGroupId").asText());
        MediaLiveStore.copy(in, group, "Name", "Nodes");
        save(region, PLACEMENT_GROUP, group);
        return MediaLiveStore.view(group);
    }

    public ObjectNode deleteChannelPlacementGroup(JsonNode in, String region) {
        ObjectNode group = placementGroup(region, in.path("ClusterId").asText(),
                in.path("ChannelPlacementGroupId").asText());
        if (!group.path("Channels").isEmpty()) {
            throw MediaLiveStore.conflict("The channel placement group has channels.");
        }
        group.put("State", "DELETING");
        gone(group);
        save(region, PLACEMENT_GROUP, group);
        return MediaLiveStore.view(group);
    }

    public ObjectNode listChannelPlacementGroups(JsonNode in, String region) {
        String cluster = cluster(region, in.path("ClusterId").asText()).path("Id").asText();
        return list(region, PLACEMENT_GROUP, in, "ChannelPlacementGroups", group -> cluster.equals(
                group.path("ClusterId").asText()));
    }

    private ObjectNode sdiSource(String region, String id) {
        return find(region, SDI_SOURCE, id, "SDI source");
    }

    public ObjectNode createSdiSource(JsonNode in, String region) {
        String id = store.numericId(region, SDI_SOURCE);
        ObjectNode source = created(region, SDI_SOURCE, id, "sdiSource:" + id, in);
        source.put("Mode", in.path("Mode").asText("INTERLEAVE"));
        source.put("Type", in.path("Type").asText("SINGLE"));
        source.putArray("Inputs");
        source.put("State", "IDLE");
        save(region, SDI_SOURCE, source);
        return wrap("SdiSource", MediaLiveStore.view(source));
    }

    public ObjectNode describeSdiSource(JsonNode in, String region) {
        return wrap("SdiSource", MediaLiveStore.view(sdiSource(region, in.path("SdiSourceId").asText())));
    }

    public ObjectNode updateSdiSource(JsonNode in, String region) {
        ObjectNode source = sdiSource(region, in.path("SdiSourceId").asText());
        MediaLiveStore.copy(in, source, "Name", "Mode", "Type");
        save(region, SDI_SOURCE, source);
        return wrap("SdiSource", MediaLiveStore.view(source));
    }

    public ObjectNode deleteSdiSource(JsonNode in, String region) {
        ObjectNode source = sdiSource(region, in.path("SdiSourceId").asText());
        for (ObjectNode input : store.list(region, MediaLiveStore.INPUT)) {
            for (JsonNode used : input.path("SdiSources")) {
                if (source.path("Id").asText().equals(used.asText())) {
                    throw MediaLiveStore.conflict("The SDI source is used by the input " + input.path("Id")
                            .asText() + ".");
                }
            }
        }
        store.delete(region, SDI_SOURCE, source.path("Id").asText());
        ObjectNode view = MediaLiveStore.view(source);
        view.put("State", "DELETED");
        return wrap("SdiSource", view);
    }

    public ObjectNode listSdiSources(JsonNode in, String region) {
        return list(region, SDI_SOURCE, in, "SdiSources", source -> true);
    }
}
