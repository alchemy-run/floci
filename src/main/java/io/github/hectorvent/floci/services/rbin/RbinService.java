package io.github.hectorvent.floci.services.rbin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rbin.model.RetentionRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Recycle Bin restJson1 — retention rules for EBS snapshots, AMIs, and volumes.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code rbin}.
 */
@ApplicationScoped
public class RbinService implements TagHandler {

    static final String SERVICE = "rbin";
    private static final Set<String> RESOURCE_TYPES = Set.of("EBS_SNAPSHOT", "EC2_IMAGE", "EBS_VOLUME");
    private static final Set<String> LOCK_STATES = Set.of("locked", "pending_unlock", "unlocked");

    private final StorageBackend<String, RetentionRule> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RbinService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("rbin", "rbin-rules.json",
                        new TypeReference<Map<String, RetentionRule>>() {
                        }),
                regionResolver, objectMapper);
    }

    RbinService(StorageBackend<String, RetentionRule> store, RegionResolver regionResolver,
                ObjectMapper objectMapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized RetentionRule createRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        String resourceType = requireResourceType(request.get("ResourceType"), true);
        JsonNode retentionPeriod = requireRetentionPeriod(request.get("RetentionPeriod"));
        JsonNode resourceTags = readResourceTags(request.get("ResourceTags"), "ResourceTags");
        JsonNode excludeResourceTags = readResourceTags(request.get("ExcludeResourceTags"), "ExcludeResourceTags");
        if (hasTags(resourceTags) && hasTags(excludeResourceTags)) {
            throw validation("Specify ResourceTags or ExcludeResourceTags, not both.");
        }
        JsonNode lockConfiguration = readLockConfiguration(request.get("LockConfiguration"));
        if (lockConfiguration != null && (hasTags(resourceTags) || hasTags(excludeResourceTags))) {
            throw validation("LockConfiguration is only supported on Region-level retention rules.");
        }
        String description = optionalText(request, "Description");
        Map<String, String> tags = readTagList(request.get("Tags"));

        String identifier = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();

        RetentionRule rule = new RetentionRule();
        rule.setIdentifier(identifier);
        rule.setDescription(description);
        rule.setResourceType(resourceType);
        rule.setRetentionPeriod(retentionPeriod);
        rule.setResourceTags(hasTags(resourceTags) ? resourceTags : null);
        rule.setExcludeResourceTags(hasTags(excludeResourceTags) ? excludeResourceTags : null);
        rule.setStatus("available");
        if (lockConfiguration != null) {
            rule.setLockConfiguration(lockConfiguration);
            rule.setLockState("locked");
        }
        rule.setTags(tags);
        rule.setRuleArn(arn(region, account, identifier));
        store.put(storageKey(region, identifier), rule);
        return rule;
    }

    public RetentionRule getRule(String region, String identifier) {
        return requireRule(region, identifier);
    }

    public List<RetentionRule> listRules(String region, JsonNode request) {
        requireObject(request, "Request body");
        String resourceType = requireResourceType(request.get("ResourceType"), true);
        JsonNode resourceTags = readResourceTags(request.get("ResourceTags"), "ResourceTags");
        JsonNode excludeResourceTags = readResourceTags(request.get("ExcludeResourceTags"), "ExcludeResourceTags");
        String lockState = optionalLockState(request.get("LockState"));

        List<RetentionRule> rules = store.scan(key -> key.startsWith(region + "::"));
        rules.sort(Comparator.comparing(RetentionRule::getIdentifier, Comparator.nullsLast(String::compareTo)));
        List<RetentionRule> matched = new ArrayList<>();
        for (RetentionRule rule : rules) {
            if (!resourceType.equals(rule.getResourceType())) {
                continue;
            }
            if (lockState != null && !lockState.equals(rule.getLockState())) {
                continue;
            }
            if (!resourceTagsMatch(rule.getResourceTags(), resourceTags)) {
                continue;
            }
            if (!resourceTagsMatch(rule.getExcludeResourceTags(), excludeResourceTags)) {
                continue;
            }
            matched.add(rule);
        }
        return matched;
    }

    public synchronized RetentionRule updateRule(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        RetentionRule current = requireRule(region, identifier);
        rejectIfLocked(current);
        if (request.has("ResourceType") && !request.get("ResourceType").isNull()) {
            String resourceType = requireResourceType(request.get("ResourceType"), true);
            if (!resourceType.equals(current.getResourceType())) {
                throw validation("ResourceType cannot be changed after creation.");
            }
        }
        if (request.has("RetentionPeriod") && !request.get("RetentionPeriod").isNull()) {
            current.setRetentionPeriod(requireRetentionPeriod(request.get("RetentionPeriod")));
        }
        if (request.has("Description")) {
            JsonNode description = request.get("Description");
            current.setDescription(description == null || description.isNull() ? null : description.asText());
        }
        if (request.has("ResourceTags")) {
            JsonNode resourceTags = readResourceTags(request.get("ResourceTags"), "ResourceTags");
            current.setResourceTags(hasTags(resourceTags) ? resourceTags : null);
        }
        if (request.has("ExcludeResourceTags")) {
            JsonNode excludeResourceTags = readResourceTags(request.get("ExcludeResourceTags"), "ExcludeResourceTags");
            current.setExcludeResourceTags(hasTags(excludeResourceTags) ? excludeResourceTags : null);
        }
        if (hasTags(current.getResourceTags()) && hasTags(current.getExcludeResourceTags())) {
            throw validation("Specify ResourceTags or ExcludeResourceTags, not both.");
        }
        store.put(storageKey(region, current.getIdentifier()), current);
        return current;
    }

    public synchronized void deleteRule(String region, String identifier) {
        RetentionRule rule = requireRule(region, identifier);
        rejectIfLocked(rule);
        store.delete(storageKey(region, rule.getIdentifier()));
    }

    public synchronized RetentionRule lockRule(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        RetentionRule current = requireRule(region, identifier);
        if (hasTags(current.getResourceTags()) || hasTags(current.getExcludeResourceTags())) {
            throw validation("LockConfiguration is only supported on Region-level retention rules.");
        }
        JsonNode lockConfiguration = readLockConfiguration(request.get("LockConfiguration"));
        if (lockConfiguration == null) {
            throw validation("LockConfiguration is required.");
        }
        current.setLockConfiguration(lockConfiguration);
        current.setLockState("locked");
        current.setLockEndTime(null);
        store.put(storageKey(region, current.getIdentifier()), current);
        return current;
    }

    public synchronized RetentionRule unlockRule(String region, String identifier) {
        RetentionRule current = requireRule(region, identifier);
        if (!"locked".equals(current.getLockState())) {
            throw conflict("The retention rule is not locked.");
        }
        current.setLockState("pending_unlock");
        store.put(storageKey(region, current.getIdentifier()), current);
        return current;
    }

    public ObjectNode toRule(RetentionRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "Identifier", rule.getIdentifier());
        putText(node, "Description", rule.getDescription());
        putText(node, "ResourceType", rule.getResourceType());
        if (rule.getRetentionPeriod() != null) {
            node.set("RetentionPeriod", rule.getRetentionPeriod());
        }
        if (hasTags(rule.getResourceTags())) {
            node.set("ResourceTags", rule.getResourceTags());
        }
        if (hasTags(rule.getExcludeResourceTags())) {
            node.set("ExcludeResourceTags", rule.getExcludeResourceTags());
        }
        putText(node, "Status", rule.getStatus());
        if (rule.getLockConfiguration() != null) {
            node.set("LockConfiguration", rule.getLockConfiguration());
        }
        putText(node, "LockState", rule.getLockState());
        if (rule.getLockEndTime() != null) {
            node.put("LockEndTime", rule.getLockEndTime());
        }
        putText(node, "RuleArn", rule.getRuleArn());
        return node;
    }

    public ObjectNode toCreateRule(RetentionRule rule) {
        ObjectNode node = toRule(rule);
        node.set("Tags", tagsList(rule.getTags()));
        return node;
    }

    public ObjectNode toSummary(RetentionRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "Identifier", rule.getIdentifier());
        putText(node, "Description", rule.getDescription());
        if (rule.getRetentionPeriod() != null) {
            node.set("RetentionPeriod", rule.getRetentionPeriod());
        }
        putText(node, "LockState", rule.getLockState());
        putText(node, "RuleArn", rule.getRuleArn());
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireRuleByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        RetentionRule rule = requireRuleByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(rule.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        rule.setTags(current);
        store.put(storageKey(region, rule.getIdentifier()), rule);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        RetentionRule rule = requireRuleByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(rule.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        rule.setTags(current);
        store.put(storageKey(region, rule.getIdentifier()), rule);
    }

    private RetentionRule requireRule(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw validation("Identifier is required.");
        }
        String decoded = decode(identifier);
        return store.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private RetentionRule requireRuleByArn(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN: " + decoded);
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("rule/")) {
            throw notFound(decoded);
        }
        String identifier = parsed.resource().substring("rule/".length());
        RetentionRule rule = requireRule(region, identifier);
        if (decoded.startsWith("arn:") && rule.getRuleArn() != null && !decoded.equals(rule.getRuleArn())) {
            throw notFound(decoded);
        }
        return rule;
    }

    private static void rejectIfLocked(RetentionRule rule) {
        String lockState = rule.getLockState();
        if ("locked".equals(lockState) || "pending_unlock".equals(lockState)) {
            throw conflict("The retention rule is locked.");
        }
    }

    private static boolean resourceTagsMatch(JsonNode ruleTags, JsonNode filter) {
        if (filter == null || !filter.isArray() || filter.isEmpty()) {
            return true;
        }
        if (ruleTags == null || !ruleTags.isArray()) {
            return false;
        }
        for (JsonNode wanted : filter) {
            String key = textOrNull(wanted, "ResourceTagKey");
            String value = textOrNull(wanted, "ResourceTagValue");
            boolean found = false;
            for (JsonNode have : ruleTags) {
                if (key != null && key.equals(textOrNull(have, "ResourceTagKey"))
                        && (value == null || value.equals(textOrNull(have, "ResourceTagValue")))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static String requireResourceType(JsonNode value, boolean required) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            if (!required) {
                return null;
            }
            throw validation("ResourceType is required.");
        }
        String resourceType = value.textValue();
        if (!RESOURCE_TYPES.contains(resourceType)) {
            throw validation("ResourceType must be EBS_SNAPSHOT, EC2_IMAGE, or EBS_VOLUME.");
        }
        return resourceType;
    }

    private JsonNode requireRetentionPeriod(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw validation("RetentionPeriod is required.");
        }
        JsonNode amount = value.get("RetentionPeriodValue");
        JsonNode unit = value.get("RetentionPeriodUnit");
        if (amount == null || !amount.isNumber()) {
            throw validation("RetentionPeriod.RetentionPeriodValue is required.");
        }
        if (unit == null || !unit.isTextual() || !"DAYS".equals(unit.textValue())) {
            throw validation("RetentionPeriod.RetentionPeriodUnit must be DAYS.");
        }
        int days = amount.intValue();
        if (days < 1 || days > 365) {
            throw validation("RetentionPeriodValue must be between 1 and 365.");
        }
        ObjectNode copy = objectMapper.createObjectNode();
        copy.put("RetentionPeriodValue", days);
        copy.put("RetentionPeriodUnit", "DAYS");
        return copy;
    }

    private JsonNode readLockConfiguration(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw validation("LockConfiguration must be a JSON object.");
        }
        JsonNode delay = value.get("UnlockDelay");
        if (delay == null || !delay.isObject()) {
            throw validation("LockConfiguration.UnlockDelay is required.");
        }
        JsonNode amount = delay.get("UnlockDelayValue");
        JsonNode unit = delay.get("UnlockDelayUnit");
        if (amount == null || !amount.isNumber()) {
            throw validation("UnlockDelay.UnlockDelayValue is required.");
        }
        if (unit == null || !unit.isTextual() || !"DAYS".equals(unit.textValue())) {
            throw validation("UnlockDelay.UnlockDelayUnit must be DAYS.");
        }
        int days = amount.intValue();
        if (days < 7 || days > 30) {
            throw validation("UnlockDelayValue must be between 7 and 30.");
        }
        ObjectNode unlockDelay = objectMapper.createObjectNode();
        unlockDelay.put("UnlockDelayValue", days);
        unlockDelay.put("UnlockDelayUnit", "DAYS");
        ObjectNode copy = objectMapper.createObjectNode();
        copy.set("UnlockDelay", unlockDelay);
        return copy;
    }

    private static JsonNode readResourceTags(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw validation(field + " must be a list.");
        }
        for (JsonNode entry : value) {
            if (entry == null || !entry.isObject() || textOrNull(entry, "ResourceTagKey") == null) {
                throw validation(field + " entries must include ResourceTagKey.");
            }
        }
        return value.deepCopy();
    }

    private static Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw validation("Tags must be a list.");
        }
        for (JsonNode entry : tagsNode) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            JsonNode key = entry.get("Key");
            JsonNode value = entry.get("Value");
            if (key == null || !key.isTextual() || value == null || !value.isTextual()) {
                throw validation("Tags entries must have Key and Value.");
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private ArrayNode tagsList(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags != null) {
            tags.forEach((key, value) -> {
                ObjectNode entry = array.addObject();
                entry.put("Key", key);
                entry.put("Value", value);
            });
        }
        return array;
    }

    private static boolean hasTags(JsonNode tags) {
        return tags != null && tags.isArray() && !tags.isEmpty();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalLockState(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || !LOCK_STATES.contains(value.textValue())) {
            throw validation("LockState must be locked, pending_unlock, or unlocked.");
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String arn(String region, String account, String identifier) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "rule/" + identifier).toString();
    }

    private static String storageKey(String region, String identifier) {
        return region + "::" + identifier;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException validation(String message) {
        return new AwsException(
                "ValidationException",
                message,
                400,
                Map.of("Reason", "INVALID_PARAMETER_VALUE"));
    }

    private static AwsException conflict(String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("Reason", "INVALID_RULE_STATE"));
    }

    private static AwsException notFound(String identifier) {
        return new AwsException(
                "ResourceNotFoundException",
                "Rule " + identifier + " not found.",
                404,
                Map.of("Reason", "RULE_NOT_FOUND"));
    }
}
