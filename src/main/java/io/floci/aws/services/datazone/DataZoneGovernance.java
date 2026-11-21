package io.floci.aws.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.conflict;
import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.notFound;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.oneOf;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * DataZone governance: domain unit owners, managed policy grants, group profiles, metadata rules (which
 * {@link DataZoneCatalog} and {@link DataZoneSubscriptions} enforce), designations, project profiles, account pools,
 * connections, data products, asset filters, governed glossary terms, column attribute metadata, environment roles,
 * the domain's data export configuration, lineage graph queries and lineage job runs.
 */
@ApplicationScoped
public class DataZoneGovernance {

    static final String GRANTS = "policy-grants";
    static final String GROUPS = "group-profiles";
    static final String RULES = "rules";
    static final String DESIGNATIONS = "designations";
    static final String PROJECT_PROFILES = "project-profiles";
    static final String ACCOUNT_POOLS = "account-pools";
    static final String CONNECTIONS = "connections";
    static final String DATA_PRODUCTS = "data-products";
    static final String FILTERS = "asset-filters";
    static final String EXPORT = "data-export-configuration";

    /** Each managed policy type and the detail member that configures it. */
    private static final Map<String, String> POLICY_DETAILS = Map.ofEntries(
            Map.entry("CREATE_DOMAIN_UNIT", "createDomainUnit"),
            Map.entry("OVERRIDE_DOMAIN_UNIT_OWNERS", "overrideDomainUnitOwners"),
            Map.entry("ADD_TO_PROJECT_MEMBER_POOL", "addToProjectMemberPool"),
            Map.entry("OVERRIDE_PROJECT_OWNERS", "overrideProjectOwners"),
            Map.entry("CREATE_GLOSSARY", "createGlossary"),
            Map.entry("CREATE_FORM_TYPE", "createFormType"),
            Map.entry("CREATE_ASSET_TYPE", "createAssetType"),
            Map.entry("CREATE_PROJECT", "createProject"),
            Map.entry("CREATE_ENVIRONMENT_PROFILE", "createEnvironmentProfile"),
            Map.entry("DELEGATE_CREATE_ENVIRONMENT_PROFILE", "delegateCreateEnvironmentProfile"),
            Map.entry("CREATE_ENVIRONMENT", "createEnvironment"),
            Map.entry("CREATE_ENVIRONMENT_FROM_BLUEPRINT", "createEnvironmentFromBlueprint"),
            Map.entry("CREATE_PROJECT_FROM_PROJECT_PROFILE", "createProjectFromProjectProfile"),
            Map.entry("USE_ASSET_TYPE", "useAssetType"));

    /** The connection type each connection properties member makes. */
    private static final Map<String, String> CONNECTION_TYPES = Map.ofEntries(
            Map.entry("athenaProperties", "ATHENA"), Map.entry("glueProperties", "GLUE"),
            Map.entry("hyperPodProperties", "HYPERPOD"), Map.entry("iamProperties", "IAM"),
            Map.entry("redshiftProperties", "REDSHIFT"), Map.entry("sparkEmrProperties", "SPARK"),
            Map.entry("sparkGlueProperties", "SPARK"), Map.entry("s3Properties", "S3"),
            Map.entry("snowflakeProperties", "SNOWFLAKE"), Map.entry("amazonQProperties", "AMAZON_Q"),
            Map.entry("mlflowProperties", "MLFLOW"), Map.entry("workflowsMwaaProperties", "WORKFLOWS_MWAA"),
            Map.entry("workflowsServerlessProperties", "WORKFLOWS_SERVERLESS"),
            Map.entry("lakehouseProperties", "LAKEHOUSE"), Map.entry("vpcProperties", "VPC"),
            Map.entry("gitProperties", "GIT"));

    private final DataZoneStore store;
    private final DataZoneService core;
    private final DataZoneCatalog catalog;
    private final DataZoneEnvironments environments;
    private final DataZoneRules rules;
    private final DataZoneIngestion ingestion;
    private final Instance<IamService> iam;

    @Inject
    public DataZoneGovernance(DataZoneStore store, DataZoneService core, DataZoneCatalog catalog,
                              DataZoneEnvironments environments, DataZoneRules rules, DataZoneIngestion ingestion,
                              Instance<IamService> iam) {
        this.store = store;
        this.core = core;
        this.catalog = catalog;
        this.environments = environments;
        this.rules = rules;
        this.ingestion = ingestion;
        this.iam = iam;
    }

    private String domainId(ObjectNode input, String region, String operation, boolean write) {
        String identifier = text(input, "domainIdentifier", 64);
        return (write ? core.available(region, identifier, operation) : core.domain(region, identifier, operation))
                .path("id").asText();
    }

    // ---------------------------------------------------------------- entity owners

    private ObjectNode unit(String region, String domainId, ObjectNode input) {
        oneOf(input, "entityType", Set.of("DOMAIN_UNIT"), null);
        return store.require(region, domainId, DataZoneService.UNITS, text(input, "entityIdentifier", 256),
                "Domain unit");
    }

    private ObjectNode owner(String region, String domainId, JsonNode owner) {
        ObjectNode result = store.object();
        if (owner.has("user")) {
            String identifier = text(owner.get("user"), "userIdentifier", 1024);
            String userId = core.profileFor(region, domainId, identifier).map(profile -> profile.path("id")
                    .asText()).orElseGet(() -> AwsArnUtils.isArn(identifier)
                    ? core.ensureProfile(region, domainId, identifier, "IAM_ROLE").path("id").asText() : null);
            if (userId == null) {
                throw notFound("User " + identifier + " was not found.");
            }
            result.putObject("user").put("userId", userId);
        } else if (owner.has("group")) {
            String groupId = text(owner.get("group"), "groupIdentifier", 1024);
            result.putObject("group").put("groupId", groupProfile(region, domainId, groupId).path("id").asText());
        } else {
            throw invalid("owner must name a user or a group.");
        }
        return result;
    }

    public synchronized ObjectNode addEntityOwner(ObjectNode input, String region) {
        String domainId = domainId(input, region, "AddEntityOwner", true);
        ObjectNode unit = unit(region, domainId, input);
        ObjectNode owner = owner(region, domainId, input.path("owner"));
        ArrayNode owners = (ArrayNode) unit.withArray("owners");
        for (JsonNode existing : owners) {
            if (existing.equals(owner)) {
                return store.object();
            }
        }
        owners.add(owner);
        unit.put("lastUpdatedAt", now());
        store.put(region, domainId, DataZoneService.UNITS, unit.path("id").asText(), unit);
        return store.object();
    }

    public synchronized ObjectNode removeEntityOwner(ObjectNode input, String region) {
        String domainId = domainId(input, region, "RemoveEntityOwner", true);
        ObjectNode unit = unit(region, domainId, input);
        ObjectNode owner = owner(region, domainId, input.path("owner"));
        ArrayNode owners = (ArrayNode) unit.withArray("owners");
        int index = -1;
        for (int i = 0; i < owners.size(); i++) {
            if (owners.get(i).equals(owner)) {
                index = i;
            }
        }
        if (index < 0) {
            throw notFound("The owner is not an owner of domain unit " + unit.path("id").asText() + ".");
        }
        if (owners.size() == 1) {
            throw invalid("A domain unit must keep at least one owner.");
        }
        owners.remove(index);
        unit.put("lastUpdatedAt", now());
        store.put(region, domainId, DataZoneService.UNITS, unit.path("id").asText(), unit);
        return store.object();
    }

    public synchronized ObjectNode listEntityOwners(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListEntityOwners", false);
        ObjectNode unit = unit(region, domainId, input);
        List<ObjectNode> items = new ArrayList<>();
        unit.path("owners").forEach(owner -> items.add(((ObjectNode) owner).deepCopy()));
        return store.page(items, input, "owners", JsonNode::toString);
    }

    public synchronized ObjectNode updateRootDomainUnitOwner(ObjectNode input, String region) {
        ObjectNode domain = core.available(region, text(input, "domainIdentifier", 64), "UpdateRootDomainUnitOwner");
        String domainId = domain.path("id").asText();
        ObjectNode unit = store.require(region, domainId, DataZoneService.UNITS, domain.path("rootDomainUnitId")
                .asText(), "Domain unit");
        String current = userId(region, domainId, text(input, "currentOwner", 1024));
        String next = userId(region, domainId, text(input, "newOwner", 1024));
        ArrayNode owners = (ArrayNode) unit.withArray("owners");
        boolean replaced = false;
        for (JsonNode owner : owners) {
            if (current.equals(owner.path("user").path("userId").asText())) {
                ((ObjectNode) owner.get("user")).put("userId", next);
                replaced = true;
            }
        }
        if (!replaced) {
            throw invalid("User " + current + " does not own the root domain unit.");
        }
        store.put(region, domainId, DataZoneService.UNITS, unit.path("id").asText(), unit);
        return store.object();
    }

    private String userId(String region, String domainId, String identifier) {
        return core.profileFor(region, domainId, identifier).map(profile -> profile.path("id").asText())
                .orElseGet(() -> AwsArnUtils.isArn(identifier)
                        ? core.ensureProfile(region, domainId, identifier, "IAM_ROLE").path("id").asText()
                        : identifier);
    }

    // ---------------------------------------------------------------- policy grants

    private void requireEntity(String region, String domainId, String type, String id) {
        switch (type) {
            case "DOMAIN_UNIT" -> store.require(region, domainId, DataZoneService.UNITS, id, "Domain unit");
            case "ENVIRONMENT_BLUEPRINT_CONFIGURATION" -> {
                String blueprint = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                store.require(region, domainId, DataZoneEnvironments.CONFIGURATIONS,
                        environments.blueprint(region, domainId, blueprint).path("id").asText(),
                        "Environment blueprint configuration");
            }
            case "ENVIRONMENT_PROFILE" -> store.require(region, domainId, DataZoneEnvironments.PROFILES, id,
                    "Environment profile");
            case "ASSET_TYPE" -> catalog.assetType(region, domainId, id, null);
            default -> throw invalid("Unsupported entityType " + type + ".");
        }
    }

    public synchronized ObjectNode addPolicyGrant(ObjectNode input, String region) {
        String domainId = domainId(input, region, "AddPolicyGrant", true);
        String entityType = oneOf(input, "entityType", Set.of("DOMAIN_UNIT", "ENVIRONMENT_BLUEPRINT_CONFIGURATION",
                "ENVIRONMENT_PROFILE", "ASSET_TYPE"), null);
        String entityId = text(input, "entityIdentifier", 256);
        requireEntity(region, domainId, entityType, entityId);
        String policyType = oneOf(input, "policyType", POLICY_DETAILS.keySet(), null);
        JsonNode detail = input.path("detail");
        if (!detail.isObject() || detail.size() != 1 || !detail.has(POLICY_DETAILS.get(policyType))) {
            throw invalid("The detail of a " + policyType + " grant must be " + POLICY_DETAILS.get(policyType)
                    + ".");
        }
        JsonNode principal = input.path("principal");
        if (!principal.isObject() || principal.size() != 1) {
            throw invalid("principal must name one user, group, project or domain unit.");
        }
        principal.path("project").path("projectIdentifier").textValue();
        if (principal.path("project").hasNonNull("projectIdentifier")) {
            core.project(region, domainId, principal.get("project").get("projectIdentifier").asText());
        }
        if (principal.path("domainUnit").hasNonNull("domainUnitIdentifier")) {
            store.require(region, domainId, DataZoneService.UNITS, principal.get("domainUnit")
                    .get("domainUnitIdentifier").asText(), "Domain unit");
        }
        for (ObjectNode existing : store.list(region, domainId, GRANTS)) {
            if (entityType.equals(existing.path("_").path("entityType").asText())
                    && entityId.equals(existing.path("_").path("entityId").asText())
                    && policyType.equals(existing.path("_").path("policyType").asText())
                    && principal.equals(existing.path("principal"))) {
                return store.object().put("grantId", existing.path("grantId").asText());
            }
        }
        String id = store.id();
        ObjectNode grant = store.object();
        grant.set("principal", principal.deepCopy());
        grant.set("detail", detail.deepCopy());
        grant.put("createdAt", now());
        grant.put("createdBy", core.callerProfileId(region, domainId));
        grant.put("grantId", id);
        grant.putObject("_").put("entityType", entityType).put("entityId", entityId).put("policyType", policyType);
        store.put(region, domainId, GRANTS, id, grant);
        return store.object().put("grantId", id);
    }

    public synchronized ObjectNode removePolicyGrant(ObjectNode input, String region) {
        String domainId = domainId(input, region, "RemovePolicyGrant", true);
        String entityType = text(input, "entityType", 64);
        String entityId = text(input, "entityIdentifier", 256);
        String policyType = oneOf(input, "policyType", POLICY_DETAILS.keySet(), null);
        String grantId = optional(input, "grantIdentifier");
        boolean removed = false;
        for (ObjectNode grant : store.list(region, domainId, GRANTS)) {
            JsonNode meta = grant.path("_");
            if (entityType.equals(meta.path("entityType").asText()) && entityId.equals(meta.path("entityId").asText())
                    && policyType.equals(meta.path("policyType").asText())
                    && input.path("principal").equals(grant.path("principal"))
                    && (grantId == null || grantId.equals(grant.path("grantId").asText()))) {
                store.delete(region, domainId, GRANTS, grant.path("grantId").asText());
                removed = true;
            }
        }
        if (!removed) {
            throw notFound("No " + policyType + " grant to the principal exists on " + entityId + ".");
        }
        return store.object();
    }

    public synchronized ObjectNode listPolicyGrants(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListPolicyGrants", false);
        String entityType = text(input, "entityType", 64);
        String entityId = text(input, "entityIdentifier", 256);
        String policyType = oneOf(input, "policyType", POLICY_DETAILS.keySet(), null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode grant : store.list(region, domainId, GRANTS)) {
            JsonNode meta = grant.path("_");
            if (entityType.equals(meta.path("entityType").asText()) && entityId.equals(meta.path("entityId").asText())
                    && policyType.equals(meta.path("policyType").asText())) {
                items.add(store.view(grant));
            }
        }
        return store.page(items, input, "grantList", item -> item.path("grantId").asText());
    }

    // ---------------------------------------------------------------- group profiles

    ObjectNode groupProfile(String region, String domainId, String identifier) {
        for (ObjectNode group : store.list(region, domainId, GROUPS)) {
            if (identifier.equals(group.path("id").asText()) || identifier.equals(group.path("groupName").asText())
                    || identifier.equals(group.path("rolePrincipalArn").asText())
                    || identifier.equals(group.path("_").path("groupIdentifier").asText())) {
                return group;
            }
        }
        throw notFound("Group profile " + identifier + " was not found.");
    }

    public synchronized ObjectNode createGroupProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateGroupProfile", true);
        String group = optional(input, "groupIdentifier");
        String role = optional(input, "rolePrincipalArn");
        if ((group == null) == (role == null)) {
            throw invalid("Specify exactly one of groupIdentifier and rolePrincipalArn.");
        }
        String identifier = group != null ? group : role;
        try {
            groupProfile(region, domainId, identifier);
            throw conflict("A group profile for " + identifier + " already exists.");
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        ObjectNode profile = store.object();
        profile.put("domainId", domainId);
        profile.put("id", store.id());
        profile.put("status", "ASSIGNED");
        if (group != null) {
            profile.put("groupName", group);
        } else {
            if (iam.isResolvable()) {
                try {
                    iam.get().getRole(role.substring(role.lastIndexOf('/') + 1));
                } catch (AwsException e) {
                    throw invalid("Role " + role + " does not exist.");
                }
            }
            profile.put("groupName", role.substring(role.lastIndexOf('/') + 1));
            profile.put("rolePrincipalArn", role);
            profile.put("rolePrincipalId", "AROA" + store.id(17).toUpperCase(Locale.ROOT));
        }
        profile.put("createdAt", now());
        profile.putObject("_").put("groupIdentifier", identifier).put("groupType", group != null ? "SSO_GROUP"
                : "IAM_ROLE_SESSION_GROUP");
        store.put(region, domainId, GROUPS, profile.path("id").asText(), profile);
        return groupView(profile);
    }

    private ObjectNode groupView(ObjectNode profile) {
        ObjectNode view = store.view(profile);
        view.remove("createdAt");
        return view;
    }

    public synchronized ObjectNode getGroupProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetGroupProfile", false);
        return groupView(groupProfile(region, domainId, text(input, "groupIdentifier", 1024)));
    }

    public synchronized ObjectNode updateGroupProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateGroupProfile", true);
        ObjectNode profile = groupProfile(region, domainId, text(input, "groupIdentifier", 1024));
        profile.put("status", oneOf(input, "status", Set.of("ASSIGNED", "NOT_ASSIGNED"), null));
        store.put(region, domainId, GROUPS, profile.path("id").asText(), profile);
        return groupView(profile);
    }

    public synchronized ObjectNode searchGroupProfiles(ObjectNode input, String region) {
        String domainId = domainId(input, region, "SearchGroupProfiles", false);
        String type = oneOf(input, "groupType", Set.of("SSO_GROUP", "DATAZONE_SSO_GROUP", "IAM_ROLE_SESSION_GROUP"),
                null);
        String text = optional(input, "searchText");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(region, domainId, GROUPS)) {
            String profileType = profile.path("_").path("groupType").asText();
            boolean typed = "IAM_ROLE_SESSION_GROUP".equals(type) ? "IAM_ROLE_SESSION_GROUP".equals(profileType)
                    : "SSO_GROUP".equals(profileType);
            if (typed && (text == null || profile.path("groupName").asText().toLowerCase(Locale.ROOT)
                    .contains(text.toLowerCase(Locale.ROOT)))) {
                items.add(groupView(profile));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- rules

    public synchronized ObjectNode createRule(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateRule", true);
        String name = text(input, "name", 256);
        JsonNode target = input.path("target").path("domainUnitTarget");
        store.require(region, domainId, DataZoneService.UNITS, text(target, "domainUnitId", 256), "Domain unit");
        String action = oneOf(input, "action", Set.of("CREATE_LISTING_CHANGE_SET", "CREATE_SUBSCRIPTION_REQUEST"),
                null);
        String ruleType = ruleType(input.path("detail"));
        validateScope(region, domainId, input.path("scope"));
        double now = now();
        ObjectNode rule = store.object();
        rule.put("identifier", store.id());
        rule.put("revision", "1");
        rule.put("name", name);
        rule.put("ruleType", ruleType);
        rule.set("target", input.get("target").deepCopy());
        rule.put("action", action);
        rule.set("scope", input.get("scope").deepCopy());
        rule.set("detail", input.get("detail").deepCopy());
        rule.put("targetType", "DOMAIN_UNIT");
        if (present(input, "description")) {
            rule.put("description", input.get("description").asText());
        }
        String creator = core.callerProfileId(region, domainId);
        rule.put("createdAt", now);
        rule.put("createdBy", creator);
        rule.put("updatedAt", now);
        rule.put("lastUpdatedBy", creator);
        store.put(region, domainId, RULES, rule.path("identifier").asText(), rule);
        ObjectNode view = store.view(rule);
        view.remove("revision");
        view.remove("updatedAt");
        view.remove("lastUpdatedBy");
        return view;
    }

    private String ruleType(JsonNode detail) {
        if (detail.has("metadataFormEnforcementDetail")) {
            for (JsonNode form : detail.get("metadataFormEnforcementDetail").path("requiredMetadataForms")) {
                text(form, "typeIdentifier", 385);
                text(form, "typeRevision", 64);
            }
            return "METADATA_FORM_ENFORCEMENT";
        }
        if (detail.has("glossaryTermEnforcementDetail")) {
            return "GLOSSARY_TERM_ENFORCEMENT";
        }
        throw invalid("detail must be a metadataFormEnforcementDetail or a glossaryTermEnforcementDetail.");
    }

    private void validateScope(String region, String domainId, JsonNode scope) {
        if (!scope.isObject()) {
            throw invalid("scope is required.");
        }
        JsonNode projects = scope.path("project");
        if ("SPECIFIC".equals(projects.path("selectionMode").asText())) {
            for (JsonNode project : projects.path("specificProjects")) {
                core.project(region, domainId, project.asText());
            }
        }
        JsonNode assetTypes = scope.path("assetType");
        if ("SPECIFIC".equals(assetTypes.path("selectionMode").asText())) {
            for (JsonNode type : assetTypes.path("specificAssetTypes")) {
                catalog.assetType(region, domainId, type.asText(), null);
            }
        }
    }

    private ObjectNode rule(String region, String domainId, String id) {
        return store.require(region, domainId, RULES, id, "Rule");
    }

    public synchronized ObjectNode getRule(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetRule", false);
        ObjectNode rule = rule(region, domainId, text(input, "identifier", 64));
        String revision = optional(input, "revision");
        if (revision != null && !revision.equals(rule.path("revision").asText())) {
            throw notFound("Revision " + revision + " of rule " + rule.path("identifier").asText()
                    + " was not found.");
        }
        return store.view(rule);
    }

    public synchronized ObjectNode updateRule(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateRule", true);
        ObjectNode rule = rule(region, domainId, text(input, "identifier", 64));
        for (String field : List.of("name", "description")) {
            if (present(input, field)) {
                rule.put(field, input.get(field).asText());
            }
        }
        if (input.has("scope")) {
            validateScope(region, domainId, input.get("scope"));
            rule.set("scope", input.get("scope").deepCopy());
        }
        if (input.has("detail")) {
            if (!rule.path("ruleType").asText().equals(ruleType(input.get("detail")))) {
                throw invalid("A rule's type cannot change.");
            }
            rule.set("detail", input.get("detail").deepCopy());
        }
        if (input.has("includeChildDomainUnits")) {
            ((ObjectNode) rule.path("target").path("domainUnitTarget")).put("includeChildDomainUnits",
                    input.get("includeChildDomainUnits").asBoolean());
        }
        rule.put("revision", String.valueOf(rule.path("revision").asInt() + 1));
        rule.put("updatedAt", now());
        rule.put("lastUpdatedBy", core.callerProfileId(region, domainId));
        store.put(region, domainId, RULES, rule.path("identifier").asText(), rule);
        return store.view(rule);
    }

    public synchronized ObjectNode deleteRule(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteRule", true);
        store.delete(region, domainId, RULES, rule(region, domainId, text(input, "identifier", 64))
                .path("identifier").asText());
        return store.object();
    }

    public synchronized ObjectNode listRules(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListRules", false);
        oneOf(input, "targetType", Set.of("DOMAIN_UNIT"), null);
        String unit = text(input, "targetIdentifier", 256);
        Set<String> projects = DataZoneRules.values(input.path("projectIds"));
        Set<String> assetTypes = DataZoneRules.values(input.path("assetTypes"));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode rule : store.list(region, domainId, RULES)) {
            JsonNode target = rule.path("target").path("domainUnitTarget");
            boolean targeted = unit.equals(target.path("domainUnitId").asText())
                    || (input.path("includeCascaded").asBoolean(false) && target.path("includeChildDomainUnits")
                    .asBoolean(false) && rules.isAncestor(region, domainId, target.path("domainUnitId").asText(),
                    unit));
            if (!targeted || (present(input, "ruleType") && !input.get("ruleType").asText().equals(rule
                    .path("ruleType").asText())) || (present(input, "action") && !input.get("action").asText()
                    .equals(rule.path("action").asText()))) {
                continue;
            }
            JsonNode scope = rule.path("scope");
            if (!projects.isEmpty() && "SPECIFIC".equals(scope.path("project").path("selectionMode").asText())
                    && DataZoneRules.values(scope.path("project").path("specificProjects")).stream()
                    .noneMatch(projects::contains)) {
                continue;
            }
            if (!assetTypes.isEmpty() && "SPECIFIC".equals(scope.path("assetType").path("selectionMode").asText())
                    && DataZoneRules.values(scope.path("assetType").path("specificAssetTypes")).stream()
                    .noneMatch(assetTypes::contains)) {
                continue;
            }
            if (input.has("dataProduct") && input.get("dataProduct").asBoolean() != scope.path("dataProduct")
                    .asBoolean(false)) {
                continue;
            }
            items.add(store.view(rule, "identifier", "revision", "ruleType", "name", "targetType", "target", "action",
                    "scope", "updatedAt", "lastUpdatedBy"));
        }
        return store.page(items, input, "items", item -> item.path("identifier").asText());
    }

    // ---------------------------------------------------------------- designations

    public synchronized ObjectNode createDesignation(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateDesignation", true);
        String name = text(input, "name", 256);
        for (ObjectNode existing : store.list(region, domainId, DESIGNATIONS)) {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("A designation named " + name + " already exists.");
            }
        }
        String policy = text(input, "policy", 10240);
        try {
            if (!store.mapper().readTree(policy).isObject()) {
                throw invalid("policy must be a JSON policy document.");
            }
        } catch (JsonProcessingException e) {
            throw invalid("policy must be a JSON policy document.");
        }
        double now = now();
        ObjectNode designation = store.object();
        designation.put("domainId", domainId);
        designation.put("id", store.id());
        designation.put("name", name);
        if (present(input, "description")) {
            designation.put("description", input.get("description").asText());
        }
        designation.put("designationType", "USER_CREATED");
        designation.put("policy", policy);
        designation.put("deletionProtection", input.path("deletionProtection").asBoolean(false));
        designation.put("createdBy", core.callerProfileId(region, domainId));
        designation.put("createdAt", now);
        designation.put("lastUpdatedAt", now);
        store.put(region, domainId, DESIGNATIONS, designation.path("id").asText(), designation);
        return store.view(designation);
    }

    public synchronized ObjectNode getDesignation(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetDesignation", false);
        return store.view(store.require(region, domainId, DESIGNATIONS, text(input, "identifier", 64),
                "Designation"));
    }

    public synchronized ObjectNode updateDesignation(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateDesignation", true);
        ObjectNode designation = store.require(region, domainId, DESIGNATIONS, text(input, "identifier", 64),
                "Designation");
        for (String field : List.of("name", "description", "policy")) {
            if (present(input, field)) {
                designation.put(field, input.get(field).asText());
            }
        }
        if (input.has("deletionProtection")) {
            designation.put("deletionProtection", input.get("deletionProtection").asBoolean());
        }
        designation.put("lastUpdatedAt", now());
        store.put(region, domainId, DESIGNATIONS, designation.path("id").asText(), designation);
        return store.view(designation);
    }

    public synchronized ObjectNode deleteDesignation(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteDesignation", true);
        ObjectNode designation = store.require(region, domainId, DESIGNATIONS, text(input, "identifier", 64),
                "Designation");
        if (designation.path("deletionProtection").asBoolean(false)) {
            throw invalid("Designation " + designation.path("id").asText() + " has deletion protection.");
        }
        for (ObjectNode grant : store.list(region, domainId, GRANTS)) {
            if (designation.path("id").asText().equals(grant.path("principal").path("project")
                    .path("userCreatedDesignationId").asText())) {
                throw conflict("Designation " + designation.path("id").asText() + " is used by policy grant "
                        + grant.path("grantId").asText() + ".");
            }
        }
        store.delete(region, domainId, DESIGNATIONS, designation.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listDesignations(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListDesignations", false);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode designation : store.list(region, domainId, DESIGNATIONS)) {
            ObjectNode summary = store.view(designation, "domainId", "id", "name", "description", "designationType",
                    "deletionProtection", "createdBy", "createdAt");
            summary.put("updatedAt", designation.path("lastUpdatedAt").asDouble());
            items.add(summary);
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- project profiles

    public synchronized ObjectNode createProjectProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateProjectProfile", true);
        String name = text(input, "name", 64);
        for (ObjectNode existing : store.list(region, domainId, PROJECT_PROFILES)) {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("A project profile named " + name + " already exists.");
            }
        }
        ObjectNode profile = store.object();
        profile.put("domainId", domainId);
        profile.put("id", store.id());
        profile.put("name", name);
        applyProjectProfile(region, domainId, profile, input);
        if (!profile.has("status")) {
            profile.put("status", "ENABLED");
        }
        double now = now();
        profile.put("createdBy", core.callerProfileId(region, domainId));
        profile.put("createdAt", now);
        profile.put("lastUpdatedAt", now);
        if (!profile.has("domainUnitId")) {
            store.domain(region, domainId).ifPresent(domain -> profile.put("domainUnitId",
                    domain.path("rootDomainUnitId").asText()));
        }
        store.put(region, domainId, PROJECT_PROFILES, profile.path("id").asText(), profile);
        return store.view(profile);
    }

    private void applyProjectProfile(String region, String domainId, ObjectNode profile, ObjectNode input) {
        for (String field : List.of("description", "projectResourceTagsDescription")) {
            if (present(input, field)) {
                profile.put(field, input.get(field).asText());
            }
        }
        if (present(input, "status")) {
            profile.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), null));
        }
        if (input.has("projectResourceTags")) {
            profile.set("projectResourceTags", input.get("projectResourceTags").deepCopy());
        }
        if (input.has("allowCustomProjectResourceTags")) {
            profile.put("allowCustomProjectResourceTags", input.get("allowCustomProjectResourceTags").asBoolean());
        }
        if (present(input, "domainUnitIdentifier")) {
            store.require(region, domainId, DataZoneService.UNITS, input.get("domainUnitIdentifier").asText(),
                    "Domain unit");
            profile.put("domainUnitId", input.get("domainUnitIdentifier").asText());
        }
        if (input.has("environmentConfigurations")) {
            ArrayNode configurations = store.array();
            for (JsonNode configuration : input.get("environmentConfigurations")) {
                ObjectNode copy = ((ObjectNode) configuration).deepCopy();
                text(configuration, "name", 64);
                environments.blueprint(region, domainId, text(configuration, "environmentBlueprintId", 64));
                if (!copy.hasNonNull("id")) {
                    copy.put("id", store.id());
                }
                if (!copy.hasNonNull("deploymentMode")) {
                    copy.put("deploymentMode", "ON_CREATE");
                }
                configurations.add(copy);
            }
            profile.set("environmentConfigurations", configurations);
        }
    }

    public synchronized ObjectNode getProjectProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetProjectProfile", false);
        return store.view(store.require(region, domainId, PROJECT_PROFILES, text(input, "identifier", 64),
                "Project profile"));
    }

    public synchronized ObjectNode updateProjectProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateProjectProfile", true);
        ObjectNode profile = store.require(region, domainId, PROJECT_PROFILES, text(input, "identifier", 64),
                "Project profile");
        if (present(input, "name")) {
            profile.put("name", input.get("name").asText());
        }
        applyProjectProfile(region, domainId, profile, input);
        profile.put("lastUpdatedAt", now());
        store.put(region, domainId, PROJECT_PROFILES, profile.path("id").asText(), profile);
        return store.view(profile);
    }

    public synchronized ObjectNode deleteProjectProfile(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteProjectProfile", true);
        ObjectNode profile = store.require(region, domainId, PROJECT_PROFILES, text(input, "identifier", 64),
                "Project profile");
        for (ObjectNode project : store.list(region, domainId, DataZoneService.PROJECTS)) {
            if (profile.path("id").asText().equals(project.path("projectProfileId").asText())) {
                throw conflict("Project profile " + profile.path("id").asText() + " is used by project "
                        + project.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, PROJECT_PROFILES, profile.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listProjectProfiles(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListProjectProfiles", false);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(region, domainId, PROJECT_PROFILES)) {
            if (!present(input, "name") || input.get("name").asText().equals(profile.path("name").asText())) {
                items.add(store.view(profile, "domainId", "id", "name", "description", "status", "createdBy",
                        "createdAt", "lastUpdatedAt", "domainUnitId"));
            }
        }
        sort(items, input, "name");
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    private static void sort(List<ObjectNode> items, JsonNode input, String nameField) {
        Comparator<ObjectNode> order = "NAME".equals(input.path("sortBy").asText())
                ? Comparator.comparing(item -> item.path(nameField).asText())
                : Comparator.comparing(item -> item.path("createdAt").asDouble());
        items.sort("DESCENDING".equals(input.path("sortOrder").asText()) ? order.reversed() : order);
    }

    // ---------------------------------------------------------------- account pools

    public synchronized ObjectNode createAccountPool(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateAccountPool", true);
        String name = text(input, "name", 256);
        for (ObjectNode existing : store.list(region, domainId, ACCOUNT_POOLS)) {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("An account pool named " + name + " already exists.");
            }
        }
        ObjectNode pool = store.object();
        pool.put("domainId", domainId);
        pool.put("name", name);
        pool.put("id", store.id());
        applyAccountPool(pool, input);
        String creator = core.callerProfileId(region, domainId);
        double now = now();
        pool.put("createdBy", creator);
        pool.put("createdAt", now);
        pool.put("lastUpdatedAt", now);
        pool.put("updatedBy", creator);
        store.domain(region, domainId).ifPresent(domain -> pool.put("domainUnitId", domain.path("rootDomainUnitId")
                .asText()));
        store.put(region, domainId, ACCOUNT_POOLS, pool.path("id").asText(), pool);
        return store.view(pool);
    }

    private void applyAccountPool(ObjectNode pool, ObjectNode input) {
        if (present(input, "description")) {
            pool.put("description", input.get("description").asText());
        }
        if (present(input, "resolutionStrategy") || !pool.has("resolutionStrategy")) {
            pool.put("resolutionStrategy", oneOf(input, "resolutionStrategy", Set.of("MANUAL"), null));
        }
        if (input.has("accountSource")) {
            JsonNode source = input.get("accountSource");
            if (source.has("accounts")) {
                for (JsonNode account : source.get("accounts")) {
                    if (!text(account, "awsAccountId", 12).matches("^\\d{12}$")) {
                        throw invalid("awsAccountId must be 12 digits.");
                    }
                    if (!account.path("supportedRegions").isArray() || account.get("supportedRegions").isEmpty()) {
                        throw invalid("supportedRegions is required.");
                    }
                }
            } else if (source.has("customAccountPoolHandler")) {
                text(source.get("customAccountPoolHandler"), "lambdaFunctionArn", 2048);
            } else {
                throw invalid("accountSource must list accounts or name a customAccountPoolHandler.");
            }
            pool.set("accountSource", source.deepCopy());
        } else if (!pool.has("accountSource")) {
            throw invalid("accountSource is required.");
        }
    }

    public synchronized ObjectNode getAccountPool(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetAccountPool", false);
        return store.view(store.require(region, domainId, ACCOUNT_POOLS, text(input, "identifier", 64),
                "Account pool"));
    }

    public synchronized ObjectNode updateAccountPool(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateAccountPool", true);
        ObjectNode pool = store.require(region, domainId, ACCOUNT_POOLS, text(input, "identifier", 64),
                "Account pool");
        if (present(input, "name")) {
            pool.put("name", input.get("name").asText());
        }
        applyAccountPool(pool, input);
        pool.put("lastUpdatedAt", now());
        pool.put("updatedBy", core.callerProfileId(region, domainId));
        store.put(region, domainId, ACCOUNT_POOLS, pool.path("id").asText(), pool);
        return store.view(pool);
    }

    public synchronized ObjectNode deleteAccountPool(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteAccountPool", true);
        ObjectNode pool = store.require(region, domainId, ACCOUNT_POOLS, text(input, "identifier", 64),
                "Account pool");
        for (ObjectNode profile : store.list(region, domainId, PROJECT_PROFILES)) {
            for (JsonNode configuration : profile.path("environmentConfigurations")) {
                if (DataZoneRules.values(configuration.path("accountPools")).contains(pool.path("id").asText())) {
                    throw conflict("Account pool " + pool.path("id").asText() + " is used by project profile "
                            + profile.path("id").asText() + ".");
                }
            }
        }
        store.delete(region, domainId, ACCOUNT_POOLS, pool.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listAccountPools(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListAccountPools", false);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode pool : store.list(region, domainId, ACCOUNT_POOLS)) {
            if (!present(input, "name") || input.get("name").asText().equals(pool.path("name").asText())) {
                items.add(store.view(pool, "domainId", "id", "name", "resolutionStrategy", "domainUnitId",
                        "createdBy", "updatedBy", "createdAt"));
            }
        }
        sort(items, input, "name");
        items.forEach(item -> item.remove("createdAt"));
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode listAccountsInAccountPool(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListAccountsInAccountPool", false);
        ObjectNode pool = store.require(region, domainId, ACCOUNT_POOLS, text(input, "identifier", 64),
                "Account pool");
        List<ObjectNode> items = new ArrayList<>();
        pool.path("accountSource").path("accounts").forEach(account -> items.add(((ObjectNode) account)
                .deepCopy()));
        return store.page(items, input, "items", item -> item.path("awsAccountId").asText());
    }

    // ---------------------------------------------------------------- connections

    public synchronized ObjectNode createConnection(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateConnection", true);
        String name = text(input, "name", 64);
        String environmentId = optional(input, "environmentIdentifier");
        String projectId = null;
        if (environmentId != null) {
            ObjectNode environment = environments.environment(region, domainId, environmentId);
            projectId = environment.path("projectId").asText();
            core.requireMember(region, domainId, projectId, "CreateConnection");
        }
        String scope = oneOf(input, "scope", Set.of("DOMAIN", "PROJECT"), environmentId != null ? "PROJECT"
                : "DOMAIN");
        for (ObjectNode existing : store.list(region, domainId, CONNECTIONS)) {
            if (name.equals(existing.path("name").asText()) && Objects.equals(projectId,
                    existing.path("projectId").asText(null))) {
                throw conflict("A connection named " + name + " already exists.");
            }
        }
        JsonNode props = input.path("props");
        if (!props.isObject() || props.size() != 1) {
            throw invalid("props must hold exactly one connection type's properties.");
        }
        String member = props.fieldNames().next();
        String type = CONNECTION_TYPES.get(member);
        if (type == null) {
            throw invalid("Unsupported connection properties " + member + ".");
        }
        if ("GLUE".equals(type)) {
            type = props.path(member).path("glueConnectionInput").path("connectionType").asText("GLUE");
        }
        ObjectNode connection = store.object();
        connection.put("connectionId", store.id());
        connection.put("domainId", domainId);
        store.domain(region, domainId).ifPresent(domain -> connection.put("domainUnitId",
                domain.path("rootDomainUnitId").asText()));
        if (environmentId != null) {
            connection.put("environmentId", environmentId);
            connection.put("projectId", projectId);
        }
        connection.put("name", name);
        if (present(input, "description")) {
            connection.put("description", input.get("description").asText());
        }
        if (input.has("configurations")) {
            connection.set("configurations", input.get("configurations").deepCopy());
        }
        connection.put("type", type);
        connection.put("scope", scope);
        connection.set("props", outputProps(member, props.get(member), environmentId));
        ObjectNode location = store.object();
        location.put("awsAccountId", input.path("awsLocation").path("awsAccountId").asText(store.account()));
        location.put("awsRegion", input.path("awsLocation").path("awsRegion").asText(region));
        if (input.path("awsLocation").hasNonNull("accessRole")) {
            location.put("accessRole", input.get("awsLocation").get("accessRole").asText());
        }
        ObjectNode endpoint = connection.putArray("physicalEndpoints").addObject();
        endpoint.set("awsLocation", location);
        if (input.has("enableTrustedIdentityPropagation")) {
            endpoint.put("enableTrustedIdentityPropagation", input.get("enableTrustedIdentityPropagation")
                    .asBoolean());
        }
        connection.put("createdAt", now());
        store.put(region, domainId, CONNECTIONS, connection.path("connectionId").asText(), connection);
        return connectionView(connection);
    }

    private ObjectNode outputProps(String member, JsonNode input, String environmentId) {
        ObjectNode output = store.object();
        ObjectNode properties = input.isObject() ? ((ObjectNode) input).deepCopy() : store.object();
        properties.remove("roleArn");
        if ("iamProperties".equals(member) && environmentId != null) {
            properties.put("environmentId", environmentId);
        }
        if (!"iamProperties".equals(member) && !"athenaProperties".equals(member)) {
            properties.put("status", "READY");
        }
        properties.remove("glueConnectionInput");
        output.set(member, properties);
        return output;
    }

    private ObjectNode connectionView(ObjectNode connection) {
        ObjectNode view = store.view(connection);
        view.remove("createdAt");
        return view;
    }

    private ObjectNode connection(String region, String domainId, String id) {
        return store.require(region, domainId, CONNECTIONS, id, "Connection");
    }

    public synchronized ObjectNode getConnection(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetConnection", false);
        ObjectNode view = connectionView(connection(region, domainId, text(input, "identifier", 128)));
        String environmentId = view.path("environmentId").asText(null);
        if (environmentId != null) {
            store.get(region, domainId, DataZoneEnvironments.ENVIRONMENTS, environmentId).ifPresent(environment -> {
                for (JsonNode resource : environment.path("provisionedResources")) {
                    if ("userRoleArn".equals(resource.path("name").asText())) {
                        view.put("environmentUserRole", resource.path("value").asText());
                    }
                }
            });
        }
        return view;
    }

    public synchronized ObjectNode updateConnection(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateConnection", true);
        ObjectNode connection = connection(region, domainId, text(input, "identifier", 128));
        if (present(input, "description")) {
            connection.put("description", input.get("description").asText());
        }
        if (input.has("configurations")) {
            connection.set("configurations", input.get("configurations").deepCopy());
        }
        if (input.path("awsLocation").isObject()) {
            ObjectNode location = (ObjectNode) connection.path("physicalEndpoints").get(0).path("awsLocation");
            input.get("awsLocation").properties().forEach(entry -> location.set(entry.getKey(), entry.getValue()));
        }
        if (input.path("props").isObject() && input.get("props").size() == 1) {
            String member = input.get("props").fieldNames().next();
            JsonNode current = connection.path("props").path(member);
            if (current.isMissingNode()) {
                throw invalid("A connection's type cannot change.");
            }
            ObjectNode merged = ((ObjectNode) current).deepCopy();
            input.get("props").get(member).properties().forEach(entry -> merged.set(entry.getKey(),
                    entry.getValue()));
            connection.set("props", outputProps(member, merged, connection.path("environmentId").asText(null)));
        }
        store.put(region, domainId, CONNECTIONS, connection.path("connectionId").asText(), connection);
        return connectionView(connection);
    }

    public synchronized ObjectNode deleteConnection(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteConnection", true);
        ObjectNode connection = connection(region, domainId, text(input, "identifier", 128));
        for (ObjectNode source : store.list(region, domainId, DataZoneIngestion.DATA_SOURCES)) {
            if (connection.path("connectionId").asText().equals(source.path("connectionId").asText())) {
                throw conflict("Connection " + connection.path("connectionId").asText() + " is used by data source "
                        + source.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, CONNECTIONS, connection.path("connectionId").asText());
        return store.object().put("status", "Connection " + connection.path("connectionId").asText()
                + " is deleted.");
    }

    public synchronized ObjectNode listConnections(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListConnections", false);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode connection : store.list(region, domainId, CONNECTIONS)) {
            if (matches(input, "name", connection, "name")
                    && matches(input, "environmentIdentifier", connection, "environmentId")
                    && matches(input, "projectIdentifier", connection, "projectId")
                    && matches(input, "type", connection, "type") && matches(input, "scope", connection, "scope")) {
                ObjectNode summary = connectionView(connection);
                summary.remove("description");
                summary.put("createdAt", connection.path("createdAt").asDouble());
                items.add(summary);
            }
        }
        sort(items, input, "name");
        items.forEach(item -> item.remove("createdAt"));
        return store.page(items, input, "items", item -> item.path("connectionId").asText());
    }

    private static boolean matches(JsonNode input, String field, JsonNode value, String valueField) {
        return !present(input, field) || input.get(field).asText().equals(value.path(valueField).asText());
    }

    // ---------------------------------------------------------------- data products

    private ArrayNode dataProductItems(String region, String domainId, String projectId, JsonNode items) {
        ArrayNode result = store.array();
        if (items.isArray()) {
            for (JsonNode item : items) {
                oneOf(item, "itemType", Set.of("ASSET"), null);
                ObjectNode asset = catalog.asset(region, domainId, text(item, "identifier", 64));
                if (!projectId.equals(asset.path("owningProjectId").asText())) {
                    throw invalid("Asset " + asset.path("id").asText() + " is not owned by project " + projectId
                            + ".");
                }
                ObjectNode copy = ((ObjectNode) item).deepCopy();
                if (!copy.hasNonNull("revision")) {
                    copy.put("revision", asset.path("revision").asText());
                }
                result.add(copy);
            }
        }
        return result;
    }

    public synchronized ObjectNode createDataProduct(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateDataProduct", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateDataProduct");
        String name = text(input, "name", 64);
        for (ObjectNode existing : store.list(region, domainId, DATA_PRODUCTS)) {
            if (name.equals(existing.path("name").asText()) && projectId.equals(existing.path("owningProjectId")
                    .asText())) {
                throw conflict("A data product named " + name + " already exists in project " + projectId + ".");
            }
        }
        double now = now();
        ObjectNode product = store.object();
        product.put("domainId", domainId);
        product.put("id", store.id());
        product.put("revision", "1");
        product.put("owningProjectId", projectId);
        product.put("name", name);
        product.put("status", "CREATED");
        applyDataProduct(region, domainId, product, input);
        product.put("createdAt", now);
        product.put("createdBy", creator);
        product.put("firstRevisionCreatedAt", now);
        product.put("firstRevisionCreatedBy", creator);
        product.putObject("_").putArray("revisions").add(store.view(product));
        store.put(region, domainId, DATA_PRODUCTS, product.path("id").asText(), product);
        return store.view(product);
    }

    private void applyDataProduct(String region, String domainId, ObjectNode product, ObjectNode input) {
        if (input.has("description")) {
            product.put("description", input.path("description").asText());
        }
        if (input.has("glossaryTerms")) {
            for (JsonNode term : input.get("glossaryTerms")) {
                store.require(region, domainId, DataZoneCatalog.TERMS, term.asText(), "Glossary term");
            }
            product.set("glossaryTerms", input.get("glossaryTerms").deepCopy());
        }
        if (input.has("items")) {
            product.set("items", dataProductItems(region, domainId, product.path("owningProjectId").asText(),
                    input.get("items")));
        }
        if (input.has("formsInput")) {
            ArrayNode forms = store.array();
            for (JsonNode form : input.get("formsInput")) {
                String typeName = text(form, "typeIdentifier", 385);
                ObjectNode type = catalog.formType(region, domainId, typeName, optional(form, "typeRevision"));
                forms.addObject().put("formName", text(form, "formName", 128)).put("typeName", typeName)
                        .put("typeRevision", type.path("revision").asText())
                        .put("content", form.path("content").asText("{}"));
            }
            product.set("formsOutput", forms);
        }
    }

    ObjectNode dataProduct(String region, String domainId, String id) {
        return store.require(region, domainId, DATA_PRODUCTS, id, "Data product");
    }

    public synchronized ObjectNode createDataProductRevision(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateDataProductRevision", true);
        ObjectNode product = dataProduct(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, product.path("owningProjectId").asText(),
                "CreateDataProductRevision");
        product.put("name", text(input, "name", 64));
        applyDataProduct(region, domainId, product, input);
        product.put("revision", String.valueOf(product.path("revision").asInt() + 1));
        product.put("createdAt", now());
        product.put("createdBy", editor);
        ((ArrayNode) product.path("_").path("revisions")).add(store.view(product));
        store.put(region, domainId, DATA_PRODUCTS, product.path("id").asText(), product);
        return store.view(product);
    }

    public synchronized ObjectNode getDataProduct(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetDataProduct", false);
        ObjectNode product = dataProduct(region, domainId, text(input, "identifier", 64));
        String revision = optional(input, "revision");
        if (revision != null && !revision.equals(product.path("revision").asText())) {
            for (JsonNode snapshot : product.path("_").path("revisions")) {
                if (revision.equals(snapshot.path("revision").asText())) {
                    return ((ObjectNode) snapshot).deepCopy();
                }
            }
            throw notFound("Revision " + revision + " of data product " + product.path("id").asText()
                    + " was not found.");
        }
        return store.view(product);
    }

    public synchronized ObjectNode deleteDataProduct(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteDataProduct", true);
        ObjectNode product = dataProduct(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, product.path("owningProjectId").asText(), "DeleteDataProduct");
        ObjectNode listing = catalog.listingOf(region, domainId, product.path("id").asText());
        if (listing != null && "ACTIVE".equals(listing.path("status").asText())) {
            throw conflict("Data product " + product.path("id").asText() + " is published; unpublish it first.");
        }
        if (listing != null) {
            store.delete(region, domainId, DataZoneCatalog.LISTINGS, listing.path("id").asText());
        }
        store.delete(region, domainId, DATA_PRODUCTS, product.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listDataProductRevisions(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListDataProductRevisions", false);
        ObjectNode product = dataProduct(region, domainId, text(input, "identifier", 64));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode snapshot : product.path("_").path("revisions")) {
            items.add(store.object().put("domainId", domainId).put("id", product.path("id").asText())
                    .put("revision", snapshot.path("revision").asText())
                    .put("createdAt", snapshot.path("createdAt").asDouble())
                    .put("createdBy", snapshot.path("createdBy").asText()));
        }
        items.sort(Comparator.comparing((ObjectNode item) -> item.path("revision").asInt()).reversed());
        return store.page(items, input, "items", item -> item.path("revision").asText());
    }

    // ---------------------------------------------------------------- asset filters

    private List<String> columnsOf(ObjectNode asset) {
        List<String> columns = new ArrayList<>();
        for (JsonNode form : asset.path("formsOutput")) {
            try {
                for (JsonNode column : store.mapper().readTree(form.path("content").asText("{}")).path("columns")) {
                    columns.add(column.path("columnName").asText(column.path("name").asText()));
                }
            } catch (JsonProcessingException e) {
                continue;
            }
        }
        return columns;
    }

    private void configureFilter(ObjectNode filter, ObjectNode asset, JsonNode configuration) {
        List<String> columns = columnsOf(asset);
        filter.set("configuration", configuration.deepCopy());
        filter.remove("effectiveColumnNames");
        filter.remove("effectiveRowFilter");
        filter.remove("errorMessage");
        filter.put("status", "VALID");
        if (configuration.has("columnConfiguration")) {
            ArrayNode effective = filter.putArray("effectiveColumnNames");
            for (JsonNode column : configuration.get("columnConfiguration").path("includedColumnNames")) {
                if (!columns.isEmpty() && !columns.contains(column.asText())) {
                    filter.put("status", "INVALID");
                    filter.put("errorMessage", "Column " + column.asText() + " is not a column of asset "
                            + asset.path("id").asText() + ".");
                }
                effective.add(column.asText());
            }
        } else if (configuration.has("rowConfiguration")) {
            String expression = rowFilter(configuration.get("rowConfiguration").path("rowFilter"), columns, filter);
            filter.put("effectiveRowFilter", expression);
            if (!columns.isEmpty()) {
                ArrayNode effective = filter.putArray("effectiveColumnNames");
                columns.forEach(effective::add);
            }
        } else {
            throw invalid("configuration must be a columnConfiguration or a rowConfiguration.");
        }
    }

    /** Renders a row filter as the SQL predicate DataZone grants it as. */
    private String rowFilter(JsonNode filter, List<String> columns, ObjectNode result) {
        if (filter.has("and") || filter.has("or")) {
            String operator = filter.has("and") ? " AND " : " OR ";
            List<String> parts = new ArrayList<>();
            for (JsonNode child : filter.get(filter.has("and") ? "and" : "or")) {
                parts.add(rowFilter(child, columns, result));
            }
            return "(" + String.join(operator, parts) + ")";
        }
        JsonNode expression = filter.path("expression");
        if (!expression.isObject() || expression.size() != 1) {
            throw invalid("A row filter expression must hold exactly one comparison.");
        }
        String kind = expression.fieldNames().next();
        JsonNode body = expression.get(kind);
        String column = text(body, "columnName", 1024);
        if (!columns.isEmpty() && !columns.contains(column)) {
            result.put("status", "INVALID");
            result.put("errorMessage", "Column " + column + " is not a column of the asset.");
        }
        String value = "'" + body.path("value").asText("").replace("'", "''") + "'";
        return switch (kind) {
            case "equalTo" -> column + " = " + value;
            case "notEqualTo" -> column + " != " + value;
            case "greaterThan" -> column + " > " + value;
            case "lessThan" -> column + " < " + value;
            case "greaterThanOrEqualTo" -> column + " >= " + value;
            case "lessThanOrEqualTo" -> column + " <= " + value;
            case "isNull" -> column + " IS NULL";
            case "isNotNull" -> column + " IS NOT NULL";
            case "like" -> column + " LIKE " + value;
            case "notLike" -> column + " NOT LIKE " + value;
            case "in", "notIn" -> {
                List<String> values = new ArrayList<>();
                body.path("values").forEach(item -> values.add("'" + item.asText().replace("'", "''") + "'"));
                yield column + ("in".equals(kind) ? " IN (" : " NOT IN (") + String.join(", ", values) + ")";
            }
            default -> throw invalid("Unsupported row filter expression " + kind + ".");
        };
    }

    public synchronized ObjectNode createAssetFilter(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateAssetFilter", true);
        ObjectNode asset = catalog.asset(region, domainId, text(input, "assetIdentifier", 64));
        core.requireMember(region, domainId, asset.path("owningProjectId").asText(), "CreateAssetFilter");
        String name = text(input, "name", 64);
        for (ObjectNode existing : store.list(region, domainId, FILTERS)) {
            if (asset.path("id").asText().equals(existing.path("assetId").asText())
                    && name.equals(existing.path("name").asText())) {
                throw conflict("Asset " + asset.path("id").asText() + " already has a filter named " + name + ".");
            }
        }
        ObjectNode filter = store.object();
        filter.put("id", store.id());
        filter.put("domainId", domainId);
        filter.put("assetId", asset.path("id").asText());
        filter.put("name", name);
        if (present(input, "description")) {
            filter.put("description", input.get("description").asText());
        }
        configureFilter(filter, asset, input.path("configuration"));
        filter.put("createdAt", now());
        store.put(region, domainId, FILTERS, filter.path("id").asText(), filter);
        return store.view(filter);
    }

    private ObjectNode assetFilter(String region, String domainId, ObjectNode input) {
        ObjectNode filter = store.require(region, domainId, FILTERS, text(input, "identifier", 64), "Asset filter");
        if (!text(input, "assetIdentifier", 64).equals(filter.path("assetId").asText())) {
            throw notFound("Asset filter " + filter.path("id").asText() + " was not found.");
        }
        return filter;
    }

    public synchronized ObjectNode getAssetFilter(ObjectNode input, String region) {
        return store.view(assetFilter(region, domainId(input, region, "GetAssetFilter", false), input));
    }

    public synchronized ObjectNode updateAssetFilter(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateAssetFilter", true);
        ObjectNode filter = assetFilter(region, domainId, input);
        ObjectNode asset = catalog.asset(region, domainId, filter.path("assetId").asText());
        core.requireMember(region, domainId, asset.path("owningProjectId").asText(), "UpdateAssetFilter");
        for (String field : List.of("name", "description")) {
            if (present(input, field)) {
                filter.put(field, input.get(field).asText());
            }
        }
        if (input.has("configuration")) {
            configureFilter(filter, asset, input.get("configuration"));
        }
        store.put(region, domainId, FILTERS, filter.path("id").asText(), filter);
        return store.view(filter);
    }

    public synchronized ObjectNode deleteAssetFilter(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteAssetFilter", true);
        ObjectNode filter = assetFilter(region, domainId, input);
        for (ObjectNode subscription : store.list(region, domainId, DataZoneSubscriptions.SUBSCRIPTIONS)) {
            JsonNode scope = subscription.path("subscribedListing").path("item").path("assetListing")
                    .path("assetScope");
            if ("APPROVED".equals(subscription.path("status").asText())
                    && DataZoneRules.values(scope.path("filterIds")).contains(filter.path("id").asText())) {
                throw conflict("Asset filter " + filter.path("id").asText() + " scopes approved subscription "
                        + subscription.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, FILTERS, filter.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listAssetFilters(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListAssetFilters", false);
        String assetId = catalog.asset(region, domainId, text(input, "assetIdentifier", 64)).path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode filter : store.list(region, domainId, FILTERS)) {
            if (assetId.equals(filter.path("assetId").asText()) && matches(input, "status", filter, "status")) {
                ObjectNode summary = store.view(filter);
                summary.remove("configuration");
                items.add(summary);
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- governed terms and attributes

    private ObjectNode governedAsset(String region, String domainId, ObjectNode input, String operation) {
        oneOf(input, "entityType", Set.of("ASSET"), null);
        ObjectNode asset = catalog.asset(region, domainId, text(input, "entityIdentifier", 64));
        core.requireMember(region, domainId, asset.path("owningProjectId").asText(), operation);
        return asset;
    }

    public synchronized ObjectNode associateGovernedTerms(ObjectNode input, String region) {
        String domainId = domainId(input, region, "AssociateGovernedTerms", true);
        ObjectNode asset = governedAsset(region, domainId, input, "AssociateGovernedTerms");
        ArrayNode governed = asset.withArray("governedGlossaryTerms");
        Set<String> existing = DataZoneRules.values(governed);
        for (JsonNode termId : input.path("governedGlossaryTerms")) {
            ObjectNode term = store.require(region, domainId, DataZoneCatalog.TERMS, termId.asText(), "Glossary term");
            if (!DataZoneRules.values(term.path("usageRestrictions")).contains("ASSET_GOVERNED_TERMS")) {
                throw invalid("Glossary term " + termId.asText() + " is not a governed term: its glossary has no "
                        + "ASSET_GOVERNED_TERMS usage restriction.");
            }
            if (existing.add(termId.asText())) {
                governed.add(termId.asText());
            }
        }
        store.put(region, domainId, DataZoneCatalog.ASSETS, asset.path("id").asText(), asset);
        return store.object();
    }

    public synchronized ObjectNode disassociateGovernedTerms(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DisassociateGovernedTerms", true);
        ObjectNode asset = governedAsset(region, domainId, input, "DisassociateGovernedTerms");
        Set<String> removed = DataZoneRules.values(input.path("governedGlossaryTerms"));
        ArrayNode kept = store.array();
        asset.path("governedGlossaryTerms").forEach(term -> {
            if (!removed.contains(term.asText())) {
                kept.add(term.asText());
            }
        });
        asset.set("governedGlossaryTerms", kept);
        store.put(region, domainId, DataZoneCatalog.ASSETS, asset.path("id").asText(), asset);
        return store.object();
    }

    public synchronized ObjectNode batchPutAttributesMetadata(ObjectNode input, String region) {
        String domainId = domainId(input, region, "BatchPutAttributesMetadata", true);
        ObjectNode asset = governedAsset(region, domainId, input, "BatchPutAttributesMetadata");
        List<String> columns = columnsOf(asset);
        ObjectNode attributes = ((ObjectNode) asset.get("_")).withObject("attributes");
        ArrayNode errors = store.array();
        ArrayNode written = store.array();
        for (JsonNode attribute : input.path("attributes")) {
            String identifier = text(attribute, "attributeIdentifier", 1024);
            if (!columns.isEmpty() && !columns.contains(identifier)) {
                errors.addObject().put("attributeIdentifier", identifier).put("code", "ResourceNotFoundException")
                        .put("message", "Asset " + asset.path("id").asText() + " has no column " + identifier + ".");
                continue;
            }
            ArrayNode forms = store.array();
            try {
                for (JsonNode form : attribute.path("forms")) {
                    String typeName = text(form, "typeIdentifier", 385);
                    ObjectNode type = catalog.formType(region, domainId, typeName, optional(form, "typeRevision"));
                    forms.addObject().put("formName", text(form, "formName", 128)).put("typeName", typeName)
                            .put("typeRevision", type.path("revision").asText())
                            .put("content", form.path("content").asText("{}"));
                }
            } catch (AwsException e) {
                errors.addObject().put("attributeIdentifier", identifier).put("code", e.getErrorCode())
                        .put("message", e.getMessage());
                continue;
            }
            attributes.set(identifier, forms);
            written.addObject().put("attributeIdentifier", identifier);
        }
        store.put(region, domainId, DataZoneCatalog.ASSETS, asset.path("id").asText(), asset);
        ObjectNode response = store.object();
        response.set("errors", errors);
        response.set("attributes", written);
        return response;
    }

    public synchronized ObjectNode batchGetAttributesMetadata(ObjectNode input, String region) {
        String domainId = domainId(input, region, "BatchGetAttributesMetadata", false);
        oneOf(input, "entityType", Set.of("ASSET"), null);
        ObjectNode asset = catalog.asset(region, domainId, text(input, "entityIdentifier", 64));
        JsonNode attributes = asset.path("_").path("attributes");
        ArrayNode found = store.array();
        ArrayNode errors = store.array();
        for (JsonNode identifier : input.path("attributeIdentifiers")) {
            JsonNode forms = attributes.get(identifier.asText());
            if (forms == null) {
                errors.addObject().put("attributeIdentifier", identifier.asText())
                        .put("code", "ResourceNotFoundException")
                        .put("message", "Attribute " + identifier.asText() + " has no metadata.");
            } else {
                ObjectNode entry = found.addObject().put("attributeIdentifier", identifier.asText());
                entry.set("forms", forms.deepCopy());
            }
        }
        ObjectNode response = store.object();
        response.set("attributes", found);
        response.set("errors", errors);
        return response;
    }

    // ---------------------------------------------------------------- environment roles

    public synchronized ObjectNode associateEnvironmentRole(ObjectNode input, String region) {
        String domainId = domainId(input, region, "AssociateEnvironmentRole", true);
        ObjectNode environment = environments.environment(region, domainId, text(input, "environmentIdentifier",
                64));
        core.requireMember(region, domainId, environment.path("projectId").asText(), "AssociateEnvironmentRole");
        String role = core.role(text(input, "environmentRoleArn", 2048), "environmentRoleArn");
        ArrayNode roles = ((ObjectNode) environment.get("_")).withArray("roles");
        if (!DataZoneRules.values(roles).contains(role)) {
            roles.add(role);
        }
        store.put(region, domainId, DataZoneEnvironments.ENVIRONMENTS, environment.path("id").asText(), environment);
        return store.object();
    }

    public synchronized ObjectNode disassociateEnvironmentRole(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DisassociateEnvironmentRole", true);
        ObjectNode environment = environments.environment(region, domainId, text(input, "environmentIdentifier",
                64));
        core.requireMember(region, domainId, environment.path("projectId").asText(), "DisassociateEnvironmentRole");
        String role = text(input, "environmentRoleArn", 2048);
        ArrayNode roles = ((ObjectNode) environment.get("_")).withArray("roles");
        ArrayNode kept = store.array();
        roles.forEach(value -> {
            if (!role.equals(value.asText())) {
                kept.add(value.asText());
            }
        });
        if (kept.size() == roles.size()) {
            throw notFound("Role " + role + " is not associated with environment " + environment.path("id").asText()
                    + ".");
        }
        ((ObjectNode) environment.get("_")).set("roles", kept);
        store.put(region, domainId, DataZoneEnvironments.ENVIRONMENTS, environment.path("id").asText(), environment);
        return store.object();
    }

    // ---------------------------------------------------------------- data export configuration

    public synchronized ObjectNode putDataExportConfiguration(ObjectNode input, String region) {
        String domainId = domainId(input, region, "PutDataExportConfiguration", true);
        if (!input.has("enableExport")) {
            throw invalid("enableExport is required.");
        }
        ObjectNode existing = store.get(region, domainId, EXPORT, "configuration").orElse(null);
        double now = now();
        ObjectNode configuration = store.object();
        configuration.put("isExportEnabled", input.get("enableExport").asBoolean());
        configuration.put("status", "COMPLETED");
        if (input.has("encryptionConfiguration")) {
            configuration.set("encryptionConfiguration", input.get("encryptionConfiguration").deepCopy());
        }
        configuration.put("s3TableBucketArn", store.regions().buildArn("s3tables", region, store.account(),
                "bucket/aws-sagemaker-catalog"));
        configuration.put("createdAt", existing == null ? now : existing.path("createdAt").asDouble());
        configuration.put("updatedAt", now);
        store.put(region, domainId, EXPORT, "configuration", configuration);
        return store.object();
    }

    public synchronized ObjectNode getDataExportConfiguration(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetDataExportConfiguration", false);
        return store.view(store.require(region, domainId, EXPORT, "configuration", "Data export configuration"));
    }

    public synchronized ObjectNode deleteDataExportConfiguration(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteDataExportConfiguration", true);
        store.require(region, domainId, EXPORT, "configuration", "Data export configuration");
        store.delete(region, domainId, EXPORT, "configuration");
        return store.object();
    }

    // ---------------------------------------------------------------- lineage graph and job runs

    public synchronized ObjectNode queryGraph(ObjectNode input, String region) {
        String domainId = domainId(input, region, "QueryGraph", false);
        JsonNode match = input.path("match");
        if (!match.isArray() || match.isEmpty()) {
            throw invalid("match is required.");
        }
        JsonNode start = match.get(0).path("entityPattern");
        oneOf(start, "entityType", Set.of("LINEAGE_NODE"), null);
        String identifier = text(start, "identifier", 2048);
        ObjectNode node = ingestion.lineageNode(region, domainId, identifier);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(node.path("id").asText());
        List<String> frontier = List.of(node.path("id").asText());
        for (int index = 1; index < match.size(); index++) {
            JsonNode relation = match.get(index).path("relationPattern");
            if (relation.isMissingNode()) {
                continue;
            }
            oneOf(relation, "relationType", Set.of("LINEAGE"), "LINEAGE");
            String edge = "IN".equals(relation.path("relationDirection").asText("OUT")) ? "upstreamNodes"
                    : "downstreamNodes";
            int depth = relation.path("maxPathLength").asInt(1);
            for (int step = 0; step < depth && !frontier.isEmpty(); step++) {
                List<String> next = new ArrayList<>();
                for (String id : frontier) {
                    store.get(region, domainId, DataZoneIngestion.LINEAGE_NODES, id).ifPresent(current ->
                            current.path(edge).forEach(reference -> {
                                if (seen.add(reference.path("id").asText())) {
                                    next.add(reference.path("id").asText());
                                }
                            }));
                }
                frontier = next;
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (String id : seen) {
            store.get(region, domainId, DataZoneIngestion.LINEAGE_NODES, id).ifPresent(found -> {
                ObjectNode item = store.view(found, "domainId", "name", "description", "createdAt", "createdBy",
                        "updatedAt", "updatedBy", "id", "typeName", "typeRevision", "sourceIdentifier",
                        "eventTimestamp", "formsOutput");
                ArrayNode upstream = item.putArray("upstreamLineageNodeIds");
                found.path("upstreamNodes").forEach(reference -> upstream.add(reference.path("id").asText()));
                ArrayNode downstream = item.putArray("downstreamLineageNodeIds");
                found.path("downstreamNodes").forEach(reference -> downstream.add(reference.path("id").asText()));
                items.add(store.object().set("lineageNode", item));
            });
        }
        return store.page(items, input, "items", item -> item.path("lineageNode").path("id").asText());
    }

    public synchronized ObjectNode deleteLineageEvent(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteLineageEvent", true);
        ObjectNode event = store.require(region, domainId, DataZoneIngestion.LINEAGE_EVENTS,
                text(input, "identifier", 64), "Lineage event");
        store.delete(region, domainId, DataZoneIngestion.LINEAGE_EVENTS, event.path("id").asText());
        return store.object().put("id", event.path("id").asText()).put("domainId", domainId)
                .put("processingStatus", event.path("processingStatus").asText());
    }

    /** A data source's runs are its lineage job's runs: each imports the lineage of the tables it reads. */
    private ObjectNode jobRun(ObjectNode run) {
        ObjectNode item = store.object();
        item.put("domainId", run.path("domainId").asText());
        item.put("jobId", run.path("dataSourceId").asText());
        item.put("jobType", "LINEAGE");
        item.put("runMode", "SCHEDULED".equals(run.path("type").asText()) ? "SCHEDULED" : "ON_DEMAND");
        String status = switch (run.path("status").asText()) {
            case "REQUESTED" -> "SCHEDULED";
            case "RUNNING" -> "IN_PROGRESS";
            default -> run.path("status").asText();
        };
        item.put("status", status);
        if (run.has("errorMessage")) {
            item.putObject("error").put("message", run.path("errorMessage").path("errorDetail").asText());
        }
        item.put("createdAt", run.path("createdAt").asDouble());
        if (run.has("startedAt")) {
            item.put("startTime", run.path("startedAt").asDouble());
        }
        if (run.has("stoppedAt")) {
            item.put("endTime", run.path("stoppedAt").asDouble());
        }
        item.put("createdBy", run.path("_").path("createdBy").asText("SYSTEM"));
        return item;
    }

    public synchronized ObjectNode getJobRun(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetJobRun", false);
        ObjectNode run = store.require(region, domainId, DataZoneIngestion.RUNS, text(input, "identifier", 64),
                "Job run");
        ObjectNode item = jobRun(run);
        item.put("id", run.path("id").asText());
        ObjectNode details = item.putObject("details").putObject("lineageRunDetails").putObject("sqlQueryRunDetails");
        details.put("queryStartTime", run.path("startedAt").asDouble());
        details.put("queryEndTime", run.path("stoppedAt").asDouble(run.path("startedAt").asDouble()));
        JsonNode stats = run.path("runStatisticsForAssets");
        details.put("totalQueriesProcessed", stats.path("added").asInt() + stats.path("updated").asInt()
                + stats.path("unchanged").asInt());
        details.put("numQueriesFailed", stats.path("failed").asInt());
        details.putArray("errorMessages");
        return item;
    }

    public synchronized ObjectNode listJobRuns(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListJobRuns", false);
        String jobId = store.require(region, domainId, DataZoneIngestion.DATA_SOURCES, text(input, "jobIdentifier",
                64), "Job").path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode run : store.list(region, domainId, DataZoneIngestion.RUNS)) {
            if (jobId.equals(run.path("dataSourceId").asText())) {
                ObjectNode item = jobRun(run);
                item.put("runId", run.path("id").asText());
                if (!present(input, "status") || input.get("status").asText().equals(item.path("status").asText())) {
                    items.add(item);
                }
            }
        }
        Comparator<ObjectNode> order = Comparator.comparing(item -> item.path("createdAt").asDouble());
        items.sort("ASCENDING".equals(input.path("sortOrder").asText()) ? order : order.reversed());
        return store.page(items, input, "items", item -> item.path("runId").asText());
    }

    // ---------------------------------------------------------------- subscription grant status

    public synchronized ObjectNode updateSubscriptionGrantStatus(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateSubscriptionGrantStatus", true);
        ObjectNode grant = store.require(region, domainId, DataZoneSubscriptions.GRANTS, text(input, "identifier", 64),
                "Subscription grant");
        String assetId = text(input, "assetIdentifier", 64);
        String status = oneOf(input, "status", Set.of("GRANT_PENDING", "REVOKE_PENDING", "GRANT_IN_PROGRESS",
                "REVOKE_IN_PROGRESS", "GRANTED", "REVOKED", "GRANT_FAILED", "REVOKE_FAILED"), null);
        ObjectNode asset = null;
        for (JsonNode candidate : grant.path("assets")) {
            if (assetId.equals(candidate.path("assetId").asText())) {
                asset = (ObjectNode) candidate;
            }
        }
        if (asset == null) {
            throw notFound("Subscription grant " + grant.path("id").asText() + " has no asset " + assetId + ".");
        }
        double now = now();
        asset.put("status", status);
        if (present(input, "targetName")) {
            asset.put("targetName", input.get("targetName").asText());
        }
        if (input.has("failureCause")) {
            asset.set("failureCause", input.get("failureCause").deepCopy());
            asset.put("failureTimestamp", now);
        }
        if ("GRANTED".equals(status)) {
            asset.put("grantedTimestamp", now);
        }
        boolean grantFailed = false;
        boolean revokeFailed = false;
        boolean pending = false;
        boolean progressing = false;
        for (JsonNode candidate : grant.path("assets")) {
            String value = candidate.path("status").asText();
            grantFailed |= "GRANT_FAILED".equals(value);
            revokeFailed |= "REVOKE_FAILED".equals(value);
            pending |= value.endsWith("_PENDING");
            progressing |= value.endsWith("_IN_PROGRESS");
        }
        grant.put("status", grantFailed && revokeFailed ? "GRANT_AND_REVOKE_FAILED" : grantFailed ? "GRANT_FAILED"
                : revokeFailed ? "REVOKE_FAILED" : progressing ? "IN_PROGRESS" : pending ? "PENDING" : "COMPLETED");
        grant.put("updatedAt", now);
        grant.put("updatedBy", core.callerProfileId(region, domainId));
        store.put(region, domainId, DataZoneSubscriptions.GRANTS, grant.path("id").asText(), grant);
        return store.view(grant);
    }
}
