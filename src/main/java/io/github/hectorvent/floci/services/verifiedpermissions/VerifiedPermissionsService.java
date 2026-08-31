package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.verifiedpermissions.model.CedarPolicy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdentitySourceRecord;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStore;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local Amazon Verified Permissions (Cedar) emulator. Policy stores, static
 * policies, schemas, identity sources, and a subset Cedar evaluator cover
 * Alchemy's resource and Bindings tests.
 *
 * @see <a href="https://docs.aws.amazon.com/verifiedpermissions/latest/apireference/API_Operations.html">Verified Permissions API</a>
 */
@ApplicationScoped
public class VerifiedPermissionsService {

    private static final Pattern HEAD = Pattern.compile(
            "(?is)^\\s*(permit|forbid)\\s*\\((.*)\\)\\s*(?:when\\s*\\{.*\\})?\\s*;?\\s*$");
    private static final Pattern UID = Pattern.compile("^(.*)::\"(.*)\"$");

    static final class Entity {
        final String type;
        final String id;

        Entity(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    static final class CedarHead {
        final boolean forbid;
        final Entity principal;
        final Entity action;
        final Entity resource;

        CedarHead(boolean forbid, Entity principal, Entity action, Entity resource) {
            this.forbid = forbid;
            this.principal = principal;
            this.action = action;
            this.resource = resource;
        }

        boolean matches(Entity principal, Entity action, Entity resource) {
            return slot(this.principal, principal)
                    && slot(this.action, action)
                    && slot(this.resource, resource);
        }

        private static boolean slot(Entity constraint, Entity request) {
            if (constraint == null) {
                return true;
            }
            if (request == null) {
                return false;
            }
            return constraint.type.equals(request.type) && constraint.id.equals(request.id);
        }
    }

    private final StorageBackend<String, PolicyStore> stores;
    private final StorageBackend<String, IdentitySourceRecord> identitySources;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public VerifiedPermissionsService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create(
                        "verifiedpermissions",
                        "verifiedpermissions-stores.json",
                        new TypeReference<Map<String, PolicyStore>>() {
                        }),
                storageFactory.create(
                        "verifiedpermissions",
                        "verifiedpermissions-identity-sources.json",
                        new TypeReference<Map<String, IdentitySourceRecord>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    VerifiedPermissionsService(
            StorageBackend<String, PolicyStore> stores,
            StorageBackend<String, IdentitySourceRecord> identitySources,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.stores = stores;
        this.identitySources = identitySources;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createPolicyStore(JsonNode request, String region) {
        String token = textOrNull(request, "clientToken");
        if (token != null) {
            for (PolicyStore existing : stores.values()) {
                if (token.equals(existing.getClientToken())) {
                    return createStoreResponse(existing);
                }
            }
        }
        String mode = validationMode(request);
        String id = newId();
        PolicyStore store = new PolicyStore();
        store.setPolicyStoreId(id);
        store.setRegion(region);
        store.setArn(regionResolver.buildArn("verifiedpermissions", region, "policy-store/" + id));
        store.setValidationMode(mode);
        store.setDescription(textOrNull(request, "description"));
        store.setDeletionProtection(textOrDefault(request, "deletionProtection", "DISABLED"));
        String now = now();
        store.setCreatedDate(now);
        store.setLastUpdatedDate(now);
        store.setClientToken(token);
        readTagMap(request.get("tags"), store.getTags());
        stores.put(id, store);
        return createStoreResponse(store);
    }

    public synchronized ObjectNode getPolicyStore(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        ObjectNode response = storeNode(store);
        if (request.path("tags").asBoolean(false)) {
            response.set("tags", tagMap(store.getTags()));
        }
        return response;
    }

    public synchronized ObjectNode updatePolicyStore(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        store.setValidationMode(validationMode(request));
        if (request.has("description") && !request.get("description").isNull()) {
            store.setDescription(textOrNull(request, "description"));
        }
        if (request.hasNonNull("deletionProtection")) {
            store.setDeletionProtection(request.get("deletionProtection").asText());
        }
        store.setLastUpdatedDate(now());
        stores.put(store.getPolicyStoreId(), store);
        return createStoreResponse(store);
    }

    public synchronized ObjectNode deletePolicyStore(JsonNode request) {
        String id = requireText(request, "policyStoreId");
        PolicyStore store = stores.get(id).orElse(null);
        if (store != null && "ENABLED".equals(store.getDeletionProtection())) {
            throw new AwsException(
                    "InvalidStateException",
                    "Policy store " + id + " has deletion protection enabled.",
                    406);
        }
        stores.delete(id);
        for (IdentitySourceRecord source : identitySources.scan(k -> k.startsWith(id + "/"))) {
            identitySources.delete(identityKey(source.getPolicyStoreId(), source.getIdentitySourceId()));
        }
        return objectMapper.createObjectNode();
    }

    /**
     * Live AWS currently rejects every alias name with ValidationException
     * ("Invalid input") — CreatePolicyStoreAlias is not generally available.
     */
    public synchronized ObjectNode createPolicyStoreAlias(JsonNode request) {
        requireText(request, "aliasName");
        requireText(request, "policyStoreId");
        throw validation("Invalid input");
    }

    public synchronized ObjectNode listPolicyStores(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("policyStores");
        stores.values().forEach(store -> items.add(storeSummary(store)));
        return response;
    }

    public synchronized ObjectNode putSchema(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        JsonNode definition = requireObject(request, "definition");
        String cedarJson = requireText(definition, "cedarJson");
        List<String> namespaces = namespacesOf(cedarJson);
        String now = now();
        if (store.getSchemaCreatedDate() == null) {
            store.setSchemaCreatedDate(now);
        }
        store.setCedarJson(cedarJson);
        store.setNamespaces(namespaces);
        store.setSchemaUpdatedDate(now);
        store.setLastUpdatedDate(now);
        stores.put(store.getPolicyStoreId(), store);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStoreId", store.getPolicyStoreId());
        ArrayNode ns = response.putArray("namespaces");
        namespaces.forEach(ns::add);
        response.put("createdDate", store.getSchemaCreatedDate());
        response.put("lastUpdatedDate", store.getSchemaUpdatedDate());
        return response;
    }

    public synchronized ObjectNode getSchema(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        if (store.getCedarJson() == null) {
            throw notFound(store.getPolicyStoreId(), "SCHEMA");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStoreId", store.getPolicyStoreId());
        response.put("schema", store.getCedarJson());
        response.put("createdDate", store.getSchemaCreatedDate());
        response.put("lastUpdatedDate", store.getSchemaUpdatedDate());
        ArrayNode ns = response.putArray("namespaces");
        store.getNamespaces().forEach(ns::add);
        return response;
    }

    public synchronized ObjectNode createPolicyTemplate(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        String token = textOrNull(request, "clientToken");
        if (token != null) {
            for (PolicyTemplate existing : store.getTemplates().values()) {
                if (token.equals(existing.getClientToken())) {
                    return templateWriteResponse(store.getPolicyStoreId(), existing);
                }
            }
        }
        String statement = requireText(request, "statement");
        parseCedar(statement);
        PolicyTemplate template = new PolicyTemplate();
        template.setPolicyTemplateId(newId());
        template.setStatement(statement);
        template.setDescription(textOrNull(request, "description"));
        template.setName(textOrNull(request, "name"));
        template.setClientToken(token);
        String now = now();
        template.setCreatedDate(now);
        template.setLastUpdatedDate(now);
        store.getTemplates().put(template.getPolicyTemplateId(), template);
        stores.put(store.getPolicyStoreId(), store);
        return templateWriteResponse(store.getPolicyStoreId(), template);
    }

    public synchronized ObjectNode getPolicyTemplate(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        PolicyTemplate template = requireTemplate(store, requireText(request, "policyTemplateId"));
        return templateReadResponse(store.getPolicyStoreId(), template);
    }

    public synchronized ObjectNode updatePolicyTemplate(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        PolicyTemplate template = requireTemplate(store, requireText(request, "policyTemplateId"));
        String statement = requireText(request, "statement");
        parseCedar(statement);
        template.setStatement(statement);
        if (request.has("description") && !request.get("description").isNull()) {
            template.setDescription(textOrNull(request, "description"));
        }
        if (request.has("name") && !request.get("name").isNull()) {
            template.setName(textOrNull(request, "name"));
        }
        template.setLastUpdatedDate(now());
        store.getTemplates().put(template.getPolicyTemplateId(), template);
        stores.put(store.getPolicyStoreId(), store);
        return templateWriteResponse(store.getPolicyStoreId(), template);
    }

    public synchronized ObjectNode deletePolicyTemplate(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        String policyTemplateId = requireText(request, "policyTemplateId");
        if (!store.getTemplates().containsKey(policyTemplateId)) {
            throw notFound(policyTemplateId, "POLICY_TEMPLATE");
        }
        store.getTemplates().remove(policyTemplateId);
        store.getPolicies().values()
                .removeIf(policy -> policyTemplateId.equals(policy.getPolicyTemplateId()));
        stores.put(store.getPolicyStoreId(), store);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listPolicyTemplates(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("policyTemplates");
        for (PolicyTemplate template : store.getTemplates().values()) {
            items.add(templateSummary(store.getPolicyStoreId(), template));
        }
        return response;
    }

    public synchronized ObjectNode createPolicy(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        if ("STRICT".equals(store.getValidationMode()) && store.getCedarJson() == null) {
            throw validation("A schema is required when the policy store validation mode is STRICT.");
        }
        JsonNode definition = requireObject(request, "definition");
        CedarPolicy policy = new CedarPolicy();
        policy.setPolicyId(newId());
        policy.setName(textOrNull(request, "name"));
        String now = now();
        policy.setCreatedDate(now);
        policy.setLastUpdatedDate(now);
        applyDefinition(store, policy, definition, true);
        store.getPolicies().put(policy.getPolicyId(), policy);
        stores.put(store.getPolicyStoreId(), store);
        return policyWriteResponse(store.getPolicyStoreId(), policy);
    }

    public synchronized ObjectNode getPolicy(JsonNode request) {
        String storeId = requireText(request, "policyStoreId");
        CedarPolicy policy = requirePolicy(storeId, requireText(request, "policyId"));
        return policyReadResponse(storeId, policy);
    }

    public synchronized ObjectNode updatePolicy(JsonNode request) {
        String storeId = requireText(request, "policyStoreId");
        PolicyStore store = requireStore(storeId);
        CedarPolicy policy = requirePolicy(storeId, requireText(request, "policyId"));
        if (!"STATIC".equals(policy.getPolicyType())) {
            throw validation("Only static policies can be updated in place.");
        }
        if (request.hasNonNull("name")) {
            policy.setName(request.get("name").asText());
        }
        JsonNode definition = request.get("definition");
        if (definition != null && !definition.isNull() && !definition.isMissingNode()) {
            applyDefinition(store, policy, definition, false);
        }
        policy.setLastUpdatedDate(now());
        store.getPolicies().put(policy.getPolicyId(), policy);
        stores.put(storeId, store);
        return policyWriteResponse(storeId, policy);
    }

    public synchronized ObjectNode deletePolicy(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        String policyId = requireText(request, "policyId");
        if (!store.getPolicies().containsKey(policyId)) {
            throw notFound(policyId, "POLICY");
        }
        store.getPolicies().remove(policyId);
        stores.put(store.getPolicyStoreId(), store);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode batchGetPolicy(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        ArrayNode errors = response.putArray("errors");
        JsonNode items = request.path("requests");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String storeId = textOrNull(item, "policyStoreId");
                String policyId = textOrNull(item, "policyId");
                PolicyStore store = storeId == null ? null : stores.get(storeId).orElse(null);
                CedarPolicy policy = store == null ? null : store.getPolicies().get(policyId);
                if (policy == null) {
                    ObjectNode error = errors.addObject();
                    error.put("code", store == null ? "POLICY_STORE_NOT_FOUND" : "POLICY_NOT_FOUND");
                    error.put("policyStoreId", storeId == null ? "" : storeId);
                    error.put("policyId", policyId == null ? "" : policyId);
                    error.put("message", "Policy not found.");
                } else {
                    results.add(batchPolicyItem(storeId, policy));
                }
            }
        }
        return response;
    }

    public synchronized ObjectNode isAuthorized(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        Entity principal = entity(request.get("principal"), "entityType", "entityId");
        Entity action = entity(request.get("action"), "actionType", "actionId");
        Entity resource = entity(request.get("resource"), "entityType", "entityId");
        return authorize(store, principal, action, resource);
    }

    public synchronized ObjectNode batchIsAuthorized(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        JsonNode items = request.path("requests");
        if (items.isArray()) {
            for (JsonNode item : items) {
                Entity principal = entity(item.get("principal"), "entityType", "entityId");
                Entity action = entity(item.get("action"), "actionType", "actionId");
                Entity resource = entity(item.get("resource"), "entityType", "entityId");
                ObjectNode result = authorize(store, principal, action, resource);
                ObjectNode row = results.addObject();
                row.set("request", item.deepCopy());
                row.set("decision", result.get("decision"));
                row.set("determiningPolicies", result.get("determiningPolicies"));
                row.set("errors", result.get("errors"));
            }
        }
        return response;
    }

    public synchronized ObjectNode batchIsAuthorizedWithToken(JsonNode request) {
        String accessToken = textOrNull(request, "accessToken");
        String identityToken = textOrNull(request, "identityToken");
        if (!isJwt(accessToken) && !isJwt(identityToken)) {
            throw validation("The token is not a valid JWT.");
        }
        requireStore(requireText(request, "policyStoreId"));
        throw validation("No identity source is configured for the policy store.");
    }

    public synchronized ObjectNode createIdentitySource(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        JsonNode configuration = requireObject(request, "configuration");
        IdentitySourceRecord source = new IdentitySourceRecord();
        source.setIdentitySourceId(newId());
        source.setPolicyStoreId(store.getPolicyStoreId());
        source.setPrincipalEntityType(textOrDefault(request, "principalEntityType", "AWS::Cognito::User"));
        source.setConfigurationJson(normalizeConfiguration(configuration).toString());
        String now = now();
        source.setCreatedDate(now);
        source.setLastUpdatedDate(now);
        identitySources.put(identityKey(source.getPolicyStoreId(), source.getIdentitySourceId()), source);
        return identityWriteResponse(source);
    }

    public synchronized ObjectNode getIdentitySource(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        IdentitySourceRecord source = identitySources
                .get(identityKey(store.getPolicyStoreId(), requireText(request, "identitySourceId")))
                .orElse(null);
        if (source == null) {
            throw notFound(request.get("identitySourceId").asText(), "IDENTITY_SOURCE");
        }
        return identityReadResponse(source);
    }

    public synchronized ObjectNode updateIdentitySource(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        String sourceId = requireText(request, "identitySourceId");
        IdentitySourceRecord source = identitySources
                .get(identityKey(store.getPolicyStoreId(), sourceId))
                .orElseThrow(() -> notFound(sourceId, "IDENTITY_SOURCE"));
        if (request.hasNonNull("principalEntityType")) {
            source.setPrincipalEntityType(request.get("principalEntityType").asText());
        }
        JsonNode configuration = request.get("updateConfiguration");
        if (configuration != null && configuration.isObject()) {
            source.setConfigurationJson(normalizeConfiguration(configuration).toString());
        }
        source.setLastUpdatedDate(now());
        identitySources.put(identityKey(store.getPolicyStoreId(), sourceId), source);
        return identityWriteResponse(source);
    }

    public synchronized ObjectNode deleteIdentitySource(JsonNode request) {
        PolicyStore store = requireStore(requireText(request, "policyStoreId"));
        String sourceId = requireText(request, "identitySourceId");
        String key = identityKey(store.getPolicyStoreId(), sourceId);
        if (identitySources.get(key).isEmpty()) {
            throw notFound(sourceId, "IDENTITY_SOURCE");
        }
        identitySources.delete(key);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        PolicyStore store = requireStoreByArn(requireText(request, "resourceArn"));
        readTagMap(request.get("tags"), store.getTags());
        store.setLastUpdatedDate(now());
        stores.put(store.getPolicyStoreId(), store);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        PolicyStore store = requireStoreByArn(requireText(request, "resourceArn"));
        JsonNode keys = request.path("tagKeys");
        if (keys.isArray()) {
            for (JsonNode key : keys) {
                store.getTags().remove(key.asText());
            }
        }
        store.setLastUpdatedDate(now());
        stores.put(store.getPolicyStoreId(), store);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode request) {
        PolicyStore store = requireStoreByArn(requireText(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagMap(store.getTags()));
        return response;
    }

    private ObjectNode authorize(PolicyStore store, Entity principal, Entity action, Entity resource) {
        List<CedarPolicy> permits = new ArrayList<>();
        List<CedarPolicy> forbids = new ArrayList<>();
        for (CedarPolicy policy : store.getPolicies().values()) {
            if (!"STATIC".equals(policy.getPolicyType()) || policy.getStatement() == null) {
                continue;
            }
            CedarHead head = parseCedar(policy.getStatement());
            if (!head.matches(principal, action, resource)) {
                continue;
            }
            if (head.forbid) {
                forbids.add(policy);
            } else {
                permits.add(policy);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode determining = response.putArray("determiningPolicies");
        response.putArray("errors");
        if (!forbids.isEmpty()) {
            response.put("decision", "DENY");
            forbids.forEach(policy -> determining.addObject().put("policyId", policy.getPolicyId()));
            return response;
        }
        if (!permits.isEmpty()) {
            response.put("decision", "ALLOW");
            permits.forEach(policy -> determining.addObject().put("policyId", policy.getPolicyId()));
            return response;
        }
        response.put("decision", "DENY");
        return response;
    }

    private void applyDefinition(PolicyStore store, CedarPolicy policy, JsonNode definition, boolean creating) {
        JsonNode staticDef = definition.get("static");
        JsonNode linked = definition.get("templateLinked");
        if (staticDef != null && !staticDef.isNull() && !staticDef.isMissingNode()) {
            String statement = requireText(staticDef, "statement");
            policy.setPolicyType("STATIC");
            policy.setStatement(statement);
            if (staticDef.has("description")) {
                policy.setDescription(textOrNull(staticDef, "description"));
            }
            CedarHead head = parseCedar(statement);
            policy.setEffect(head.forbid ? "Forbid" : "Permit");
            if (head.principal != null) {
                policy.setPrincipalEntityType(head.principal.type);
                policy.setPrincipalEntityId(head.principal.id);
            }
            if (head.resource != null) {
                policy.setResourceEntityType(head.resource.type);
                policy.setResourceEntityId(head.resource.id);
            }
            return;
        }
        if (linked != null && !linked.isNull() && !linked.isMissingNode()) {
            if (!creating) {
                throw validation("Template-linked policies cannot be updated in place.");
            }
            PolicyTemplate template = requireTemplate(store, requireText(linked, "policyTemplateId"));
            CedarHead head = parseCedar(template.getStatement());
            Entity principal = entity(linked.get("principal"), "entityType", "entityId");
            Entity resource = entity(linked.get("resource"), "entityType", "entityId");
            policy.setPolicyType("TEMPLATE_LINKED");
            policy.setPolicyTemplateId(template.getPolicyTemplateId());
            policy.setStatement("");
            policy.setEffect(head.forbid ? "Forbid" : "Permit");
            if (principal != null) {
                policy.setPrincipalEntityType(principal.type);
                policy.setPrincipalEntityId(principal.id);
            } else if (head.principal != null) {
                policy.setPrincipalEntityType(head.principal.type);
                policy.setPrincipalEntityId(head.principal.id);
            }
            if (resource != null) {
                policy.setResourceEntityType(resource.type);
                policy.setResourceEntityId(resource.id);
            } else if (head.resource != null) {
                policy.setResourceEntityType(head.resource.type);
                policy.setResourceEntityId(head.resource.id);
            }
            return;
        }
        throw validation("definition must contain static or templateLinked.");
    }

    static CedarHead parseCedar(String statement) {
        if (statement == null) {
            throw validation("statement is required.");
        }
        Matcher matcher = HEAD.matcher(statement.trim());
        if (!matcher.matches()) {
            throw validation("Invalid Cedar policy statement.");
        }
        boolean forbid = "forbid".equalsIgnoreCase(matcher.group(1));
        Entity principal = null;
        Entity action = null;
        Entity resource = null;
        for (String slot : splitSlots(matcher.group(2))) {
            String trimmed = slot.trim();
            if (trimmed.startsWith("principal")) {
                principal = parseConstraint(trimmed, "principal");
            } else if (trimmed.startsWith("action")) {
                action = parseConstraint(trimmed, "action");
            } else if (trimmed.startsWith("resource")) {
                resource = parseConstraint(trimmed, "resource");
            }
        }
        return new CedarHead(forbid, principal, action, resource);
    }

    private static Entity parseConstraint(String slot, String keyword) {
        int eq = slot.indexOf("==");
        if (eq < 0) {
            return null;
        }
        String uid = slot.substring(eq + 2).trim();
        if (uid.startsWith("?")) {
            return null;
        }
        Matcher matcher = UID.matcher(uid);
        if (!matcher.matches()) {
            throw validation("Invalid Cedar " + keyword + " UID: " + uid);
        }
        return new Entity(matcher.group(1).trim(), matcher.group(2));
    }

    private static List<String> splitSlots(String head) {
        List<String> slots = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < head.length(); i++) {
            char ch = head.charAt(i);
            if (ch == '"' && (i == 0 || head.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            }
            if (ch == ',' && !inQuotes) {
                slots.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            slots.add(current.toString());
        }
        return slots;
    }

    private ObjectNode createStoreResponse(PolicyStore store) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStoreId", store.getPolicyStoreId());
        response.put("arn", store.getArn());
        response.put("createdDate", store.getCreatedDate());
        response.put("lastUpdatedDate", store.getLastUpdatedDate());
        return response;
    }

    private ObjectNode storeNode(PolicyStore store) {
        ObjectNode response = createStoreResponse(store);
        response.putObject("validationSettings").put("mode", store.getValidationMode());
        if (store.getDescription() != null) {
            response.put("description", store.getDescription());
        }
        response.put(
                "deletionProtection",
                store.getDeletionProtection() == null ? "DISABLED" : store.getDeletionProtection());
        response.putObject("encryptionState").putObject("default");
        response.put("cedarVersion", "CEDAR_2");
        return response;
    }

    private ObjectNode storeSummary(PolicyStore store) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policyStoreId", store.getPolicyStoreId());
        node.put("arn", store.getArn());
        node.put("createdDate", store.getCreatedDate());
        node.put("lastUpdatedDate", store.getLastUpdatedDate());
        if (store.getDescription() != null) {
            node.put("description", store.getDescription());
        }
        return node;
    }

    private ObjectNode policyWriteResponse(String storeId, CedarPolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStoreId", storeId);
        response.put("policyId", policy.getPolicyId());
        response.put("policyType", policy.getPolicyType());
        if (policy.getPrincipalEntityType() != null && policy.getPrincipalEntityId() != null) {
            ObjectNode principal = response.putObject("principal");
            principal.put("entityType", policy.getPrincipalEntityType());
            principal.put("entityId", policy.getPrincipalEntityId());
        }
        if (policy.getResourceEntityType() != null && policy.getResourceEntityId() != null) {
            ObjectNode resource = response.putObject("resource");
            resource.put("entityType", policy.getResourceEntityType());
            resource.put("entityId", policy.getResourceEntityId());
        }
        response.put("createdDate", policy.getCreatedDate());
        response.put("lastUpdatedDate", policy.getLastUpdatedDate());
        if (policy.getEffect() != null) {
            response.put("effect", policy.getEffect());
        }
        return response;
    }

    private ObjectNode policyReadResponse(String storeId, CedarPolicy policy) {
        ObjectNode response = policyWriteResponse(storeId, policy);
        response.set("definition", definitionNode(policy));
        if (policy.getName() != null) {
            response.put("name", policy.getName());
        }
        return response;
    }

    private ObjectNode batchPolicyItem(String storeId, CedarPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policyStoreId", storeId);
        node.put("policyId", policy.getPolicyId());
        node.put("policyType", policy.getPolicyType());
        node.set("definition", definitionNode(policy));
        node.put("createdDate", policy.getCreatedDate());
        node.put("lastUpdatedDate", policy.getLastUpdatedDate());
        if (policy.getName() != null) {
            node.put("name", policy.getName());
        }
        return node;
    }

    private ObjectNode definitionNode(CedarPolicy policy) {
        ObjectNode definition = objectMapper.createObjectNode();
        if ("TEMPLATE_LINKED".equals(policy.getPolicyType())) {
            ObjectNode linked = definition.putObject("templateLinked");
            if (policy.getPolicyTemplateId() != null) {
                linked.put("policyTemplateId", policy.getPolicyTemplateId());
            }
            if (policy.getPrincipalEntityType() != null && policy.getPrincipalEntityId() != null) {
                ObjectNode principal = linked.putObject("principal");
                principal.put("entityType", policy.getPrincipalEntityType());
                principal.put("entityId", policy.getPrincipalEntityId());
            }
            if (policy.getResourceEntityType() != null && policy.getResourceEntityId() != null) {
                ObjectNode resource = linked.putObject("resource");
                resource.put("entityType", policy.getResourceEntityType());
                resource.put("entityId", policy.getResourceEntityId());
            }
            return definition;
        }
        ObjectNode staticDef = definition.putObject("static");
        if (policy.getDescription() != null) {
            staticDef.put("description", policy.getDescription());
        }
        staticDef.put("statement", policy.getStatement() == null ? "" : policy.getStatement());
        return definition;
    }

    private ObjectNode templateWriteResponse(String storeId, PolicyTemplate template) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyStoreId", storeId);
        response.put("policyTemplateId", template.getPolicyTemplateId());
        response.put("createdDate", template.getCreatedDate());
        response.put("lastUpdatedDate", template.getLastUpdatedDate());
        return response;
    }

    private ObjectNode templateReadResponse(String storeId, PolicyTemplate template) {
        ObjectNode response = templateWriteResponse(storeId, template);
        if (template.getDescription() != null) {
            response.put("description", template.getDescription());
        }
        response.put("statement", template.getStatement() == null ? "" : template.getStatement());
        if (template.getName() != null) {
            response.put("name", template.getName());
        }
        return response;
    }

    private ObjectNode templateSummary(String storeId, PolicyTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policyStoreId", storeId);
        node.put("policyTemplateId", template.getPolicyTemplateId());
        if (template.getDescription() != null) {
            node.put("description", template.getDescription());
        }
        node.put("createdDate", template.getCreatedDate());
        node.put("lastUpdatedDate", template.getLastUpdatedDate());
        if (template.getName() != null) {
            node.put("name", template.getName());
        }
        return node;
    }

    private PolicyTemplate requireTemplate(PolicyStore store, String policyTemplateId) {
        PolicyTemplate template = store.getTemplates().get(policyTemplateId);
        if (template == null) {
            throw notFound(policyTemplateId, "POLICY_TEMPLATE");
        }
        return template;
    }

    private ObjectNode identityWriteResponse(IdentitySourceRecord source) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("createdDate", source.getCreatedDate());
        response.put("identitySourceId", source.getIdentitySourceId());
        response.put("lastUpdatedDate", source.getLastUpdatedDate());
        response.put("policyStoreId", source.getPolicyStoreId());
        return response;
    }

    private ObjectNode identityReadResponse(IdentitySourceRecord source) {
        ObjectNode response = identityWriteResponse(source);
        response.put("principalEntityType", source.getPrincipalEntityType());
        if (source.getConfigurationJson() != null) {
            try {
                response.set("configuration", objectMapper.readTree(source.getConfigurationJson()));
            } catch (JsonProcessingException e) {
                throw new AwsException("InternalServerException", "Stored identity source is corrupt.", 500);
            }
        }
        return response;
    }

    private JsonNode normalizeConfiguration(JsonNode configuration) {
        ObjectNode copy = configuration.deepCopy();
        JsonNode oidc = copy.get("openIdConnectConfiguration");
        if (oidc instanceof ObjectNode oidcObject) {
            JsonNode selection = oidcObject.get("tokenSelection");
            if (selection instanceof ObjectNode selectionObject) {
                defaultPrincipalIdClaim(selectionObject, "identityTokenOnly");
                defaultPrincipalIdClaim(selectionObject, "accessTokenOnly");
            }
        }
        return copy;
    }

    private void defaultPrincipalIdClaim(ObjectNode tokenSelection, String field) {
        JsonNode node = tokenSelection.get(field);
        if (node instanceof ObjectNode objectNode && !objectNode.hasNonNull("principalIdClaim")) {
            objectNode.put("principalIdClaim", "sub");
        }
    }

    private List<String> namespacesOf(String cedarJson) {
        List<String> namespaces = new ArrayList<>();
        try {
            JsonNode tree = objectMapper.readTree(cedarJson);
            if (tree != null && tree.isObject()) {
                tree.fieldNames().forEachRemaining(namespaces::add);
            }
        } catch (JsonProcessingException e) {
            throw validation("definition.cedarJson is not valid JSON.");
        }
        return namespaces;
    }

    private ObjectNode tagMap(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private PolicyStore requireStore(String policyStoreId) {
        return stores.get(policyStoreId).orElseThrow(() -> notFound(policyStoreId, "POLICY_STORE"));
    }

    private PolicyStore requireStoreByArn(String arn) {
        for (PolicyStore store : stores.values()) {
            if (arn.equals(store.getArn())) {
                return store;
            }
        }
        throw notFound(arn, "POLICY_STORE");
    }

    private CedarPolicy requirePolicy(String policyStoreId, String policyId) {
        PolicyStore store = requireStore(policyStoreId);
        CedarPolicy policy = store.getPolicies().get(policyId);
        if (policy == null) {
            throw notFound(policyId, "POLICY");
        }
        return policy;
    }

    private String validationMode(JsonNode request) {
        JsonNode settings = request.path("validationSettings");
        String mode = textOrNull(settings, "mode");
        if (mode == null) {
            throw validation("validationSettings.mode is required.");
        }
        if (!"OFF".equals(mode) && !"STRICT".equals(mode)) {
            throw validation("validationSettings.mode is invalid.");
        }
        return mode;
    }

    private static Entity entity(JsonNode node, String typeField, String idField) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String type = textOrNull(node, typeField);
        String id = textOrNull(node, idField);
        if (type == null || id == null) {
            return null;
        }
        return new Entity(type, id);
    }

    private static void readTagMap(JsonNode tags, Map<String, String> dest) {
        if (tags == null || !tags.isObject() || dest == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = tags.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            dest.put(field.getKey(), field.getValue().asText(""));
        }
    }

    private static boolean isJwt(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        return parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty();
    }

    private static String identityKey(String storeId, String sourceId) {
        return storeId + "/" + sourceId;
    }

    private static JsonNode requireObject(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || !node.isObject()) {
            throw validation(field + " is required.");
        }
        return node;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " not found.",
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }
}
