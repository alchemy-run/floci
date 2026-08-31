package io.github.hectorvent.floci.services.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.notifications.model.EventRule;
import io.github.hectorvent.floci.services.notifications.model.EventRuleStatusSummary;
import io.github.hectorvent.floci.services.notifications.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.notifications.model.NotificationHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS User Notifications restJson1. Configurations, event rules, channel
 * associations, and hubs are account-scoped (the control plane is global).
 */
@ApplicationScoped
public class NotificationsService implements TagHandler {

    static final String SERVICE = "notifications";
    private static final int MAX_HUBS = 3;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> AGGREGATION = Set.of("LONG", "SHORT", "NONE");
    private static final List<ManagedNotificationConfiguration> MANAGED_CONFIGS = List.of(
            managed("AWS-Health", "Account-Specific", "AWS Health Account-Specific",
                    "Notifications for account-specific AWS Health events."),
            managed("AWS-Health", "Public", "AWS Health Public",
                    "Notifications for public AWS Health events."),
            managed("AWS-Health", "Security", "AWS Health Security",
                    "Notifications for AWS Health security events."));

    private final StorageBackend<String, NotificationConfiguration> configurations;
    private final StorageBackend<String, EventRule> eventRules;
    private final StorageBackend<String, NotificationHub> hubs;
    private final RegionResolver regionResolver;

    @Inject
    public NotificationsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("notifications", "notifications-configurations.json",
                        new TypeReference<Map<String, NotificationConfiguration>>() {
                        }),
                storageFactory.create("notifications", "notifications-event-rules.json",
                        new TypeReference<Map<String, EventRule>>() {
                        }),
                storageFactory.create("notifications", "notifications-hubs.json",
                        new TypeReference<Map<String, NotificationHub>>() {
                        }),
                regionResolver);
    }

    NotificationsService(
            StorageBackend<String, NotificationConfiguration> configurations,
            StorageBackend<String, EventRule> eventRules,
            StorageBackend<String, NotificationHub> hubs,
            RegionResolver regionResolver) {
        this.configurations = configurations;
        this.eventRules = eventRules;
        this.hubs = hubs;
        this.regionResolver = regionResolver;
    }

    public synchronized NotificationConfiguration createConfiguration(JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String description = requireText(request, "description");
        String aggregation = optionalText(request, "aggregationDuration", "NONE");
        validateAggregation(aggregation);
        String account = regionResolver.getAccountId();
        if (findByName(account, name) != null) {
            throw conflict(name, "Notification configuration " + name + " already exists.");
        }
        String id = UUID.randomUUID().toString();
        NotificationConfiguration config = new NotificationConfiguration();
        config.setId(id);
        config.setAccountId(account);
        config.setName(name);
        config.setDescription(description);
        config.setAggregationDuration(aggregation);
        config.setCreationTime(now());
        config.setArn(configArn(account, id));
        config.setTags(readTags(request.get("tags")));
        configurations.put(config.getArn(), config);
        return config;
    }

    public NotificationConfiguration getConfiguration(String arn) {
        return requireConfiguration(arn);
    }

    public synchronized NotificationConfiguration updateConfiguration(String arn, JsonNode request) {
        requireObject(request, "Request body");
        NotificationConfiguration config = requireConfiguration(arn);
        if (request.hasNonNull("name")) {
            String name = requireText(request, "name");
            validateName(name);
            NotificationConfiguration existing = findByName(config.getAccountId(), name);
            if (existing != null && !existing.getArn().equals(config.getArn())) {
                throw conflict(name, "Notification configuration " + name + " already exists.");
            }
            config.setName(name);
        }
        if (request.hasNonNull("description")) {
            config.setDescription(requireText(request, "description"));
        }
        if (request.hasNonNull("aggregationDuration")) {
            String aggregation = requireText(request, "aggregationDuration");
            validateAggregation(aggregation);
            config.setAggregationDuration(aggregation);
        }
        configurations.put(config.getArn(), config);
        return config;
    }

    public synchronized void deleteConfiguration(String arn) {
        NotificationConfiguration config = requireConfiguration(arn);
        for (EventRule rule : eventRules.scan(key -> true)) {
            if (config.getArn().equals(rule.getNotificationConfigurationArn())) {
                eventRules.delete(rule.getArn());
            }
        }
        configurations.delete(config.getArn());
    }

    public List<NotificationConfiguration> listConfigurations() {
        String account = regionResolver.getAccountId();
        List<NotificationConfiguration> items = configurations.scan(key -> true);
        items.removeIf(config -> !account.equals(config.getAccountId()));
        items.sort(Comparator.comparing(NotificationConfiguration::getCreationTime,
                Comparator.nullsLast(String::compareTo)).reversed());
        return items;
    }

    public String configurationStatus(NotificationConfiguration config) {
        boolean hasRules = eventRules.scan(key -> true).stream()
                .anyMatch(rule -> config.getArn().equals(rule.getNotificationConfigurationArn()));
        boolean hasChannels = config.getChannels() != null && !config.getChannels().isEmpty();
        return hasRules || hasChannels ? "ACTIVE" : "INACTIVE";
    }

    public synchronized EventRule createEventRule(JsonNode request) {
        requireObject(request, "Request body");
        String configArn = requireText(request, "notificationConfigurationArn");
        NotificationConfiguration config = requireConfiguration(configArn);
        String source = requireText(request, "source");
        String eventType = requireText(request, "eventType");
        String eventPattern = optionalText(request, "eventPattern", "");
        List<String> regions = requireStringList(request, "regions");
        String account = config.getAccountId();
        String id = UUID.randomUUID().toString();
        EventRule rule = new EventRule();
        rule.setId(id);
        rule.setAccountId(account);
        rule.setNotificationConfigurationArn(config.getArn());
        rule.setSource(source);
        rule.setEventType(eventType);
        rule.setEventPattern(eventPattern);
        rule.setCreationTime(now());
        rule.setArn(ruleArn(config.getArn(), id));
        applyRegions(rule, regions, account);
        eventRules.put(rule.getArn(), rule);
        return rule;
    }

    public EventRule getEventRule(String arn) {
        return requireEventRule(arn);
    }

    public synchronized EventRule updateEventRule(String arn, JsonNode request) {
        requireObject(request, "Request body");
        EventRule rule = requireEventRule(arn);
        if (request.has("eventPattern") && !request.get("eventPattern").isNull()) {
            JsonNode pattern = request.get("eventPattern");
            if (!pattern.isTextual()) {
                throw validation("eventPattern must be a string.");
            }
            rule.setEventPattern(pattern.asText());
        }
        if (request.has("regions") && !request.get("regions").isNull()) {
            applyRegions(rule, requireStringList(request, "regions"), rule.getAccountId());
        }
        eventRules.put(rule.getArn(), rule);
        return rule;
    }

    public synchronized void deleteEventRule(String arn) {
        EventRule rule = requireEventRule(arn);
        eventRules.delete(rule.getArn());
    }

    public List<EventRule> listEventRules(String notificationConfigurationArn) {
        if (notificationConfigurationArn == null || notificationConfigurationArn.isBlank()) {
            throw validation("notificationConfigurationArn is required.");
        }
        NotificationConfiguration config = requireConfiguration(notificationConfigurationArn);
        List<EventRule> items = eventRules.scan(key -> true);
        items.removeIf(rule -> !config.getArn().equals(rule.getNotificationConfigurationArn()));
        items.sort(Comparator.comparing(EventRule::getCreationTime, Comparator.nullsLast(String::compareTo)).reversed());
        return items;
    }

    public synchronized void associateChannel(String channelArn, JsonNode request) {
        requireObject(request, "Request body");
        String configArn = requireText(request, "notificationConfigurationArn");
        String decodedChannel = decode(channelArn);
        NotificationConfiguration config = requireConfiguration(configArn);
        List<String> channels = config.getChannels();
        if (channels.contains(decodedChannel)) {
            throw conflict(decodedChannel, "Channel " + decodedChannel + " is already associated.");
        }
        channels.add(decodedChannel);
        config.setChannels(channels);
        configurations.put(config.getArn(), config);
    }

    public synchronized void disassociateChannel(String channelArn, JsonNode request) {
        requireObject(request, "Request body");
        String configArn = requireText(request, "notificationConfigurationArn");
        String decodedChannel = decode(channelArn);
        NotificationConfiguration config = requireConfiguration(configArn);
        List<String> channels = config.getChannels();
        if (!channels.remove(decodedChannel)) {
            throw resourceNotFound(decodedChannel, "Channel " + decodedChannel + " is not associated.");
        }
        config.setChannels(channels);
        configurations.put(config.getArn(), config);
    }

    public List<String> listChannels(String notificationConfigurationArn) {
        if (notificationConfigurationArn == null || notificationConfigurationArn.isBlank()) {
            throw validation("notificationConfigurationArn is required.");
        }
        return new ArrayList<>(requireConfiguration(notificationConfigurationArn).getChannels());
    }

    public List<ManagedNotificationConfiguration> listManagedConfigurations() {
        String account = regionResolver.getAccountId();
        List<ManagedNotificationConfiguration> items = new ArrayList<>();
        for (ManagedNotificationConfiguration template : MANAGED_CONFIGS) {
            items.add(template.forAccount(account));
        }
        return items;
    }

    public ManagedNotificationConfiguration getManagedConfiguration(String arn) {
        String decoded = decode(arn);
        String account = accountFromArn(decoded);
        for (ManagedNotificationConfiguration template : MANAGED_CONFIGS) {
            ManagedNotificationConfiguration candidate = template.forAccount(account);
            if (candidate.arn().equals(decoded)) {
                return candidate;
            }
        }
        throw resourceNotFound(decoded, "Managed notification configuration " + decoded + " does not exist.");
    }

    public List<ManagedNotificationChannelAssociation> listManagedChannelAssociations(String managedConfigurationArn) {
        if (managedConfigurationArn == null || managedConfigurationArn.isBlank()) {
            throw validation("managedNotificationConfigurationArn is required.");
        }
        getManagedConfiguration(managedConfigurationArn);
        return List.of();
    }

    public void requireNotificationEvent(String arn) {
        String decoded = decode(arn);
        throw resourceNotFound(decoded, "Notification event " + decoded + " does not exist.");
    }

    public void requireManagedEvent(String arn) {
        String decoded = decode(arn);
        throw resourceNotFound(decoded, "Managed notification event " + decoded + " does not exist.");
    }

    public void requireManagedChildEvent(String arn) {
        String decoded = decode(arn);
        throw resourceNotFound(decoded, "Managed notification child event " + decoded + " does not exist.");
    }

    public synchronized NotificationHub registerHub(JsonNode request) {
        requireObject(request, "Request body");
        String hubRegion = requireText(request, "notificationHubRegion");
        String account = regionResolver.getAccountId();
        String key = hubKey(account, hubRegion);
        NotificationHub existing = hubs.get(key).orElse(null);
        if (existing != null) {
            return existing;
        }
        List<NotificationHub> current = hubsForAccount(account);
        if (current.size() >= MAX_HUBS) {
            throw new AwsException(
                    "ServiceQuotaExceededException",
                    "An account may register at most " + MAX_HUBS + " notification hubs.",
                    402,
                    Map.of("resourceId", hubRegion, "resourceType", "NotificationHub"));
        }
        String timestamp = now();
        NotificationHub hub = new NotificationHub();
        hub.setAccountId(account);
        hub.setNotificationHubRegion(hubRegion);
        hub.setStatus("ACTIVE");
        hub.setStatusReason("Registration completed");
        hub.setCreationTime(timestamp);
        hub.setLastActivationTime(timestamp);
        hubs.put(key, hub);
        return hub;
    }

    public List<NotificationHub> listHubs() {
        List<NotificationHub> items = hubsForAccount(regionResolver.getAccountId());
        items.sort(Comparator.comparing(NotificationHub::getCreationTime, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized NotificationHub deregisterHub(String notificationHubRegion) {
        if (notificationHubRegion == null || notificationHubRegion.isBlank()) {
            throw validation("notificationHubRegion is required.");
        }
        String account = regionResolver.getAccountId();
        String key = hubKey(account, notificationHubRegion);
        NotificationHub hub = hubs.get(key).orElseThrow(
                () -> resourceNotFound(notificationHubRegion,
                        "Notification hub " + notificationHubRegion + " does not exist."));
        List<NotificationHub> active = hubsForAccount(account).stream()
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .toList();
        if (active.size() == 1 && hub.getNotificationHubRegion().equals(active.getFirst().getNotificationHubRegion())) {
            throw conflict(notificationHubRegion, "Cannot deregister last ACTIVE notification hub");
        }
        hubs.delete(key);
        hub.setStatus("INACTIVE");
        hub.setStatusReason("Deregistered");
        return hub;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        NotificationConfiguration config = requireConfiguration(arn);
        return config.getTags() == null ? Map.of() : Map.copyOf(config.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        NotificationConfiguration config = requireConfiguration(arn);
        Map<String, String> current = config.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(config.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        config.setTags(current);
        configurations.put(config.getArn(), config);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        NotificationConfiguration config = requireConfiguration(arn);
        if (config.getTags() != null && tagKeys != null) {
            tagKeys.forEach(config.getTags()::remove);
        }
        configurations.put(config.getArn(), config);
    }

    private NotificationConfiguration requireConfiguration(String arn) {
        String decoded = decode(arn);
        NotificationConfiguration config = configurations.get(decoded).orElse(null);
        if (config == null) {
            throw resourceNotFound(decoded, "Notification configuration " + decoded + " does not exist.");
        }
        return config;
    }

    private EventRule requireEventRule(String arn) {
        String decoded = decode(arn);
        return eventRules.get(decoded).orElseThrow(
                () -> resourceNotFound(decoded, "Event rule " + decoded + " does not exist."));
    }

    private NotificationConfiguration findByName(String account, String name) {
        for (NotificationConfiguration config : configurations.scan(key -> true)) {
            if (account.equals(config.getAccountId()) && name.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }

    private List<NotificationHub> hubsForAccount(String account) {
        List<NotificationHub> items = hubs.scan(key -> true);
        items.removeIf(hub -> !account.equals(hub.getAccountId()));
        return items;
    }

    private void applyRegions(EventRule rule, List<String> regions, String account) {
        if (regions.isEmpty()) {
            throw validation("regions must not be empty.");
        }
        Map<String, EventRuleStatusSummary> summaries = new LinkedHashMap<>();
        List<String> managed = new ArrayList<>();
        for (String region : regions) {
            summaries.put(region, new EventRuleStatusSummary("ACTIVE", "OK"));
            managed.add("arn:aws:events:" + region + ":" + account + ":rule/AWSUserNotifications-" + rule.getId());
        }
        rule.setRegions(regions);
        rule.setStatusSummaryByRegion(summaries);
        rule.setManagedRules(managed);
    }

    private static ManagedNotificationConfiguration managed(
            String category, String subCategory, String name, String description) {
        return new ManagedNotificationConfiguration("", name, description, category, subCategory);
    }

    private static String accountFromArn(String arn) {
        try {
            String account = AwsArnUtils.parse(arn).accountId();
            return account == null || account.isBlank() ? "000000000000" : account;
        } catch (IllegalArgumentException e) {
            throw resourceNotFound(arn, "Resource " + arn + " does not exist.");
        }
    }

    private static String configArn(String account, String id) {
        return AwsArnUtils.Arn.of(SERVICE, "", account, "configuration/" + id).toString();
    }

    private static String ruleArn(String configurationArn, String id) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(configurationArn);
        return AwsArnUtils.Arn.of(SERVICE, "", parsed.accountId(), parsed.resource() + "/rule/" + id).toString();
    }

    private static String hubKey(String account, String region) {
        return account + "::" + region;
    }

    private static Map<String, String> readTags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || tags.isNull()) {
            return result;
        }
        if (!tags.isObject()) {
            throw validation("tags must be an object.");
        }
        tags.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return result;
    }

    private static List<String> requireStringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            throw validation(field + " is required.");
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.asText());
        }
        return values;
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw validation(field + " is required.");
        }
        return node.asText();
    }

    private static String optionalText(JsonNode request, String field, String fallback) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return fallback;
        }
        return node.asText();
    }

    private static void validateName(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 1-64 letters, numbers, underscores or hyphens.");
        }
    }

    private static void validateAggregation(String aggregation) {
        if (!AGGREGATION.contains(aggregation)) {
            throw validation("aggregationDuration must be LONG, SHORT, or NONE.");
        }
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    static AwsException resourceNotFound(String resourceId, String message) {
        return new AwsException("ResourceNotFoundException", message, 404, Map.of("resourceId", resourceId));
    }

    private static AwsException conflict(String resourceId, String message) {
        return new AwsException("ConflictException", message, 409, Map.of("resourceId", resourceId));
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", "fieldValidationFailed"));
    }

    public record ManagedNotificationConfiguration(
            String arn, String name, String description, String category, String subCategory) {
        ManagedNotificationConfiguration forAccount(String account) {
            String resolved = "arn:aws:notifications::" + account
                    + ":managed-notification-configuration/category/" + category
                    + "/subcategory/" + subCategory;
            return new ManagedNotificationConfiguration(resolved, name, description, category, subCategory);
        }
    }

    public record ManagedNotificationChannelAssociation(
            String channelIdentifier, String channelType, String overrideOption) {
    }
}
