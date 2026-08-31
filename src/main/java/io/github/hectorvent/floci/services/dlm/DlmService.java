package io.github.hectorvent.floci.services.dlm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dlm.model.LifecyclePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Data Lifecycle Manager restJson1 — custom EBS snapshot / AMI policies.
 *
 * <p>Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}
 * using ARN service {@code dlm}.
 */
@ApplicationScoped
public class DlmService implements TagHandler {

    static final String SERVICE = "dlm";
    private static final Set<String> STATES = Set.of("ENABLED", "DISABLED");
    private static final String DEFAULT_POLICY_TYPE = "EBS_SNAPSHOT_MANAGEMENT";

    private final StorageBackend<String, LifecyclePolicy> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DlmService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("dlm", "dlm-policies.json",
                        new TypeReference<Map<String, LifecyclePolicy>>() {
                        }),
                regionResolver, objectMapper);
    }

    DlmService(StorageBackend<String, LifecyclePolicy> store, RegionResolver regionResolver,
               ObjectMapper objectMapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized LifecyclePolicy createLifecyclePolicy(String region, JsonNode request) {
        requireObject(request, "Request body");
        String executionRoleArn = requireText(request, "ExecutionRoleArn");
        String description = requireText(request, "Description");
        if (description.length() > 500) {
            throw invalid("Description must be at most 500 characters.");
        }
        String state = requireState(request.get("State"), true);
        JsonNode details = requireObjectField(request, "PolicyDetails");
        Map<String, String> tags = readTags(request.get("Tags"));

        String policyId = newPolicyId();
        String now = Instant.now().toString();
        String account = regionResolver.getAccountId();

        LifecyclePolicy policy = new LifecyclePolicy();
        policy.setPolicyId(policyId);
        policy.setDescription(description);
        policy.setState(state);
        policy.setExecutionRoleArn(executionRoleArn);
        policy.setDateCreated(now);
        policy.setDateModified(now);
        policy.setPolicyDetails(normalizeDetails(details));
        policy.setTags(tags);
        policy.setPolicyArn(arn(region, account, policyId));
        policy.setDefaultPolicy(false);
        store.put(storageKey(region, policyId), policy);
        return policy;
    }

    public LifecyclePolicy getLifecyclePolicy(String region, String policyId) {
        return requirePolicy(region, policyId);
    }

    public List<LifecyclePolicy> getLifecyclePolicies(
            String region,
            List<String> policyIds,
            String state,
            List<String> resourceTypes,
            List<String> targetTags,
            List<String> tagsToAdd) {
        if (state != null && !state.isBlank() && !STATES.contains(state) && !"ERROR".equals(state)) {
            throw invalid("State must be ENABLED, DISABLED, or ERROR.");
        }
        List<LifecyclePolicy> policies = store.scan(key -> key.startsWith(region + "::"));
        policies.sort(Comparator.comparing(LifecyclePolicy::getPolicyId, Comparator.nullsLast(String::compareTo)));
        List<LifecyclePolicy> matched = new ArrayList<>();
        for (LifecyclePolicy policy : policies) {
            if (!matches(policy, policyIds, state, resourceTypes, targetTags, tagsToAdd)) {
                continue;
            }
            matched.add(policy);
        }
        return matched;
    }

    public synchronized LifecyclePolicy updateLifecyclePolicy(String region, String policyId, JsonNode request) {
        requireObject(request, "Request body");
        LifecyclePolicy current = requirePolicy(region, policyId);
        if (request.has("ExecutionRoleArn")) {
            current.setExecutionRoleArn(requireText(request, "ExecutionRoleArn"));
        }
        if (request.has("Description")) {
            String description = requireText(request, "Description");
            if (description.length() > 500) {
                throw invalid("Description must be at most 500 characters.");
            }
            current.setDescription(description);
        }
        if (request.has("State")) {
            current.setState(requireState(request.get("State"), true));
        }
        if (request.has("PolicyDetails")) {
            current.setPolicyDetails(normalizeDetails(requireObjectField(request, "PolicyDetails")));
        }
        current.setDateModified(Instant.now().toString());
        store.put(storageKey(region, current.getPolicyId()), current);
        return current;
    }

    public synchronized void deleteLifecyclePolicy(String region, String policyId) {
        LifecyclePolicy policy = requirePolicy(region, policyId);
        store.delete(storageKey(region, policy.getPolicyId()));
    }

    public ObjectNode toPolicy(LifecyclePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "PolicyId", policy.getPolicyId());
        putText(node, "Description", policy.getDescription());
        putText(node, "State", policy.getState());
        putText(node, "StatusMessage", policy.getStatusMessage());
        putText(node, "ExecutionRoleArn", policy.getExecutionRoleArn());
        putText(node, "DateCreated", policy.getDateCreated());
        putText(node, "DateModified", policy.getDateModified());
        if (policy.getPolicyDetails() != null) {
            node.set("PolicyDetails", policy.getPolicyDetails());
        }
        node.set("Tags", tagsNode(policy.getTags()));
        putText(node, "PolicyArn", policy.getPolicyArn());
        if (policy.getDefaultPolicy() != null) {
            node.put("DefaultPolicy", policy.getDefaultPolicy());
        }
        return node;
    }

    public ObjectNode toSummary(LifecyclePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "PolicyId", policy.getPolicyId());
        putText(node, "Description", policy.getDescription());
        putText(node, "State", policy.getState());
        node.set("Tags", tagsNode(policy.getTags()));
        putText(node, "PolicyType", policyType(policy));
        if (policy.getDefaultPolicy() != null) {
            node.put("DefaultPolicy", policy.getDefaultPolicy());
        }
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
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requirePolicyByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        LifecyclePolicy policy = requirePolicyByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(policy.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        policy.setTags(current);
        policy.setDateModified(Instant.now().toString());
        store.put(storageKey(region, policy.getPolicyId()), policy);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        LifecyclePolicy policy = requirePolicyByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(policy.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        policy.setTags(current);
        policy.setDateModified(Instant.now().toString());
        store.put(storageKey(region, policy.getPolicyId()), policy);
    }

    private LifecyclePolicy requirePolicy(String region, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw invalid("PolicyId is required.");
        }
        String decoded = decode(policyId);
        return store.get(storageKey(region, decoded)).orElseThrow(() -> notFound(decoded));
    }

    private LifecyclePolicy requirePolicyByArn(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid resource ARN: " + decoded);
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("policy/")) {
            throw notFound(decoded);
        }
        String policyId = parsed.resource().substring("policy/".length());
        LifecyclePolicy policy = requirePolicy(region, policyId);
        if (decoded.startsWith("arn:") && policy.getPolicyArn() != null && !decoded.equals(policy.getPolicyArn())) {
            throw notFound(decoded);
        }
        return policy;
    }

    private boolean matches(
            LifecyclePolicy policy,
            List<String> policyIds,
            String state,
            List<String> resourceTypes,
            List<String> targetTags,
            List<String> tagsToAdd) {
        if (policyIds != null && !policyIds.isEmpty() && !policyIds.contains(policy.getPolicyId())) {
            return false;
        }
        if (state != null && !state.isBlank() && !state.equals(policy.getState())) {
            return false;
        }
        JsonNode details = policy.getPolicyDetails();
        if (resourceTypes != null && !resourceTypes.isEmpty()) {
            if (!containsAny(details, "ResourceTypes", resourceTypes)) {
                return false;
            }
        }
        if (targetTags != null && !targetTags.isEmpty()) {
            if (!containsTagFilters(details, "TargetTags", targetTags)) {
                return false;
            }
        }
        if (tagsToAdd != null && !tagsToAdd.isEmpty()) {
            JsonNode schedules = details == null ? null : details.get("Schedules");
            if (schedules == null || !schedules.isArray()) {
                return false;
            }
            boolean matched = false;
            for (JsonNode schedule : schedules) {
                if (containsTagFilters(schedule, "TagsToAdd", tagsToAdd)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(JsonNode parent, String field, List<String> wanted) {
        if (parent == null || !parent.has(field) || !parent.get(field).isArray()) {
            return false;
        }
        for (JsonNode value : parent.get(field)) {
            if (value.isTextual() && wanted.contains(value.textValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTagFilters(JsonNode parent, String field, List<String> filters) {
        if (parent == null || !parent.has(field) || !parent.get(field).isArray()) {
            return false;
        }
        for (String filter : filters) {
            if (filter == null || filter.isBlank()) {
                continue;
            }
            int split = filter.indexOf(':');
            String key = split < 0 ? filter : filter.substring(0, split);
            String value = split < 0 ? null : filter.substring(split + 1);
            boolean found = false;
            for (JsonNode tag : parent.get(field)) {
                if (tag == null || !tag.isObject()) {
                    continue;
                }
                String tagKey = textOrNull(tag, "Key");
                String tagValue = textOrNull(tag, "Value");
                if (key.equals(tagKey) && (value == null || value.equals(tagValue))) {
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

    private JsonNode normalizeDetails(JsonNode details) {
        ObjectNode copy = details.deepCopy();
        if (!copy.hasNonNull("PolicyType") || copy.get("PolicyType").asText().isBlank()) {
            copy.put("PolicyType", DEFAULT_POLICY_TYPE);
        }
        if (!copy.has("ResourceLocations") || copy.get("ResourceLocations").isNull()
                || (copy.get("ResourceLocations").isArray() && copy.get("ResourceLocations").isEmpty())) {
            copy.putArray("ResourceLocations").add("CLOUD");
        }
        return copy;
    }

    private String policyType(LifecyclePolicy policy) {
        JsonNode details = policy.getPolicyDetails();
        if (details != null && details.hasNonNull("PolicyType")) {
            return details.get("PolicyType").asText();
        }
        return DEFAULT_POLICY_TYPE;
    }

    private static String newPolicyId() {
        return "policy-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private static String arn(String region, String account, String policyId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "policy/" + policyId).toString();
    }

    private static String storageKey(String region, String policyId) {
        return region + "::" + policyId;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " is required.");
        }
        return value.textValue();
    }

    private static String requireState(JsonNode value, boolean settable) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("State is required.");
        }
        String state = value.textValue();
        if (settable && !STATES.contains(state)) {
            throw invalid("State must be ENABLED or DISABLED.");
        }
        return state;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw invalid("Tags must be an object.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalid("Tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String policyId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Lifecycle policy " + policyId + " not found.",
                404);
    }
}
