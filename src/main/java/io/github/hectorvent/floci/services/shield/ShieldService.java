package io.github.hectorvent.floci.services.shield;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.shield.model.ShieldProtection;
import io.github.hectorvent.floci.services.shield.model.ShieldProtectionGroup;
import io.github.hectorvent.floci.services.shield.model.ShieldSubscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS Shield JSON 1.1 ({@code AWSShield_20160616.*}).
 *
 * <p>Accounts start unsubscribed. {@code GetSubscriptionState} reports
 * {@code INACTIVE}; subscription-gated operations fail with the live
 * {@code ResourceNotFoundException} message {@code The subscription does not
 * exist.} so distilled synthesizes {@code SubscriptionNotFound}. Protection
 * group reads report a missing group even without a subscription.
 */
@ApplicationScoped
public class ShieldService implements Resettable {

    static final String SERVICE = "shield";
    static final String SUBSCRIPTION_KEY = "subscription";
    static final long COMMITMENT_SECONDS = 31_536_000L;
    static final String SUBSCRIPTION_MISSING = "The subscription does not exist.";

    private static final TypeReference<Map<String, ShieldSubscription>> SUBSCRIPTION_STORE =
            new TypeReference<Map<String, ShieldSubscription>>() {
            };
    private static final TypeReference<Map<String, ShieldProtection>> PROTECTION_STORE =
            new TypeReference<Map<String, ShieldProtection>>() {
            };
    private static final TypeReference<Map<String, ShieldProtectionGroup>> GROUP_STORE =
            new TypeReference<Map<String, ShieldProtectionGroup>>() {
            };

    private final StorageBackend<String, ShieldSubscription> subscriptions;
    private final StorageBackend<String, ShieldProtection> protections;
    private final StorageBackend<String, ShieldProtectionGroup> groups;
    private final RegionResolver regionResolver;

    @Inject
    public ShieldService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "shield-subscription.json", SUBSCRIPTION_STORE),
                storageFactory.create(SERVICE, "shield-protections.json", PROTECTION_STORE),
                storageFactory.create(SERVICE, "shield-protection-groups.json", GROUP_STORE),
                regionResolver);
    }

    ShieldService(StorageBackend<String, ShieldSubscription> subscriptions,
                  StorageBackend<String, ShieldProtection> protections,
                  StorageBackend<String, ShieldProtectionGroup> groups,
                  RegionResolver regionResolver) {
        this.subscriptions = subscriptions;
        this.protections = protections;
        this.groups = groups;
        this.regionResolver = regionResolver;
    }

    public Optional<ShieldSubscription> findSubscription() {
        return subscriptions.get(SUBSCRIPTION_KEY);
    }

    public String subscriptionState() {
        return findSubscription().isPresent() ? "ACTIVE" : "INACTIVE";
    }

    public ShieldSubscription requireSubscription() {
        return findSubscription().orElseThrow(ShieldService::subscriptionNotFound);
    }

    public synchronized ShieldSubscription createSubscription() {
        Optional<ShieldSubscription> existing = findSubscription();
        if (existing.isPresent()) {
            throw new AwsException(
                    "ResourceAlreadyExistsException",
                    "The subscription already exists.",
                    400);
        }
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plusSeconds(COMMITMENT_SECONDS);
        ShieldSubscription subscription = new ShieldSubscription();
        subscription.setSubscriptionArn("arn:aws:shield::" + accountId() + ":subscription/" + accountId());
        subscription.setAutoRenew("ENABLED");
        subscription.setStartTime(start.getEpochSecond());
        subscription.setEndTime(end.getEpochSecond());
        subscription.setTimeCommitmentInSeconds(COMMITMENT_SECONDS);
        subscription.setProactiveEngagementStatus("DISABLED");
        subscriptions.put(SUBSCRIPTION_KEY, subscription);
        return subscription;
    }

    public synchronized void updateSubscription(JsonNode request) {
        ShieldSubscription subscription = requireSubscription();
        if (request != null && request.hasNonNull("AutoRenew")) {
            subscription.setAutoRenew(request.get("AutoRenew").asText());
            subscriptions.put(SUBSCRIPTION_KEY, subscription);
        }
    }

    public synchronized void deleteSubscription() {
        if (findSubscription().isEmpty()) {
            throw subscriptionNotFound();
        }
        subscriptions.delete(SUBSCRIPTION_KEY);
        protections.clear();
        groups.clear();
    }

    public synchronized ShieldProtection createProtection(JsonNode request) {
        requireSubscription();
        String name = requireText(request, "Name");
        String resourceArn = requireText(request, "ResourceArn");
        for (ShieldProtection existing : protections.values()) {
            if (resourceArn.equals(existing.getResourceArn())) {
                throw new AwsException(
                        "ResourceAlreadyExistsException",
                        "The specified resource is already protected.",
                        400);
            }
        }
        String id = UUID.randomUUID().toString();
        ShieldProtection protection = new ShieldProtection();
        protection.setId(id);
        protection.setName(name);
        protection.setResourceArn(resourceArn);
        protection.setProtectionArn("arn:aws:shield::" + accountId() + ":protection/" + id);
        protection.setTags(readTags(request));
        protections.put(id, protection);
        return protection;
    }

    public ShieldProtection describeProtection(JsonNode request) {
        requireSubscription();
        String protectionId = text(request, "ProtectionId");
        String resourceArn = text(request, "ResourceArn");
        if ((protectionId == null || protectionId.isBlank())
                && (resourceArn == null || resourceArn.isBlank())) {
            throw new AwsException(
                    "InvalidParameterException",
                    "ProtectionId or ResourceArn is a required parameter.",
                    400);
        }
        if (protectionId != null && !protectionId.isBlank()) {
            return protections.get(protectionId).orElseThrow(ShieldService::protectionNotFound);
        }
        return protections.values().stream()
                .filter(p -> resourceArn.equals(p.getResourceArn()))
                .findFirst()
                .orElseThrow(ShieldService::protectionNotFound);
    }

    public List<ShieldProtection> listProtections() {
        requireSubscription();
        return new ArrayList<>(protections.values());
    }

    public synchronized void deleteProtection(String protectionId) {
        requireSubscription();
        if (protectionId == null || protectionId.isBlank() || protections.get(protectionId).isEmpty()) {
            throw protectionNotFound();
        }
        protections.delete(protectionId);
    }

    public ShieldProtectionGroup requireProtectionGroup(String protectionGroupId) {
        if (protectionGroupId == null || protectionGroupId.isBlank()) {
            throw new AwsException(
                    "InvalidParameterException",
                    "ProtectionGroupId is a required parameter.",
                    400);
        }
        return groups.get(protectionGroupId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "The specified protection group does not exist.",
                400));
    }

    public synchronized ShieldProtectionGroup createProtectionGroup(JsonNode request) {
        requireSubscription();
        String id = requireText(request, "ProtectionGroupId");
        if (groups.get(id).isPresent()) {
            throw new AwsException(
                    "ResourceAlreadyExistsException",
                    "The specified protection group already exists.",
                    400);
        }
        ShieldProtectionGroup group = new ShieldProtectionGroup();
        group.setProtectionGroupId(id);
        group.setProtectionGroupArn("arn:aws:shield::" + accountId() + ":protection-group/" + id);
        applyGroupFields(group, request);
        group.setTags(readTags(request));
        groups.put(id, group);
        return group;
    }

    public synchronized ShieldProtectionGroup updateProtectionGroup(JsonNode request) {
        requireSubscription();
        String id = requireText(request, "ProtectionGroupId");
        ShieldProtectionGroup group = requireProtectionGroup(id);
        applyGroupFields(group, request);
        groups.put(id, group);
        return group;
    }

    public List<ShieldProtectionGroup> listProtectionGroups() {
        requireSubscription();
        return new ArrayList<>(groups.values());
    }

    public synchronized void deleteProtectionGroup(String protectionGroupId) {
        requireSubscription();
        requireProtectionGroup(protectionGroupId);
        groups.delete(protectionGroupId);
    }

    public Map<String, String> listTags(String resourceArn) {
        return new LinkedHashMap<>(taggable(resourceArn).tags());
    }

    public synchronized void tagResource(String resourceArn, JsonNode request) {
        Taggable taggable = taggable(resourceArn);
        taggable.tags().putAll(readTags(request));
        taggable.persist().run();
    }

    public synchronized void untagResource(String resourceArn, JsonNode request) {
        Taggable taggable = taggable(resourceArn);
        for (String key : readStringList(request, "TagKeys")) {
            taggable.tags().remove(key);
        }
        taggable.persist().run();
    }

    public String requireAttackId(JsonNode request) {
        return requireText(request, "AttackId");
    }

    @Override
    public void clear() {
        subscriptions.clear();
        protections.clear();
        groups.clear();
    }

    private void applyGroupFields(ShieldProtectionGroup group, JsonNode request) {
        group.setAggregation(requireText(request, "Aggregation"));
        group.setPattern(requireText(request, "Pattern"));
        if (request != null && request.hasNonNull("ResourceType")) {
            group.setResourceType(request.get("ResourceType").asText());
        } else {
            group.setResourceType(null);
        }
        group.setMembers(readStringList(request, "Members"));
    }

    private Taggable taggable(String resourceArn) {
        requireSubscription();
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException(
                    "InvalidParameterException",
                    "ResourceARN is a required parameter.",
                    400);
        }
        Optional<ShieldProtection> protection = protections.values().stream()
                .filter(p -> resourceArn.equals(p.getProtectionArn()))
                .findFirst();
        if (protection.isPresent()) {
            ShieldProtection value = protection.get();
            return new Taggable(value.getTags(), () -> protections.put(value.getId(), value));
        }
        Optional<ShieldProtectionGroup> group = groups.values().stream()
                .filter(g -> resourceArn.equals(g.getProtectionGroupArn()))
                .findFirst();
        if (group.isPresent()) {
            ShieldProtectionGroup value = group.get();
            return new Taggable(value.getTags(), () -> groups.put(value.getProtectionGroupId(), value));
        }
        throw new AwsException(
                "ResourceNotFoundException",
                "The requested resource does not exist.",
                400);
    }

    private static List<String> readStringList(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        if (request == null || !request.has(field) || !request.get(field).isArray()) {
            return values;
        }
        for (JsonNode node : request.get(field)) {
            if (node != null && node.isTextual()) {
                values.add(node.asText());
            }
        }
        return values;
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null || !request.has("Tags") || !request.get("Tags").isArray()) {
            return tags;
        }
        for (JsonNode tag : request.get("Tags")) {
            if (tag != null && tag.hasNonNull("Key")) {
                String value = tag.hasNonNull("Value") ? tag.get("Value").asText() : "";
                tags.put(tag.get("Key").asText(), value);
            }
        }
        return tags;
    }

    private static String text(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        return request.get(field).asText();
    }

    private static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException(
                    "InvalidParameterException",
                    field + " is a required parameter.",
                    400);
        }
        return value;
    }

    static AwsException subscriptionNotFound() {
        return new AwsException("ResourceNotFoundException", SUBSCRIPTION_MISSING, 400);
    }

    static AwsException protectionNotFound() {
        return new AwsException(
                "ResourceNotFoundException",
                "The requested protection does not exist.",
                400);
    }

    static AwsException invalidOperationNoSubscription() {
        return new AwsException(
                "InvalidOperationException",
                SUBSCRIPTION_MISSING,
                400);
    }

    static AwsException drtAccessNotFound() {
        return new AwsException(
                "ResourceNotFoundException",
                "The referenced item does not exist.",
                400);
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private record Taggable(Map<String, String> tags, Runnable persist) {
    }
}
