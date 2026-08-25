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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Local OpenSearch Serverless stub. Collections, policies, security configs, and
 * collection groups are in-memory; collections become {@code ACTIVE} immediately
 * so Alchemy's bounded wait-for-status loops converge.
 *
 * @see <a href="https://docs.aws.amazon.com/opensearch-service/latest/ServerlessAPIReference/API_Operations.html">OpenSearch Serverless API</a>
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

    static final class Policy {
        String type;
        String name;
        String policyVersion;
        String description;
        JsonNode policy;
        long createdDate;
        long lastModifiedDate;
    }

    static final class Collection {
        String id;
        String name;
        String arn;
        String type;
        String status;
        String description;
        String standbyReplicas;
        String deletionProtection;
        String kmsKeyArn;
        String collectionEndpoint;
        String dashboardEndpoint;
        String collectionGroupName;
        long createdDate;
        long lastModifiedDate;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class AccountSettings {
        int maxIndexingCapacityInOCU = DEFAULT_CAPACITY_OCU;
        int maxSearchCapacityInOCU = DEFAULT_CAPACITY_OCU;
    }

    static final class SecurityConfig {
        String id;
        String type;
        String name;
        String configVersion;
        String description;
        ObjectNode samlOptions;
        ObjectNode iamIdentityCenterOptions;
        ObjectNode iamFederationOptions;
        long createdDate;
        long lastModifiedDate;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, CollectionGroup> groupsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> groupsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Policy> securityPolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Policy> accessPolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Collection> collections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createCollectionTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AccountSettings> accountSettingsByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Policy> lifecyclePolicies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SecurityConfig> securityConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createSecurityConfigTokens = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong(1);

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
        securityPolicies.clear();
        accessPolicies.clear();
        collections.clear();
        createCollectionTokens.clear();
        accountSettingsByRegion.clear();
        lifecyclePolicies.clear();
        securityConfigs.clear();
        createSecurityConfigTokens.clear();
        versions.set(1);
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
        for (Policy policy : securityPolicies.values()) {
            if ("encryption".equals(policy.type)) {
                encryption++;
            } else if ("network".equals(policy.type)) {
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
            Policy policy = findEffectiveLifecyclePolicy(type, resource);
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

    public ObjectNode createLifecyclePolicy(JsonNode request) {
        String type = requireLifecycleType(request);
        String name = requireText(request, "name");
        validateName(name);
        String key = policyKey(type, name);
        if (lifecyclePolicies.containsKey(key)) {
            throw conflict("Policy with name " + name + " and type " + type + " already exists.");
        }
        Policy policy = newPolicy(type, name, request);
        lifecyclePolicies.put(key, policy);
        return wrap("lifecyclePolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode batchGetLifecyclePolicy(JsonNode request) {
        JsonNode identifiers = request == null ? null : request.get("identifiers");
        if (identifiers == null || identifiers.isNull() || identifiers.isMissingNode()) {
            throw invalid("identifiers is required.");
        }
        if (!identifiers.isArray() || identifiers.size() < 1 || identifiers.size() > 40) {
            throw invalid("identifiers must contain between 1 and 40 items.");
        }
        ArrayNode details = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        for (JsonNode identifier : identifiers) {
            if (identifier == null || !identifier.isObject()) {
                throw invalid("Each identifier must be an object.");
            }
            String type = textOrNull(identifier, "type");
            String name = textOrNull(identifier, "name");
            if (type == null) {
                throw invalid("type is required.");
            }
            if (name == null) {
                throw invalid("name is required.");
            }
            if (!"retention".equals(type)) {
                throw invalid("type must be retention.");
            }
            Policy policy = lifecyclePolicies.get(policyKey(type, name));
            if (policy == null) {
                ObjectNode error = errors.addObject();
                error.put("type", type);
                error.put("name", name);
                error.put("errorMessage", "Policy with name " + name + " and type " + type + " not found.");
                error.put("errorCode", "NOT_FOUND");
            } else {
                details.add(securityPolicyNode(policy));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        if (details.size() > 0) {
            response.set("lifecyclePolicyDetails", details);
        }
        if (errors.size() > 0) {
            response.set("lifecyclePolicyErrorDetails", errors);
        }
        return response;
    }

    public ObjectNode listLifecyclePolicies(JsonNode request) {
        String type = requireLifecycleType(request);
        List<String> resources = stringList(request, "resources");
        ArrayNode summaries = objectMapper.createArrayNode();
        lifecyclePolicies.values().stream()
                .filter(policy -> type.equals(policy.type))
                .filter(policy -> resources.isEmpty() || matchesLifecycleResources(policy, resources))
                .sorted(Comparator.comparing((Policy p) -> p.name))
                .forEach(policy -> summaries.add(policySummary(policy)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("lifecyclePolicySummaries", summaries);
        return response;
    }

    public ObjectNode updateLifecyclePolicy(JsonNode request) {
        Policy policy = requireLifecyclePolicy(request);
        requireMatchingVersion(policy, request);
        applyPolicyUpdate(policy, request);
        return wrap("lifecyclePolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode deleteLifecyclePolicy(JsonNode request) {
        Policy policy = requireLifecyclePolicy(request);
        lifecyclePolicies.remove(policyKey(policy.type, policy.name));
        return objectMapper.createObjectNode();
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

    public ObjectNode createSecurityPolicy(JsonNode request) {
        String type = requirePolicyType(request, true);
        String name = requireText(request, "name");
        String key = policyKey(type, name);
        if (securityPolicies.containsKey(key)) {
            throw conflict("Policy with name " + name + " and type " + type + " already exists.");
        }
        Policy policy = newPolicy(type, name, request);
        securityPolicies.put(key, policy);
        return wrap("securityPolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode getSecurityPolicy(JsonNode request) {
        return wrap("securityPolicyDetail", securityPolicyNode(requireSecurityPolicy(request)));
    }

    public ObjectNode updateSecurityPolicy(JsonNode request) {
        Policy policy = requireSecurityPolicy(request);
        requireMatchingVersion(policy, request);
        applyPolicyUpdate(policy, request);
        return wrap("securityPolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode deleteSecurityPolicy(JsonNode request) {
        Policy policy = requireSecurityPolicy(request);
        if ("encryption".equals(policy.type)) {
            for (Collection collection : collections.values()) {
                if (coversCollection(policy.policy, collection.name)) {
                    throw conflict("Cannot delete encryption policy while collections it covers still exist.");
                }
            }
        }
        securityPolicies.remove(policyKey(policy.type, policy.name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listSecurityPolicies(JsonNode request) {
        String type = requirePolicyType(request, true);
        ArrayNode summaries = objectMapper.createArrayNode();
        securityPolicies.values().stream()
                .filter(policy -> type.equals(policy.type))
                .sorted(Comparator.comparing((Policy p) -> p.name))
                .forEach(policy -> summaries.add(policySummary(policy)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("securityPolicySummaries", summaries);
        return response;
    }

    public ObjectNode createAccessPolicy(JsonNode request) {
        String type = requireAccessPolicyType(request);
        String name = requireText(request, "name");
        String key = policyKey(type, name);
        if (accessPolicies.containsKey(key)) {
            throw conflict("Policy with name " + name + " and type " + type + " already exists.");
        }
        Policy policy = newPolicy(type, name, request);
        accessPolicies.put(key, policy);
        return wrap("accessPolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode getAccessPolicy(JsonNode request) {
        return wrap("accessPolicyDetail", securityPolicyNode(requireAccessPolicy(request)));
    }

    public ObjectNode updateAccessPolicy(JsonNode request) {
        Policy policy = requireAccessPolicy(request);
        requireMatchingVersion(policy, request);
        applyPolicyUpdate(policy, request);
        return wrap("accessPolicyDetail", securityPolicyNode(policy));
    }

    public ObjectNode deleteAccessPolicy(JsonNode request) {
        Policy policy = requireAccessPolicy(request);
        accessPolicies.remove(policyKey(policy.type, policy.name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAccessPolicies(JsonNode request) {
        String type = requireAccessPolicyType(request);
        ArrayNode summaries = objectMapper.createArrayNode();
        accessPolicies.values().stream()
                .filter(policy -> type.equals(policy.type))
                .sorted(Comparator.comparing((Policy p) -> p.name))
                .forEach(policy -> summaries.add(policySummary(policy)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("accessPolicySummaries", summaries);
        return response;
    }

    public ObjectNode createSecurityConfig(JsonNode request) {
        String token = textOrNull(request, "clientToken");
        if (token != null) {
            String existingId = createSecurityConfigTokens.get(token);
            if (existingId != null) {
                SecurityConfig existing = securityConfigs.get(existingId);
                if (existing != null) {
                    return wrap("securityConfigDetail", securityConfigDetail(existing));
                }
            }
        }
        String type = requireSecurityConfigType(request);
        String name = requireText(request, "name");
        validateName(name);
        String id = type + "/" + regionResolver.getAccountId() + "/" + name;
        if (securityConfigs.containsKey(id)) {
            throw conflict("Security config with name " + name + " and type " + type + " already exists.");
        }
        long now = nowMillis();
        SecurityConfig config = new SecurityConfig();
        config.id = id;
        config.type = type;
        config.name = name;
        config.configVersion = nextVersion();
        config.description = textOrNull(request, "description");
        config.createdDate = now;
        config.lastModifiedDate = now;
        if ("saml".equals(type)) {
            config.samlOptions = copySamlOptions(request.get("samlOptions"), true);
        } else if ("iamidentitycenter".equals(type)) {
            config.iamIdentityCenterOptions = copyIamIdentityCenterOptions(request.get("iamIdentityCenterOptions"), true);
        } else {
            config.iamFederationOptions = copyIamFederationOptions(request.get("iamFederationOptions"));
        }
        securityConfigs.put(id, config);
        if (token != null) {
            createSecurityConfigTokens.put(token, id);
        }
        return wrap("securityConfigDetail", securityConfigDetail(config));
    }

    public ObjectNode getSecurityConfig(JsonNode request) {
        return wrap("securityConfigDetail", securityConfigDetail(requireSecurityConfig(requireText(request, "id"))));
    }

    public ObjectNode updateSecurityConfig(JsonNode request) {
        SecurityConfig config = requireSecurityConfig(requireText(request, "id"));
        String expected = requireText(request, "configVersion");
        if (!expected.equals(config.configVersion)) {
            throw conflict("Security config version mismatch.");
        }
        boolean bump = false;
        if (request.hasNonNull("description")) {
            config.description = request.get("description").asText();
        }
        if (request.has("samlOptions") && !request.get("samlOptions").isNull()) {
            config.samlOptions = copySamlOptions(request.get("samlOptions"), true);
            bump = true;
        }
        if (request.has("iamIdentityCenterOptionsUpdates")
                && !request.get("iamIdentityCenterOptionsUpdates").isNull()) {
            applyIamIdentityCenterUpdates(config, request.get("iamIdentityCenterOptionsUpdates"));
            bump = true;
        }
        if (request.has("iamFederationOptions") && !request.get("iamFederationOptions").isNull()) {
            config.iamFederationOptions = copyIamFederationOptions(request.get("iamFederationOptions"));
            bump = true;
        }
        if (bump) {
            config.configVersion = nextVersion();
        }
        config.lastModifiedDate = nowMillis();
        return wrap("securityConfigDetail", securityConfigDetail(config));
    }

    public ObjectNode deleteSecurityConfig(JsonNode request) {
        SecurityConfig config = requireSecurityConfig(requireText(request, "id"));
        securityConfigs.remove(config.id);
        createSecurityConfigTokens.values().removeIf(config.id::equals);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listSecurityConfigs(JsonNode request) {
        String type = requireSecurityConfigType(request);
        ArrayNode summaries = objectMapper.createArrayNode();
        securityConfigs.values().stream()
                .filter(config -> type.equals(config.type))
                .sorted(Comparator.comparing((SecurityConfig c) -> c.name))
                .forEach(config -> summaries.add(securityConfigSummary(config)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("securityConfigSummaries", summaries);
        return response;
    }

    public ObjectNode createCollection(JsonNode request, String region) {
        String token = textOrNull(request, "clientToken");
        if (token != null) {
            String existingId = createCollectionTokens.get(token);
            if (existingId != null) {
                Collection existing = collections.get(existingId);
                if (existing != null) {
                    return wrap("createCollectionDetail", createCollectionDetail(existing));
                }
            }
        }
        String name = requireText(request, "name");
        if (findCollectionByName(name) != null) {
            throw conflict("A collection with name " + name + " already exists.");
        }
        Policy encryption = matchingEncryptionPolicy(name);
        if (encryption == null) {
            throw invalid("The collection cannot be created because no encryption policy exists that matches the collection name.");
        }
        String groupName = textOrNull(request, "collectionGroupName");
        CollectionGroup group = null;
        if (groupName != null) {
            String groupId = groupsByName.get(groupName);
            group = groupId == null ? null : groupsById.get(groupId);
            if (group == null) {
                throw notFound("Collection group " + groupName + " not found.");
            }
        }
        long now = nowMillis();
        Collection collection = new Collection();
        collection.id = newId();
        collection.name = name;
        collection.arn = regionResolver.buildArn("aoss", region, "collection/" + collection.id);
        collection.type = textOrDefault(request, "type", "SEARCH");
        collection.status = "ACTIVE";
        collection.description = textOrNull(request, "description");
        collection.standbyReplicas = textOrDefault(request, "standbyReplicas", "ENABLED");
        collection.deletionProtection = textOrDefault(request, "deletionProtection", "DISABLED");
        collection.kmsKeyArn = kmsKeyArn(encryption, region);
        collection.collectionEndpoint = "https://" + collection.id + "." + region + ".aoss.amazonaws.com";
        collection.dashboardEndpoint = collection.collectionEndpoint + "/_dashboards";
        collection.collectionGroupName = groupName;
        collection.createdDate = now;
        collection.lastModifiedDate = now;
        collection.tags.putAll(readTags(request.get("tags")));
        collections.put(collection.id, collection);
        if (group != null) {
            group.numberOfCollections++;
        }
        if (token != null) {
            createCollectionTokens.put(token, collection.id);
        }
        return wrap("createCollectionDetail", createCollectionDetail(collection));
    }

    public ObjectNode batchGetCollection(JsonNode request) {
        ArrayNode details = objectMapper.createArrayNode();
        ArrayNode errors = objectMapper.createArrayNode();
        for (String id : stringList(request, "ids")) {
            Collection collection = collections.get(id);
            if (collection == null) {
                errors.add(errorDetail(id, null, "Collection not found", "NOT_FOUND"));
            } else {
                details.add(collectionDetail(collection));
            }
        }
        for (String name : stringList(request, "names")) {
            Collection collection = findCollectionByName(name);
            if (collection == null) {
                errors.add(errorDetail(null, name, "Collection not found", "NOT_FOUND"));
            } else {
                details.add(collectionDetail(collection));
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("collectionDetails", details);
        response.set("collectionErrorDetails", errors);
        return response;
    }

    public ObjectNode updateCollection(JsonNode request) {
        Collection collection = requireCollectionById(requireText(request, "id"));
        if (request.hasNonNull("description")) {
            collection.description = request.get("description").asText();
        }
        if (request.hasNonNull("deletionProtection")) {
            collection.deletionProtection = request.get("deletionProtection").asText();
        }
        collection.lastModifiedDate = nowMillis();
        return wrap("updateCollectionDetail", updateCollectionDetail(collection));
    }

    public ObjectNode deleteCollection(JsonNode request) {
        Collection collection = requireCollectionById(requireText(request, "id"));
        if ("ENABLED".equals(collection.deletionProtection)) {
            throw conflict("Collection cannot be deleted because deletion protection is enabled.");
        }
        collections.remove(collection.id);
        if (collection.collectionGroupName != null) {
            String groupId = groupsByName.get(collection.collectionGroupName);
            CollectionGroup group = groupId == null ? null : groupsById.get(groupId);
            if (group != null && group.numberOfCollections > 0) {
                group.numberOfCollections--;
            }
        }
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("id", collection.id);
        detail.put("name", collection.name);
        detail.put("status", "DELETING");
        detail.put("deletionProtection", collection.deletionProtection);
        return wrap("deleteCollectionDetail", detail);
    }

    public ObjectNode listCollections(JsonNode request) {
        JsonNode filters = request.path("collectionFilters");
        String name = textOrNull(filters, "name");
        String status = textOrNull(filters, "status");
        ArrayNode summaries = objectMapper.createArrayNode();
        collections.values().stream()
                .filter(collection -> name == null || name.equals(collection.name))
                .filter(collection -> status == null || status.equals(collection.status))
                .sorted(Comparator.comparing((Collection c) -> c.name))
                .forEach(collection -> summaries.add(collectionSummary(collection)));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("collectionSummaries", summaries);
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        String arn = requireText(request, "resourceArn");
        Map<String, String> incoming = readTags(request.get("tags"));
        Collection collection = findCollectionByArn(arn);
        if (collection != null) {
            collection.tags.putAll(incoming);
            return objectMapper.createObjectNode();
        }
        CollectionGroup group = requireGroupByArn(arn);
        group.tags.putAll(incoming);
        group.lastModifiedDate = nowMillis();
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        String arn = requireText(request, "resourceArn");
        Collection collection = findCollectionByArn(arn);
        if (collection != null) {
            for (String key : stringList(request, "tagKeys")) {
                collection.tags.remove(key);
            }
            return objectMapper.createObjectNode();
        }
        CollectionGroup group = requireGroupByArn(arn);
        for (String key : stringList(request, "tagKeys")) {
            group.tags.remove(key);
        }
        group.lastModifiedDate = nowMillis();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requireText(request, "resourceArn");
        Map<String, String> tags;
        Collection collection = findCollectionByArn(arn);
        if (collection != null) {
            tags = collection.tags;
        } else {
            tags = requireGroupByArn(arn).tags;
        }
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("tags"), tags);
        return response;
    }

    private Policy newPolicy(String type, String name, JsonNode request) {
        long now = nowMillis();
        Policy policy = new Policy();
        policy.type = type;
        policy.name = name;
        policy.policyVersion = nextVersion();
        policy.description = textOrNull(request, "description");
        policy.policy = parsePolicy(request.get("policy"), true);
        policy.createdDate = now;
        policy.lastModifiedDate = now;
        return policy;
    }

    private void applyPolicyUpdate(Policy policy, JsonNode request) {
        if (request.has("policy") && !request.get("policy").isNull()) {
            policy.policy = parsePolicy(request.get("policy"), false);
        }
        if (request.hasNonNull("description")) {
            policy.description = request.get("description").asText();
        }
        policy.policyVersion = nextVersion();
        policy.lastModifiedDate = nowMillis();
    }

    private void requireMatchingVersion(Policy policy, JsonNode request) {
        String expected = requireText(request, "policyVersion");
        if (!expected.equals(policy.policyVersion)) {
            throw conflict("Policy version mismatch.");
        }
    }

    private Policy requireSecurityPolicy(JsonNode request) {
        String type = requirePolicyType(request, true);
        String name = requireText(request, "name");
        Policy policy = securityPolicies.get(policyKey(type, name));
        if (policy == null) {
            throw notFound("Policy with name " + name + " and type " + type + " not found.");
        }
        return policy;
    }

    private Policy requireAccessPolicy(JsonNode request) {
        String type = requireAccessPolicyType(request);
        String name = requireText(request, "name");
        Policy policy = accessPolicies.get(policyKey(type, name));
        if (policy == null) {
            throw notFound("Policy with name " + name + " and type " + type + " not found.");
        }
        return policy;
    }

    private String requirePolicyType(JsonNode request, boolean security) {
        String type = requireText(request, "type");
        if (security && !"encryption".equals(type) && !"network".equals(type)) {
            throw invalid("type must be encryption or network.");
        }
        return type;
    }

    private String requireAccessPolicyType(JsonNode request) {
        String type = requireText(request, "type");
        if (!"data".equals(type)) {
            throw invalid("type must be data.");
        }
        return type;
    }

    private String requireSecurityConfigType(JsonNode request) {
        String type = requireText(request, "type");
        if (!"saml".equals(type) && !"iamidentitycenter".equals(type) && !"iamfederation".equals(type)) {
            throw invalid("type must be saml, iamidentitycenter, or iamfederation.");
        }
        return type;
    }

    private SecurityConfig requireSecurityConfig(String id) {
        SecurityConfig config = securityConfigs.get(id);
        if (config == null) {
            throw notFound("Security config " + id + " not found.");
        }
        return config;
    }

    private ObjectNode securityConfigDetail(SecurityConfig config) {
        ObjectNode node = securityConfigSummary(config);
        if (config.samlOptions != null) {
            node.set("samlOptions", config.samlOptions);
        }
        if (config.iamIdentityCenterOptions != null) {
            node.set("iamIdentityCenterOptions", config.iamIdentityCenterOptions);
        }
        if (config.iamFederationOptions != null) {
            node.set("iamFederationOptions", config.iamFederationOptions);
        }
        node.put("createdDate", config.createdDate);
        node.put("lastModifiedDate", config.lastModifiedDate);
        return node;
    }

    private ObjectNode securityConfigSummary(SecurityConfig config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", config.id);
        node.put("type", config.type);
        node.put("configVersion", config.configVersion);
        if (config.description != null) {
            node.put("description", config.description);
        }
        node.put("createdDate", config.createdDate);
        node.put("lastModifiedDate", config.lastModifiedDate);
        return node;
    }

    private ObjectNode copySamlOptions(JsonNode options, boolean required) {
        if (options == null || options.isNull() || options.isMissingNode()) {
            if (required) {
                throw invalid("samlOptions is required.");
            }
            return null;
        }
        if (!options.isObject()) {
            throw invalid("samlOptions must be an object.");
        }
        if (textOrNull(options, "metadata") == null) {
            throw invalid("samlOptions.metadata is required.");
        }
        ObjectNode copy = options.deepCopy();
        int timeout = 60;
        if (copy.hasNonNull("sessionTimeout")) {
            timeout = copy.get("sessionTimeout").asInt();
            if (timeout < 5 || timeout > 720) {
                throw invalid("sessionTimeout must be between 5 and 720 minutes.");
            }
        }
        copy.put("sessionTimeout", timeout);
        return copy;
    }

    private ObjectNode copyIamIdentityCenterOptions(JsonNode options, boolean required) {
        if (options == null || options.isNull() || options.isMissingNode()) {
            if (required) {
                throw invalid("iamIdentityCenterOptions is required.");
            }
            return null;
        }
        if (textOrNull(options, "instanceArn") == null) {
            throw invalid("iamIdentityCenterOptions.instanceArn is required.");
        }
        return options.deepCopy();
    }

    private ObjectNode copyIamFederationOptions(JsonNode options) {
        if (options == null || options.isNull() || options.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        return options.deepCopy();
    }

    private void applyIamIdentityCenterUpdates(SecurityConfig config, JsonNode updates) {
        ObjectNode current = config.iamIdentityCenterOptions == null
                ? objectMapper.createObjectNode()
                : config.iamIdentityCenterOptions.deepCopy();
        if (updates.has("userAttribute")) {
            current.set("userAttribute", updates.get("userAttribute").deepCopy());
        }
        if (updates.has("groupAttribute")) {
            current.set("groupAttribute", updates.get("groupAttribute").deepCopy());
        }
        config.iamIdentityCenterOptions = current;
    }

    private String requireLifecycleType(JsonNode request) {
        String type = requireText(request, "type");
        if (!"retention".equals(type)) {
            throw invalid("type must be retention.");
        }
        return type;
    }

    private Policy requireLifecyclePolicy(JsonNode request) {
        String type = requireLifecycleType(request);
        String name = requireText(request, "name");
        Policy policy = lifecyclePolicies.get(policyKey(type, name));
        if (policy == null) {
            throw notFound("Policy with name " + name + " and type " + type + " not found.");
        }
        return policy;
    }

    private boolean matchesLifecycleResources(Policy policy, List<String> resources) {
        if (policy.policy == null) {
            return false;
        }
        String serialized = policy.policy.toString();
        for (String resource : resources) {
            if (serialized.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    private Collection requireCollectionById(String id) {
        Collection collection = collections.get(id);
        if (collection == null) {
            throw notFound("Collection " + id + " not found.");
        }
        return collection;
    }

    private Collection findCollectionByArn(String arn) {
        return collections.values().stream()
                .filter(collection -> arn.equals(collection.arn))
                .findFirst()
                .orElse(null);
    }

    private Collection findCollectionByName(String name) {
        return collections.values().stream()
                .filter(collection -> name.equals(collection.name))
                .findFirst()
                .orElse(null);
    }

    private Policy matchingEncryptionPolicy(String collectionName) {
        for (Policy policy : securityPolicies.values()) {
            if ("encryption".equals(policy.type) && coversCollection(policy.policy, collectionName)) {
                return policy;
            }
        }
        return null;
    }

    private boolean coversCollection(JsonNode policy, String collectionName) {
        if (policy == null) {
            return false;
        }
        List<JsonNode> ruleSets = new ArrayList<>();
        if (policy.isArray()) {
            for (JsonNode entry : policy) {
                ruleSets.add(entry.get("Rules"));
            }
        } else {
            ruleSets.add(policy.get("Rules"));
        }
        String exact = "collection/" + collectionName;
        for (JsonNode rules : ruleSets) {
            if (rules == null || !rules.isArray()) {
                continue;
            }
            for (JsonNode rule : rules) {
                if (!"collection".equals(textOrNull(rule, "ResourceType"))) {
                    continue;
                }
                JsonNode resources = rule.get("Resource");
                if (resources == null || !resources.isArray()) {
                    continue;
                }
                for (JsonNode resource : resources) {
                    String pattern = resource.asText();
                    if (exact.equals(pattern) || "collection/*".equals(pattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String kmsKeyArn(Policy encryption, String region) {
        JsonNode kmsArn = encryption.policy.get("KmsARN");
        if (kmsArn == null) {
            kmsArn = encryption.policy.get("kmsKeyArn");
        }
        if (kmsArn != null && kmsArn.isTextual() && !kmsArn.asText().isBlank()) {
            return kmsArn.asText();
        }
        return regionResolver.buildArn("kms", region, "alias/aws/aoss");
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

    private Policy findEffectiveLifecyclePolicy(String type, String resource) {
        String collection = collectionFromIndexResource(resource);
        if (collection == null) {
            return null;
        }
        for (Policy policy : lifecyclePolicies.values()) {
            if (!type.equals(policy.type)) {
                continue;
            }
            if (coversIndex(policy.policy, collection)) {
                return policy;
            }
        }
        return null;
    }

    private static String collectionFromIndexResource(String resource) {
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

    private boolean coversIndex(JsonNode policy, String collectionName) {
        if (policy == null) {
            return false;
        }
        String exact = "index/" + collectionName + "/*";
        String collectionExact = "index/" + collectionName;
        List<JsonNode> ruleSets = new ArrayList<>();
        if (policy.isArray()) {
            for (JsonNode entry : policy) {
                ruleSets.add(entry.get("Rules"));
            }
        } else {
            ruleSets.add(policy.get("Rules"));
        }
        for (JsonNode rules : ruleSets) {
            if (rules == null || !rules.isArray()) {
                continue;
            }
            for (JsonNode rule : rules) {
                JsonNode resources = rule.get("Resource");
                if (resources == null || !resources.isArray()) {
                    continue;
                }
                for (JsonNode resource : resources) {
                    String pattern = resource.asText();
                    if (exact.equals(pattern)
                            || collectionExact.equals(pattern)
                            || "index/*".equals(pattern)
                            || "index/*/*".equals(pattern)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ObjectNode securityPolicyNode(Policy policy) {
        ObjectNode node = policySummary(policy);
        if (policy.policy != null) {
            node.set("policy", policy.policy);
        }
        return node;
    }

    private ObjectNode policySummary(Policy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", policy.type);
        node.put("name", policy.name);
        node.put("policyVersion", policy.policyVersion);
        if (policy.description != null) {
            node.put("description", policy.description);
        }
        node.put("createdDate", policy.createdDate);
        node.put("lastModifiedDate", policy.lastModifiedDate);
        return node;
    }

    private ObjectNode collectionDetail(Collection collection) {
        ObjectNode node = createCollectionDetail(collection);
        node.put("collectionEndpoint", collection.collectionEndpoint);
        node.put("dashboardEndpoint", collection.dashboardEndpoint);
        return node;
    }

    private ObjectNode createCollectionDetail(Collection collection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", collection.id);
        node.put("name", collection.name);
        node.put("status", collection.status);
        node.put("type", collection.type);
        if (collection.description != null) {
            node.put("description", collection.description);
        }
        node.put("arn", collection.arn);
        node.put("kmsKeyArn", collection.kmsKeyArn);
        node.put("standbyReplicas", collection.standbyReplicas);
        node.put("deletionProtection", collection.deletionProtection);
        node.put("createdDate", collection.createdDate);
        node.put("lastModifiedDate", collection.lastModifiedDate);
        if (collection.collectionGroupName != null) {
            node.put("collectionGroupName", collection.collectionGroupName);
        }
        return node;
    }

    private ObjectNode updateCollectionDetail(Collection collection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", collection.id);
        node.put("name", collection.name);
        node.put("status", collection.status);
        node.put("type", collection.type);
        if (collection.description != null) {
            node.put("description", collection.description);
        }
        node.put("arn", collection.arn);
        node.put("createdDate", collection.createdDate);
        node.put("lastModifiedDate", collection.lastModifiedDate);
        node.put("deletionProtection", collection.deletionProtection);
        return node;
    }

    private ObjectNode collectionSummary(Collection collection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", collection.id);
        node.put("name", collection.name);
        node.put("status", collection.status);
        node.put("arn", collection.arn);
        node.put("kmsKeyArn", collection.kmsKeyArn);
        if (collection.collectionGroupName != null) {
            node.put("collectionGroupName", collection.collectionGroupName);
        }
        return node;
    }

    private JsonNode parsePolicy(JsonNode policy, boolean required) {
        if (policy == null || policy.isNull() || policy.isMissingNode()) {
            if (required) {
                throw invalid("policy is required.");
            }
            return null;
        }
        if (policy.isTextual()) {
            try {
                return objectMapper.readTree(policy.asText());
            } catch (Exception e) {
                throw invalid("policy is not valid JSON.");
            }
        }
        return policy.deepCopy();
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

    private String nextVersion() {
        return Base64.getEncoder().encodeToString(
                Long.toString(versions.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
    }

    private static String policyKey(String type, String name) {
        return type + "/" + name;
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
