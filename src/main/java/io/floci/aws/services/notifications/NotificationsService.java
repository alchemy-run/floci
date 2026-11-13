package io.floci.aws.services.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.model.RuleState;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.services.notificationscontacts.NotificationsContactsService;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AWS User Notifications' configuration. A notification configuration holds event rules and channels. Each event rule
 * puts a managed rule ({@code AWSUserNotificationsManagedRule-...}) on the default event bus of each of its Regions,
 * matching its source, event type and pattern, whose events {@link NotificationsDelivery} turns into notification
 * events. A configuration is {@code ACTIVE} when all its event rules are, {@code PARTIALLY_ACTIVE} when some are, and
 * {@code INACTIVE} without any. Channels are User Notifications email contacts, Chatbot configurations and Console
 * Mobile Application devices. Notification hubs are the Regions events are kept in; the last active one can't be
 * deregistered. Managed notification configurations are AWS Health's categories, delivered to the account contacts and
 * the additional channels associated with them.
 */
@ApplicationScoped
public class NotificationsService {

    private static final Logger LOG = Logger.getLogger(NotificationsService.class);
    static final String MANAGED_RULE = "AWSUserNotificationsManagedRule-";
    private static final Set<String> DURATIONS = Set.of("LONG", "SHORT", "NONE");
    private static final Set<String> CONTACTS = Set.of("ACCOUNT_PRIMARY", "ACCOUNT_ALTERNATE_BILLING",
            "ACCOUNT_ALTERNATE_OPERATIONS", "ACCOUNT_ALTERNATE_SECURITY");
    /** AWS Health's managed notification categories: sub-category, name and description. */
    static final List<String[]> MANAGED = List.of(
            new String[] {"Billing", "AWS Health - Billing", "Billing and payment notifications from AWS Health."},
            new String[] {"Issue", "AWS Health - Operational Issues", "Operational issues affecting AWS services."},
            new String[] {"ScheduledChange", "AWS Health - Scheduled Changes", "Planned maintenance and scheduled "
                    + "changes to AWS resources."},
            new String[] {"AccountNotification", "AWS Health - Account Notifications", "Notifications about your "
                    + "account and its resources."},
            new String[] {"Security", "AWS Health - Security", "Security notifications from AWS Health."});

    private final NotificationsStore store;
    private final ObjectMapper mapper;
    private final Provider<EventBridgeService> events;
    private final Provider<NotificationsContactsService> contacts;
    private final Vertx vertx;
    private final RegionResolver regionResolver;

    @Inject
    public NotificationsService(NotificationsStore store, Provider<EventBridgeService> events,
                                Provider<NotificationsContactsService> contacts, Vertx vertx, RegionResolver regions) {
        this.regionResolver = regions;
        this.store = store;
        this.mapper = store.mapper();
        this.events = events;
        this.contacts = contacts;
        this.vertx = vertx;
    }

    private void later(Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> vertx.executeBlocking(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warnv("User Notifications work failed: {0}", e.getMessage());
            }
            return null;
        }, false));
    }

    static String id() {
        return NotificationsStore.random(27, "abcdefghijklmnopqrstuvwxyz0123456789");
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw NotificationsStore.validation(member + " is required.");
        }
        return value;
    }

    private static ObjectNode view(ObjectNode record) {
        return NotificationsStore.view(record);
    }

    private String arn(String resource) {
        return regionResolver.buildGlobalArn(NotificationsStore.SERVICE, store.account(), resource);
    }

    private ObjectNode get(String kind, String id) {
        return store.get(NotificationsStore.GLOBAL, kind, id).orElse(null);
    }

    private void put(String kind, String id, ObjectNode record) {
        store.put(NotificationsStore.GLOBAL, kind, id, record);
    }

    private List<ObjectNode> list(String kind) {
        return store.list(NotificationsStore.GLOBAL, kind);
    }

    // ================================================================ configurations

    ObjectNode configuration(String arn) {
        ObjectNode configuration = get(NotificationsStore.CONFIGURATION, arn);
        if (configuration == null) {
            throw NotificationsStore.notFound("The notification configuration " + arn + " doesn't exist.");
        }
        return configuration;
    }

    private List<ObjectNode> rulesOf(String configurationArn) {
        List<ObjectNode> rules = new ArrayList<>();
        for (ObjectNode rule : list(NotificationsStore.RULE)) {
            if (configurationArn.equals(rule.path("notificationConfigurationArn").asText())) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /** A configuration's status, from its event rules' statuses in their Regions. */
    private String status(ObjectNode configuration) {
        if ("DELETING".equals(configuration.path("status").asText())) {
            return "DELETING";
        }
        int active = 0;
        int total = 0;
        for (ObjectNode rule : rulesOf(configuration.path("arn").asText())) {
            for (JsonNode summary : rule.path("statusSummaryByRegion")) {
                total++;
                if ("ACTIVE".equals(summary.path("status").asText())) {
                    active++;
                }
            }
        }
        return total == 0 || active == 0 ? "INACTIVE" : active == total ? "ACTIVE" : "PARTIALLY_ACTIVE";
    }

    private ObjectNode configurationView(ObjectNode configuration) {
        ObjectNode view = view(configuration);
        view.put("status", status(configuration));
        return view;
    }

    private static void checkName(String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw NotificationsStore.validation("name must be 1-64 letters, digits, hyphens and underscores.");
        }
    }

    public ObjectNode createNotificationConfiguration(JsonNode in, String region) {
        String name = required(in, "name");
        checkName(name);
        String description = in.path("description").asText("");
        if (description.length() > 256) {
            throw NotificationsStore.validation("description must be at most 256 characters.");
        }
        String duration = in.path("aggregationDuration").asText("NONE");
        if (!DURATIONS.contains(duration)) {
            throw NotificationsStore.validation("aggregationDuration must be LONG, SHORT or NONE.");
        }
        for (ObjectNode existing : list(NotificationsStore.CONFIGURATION)) {
            if (name.equals(existing.path("name").asText())) {
                throw NotificationsStore.conflict("A notification configuration named " + name + " already exists.");
            }
        }
        String arn = arn("configuration/" + id());
        ObjectNode configuration = store.record();
        configuration.put("arn", arn);
        configuration.put("name", name);
        configuration.put("description", description);
        configuration.put("status", "INACTIVE");
        configuration.put("creationTime", NotificationsStore.epoch());
        configuration.put("aggregationDuration", duration);
        configuration.put("subtype", "ACCOUNT");
        Channels.channels(configuration);
        NotificationsStore.addTags(configuration, in.path("tags"));
        put(NotificationsStore.CONFIGURATION, arn, configuration);
        ObjectNode out = mapper.createObjectNode();
        out.put("arn", arn);
        out.put("status", "INACTIVE");
        return out;
    }

    public ObjectNode getNotificationConfiguration(JsonNode in, String region) {
        return configurationView(configuration(required(in, "arn")));
    }

    public ObjectNode updateNotificationConfiguration(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode configuration = configuration(arn);
        if (in.hasNonNull("name")) {
            String name = in.get("name").asText();
            checkName(name);
            for (ObjectNode existing : list(NotificationsStore.CONFIGURATION)) {
                if (name.equals(existing.path("name").asText()) && !arn.equals(existing.path("arn").asText())) {
                    throw NotificationsStore.conflict("A notification configuration named " + name
                            + " already exists.");
                }
            }
            configuration.put("name", name);
        }
        if (in.hasNonNull("description")) {
            configuration.put("description", in.get("description").asText());
        }
        if (in.hasNonNull("aggregationDuration")) {
            String duration = in.get("aggregationDuration").asText();
            if (!DURATIONS.contains(duration)) {
                throw NotificationsStore.validation("aggregationDuration must be LONG, SHORT or NONE.");
            }
            configuration.put("aggregationDuration", duration);
        }
        put(NotificationsStore.CONFIGURATION, arn, configuration);
        ObjectNode out = mapper.createObjectNode();
        out.put("arn", arn);
        return out;
    }

    public ObjectNode deleteNotificationConfiguration(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode configuration = configuration(arn);
        configuration.put("status", "DELETING");
        put(NotificationsStore.CONFIGURATION, arn, configuration);
        String account = store.account();
        for (ObjectNode rule : rulesOf(arn)) {
            removeManagedRules(account, rule, rule.path("regions"));
            store.deleteForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.RULE,
                    rule.path("arn").asText());
        }
        later(() -> store.deleteForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.CONFIGURATION, arn));
        return mapper.createObjectNode();
    }

    public ObjectNode listNotificationConfigurations(JsonNode in, String region) {
        String source = in.path("eventRuleSource").asText(null);
        String channel = in.path("channelArn").asText(null);
        String status = in.path("status").asText(null);
        String subtype = in.path("subtype").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode configuration : list(NotificationsStore.CONFIGURATION)) {
            ObjectNode summary = configurationView(configuration);
            String arn = configuration.path("arn").asText();
            if (status != null && !status.equals(summary.path("status").asText())
                    || subtype != null && !subtype.equals(summary.path("subtype").asText())
                    || channel != null && !Channels.channels(configuration).toString().contains("\"" + channel
                    + "\"")) {
                continue;
            }
            if (source != null && rulesOf(arn).stream().noneMatch(rule -> source.equals(rule.path("source")
                    .asText()))) {
                continue;
            }
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "notificationConfigurations", 100);
        return out;
    }

    // ================================================================ event rules

    ObjectNode rule(String arn) {
        ObjectNode rule = get(NotificationsStore.RULE, arn);
        if (rule == null) {
            throw NotificationsStore.notFound("The event rule " + arn + " doesn't exist.");
        }
        return rule;
    }

    private static Set<String> regions(JsonNode in) {
        Set<String> regions = new LinkedHashSet<>();
        in.forEach(region -> regions.add(region.asText()));
        if (regions.isEmpty()) {
            throw NotificationsStore.validation("regions needs at least one Region.");
        }
        for (String region : regions) {
            if (!region.matches("[a-z]{2}(-[a-z]+)+-\\d")) {
                throw NotificationsStore.validation("The Region " + region + " isn't valid.");
            }
        }
        return regions;
    }

    /** The managed rule's pattern: the event rule's source and event type, with its pattern's other filters. */
    private String pattern(String source, String eventType, String eventPattern) {
        ObjectNode pattern = mapper.createObjectNode();
        if (eventPattern != null && !eventPattern.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(eventPattern);
                if (!parsed.isObject()) {
                    throw NotificationsStore.validation("eventPattern must be a JSON object.");
                }
                pattern.setAll((ObjectNode) parsed);
            } catch (JsonProcessingException e) {
                throw NotificationsStore.validation("eventPattern isn't valid JSON.");
            }
        }
        pattern.putArray("source").add(source);
        pattern.putArray("detail-type").add(eventType);
        return pattern.toString();
    }

    private String managedRuleName(ObjectNode rule) {
        return MANAGED_RULE + rule.path("arn").asText().substring(rule.path("arn").asText().lastIndexOf('/') + 1);
    }

    private void putManagedRules(String account, ObjectNode rule, Set<String> regions) {
        String name = managedRuleName(rule);
        String pattern = pattern(rule.path("source").asText(), rule.path("eventType").asText(),
                rule.path("eventPattern").asText(null));
        ArrayNode managed = rule.putArray("managedRules");
        for (String region : regions) {
            try {
                RequestScopes.runAs(account, region, () -> {
                    events.get().putRule(name, "default", pattern, null, RuleState.ENABLED, "Managed by AWS User "
                            + "Notifications for " + rule.path("arn").asText(), null, Map.of(), region);
                    Target target = new Target();
                    target.setId("user-notifications");
                    target.setArn(rule.path("arn").asText());
                    events.get().putTargets(name, "default", List.of(target), region);
                });
                managed.add(AwsArnUtils.Arn.of("events", region, account, "rule/" + name).toString());
            } catch (AwsException e) {
                LOG.debugv("User Notifications could not create its rule in {0}: {1}", region, e.getMessage());
                ((ObjectNode) rule.get("statusSummaryByRegion")).putObject(region).put("status", "INACTIVE")
                        .put("reason", "The managed rule couldn't be created: " + e.getMessage());
            }
        }
    }

    private void removeManagedRules(String account, ObjectNode rule, JsonNode regions) {
        String name = managedRuleName(rule);
        for (JsonNode region : regions) {
            try {
                RequestScopes.runAs(account, region.asText(), () -> {
                    events.get().removeTargets(name, "default", List.of("user-notifications"), region.asText());
                    events.get().deleteRule(name, "default", region.asText());
                });
            } catch (AwsException e) {
                LOG.debugv("User Notifications could not remove its rule in {0}: {1}", region.asText(),
                        e.getMessage());
            }
        }
    }

    private void settle(String account, String ruleArn, String status, String reason) {
        later(() -> store.getForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.RULE, ruleArn).ifPresent(
                current -> {
                    current.path("statusSummaryByRegion").forEach(summary -> {
                        if (!"INACTIVE".equals(summary.path("status").asText())) {
                            ((ObjectNode) summary).put("status", status).put("reason", reason);
                        }
                    });
                    store.putForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.RULE, ruleArn, current);
                }));
    }

    private ObjectNode ruleOutput(ObjectNode rule) {
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.copy(rule, out, "arn", "notificationConfigurationArn", "statusSummaryByRegion");
        return out;
    }

    public ObjectNode createEventRule(JsonNode in, String region) {
        String configurationArn = required(in, "notificationConfigurationArn");
        ObjectNode configuration = configuration(configurationArn);
        String source = required(in, "source");
        if (!source.startsWith("aws.")) {
            throw NotificationsStore.validation("source must be an AWS service's event source, such as aws.s3.");
        }
        String eventType = required(in, "eventType");
        Set<String> regions = regions(in.path("regions"));
        String eventPattern = in.path("eventPattern").asText("");
        pattern(source, eventType, eventPattern);
        for (ObjectNode existing : rulesOf(configurationArn)) {
            if (source.equals(existing.path("source").asText()) && eventType.equals(existing.path("eventType")
                    .asText()) && eventPattern.equals(existing.path("eventPattern").asText())) {
                throw NotificationsStore.conflict("The configuration already has this event rule.");
            }
        }
        if (rulesOf(configurationArn).size() >= 10) {
            throw NotificationsStore.quota("A notification configuration has at most 10 event rules.");
        }
        String ruleArn = configurationArn + "/rule/" + id();
        ObjectNode rule = store.record();
        rule.put("arn", ruleArn);
        rule.put("notificationConfigurationArn", configurationArn);
        rule.put("creationTime", NotificationsStore.epoch());
        rule.put("source", source);
        rule.put("eventType", eventType);
        rule.put("eventPattern", eventPattern);
        ArrayNode regionList = rule.putArray("regions");
        regions.forEach(regionList::add);
        ObjectNode summaries = rule.putObject("statusSummaryByRegion");
        regions.forEach(one -> summaries.putObject(one).put("status", "CREATING").put("reason", "The rule is being "
                + "created."));
        String account = store.account();
        putManagedRules(account, rule, regions);
        put(NotificationsStore.RULE, ruleArn, rule);
        settle(account, ruleArn, "ACTIVE", "The rule is active.");
        put(NotificationsStore.CONFIGURATION, configurationArn, configuration);
        return ruleOutput(rule);
    }

    public ObjectNode getEventRule(JsonNode in, String region) {
        return view(rule(required(in, "arn")));
    }

    public ObjectNode updateEventRule(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode rule = rule(arn);
        String account = store.account();
        Set<String> previous = new LinkedHashSet<>();
        rule.path("regions").forEach(one -> previous.add(one.asText()));
        Set<String> regions = in.has("regions") ? regions(in.path("regions")) : previous;
        if (in.has("eventPattern")) {
            String eventPattern = in.path("eventPattern").asText("");
            pattern(rule.path("source").asText(), rule.path("eventType").asText(), eventPattern);
            rule.put("eventPattern", eventPattern);
        }
        ArrayNode removed = mapper.createArrayNode();
        previous.stream().filter(one -> !regions.contains(one)).forEach(removed::add);
        removeManagedRules(account, rule, removed);
        ArrayNode regionList = rule.putArray("regions");
        regions.forEach(regionList::add);
        ObjectNode summaries = rule.putObject("statusSummaryByRegion");
        regions.forEach(one -> summaries.putObject(one).put("status", "UPDATING").put("reason", "The rule is being "
                + "updated."));
        putManagedRules(account, rule, regions);
        put(NotificationsStore.RULE, arn, rule);
        settle(account, arn, "ACTIVE", "The rule is active.");
        return ruleOutput(rule);
    }

    public ObjectNode deleteEventRule(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode rule = rule(arn);
        removeManagedRules(store.account(), rule, rule.path("regions"));
        store.delete(NotificationsStore.GLOBAL, NotificationsStore.RULE, arn);
        return mapper.createObjectNode();
    }

    public ObjectNode listEventRules(JsonNode in, String region) {
        String configurationArn = required(in, "notificationConfigurationArn");
        configuration(configurationArn);
        List<ObjectNode> items = new ArrayList<>();
        rulesOf(configurationArn).forEach(rule -> items.add(view(rule)));
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "eventRules", 100);
        return out;
    }

    // ================================================================ channels

    /** Checks a channel exists: an email contact, a Chatbot configuration or a Console Mobile Application device. */
    private void checkChannel(String channel) {
        if (channel.contains(":notifications-contacts:") && channel.contains(":emailcontact/")) {
            if (contacts.get().find(store.account(), channel).isEmpty()) {
                throw NotificationsStore.notFound("The email contact " + channel + " doesn't exist.");
            }
            return;
        }
        if (channel.contains(":chatbot:") || channel.contains(":consoleapp:")) {
            return;
        }
        throw NotificationsStore.validation("The channel " + channel + " isn't an email contact, a Chatbot "
                + "configuration or a mobile device.");
    }

    public ObjectNode associateChannel(JsonNode in, String region) {
        String channel = required(in, "arn");
        String configurationArn = required(in, "notificationConfigurationArn");
        ObjectNode configuration = configuration(configurationArn);
        checkChannel(channel);
        ArrayNode channels = Channels.channels(configuration);
        for (JsonNode existing : channels) {
            if (channel.equals(existing.asText())) {
                throw NotificationsStore.conflict("The channel is already associated with the configuration.");
            }
        }
        if (channels.size() >= 25) {
            throw NotificationsStore.quota("A notification configuration has at most 25 channels.");
        }
        channels.add(channel);
        put(NotificationsStore.CONFIGURATION, configurationArn, configuration);
        return mapper.createObjectNode();
    }

    public ObjectNode disassociateChannel(JsonNode in, String region) {
        String channel = required(in, "arn");
        String configurationArn = required(in, "notificationConfigurationArn");
        ObjectNode configuration = configuration(configurationArn);
        ArrayNode channels = Channels.channels(configuration);
        boolean found = false;
        for (Iterator<JsonNode> each = channels.iterator(); each.hasNext(); ) {
            if (channel.equals(each.next().asText())) {
                each.remove();
                found = true;
            }
        }
        if (!found) {
            throw NotificationsStore.notFound("The channel isn't associated with the configuration.");
        }
        put(NotificationsStore.CONFIGURATION, configurationArn, configuration);
        return mapper.createObjectNode();
    }

    public ObjectNode listChannels(JsonNode in, String region) {
        ObjectNode configuration = configuration(required(in, "notificationConfigurationArn"));
        List<JsonNode> items = new ArrayList<>();
        Channels.channels(configuration).forEach(items::add);
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "channels", 100);
        return out;
    }

    // ================================================================ hubs

    private ObjectNode hubView(ObjectNode hub) {
        return view(hub);
    }

    public ObjectNode registerNotificationHub(JsonNode in, String region) {
        String hubRegion = required(in, "notificationHubRegion");
        if (!AwsPartitions.isPublishedRegion(hubRegion)) {
            throw NotificationsStore.validation("The Region " + hubRegion + " isn't a Region notification hubs are "
                    + "in.");
        }
        ObjectNode hub = get(NotificationsStore.HUB, hubRegion);
        if (hub != null && !"DEREGISTERING".equals(hub.path("statusSummary").path("status").asText())) {
            return hubView(hub);
        }
        if (list(NotificationsStore.HUB).size() >= 3) {
            throw NotificationsStore.quota("An account has at most 3 notification hubs.");
        }
        double now = NotificationsStore.epoch();
        hub = store.record();
        hub.put("notificationHubRegion", hubRegion);
        hub.putObject("statusSummary").put("status", "REGISTERING").put("reason", "The hub is being registered.");
        hub.put("creationTime", now);
        put(NotificationsStore.HUB, hubRegion, hub);
        String account = store.account();
        later(() -> store.getForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.HUB, hubRegion)
                .ifPresent(current -> {
                    current.putObject("statusSummary").put("status", "ACTIVE").put("reason", "The hub is active.");
                    current.put("lastActivationTime", NotificationsStore.epoch());
                    store.putForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.HUB, hubRegion,
                            current);
                }));
        return hubView(hub);
    }

    public ObjectNode deregisterNotificationHub(JsonNode in, String region) {
        String hubRegion = required(in, "notificationHubRegion");
        ObjectNode hub = get(NotificationsStore.HUB, hubRegion);
        if (hub == null) {
            throw NotificationsStore.notFound("No notification hub is registered in " + hubRegion + ".");
        }
        long otherActive = list(NotificationsStore.HUB).stream().filter(other -> !hubRegion.equals(other.path(
                "notificationHubRegion").asText()) && "ACTIVE".equals(other.path("statusSummary").path("status")
                .asText())).count();
        if ("ACTIVE".equals(hub.path("statusSummary").path("status").asText()) && otherActive == 0) {
            throw NotificationsStore.conflict("Cannot deregister last ACTIVE notification hub.");
        }
        hub.putObject("statusSummary").put("status", "DEREGISTERING").put("reason", "The hub is being deregistered.");
        put(NotificationsStore.HUB, hubRegion, hub);
        String account = store.account();
        later(() -> store.deleteForAccount(account, NotificationsStore.GLOBAL, NotificationsStore.HUB, hubRegion));
        ObjectNode out = mapper.createObjectNode();
        out.put("notificationHubRegion", hubRegion);
        out.set("statusSummary", hub.get("statusSummary").deepCopy());
        return out;
    }

    public ObjectNode listNotificationHubs(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        list(NotificationsStore.HUB).forEach(hub -> items.add(hubView(hub)));
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "notificationHubs", 100);
        return out;
    }

    // ================================================================ notification events

    public ObjectNode getNotificationEvent(JsonNode in, String region) {
        String arn = required(in, "arn");
        ObjectNode event = get(NotificationsStore.EVENT, arn);
        if (event == null) {
            throw NotificationsStore.notFound("The notification event " + arn + " doesn't exist.");
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.copy(event, out, "arn", "notificationConfigurationArn", "creationTime", "content");
        return out;
    }

    private static double time(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Double.NaN;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Instant.parse(value.asText()).toEpochMilli() / 1000.0;
        } catch (RuntimeException e) {
            try {
                return Double.parseDouble(value.asText());
            } catch (NumberFormatException f) {
                throw NotificationsStore.validation("The time " + value.asText() + " isn't valid.");
            }
        }
    }

    public ObjectNode listNotificationEvents(JsonNode in, String region) {
        double start = time(in.path("startTime"));
        double end = time(in.path("endTime"));
        String source = in.path("source").asText(null);
        boolean children = in.path("includeChildEvents").asBoolean(false);
        String aggregate = in.path("aggregateNotificationEventArn").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> all = list(NotificationsStore.EVENT);
        for (int i = all.size() - 1; i >= 0; i--) {
            ObjectNode event = all.get(i);
            JsonNode content = event.path("content");
            double created = event.path("creationTime").asDouble();
            String type = content.path("aggregationEventType").asText("NONE");
            if (!Double.isNaN(start) && created < start || !Double.isNaN(end) && created > end
                    || source != null && !source.equals(content.path("sourceEventMetadata").path("source").asText())
                    || aggregate != null && !aggregate.equals(content.path("aggregateNotificationEventArn").asText())
                    || aggregate == null && !children && "CHILD".equals(type)) {
                continue;
            }
            ObjectNode overview = mapper.createObjectNode();
            NotificationsStore.copy(event, overview, "arn", "notificationConfigurationArn", "creationTime");
            overview.put("relatedAccount", content.path("sourceEventMetadata").path("relatedAccount").asText());
            ObjectNode summary = overview.putObject("notificationEvent");
            summary.put("schemaVersion", "v1.0");
            ObjectNode metadata = summary.putObject("sourceEventMetadata");
            NotificationsStore.copy(content.path("sourceEventMetadata"), metadata, "eventOriginRegion", "source",
                    "eventType");
            summary.putObject("messageComponents").put("headline", content.path("messageComponents")
                    .path("headline").asText());
            NotificationsStore.copy(content, summary, "eventStatus", "notificationType");
            overview.put("aggregationEventType", type);
            NotificationsStore.copy(content, overview, "aggregateNotificationEventArn", "aggregationSummary");
            items.add(overview);
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "notificationEvents", 100);
        return out;
    }

    // ================================================================ managed notifications

    private String managedArn(String subCategory) {
        return arn("managed-notification-configuration/category/AWS-Health/sub-category/" + subCategory);
    }

    private String[] managed(String arn) {
        for (String[] managed : MANAGED) {
            if (arn.equals(managedArn(managed[0]))) {
                return managed;
            }
        }
        throw NotificationsStore.notFound("The managed notification configuration " + arn + " doesn't exist.");
    }

    public ObjectNode listManagedNotificationConfigurations(JsonNode in, String region) {
        String channel = in.path("channelIdentifier").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (String[] managed : MANAGED) {
            String arn = managedArn(managed[0]);
            if (channel != null && associations(arn).stream().noneMatch(association -> channel.equals(association
                    .path("channelIdentifier").asText()))) {
                continue;
            }
            ObjectNode item = mapper.createObjectNode();
            item.put("arn", arn);
            item.put("name", managed[1]);
            item.put("description", managed[2]);
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "managedNotificationConfigurations", 100);
        return out;
    }

    public ObjectNode getManagedNotificationConfiguration(JsonNode in, String region) {
        String arn = required(in, "arn");
        String[] managed = managed(arn);
        ObjectNode out = mapper.createObjectNode();
        out.put("arn", arn);
        out.put("name", managed[1]);
        out.put("description", managed[2]);
        out.put("category", "AWS-Health");
        out.put("subCategory", managed[0]);
        return out;
    }

    /** A managed configuration's channels: the primary account contact always, then what was associated. */
    List<ObjectNode> associations(String arn) {
        List<ObjectNode> found = new ArrayList<>();
        ObjectNode primary = mapper.createObjectNode();
        primary.put("channelIdentifier", "ACCOUNT_PRIMARY");
        primary.put("channelType", "ACCOUNT_CONTACT");
        primary.put("overrideOption", "DISABLED");
        ObjectNode record = get(NotificationsStore.MANAGED, arn);
        boolean primaryOverridden = false;
        if (record != null) {
            for (JsonNode association : record.path("associations")) {
                if ("ACCOUNT_PRIMARY".equals(association.path("channelIdentifier").asText())) {
                    primary.set("isSensitiveEventsSubscribed", association.path("isSensitiveEventsSubscribed"));
                    primaryOverridden = true;
                    continue;
                }
                found.add((ObjectNode) association.deepCopy());
            }
        }
        if (!primaryOverridden) {
            primary.put("isSensitiveEventsSubscribed", false);
        }
        found.add(0, primary);
        return found;
    }

    private ObjectNode associationRecord(String arn) {
        ObjectNode record = get(NotificationsStore.MANAGED, arn);
        if (record == null) {
            record = store.record();
            record.putArray("associations");
        }
        return record;
    }

    private void associate(String arn, String channel, String type, JsonNode in) {
        managed(arn);
        ObjectNode record = associationRecord(arn);
        for (JsonNode existing : record.path("associations")) {
            if (channel.equals(existing.path("channelIdentifier").asText())) {
                throw NotificationsStore.conflict("The channel is already associated with the managed "
                        + "notification configuration.");
            }
        }
        if ("ACCOUNT_PRIMARY".equals(channel)) {
            throw NotificationsStore.conflict("The primary account contact is always associated.");
        }
        ObjectNode association = ((ArrayNode) record.get("associations")).addObject();
        association.put("channelIdentifier", channel);
        association.put("channelType", type);
        association.put("overrideOption", "ENABLED");
        association.put("isSensitiveEventsSubscribed", in.path("isSensitiveEventsSubscribed").asBoolean(false));
        put(NotificationsStore.MANAGED, arn, record);
    }

    private void disassociate(String arn, String channel) {
        managed(arn);
        ObjectNode record = associationRecord(arn);
        boolean found = false;
        for (Iterator<JsonNode> each = record.path("associations").iterator(); each.hasNext(); ) {
            if (channel.equals(each.next().path("channelIdentifier").asText())) {
                each.remove();
                found = true;
            }
        }
        if (!found) {
            throw NotificationsStore.notFound("The channel isn't associated with the managed notification "
                    + "configuration.");
        }
        put(NotificationsStore.MANAGED, arn, record);
    }

    public ObjectNode associateManagedNotificationAccountContact(JsonNode in, String region) {
        String contact = required(in, "contactIdentifier");
        if (!CONTACTS.contains(contact)) {
            throw NotificationsStore.validation("contactIdentifier must be an account contact.");
        }
        associate(required(in, "managedNotificationConfigurationArn"), contact, "ACCOUNT_CONTACT", in);
        return mapper.createObjectNode();
    }

    public ObjectNode disassociateManagedNotificationAccountContact(JsonNode in, String region) {
        String contact = required(in, "contactIdentifier");
        if ("ACCOUNT_PRIMARY".equals(contact)) {
            throw NotificationsStore.conflict("The primary account contact can't be disassociated.");
        }
        disassociate(required(in, "managedNotificationConfigurationArn"), contact);
        return mapper.createObjectNode();
    }

    public ObjectNode associateManagedNotificationAdditionalChannel(JsonNode in, String region) {
        String channel = required(in, "channelArn");
        checkChannel(channel);
        String type = channel.contains(":chatbot:") ? "CHATBOT" : channel.contains(":consoleapp:") ? "MOBILE" : "EMAIL";
        associate(required(in, "managedNotificationConfigurationArn"), channel, type, in);
        return mapper.createObjectNode();
    }

    public ObjectNode disassociateManagedNotificationAdditionalChannel(JsonNode in, String region) {
        disassociate(required(in, "managedNotificationConfigurationArn"), required(in, "channelArn"));
        return mapper.createObjectNode();
    }

    public ObjectNode updateManagedNotificationChannelAssociation(JsonNode in, String region) {
        String arn = required(in, "managedNotificationConfigurationArn");
        String channel = required(in, "channelIdentifier");
        managed(arn);
        ObjectNode record = associationRecord(arn);
        ObjectNode target = null;
        for (JsonNode existing : record.path("associations")) {
            if (channel.equals(existing.path("channelIdentifier").asText())) {
                target = (ObjectNode) existing;
            }
        }
        if (target == null && "ACCOUNT_PRIMARY".equals(channel)) {
            target = ((ArrayNode) record.get("associations")).addObject();
            target.put("channelIdentifier", channel);
            target.put("channelType", "ACCOUNT_CONTACT");
            target.put("overrideOption", "DISABLED");
        }
        if (target == null) {
            throw NotificationsStore.notFound("The channel isn't associated with the managed notification "
                    + "configuration.");
        }
        target.put("isSensitiveEventsSubscribed", in.path("isSensitiveEventsSubscribed").asBoolean(false));
        put(NotificationsStore.MANAGED, arn, record);
        return mapper.createObjectNode();
    }

    public ObjectNode listManagedNotificationChannelAssociations(JsonNode in, String region) {
        String arn = required(in, "managedNotificationConfigurationArn");
        managed(arn);
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(associations(arn), in, out, "channelAssociations", 100);
        return out;
    }

    private ObjectNode managedEvent(String arn) {
        ObjectNode event = get(NotificationsStore.EVENT, arn);
        if (event == null || !event.path("managedNotificationConfigurationArn").isTextual()) {
            throw NotificationsStore.notFound("The managed notification event " + arn + " doesn't exist.");
        }
        return event;
    }

    public ObjectNode getManagedNotificationEvent(JsonNode in, String region) {
        ObjectNode event = managedEvent(required(in, "arn"));
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.copy(event, out, "arn", "managedNotificationConfigurationArn", "creationTime", "content");
        return out;
    }

    public ObjectNode getManagedNotificationChildEvent(JsonNode in, String region) {
        return getManagedNotificationEvent(in, region);
    }

    public ObjectNode listManagedNotificationEvents(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode event : list(NotificationsStore.EVENT)) {
            if (event.path("managedNotificationConfigurationArn").isTextual()
                    && !"CHILD".equals(event.path("content").path("aggregationEventType").asText())) {
                ObjectNode overview = mapper.createObjectNode();
                NotificationsStore.copy(event, overview, "arn", "managedNotificationConfigurationArn",
                        "creationTime");
                overview.put("relatedAccount", store.account());
                items.add(overview);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "managedNotificationEvents", 100);
        return out;
    }

    public ObjectNode listManagedNotificationChildEvents(JsonNode in, String region) {
        String aggregate = required(in, "aggregateManagedNotificationEventArn");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode event : list(NotificationsStore.EVENT)) {
            if (aggregate.equals(event.path("content").path("aggregateManagedNotificationEventArn").asText())) {
                ObjectNode overview = mapper.createObjectNode();
                NotificationsStore.copy(event, overview, "arn", "managedNotificationConfigurationArn",
                        "creationTime");
                overview.put("relatedAccount", store.account());
                overview.put("aggregateManagedNotificationEventArn", aggregate);
                items.add(overview);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "managedNotificationChildEvents", 100);
        return out;
    }

    // ================================================================ organizations

    private ObjectNode organization() {
        ObjectNode record = get(NotificationsStore.ORGANIZATION, "access");
        if (record == null) {
            record = store.record();
            record.put("accessStatus", "DISABLED");
        }
        return record;
    }

    public ObjectNode enableNotificationsAccessForOrganization(JsonNode in, String region) {
        ObjectNode record = organization();
        record.put("accessStatus", "ENABLED");
        put(NotificationsStore.ORGANIZATION, "access", record);
        return mapper.createObjectNode();
    }

    public ObjectNode disableNotificationsAccessForOrganization(JsonNode in, String region) {
        ObjectNode record = organization();
        record.put("accessStatus", "DISABLED");
        put(NotificationsStore.ORGANIZATION, "access", record);
        return mapper.createObjectNode();
    }

    public ObjectNode getNotificationsAccessForOrganization(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.putObject("notificationsAccessForOrganization").put("accessStatus", organization().path("accessStatus")
                .asText());
        return out;
    }

    private ArrayNode units(ObjectNode configuration) {
        ObjectNode meta = NotificationsStore.meta(configuration);
        if (!meta.path("units").isArray()) {
            meta.putArray("units");
        }
        return (ArrayNode) meta.get("units");
    }

    public ObjectNode associateOrganizationalUnit(JsonNode in, String region) {
        String unit = required(in, "organizationalUnitId");
        if (!unit.matches("(ou-[0-9a-z]{4,32}-[0-9a-z]{8,32})|(r-[0-9a-z]{4,32})")) {
            throw NotificationsStore.validation("organizationalUnitId must be an organizational unit or root id.");
        }
        if (!"ENABLED".equals(organization().path("accessStatus").asText())) {
            throw NotificationsStore.conflict("Notifications access for the organization isn't enabled.");
        }
        String arn = required(in, "notificationConfigurationArn");
        ObjectNode configuration = configuration(arn);
        ArrayNode units = units(configuration);
        for (JsonNode existing : units) {
            if (unit.equals(existing.asText())) {
                throw NotificationsStore.conflict("The organizational unit is already associated.");
            }
        }
        units.add(unit);
        configuration.put("subtype", "ADMIN_MANAGED");
        put(NotificationsStore.CONFIGURATION, arn, configuration);
        return mapper.createObjectNode();
    }

    public ObjectNode disassociateOrganizationalUnit(JsonNode in, String region) {
        String unit = required(in, "organizationalUnitId");
        String arn = required(in, "notificationConfigurationArn");
        ObjectNode configuration = configuration(arn);
        boolean found = false;
        for (Iterator<JsonNode> each = units(configuration).iterator(); each.hasNext(); ) {
            if (unit.equals(each.next().asText())) {
                each.remove();
                found = true;
            }
        }
        if (!found) {
            throw NotificationsStore.notFound("The organizational unit isn't associated.");
        }
        put(NotificationsStore.CONFIGURATION, arn, configuration);
        return mapper.createObjectNode();
    }

    public ObjectNode listOrganizationalUnits(JsonNode in, String region) {
        ObjectNode configuration = configuration(required(in, "notificationConfigurationArn"));
        List<JsonNode> items = new ArrayList<>();
        units(configuration).forEach(items::add);
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "organizationalUnits", 100);
        return out;
    }

    public ObjectNode listMemberAccounts(JsonNode in, String region) {
        ObjectNode configuration = configuration(required(in, "notificationConfigurationArn"));
        String unitFilter = in.path("organizationalUnitId").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode unit : units(configuration)) {
            if (unitFilter != null && !unitFilter.equals(unit.asText())) {
                continue;
            }
            ObjectNode member = mapper.createObjectNode();
            member.put("notificationConfigurationArn", configuration.path("arn").asText());
            member.put("accountId", store.account());
            member.put("status", "ACTIVE");
            member.put("statusReason", "The configuration applies to the account.");
            member.put("organizationalUnitId", unit.asText());
            items.add(member);
        }
        ObjectNode out = mapper.createObjectNode();
        NotificationsStore.page(items, in, out, "memberAccounts", 100);
        return out;
    }

    // ================================================================ tags

    private Object[] tagged(String arn) {
        for (String kind : List.of(NotificationsStore.CONFIGURATION, NotificationsStore.RULE)) {
            ObjectNode record = get(kind, arn);
            if (record != null) {
                return new Object[] {kind, record};
            }
        }
        throw NotificationsStore.notFound("The resource " + arn + " doesn't exist.");
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String arn = required(in, "arn");
        Object[] tagged = tagged(arn);
        NotificationsStore.addTags((ObjectNode) tagged[1], in.path("tags"));
        put((String) tagged[0], arn, (ObjectNode) tagged[1]);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String arn = required(in, "arn");
        Object[] tagged = tagged(arn);
        in.path("tagKeys").forEach(key -> NotificationsStore.tags((ObjectNode) tagged[1]).remove(key.asText()));
        put((String) tagged[0], arn, (ObjectNode) tagged[1]);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", NotificationsStore.tags((ObjectNode) tagged(required(in, "arn"))[1]).deepCopy());
        return out;
    }

    /** A configuration's channel list. */
    static final class Channels {
        private Channels() {
        }

        static ArrayNode channels(ObjectNode configuration) {
            ObjectNode meta = NotificationsStore.meta(configuration);
            if (!meta.path("channels").isArray()) {
                meta.putArray("channels");
            }
            return (ArrayNode) meta.get("channels");
        }
    }
}
