package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Local OpenSearch Serverless stub. Collection groups are in-memory and become
 * usable immediately so Alchemy's CollectionGroup lifecycle converges.
 * Account settings, policy stats, and effective lifecycle lookups back the
 * account-level Alchemy bindings suite.
 *
 * @see <a href="https://docs.aws.amazon.com/opensearch-service/latest/APIReference/API_Operations_Amazon_OpenSearch_Serverless.html">OpenSearch Serverless API</a>
 */
@ApplicationScoped
public class OpenSearchServerlessService implements Resettable {

    private static final Pattern GROUP_NAME = Pattern.compile("^[a-z][a-z0-9-]{2,31}$");
    private static final String DEFAULT_GENERATION = "2.0";
    private static final int DEFAULT_CAPACITY_OCU = 10;
    private static final int MIN_CAPACITY_OCU = 2;
    private static final int MAX_RESOURCE_IDENTIFIERS = 100;

    static final class CollectionGroup {
        String id;
        String arn;
        String name;
        String standbyReplicas;
        String description;
        String generation;
        long createdDate;
        long lastModifiedDate;
        JsonNode capacityLimits;
        int numberOfCollections;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class AccountSettings {
        int maxIndexingCapacityInOCU = DEFAULT_CAPACITY_OCU;
        int maxSearchCapacityInOCU = DEFAULT_CAPACITY_OCU;
    }

    static final class LifecyclePolicy {
        String type;
        String name;
        String policy;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, CollectionGroup> groupsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> groupsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AccountSettings> accountSettingsByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LifecyclePolicy> lifecyclePolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> accessPolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> securityPolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> securityConfigs = new ConcurrentHashMap<>();

    @Inject
    public OpenSearchServerlessService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        groupsById.clear();
        groupsByName.clear();
        createTokens.clear();
        accountSettingsByRegion.clear();
        lifecyclePolicies.clear();
        accessPolicies.clear();
        securityPolicies.clear();
        securityConfigs.clear();
    }

    public ObjectNode getAccountSettings(String region) {
        return wrap("accountSettingsDetail", capacityLimitsNode(settingsFor(region)));
    }

    public ObjectNode updateAccountSettings(JsonNode request, String region) {
        AccountSettings settings = settingsFor(region);
        JsonNode limits = request == null ? null : request.get("capacityLimits");
        if (limits != null && !limits.isNull() && !limits.isMissingNode()) {
            if (!limits.isObject()) {
                throw invalid("capacityLimits must be an object.");
            }
            if (limits.has("maxIndexingCapacityInOCU") && !limits.get("maxIndexingCapacityInOCU").isNull()) {
                settings.maxIndexingCapacityInOCU = requireCapacity(limits, "maxIndexingCapacityInOCU");
            }
            if (limits.has("maxSearchCapacityInOCU") && !limits.get("maxSearchCapacityInOCU").isNull()) {
                settings.maxSearchCapacityInOCU = requireCapacity(limits, "maxSearchCapacityInOCU");
            }
        }
        accountSettingsByRegion.put(regionKey(region), settings);
        return wrap("accountSettingsDetail", capacityLimitsNode(settings));
    }

    public ObjectNode getPoliciesStats() {
        int dataPolicies = accessPolicies.size();
        int encryption = 0;
        int network = 0;
        for (String type : securityPolicies.values()) {
            if ("encryption".equals(type)) {
                encryption++;
            } else if ("network".equals(type)) {
                network++;
            }
        }
        int saml = securityConfigs.size();
        int retention = lifecyclePolicies.size();
        int total = dataPolicies + encryption + network + saml + retention;

        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("AccessPolicyStats").put("DataPolicyCount", dataPolicies);
        ObjectNode security = response.putObject("SecurityPolicyStats");
        security.put("EncryptionPolicyCount", encryption);
        security.put("NetworkPolicyCount", network);
        response.putObject("SecurityConfigStats").put("SamlConfigCount", saml);
        response.putObject("LifecyclePolicyStats").put("RetentionPolicyCount", retention);
        response.put("TotalPolicyCount", total);
        return response;
    }

    public ObjectNode batchGetEffectiveLifecyclePolicy(JsonNode request) {
        JsonNode identifiers = request == null ? null : request.get("resourceIdentifiers");
        if (identifiers == null || identifiers.isNull() || identifiers.isMissingNode()) {
            throw invalid("resourceIdentifiers is required.");
        }
        if (!identifiers.isArray()) {
            throw invalid("resourceIdentifiers must be an array.");
        }
        if (identifiers.size() < 1 || identifiers.size() > MAX_RESOURCE_IDENTIFIERS) {
            throw invalid("resourceIdentifiers must contain between 1 and "
                    + MAX_RESOURCE_IDENTIFIERS + " items.");
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        for (JsonNode identifier : identifiers) {
            if (identifier == null || !identifier.isObject()) {
                throw invalid("Each resource identifier must be an object.");
            }
            String type = textOrNull(identifier, "type");
            String resource = textOrNull(identifier, "resource");
            if (type == null) {
                throw invalid("type is required.");
            }
            if (resource == null) {
                throw invalid("resource is required.");
            }
            LifecyclePolicy policy = findEffectivePolicy(type, resource);
            if (policy == null) {
                ObjectNode error = errors.addObject();
                error.put("type", type);
                error.put("resource", resource);
                error.put("errorMessage", "No matching lifecycle policy found for resource " + resource + ".");
                error.put("errorCode", "ResourceNotFoundException");
            } else {
                ObjectNode detail = details.addObject();
                detail.put("type", type);
                detail.put("resource", resource);
                detail.put("policyName", policy.name);
                detail.put("resourceType", "index");
            }
        }
        response.set("effectiveLifecyclePolicyDetails", details);
        response.set("effectiveLifecyclePolicyErrorDetails", errors);
        return response;
    }

    public ObjectNode createCollectionGroup(JsonNode request, String region) {
        String token = textOrNull(request, "clientToken");
        if (token != null) {
            String existingId = createTokens.get(token);
            if (existingId != null) {
                CollectionGroup existing = groupsById.get(existingId);
                if (existing != null) {
                    return wrap("createCollectionGroupDetail", createDetail(existing));
                }
            }
        }
        String name = requireText(request, "name");
        validateName(name);
        String standbyReplicas = requireStandbyReplicas(request);
        if (groupsByName.containsKey(name)) {
            throw conflict("Collection group with name " + name + " already exists.");
        }
        long now = nowMillis();
        CollectionGroup group = new CollectionGroup();
        group.id = newId();
        group.arn = regionResolver.buildArn("aoss", region, "collection-group/" + group.id);
        group.name = name;
        group.standbyReplicas = standbyReplicas;
        group.description = textOrNull(request, "description");
        group.generation = textOrDefault(request, "generation", DEFAULT_GENERATION);
        group.createdDate = now;
        group.lastModifiedDate = now;
        group.capacityLimits = copy(request.get("capacityLimits"));
        group.numberOfCollections = 0;
        group.tags.putAll(readTags(request.get("tags")));
        groupsById.put(group.id, group);
        groupsByName.put(group.name, group.id);
        if (token != null) {
            createTokens.put(token, group.id);
        }
        return wrap("createCollectionGroupDetail", createDetail(group));
    }

    public ObjectNode batchGetCollectionGroup(JsonNode request) {
        List<String> ids = stringList(request, "ids");
        List<String> names = stringList(request, "names");
        if (ids.isEmpty() && names.isEmpty()) {
            throw invalid("Either ids or names must be specified.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        for (String id : ids) {
            CollectionGroup group = groupsById.get(id);
            if (group == null) {
                errors.add(errorDetail(id, null, "Collection group not found.", "NOT_FOUND"));
            } else {
                details.add(detail(group));
            }
        }
        for (String name : names) {
            String id = groupsByName.get(name);
            CollectionGroup group = id == null ? null : groupsById.get(id);
            if (group == null) {
                errors.add(errorDetail(null, name, "Collection group not found.", "NOT_FOUND"));
            } else {
                details.add(detail(group));
            }
        }
        if (details.size() > 0) {
            response.set("collectionGroupDetails", details);
        }
        if (errors.size() > 0) {
            response.set("collectionGroupErrorDetails", errors);
        }
        return response;
    }

    public ObjectNode listCollectionGroups(JsonNode request) {
        int maxResults = request != null && request.hasNonNull("maxResults")
                ? Math.max(1, request.get("maxResults").asInt())
                : Integer.MAX_VALUE;
        List<CollectionGroup> groups = new ArrayList<>(groupsById.values());
        groups.sort(Comparator.comparingLong((CollectionGroup g) -> g.createdDate).reversed());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("collectionGroupSummaries");
        int limit = Math.min(groups.size(), maxResults);
        for (int i = 0; i < limit; i++) {
            summaries.add(summary(groups.get(i)));
        }
        return response;
    }

    public ObjectNode updateCollectionGroup(JsonNode request) {
        CollectionGroup group = requireGroupById(requireText(request, "id"));
        if (request.has("description")) {
            group.description = textOrNull(request, "description");
        }
        if (request.has("capacityLimits") && !request.get("capacityLimits").isNull()) {
            group.capacityLimits = copy(request.get("capacityLimits"));
        }
        group.lastModifiedDate = nowMillis();
        return wrap("updateCollectionGroupDetail", updateDetail(group));
    }

    public ObjectNode deleteCollectionGroup(JsonNode request) {
        CollectionGroup group = requireGroupById(requireText(request, "id"));
        if (group.numberOfCollections > 0) {
            throw conflict("Collection group " + group.id + " still contains collections.");
        }
        groupsById.remove(group.id);
        groupsByName.remove(group.name);
        createTokens.values().removeIf(group.id::equals);
        return objectMapper.createObjectNode();
    }

    public ObjectNode tagResource(JsonNode request) {
        CollectionGroup group = requireGroupByArn(requireText(request, "resourceArn"));
        group.tags.putAll(readTags(request.get("tags")));
        group.lastModifiedDate = nowMillis();
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        CollectionGroup group = requireGroupByArn(requireText(request, "resourceArn"));
        for (String key : stringList(request, "tagKeys")) {
            group.tags.remove(key);
        }
        group.lastModifiedDate = nowMillis();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        CollectionGroup group = requireGroupByArn(requireText(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("tags"), group.tags);
        return response;
    }

    private ObjectNode wrap(String field, ObjectNode detail) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, detail);
        return response;
    }

    private ObjectNode capacityLimitsNode(AccountSettings settings) {
        ObjectNode detail = objectMapper.createObjectNode();
        ObjectNode limits = detail.putObject("capacityLimits");
        limits.put("maxIndexingCapacityInOCU", settings.maxIndexingCapacityInOCU);
        limits.put("maxSearchCapacityInOCU", settings.maxSearchCapacityInOCU);
        return detail;
    }

    private AccountSettings settingsFor(String region) {
        return accountSettingsByRegion.computeIfAbsent(regionKey(region), key -> new AccountSettings());
    }

    private static String regionKey(String region) {
        return region == null || region.isBlank() ? "us-east-1" : region;
    }

    private static int requireCapacity(JsonNode limits, String field) {
        JsonNode value = limits.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw invalid(field + " must be an integer.");
        }
        int ocu = value.asInt();
        if (ocu < MIN_CAPACITY_OCU || ocu % 2 != 0) {
            throw invalid(field + " must be an even integer greater than or equal to "
                    + MIN_CAPACITY_OCU + ".");
        }
        return ocu;
    }

    private LifecyclePolicy findEffectivePolicy(String type, String resource) {
        String collection = collectionFromIndexResource(resource);
        if (collection == null) {
            return null;
        }
        for (LifecyclePolicy policy : lifecyclePolicies.values()) {
            if (!type.equals(policy.type)) {
                continue;
            }
            if (policy.policy != null && policy.policy.contains("index/" + collection)) {
                return policy;
            }
        }
        return null;
    }

    private static String collectionFromIndexResource(String resource) {
        // index/{collection}/{index}
        if (resource == null || !resource.startsWith("index/")) {
            return null;
        }
        String rest = resource.substring("index/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return rest.substring(0, slash);
    }

    private ObjectNode createDetail(CollectionGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.id);
        node.put("arn", group.arn);
        node.put("name", group.name);
        node.put("standbyReplicas", group.standbyReplicas);
        if (group.description != null) {
            node.put("description", group.description);
        }
        node.put("createdDate", group.createdDate);
        node.put("generation", group.generation);
        if (group.capacityLimits != null) {
            node.set("capacityLimits", group.capacityLimits);
        }
        return node;
    }

    private ObjectNode updateDetail(CollectionGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.id);
        node.put("arn", group.arn);
        node.put("name", group.name);
        if (group.description != null) {
            node.put("description", group.description);
        }
        if (group.capacityLimits != null) {
            node.set("capacityLimits", group.capacityLimits);
        }
        node.put("createdDate", group.createdDate);
        node.put("lastModifiedDate", group.lastModifiedDate);
        node.put("generation", group.generation);
        return node;
    }

    private ObjectNode detail(CollectionGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.id);
        node.put("arn", group.arn);
        node.put("name", group.name);
        node.put("standbyReplicas", group.standbyReplicas);
        if (group.description != null) {
            node.put("description", group.description);
        }
        node.put("createdDate", group.createdDate);
        node.put("numberOfCollections", group.numberOfCollections);
        node.put("generation", group.generation);
        if (group.capacityLimits != null) {
            node.set("capacityLimits", group.capacityLimits);
        }
        return node;
    }

    private ObjectNode summary(CollectionGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.id);
        node.put("arn", group.arn);
        node.put("name", group.name);
        node.put("numberOfCollections", group.numberOfCollections);
        node.put("createdDate", group.createdDate);
        node.put("generation", group.generation);
        if (group.capacityLimits != null) {
            node.set("capacityLimits", group.capacityLimits);
        }
        return node;
    }

    private ObjectNode errorDetail(String id, String name, String message, String code) {
        ObjectNode node = objectMapper.createObjectNode();
        if (id != null) {
            node.put("id", id);
        }
        if (name != null) {
            node.put("name", name);
        }
        node.put("errorMessage", message);
        node.put("errorCode", code);
        return node;
    }

    private CollectionGroup requireGroupById(String id) {
        CollectionGroup group = groupsById.get(id);
        if (group == null) {
            throw notFound("Collection group " + id + " not found.");
        }
        return group;
    }

    private CollectionGroup requireGroupByArn(String arn) {
        for (CollectionGroup group : groupsById.values()) {
            if (arn.equals(group.arn)) {
                return group;
            }
        }
        throw notFound("Resource " + arn + " not found.");
    }

    private void validateName(String name) {
        if (!GROUP_NAME.matcher(name).matches()) {
            throw invalid("name must be 3-32 characters, start with a lowercase letter, "
                    + "and contain only lowercase letters, numbers, and hyphens.");
        }
    }

    private String requireStandbyReplicas(JsonNode request) {
        String value = requireText(request, "standbyReplicas");
        if (!"ENABLED".equals(value) && !"DISABLED".equals(value)) {
            throw invalid("standbyReplicas must be ENABLED or DISABLED.");
        }
        return value;
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return tags;
        }
        for (JsonNode tag : node) {
            String key = textOrNull(tag, "key");
            if (key == null) {
                key = textOrNull(tag, "Key");
            }
            if (key != null) {
                String value = textOrNull(tag, "value");
                if (value == null) {
                    value = tag.path("Value").asText("");
                }
                tags.put(key, value);
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
    }

    private static List<String> stringList(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toLowerCase(Locale.ROOT);
    }

    private static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
