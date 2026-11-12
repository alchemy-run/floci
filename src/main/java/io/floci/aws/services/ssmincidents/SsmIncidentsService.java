package io.floci.aws.services.ssmincidents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.ssmcontacts.SsmContactsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Systems Manager Incident Manager: the replication set that onboards an account, response plans, and the
 * incidents started from them, with their timeline, related items and resource policies.
 *
 * <p>Starting an incident publishes to the plan's SNS notification targets and engages the plan's SSM Contacts
 * contacts, and resolving one notifies the targets again. Deleting the replication set offboards the account:
 * plans, incidents and contacts go with it. Response plan actions are checked but not run, since Floci has no
 * SSM Automation.
 */
@ApplicationScoped
public class SsmIncidentsService implements Resettable {

    private static final String SET = "replication-set";
    private static final Set<String> USER_EVENT_TYPES = Set.of("Custom Event", "Note");

    private final AccountAwareStorageBackend<ObjectNode> sets;
    private final AccountAwareStorageBackend<ObjectNode> plans;
    private final AccountAwareStorageBackend<ObjectNode> records;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<SnsService> sns;
    private final Provider<SsmContactsService> contacts;

    @Inject
    public SsmIncidentsService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                               Provider<SnsService> sns, Provider<SsmContactsService> contacts) {
        this.sets = storageFactory.create("ssm-incidents", "replication-sets.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.plans = storageFactory.create("ssm-incidents", "response-plans.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.records = storageFactory.create("ssm-incidents", "incident-records.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.sns = sns;
        this.contacts = contacts;
    }

    @Override
    public void clear() {
        sets.clear();
        plans.clear();
        records.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateReplicationSet" -> createReplicationSet(in, region);
            case "GetReplicationSet" -> getReplicationSet(in);
            case "ListReplicationSets" -> listReplicationSets(region);
            case "UpdateReplicationSet" -> updateReplicationSet(in);
            case "UpdateDeletionProtection" -> updateDeletionProtection(in);
            case "DeleteReplicationSet" -> deleteReplicationSet(in);
            case "CreateResponsePlan" -> createResponsePlan(in, region);
            case "GetResponsePlan" -> getResponsePlan(in);
            case "ListResponsePlans" -> listResponsePlans(in);
            case "UpdateResponsePlan" -> updateResponsePlan(in, region);
            case "DeleteResponsePlan" -> deleteResponsePlan(in);
            case "PutResourcePolicy" -> putResourcePolicy(in, region);
            case "GetResourcePolicies" -> getResourcePolicies(in);
            case "DeleteResourcePolicy" -> deleteResourcePolicy(in);
            case "StartIncident" -> startIncident(in, region);
            case "GetIncidentRecord" -> wrap("incidentRecord", view(record(required(in, "arn"))));
            case "ListIncidentRecords" -> listIncidentRecords(in);
            case "UpdateIncidentRecord" -> updateIncidentRecord(in, region);
            case "DeleteIncidentRecord" -> deleteIncidentRecord(in);
            case "CreateTimelineEvent" -> createTimelineEvent(in);
            case "GetTimelineEvent" -> getTimelineEvent(in);
            case "UpdateTimelineEvent" -> updateTimelineEvent(in);
            case "DeleteTimelineEvent" -> deleteTimelineEvent(in);
            case "ListTimelineEvents" -> listTimelineEvents(in);
            case "UpdateRelatedItems" -> updateRelatedItems(in);
            case "ListRelatedItems" -> listRelatedItems(in);
            case "ListIncidentFindings" -> listIncidentFindings(in);
            case "BatchGetIncidentFindings" -> batchGetIncidentFindings(in);
            case "TagResource" -> tag(in, true);
            case "UntagResource" -> tag(in, false);
            case "ListTagsForResource" -> listTags(in);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    // ---------- replication set ----------

    /** Whether the account is onboarded in this region: an active replication set that includes it. */
    public boolean onboarded(String region) {
        ObjectNode set = currentSet(AwsRegions.partitionFor(region));
        return set != null && "ACTIVE".equals(set.path("status").asText())
                && "ACTIVE".equals(set.path("regionMap").path(region).path("status").asText());
    }

    private ObjectNode createReplicationSet(JsonNode in, String region) {
        if (currentSet(AwsRegions.partitionFor(region)) != null) {
            throw conflict("The account already has a replication set. Incident Manager has one per account.");
        }
        JsonNode regions = in.path("regions");
        if (!regions.isObject() || regions.isEmpty() || regions.size() > 3) {
            throw validation("A replication set takes 1 to 3 Regions.");
        }
        String partition = AwsRegions.partitionFor(region);
        String id = UUID.randomUUID().toString();
        String account = sets.accountId();
        double now = now();
        ObjectNode set = mapper.createObjectNode();
        set.put("arn", AwsArnUtils.Arn.global(partition, "ssm-incidents", account, "replication-set/" + id).toString())
                .put("createdBy", caller(partition)).put("createdTime", now).put("deletionProtected", false)
                .put("lastModifiedBy", caller(partition)).put("lastModifiedTime", now).put("status", "CREATING");
        ObjectNode map = set.putObject("regionMap");
        regions.fields().forEachRemaining(entry -> {
            requireRegion(entry.getKey(), partition);
            ObjectNode info = map.putObject(entry.getKey()).put("status", "CREATING")
                    .put("statusUpdateDateTime", now);
            if (entry.getValue().hasNonNull("sseKmsKeyId")) {
                info.put("sseKmsKeyId", entry.getValue().get("sseKmsKeyId").asText());
            }
        });
        set.set("_tags", in.path("tags").isObject() ? in.get("tags").deepCopy() : mapper.createObjectNode());
        set.putObject("_").put("ready", now + step());
        sets.put(setKey(partition), set);
        return mapper.createObjectNode().put("arn", set.path("arn").asText());
    }

    private void requireRegion(String name, String partition) {
        if (!name.matches("[a-z]{2}(-[a-z]+)+-\\d") || !partition.equals(AwsRegions.partitionFor(name))) {
            throw validation("The Region " + name + " isn't valid for a replication set.");
        }
    }

    /** The replication set is one per account, and an account lives in one partition. */
    private static String setKey(String partition) {
        return SET + "/" + partition;
    }

    /** The account's replication set in the partition, with any due transitions applied, or null. */
    private ObjectNode currentSet(String partition) {
        ObjectNode set = sets.get(setKey(partition)).map(ObjectNode::deepCopy).orElse(null);
        if (set == null) {
            return null;
        }
        ObjectNode meta = (ObjectNode) set.get("_");
        double now = now();
        boolean changed = false;
        if (meta.has("gone") && now >= meta.path("gone").asDouble()) {
            offboard(partition);
            return null;
        }
        if (now >= meta.path("ready").asDouble(0)) {
            Iterator<Map.Entry<String, JsonNode>> regions = set.withObject("/regionMap").fields();
            while (regions.hasNext()) {
                Map.Entry<String, JsonNode> entry = regions.next();
                ObjectNode info = (ObjectNode) entry.getValue();
                switch (info.path("status").asText()) {
                    case "CREATING" -> {
                        info.put("status", "ACTIVE").put("statusUpdateDateTime", now);
                        changed = true;
                    }
                    case "DELETING" -> {
                        regions.remove();
                        changed = true;
                    }
                    default -> { }
                }
            }
            String status = set.path("status").asText();
            if ("CREATING".equals(status) || "UPDATING".equals(status)) {
                set.put("status", "ACTIVE");
                changed = true;
            }
        }
        if (changed) {
            sets.put(setKey(partition), set);
        }
        return set;
    }

    private ObjectNode requireSet(String arn) {
        ObjectNode set = AwsArnUtils.isArn(arn) ? currentSet(AwsArnUtils.parse(arn).partition()) : null;
        if (set == null || !set.path("arn").asText().equals(arn)) {
            throw notFound("The replication set " + arn + " doesn't exist.");
        }
        return set;
    }

    private ObjectNode getReplicationSet(JsonNode in) {
        ObjectNode set = requireSet(decode(required(in, "arn")));
        return wrap("replicationSet", view(set));
    }

    private ObjectNode listReplicationSets(String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arns = out.putArray("replicationSetArns");
        ObjectNode set = currentSet(AwsRegions.partitionFor(region));
        if (set != null) {
            arns.add(set.path("arn").asText());
        }
        return out;
    }

    private ObjectNode updateReplicationSet(JsonNode in) {
        ObjectNode set = requireSet(required(in, "arn"));
        if (!"ACTIVE".equals(set.path("status").asText())) {
            throw conflict("The replication set is " + set.path("status").asText() + ".");
        }
        String partition = AwsArnUtils.parse(set.path("arn").asText()).partition();
        ObjectNode map = (ObjectNode) set.get("regionMap");
        for (JsonNode action : in.path("actions")) {
            if (action.has("addRegionAction")) {
                String name = action.path("addRegionAction").path("regionName").asText();
                requireRegion(name, partition);
                if (map.has(name)) {
                    throw validation("The replication set already includes " + name + ".");
                }
                if (map.size() >= 3) {
                    throw new AwsException("ServiceQuotaExceededException", "A replication set has at most 3 Regions.",
                            402);
                }
                ObjectNode info = map.putObject(name).put("status", "CREATING").put("statusUpdateDateTime", now());
                if (action.path("addRegionAction").hasNonNull("sseKmsKeyId")) {
                    info.put("sseKmsKeyId", action.path("addRegionAction").get("sseKmsKeyId").asText());
                }
            }
            if (action.has("deleteRegionAction")) {
                String name = action.path("deleteRegionAction").path("regionName").asText();
                if (!map.has(name)) {
                    throw validation("The replication set doesn't include " + name + ".");
                }
                long remaining = 0;
                for (JsonNode info : map) {
                    remaining += "DELETING".equals(info.path("status").asText()) ? 0 : 1;
                }
                if (remaining <= 1) {
                    throw validation("A replication set keeps at least one Region. Delete the replication set "
                            + "instead.");
                }
                ((ObjectNode) map.get(name)).put("status", "DELETING").put("statusUpdateDateTime", now());
            }
        }
        set.put("status", "UPDATING").put("lastModifiedTime", now()).put("lastModifiedBy", caller(partition));
        ((ObjectNode) set.get("_")).put("ready", now() + step());
        sets.put(setKey(AwsArnUtils.parse(set.path("arn").asText()).partition()), set);
        return mapper.createObjectNode();
    }

    private ObjectNode updateDeletionProtection(JsonNode in) {
        ObjectNode set = requireSet(required(in, "arn"));
        set.put("deletionProtected", in.path("deletionProtected").asBoolean()).put("lastModifiedTime", now());
        sets.put(setKey(AwsArnUtils.parse(set.path("arn").asText()).partition()), set);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteReplicationSet(JsonNode in) {
        ObjectNode set = requireSet(decode(required(in, "arn")));
        if (set.path("deletionProtected").asBoolean()) {
            throw validation("Deletion protection is enabled on the replication set. Turn it off before deleting "
                    + "the replication set.");
        }
        if (!"DELETING".equals(set.path("status").asText())) {
            set.put("status", "DELETING").put("lastModifiedTime", now());
            for (JsonNode info : set.get("regionMap")) {
                ((ObjectNode) info).put("status", "DELETING").put("statusUpdateDateTime", now());
            }
            ((ObjectNode) set.get("_")).put("gone", now() + step());
            sets.put(setKey(AwsArnUtils.parse(set.path("arn").asText()).partition()), set);
        }
        return mapper.createObjectNode();
    }

    /**
     * Removes the account's Incident Manager data in the partition: its replication set, response plans, incidents
     * and contacts.
     */
    private void offboard(String partition) {
        sets.delete(setKey(partition));
        for (ObjectNode plan : plans.scan(key -> true)) {
            if (partition.equals(AwsArnUtils.parse(plan.path("arn").asText()).partition())) {
                plans.delete(plan.path("arn").asText());
            }
        }
        for (ObjectNode record : records.scan(key -> true)) {
            if (partition.equals(AwsArnUtils.parse(record.path("arn").asText()).partition())) {
                records.delete(record.path("arn").asText());
            }
        }
        contacts.get().offboard(partition);
    }

    private void requireOnboarded(String region) {
        if (!onboarded(region)) {
            throw validation("Incident Manager isn't set up in " + region + ". Create a replication set that "
                    + "includes the Region first.");
        }
    }

    // ---------- response plans ----------

    private ObjectNode createResponsePlan(JsonNode in, String region) {
        requireOnboarded(region);
        String name = required(in, "name");
        String partition = AwsRegions.partitionFor(region);
        String arn = AwsArnUtils.Arn.global(partition, "ssm-incidents", plans.accountId(), "response-plan/" + name)
                .toString();
        if (plans.get(arn).isPresent()) {
            throw conflict("The response plan " + name + " already exists.");
        }
        ObjectNode plan = mapper.createObjectNode();
        plan.put("arn", arn).put("name", name);
        applyPlan(plan, in, region, true);
        plan.set("_tags", in.path("tags").isObject() ? in.get("tags").deepCopy() : mapper.createObjectNode());
        plan.putArray("_policies");
        plans.put(arn, plan);
        return mapper.createObjectNode().put("arn", arn);
    }

    private void applyPlan(ObjectNode plan, JsonNode in, String region, boolean create) {
        if (in.hasNonNull("displayName")) {
            plan.put("displayName", in.get("displayName").asText());
        }
        if (create) {
            JsonNode template = in.path("incidentTemplate");
            requireImpact(template.path("impact"));
            plan.set("incidentTemplate", template.deepCopy());
        } else {
            ObjectNode template = (ObjectNode) plan.get("incidentTemplate");
            if (in.has("incidentTemplateImpact")) {
                requireImpact(in.get("incidentTemplateImpact"));
                template.put("impact", in.get("incidentTemplateImpact").asInt());
            }
            for (String[] field : List.of(new String[]{"incidentTemplateTitle", "title"},
                    new String[]{"incidentTemplateSummary", "summary"},
                    new String[]{"incidentTemplateDedupeString", "dedupeString"})) {
                if (in.hasNonNull(field[0])) {
                    template.put(field[1], in.get(field[0]).asText());
                }
            }
            if (in.has("incidentTemplateNotificationTargets")) {
                template.set("notificationTargets", in.get("incidentTemplateNotificationTargets").deepCopy());
            }
            if (in.has("incidentTemplateTags")) {
                template.set("incidentTags", in.get("incidentTemplateTags").deepCopy());
            }
        }
        requireTopics(plan.path("incidentTemplate").path("notificationTargets"), region);
        for (String field : List.of("chatChannel", "engagements", "actions", "integrations")) {
            if (in.has(field)) {
                plan.set(field, in.get(field).deepCopy());
            }
        }
        for (JsonNode topic : plan.path("chatChannel").path("chatbotSns")) {
            requireTopic(topic.asText(), region);
        }
        for (JsonNode contact : plan.path("engagements")) {
            if (!contacts.get().contactExists(contact.asText())) {
                throw validation("The contact " + contact.asText() + " doesn't exist in SSM Contacts.");
            }
        }
        for (JsonNode action : plan.path("actions")) {
            String role = action.path("ssmAutomation").path("roleArn").asText();
            if (!AwsArnUtils.isArnFor(role, "iam") || !AwsArnUtils.parse(role).resource().startsWith("role/")) {
                throw validation("The automation roleArn " + role + " isn't an IAM role ARN.");
            }
        }
    }

    private void requireImpact(JsonNode impact) {
        if (!impact.canConvertToInt() || impact.asInt() < 1 || impact.asInt() > 5) {
            throw validation("The impact must be between 1 (critical) and 5 (no impact).");
        }
    }

    private void requireTopics(JsonNode targets, String region) {
        for (JsonNode target : targets) {
            requireTopic(target.path("snsTopicArn").asText(), region);
        }
    }

    private void requireTopic(String topicArn, String region) {
        String topicRegion = AwsArnUtils.isArnFor(topicArn, "sns") ? AwsArnUtils.parse(topicArn).region() : region;
        if (!sns.get().topicExists(topicArn, topicRegion)) {
            throw validation("The SNS topic " + topicArn + " doesn't exist.");
        }
    }

    private ObjectNode plan(String arn) {
        return plans.get(decode(arn)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The response plan " + arn + " doesn't exist."));
    }

    private ObjectNode getResponsePlan(JsonNode in) {
        return view(plan(required(in, "arn")));
    }

    private ObjectNode listResponsePlans(JsonNode in) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode plan : plans.scan(key -> true)) {
            ObjectNode summary = mapper.createObjectNode().put("arn", plan.path("arn").asText())
                    .put("name", plan.path("name").asText());
            if (plan.hasNonNull("displayName")) {
                summary.put("displayName", plan.get("displayName").asText());
            }
            items.add(summary);
        }
        items.sort(Comparator.comparing(item -> item.path("name").asText()));
        return page(items, in, "responsePlanSummaries");
    }

    private ObjectNode updateResponsePlan(JsonNode in, String region) {
        ObjectNode plan = plan(required(in, "arn"));
        applyPlan(plan, in, region, false);
        plans.put(plan.path("arn").asText(), plan);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteResponsePlan(JsonNode in) {
        plans.delete(decode(required(in, "arn")));
        return mapper.createObjectNode();
    }

    private ObjectNode putResourcePolicy(JsonNode in, String region) {
        ObjectNode plan = plan(required(in, "resourceArn"));
        String policy = required(in, "policy");
        try {
            if (!mapper.readTree(policy).has("Statement")) {
                throw validation("The policy needs a Statement.");
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("The policy isn't valid JSON.");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        ((ArrayNode) plan.get("_policies")).addObject().put("policyId", id).put("policyDocument", policy)
                .put("ramResourceShareRegion", region);
        plans.put(plan.path("arn").asText(), plan);
        return mapper.createObjectNode().put("policyId", id);
    }

    private ObjectNode getResourcePolicies(JsonNode in) {
        ObjectNode plan = plan(required(in, "resourceArn"));
        List<ObjectNode> items = new ArrayList<>();
        plan.get("_policies").forEach(policy -> items.add((ObjectNode) policy.deepCopy()));
        return page(items, in, "resourcePolicies");
    }

    private ObjectNode deleteResourcePolicy(JsonNode in) {
        ObjectNode plan = plan(required(in, "resourceArn"));
        String id = required(in, "policyId");
        ArrayNode policies = (ArrayNode) plan.get("_policies");
        boolean removed = false;
        for (int i = policies.size() - 1; i >= 0; i--) {
            if (id.equals(policies.get(i).path("policyId").asText())) {
                policies.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            throw notFound("The resource policy " + id + " doesn't exist.");
        }
        plans.put(plan.path("arn").asText(), plan);
        return mapper.createObjectNode();
    }

    // ---------- incidents ----------

    private ObjectNode startIncident(JsonNode in, String region) {
        ObjectNode plan = plan(required(in, "responsePlanArn"));
        requireOnboarded(region);
        String token = in.path("clientToken").asText(null);
        JsonNode template = plan.path("incidentTemplate");
        String dedupe = template.path("dedupeString").asText("");
        for (ObjectNode existing : records.scan(key -> true)) {
            if (token != null && token.equals(existing.path("_").path("clientToken").asText())) {
                return mapper.createObjectNode().put("incidentRecordArn", existing.path("arn").asText());
            }
            if (!dedupe.isEmpty() && "OPEN".equals(existing.path("status").asText())
                    && dedupe.equals(existing.path("dedupeString").asText())) {
                addEvent(existing, "SSM Incident Trigger", mapper.createObjectNode()
                        .put("deduplicated", true).put("source", source(in)).toString(), null);
                records.put(existing.path("arn").asText(), existing);
                return mapper.createObjectNode().put("incidentRecordArn", existing.path("arn").asText());
            }
        }
        if (in.has("impact")) {
            requireImpact(in.get("impact"));
        }
        String partition = AwsArnUtils.parse(plan.path("arn").asText()).partition();
        String arn = AwsArnUtils.Arn.global(partition, "ssm-incidents", records.accountId(), "incident-record/"
                + plan.path("name").asText() + "/" + UUID.randomUUID()).toString();
        double now = now();
        ObjectNode record = mapper.createObjectNode();
        record.put("arn", arn).put("title", in.path("title").asText(template.path("title").asText()))
                .put("impact", in.has("impact") ? in.get("impact").asInt() : template.path("impact").asInt())
                .put("status", "OPEN").put("dedupeString", dedupe).put("creationTime", now)
                .put("lastModifiedTime", now).put("lastModifiedBy", caller(partition));
        if (template.hasNonNull("summary")) {
            record.put("summary", template.get("summary").asText());
        }
        ObjectNode source = record.putObject("incidentRecordSource").put("createdBy", caller(partition))
                .put("source", source(in));
        if (in.path("triggerDetails").hasNonNull("triggerArn")) {
            source.put("resourceArn", in.path("triggerDetails").get("triggerArn").asText());
        }
        record.set("notificationTargets", template.path("notificationTargets").isArray()
                ? template.get("notificationTargets").deepCopy() : mapper.createArrayNode());
        record.set("chatChannel", plan.has("chatChannel") ? plan.get("chatChannel").deepCopy()
                : mapper.createObjectNode().putObject("empty"));
        record.putArray("automationExecutions");
        ObjectNode meta = record.putObject("_");
        if (token != null) {
            meta.put("clientToken", token);
        }
        meta.put("responsePlanArn", plan.path("arn").asText());
        meta.set("tags", template.path("incidentTags").isObject() ? template.get("incidentTags").deepCopy()
                : mapper.createObjectNode());
        meta.putArray("events");
        ArrayNode related = meta.putArray("related");
        for (JsonNode item : in.path("relatedItems")) {
            related.add(relatedItem(item));
        }
        ObjectNode trigger = mapper.createObjectNode().put("title", record.path("title").asText())
                .put("source", source(in));
        if (in.path("triggerDetails").hasNonNull("rawData")) {
            trigger.put("rawData", in.path("triggerDetails").get("rawData").asText());
        }
        addEvent(record, "SSM Incident Trigger", trigger.toString(), null);
        records.put(arn, record);

        notifyTargets(record, "Incident started: " + record.path("title").asText(), region);
        for (JsonNode contact : plan.path("engagements")) {
            String engagement = contacts.get().startEngagement(contact.asText(), "Incident Manager",
                    record.path("title").asText(), record.path("summary").asText(record.path("title").asText()),
                    arn, region);
            ObjectNode current = records.get(arn).orElseThrow().deepCopy();
            addEvent(current, "SSM Contacts Page for Incident", mapper.createObjectNode()
                    .put("contactArn", contact.asText()).put("engagementArn", engagement).toString(), null);
            records.put(arn, current);
        }
        return mapper.createObjectNode().put("incidentRecordArn", arn);
    }

    private static String source(JsonNode in) {
        return in.path("triggerDetails").path("source").asText("aws.ssm-incidents.custom");
    }

    private void notifyTargets(ObjectNode record, String subject, String region) {
        String message = mapper.createObjectNode().put("incidentRecordArn", record.path("arn").asText())
                .put("title", record.path("title").asText()).put("impact", record.path("impact").asInt())
                .put("status", record.path("status").asText()).toString();
        for (JsonNode target : record.path("notificationTargets")) {
            String topic = target.path("snsTopicArn").asText();
            try {
                sns.get().publish(topic, null, message, subject.length() > 100 ? subject.substring(0, 100) : subject,
                        AwsArnUtils.isArnFor(topic, "sns") ? AwsArnUtils.parse(topic).region() : region);
            } catch (AwsException e) {
                addEvent(record, "SSM Incident Record Update", mapper.createObjectNode()
                        .put("notificationError", topic + ": " + e.getMessage()).toString(), null);
                records.put(record.path("arn").asText(), record);
            }
        }
    }

    private ObjectNode record(String arn) {
        return records.get(decode(arn)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("The incident record " + arn + " doesn't exist."));
    }

    private ObjectNode listIncidentRecords(JsonNode in) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : records.scan(key -> true)) {
            if (!matches(record, in.path("filters"))) {
                continue;
            }
            ObjectNode summary = mapper.createObjectNode();
            for (String field : List.of("arn", "creationTime", "impact", "incidentRecordSource", "resolvedTime",
                    "status", "title")) {
                if (record.hasNonNull(field)) {
                    summary.set(field, record.get(field).deepCopy());
                }
            }
            items.add(summary);
        }
        items.sort(Comparator.comparingDouble((ObjectNode item) -> item.path("creationTime").asDouble()).reversed());
        return page(items, in, "incidentRecordSummaries");
    }

    private boolean matches(ObjectNode record, JsonNode filters) {
        for (JsonNode filter : filters) {
            String key = filter.path("key").asText();
            JsonNode condition = filter.path("condition");
            JsonNode value = switch (key) {
                case "creationTime" -> record.get("creationTime");
                case "impact" -> record.get("impact");
                case "status" -> record.get("status");
                case "createdBy" -> record.path("incidentRecordSource").get("createdBy");
                default -> throw validation("Filter key " + key + " isn't supported. Use creationTime, impact, "
                        + "status or createdBy.");
            };
            if (!condition(value, condition)) {
                return false;
            }
        }
        return true;
    }

    private static boolean condition(JsonNode value, JsonNode condition) {
        if (value == null) {
            return false;
        }
        if (condition.has("after") && value.asDouble() <= condition.get("after").asDouble()) {
            return false;
        }
        if (condition.has("before") && value.asDouble() >= condition.get("before").asDouble()) {
            return false;
        }
        if (condition.has("equals")) {
            boolean any = false;
            for (JsonNode candidate : condition.path("equals").path("stringValues")) {
                any |= candidate.asText().equals(value.asText());
            }
            for (JsonNode candidate : condition.path("equals").path("integerValues")) {
                any |= candidate.asInt() == value.asInt();
            }
            return any;
        }
        return true;
    }

    private ObjectNode updateIncidentRecord(JsonNode in, String region) {
        ObjectNode record = record(required(in, "arn"));
        String before = record.path("status").asText();
        List<String> changed = new ArrayList<>();
        if (in.has("impact")) {
            requireImpact(in.get("impact"));
            record.put("impact", in.get("impact").asInt());
            changed.add("impact");
        }
        for (String field : List.of("title", "summary")) {
            if (in.hasNonNull(field)) {
                record.put(field, in.get(field).asText());
                changed.add(field);
            }
        }
        if (in.has("notificationTargets")) {
            requireTopics(in.get("notificationTargets"), region);
            record.set("notificationTargets", in.get("notificationTargets").deepCopy());
            changed.add("notificationTargets");
        }
        if (in.has("chatChannel")) {
            record.set("chatChannel", in.get("chatChannel").deepCopy());
            changed.add("chatChannel");
        }
        if (in.hasNonNull("status")) {
            String status = in.get("status").asText();
            record.put("status", status);
            if ("RESOLVED".equals(status) && !"RESOLVED".equals(before)) {
                record.put("resolvedTime", now());
            } else if ("OPEN".equals(status)) {
                record.remove("resolvedTime");
            }
            changed.add("status");
        }
        record.put("lastModifiedTime", now());
        addEvent(record, "SSM Incident Record Update", mapper.createObjectNode()
                .set("modifiedAttributes", mapper.valueToTree(changed)).toString(), null);
        records.put(record.path("arn").asText(), record);
        if (!before.equals(record.path("status").asText())) {
            notifyTargets(record, "Incident " + record.path("status").asText().toLowerCase() + ": "
                    + record.path("title").asText(), region);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode deleteIncidentRecord(JsonNode in) {
        records.delete(decode(required(in, "arn")));
        return mapper.createObjectNode();
    }

    // ---------- timeline ----------

    private ObjectNode addEvent(ObjectNode record, String type, String data, JsonNode in) {
        double now = now();
        ObjectNode event = mapper.createObjectNode().put("eventId", UUID.randomUUID().toString())
                .put("incidentRecordArn", record.path("arn").asText()).put("eventType", type).put("eventData", data)
                .put("eventTime", in != null && in.has("eventTime") ? in.get("eventTime").asDouble() : now)
                .put("eventUpdatedTime", now);
        event.set("eventReferences", in != null && in.has("eventReferences") ? in.get("eventReferences").deepCopy()
                : mapper.createArrayNode());
        ((ArrayNode) record.withArray("/_/events")).add(event);
        return event;
    }

    private ObjectNode createTimelineEvent(JsonNode in) {
        ObjectNode record = record(required(in, "incidentRecordArn"));
        String type = required(in, "eventType");
        if (!USER_EVENT_TYPES.contains(type)) {
            throw validation("eventType must be Custom Event or Note.");
        }
        String token = in.path("clientToken").asText(null);
        for (JsonNode event : record.path("_").path("events")) {
            if (token != null && token.equals(event.path("_clientToken").asText())) {
                return mapper.createObjectNode().put("eventId", event.path("eventId").asText())
                        .put("incidentRecordArn", record.path("arn").asText());
            }
        }
        requireReferences(record, in.path("eventReferences"));
        ObjectNode event = addEvent(record, type, required(in, "eventData"), in);
        if (token != null) {
            event.put("_clientToken", token);
        }
        records.put(record.path("arn").asText(), record);
        return mapper.createObjectNode().put("eventId", event.path("eventId").asText())
                .put("incidentRecordArn", record.path("arn").asText());
    }

    /** An event can reference only the incident's own related items. */
    private void requireReferences(ObjectNode record, JsonNode references) {
        for (JsonNode reference : references) {
            if (reference.hasNonNull("relatedItemId")) {
                String id = reference.get("relatedItemId").asText();
                boolean found = false;
                for (JsonNode item : record.path("_").path("related")) {
                    found |= id.equals(item.path("generatedId").asText());
                }
                if (!found) {
                    throw validation("The related item " + id + " isn't on the incident.");
                }
            }
        }
    }

    private ObjectNode event(ObjectNode record, String eventId) {
        for (JsonNode event : record.path("_").path("events")) {
            if (eventId.equals(event.path("eventId").asText())) {
                return (ObjectNode) event;
            }
        }
        throw notFound("The timeline event " + eventId + " doesn't exist.");
    }

    private ObjectNode getTimelineEvent(JsonNode in) {
        ObjectNode record = record(required(in, "incidentRecordArn"));
        ObjectNode event = event(record, required(in, "eventId")).deepCopy();
        event.remove("_clientToken");
        return wrap("event", event);
    }

    private ObjectNode updateTimelineEvent(JsonNode in) {
        ObjectNode record = record(required(in, "incidentRecordArn"));
        ObjectNode event = event(record, required(in, "eventId"));
        if (in.hasNonNull("eventType")) {
            if (!USER_EVENT_TYPES.contains(in.get("eventType").asText())) {
                throw validation("eventType must be Custom Event or Note.");
            }
            event.put("eventType", in.get("eventType").asText());
        }
        if (in.hasNonNull("eventData")) {
            event.put("eventData", in.get("eventData").asText());
        }
        if (in.has("eventTime")) {
            event.put("eventTime", in.get("eventTime").asDouble());
        }
        if (in.has("eventReferences")) {
            requireReferences(record, in.get("eventReferences"));
            event.set("eventReferences", in.get("eventReferences").deepCopy());
        }
        event.put("eventUpdatedTime", now());
        records.put(record.path("arn").asText(), record);
        return mapper.createObjectNode();
    }

    private ObjectNode deleteTimelineEvent(JsonNode in) {
        records.get(decode(required(in, "incidentRecordArn"))).map(ObjectNode::deepCopy).ifPresent(record -> {
            ArrayNode events = (ArrayNode) record.withArray("/_/events");
            String id = required(in, "eventId");
            for (int i = events.size() - 1; i >= 0; i--) {
                if (id.equals(events.get(i).path("eventId").asText())) {
                    events.remove(i);
                }
            }
            records.put(record.path("arn").asText(), record);
        });
        return mapper.createObjectNode();
    }

    private ObjectNode listTimelineEvents(JsonNode in) {
        String arn = decode(required(in, "incidentRecordArn"));
        List<ObjectNode> items = new ArrayList<>();
        records.get(arn).ifPresent(record -> {
            for (JsonNode event : record.path("_").path("events")) {
                if (eventMatches(event, in.path("filters"))) {
                    ObjectNode summary = ((ObjectNode) event).deepCopy();
                    summary.remove("eventData");
                    summary.remove("_clientToken");
                    items.add(summary);
                }
            }
        });
        Comparator<ObjectNode> order = Comparator.comparingDouble(event -> event.path("eventTime").asDouble());
        items.sort("DESCENDING".equals(in.path("sortOrder").asText()) ? order.reversed() : order);
        return page(items, in, "eventSummaries");
    }

    private static boolean eventMatches(JsonNode event, JsonNode filters) {
        for (JsonNode filter : filters) {
            String key = filter.path("key").asText();
            JsonNode value = switch (key) {
                case "eventTime" -> event.get("eventTime");
                case "eventType" -> event.get("eventType");
                default -> throw validation("Filter key " + key + " isn't supported. Use eventTime or eventType.");
            };
            if (!condition(value, filter.path("condition"))) {
                return false;
            }
        }
        return true;
    }

    // ---------- related items and findings ----------

    private ObjectNode relatedItem(JsonNode item) {
        JsonNode identifier = item.path("identifier");
        String type = identifier.path("type").asText();
        JsonNode value = identifier.path("value");
        boolean valid = switch (type) {
            case "METRIC" -> value.hasNonNull("metricDefinition");
            case "ATTACHMENT", "OTHER", "ANALYSIS" -> value.hasNonNull("url") || value.hasNonNull("arn");
            case "INCIDENT", "PARENT", "AUTOMATION", "INVOLVED_RESOURCE", "TASK" -> value.hasNonNull("arn")
                    || value.hasNonNull("pagerDutyIncidentDetail") || value.hasNonNull("url");
            default -> false;
        };
        if (!valid) {
            throw validation("The related item of type " + type + " needs a value its type takes (an arn, url or "
                    + "metricDefinition).");
        }
        ObjectNode stored = item.deepCopy();
        if (!stored.hasNonNull("generatedId")) {
            stored.put("generatedId", UUID.randomUUID().toString());
        }
        return stored;
    }

    private ObjectNode updateRelatedItems(JsonNode in) {
        ObjectNode record = record(required(in, "incidentRecordArn"));
        ArrayNode related = (ArrayNode) record.withArray("/_/related");
        JsonNode update = in.path("relatedItemsUpdate");
        if (update.has("itemToAdd")) {
            ObjectNode item = relatedItem(update.get("itemToAdd"));
            for (JsonNode existing : related) {
                if (existing.path("identifier").equals(item.path("identifier"))) {
                    throw conflict("The incident already has this related item.");
                }
            }
            related.add(item);
        }
        if (update.has("itemToRemove")) {
            boolean removed = false;
            for (int i = related.size() - 1; i >= 0; i--) {
                if (related.get(i).path("identifier").equals(update.get("itemToRemove"))) {
                    related.remove(i);
                    removed = true;
                }
            }
            if (!removed) {
                throw notFound("The incident has no such related item.");
            }
        }
        records.put(record.path("arn").asText(), record);
        return mapper.createObjectNode();
    }

    private ObjectNode listRelatedItems(JsonNode in) {
        List<ObjectNode> items = new ArrayList<>();
        records.get(decode(required(in, "incidentRecordArn"))).ifPresent(record -> record.path("_").path("related")
                .forEach(item -> items.add((ObjectNode) item.deepCopy())));
        return page(items, in, "relatedItems");
    }

    /**
     * Findings are deployments AWS correlates with an incident. Floci doesn't correlate any, so an incident has
     * none; an unknown incident is still an error.
     */
    private ObjectNode listIncidentFindings(JsonNode in) {
        record(required(in, "incidentRecordArn"));
        return page(List.of(), in, "findings");
    }

    private ObjectNode batchGetIncidentFindings(JsonNode in) {
        record(required(in, "incidentRecordArn"));
        ObjectNode out = mapper.createObjectNode();
        out.putArray("findings");
        ArrayNode errors = out.putArray("errors");
        for (JsonNode id : in.path("findingIds")) {
            errors.addObject().put("findingId", id.asText()).put("code", "ResourceNotFoundException")
                    .put("message", "The finding " + id.asText() + " doesn't exist.");
        }
        return out;
    }

    // ---------- tags ----------

    private record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode record,
                          String field) {
    }

    private Tagged tagged(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "ssm-incidents")) {
            throw validation("The resource ARN " + arn + " isn't an Incident Manager ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        if (resource.startsWith("replication-set/")) {
            return new Tagged(sets, setKey(AwsArnUtils.parse(arn).partition()), requireSet(arn), "_tags");
        }
        if (resource.startsWith("response-plan/")) {
            return new Tagged(plans, arn, plan(arn), "_tags");
        }
        if (resource.startsWith("incident-record/")) {
            ObjectNode record = record(arn);
            return new Tagged(records, arn, record, "_");
        }
        throw notFound("The resource " + arn + " doesn't exist.");
    }

    private ObjectNode tagsOf(Tagged target) {
        return "_".equals(target.field()) ? target.record().withObject("/_/tags")
                : target.record().withObject("/" + target.field());
    }

    private ObjectNode tag(JsonNode in, boolean add) {
        Tagged target = tagged(decode(required(in, "resourceArn")));
        ObjectNode tags = tagsOf(target);
        if (add) {
            tags.setAll((ObjectNode) in.path("tags"));
            if (tags.size() > 50) {
                throw validation("A resource can have at most 50 tags.");
            }
        } else {
            in.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        }
        target.store().put(target.key(), target.record());
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in) {
        Tagged target = tagged(decode(required(in, "resourceArn")));
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", tagsOf(target).deepCopy());
        return out;
    }

    // ---------- shared ----------

    private ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        view.remove("_tags");
        view.remove("_policies");
        return view;
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private String caller(String partition) {
        return AwsArnUtils.Arn.global(partition, "iam", sets.accountId(), "root").toString();
    }

    /** The transition time, in seconds. */
    private double step() {
        return config.services().ssmIncidents().transitionMillis() / 1000.0;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static String required(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
