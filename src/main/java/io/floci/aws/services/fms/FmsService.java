package io.floci.aws.services.fms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Firewall Manager over an AWS Organizations organization: the default administrator the management account
 * designates, further administrators with their scopes, and what an administrator manages, its security policies,
 * applications and protocols lists, resource sets, notification channel and third-party firewall onboarding.
 *
 * <p>Administrators are kept with the organization's management account; an administrator's resources with the
 * administrator. Only an administrator may manage policies and lists; any other account is refused with
 * AccessDeniedException. Policies report every account they cover as compliant: floci evaluates no resources
 * against them and creates none.
 */
@ApplicationScoped
public class FmsService {

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Set<String> POLICY_TYPES = Set.of("WAF", "WAFV2", "SHIELD_ADVANCED",
            "SECURITY_GROUPS_COMMON", "SECURITY_GROUPS_CONTENT_AUDIT", "SECURITY_GROUPS_USAGE_AUDIT",
            "NETWORK_FIREWALL", "DNS_FIREWALL", "THIRD_PARTY_FIREWALL", "IMPORT_NETWORK_FIREWALL",
            "NETWORK_ACL_COMMON");
    private static final Set<String> FIREWALLS = Set.of("PALO_ALTO_NETWORKS_CLOUD_NGFW",
            "FORTIGATE_CLOUD_NATIVE_FIREWALL");

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Instance<OrganizationsService> organizations;
    private final Instance<SnsService> sns;
    private final IamService iam;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public FmsService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                      Instance<OrganizationsService> organizations, Instance<SnsService> sns, IamService iam) {
        this.storage = storageFactory.create("fms", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.organizations = organizations;
        this.sns = sns;
        this.iam = iam;
    }

    // ---------------------------------------------------------------- organization

    private String caller() {
        return storage.accountId();
    }

    private Optional<Organization> organization() {
        if (!organizations.isResolvable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(organizations.get().describeOrganization(caller()));
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    private Organization managementOrganization(String operation) {
        Organization organization = organization().orElseThrow(() -> invalidOperation("Your AWS account is not a "
                + "member of an organization in AWS Organizations, which " + operation + " requires."));
        if (!caller().equals(organization.getMasterAccountId())) {
            throw invalidOperation(operation + " must be called by the management account of the organization.");
        }
        return organization;
    }

    private List<String> accounts(Organization organization) {
        List<String> ids = new ArrayList<>();
        for (OrganizationAccount account : organizations.get().listAccounts(organization.getMasterAccountId())) {
            if (!"SUSPENDED".equals(account.getStatus())) {
                ids.add(account.getId());
            }
        }
        return ids;
    }

    /** The administrators of the caller's organization, the default one first. */
    private List<ObjectNode> admins(Organization organization) {
        List<ObjectNode> admins = new ArrayList<>(storage.scanForAccount(organization.getMasterAccountId(),
                key -> key.startsWith("admin/")));
        admins.sort(Comparator.comparing((ObjectNode admin) -> !admin.path("_").path("default").asBoolean(false))
                .thenComparingLong(admin -> admin.path("_").path("seq").asLong()));
        List<ObjectNode> settled = new ArrayList<>();
        for (ObjectNode admin : admins) {
            settled.add(settle(organization, admin));
        }
        return settled;
    }

    private ObjectNode settle(Organization organization, ObjectNode admin) {
        ObjectNode meta = (ObjectNode) admin.get("_");
        if (meta.has("readyAt") && meta.path("readyAt").asLong() <= System.currentTimeMillis()) {
            meta.remove("readyAt");
            admin.put("Status", "ONBOARDING_COMPLETE");
            storage.putForAccount(organization.getMasterAccountId(), "admin/" + admin.path("AdminAccount").asText(),
                    admin);
        }
        return admin;
    }

    private Optional<ObjectNode> defaultAdmin(Organization organization) {
        return admins(organization).stream().filter(admin -> admin.path("_").path("default").asBoolean(false))
                .findFirst();
    }

    /** The caller's administrator record; AccessDeniedException when the caller administers nothing. */
    private ObjectNode requireAdmin() {
        Optional<Organization> organization = organization();
        Optional<ObjectNode> admin = organization.flatMap(org -> admins(org).stream()
                .filter(record -> caller().equals(record.path("AdminAccount").asText())).findFirst());
        return admin.orElseThrow(() -> new AwsException("AccessDeniedException", "The account " + caller()
                + " is not an AWS Firewall Manager administrator account.", 400));
    }

    // ---------------------------------------------------------------- administrators

    /** Designates the organization's default administrator, onboarding the organization to Firewall Manager. */
    public synchronized ObjectNode associateAdminAccount(JsonNode input) {
        Organization organization = managementOrganization("AssociateAdminAccount");
        String account = text(input, "AdminAccount");
        if (!accounts(organization).contains(account)) {
            throw invalidInput("Account " + account + " is not a member of the organization.");
        }
        Optional<ObjectNode> existing = defaultAdmin(organization);
        if (existing.isPresent()) {
            if (existing.get().path("AdminAccount").asText().equals(account)) {
                return mapper.createObjectNode();
            }
            throw invalidOperation("Account " + existing.get().path("AdminAccount").asText()
                    + " is already the AWS Firewall Manager default administrator.");
        }
        ObjectNode admin = adminRecord(account, defaultScope(), true);
        storage.putForAccount(organization.getMasterAccountId(), "admin/" + account, admin);
        organizations.get().enableAWSServiceAccess(organization.getMasterAccountId(), ServicePrincipals.of("fms"));
        serviceLinkedRole(organization.getMasterAccountId());
        settle(organization, admin);
        return mapper.createObjectNode();
    }

    /** Offboards the organization: its administrators go once onboarding has finished. */
    public synchronized ObjectNode disassociateAdminAccount(JsonNode input) {
        Organization organization = organization().orElseThrow(() -> notFound("No AWS Firewall Manager "
                + "administrator account is associated with this organization."));
        ObjectNode admin = defaultAdmin(organization).orElseThrow(() -> notFound("No AWS Firewall Manager "
                + "administrator account is associated with this organization."));
        if (!caller().equals(organization.getMasterAccountId())
                && !caller().equals(admin.path("AdminAccount").asText())) {
            throw invalidOperation("DisassociateAdminAccount must be called by the management account or the "
                    + "default administrator account.");
        }
        if ("ONBOARDING".equals(admin.path("Status").asText())) {
            throw invalidOperation("Failed to offboard AWS Account " + admin.path("AdminAccount").asText()
                    + " as an AWS Firewall Manager administrator account. Your AWS Organization is currently "
                    + "onboarding with AWS Firewall Manager and cannot be offboarded.");
        }
        for (ObjectNode record : admins(organization)) {
            storage.deleteForAccount(organization.getMasterAccountId(), "admin/"
                    + record.path("AdminAccount").asText());
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode getAdminAccount(JsonNode input) {
        Organization organization = organization().orElseThrow(() -> notFound("No AWS Firewall Manager "
                + "administrator account is associated with this organization."));
        ObjectNode admin = defaultAdmin(organization).orElseThrow(() -> notFound("No AWS Firewall Manager "
                + "administrator account is associated with this organization."));
        ObjectNode response = mapper.createObjectNode();
        response.put("AdminAccount", admin.path("AdminAccount").asText())
                .put("RoleStatus", "ONBOARDING".equals(admin.path("Status").asText()) ? "CREATING" : "READY");
        return response;
    }

    /** Creates or updates an administrator with its scope; the default administrator must exist first. */
    public synchronized ObjectNode putAdminAccount(JsonNode input) {
        Organization organization = organization().orElseThrow(() -> invalidOperation("Your AWS account is not a "
                + "member of an organization in AWS Organizations."));
        Optional<ObjectNode> fallback = defaultAdmin(organization);
        if (!caller().equals(organization.getMasterAccountId()) && (fallback.isEmpty()
                || !caller().equals(fallback.get().path("AdminAccount").asText()))) {
            throw invalidOperation("PutAdminAccount must be called by the management account or the default "
                    + "administrator account.");
        }
        if (fallback.isEmpty()) {
            throw invalidOperation("Designate the default administrator with AssociateAdminAccount first.");
        }
        String account = text(input, "AdminAccount");
        if (!accounts(organization).contains(account)) {
            throw invalidInput("Account " + account + " is not a member of the organization.");
        }
        JsonNode scope = input.hasNonNull("AdminScope") ? input.get("AdminScope") : defaultScope();
        Optional<ObjectNode> existing = storage.getForAccount(organization.getMasterAccountId(), "admin/" + account);
        ObjectNode admin = existing.orElseGet(() -> adminRecord(account, scope, false));
        admin.set("AdminScope", scope.deepCopy());
        storage.putForAccount(organization.getMasterAccountId(), "admin/" + account, admin);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode getAdminScope(JsonNode input) {
        Organization organization = organization().orElseThrow(() -> notFound("The account is not an AWS "
                + "Firewall Manager administrator."));
        String account = text(input, "AdminAccount");
        ObjectNode admin = admins(organization).stream()
                .filter(record -> account.equals(record.path("AdminAccount").asText())).findFirst()
                .orElseThrow(() -> notFound("Account " + account + " is not an AWS Firewall Manager administrator."));
        ObjectNode response = mapper.createObjectNode();
        response.set("AdminScope", admin.path("AdminScope").deepCopy());
        response.put("Status", admin.path("Status").asText());
        return response;
    }

    public synchronized ObjectNode listAdminAccountsForOrganization(JsonNode input) {
        Organization organization = managementOrganization("ListAdminAccountsForOrganization");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode admin : admins(organization)) {
            ObjectNode item = mapper.createObjectNode();
            item.put("AdminAccount", admin.path("AdminAccount").asText())
                    .put("DefaultAdmin", admin.path("_").path("default").asBoolean(false))
                    .put("Status", admin.path("Status").asText());
            items.add(item);
        }
        return page(items, input, "AdminAccounts", 100);
    }

    /** The administrators whose scope covers the calling account. */
    public synchronized ObjectNode listAdminsManagingAccount(JsonNode input) {
        Organization organization = organization().orElseThrow(() -> notFound("No AWS Firewall Manager "
                + "administrator manages this account."));
        List<ObjectNode> admins = admins(organization);
        if (admins.isEmpty()) {
            throw notFound("No AWS Firewall Manager administrator manages this account.");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode admin : admins) {
            if (inScope(admin.path("AdminScope"), caller())) {
                items.add(mapper.createObjectNode().put("id", admin.path("AdminAccount").asText()));
            }
        }
        ObjectNode response = page(items, input, "AdminAccounts", 100);
        ArrayNode ids = mapper.createArrayNode();
        response.path("AdminAccounts").forEach(item -> ids.add(item.path("id").asText()));
        response.set("AdminAccounts", ids);
        return response;
    }

    /** The organization's accounts an administrator's scope covers. */
    public synchronized ObjectNode listMemberAccounts(JsonNode input) {
        ObjectNode admin = requireAdmin();
        Organization organization = organization().orElseThrow();
        List<ObjectNode> items = new ArrayList<>();
        for (String account : accounts(organization)) {
            if (inScope(admin.path("AdminScope"), account)) {
                items.add(mapper.createObjectNode().put("id", account));
            }
        }
        ObjectNode response = page(items, input, "MemberAccounts", 100);
        ArrayNode ids = mapper.createArrayNode();
        response.path("MemberAccounts").forEach(item -> ids.add(item.path("id").asText()));
        response.set("MemberAccounts", ids);
        return response;
    }

    private ObjectNode adminRecord(String account, JsonNode scope, boolean isDefault) {
        ObjectNode admin = mapper.createObjectNode();
        admin.put("AdminAccount", account).put("Status", "ONBOARDING");
        admin.set("AdminScope", scope.deepCopy());
        admin.putObject("_").put("seq", System.nanoTime()).put("default", isDefault)
                .put("readyAt", System.currentTimeMillis() + config.services().fms().transitionMillis());
        return admin;
    }

    private ObjectNode defaultScope() {
        ObjectNode scope = mapper.createObjectNode();
        scope.putObject("AccountScope").put("AllAccountsEnabled", true).put("ExcludeSpecifiedAccounts", false);
        scope.putObject("OrganizationalUnitScope").put("AllOrganizationalUnitsEnabled", true)
                .put("ExcludeSpecifiedOrganizationalUnits", false);
        scope.putObject("RegionScope").put("AllRegionsEnabled", true);
        scope.putObject("PolicyTypeScope").put("AllPolicyTypesEnabled", true);
        return scope;
    }

    /** Whether an administrator's account and organizational unit scope covers an account. */
    private boolean inScope(JsonNode scope, String account) {
        JsonNode accounts = scope.path("AccountScope");
        if (!accounts.isMissingNode() && !accounts.path("AllAccountsEnabled").asBoolean(false)) {
            boolean listed = false;
            for (JsonNode id : accounts.path("Accounts")) {
                listed |= account.equals(id.asText());
            }
            if (listed == accounts.path("ExcludeSpecifiedAccounts").asBoolean(false)) {
                return false;
            }
        }
        JsonNode units = scope.path("OrganizationalUnitScope");
        if (!units.isMissingNode() && !units.path("AllOrganizationalUnitsEnabled").asBoolean(true)
                && units.path("OrganizationalUnits").size() > 0) {
            Set<String> parents = new LinkedHashSet<>();
            try {
                organizations.get().listParents(organization().orElseThrow().getMasterAccountId(), account)
                        .forEach(parent -> parents.add(parent.id()));
            } catch (AwsException e) {
                return false;
            }
            boolean listed = false;
            for (JsonNode unit : units.path("OrganizationalUnits")) {
                listed |= parents.contains(unit.asText());
            }
            return listed != units.path("ExcludeSpecifiedOrganizationalUnits").asBoolean(false);
        }
        return true;
    }

    private boolean regionInScope(JsonNode scope, String region) {
        JsonNode regions = scope.path("RegionScope");
        if (regions.isMissingNode() || regions.path("AllRegionsEnabled").asBoolean(true)) {
            return true;
        }
        for (JsonNode listed : regions.path("Regions")) {
            if (region.equals(listed.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean policyTypeInScope(JsonNode scope, String type) {
        JsonNode types = scope.path("PolicyTypeScope");
        if (types.isMissingNode() || types.path("AllPolicyTypesEnabled").asBoolean(true)) {
            return true;
        }
        for (JsonNode listed : types.path("PolicyTypes")) {
            if (type.equals(listed.asText())) {
                return true;
            }
        }
        return false;
    }

    private void serviceLinkedRole(String account) {
        if (iam.findRole(account, "AWSServiceRoleForFMS").isEmpty()) {
            try {
                iam.createServiceLinkedRole(ServicePrincipals.of("fms"), null, null);
            } catch (AwsException e) {
                // Created by an earlier association; it outlives offboarding, as AWS keeps it.
            }
        }
    }

    // ---------------------------------------------------------------- policies

    public synchronized ObjectNode putPolicy(JsonNode input, String region) {
        ObjectNode admin = requireAdmin();
        JsonNode policy = input.path("Policy");
        String name = text(policy, "PolicyName");
        JsonNode data = policy.path("SecurityServicePolicyData");
        String type = text(data, "Type");
        if (!POLICY_TYPES.contains(type)) {
            throw invalidInput("SecurityServicePolicyData.Type " + type + " is not valid.");
        }
        String managed = data.path("ManagedServiceData").asText(null);
        if (managed != null) {
            try {
                JsonNode document = mapper.readTree(managed);
                if (document == null || !document.isObject()) {
                    throw invalidInput("ManagedServiceData must be a JSON object.");
                }
                if (document.has("type") && !type.equals(document.path("type").asText())) {
                    throw invalidInput("ManagedServiceData type " + document.path("type").asText()
                            + " does not match the policy type " + type + ".");
                }
            } catch (JsonProcessingException e) {
                throw invalidInput("ManagedServiceData is not valid JSON.");
            }
        }
        String resourceType = text(policy, "ResourceType");
        if ("ResourceTypeList".equals(resourceType) && policy.path("ResourceTypeList").isEmpty()) {
            throw invalidInput("ResourceTypeList is required when ResourceType is ResourceTypeList.");
        }
        for (String set : strings(policy.path("ResourceSetIds"))) {
            if (storage.getForAccount(caller(), key(region, "resourceset", set)).isEmpty()) {
                throw notFound("Resource set " + set + " not found.");
            }
        }
        String id = policy.path("PolicyId").asText(null);
        ObjectNode stored;
        if (id != null) {
            stored = record(region, "policy", id, "Policy");
            String token = policy.path("PolicyUpdateToken").asText(null);
            if (token == null || !token.equals(stored.path("PolicyUpdateToken").asText())) {
                throw invalidOperation("The PolicyUpdateToken does not match the policy's current version.");
            }
        } else {
            id = UUID.randomUUID().toString();
            stored = mapper.createObjectNode();
            stored.putObject("_").put("seq", System.nanoTime()).putObject("tags");
        }
        ObjectNode meta = (ObjectNode) stored.get("_");
        ObjectNode copy = policy.deepCopy();
        copy.put("PolicyId", id);
        copy.put("PolicyUpdateToken", Integer.toString(Integer.parseInt(stored.path("PolicyUpdateToken")
                .asText("0")) + 1));
        copy.put("ExcludeResourceTags", policy.path("ExcludeResourceTags").asBoolean(false));
        copy.put("RemediationEnabled", policy.path("RemediationEnabled").asBoolean(false));
        copy.put("PolicyStatus", policyTypeInScope(admin.path("AdminScope"), type)
                && regionInScope(admin.path("AdminScope"), region) ? "ACTIVE" : "OUT_OF_ADMIN_SCOPE");
        copy.set("_", meta);
        if (input.has("TagList")) {
            input.path("TagList").forEach(tag -> meta.withObject("/tags").put(tag.path("Key").asText(),
                    tag.path("Value").asText()));
        }
        meta.put("updatedAt", Instant.now().toEpochMilli() / 1000.0);
        storage.putForAccount(caller(), key(region, "policy", id), copy);
        ObjectNode response = mapper.createObjectNode();
        response.set("Policy", view(copy));
        response.put("PolicyArn", arn(region, "policy/" + id));
        return response;
    }

    public synchronized ObjectNode getPolicy(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "PolicyId");
        ObjectNode response = mapper.createObjectNode();
        response.set("Policy", view(record(region, "policy", id, "Policy")));
        response.put("PolicyArn", arn(region, "policy/" + id));
        return response;
    }

    public synchronized ObjectNode deletePolicy(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "PolicyId");
        record(region, "policy", id, "Policy");
        storage.deleteForAccount(caller(), key(region, "policy", id));
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listPolicies(JsonNode input, String region) {
        requireAdmin();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode policy : list(region, "policy")) {
            ObjectNode item = mapper.createObjectNode();
            item.put("PolicyArn", arn(region, "policy/" + policy.path("PolicyId").asText()))
                    .put("PolicyId", policy.path("PolicyId").asText())
                    .put("PolicyName", policy.path("PolicyName").asText())
                    .put("ResourceType", policy.path("ResourceType").asText())
                    .put("SecurityServiceType", policy.path("SecurityServicePolicyData").path("Type").asText())
                    .put("RemediationEnabled", policy.path("RemediationEnabled").asBoolean())
                    .put("DeleteUnusedFMManagedResources", policy.path("DeleteUnusedFMManagedResources")
                            .asBoolean(false))
                    .put("PolicyStatus", policy.path("PolicyStatus").asText());
            items.add(item);
        }
        return page(items, input, "PolicyList", 50);
    }

    /** The accounts a policy covers: its administrator's scope, narrowed by its include and exclude maps. */
    private List<String> covered(ObjectNode admin, JsonNode policy) {
        List<String> accounts = new ArrayList<>();
        Organization organization = organization().orElseThrow();
        Set<String> include = new LinkedHashSet<>(strings(policy.path("IncludeMap").path("ACCOUNT")));
        Set<String> exclude = new LinkedHashSet<>(strings(policy.path("ExcludeMap").path("ACCOUNT")));
        for (String account : accounts(organization)) {
            if (inScope(admin.path("AdminScope"), account) && (include.isEmpty() || include.contains(account))
                    && !exclude.contains(account)) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    public synchronized ObjectNode listComplianceStatus(JsonNode input, String region) {
        ObjectNode admin = requireAdmin();
        String id = text(input, "PolicyId");
        ObjectNode policy = record(region, "policy", id, "Policy");
        List<ObjectNode> items = new ArrayList<>();
        for (String account : covered(admin, policy)) {
            ObjectNode item = mapper.createObjectNode();
            item.put("PolicyOwner", caller()).put("PolicyId", id).put("PolicyName", policy.path("PolicyName").asText())
                    .put("MemberAccount", account);
            item.putArray("EvaluationResults").addObject().put("ComplianceStatus", "COMPLIANT")
                    .put("ViolatorCount", 0).put("EvaluationLimitExceeded", false);
            item.put("LastUpdated", policy.path("_").path("updatedAt").asDouble());
            item.putObject("IssueInfoMap");
            items.add(item);
        }
        return page(items, input, "PolicyComplianceStatusList", 100);
    }

    public synchronized ObjectNode getComplianceDetail(JsonNode input, String region) {
        ObjectNode admin = requireAdmin();
        String id = text(input, "PolicyId");
        String account = text(input, "MemberAccount");
        ObjectNode policy = record(region, "policy", id, "Policy");
        if (!covered(admin, policy).contains(account)) {
            throw notFound("Policy " + id + " does not cover account " + account + ".");
        }
        ObjectNode response = mapper.createObjectNode();
        ObjectNode detail = response.putObject("PolicyComplianceDetail");
        detail.put("PolicyOwner", caller()).put("PolicyId", id).put("MemberAccount", account);
        detail.putArray("Violators");
        detail.put("EvaluationLimitExceeded", false)
                .put("ExpiredAt", Instant.now().plusSeconds(3600).toEpochMilli() / 1000.0);
        detail.putObject("IssueInfoMap");
        return response;
    }

    public synchronized ObjectNode getViolationDetails(JsonNode input, String region) {
        requireAdmin();
        record(region, "policy", text(input, "PolicyId"), "Policy");
        throw notFound("Resource " + text(input, "ResourceId") + " has no violations of the policy.");
    }

    /** Shield Advanced attacks on a policy's resources: none in floci. */
    public synchronized ObjectNode getProtectionStatus(JsonNode input, String region) {
        requireAdmin();
        ObjectNode policy = record(region, "policy", text(input, "PolicyId"), "Policy");
        if (!"SHIELD_ADVANCED".equals(policy.path("SecurityServicePolicyData").path("Type").asText())) {
            throw invalidInput("GetProtectionStatus is only for SHIELD_ADVANCED policies.");
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("AdminAccountId", caller()).put("ServiceType", "SHIELD_ADVANCED").put("Data", "{}");
        return response;
    }

    // ---------------------------------------------------------------- applications and protocols lists

    public synchronized ObjectNode putAppsList(JsonNode input, String region) {
        return putList(input, region, "AppsList", "applications-list", "AppsList", "AppsListArn");
    }

    public synchronized ObjectNode getAppsList(JsonNode input, String region) {
        return getList(input, region, "AppsList", "applications-list", "AppsListArn");
    }

    public synchronized ObjectNode deleteAppsList(JsonNode input, String region) {
        return deleteList(input, region, "applications-list", "Applications list");
    }

    public synchronized ObjectNode listAppsLists(JsonNode input, String region) {
        return listLists(input, region, "applications-list", "AppsList", "AppsLists", "AppsList");
    }

    public synchronized ObjectNode putProtocolsList(JsonNode input, String region) {
        return putList(input, region, "ProtocolsList", "protocols-list", "ProtocolsList", "ProtocolsListArn");
    }

    public synchronized ObjectNode getProtocolsList(JsonNode input, String region) {
        return getList(input, region, "ProtocolsList", "protocols-list", "ProtocolsListArn");
    }

    public synchronized ObjectNode deleteProtocolsList(JsonNode input, String region) {
        return deleteList(input, region, "protocols-list", "Protocols list");
    }

    public synchronized ObjectNode listProtocolsLists(JsonNode input, String region) {
        return listLists(input, region, "protocols-list", "ProtocolsList", "ProtocolsLists", "ProtocolsList");
    }

    /** Creates a list, or updates one whose update token is current, keeping its previous entries by version. */
    private ObjectNode putList(JsonNode input, String region, String field, String kind, String entries,
                               String arnField) {
        requireAdmin();
        JsonNode list = input.path(field);
        String name = text(list, "ListName");
        String id = list.path("ListId").asText(null);
        ObjectNode stored;
        double now = Instant.now().toEpochMilli() / 1000.0;
        if (id != null) {
            stored = record(region, kind, id, field);
            String token = list.path("ListUpdateToken").asText(null);
            if (token == null || !token.equals(stored.path("ListUpdateToken").asText())) {
                throw invalidOperation("The ListUpdateToken does not match the list's current version.");
            }
            stored.withObject("/Previous" + entries).set(stored.path("ListUpdateToken").asText(),
                    stored.path(entries).deepCopy());
        } else {
            id = UUID.randomUUID().toString();
            stored = mapper.createObjectNode();
            stored.put("CreateTime", now);
            stored.putObject("_").put("seq", System.nanoTime()).putObject("tags");
        }
        stored.put("ListId", id).put("ListName", name).put("LastUpdateTime", now)
                .put("ListUpdateToken", Integer.toString(Integer.parseInt(stored.path("ListUpdateToken")
                        .asText("0")) + 1));
        stored.set(entries, list.path(entries).deepCopy());
        input.path("TagList").forEach(tag -> stored.withObject("/_/tags").put(tag.path("Key").asText(),
                tag.path("Value").asText()));
        storage.putForAccount(caller(), key(region, kind, id), stored);
        ObjectNode response = mapper.createObjectNode();
        response.set(field, view(stored));
        response.put(arnField, arn(region, kind + "/" + id));
        return response;
    }

    private ObjectNode getList(JsonNode input, String region, String field, String kind, String arnField) {
        requireAdmin();
        String id = text(input, "ListId");
        ObjectNode list = input.path("DefaultList").asBoolean(false) ? defaultList(region, kind, id)
                : record(region, kind, id, field);
        ObjectNode response = mapper.createObjectNode();
        response.set(field, view(list));
        response.put(arnField, arn(region, kind + "/" + id));
        return response;
    }

    private ObjectNode deleteList(JsonNode input, String region, String kind, String what) {
        requireAdmin();
        String id = text(input, "ListId");
        if (storage.getForAccount(caller(), key(region, kind, id)).isEmpty()) {
            throw notFound(what + " " + id + " not found.");
        }
        storage.deleteForAccount(caller(), key(region, kind, id));
        return mapper.createObjectNode();
    }

    private ObjectNode listLists(JsonNode input, String region, String kind, String field, String out,
                                 String entries) {
        requireAdmin();
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> lists = input.path("DefaultLists").asBoolean(false)
                ? List.of(defaultList(region, kind, null)) : list(region, kind);
        for (ObjectNode list : lists) {
            ObjectNode item = mapper.createObjectNode();
            item.put("ListArn", arn(region, kind + "/" + list.path("ListId").asText()))
                    .put("ListId", list.path("ListId").asText()).put("ListName", list.path("ListName").asText());
            item.set(entries, list.path(entries).deepCopy());
            items.add(item);
        }
        return page(items, input, out, 100);
    }

    /** The AWS-managed default list of a kind, which every administrator can read and none can change. */
    private ObjectNode defaultList(String region, String kind, String id) {
        boolean apps = "applications-list".equals(kind);
        String listId = apps ? "da5a8bd4-5b4d-4d4c-9bfc-0e06e5d06a7c" : "b0b25d5c-7c56-4a8e-9d8d-2f1d3e0c5e3e";
        if (id != null && !id.equals(listId)) {
            throw notFound((apps ? "Applications" : "Protocols") + " list " + id + " is not a default list.");
        }
        ObjectNode list = mapper.createObjectNode();
        list.put("ListId", listId).put("ListName", apps ? "FMS-Default-Public-Access-Apps-Allowed"
                : "FMS-Default-Protocols-Allowed").put("ListUpdateToken", "1");
        if (apps) {
            ArrayNode entries = list.putArray("AppsList");
            entries.addObject().put("AppName", "HTTP").put("Protocol", "tcp").put("Port", 80);
            entries.addObject().put("AppName", "HTTPS").put("Protocol", "tcp").put("Port", 443);
        } else {
            list.putArray("ProtocolsList").add("tcp").add("udp").add("icmp");
        }
        list.putObject("_");
        return list;
    }

    // ---------------------------------------------------------------- resource sets

    public synchronized ObjectNode putResourceSet(JsonNode input, String region) {
        requireAdmin();
        JsonNode set = input.path("ResourceSet");
        String id = set.path("Id").asText(null);
        ObjectNode stored;
        if (id != null) {
            stored = record(region, "resourceset", id, "ResourceSet");
            String token = set.path("UpdateToken").asText(null);
            if (token == null || !token.equals(stored.path("UpdateToken").asText())) {
                throw invalidOperation("The UpdateToken does not match the resource set's current version.");
            }
        } else {
            StringBuilder generated = new StringBuilder();
            for (int index = 0; index < 22; index++) {
                generated.append(BASE62.charAt(random.nextInt(BASE62.length())));
            }
            id = generated.toString();
            stored = mapper.createObjectNode();
            stored.putObject("_").put("seq", System.nanoTime()).putObject("tags");
            ((ObjectNode) stored.get("_")).putArray("resources");
        }
        stored.put("Id", id).put("Name", text(set, "Name"))
                .put("Description", set.path("Description").asText(stored.path("Description").asText("")))
                .put("UpdateToken", Integer.toString(Integer.parseInt(stored.path("UpdateToken").asText("0")) + 1))
                .put("LastUpdateTime", Instant.now().toEpochMilli() / 1000.0).put("ResourceSetStatus", "ACTIVE");
        stored.set("ResourceTypeList", set.path("ResourceTypeList").deepCopy());
        input.path("TagList").forEach(tag -> stored.withObject("/_/tags").put(tag.path("Key").asText(),
                tag.path("Value").asText()));
        storage.putForAccount(caller(), key(region, "resourceset", id), stored);
        ObjectNode response = mapper.createObjectNode();
        response.set("ResourceSet", view(stored));
        response.put("ResourceSetArn", arn(region, "resource-set/" + id));
        return response;
    }

    public synchronized ObjectNode getResourceSet(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "Identifier");
        ObjectNode response = mapper.createObjectNode();
        response.set("ResourceSet", view(record(region, "resourceset", id, "ResourceSet")));
        response.put("ResourceSetArn", arn(region, "resource-set/" + id));
        return response;
    }

    public synchronized ObjectNode deleteResourceSet(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "Identifier");
        record(region, "resourceset", id, "ResourceSet");
        for (ObjectNode policy : list(region, "policy")) {
            if (strings(policy.path("ResourceSetIds")).contains(id)) {
                throw invalidOperation("Resource set " + id + " is used by policy " + policy.path("PolicyName")
                        .asText() + ".");
            }
        }
        storage.deleteForAccount(caller(), key(region, "resourceset", id));
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode listResourceSets(JsonNode input, String region) {
        requireAdmin();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode set : list(region, "resourceset")) {
            ObjectNode item = mapper.createObjectNode();
            item.put("Id", set.path("Id").asText()).put("Name", set.path("Name").asText())
                    .put("Description", set.path("Description").asText(""))
                    .put("LastUpdateTime", set.path("LastUpdateTime").asDouble())
                    .put("ResourceSetStatus", set.path("ResourceSetStatus").asText());
            items.add(item);
        }
        return page(items, input, "ResourceSets", 100);
    }

    /** Adds resources, by ARN, to a resource set; one that is no ARN, or of a type the set does not take, fails. */
    public synchronized ObjectNode batchAssociateResource(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "ResourceSetIdentifier");
        ObjectNode set = record(region, "resourceset", id, "ResourceSet");
        ArrayNode resources = set.withArray("/_/resources");
        ObjectNode response = mapper.createObjectNode();
        response.put("ResourceSetIdentifier", id);
        ArrayNode failed = response.putArray("FailedItems");
        for (JsonNode item : input.path("Items")) {
            String uri = item.asText();
            if (!AwsArnUtils.isArn(uri)) {
                failed.addObject().put("URI", uri).put("Reason", "NOT_VALID_ARN");
                continue;
            }
            boolean present = false;
            for (JsonNode resource : resources) {
                present |= uri.equals(resource.path("URI").asText());
            }
            if (!present) {
                resources.addObject().put("URI", uri).put("AccountId", AwsArnUtils.parse(uri).accountId());
            }
        }
        storage.putForAccount(caller(), key(region, "resourceset", id), set);
        return response;
    }

    public synchronized ObjectNode batchDisassociateResource(JsonNode input, String region) {
        requireAdmin();
        String id = text(input, "ResourceSetIdentifier");
        ObjectNode set = record(region, "resourceset", id, "ResourceSet");
        ArrayNode resources = set.withArray("/_/resources");
        ObjectNode response = mapper.createObjectNode();
        response.put("ResourceSetIdentifier", id);
        ArrayNode failed = response.putArray("FailedItems");
        for (JsonNode item : input.path("Items")) {
            boolean removed = false;
            for (int index = resources.size() - 1; index >= 0; index--) {
                if (item.asText().equals(resources.get(index).path("URI").asText())) {
                    resources.remove(index);
                    removed = true;
                }
            }
            if (!removed) {
                failed.addObject().put("URI", item.asText()).put("Reason", "NOT_VALID_ARN");
            }
        }
        storage.putForAccount(caller(), key(region, "resourceset", id), set);
        return response;
    }

    public synchronized ObjectNode listResourceSetResources(JsonNode input, String region) {
        requireAdmin();
        ObjectNode set = record(region, "resourceset", text(input, "Identifier"), "ResourceSet");
        List<ObjectNode> items = new ArrayList<>();
        set.path("_").path("resources").forEach(resource -> items.add(((ObjectNode) resource).deepCopy()));
        ObjectNode response = page(items, input, "Items", 100);
        return response;
    }

    /** Resources of a type in member accounts that floci discovers for policies: none. */
    public synchronized ObjectNode listDiscoveredResources(JsonNode input, String region) {
        requireAdmin();
        text(input, "ResourceType");
        return page(List.of(), input, "Items", 100);
    }

    // ---------------------------------------------------------------- notification channel

    public synchronized ObjectNode putNotificationChannel(JsonNode input, String region) {
        requireAdmin();
        String topic = text(input, "SnsTopicArn");
        String role = text(input, "SnsRoleName");
        if (sns.isResolvable()) {
            try {
                sns.get().getTopicAttributes(topic, region);
            } catch (AwsException e) {
                throw notFound("SNS topic " + topic + " not found.");
            }
        }
        String roleName = role.contains("/") ? role.substring(role.lastIndexOf('/') + 1) : role;
        if (iam.findRole(caller(), roleName).isEmpty()) {
            throw notFound("IAM role " + role + " not found.");
        }
        ObjectNode channel = mapper.createObjectNode();
        channel.put("SnsTopicArn", topic).put("SnsRoleName", role);
        storage.putForAccount(caller(), key(region, "channel", "default"), channel);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode getNotificationChannel(JsonNode input, String region) {
        requireAdmin();
        return storage.getForAccount(caller(), key(region, "channel", "default")).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("No notification channel is configured."));
    }

    public synchronized ObjectNode deleteNotificationChannel(JsonNode input, String region) {
        requireAdmin();
        if (storage.getForAccount(caller(), key(region, "channel", "default")).isEmpty()) {
            throw notFound("No notification channel is configured.");
        }
        storage.deleteForAccount(caller(), key(region, "channel", "default"));
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- third-party firewalls

    public synchronized ObjectNode associateThirdPartyFirewall(JsonNode input, String region) {
        requireAdmin();
        String firewall = firewall(input);
        ObjectNode status = mapper.createObjectNode();
        status.put("ThirdPartyFirewallStatus", "ONBOARD_COMPLETE");
        storage.putForAccount(caller(), key(region, "thirdparty", firewall), status);
        ObjectNode response = mapper.createObjectNode();
        response.put("ThirdPartyFirewallStatus", "ONBOARDING");
        return response;
    }

    public synchronized ObjectNode disassociateThirdPartyFirewall(JsonNode input, String region) {
        requireAdmin();
        String firewall = firewall(input);
        if (storage.getForAccount(caller(), key(region, "thirdparty", firewall)).isEmpty()) {
            throw invalidOperation("The account is not onboarded to " + firewall + ".");
        }
        storage.deleteForAccount(caller(), key(region, "thirdparty", firewall));
        ObjectNode response = mapper.createObjectNode();
        response.put("ThirdPartyFirewallStatus", "OFFBOARDING");
        return response;
    }

    /** Onboarding status: NOT_EXIST until associated; floci has no Marketplace subscriptions. */
    public synchronized ObjectNode getThirdPartyFirewallAssociationStatus(JsonNode input, String region) {
        requireAdmin();
        String firewall = firewall(input);
        ObjectNode response = mapper.createObjectNode();
        response.put("ThirdPartyFirewallStatus", storage.getForAccount(caller(), key(region, "thirdparty",
                firewall)).map(status -> status.path("ThirdPartyFirewallStatus").asText()).orElse("NOT_EXIST"))
                .put("MarketplaceOnboardingStatus", "NO_SUBSCRIPTION");
        return response;
    }

    public synchronized ObjectNode listThirdPartyFirewallFirewallPolicies(JsonNode input, String region) {
        requireAdmin();
        String firewall = firewall(input);
        if (storage.getForAccount(caller(), key(region, "thirdparty", firewall)).isEmpty()) {
            throw invalidOperation("The account is not onboarded to " + firewall + ".");
        }
        return page(List.of(), input, "ThirdPartyFirewallFirewallPolicies", 100);
    }

    private static String firewall(JsonNode input) {
        String firewall = text(input, "ThirdPartyFirewall");
        if (!FIREWALLS.contains(firewall)) {
            throw invalidInput("ThirdPartyFirewall " + firewall + " is not valid.");
        }
        return firewall;
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(JsonNode input, String region) {
        ObjectNode record = byArn(region, text(input, "ResourceArn"));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode tags = response.putArray("TagList");
        record.path("_").path("tags").fields().forEachRemaining(entry -> tags.addObject()
                .put("Key", entry.getKey()).put("Value", entry.getValue().asText()));
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = byArn(region, arn);
        ObjectNode tags = record.withObject("/_/tags");
        input.path("TagList").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        if (tags.size() > 200) {
            throw new AwsException("LimitExceededException", "A resource can have at most 200 tags.", 400);
        }
        saveByArn(region, arn, record);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode input, String region) {
        String arn = text(input, "ResourceArn");
        ObjectNode record = byArn(region, arn);
        ObjectNode tags = record.withObject("/_/tags");
        input.path("TagKeys").forEach(key -> tags.remove(key.asText()));
        saveByArn(region, arn, record);
        return mapper.createObjectNode();
    }

    private record ArnRef(String kind, String id) {
    }

    private ArnRef arnRef(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalidInput("Invalid resource ARN " + arn + ".");
        }
        String[] parts = parsed.resource().split("/", 2);
        String kind = switch (parts[0]) {
            case "policy" -> "policy";
            case "applications-list" -> "applications-list";
            case "protocols-list" -> "protocols-list";
            case "resource-set" -> "resourceset";
            default -> null;
        };
        if (!"fms".equals(parsed.service()) || kind == null || parts.length < 2) {
            throw invalidInput("Invalid resource ARN " + arn + ".");
        }
        if (!region.equals(parsed.region()) || !caller().equals(parsed.accountId())) {
            throw notFound("Resource " + arn + " not found.");
        }
        return new ArnRef(kind, parts[1]);
    }

    private ObjectNode byArn(String region, String arn) {
        ArnRef ref = arnRef(region, arn);
        return storage.getForAccount(caller(), key(region, ref.kind(), ref.id()))
                .orElseThrow(() -> notFound("Resource " + arn + " not found."));
    }

    private void saveByArn(String region, String arn, ObjectNode record) {
        ArnRef ref = arnRef(region, arn);
        storage.putForAccount(caller(), key(region, ref.kind(), ref.id()), record);
    }

    // ---------------------------------------------------------------- shared

    private ObjectNode record(String region, String kind, String id, String what) {
        return storage.getForAccount(caller(), key(region, kind, id))
                .orElseThrow(() -> notFound(what + " " + id + " not found."));
    }

    private List<ObjectNode> list(String region, String kind) {
        String prefix = key(region, kind, "");
        List<ObjectNode> records = new ArrayList<>(storage.scanForAccount(caller(), key -> key.startsWith(prefix)));
        records.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return records;
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("fms", region, caller(), resource).toString();
    }

    private static ObjectNode view(ObjectNode record) {
        ObjectNode copy = record.deepCopy();
        copy.remove("_");
        return copy;
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        int limit = input.hasNonNull("MaxResults") ? input.get("MaxResults").asInt() : max;
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), Math.min(Math.max(limit, 1), max),
                input.path("NextToken").asText(null), field, bad -> invalidInput("The NextToken is not valid."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    private static List<String> strings(JsonNode list) {
        List<String> values = new ArrayList<>();
        list.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw invalidInput(field + " is required.");
        }
        return value.asText();
    }

    static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException invalidOperation(String message) {
        return new AwsException("InvalidOperationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
