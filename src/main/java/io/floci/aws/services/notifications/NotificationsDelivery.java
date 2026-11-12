package io.floci.aws.services.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.eventbridge.ServiceEventTarget;
import io.floci.aws.services.notificationscontacts.NotificationsContactsService;
import io.floci.aws.services.ses.SesSentEmailService;
import io.floci.aws.services.ses.model.SentEmail;
import io.quarkus.arc.Unremovable;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Notification events. An event rule's managed rule hands its matched events here: each becomes a notification event
 * of the rule's configuration, kept in the account's first notification hub (or the default Region without one).
 * With aggregation, the first event of a window is delivered at once as an aggregate, later ones are its children, and
 * the aggregate is delivered again with their count when the window ends ({@code SHORT} 5 minutes, {@code LONG} 12
 * hours). Delivery emails the configuration's active email contacts through Floci's SES.
 */
@ApplicationScoped
@Unremovable
public class NotificationsDelivery implements ServiceEventTarget {

    private static final Logger LOG = Logger.getLogger(NotificationsDelivery.class);
    static final String SENDER = "no-reply@notifications.aws";

    private final NotificationsStore store;
    private final ObjectMapper mapper;
    private final Provider<NotificationsContactsService> contacts;
    private final Provider<SesSentEmailService> mail;
    private final Vertx vertx;
    private final RegionResolver regionResolver;

    @Inject
    public NotificationsDelivery(NotificationsStore store, Provider<NotificationsContactsService> contacts,
                                 Provider<SesSentEmailService> mail, Vertx vertx, RegionResolver regionResolver) {
        this.store = store;
        this.mapper = store.mapper();
        this.contacts = contacts;
        this.mail = mail;
        this.vertx = vertx;
        this.regionResolver = regionResolver;
    }

    @Override
    public boolean handles(String targetArn) {
        return targetArn.contains(":" + NotificationsStore.SERVICE + ":") && targetArn.contains("/rule/");
    }

    private static long window(String duration) {
        return switch (duration) {
            case "SHORT" -> 5 * 60_000L;
            case "LONG" -> 12 * 3600_000L;
            default -> 0;
        };
    }

    @Override
    public void deliver(String ruleArn, String eventJson, String region) {
        String account = AwsArnUtils.parse(ruleArn).accountId();
        ObjectNode rule = store.getForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.RULE, ruleArn)
                .orElse(null);
        if (rule == null) {
            return;
        }
        String configurationArn = rule.path("notificationConfigurationArn").asText();
        ObjectNode configuration = store.getForAccount(account, NotificationsStore.GLOBAL,
                NotificationsStore.CONFIGURATION, configurationArn).orElse(null);
        if (configuration == null || "DELETING".equals(configuration.path("status").asText())) {
            return;
        }
        JsonNode event;
        try {
            event = mapper.readTree(eventJson);
        } catch (IOException e) {
            LOG.debugv("User Notifications got an event it can't read: {0}", e.getMessage());
            return;
        }
        String hub = hub(account);
        String id = NotificationsService.id();
        String arn = AwsArnUtils.Arn.of(NotificationsStore.SERVICE, hub, account, configurationArn.substring(
                configurationArn.indexOf(":configuration/") + 1) + "/event/" + id).toString();
        double now = NotificationsStore.epoch();
        ObjectNode content = content(event, region, account, id);
        String duration = configuration.path("aggregationDuration").asText("NONE");
        long window = window(duration);
        ObjectNode aggregate = window == 0 ? null : openAggregate(account, configurationArn, rule, now, window);
        ObjectNode record = store.record();
        record.put("arn", arn);
        record.put("notificationConfigurationArn", configurationArn);
        record.put("creationTime", now);
        if (aggregate == null) {
            content.put("aggregationEventType", window == 0 ? "NONE" : "AGGREGATE");
            if (window > 0) {
                ObjectNode summary = content.putObject("aggregationSummary");
                summary.put("eventCount", 1);
                summary.putArray("aggregatedBy").addObject().put("name", "eventType").put("value", rule.path(
                        "eventType").asText());
                summary.putObject("aggregatedAccounts").put("name", "accounts").put("count", 1).putArray(
                        "sampleValues").add(account);
                summary.putObject("aggregatedRegions").put("name", "regions").put("count", 1).putArray(
                        "sampleValues").add(region);
                NotificationsStore.meta(record).put("ruleArn", rule.path("arn").asText());
                NotificationsStore.meta(record).put("windowEnds", (long) (now * 1000) + window);
            }
            record.set("content", content);
            store.putForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.EVENT, arn, record);
            send(account, configuration, record, false);
            if (window > 0) {
                vertx.setTimer(window, timer -> vertx.executeBlocking(() -> {
                    closeAggregate(account, arn);
                    return null;
                }, false));
            }
            return;
        }
        content.put("aggregationEventType", "CHILD");
        content.put("aggregateNotificationEventArn", aggregate.path("arn").asText());
        record.set("content", content);
        store.putForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.EVENT, arn, record);
        ObjectNode summary = (ObjectNode) aggregate.path("content").path("aggregationSummary");
        summary.put("eventCount", summary.path("eventCount").asInt(1) + 1);
        ObjectNode regions = (ObjectNode) summary.path("aggregatedRegions");
        ArrayNode samples = (ArrayNode) regions.path("sampleValues");
        if (!samples.toString().contains("\"" + region + "\"")) {
            samples.add(region);
            regions.put("count", samples.size());
        }
        store.putForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.EVENT, aggregate.path("arn").asText(),
                aggregate);
    }

    /** The aggregate an event of a rule joins: the latest one whose window is still open. */
    private ObjectNode openAggregate(String account, String configurationArn, ObjectNode rule, double now,
                                     long window) {
        List<ObjectNode> events = store.listForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.EVENT);
        for (int i = events.size() - 1; i >= 0; i--) {
            ObjectNode event = events.get(i);
            if (configurationArn.equals(event.path("notificationConfigurationArn").asText())
                    && "AGGREGATE".equals(event.path("content").path("aggregationEventType").asText())
                    && rule.path("arn").asText().equals(NotificationsStore.meta(event).path("ruleArn").asText())
                    && NotificationsStore.meta(event).path("windowEnds").asLong(0) > (long) (now * 1000)) {
                return event;
            }
        }
        return null;
    }

    private void closeAggregate(String account, String arn) {
        ObjectNode aggregate = store.getForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.EVENT, arn)
                .orElse(null);
        if (aggregate == null || aggregate.path("content").path("aggregationSummary").path("eventCount").asInt(1)
                < 2) {
            return;
        }
        ObjectNode configuration = store.getForAccount(account, NotificationsStore.GLOBAL,
                NotificationsStore.CONFIGURATION, aggregate.path("notificationConfigurationArn").asText()).orElse(null);
        if (configuration != null) {
            send(account, configuration, aggregate, true);
        }
    }

    private String hub(String account) {
        for (ObjectNode hub : store.listForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.HUB)) {
            if ("ACTIVE".equals(hub.path("statusSummary").path("status").asText())) {
                return hub.path("notificationHubRegion").asText();
            }
        }
        return regionResolver.getDefaultRegion();
    }

    /** A notification event's content, from the EventBridge event that matched. */
    private ObjectNode content(JsonNode event, String region, String account, String id) {
        ObjectNode content = mapper.createObjectNode();
        content.put("schemaVersion", "v1.0");
        content.put("id", id);
        String source = event.path("source").asText();
        String type = event.path("detail-type").asText();
        String origin = event.path("region").asText(region);
        ObjectNode metadata = content.putObject("sourceEventMetadata");
        metadata.put("eventTypeVersion", event.path("version").asText("0"));
        metadata.put("sourceEventId", event.path("id").asText(id));
        metadata.put("eventOriginRegion", origin);
        metadata.put("relatedAccount", event.path("account").asText(account));
        metadata.put("source", source);
        double occurred = NotificationsStore.epoch();
        try {
            occurred = Instant.parse(event.path("time").asText()).toEpochMilli() / 1000.0;
        } catch (RuntimeException e) {
            // events without a time are dated by their arrival
        }
        metadata.put("eventOccurrenceTime", occurred);
        metadata.put("eventType", type);
        ArrayNode resources = metadata.putArray("relatedResources");
        event.path("resources").forEach(resource -> resources.addObject().put("arn", resource.asText()));
        String service = source.startsWith("aws.") ? source.substring(4).toUpperCase(Locale.ROOT) : source;
        String headline = "AWS " + service + " " + type + " in " + origin;
        ObjectNode message = content.putObject("messageComponents");
        message.put("headline", headline);
        message.put("paragraphSummary", headline + (resources.isEmpty() ? "." : " for "
                + resources.get(0).path("arn").asText() + "."));
        message.put("completeDescription", event.path("detail").toPrettyString());
        ArrayNode dimensions = message.putArray("dimensions");
        dimensions.addObject().put("name", "Account").put("value", metadata.path("relatedAccount").asText());
        dimensions.addObject().put("name", "Region").put("value", origin);
        String lower = type.toLowerCase(Locale.ROOT);
        boolean failing = lower.contains("fail") || lower.contains("error") || lower.contains("alarm")
                || lower.contains("unhealthy") || lower.contains("terminat");
        content.put("notificationType", failing ? "ALERT" : "INFORMATIONAL");
        content.put("eventStatus", failing ? "UNHEALTHY" : "HEALTHY");
        content.put("startTime", occurred);
        ObjectNode parts = content.putObject("textParts");
        parts.putObject("headline").put("type", "PLAIN_TEXT").put("displayText", headline);
        content.putArray("media");
        return content;
    }

    /** Emails a notification event to its configuration's active email contacts. */
    private void send(String account, ObjectNode configuration, ObjectNode event, boolean summary) {
        JsonNode content = event.path("content");
        String headline = content.path("messageComponents").path("headline").asText();
        int count = content.path("aggregationSummary").path("eventCount").asInt(1);
        String subject = summary ? count + " notifications: " + headline : headline;
        String body = (summary ? count + " events matched the notification configuration "
                : "An event matched the notification configuration ") + configuration.path("name").asText() + ".\n\n"
                + content.path("messageComponents").path("paragraphSummary").asText() + "\n\n"
                + content.path("messageComponents").path("completeDescription").asText();
        for (JsonNode channel : NotificationsService.Channels.channels(configuration)) {
            contacts.get().find(account, channel.asText()).ifPresent(contact -> {
                if (!"active".equals(contact.path("status").asText())) {
                    return;
                }
                String region = regionResolver.getDefaultRegion();
                String messageId = UUID.randomUUID().toString();
                mail.get().record(region, messageId, new SentEmail(messageId, region, SENDER,
                        List.of(contact.path("address").asText()), List.of(), List.of(), List.of(), subject, body,
                        null));
            });
        }
    }
}
