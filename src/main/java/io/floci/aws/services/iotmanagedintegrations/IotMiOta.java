package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.OTA_CONFIGURATION;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.OTA_TASK;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.THING;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.meta;
import static io.floci.aws.services.iotmanagedintegrations.IotMiStore.view;

/**
 * Over-the-air update tasks and their configurations. A task's firmware must be in S3 and its targets must be
 * managed things, named by ARN or matched by a query ({@code attribute:value} terms over the things' fields);
 * each target gets an execution that waits, QUEUED, for its device to pick the update up through the device SDK,
 * which doesn't run in Floci. A task whose scheduling starts later is SCHEDULED until then.
 */
@ApplicationScoped
public class IotMiOta {

    private final IotMiStore store;
    private final IotMiService things;
    private final IotMiNotifications notifications;
    private final Instance<S3Service> s3;

    @Inject
    public IotMiOta(IotMiStore store, IotMiService things, IotMiNotifications notifications,
                    Instance<S3Service> s3) {
        this.store = store;
        this.things = things;
        this.notifications = notifications;
        this.s3 = s3;
    }

    // ---------------------------------------------------------------- task configurations

    public ObjectNode createOtaTaskConfiguration(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateOtaTaskConfiguration", in);
        if (repeated != null) {
            return repeated;
        }
        String id = IotMiStore.id();
        ObjectNode record = store.record();
        record.put("TaskConfigurationId", id).put("CreatedAt", IotMiStore.now());
        for (String field : List.of("Name", "Description", "PushConfig")) {
            if (in.hasNonNull(field)) {
                record.set(field, in.get(field).deepCopy());
            }
        }
        store.put(account, region, OTA_CONFIGURATION, id, record);
        ObjectNode out = store.mapper().createObjectNode().put("TaskConfigurationId", id);
        store.remember(account, region, "CreateOtaTaskConfiguration", in, out);
        return out;
    }

    public ObjectNode getOtaTaskConfiguration(String id, String region) {
        return view(store.require(store.account(), region, OTA_CONFIGURATION, id, "OTA task configuration"));
    }

    public ObjectNode deleteOtaTaskConfiguration(String id, String region) {
        String account = store.account();
        store.require(account, region, OTA_CONFIGURATION, id, "OTA task configuration");
        for (ObjectNode task : store.list(account, region, OTA_TASK)) {
            if (id.equals(task.path("TaskConfigurationId").asText())
                    && !Set.of("COMPLETED", "CANCELED").contains(task.path("Status").asText())) {
                throw IotMiStore.conflict("The OTA task configuration " + id + " is used by task "
                        + task.path("TaskId").asText() + ".");
            }
        }
        store.delete(account, region, OTA_CONFIGURATION, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listOtaTaskConfigurations(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, OTA_CONFIGURATION)) {
            ObjectNode summary = store.mapper().createObjectNode()
                    .put("TaskConfigurationId", record.path("TaskConfigurationId").asText())
                    .put("CreatedAt", record.path("CreatedAt").asDouble());
            if (record.has("Name")) {
                summary.put("Name", record.path("Name").asText());
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Items");
        return out;
    }

    // ---------------------------------------------------------------- tasks

    public ObjectNode createOtaTask(JsonNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateOtaTask", in);
        if (repeated != null) {
            return repeated;
        }
        checkFirmware(account, region, in.path("S3Url").asText());
        if (in.hasNonNull("TaskConfigurationId")) {
            store.require(account, region, OTA_CONFIGURATION, in.path("TaskConfigurationId").asText(),
                    "OTA task configuration");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (JsonNode target : in.path("Target")) {
            String arn = target.asText();
            String id = arn.substring(arn.lastIndexOf('/') + 1);
            things.thing(account, region, id);
            targets.add(arn);
        }
        if (in.hasNonNull("OtaTargetQueryString")) {
            targets.addAll(query(account, region, in.path("OtaTargetQueryString").asText()));
        }
        if (targets.isEmpty() && !"CONTINUOUS".equals(in.path("OtaType").asText())) {
            throw IotMiStore.validation("A ONE_TIME OTA task needs a Target or an OtaTargetQueryString that matches "
                    + "managed things.");
        }
        String id = IotMiStore.id();
        double now = IotMiStore.now();
        ObjectNode record = store.record();
        record.put("TaskId", id).put("TaskArn", store.arn(account, region, OTA_TASK, id))
                .put("S3Url", in.path("S3Url").asText()).put("Protocol", in.path("Protocol").asText("HTTP"))
                .put("OtaType", in.path("OtaType").asText()).put("OtaMechanism", in.path("OtaMechanism").asText("PUSH"))
                .put("CreatedAt", now).put("LastUpdatedAt", now);
        for (String field : List.of("Description", "OtaTargetQueryString", "TaskConfigurationId",
                "OtaSchedulingConfig", "OtaTaskExecutionRetryConfig")) {
            if (in.hasNonNull(field)) {
                record.set(field, in.get(field).deepCopy());
            }
        }
        ArrayNode targetList = record.putArray("Target");
        targets.forEach(targetList::add);
        Instant start = startTime(in.path("OtaSchedulingConfig").path("StartTime").asText(null));
        record.put("Status", start != null && start.isAfter(Instant.now()) ? "SCHEDULED" : "IN_PROGRESS");
        ArrayNode executions = meta(record).putArray("executions");
        long number = 1;
        for (String target : targets) {
            ObjectNode execution = executions.addObject().put("ManagedThingId", target.substring(
                    target.lastIndexOf('/') + 1));
            execution.putObject("TaskExecutionSummary").put("ExecutionNumber", number++).put("QueuedAt", now)
                    .put("LastUpdatedAt", now).put("RetryAttempt", 0).put("Status", "QUEUED");
        }
        processing(record);
        record.set("Tags", IotMiStore.tagsOf(store.mapper(), in.path("Tags")));
        store.put(account, region, OTA_TASK, id, record);
        notify(account, region, record, "CREATE_OTA");
        ObjectNode out = store.mapper().createObjectNode().put("TaskId", id).put("TaskArn", record.path("TaskArn")
                .asText());
        if (record.has("Description")) {
            out.put("Description", record.path("Description").asText());
        }
        store.remember(account, region, "CreateOtaTask", in, out);
        return out;
    }

    public ObjectNode getOtaTask(String id, String region) {
        return view(task(store.account(), region, id));
    }

    public ObjectNode updateOtaTask(String id, JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = task(account, region, id);
        if (in.hasNonNull("TaskConfigurationId")) {
            store.require(account, region, OTA_CONFIGURATION, in.path("TaskConfigurationId").asText(),
                    "OTA task configuration");
            record.put("TaskConfigurationId", in.path("TaskConfigurationId").asText());
        }
        if (in.hasNonNull("Description")) {
            record.put("Description", in.path("Description").asText());
        }
        record.put("LastUpdatedAt", IotMiStore.now());
        store.put(account, region, OTA_TASK, id, record);
        notify(account, region, record, "UPDATE_OTA");
        return store.mapper().createObjectNode();
    }

    /** Cancels the task's waiting executions and deletes it. */
    public ObjectNode deleteOtaTask(String id, String region) {
        String account = store.account();
        ObjectNode record = task(account, region, id);
        record.put("Status", "DELETION_IN_PROGRESS");
        notify(account, region, record, "DELETE_OTA");
        store.delete(account, region, OTA_TASK, id);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listOtaTasks(String token, Integer max, String region) {
        List<JsonNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, OTA_TASK)) {
            ObjectNode task = task(store.account(), region, record.path("TaskId").asText());
            ObjectNode summary = store.mapper().createObjectNode().put("TaskId", task.path("TaskId").asText())
                    .put("TaskArn", task.path("TaskArn").asText()).put("CreatedAt", task.path("CreatedAt").asDouble())
                    .put("LastUpdatedAt", task.path("LastUpdatedAt").asDouble())
                    .put("Status", task.path("Status").asText());
            if (task.has("TaskConfigurationId")) {
                summary.put("TaskConfigurationId", task.path("TaskConfigurationId").asText());
            }
            items.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "Tasks");
        return out;
    }

    public ObjectNode listOtaTaskExecutions(String id, String token, Integer max, String region) {
        ObjectNode record = task(store.account(), region, id);
        List<JsonNode> items = new ArrayList<>();
        record.path("_").path("executions").forEach(items::add);
        ObjectNode out = store.mapper().createObjectNode();
        IotMiStore.page(items, token, max, out, "ExecutionSummaries");
        return out;
    }

    /** A task, IN_PROGRESS once its scheduled start has come. */
    private ObjectNode task(String account, String region, String id) {
        ObjectNode record = store.require(account, region, OTA_TASK, id, "OTA task");
        Instant start = startTime(record.path("OtaSchedulingConfig").path("StartTime").asText(null));
        if ("SCHEDULED".equals(record.path("Status").asText()) && start != null && !start.isAfter(Instant.now())) {
            record.put("Status", "IN_PROGRESS").put("LastUpdatedAt", IotMiStore.now());
            store.put(account, region, OTA_TASK, id, record);
            notify(account, region, record, "UPDATE_OTA");
        }
        return record;
    }

    private static void processing(ObjectNode record) {
        ObjectNode details = record.putObject("TaskProcessingDetails");
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode execution : record.path("_").path("executions")) {
            counts.merge(execution.path("TaskExecutionSummary").path("Status").asText(), 1, Integer::sum);
        }
        details.put("NumberOfCanceledThings", counts.getOrDefault("CANCELED", 0))
                .put("NumberOfFailedThings", counts.getOrDefault("FAILED", 0))
                .put("NumberOfInProgressThings", counts.getOrDefault("IN_PROGRESS", 0))
                .put("numberOfQueuedThings", counts.getOrDefault("QUEUED", 0))
                .put("numberOfRejectedThings", counts.getOrDefault("REJECTED", 0))
                .put("numberOfRemovedThings", counts.getOrDefault("REMOVED", 0))
                .put("numberOfSucceededThings", counts.getOrDefault("SUCCEEDED", 0))
                .put("numberOfTimedOutThings", counts.getOrDefault("TIMED_OUT", 0));
        details.set("processingTargets", record.path("Target").deepCopy());
    }

    private void notify(String account, String region, ObjectNode task, String operation) {
        ObjectNode payload = store.mapper().createObjectNode().put("operation", operation)
                .put("otaTaskId", task.path("TaskId").asText()).put("status", task.path("Status").asText())
                .put("otaType", task.path("OtaType").asText());
        List<String> resources = new ArrayList<>();
        task.path("Target").forEach(target -> resources.add(target.asText()));
        notifications.publish(account, region, "DEVICE_OTA", resources, payload);
    }

    /** The firmware must be an object in S3: {@code s3://bucket/key} or its HTTPS form. */
    private void checkFirmware(String account, String region, String url) {
        String bucket;
        String key;
        try {
            URI uri = URI.create(url);
            if ("s3".equals(uri.getScheme())) {
                bucket = uri.getHost();
                key = uri.getPath().substring(1);
            } else {
                String host = uri.getHost();
                String path = uri.getPath().substring(1);
                if (host != null && host.contains(".s3")) {
                    bucket = host.substring(0, host.indexOf(".s3"));
                    key = path;
                } else {
                    bucket = path.substring(0, path.indexOf('/'));
                    key = path.substring(path.indexOf('/') + 1);
                }
            }
        } catch (RuntimeException e) {
            throw IotMiStore.validation("The S3Url " + url + " isn't an S3 object URL.");
        }
        try {
            String finalBucket = bucket;
            String finalKey = key;
            RequestScopes.runAs(account, region, () -> s3.get().getObject(finalBucket, finalKey));
        } catch (AwsException e) {
            throw IotMiStore.validation("The firmware " + url + " isn't in S3: " + e.getMessage());
        }
    }

    /** Managed things matching every {@code field:value} term of a query, by ARN. */
    private List<String> query(String account, String region, String query) {
        List<String> matched = new ArrayList<>();
        for (ObjectNode thing : store.list(account, region, THING)) {
            boolean all = true;
            for (String term : query.trim().split("\\s+(?i:AND)\\s+|\\s+")) {
                int colon = term.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String field = term.substring(0, colon);
                String value = term.substring(colon + 1);
                String actual = field.equals("thingName") ? thing.path("Name").asText()
                        : thing.path(Character.toUpperCase(field.charAt(0)) + field.substring(1)).asText(
                        thing.path("MetaData").path(field).asText(null));
                all &= value.equals(actual);
            }
            if (all && !IotMiService.deleted(thing)) {
                matched.add(thing.path("Arn").asText());
            }
        }
        return matched;
    }

    private static Instant startTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw IotMiStore.validation("The scheduling StartTime " + text + " isn't an ISO 8601 timestamp.");
        }
    }
}
