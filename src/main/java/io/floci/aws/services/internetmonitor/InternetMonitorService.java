package io.floci.aws.services.internetmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Internet Monitor's control plane: monitors, their status transitions and tags, and reads of the health events and
 * internet events {@link InternetMonitorMeasurements} raises.
 */
@ApplicationScoped
public class InternetMonitorService {

    private static final Logger LOG = Logger.getLogger(InternetMonitorService.class);

    /** The resource types a monitor can watch: VPCs, Network Load Balancers, distributions, WorkSpaces directories. */
    private static final Pattern VPC = Pattern.compile("vpc/vpc-[A-Za-z0-9-]+");
    private static final Pattern NLB = Pattern.compile("loadbalancer/net/[^/]+/[^/]+");
    private static final Pattern DISTRIBUTION = Pattern.compile("distribution/[A-Z0-9]+");
    private static final Pattern DIRECTORY = Pattern.compile("directory/d-[A-Za-z0-9]+");
    private static final int MAX_TAGS = 50;

    private final InternetMonitorStore store;
    private final InternetMonitorMeasurements measurements;
    private final Instance<S3Service> s3;

    @Inject
    public InternetMonitorService(InternetMonitorStore store, InternetMonitorMeasurements measurements,
                                  Instance<S3Service> s3) {
        this.store = store;
        this.measurements = measurements;
        this.s3 = s3;
    }

    public void restorePersistedRuntime() {
        measurements.start();
    }

    // ---------------------------------------------------------------- monitors

    public ObjectNode createMonitor(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("MonitorName").asText();
        String key = InternetMonitorStore.monitorKey(region, name);
        String token = text(in, "ClientToken");
        ObjectNode existing = store.get(account, key).orElse(null);
        if (existing != null) {
            if (token != null && token.equals(existing.path("_").path("clientToken").asText(null))) {
                ObjectNode view = view(existing);
                return store.mapper().createObjectNode().put("Arn", view.path("MonitorArn").asText())
                        .put("Status", view.path("Status").asText());
            }
            throw InternetMonitorStore.conflict("A monitor named " + name + " already exists.");
        }
        if (!in.hasNonNull("MaxCityNetworksToMonitor") && !in.hasNonNull("TrafficPercentageToMonitor")) {
            throw InternetMonitorStore.validation(
                    "You must specify MaxCityNetworksToMonitor or TrafficPercentageToMonitor.");
        }
        List<String> resources = resources(in.path("Resources"), account, region);
        validateDelivery(in.path("InternetMeasurementsLogDelivery"), region);
        Map<String, String> tags = tags(in.path("Tags"));
        Instant now = Instant.now();
        ObjectNode record = store.mapper().createObjectNode();
        record.put("MonitorName", name)
                .put("MonitorArn", InternetMonitorStore.monitorArn(account, region, name));
        ArrayNode list = record.putArray("Resources");
        resources.forEach(list::add);
        record.put("Status", "PENDING").put("CreatedAt", now.toString()).put("ModifiedAt", now.toString())
                .put("ProcessingStatus", "COLLECTING_DATA")
                .put("ProcessingStatusInfo", "Internet Monitor is collecting data for the monitor.");
        copy(in, record, "MaxCityNetworksToMonitor", "TrafficPercentageToMonitor", "InternetMeasurementsLogDelivery",
                "HealthEventsConfig");
        ObjectNode tagNode = record.putObject("Tags");
        tags.forEach(tagNode::put);
        ObjectNode meta = record.putObject("_");
        meta.put("target", "ACTIVE").put("pendingUntil", now.plusMillis(store.config().pendingMillis()).toString())
                .put("region", region);
        if (token != null) {
            meta.put("clientToken", token);
        }
        store.put(account, key, record);
        return store.mapper().createObjectNode().put("Arn", record.path("MonitorArn").asText())
                .put("Status", "PENDING");
    }

    public ObjectNode getMonitor(String name, String linkedAccount, String region) {
        String account = account(linkedAccount);
        ObjectNode view = view(monitor(account, region, name));
        view.remove("_");
        return view;
    }

    public ObjectNode updateMonitor(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("MonitorName").asText();
        ObjectNode record = store.get(account, InternetMonitorStore.monitorKey(region, name))
                .orElseThrow(() -> InternetMonitorStore.notFound("The monitor " + name + " does not exist."));
        record = view(record);
        String status = record.path("Status").asText();
        Set<String> resources = new LinkedHashSet<>();
        record.path("Resources").forEach(arn -> resources.add(arn.asText()));
        List<String> added = resources(in.path("ResourcesToAdd"), account, region);
        List<String> removed = new ArrayList<>();
        in.path("ResourcesToRemove").forEach(arn -> removed.add(arn.asText()));
        for (String arn : removed) {
            if (!resources.contains(arn)) {
                throw InternetMonitorStore.validation("The resource " + arn + " is not in the monitor.");
            }
        }
        resources.removeAll(removed);
        resources.addAll(added);
        validateDelivery(in.path("InternetMeasurementsLogDelivery"), region);
        ArrayNode list = record.putArray("Resources");
        resources.forEach(list::add);
        copy(in, record, "MaxCityNetworksToMonitor", "TrafficPercentageToMonitor", "InternetMeasurementsLogDelivery",
                "HealthEventsConfig");
        String target = in.hasNonNull("Status") ? in.path("Status").asText()
                : "PENDING".equals(status) ? record.path("_").path("target").asText("ACTIVE") : status;
        if ("PENDING".equals(target) || "ERROR".equals(target)) {
            throw InternetMonitorStore.validation("A monitor's status can be set to ACTIVE or INACTIVE.");
        }
        Instant now = Instant.now();
        record.put("Status", "PENDING").put("ModifiedAt", now.toString());
        ((ObjectNode) record.path("_")).put("target", target)
                .put("pendingUntil", now.plusMillis(store.config().pendingMillis()).toString());
        String token = text(in, "ClientToken");
        if (token != null) {
            ((ObjectNode) record.path("_")).put("updateToken", token);
        }
        store.put(account, InternetMonitorStore.monitorKey(region, name), record);
        return store.mapper().createObjectNode().put("MonitorArn", record.path("MonitorArn").asText())
                .put("Status", "PENDING");
    }

    public ObjectNode deleteMonitor(String name, String region) {
        String account = store.account();
        ObjectNode record = view(monitor(account, region, name));
        if (!"INACTIVE".equals(record.path("Status").asText())) {
            throw InternetMonitorStore.validation("Monitor must be in inactive state before deletion.");
        }
        store.delete(account, InternetMonitorStore.monitorKey(region, name));
        for (String prefix : List.of(region + "/health-event/" + name + "/", region + "/window/" + name + "/")) {
            store.everywhere(key -> key.startsWith(prefix)).stream()
                    .filter(entry -> entry.accountId().equals(account))
                    .forEach(entry -> store.delete(account, entry.key()));
        }
        measurements.forget(account, region, name);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listMonitors(String token, Integer maxResults, String status, String region) {
        String account = store.account();
        List<JsonNode> monitors = new ArrayList<>();
        for (ObjectNode record : store.list(account, region + "/monitor/")) {
            ObjectNode view = view(record);
            if (status != null && !status.equalsIgnoreCase(view.path("Status").asText())) {
                continue;
            }
            monitors.add(store.mapper().createObjectNode().put("MonitorName", view.path("MonitorName").asText())
                    .put("MonitorArn", view.path("MonitorArn").asText()).put("Status", view.path("Status").asText())
                    .put("ProcessingStatus", view.path("ProcessingStatus").asText()));
        }
        ObjectNode out = store.mapper().createObjectNode();
        InternetMonitorStore.page(monitors, token, maxResults == null ? 25 : maxResults, out, "Monitors");
        return out;
    }

    /** A monitor with its pending transition settled, as stored (hidden members included). */
    ObjectNode view(ObjectNode record) {
        if ("PENDING".equals(record.path("Status").asText())) {
            Instant until = Instant.parse(record.path("_").path("pendingUntil").asText(Instant.EPOCH.toString()));
            if (!Instant.now().isBefore(until)) {
                String target = record.path("_").path("target").asText("ACTIVE");
                record.put("Status", target);
                if ("INACTIVE".equals(target)) {
                    record.put("ProcessingStatus", "INACTIVE")
                            .put("ProcessingStatusInfo", "The monitor is inactive.");
                } else if ("INACTIVE".equals(record.path("ProcessingStatus").asText())) {
                    record.put("ProcessingStatus", "COLLECTING_DATA")
                            .put("ProcessingStatusInfo", "Internet Monitor is collecting data for the monitor.");
                }
                String account = AwsArnUtils.parse(record.path("MonitorArn").asText()).accountId();
                String region = AwsArnUtils.parse(record.path("MonitorArn").asText()).region();
                store.put(account, InternetMonitorStore.monitorKey(region, record.path("MonitorName").asText()),
                        record);
            }
        }
        return record.deepCopy();
    }

    ObjectNode monitor(String account, String region, String name) {
        return store.get(account, InternetMonitorStore.monitorKey(region, name))
                .orElseThrow(() -> InternetMonitorStore.notFound("The monitor " + name + " does not exist."));
    }

    // ---------------------------------------------------------------- health events

    public ObjectNode getHealthEvent(String monitorName, String eventId, String linkedAccount, String region) {
        String account = account(linkedAccount);
        monitor(account, region, monitorName);
        ObjectNode event = store.get(account, InternetMonitorStore.eventKey(region, monitorName, eventId))
                .orElseThrow(() -> InternetMonitorStore.notFound("The health event " + eventId + " does not exist."));
        ObjectNode view = event.deepCopy();
        view.remove("_");
        return view;
    }

    public ObjectNode listHealthEvents(String monitorName, Instant start, Instant end, String token,
                                       Integer maxResults, String eventStatus, String linkedAccount, String region) {
        String account = account(linkedAccount);
        monitor(account, region, monitorName);
        List<JsonNode> events = new ArrayList<>();
        for (ObjectNode event : store.list(account, region + "/health-event/" + monitorName + "/")) {
            Instant started = Instant.parse(event.path("StartedAt").asText());
            if ((start != null && started.isBefore(start)) || (end != null && started.isAfter(end))
                    || (eventStatus != null && !eventStatus.equals(event.path("Status").asText()))) {
                continue;
            }
            ObjectNode view = event.deepCopy();
            view.remove("_");
            events.add(view);
        }
        events.sort((a, b) -> b.path("StartedAt").asText().compareTo(a.path("StartedAt").asText()));
        ObjectNode out = store.mapper().createObjectNode();
        InternetMonitorStore.page(events, token, maxResults == null ? 25 : maxResults, out, "HealthEvents");
        return out;
    }

    // ---------------------------------------------------------------- internet events

    public ObjectNode getInternetEvent(String eventId, String region) {
        return measurements.internetEvent(eventId, region)
                .orElseThrow(() -> InternetMonitorStore.notFound("The internet event " + eventId
                        + " does not exist."));
    }

    public ObjectNode listInternetEvents(Instant start, Instant end, String status, String type, String token,
                                         Integer maxResults, String region) {
        if (status != null && !Set.of("ACTIVE", "RESOLVED").contains(status)) {
            throw InternetMonitorStore.validation("EventStatus must be ACTIVE or RESOLVED.");
        }
        if (type != null && !Set.of("AVAILABILITY", "PERFORMANCE").contains(type)) {
            throw InternetMonitorStore.validation("EventType must be AVAILABILITY or PERFORMANCE.");
        }
        List<JsonNode> events = new ArrayList<>();
        for (ObjectNode event : measurements.internetEvents(region)) {
            Instant started = Instant.parse(event.path("StartedAt").asText());
            if ((start != null && started.isBefore(start)) || (end != null && started.isAfter(end))
                    || (status != null && !status.equals(event.path("EventStatus").asText()))
                    || (type != null && !type.equals(event.path("EventType").asText()))) {
                continue;
            }
            events.add(event);
        }
        ObjectNode out = store.mapper().createObjectNode();
        InternetMonitorStore.page(events, token, maxResults == null ? 100 : maxResults, out, "InternetEvents");
        return out;
    }

    // ---------------------------------------------------------------- tags

    public ObjectNode listTags(String arn) {
        ObjectNode record = tagged(arn);
        ObjectNode out = store.mapper().createObjectNode();
        out.set("Tags", record.path("Tags").deepCopy());
        return out;
    }

    public ObjectNode tagResource(String arn, JsonNode tags) {
        ObjectNode record = tagged(arn);
        ObjectNode current = record.has("Tags") ? (ObjectNode) record.get("Tags") : record.putObject("Tags");
        Map<String, String> added;
        try {
            added = tags(tags);
        } catch (AwsException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
        added.forEach(current::put);
        if (current.size() > MAX_TAGS) {
            throw new AwsException("BadRequestException", "A monitor can have at most " + MAX_TAGS + " tags.", 400);
        }
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode untagResource(String arn, List<String> keys) {
        ObjectNode record = tagged(arn);
        if (record.path("Tags") instanceof ObjectNode current) {
            keys.forEach(current::remove);
        }
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    private ObjectNode tagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "The resource ARN " + arn + " is not valid.", 400);
        }
        if (!InternetMonitorStore.SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("monitor/")
                || !Objects.equals(parsed.accountId(), store.account())) {
            throw new AwsException("NotFoundException", "The resource " + arn + " does not exist.", 404);
        }
        String name = parsed.resource().substring("monitor/".length());
        return store.get(parsed.accountId(), InternetMonitorStore.monitorKey(parsed.region(), name))
                .orElseThrow(() -> new AwsException("NotFoundException", "The resource " + arn + " does not exist.",
                        404));
    }

    private void save(String arn, ObjectNode record) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        store.put(parsed.accountId(), InternetMonitorStore.monitorKey(parsed.region(),
                parsed.resource().substring("monitor/".length())), record);
    }

    // ---------------------------------------------------------------- validation

    private String account(String linkedAccount) {
        String account = store.account();
        if (linkedAccount != null && !linkedAccount.isBlank() && !linkedAccount.equals(account)) {
            throw new AwsException("AccessDeniedException", "The account " + linkedAccount
                    + " is not linked to this monitoring account.", 403);
        }
        return account;
    }

    private List<String> resources(JsonNode arns, String account, String region) {
        List<String> resources = new ArrayList<>();
        for (JsonNode node : arns) {
            String arn = node.asText();
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(arn);
            } catch (IllegalArgumentException e) {
                throw InternetMonitorStore.validation("The resource " + arn + " is not a valid ARN.");
            }
            boolean supported = switch (parsed.service()) {
                case "ec2" -> VPC.matcher(parsed.resource()).matches();
                case "elasticloadbalancing" -> NLB.matcher(parsed.resource()).matches();
                case "cloudfront" -> DISTRIBUTION.matcher(parsed.resource()).matches();
                case "workspaces" -> DIRECTORY.matcher(parsed.resource()).matches();
                default -> false;
            };
            if (!supported) {
                throw InternetMonitorStore.validation("The resource " + arn + " is not a VPC, Network Load "
                        + "Balancer, CloudFront distribution or WorkSpaces directory.");
            }
            if (!account.equals(parsed.accountId())) {
                throw InternetMonitorStore.validation("The resource " + arn + " is not in account " + account + ".");
            }
            if (!"cloudfront".equals(parsed.service()) && !region.equals(parsed.region())) {
                throw InternetMonitorStore.validation("The resource " + arn + " is not in " + region + ".");
            }
            if (!resources.contains(arn)) {
                resources.add(arn);
            }
        }
        return resources;
    }

    private void validateDelivery(JsonNode delivery, String region) {
        JsonNode s3Config = delivery.path("S3Config");
        if (s3Config.isMissingNode() || "DISABLED".equals(s3Config.path("LogDeliveryStatus").asText())) {
            return;
        }
        String bucket = s3Config.path("BucketName").asText(null);
        if (bucket == null || bucket.isBlank()) {
            throw InternetMonitorStore.validation("S3Config.BucketName is required to deliver measurements to S3.");
        }
        boolean exists;
        try {
            exists = RequestScopes.callAs(store.account(), region, () -> s3.get().bucketExists(bucket));
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not check the bucket {0}", bucket);
            exists = false;
        }
        if (!exists) {
            throw InternetMonitorStore.validation("The S3 bucket " + bucket + " does not exist.");
        }
    }

    private Map<String, String> tags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = tags.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> tag = fields.next();
            if (tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue().asText().length() > 256) {
                throw InternetMonitorStore.validation("The tag " + tag.getKey() + " is not valid.");
            }
            if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw InternetMonitorStore.validation("Tag keys can't start with aws:.");
            }
            result.put(tag.getKey(), tag.getValue().asText());
        }
        if (result.size() > MAX_TAGS) {
            throw InternetMonitorStore.validation("A monitor can have at most " + MAX_TAGS + " tags.");
        }
        return result;
    }

    private static void copy(JsonNode from, ObjectNode to, String... names) {
        for (String name : names) {
            if (from.hasNonNull(name)) {
                to.set(name, from.get(name).deepCopy());
            }
        }
    }

    private static String text(JsonNode in, String name) {
        return in.hasNonNull(name) ? in.get(name).asText() : null;
    }
}
