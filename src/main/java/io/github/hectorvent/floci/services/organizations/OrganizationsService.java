package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountRequest;
import io.github.hectorvent.floci.services.organizations.model.DelegatedAdministratorRegistration;
import io.github.hectorvent.floci.services.organizations.model.OrgHandshake;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationRecord;
import io.github.hectorvent.floci.services.organizations.model.OrganizationResourcePolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AWS Organizations JSON 1.1 ({@code AWSOrganizationsV20161128.*}).
 *
 * <p>A default organization is seeded at startup so Alchemy's standing-org
 * bindings (DescribeOrganization, ListPolicies/p-FullAWSAccess, …) match a
 * management account. {@link #clear()} and {@code DeleteOrganization} leave
 * no organization until {@code CreateOrganization}.
 */
@ApplicationScoped
public class OrganizationsService implements Resettable {

    static final String SERVICE = "organizations";
    static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";
    static final String DEFAULT_ORG_ID = "o-flocidefault";
    static final String DEFAULT_ROOT_ID = "r-flci";
    static final String DEFAULT_MASTER_EMAIL = "management@floci.local";
    static final String DEFAULT_MASTER_NAME = "floci";
    private static final String ORG_KEY = "current";
    private static final String POLICY_KEY = "resource-policy";
    private static final String FULL_AWS_ACCESS_ID = "p-FullAWSAccess";
    private static final String FULL_AWS_ACCESS_CONTENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";
    private static final Set<String> POLICY_TYPES = Set.of(
            "SERVICE_CONTROL_POLICY",
            "RESOURCE_CONTROL_POLICY",
            "TAG_POLICY",
            "BACKUP_POLICY",
            "AISERVICES_OPT_OUT_POLICY",
            "CHATBOT_POLICY",
            "DECLARATIVE_POLICY_EC2",
            "SECURITYHUB_POLICY");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern HANDSHAKE_ID = Pattern.compile("^h-[a-z0-9]{8,32}$");
    private static final Pattern CAR_ID = Pattern.compile("^car-[a-z0-9]{8,32}$");

    private final StorageBackend<String, OrganizationRecord> organizations;
    private final StorageBackend<String, OrganizationAccount> accounts;
    private final StorageBackend<String, CreateAccountRequest> createRequests;
    private final StorageBackend<String, DelegatedAdministratorRegistration> delegated;
    private final StorageBackend<String, OrganizationalUnit> ous;
    private final StorageBackend<String, OrganizationResourcePolicy> resourcePolicies;
    private final ConcurrentHashMap<String, OrganizationPolicy> policies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrgHandshake> handshakes = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public OrganizationsService(StorageFactory factory, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this(factory.create(SERVICE, "organizations-org.json",
                        new TypeReference<Map<String, OrganizationRecord>>() {
                        }),
                factory.create(SERVICE, "organizations-accounts.json",
                        new TypeReference<Map<String, OrganizationAccount>>() {
                        }),
                factory.create(SERVICE, "organizations-create-requests.json",
                        new TypeReference<Map<String, CreateAccountRequest>>() {
                        }),
                factory.create(SERVICE, "organizations-delegated-administrators.json",
                        new TypeReference<Map<String, DelegatedAdministratorRegistration>>() {
                        }),
                factory.create(SERVICE, "organizations-ous.json",
                        new TypeReference<Map<String, OrganizationalUnit>>() {
                        }),
                factory.create(SERVICE, "organizations-resource-policy.json",
                        new TypeReference<Map<String, OrganizationResourcePolicy>>() {
                        }),
                objectMapper, regionResolver);
        ensureDefaultOrganization();
    }

    OrganizationsService(
            StorageBackend<String, OrganizationRecord> organizations,
            StorageBackend<String, OrganizationAccount> accounts,
            StorageBackend<String, CreateAccountRequest> createRequests,
            StorageBackend<String, DelegatedAdministratorRegistration> delegated,
            StorageBackend<String, OrganizationalUnit> ous,
            StorageBackend<String, OrganizationResourcePolicy> resourcePolicies,
            ObjectMapper objectMapper,
            RegionResolver regionResolver) {
        this.organizations = organizations;
        this.accounts = accounts;
        this.createRequests = createRequests;
        this.delegated = delegated;
        this.ous = ous;
        this.resourcePolicies = resourcePolicies;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        organizations.clear();
        accounts.clear();
        createRequests.clear();
        delegated.clear();
        ous.clear();
        resourcePolicies.clear();
        policies.clear();
        handshakes.clear();
    }

    public ObjectNode listAccounts(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        List<OrganizationAccount> items = new ArrayList<>(accounts.values());
        items.sort(Comparator.comparing(OrganizationAccount::getId));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Accounts");
        for (OrganizationAccount account : items) {
            list.add(toAccountNode(org, account));
        }
        return response;
    }

    public ObjectNode describeAccount(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        OrganizationAccount account = accounts.get(requireText(request, "AccountId")).orElseThrow(() ->
                new AwsException("AccountNotFoundException", "You specified an account that doesn't exist.", 404));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Account", toAccountNode(org, account));
        return response;
    }

    public ObjectNode listParents(JsonNode request) {
        requireOrganization();
        String childId = requireText(request, "ChildId");
        String parentId;
        String parentType;
        OrganizationAccount account = accounts.get(childId).orElse(null);
        if (account != null) {
            parentId = account.getParentId();
            parentType = account.getParentType();
        } else {
            OrganizationalUnit ou = ous.get(childId).orElseThrow(() ->
                    new AwsException("ChildNotFoundException", "You specified a child that doesn't exist.", 404));
            parentId = ou.getParentId();
            parentType = ou.getParentType();
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parents = response.putArray("Parents");
        ObjectNode parent = parents.addObject();
        parent.put("Id", parentId);
        parent.put("Type", parentType);
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        requireOrganization();
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("Tags"), tagsFor(requireText(request, "ResourceId")));
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String resourceId = requireText(request, "ResourceId");
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw new AwsException("InvalidInputException", "Tags is a required parameter.", 400);
        }
        Map<String, String> tags = tagsFor(resourceId);
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.hasNonNull("Key")) {
                throw new AwsException("InvalidInputException", "Tag Key is a required parameter.", 400);
            }
            tags.put(tag.get("Key").asText(), tag.path("Value").asText(""));
        }
        storeTags(org, resourceId, tags);
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String resourceId = requireText(request, "ResourceId");
        JsonNode keys = request.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw new AwsException("InvalidInputException", "TagKeys is a required parameter.", 400);
        }
        Map<String, String> tags = tagsFor(resourceId);
        for (JsonNode key : keys) {
            tags.remove(key.asText());
        }
        storeTags(org, resourceId, tags);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode createAccount(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String email = requireText(request, "Email");
        String name = requireText(request, "AccountName");
        long now = Instant.now().getEpochSecond();
        CreateAccountRequest status = new CreateAccountRequest();
        status.setId("car-" + UUID.randomUUID().toString().replace("-", ""));
        status.setAccountName(name);
        status.setRequestedTimestamp(now);
        status.setCompletedTimestamp(now);
        for (OrganizationAccount existing : accounts.values()) {
            if (email.equalsIgnoreCase(existing.getEmail())) {
                status.setState("FAILED");
                status.setFailureReason("EMAIL_ALREADY_EXISTS");
                createRequests.put(status.getId(), status);
                return wrapCreateStatus(status);
            }
        }
        String accountId = nextAccountId(org);
        OrganizationAccount account = new OrganizationAccount();
        account.setId(accountId);
        account.setArn(accountArn(org, accountId));
        account.setEmail(email);
        account.setName(name);
        account.setStatus("ACTIVE");
        account.setState("ACTIVE");
        account.setJoinedMethod("CREATED");
        account.setJoinedTimestamp(now);
        account.setParentId(org.getRootId());
        account.setParentType("ROOT");
        account.setTags(readTags(request.get("Tags")));
        accounts.put(accountId, account);
        status.setState("SUCCEEDED");
        status.setAccountId(accountId);
        createRequests.put(status.getId(), status);
        return wrapCreateStatus(status);
    }

    public ObjectNode describeCreateAccountStatus(JsonNode request) {
        requireOrganization();
        String requestId = requireText(request, "CreateAccountRequestId");
        if (!CAR_ID.matcher(requestId).matches()) {
            throw new AwsException("InvalidInputException", "You specified an invalid create account request ID.", 400);
        }
        CreateAccountRequest status = createRequests.get(requestId).orElseThrow(() ->
                new AwsException("CreateAccountStatusNotFoundException",
                        "You specified a create account request that doesn't exist.", 404));
        return wrapCreateStatus(status);
    }

    public ObjectNode listCreateAccountStatus(JsonNode request) {
        requireOrganization();
        List<CreateAccountRequest> items = new ArrayList<>(createRequests.values());
        items.sort(Comparator.comparing(CreateAccountRequest::getId));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("CreateAccountStatuses");
        for (CreateAccountRequest status : items) {
            list.add(wrapCreateStatus(status).get("CreateAccountStatus"));
        }
        return response;
    }

    public ObjectNode removeAccountFromOrganization(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String accountId = requireText(request, "AccountId");
        if (accountId.equals(org.getMasterAccountId())) {
            throw new AwsException("ConstraintViolationException",
                    "You cannot remove the management account from the organization.", 409);
        }
        if (accounts.get(accountId).isEmpty()) {
            throw new AwsException("AccountNotFoundException", "You specified an account that doesn't exist.", 404);
        }
        accounts.delete(accountId);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeOrganization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Organization", toOrganizationNode(requireOrganization()));
        return response;
    }

    public synchronized ObjectNode createOrganization(JsonNode request) {
        if (organizations.get(ORG_KEY).isPresent()) {
            throw new AwsException("AlreadyInOrganizationException",
                    "This account is already a member of an organization.", 409);
        }
        String featureSet = textOr(request, "FeatureSet", "ALL");
        if (!"ALL".equals(featureSet) && !"CONSOLIDATED_BILLING".equals(featureSet)) {
            throw new AwsException("InvalidInputException", "You specified an invalid value for FeatureSet.", 400);
        }
        seedOrganization(featureSet);
        return describeOrganization();
    }

    public synchronized ObjectNode deleteOrganization() {
        clear();
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode enableAllFeatures() {
        OrganizationRecord org = requireOrganization();
        if ("ALL".equals(org.getFeatureSet())) {
            throw new AwsException("ConstraintViolationException",
                    "The requested operation cannot be performed on the organization in its current state.", 409);
        }
        org.setFeatureSet("ALL");
        organizations.put(ORG_KEY, org);
        ensureFullAwsAccessPolicy();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAWSServiceAccessForOrganization(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode principals = response.putArray("EnabledServicePrincipals");
        org.getEnabledServicePrincipals().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ObjectNode node = principals.addObject();
                    node.put("ServicePrincipal", entry.getKey());
                    node.put("DateEnabled", entry.getValue());
                });
        return response;
    }

    public synchronized ObjectNode enableAWSServiceAccess(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        org.getEnabledServicePrincipals().putIfAbsent(
                requireText(request, "ServicePrincipal"), Instant.now().getEpochSecond());
        organizations.put(ORG_KEY, org);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode disableAWSServiceAccess(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        org.getEnabledServicePrincipals().remove(requireText(request, "ServicePrincipal"));
        organizations.put(ORG_KEY, org);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listRoots(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode roots = response.putArray("Roots");
        ObjectNode root = roots.addObject();
        root.put("Id", org.getRootId());
        root.put("Arn", rootArn(org));
        root.put("Name", "Root");
        ArrayNode policyTypes = root.putArray("PolicyTypes");
        if ("ALL".equals(org.getFeatureSet())) {
            ObjectNode scp = policyTypes.addObject();
            scp.put("Type", "SERVICE_CONTROL_POLICY");
            scp.put("Status", "ENABLED");
        }
        return response;
    }

    public synchronized ObjectNode moveAccount(JsonNode request) {
        requireOrganization();
        String accountId = requireText(request, "AccountId");
        String destination = requireText(request, "DestinationParentId");
        requireText(request, "SourceParentId");
        requireParent(destination);
        OrganizationAccount account = accounts.get(accountId).orElseThrow(() ->
                new AwsException("AccountNotFoundException", "You specified an account that doesn't exist.", 404));
        account.setParentId(destination);
        account.setParentType(destination.startsWith("ou-") ? "ORGANIZATIONAL_UNIT" : "ROOT");
        accounts.put(accountId, account);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listOrganizationalUnitsForParent(JsonNode request) {
        requireOrganization();
        String parentId = requireText(request, "ParentId");
        requireParent(parentId);
        List<OrganizationalUnit> items = new ArrayList<>();
        for (OrganizationalUnit ou : ous.values()) {
            if (parentId.equals(ou.getParentId())) {
                items.add(ou);
            }
        }
        items.sort(Comparator.comparing(OrganizationalUnit::getId));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("OrganizationalUnits");
        for (OrganizationalUnit ou : items) {
            list.add(toOuNode(ou));
        }
        return response;
    }

    public ObjectNode describeOrganizationalUnit(JsonNode request) {
        requireOrganization();
        OrganizationalUnit ou = ous.get(requireText(request, "OrganizationalUnitId"))
                .orElseThrow(OrganizationsService::ouNotFound);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", toOuNode(ou));
        return response;
    }

    public synchronized ObjectNode createOrganizationalUnit(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String parentId = requireText(request, "ParentId");
        String name = requireText(request, "Name");
        requireParent(parentId);
        for (OrganizationalUnit existing : ous.values()) {
            if (parentId.equals(existing.getParentId()) && name.equals(existing.getName())) {
                throw new AwsException("DuplicateOrganizationalUnitException",
                        "An OU with the same name already exists under this parent.", 400);
            }
        }
        String ouId = "ou-flci-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        OrganizationalUnit ou = new OrganizationalUnit();
        ou.setId(ouId);
        ou.setArn("arn:aws:organizations::" + org.getMasterAccountId() + ":ou/" + org.getId() + "/" + ouId);
        ou.setName(name);
        ou.setParentId(parentId);
        ou.setParentType(parentId.startsWith("ou-") ? "ORGANIZATIONAL_UNIT" : "ROOT");
        ou.setTags(readTags(request.get("Tags")));
        ous.put(ouId, ou);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", toOuNode(ou));
        return response;
    }

    public synchronized ObjectNode updateOrganizationalUnit(JsonNode request) {
        requireOrganization();
        String ouId = requireText(request, "OrganizationalUnitId");
        OrganizationalUnit ou = ous.get(ouId).orElseThrow(OrganizationsService::ouNotFound);
        if (request.hasNonNull("Name")) {
            ou.setName(request.get("Name").asText());
        }
        ous.put(ouId, ou);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("OrganizationalUnit", toOuNode(ou));
        return response;
    }

    public synchronized ObjectNode deleteOrganizationalUnit(JsonNode request) {
        requireOrganization();
        String ouId = requireText(request, "OrganizationalUnitId");
        if (ous.get(ouId).isEmpty()) {
            throw ouNotFound();
        }
        ous.delete(ouId);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDelegatedAdministrators(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String servicePrincipal = textOr(request, "ServicePrincipal", null);
        Map<String, DelegatedAdministratorRegistration> earliest = new LinkedHashMap<>();
        for (DelegatedAdministratorRegistration registration : delegated.values()) {
            if (servicePrincipal != null && !servicePrincipal.equals(registration.getServicePrincipal())) {
                continue;
            }
            DelegatedAdministratorRegistration existing = earliest.get(registration.getAccountId());
            if (existing == null || registration.getDelegationEnabledDate() < existing.getDelegationEnabledDate()) {
                earliest.put(registration.getAccountId(), registration);
            }
        }
        List<String> accountIds = new ArrayList<>(earliest.keySet());
        accountIds.sort(Comparator.naturalOrder());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode admins = response.putArray("DelegatedAdministrators");
        for (String accountId : accountIds) {
            OrganizationAccount account = accounts.get(accountId).orElse(null);
            if (account == null) {
                continue;
            }
            ObjectNode node = toAccountNode(org, account);
            node.put("DelegationEnabledDate", earliest.get(accountId).getDelegationEnabledDate());
            admins.add(node);
        }
        return response;
    }

    public ObjectNode listDelegatedServicesForAccount(JsonNode request) {
        requireOrganization();
        String accountId = requireText(request, "AccountId");
        if (accounts.get(accountId).isEmpty()) {
            throw new AwsException("AccountNotFoundException", "You specified an account that doesn't exist.", 404);
        }
        List<DelegatedAdministratorRegistration> services = new ArrayList<>();
        for (DelegatedAdministratorRegistration registration : delegated.values()) {
            if (accountId.equals(registration.getAccountId())) {
                services.add(registration);
            }
        }
        if (services.isEmpty()) {
            throw new AwsException("AccountNotRegisteredException",
                    "The specified account is not a registered delegated administrator.", 409);
        }
        services.sort(Comparator.comparing(DelegatedAdministratorRegistration::getServicePrincipal));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DelegatedServices");
        for (DelegatedAdministratorRegistration registration : services) {
            ObjectNode node = list.addObject();
            node.put("ServicePrincipal", registration.getServicePrincipal());
            node.put("DelegationEnabledDate", registration.getDelegationEnabledDate());
        }
        return response;
    }

    public synchronized ObjectNode registerDelegatedAdministrator(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String accountId = requireText(request, "AccountId");
        String servicePrincipal = requireText(request, "ServicePrincipal");
        if (accountId.equals(org.getMasterAccountId())) {
            throw new AwsException("ConstraintViolationException",
                    "You cannot register the management account as a delegated administrator.", 409);
        }
        OrganizationAccount account = accounts.get(accountId).orElse(null);
        if (account == null) {
            account = new OrganizationAccount();
            account.setId(accountId);
            account.setArn(accountArn(org, accountId));
            account.setEmail(accountId + "@example.com");
            account.setName("Member " + accountId);
            account.setStatus("ACTIVE");
            account.setState("ACTIVE");
            account.setJoinedMethod("INVITED");
            account.setJoinedTimestamp(Instant.now().getEpochSecond());
            account.setParentId(org.getRootId());
            account.setParentType("ROOT");
            accounts.put(accountId, account);
        }
        String key = delegatedKey(accountId, servicePrincipal);
        if (delegated.get(key).isPresent()) {
            throw new AwsException("AccountAlreadyRegisteredException",
                    "The specified account is already a registered delegated administrator for the service.", 409);
        }
        DelegatedAdministratorRegistration registration = new DelegatedAdministratorRegistration();
        registration.setAccountId(accountId);
        registration.setServicePrincipal(servicePrincipal);
        registration.setDelegationEnabledDate(Instant.now().getEpochSecond());
        delegated.put(key, registration);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deregisterDelegatedAdministrator(JsonNode request) {
        requireOrganization();
        String key = delegatedKey(requireText(request, "AccountId"), requireText(request, "ServicePrincipal"));
        if (delegated.get(key).isEmpty()) {
            throw new AwsException("AccountNotRegisteredException",
                    "The specified account is not a registered delegated administrator for the service.", 409);
        }
        delegated.delete(key);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeResourcePolicy() {
        requireOrganization();
        OrganizationResourcePolicy policy = resourcePolicies.get(POLICY_KEY).orElse(null);
        if (policy == null) {
            throw policyNotFound();
        }
        return toResourcePolicyResponse(policy);
    }

    public synchronized ObjectNode putResourcePolicy(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String content = requirePolicyContent(request);
        OrganizationResourcePolicy policy = resourcePolicies.get(POLICY_KEY).orElse(null);
        if (policy == null) {
            policy = new OrganizationResourcePolicy();
            policy.setId("p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            policy.setArn("arn:aws:organizations::" + org.getMasterAccountId()
                    + ":resourcepolicy/" + org.getId() + "/" + policy.getId());
        }
        policy.setContent(content);
        resourcePolicies.put(POLICY_KEY, policy);
        return toResourcePolicyResponse(policy);
    }

    public void deleteResourcePolicy() {
        requireOrganization();
        if (resourcePolicies.get(POLICY_KEY).isEmpty()) {
            throw policyNotFound();
        }
        resourcePolicies.delete(POLICY_KEY);
    }

    public ObjectNode listAccountsForParent(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        String parentId = requireText(request, "ParentId");
        requireParent(parentId);
        List<OrganizationAccount> items = new ArrayList<>();
        for (OrganizationAccount account : accounts.values()) {
            if (parentId.equals(account.getParentId())) {
                items.add(account);
            }
        }
        items.sort(Comparator.comparing(OrganizationAccount::getId));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Accounts");
        for (OrganizationAccount account : items) {
            list.add(toAccountNode(org, account));
        }
        return response;
    }

    public ObjectNode listChildren(JsonNode request) {
        requireOrganization();
        String parentId = requireText(request, "ParentId");
        String childType = requireText(request, "ChildType");
        requireParent(parentId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode children = response.putArray("Children");
        if ("ACCOUNT".equals(childType)) {
            for (OrganizationAccount account : accounts.values()) {
                if (parentId.equals(account.getParentId())) {
                    ObjectNode child = children.addObject();
                    child.put("Id", account.getId());
                    child.put("Type", "ACCOUNT");
                }
            }
        } else if ("ORGANIZATIONAL_UNIT".equals(childType)) {
            for (OrganizationalUnit ou : ous.values()) {
                if (parentId.equals(ou.getParentId())) {
                    ObjectNode child = children.addObject();
                    child.put("Id", ou.getId());
                    child.put("Type", "ORGANIZATIONAL_UNIT");
                }
            }
        } else {
            throw new AwsException("InvalidInputException", "You specified an invalid ChildType.", 400);
        }
        return response;
    }

    public ObjectNode listPolicies(JsonNode request) {
        requireOrganization();
        ensureFullAwsAccessPolicy();
        String filter = requireText(request, "Filter");
        if (!POLICY_TYPES.contains(filter)) {
            throw new AwsException("InvalidInputException", "You specified an invalid value for Filter.", 400);
        }
        List<OrganizationPolicy> matches = new ArrayList<>();
        for (OrganizationPolicy policy : policies.values()) {
            if (filter.equals(policy.getType())) {
                matches.add(policy);
            }
        }
        matches.sort(Comparator.comparing(OrganizationPolicy::getId));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Policies");
        for (OrganizationPolicy policy : matches) {
            list.add(toPolicySummary(policy));
        }
        return response;
    }

    public ObjectNode describePolicy(JsonNode request) {
        requireOrganization();
        ensureFullAwsAccessPolicy();
        return toPolicyResponse(requireOrgPolicy(requireText(request, "PolicyId")));
    }

    public synchronized ObjectNode createPolicy(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        requireEnabledPolicyType(org, requireText(request, "Type"));
        ensureFullAwsAccessPolicy();
        String name = requireText(request, "Name");
        String type = requireText(request, "Type");
        String content = requireText(request, "Content");
        for (OrganizationPolicy existing : policies.values()) {
            if (type.equals(existing.getType()) && name.equals(existing.getName())) {
                throw new AwsException("DuplicatePolicyException",
                        "A policy with the specified name already exists.", 400);
            }
        }
        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setId("p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        policy.setType(type);
        policy.setName(name);
        policy.setDescription(textOr(request, "Description", ""));
        policy.setContent(content);
        policy.setAwsManaged(false);
        policy.setArn("arn:aws:organizations::" + org.getMasterAccountId()
                + ":policy/" + org.getId() + "/" + type.toLowerCase() + "/" + policy.getId());
        policy.setTags(readTags(request.get("Tags")));
        policies.put(policy.getId(), policy);
        return toPolicyResponse(policy);
    }

    public synchronized ObjectNode updatePolicy(JsonNode request) {
        requireOrganization();
        OrganizationPolicy policy = requireOrgPolicy(requireText(request, "PolicyId"));
        if (policy.isAwsManaged()) {
            throw new AwsException("PolicyInUseException", "You cannot modify an AWS managed policy.", 400);
        }
        if (request != null && request.hasNonNull("Name")) {
            policy.setName(request.get("Name").asText());
        }
        if (request != null && request.hasNonNull("Description")) {
            policy.setDescription(request.get("Description").asText(""));
        }
        if (request != null && request.hasNonNull("Content")) {
            policy.setContent(request.get("Content").asText());
        }
        policies.put(policy.getId(), policy);
        return toPolicyResponse(policy);
    }

    public synchronized ObjectNode deletePolicy(JsonNode request) {
        requireOrganization();
        OrganizationPolicy policy = requireOrgPolicy(requireText(request, "PolicyId"));
        if (policy.isAwsManaged()) {
            throw new AwsException("PolicyInUseException", "You cannot delete an AWS managed policy.", 400);
        }
        policies.remove(policy.getId());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listPoliciesForTarget(JsonNode request) {
        requireOrganization();
        ensureFullAwsAccessPolicy();
        String targetId = requireText(request, "TargetId");
        String filter = requireText(request, "Filter");
        requireTarget(targetId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Policies");
        for (OrganizationPolicy policy : policies.values()) {
            if (filter.equals(policy.getType()) && policy.getAttachedTargets().contains(targetId)) {
                list.add(toPolicySummary(policy));
            }
        }
        return response;
    }

    public ObjectNode listTargetsForPolicy(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        ensureFullAwsAccessPolicy();
        OrganizationPolicy policy = requireOrgPolicy(requireText(request, "PolicyId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode targets = response.putArray("Targets");
        for (String targetId : policy.getAttachedTargets()) {
            ObjectNode target = targets.addObject();
            target.put("TargetId", targetId);
            target.put("Arn", targetArn(org, targetId));
            target.put("Name", targetName(org, targetId));
            target.put("Type", targetType(targetId));
        }
        return response;
    }

    public ObjectNode attachPolicy(JsonNode request) {
        requireOrganization();
        OrganizationPolicy policy = requireOrgPolicy(requireText(request, "PolicyId"));
        String targetId = requireText(request, "TargetId");
        requireTarget(targetId);
        policy.getAttachedTargets().add(targetId);
        policies.put(policy.getId(), policy);
        return objectMapper.createObjectNode();
    }

    public ObjectNode detachPolicy(JsonNode request) {
        requireOrganization();
        OrganizationPolicy policy = requireOrgPolicy(requireText(request, "PolicyId"));
        policy.getAttachedTargets().remove(requireText(request, "TargetId"));
        policies.put(policy.getId(), policy);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeEffectivePolicy(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        requireEnabledPolicyType(org, requireText(request, "PolicyType"));
        throw new AwsException("EffectivePolicyNotFoundException",
                "There is no effective policy of the specified type for the target.", 404);
    }

    public ObjectNode listAccountsWithInvalidEffectivePolicy(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        requireEnabledPolicyType(org, requireText(request, "PolicyType"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Accounts");
        return response;
    }

    public ObjectNode listEffectivePolicyValidationErrors(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        requireText(request, "AccountId");
        requireEnabledPolicyType(org, requireText(request, "PolicyType"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("EffectivePolicyValidationErrors");
        return response;
    }

    public ObjectNode listHandshakesForAccount(JsonNode request) {
        return handshakeList(handshakes.values());
    }

    public ObjectNode listHandshakesForOrganization(JsonNode request) {
        requireOrganization();
        return handshakeList(handshakes.values());
    }

    public ObjectNode describeHandshake(JsonNode request) {
        return wrapHandshake(requireHandshake(requireText(request, "HandshakeId")));
    }

    public ObjectNode acceptHandshake(JsonNode request) {
        OrgHandshake handshake = requireHandshake(requireText(request, "HandshakeId"));
        handshake.setState("ACCEPTED");
        handshakes.put(handshake.getId(), handshake);
        return wrapHandshake(handshake);
    }

    public ObjectNode declineHandshake(JsonNode request) {
        OrgHandshake handshake = requireHandshake(requireText(request, "HandshakeId"));
        handshake.setState("DECLINED");
        handshakes.put(handshake.getId(), handshake);
        return wrapHandshake(handshake);
    }

    public ObjectNode cancelHandshake(JsonNode request) {
        OrgHandshake handshake = requireHandshake(requireText(request, "HandshakeId"));
        handshake.setState("CANCELED");
        handshakes.put(handshake.getId(), handshake);
        return wrapHandshake(handshake);
    }

    public synchronized ObjectNode inviteAccountToOrganization(JsonNode request) {
        OrganizationRecord org = requireOrganization();
        JsonNode target = request == null ? null : request.get("Target");
        if (target == null || !target.hasNonNull("Id") || !target.hasNonNull("Type")) {
            throw new AwsException("InvalidInputException", "Target is a required parameter.", 400);
        }
        String targetId = target.get("Id").asText();
        String targetType = target.get("Type").asText();
        if (!"ACCOUNT".equals(targetType) && !"EMAIL".equals(targetType)) {
            throw new AwsException("InvalidInputException", "You specified an invalid handshake target type.", 400);
        }
        if ("ACCOUNT".equals(targetType) && !ACCOUNT_ID.matcher(targetId).matches()) {
            throw new AwsException("InvalidInputException", "You specified an invalid account ID.", 400);
        }
        if ("EMAIL".equals(targetType) && !targetId.contains("@")) {
            throw new AwsException("InvalidInputException", "You specified an invalid email address.", 400);
        }
        long now = Instant.now().getEpochSecond();
        OrgHandshake handshake = new OrgHandshake();
        handshake.setId("h-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        handshake.setArn("arn:aws:organizations::" + org.getMasterAccountId()
                + ":handshake/" + org.getId() + "/invite/" + handshake.getId());
        handshake.setState("OPEN");
        handshake.setAction("INVITE");
        handshake.setRequestedTimestamp(now);
        handshake.setExpirationTimestamp(now + 14 * 24 * 3600L);
        handshake.setParties(List.of(
                Map.of("Id", targetId, "Type", targetType),
                Map.of("Id", org.getId(), "Type", "ORGANIZATION")));
        handshake.setResources(List.of(Map.of("Type", "ORGANIZATION", "Value", org.getId())));
        handshakes.put(handshake.getId(), handshake);
        return wrapHandshake(handshake);
    }

    private void ensureDefaultOrganization() {
        if (organizations.get(ORG_KEY).isEmpty()) {
            seedOrganization("ALL");
        }
    }

    private synchronized void seedOrganization(String featureSet) {
        String masterAccountId = accountId();
        OrganizationRecord org = new OrganizationRecord();
        org.setId(DEFAULT_ORG_ID);
        org.setArn("arn:aws:organizations::" + masterAccountId + ":organization/" + DEFAULT_ORG_ID);
        org.setFeatureSet(featureSet == null || featureSet.isBlank() ? "ALL" : featureSet);
        org.setMasterAccountId(masterAccountId);
        org.setMasterAccountArn(accountArn(org, masterAccountId));
        org.setMasterAccountEmail(DEFAULT_MASTER_EMAIL);
        org.setRootId(DEFAULT_ROOT_ID);
        org.setNextAccountSeq(1);
        organizations.put(ORG_KEY, org);

        OrganizationAccount master = new OrganizationAccount();
        master.setId(masterAccountId);
        master.setArn(accountArn(org, masterAccountId));
        master.setEmail(DEFAULT_MASTER_EMAIL);
        master.setName(DEFAULT_MASTER_NAME);
        master.setStatus("ACTIVE");
        master.setState("ACTIVE");
        master.setJoinedMethod("CREATED");
        master.setJoinedTimestamp(Instant.now().getEpochSecond());
        master.setParentId(DEFAULT_ROOT_ID);
        master.setParentType("ROOT");
        accounts.put(masterAccountId, master);
        ensureFullAwsAccessPolicy();
    }

    private OrganizationRecord requireOrganization() {
        return organizations.get(ORG_KEY).orElseThrow(OrganizationsService::notInUse);
    }

    private Map<String, String> tagsFor(String resourceId) {
        OrganizationPolicy policy = policies.get(resourceId);
        if (policy != null) {
            return policy.getTags();
        }
        OrganizationAccount account = accounts.get(resourceId).orElse(null);
        if (account != null) {
            return account.getTags();
        }
        OrganizationalUnit ou = ous.get(resourceId).orElse(null);
        if (ou != null) {
            return ou.getTags();
        }
        OrganizationRecord org = requireOrganization();
        if (resourceId.equals(org.getRootId())) {
            return org.getRootTags();
        }
        throw new AwsException("TargetNotFoundException", "You specified a target that doesn't exist.", 404);
    }

    private void storeTags(OrganizationRecord org, String resourceId, Map<String, String> tags) {
        OrganizationPolicy policy = policies.get(resourceId);
        if (policy != null) {
            policy.setTags(tags);
            policies.put(policy.getId(), policy);
            return;
        }
        OrganizationAccount account = accounts.get(resourceId).orElse(null);
        if (account != null) {
            account.setTags(tags);
            accounts.put(account.getId(), account);
            return;
        }
        OrganizationalUnit ou = ous.get(resourceId).orElse(null);
        if (ou != null) {
            ou.setTags(tags);
            ous.put(ou.getId(), ou);
            return;
        }
        if (resourceId.equals(org.getRootId())) {
            org.setRootTags(tags);
            organizations.put(ORG_KEY, org);
            return;
        }
        throw new AwsException("TargetNotFoundException", "You specified a target that doesn't exist.", 404);
    }

    private void requireParent(String parentId) {
        OrganizationRecord org = requireOrganization();
        if (parentId.equals(org.getRootId()) || ous.get(parentId).isPresent()) {
            return;
        }
        throw new AwsException("ParentNotFoundException", "You specified a parent that doesn't exist.", 400);
    }

    private void requireTarget(String targetId) {
        OrganizationRecord org = requireOrganization();
        if (targetId.equals(org.getRootId())
                || ous.get(targetId).isPresent()
                || accounts.get(targetId).isPresent()) {
            return;
        }
        throw new AwsException("TargetNotFoundException", "You specified a target that doesn't exist.", 404);
    }

    private void requireEnabledPolicyType(OrganizationRecord org, String policyType) {
        if (!POLICY_TYPES.contains(policyType)) {
            throw new AwsException("InvalidInputException", "You specified an invalid policy type.", 400);
        }
        if ("ALL".equals(org.getFeatureSet()) && "SERVICE_CONTROL_POLICY".equals(policyType)) {
            return;
        }
        throw new AwsException("ConstraintViolationException",
                "The specified policy type is not enabled for this organization.", 409);
    }

    private OrganizationPolicy requireOrgPolicy(String policyId) {
        OrganizationPolicy policy = policies.get(policyId);
        if (policy == null) {
            throw new AwsException("PolicyNotFoundException", "You specified a policy that doesn't exist.", 404);
        }
        return policy;
    }

    private OrgHandshake requireHandshake(String handshakeId) {
        if (!HANDSHAKE_ID.matcher(handshakeId).matches()) {
            throw new AwsException("InvalidInputException", "You specified an invalid handshake ID.", 400);
        }
        OrgHandshake handshake = handshakes.get(handshakeId);
        if (handshake == null) {
            throw new AwsException("HandshakeNotFoundException", "You specified a handshake that doesn't exist.", 404);
        }
        return handshake;
    }

    private ObjectNode handshakeList(Iterable<OrgHandshake> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Handshakes");
        for (OrgHandshake handshake : items) {
            list.add(toHandshakeNode(handshake));
        }
        return response;
    }

    private ObjectNode wrapHandshake(OrgHandshake handshake) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Handshake", toHandshakeNode(handshake));
        return response;
    }

    private ObjectNode toHandshakeNode(OrgHandshake handshake) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", handshake.getId());
        node.put("Arn", handshake.getArn());
        node.put("State", handshake.getState());
        node.put("Action", handshake.getAction());
        node.put("RequestedTimestamp", handshake.getRequestedTimestamp());
        node.put("ExpirationTimestamp", handshake.getExpirationTimestamp());
        ArrayNode parties = node.putArray("Parties");
        for (Map<String, String> party : handshake.getParties()) {
            ObjectNode p = parties.addObject();
            p.put("Id", party.get("Id"));
            p.put("Type", party.get("Type"));
        }
        ArrayNode resources = node.putArray("Resources");
        for (Map<String, String> resource : handshake.getResources()) {
            ObjectNode r = resources.addObject();
            if (resource.get("Type") != null) {
                r.put("Type", resource.get("Type"));
            }
            if (resource.get("Value") != null) {
                r.put("Value", resource.get("Value"));
            }
        }
        return node;
    }

    private ObjectNode toPolicySummary(OrganizationPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", policy.getId());
        node.put("Arn", policy.getArn());
        node.put("Name", policy.getName());
        if (policy.getDescription() != null) {
            node.put("Description", policy.getDescription());
        }
        node.put("Type", policy.getType());
        node.put("AwsManaged", policy.isAwsManaged());
        return node;
    }

    private ObjectNode toPolicyResponse(OrganizationPolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("Policy");
        node.set("PolicySummary", toPolicySummary(policy));
        node.put("Content", policy.getContent());
        return response;
    }

    private String targetArn(OrganizationRecord org, String targetId) {
        if (targetId.equals(org.getRootId())) {
            return rootArn(org);
        }
        OrganizationalUnit ou = ous.get(targetId).orElse(null);
        if (ou != null) {
            return ou.getArn();
        }
        OrganizationAccount account = accounts.get(targetId).orElse(null);
        if (account != null) {
            return account.getArn();
        }
        return accountArn(org, targetId);
    }

    private String targetName(OrganizationRecord org, String targetId) {
        if (targetId.equals(org.getRootId())) {
            return "Root";
        }
        OrganizationalUnit ou = ous.get(targetId).orElse(null);
        if (ou != null) {
            return ou.getName();
        }
        OrganizationAccount account = accounts.get(targetId).orElse(null);
        return account != null && account.getName() != null ? account.getName() : targetId;
    }

    private String targetType(String targetId) {
        if (targetId.startsWith("r-")) {
            return "ROOT";
        }
        if (targetId.startsWith("ou-")) {
            return "ORGANIZATIONAL_UNIT";
        }
        return "ACCOUNT";
    }

    private void ensureFullAwsAccessPolicy() {
        organizations.get(ORG_KEY).ifPresent(this::seedFullAwsAccess);
    }

    private void seedFullAwsAccess(OrganizationRecord org) {
        if (policies.containsKey(FULL_AWS_ACCESS_ID) || !"ALL".equals(org.getFeatureSet())) {
            return;
        }
        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setId(FULL_AWS_ACCESS_ID);
        policy.setArn("arn:aws:organizations::aws:policy/service_control_policy/p-FullAWSAccess");
        policy.setName("FullAWSAccess");
        policy.setDescription("Allows access to every operation");
        policy.setType("SERVICE_CONTROL_POLICY");
        policy.setContent(FULL_AWS_ACCESS_CONTENT);
        policy.setAwsManaged(true);
        policy.getAttachedTargets().add(org.getRootId());
        policies.put(FULL_AWS_ACCESS_ID, policy);
    }

    private String nextAccountId(OrganizationRecord org) {
        int seq = org.getNextAccountSeq();
        String accountId;
        do {
            accountId = String.format("%012d", seq++);
        } while (accounts.get(accountId).isPresent() || accountId.equals(org.getMasterAccountId()));
        org.setNextAccountSeq(seq);
        organizations.put(ORG_KEY, org);
        return accountId;
    }

    private ObjectNode toAccountNode(OrganizationRecord org, OrganizationAccount account) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", account.getId());
        node.put("Arn", account.getArn() != null ? account.getArn() : accountArn(org, account.getId()));
        if (account.getEmail() != null) {
            node.put("Email", account.getEmail());
        }
        if (account.getName() != null) {
            node.put("Name", account.getName());
        }
        if (account.getStatus() != null) {
            node.put("Status", account.getStatus());
        }
        if (account.getState() != null) {
            node.put("State", account.getState());
        }
        if (account.getJoinedMethod() != null) {
            node.put("JoinedMethod", account.getJoinedMethod());
        }
        node.put("JoinedTimestamp", account.getJoinedTimestamp());
        return node;
    }

    private ObjectNode toOuNode(OrganizationalUnit ou) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", ou.getId());
        node.put("Arn", ou.getArn());
        node.put("Name", ou.getName());
        return node;
    }

    private ObjectNode toOrganizationNode(OrganizationRecord org) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", org.getId());
        node.put("Arn", org.getArn());
        node.put("FeatureSet", org.getFeatureSet());
        node.put("MasterAccountId", org.getMasterAccountId());
        node.put("MasterAccountArn", org.getMasterAccountArn());
        node.put("MasterAccountEmail", org.getMasterAccountEmail());
        ArrayNode policyTypes = node.putArray("AvailablePolicyTypes");
        if ("ALL".equals(org.getFeatureSet())) {
            ObjectNode scp = policyTypes.addObject();
            scp.put("Type", "SERVICE_CONTROL_POLICY");
            scp.put("Status", "ENABLED");
        }
        return node;
    }

    private ObjectNode wrapCreateStatus(CreateAccountRequest status) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("CreateAccountStatus");
        node.put("Id", status.getId());
        if (status.getAccountName() != null) {
            node.put("AccountName", status.getAccountName());
        }
        node.put("State", status.getState());
        node.put("RequestedTimestamp", status.getRequestedTimestamp());
        if (status.getCompletedTimestamp() > 0) {
            node.put("CompletedTimestamp", status.getCompletedTimestamp());
        }
        if (status.getAccountId() != null) {
            node.put("AccountId", status.getAccountId());
        }
        if (status.getFailureReason() != null) {
            node.put("FailureReason", status.getFailureReason());
        }
        return response;
    }

    private static String accountArn(OrganizationRecord org, String accountId) {
        return "arn:aws:organizations::" + org.getMasterAccountId()
                + ":account/" + org.getId() + "/" + accountId;
    }

    private static String rootArn(OrganizationRecord org) {
        return "arn:aws:organizations::" + org.getMasterAccountId()
                + ":root/" + org.getId() + "/" + org.getRootId();
    }

    private static String delegatedKey(String accountId, String principal) {
        return accountId + "|" + principal;
    }

    private static void writeTags(ArrayNode array, Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("Key", key);
            tag.put("Value", value != null ? value : "");
        });
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            if (tag != null && tag.hasNonNull("Key")) {
                tags.put(tag.get("Key").asText(), tag.path("Value").asText(""));
            }
        }
        return tags;
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            throw new AwsException("InvalidInputException", field + " is a required parameter.", 400);
        }
        String value = request.get(field).asText();
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is a required parameter.", 400);
        }
        if ("AccountId".equals(field) && !ACCOUNT_ID.matcher(value).matches()) {
            throw new AwsException("InvalidInputException", "You specified an invalid account ID.", 400);
        }
        return value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        if (request == null || !request.hasNonNull(field)) {
            return fallback;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? fallback : value;
    }

    private String requirePolicyContent(JsonNode request) {
        if (request == null || !request.hasNonNull("Content")) {
            throw new AwsException("InvalidInputException", "Content is a required parameter.", 400);
        }
        String content = request.get("Content").asText();
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidInputException", "Content is a required parameter.", 400);
        }
        try {
            objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidInputException", "The provided content is not valid JSON.", 400);
        }
        return content;
    }

    private ObjectNode toResourcePolicyResponse(OrganizationResourcePolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode resourcePolicy = response.putObject("ResourcePolicy");
        ObjectNode summary = resourcePolicy.putObject("ResourcePolicySummary");
        summary.put("Id", policy.getId());
        summary.put("Arn", policy.getArn());
        resourcePolicy.put("Content", policy.getContent());
        return response;
    }

    private static AwsException policyNotFound() {
        return new AwsException("ResourcePolicyNotFoundException",
                "A resource policy was not found in this organization.", 404);
    }

    private static AwsException ouNotFound() {
        return new AwsException("OrganizationalUnitNotFoundException",
                "You specified an organizational unit that doesn't exist.", 400);
    }

    private static AwsException notInUse() {
        return new AwsException("AWSOrganizationsNotInUseException",
                "Your account is not a member of an organization.", 404);
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }
}
