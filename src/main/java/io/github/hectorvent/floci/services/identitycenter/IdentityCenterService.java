package io.github.hectorvent.floci.services.identitycenter;

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
import io.github.hectorvent.floci.services.identitycenter.model.AssignmentOperation;
import io.github.hectorvent.floci.services.identitycenter.model.IdentityStoreGroup;
import io.github.hectorvent.floci.services.identitycenter.model.SsoAccountAssignment;
import io.github.hectorvent.floci.services.identitycenter.model.SsoInstance;
import io.github.hectorvent.floci.services.identitycenter.model.SsoPermissionSet;
import io.github.hectorvent.floci.services.identitystore.IdentityStoreService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IAM Identity Center (SSO Admin + Identity Store). A default organization
 * instance is seeded on first use so Alchemy's {@code resolveInstance()} finds
 * exactly one ACTIVE instance, matching a management-account SSO directory.
 */
@ApplicationScoped
public class IdentityCenterService implements Resettable {

    static final String SERVICE = "identitycenter";
    static final String SSO_TARGET_PREFIX = "SWBExternalService.";
    static final String IDENTITY_STORE_TARGET_PREFIX = "AWSIdentityStore.";
    static final String DEFAULT_INSTANCE_ID = "ssoins-floci00000000";
    static final String DEFAULT_INSTANCE_ARN = "arn:aws:sso:::instance/" + DEFAULT_INSTANCE_ID;
    static final String DEFAULT_STORE_ID = "d-floci00000000";
    static final String DEFAULT_IDENTITY_STORE_ID = DEFAULT_STORE_ID;

    private final StorageBackend<String, SsoInstance> instances;
    private final StorageBackend<String, SsoPermissionSet> permissionSets;
    private final StorageBackend<String, SsoAccountAssignment> assignments;
    private final StorageBackend<String, IdentityStoreGroup> groups;
    private final StorageBackend<String, AssignmentOperation> operations;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final IdentityStoreService identityStore;

    @Inject
    public IdentityCenterService(StorageFactory factory, ObjectMapper objectMapper, RegionResolver regionResolver,
                                 IdentityStoreService identityStore) {
        this.instances = factory.create(SERVICE, "identitycenter-instances.json",
                new TypeReference<Map<String, SsoInstance>>() {
                });
        this.permissionSets = factory.create(SERVICE, "identitycenter-permission-sets.json",
                new TypeReference<Map<String, SsoPermissionSet>>() {
                });
        this.assignments = factory.create(SERVICE, "identitycenter-assignments.json",
                new TypeReference<Map<String, SsoAccountAssignment>>() {
                });
        this.groups = factory.create(SERVICE, "identitycenter-groups.json",
                new TypeReference<Map<String, IdentityStoreGroup>>() {
                });
        this.operations = factory.create(SERVICE, "identitycenter-operations.json",
                new TypeReference<Map<String, AssignmentOperation>>() {
                });
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.identityStore = identityStore;
        ensureDefaultInstance();
    }

    IdentityCenterService(StorageBackend<String, SsoInstance> instances,
                          StorageBackend<String, SsoPermissionSet> permissionSets,
                          StorageBackend<String, SsoAccountAssignment> assignments,
                          StorageBackend<String, IdentityStoreGroup> groups,
                          StorageBackend<String, AssignmentOperation> operations,
                          ObjectMapper objectMapper,
                          RegionResolver regionResolver) {
        this.instances = instances;
        this.permissionSets = permissionSets;
        this.assignments = assignments;
        this.groups = groups;
        this.operations = operations;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.identityStore = null;
    }

    @Override
    public void clear() {
        instances.clear();
        permissionSets.clear();
        assignments.clear();
        groups.clear();
        operations.clear();
        ensureDefaultInstance();
    }

    public ObjectNode listInstances() {
        ensureDefaultInstance();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Instances");
        for (SsoInstance instance : instances.values()) {
            list.add(toInstanceNode(instance));
        }
        return response;
    }

    public ObjectNode createInstance(JsonNode request) {
        if (!instances.values().isEmpty()) {
            throw new AwsException(
                    "ConflictException",
                    "An IAM Identity Center instance already exists in this account.",
                    409);
        }
        String name = textOr(request, "Name", "Floci Identity Center");
        SsoInstance instance = newInstance(
                "ssoins-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                name);
        instances.put(instance.getInstanceArn(), instance);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("InstanceArn", instance.getInstanceArn());
        return response;
    }

    public ObjectNode deleteInstance(JsonNode request) {
        String instanceArn = requireText(request, "InstanceArn");
        if (instances.get(instanceArn).isEmpty()) {
            throw notFound("Identity Center instance " + instanceArn + " was not found.");
        }
        instances.delete(instanceArn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeInstance(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Instance", toInstanceNode(instance));
        return response;
    }

    public ObjectNode createPermissionSet(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String name = requireText(request, "Name");
        for (SsoPermissionSet existing : permissionSets.values()) {
            if (instance.getInstanceArn().equals(existing.getInstanceArn()) && name.equals(existing.getName())) {
                ObjectNode response = objectMapper.createObjectNode();
                response.set("PermissionSet", toPermissionSetNode(existing));
                return response;
            }
        }
        String instanceId = instanceIdFromArn(instance.getInstanceArn());
        String permissionSetArn = "arn:aws:sso:::permissionSet/" + instanceId + "/ps-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        SsoPermissionSet permissionSet = new SsoPermissionSet();
        permissionSet.setInstanceArn(instance.getInstanceArn());
        permissionSet.setPermissionSetArn(permissionSetArn);
        permissionSet.setName(name);
        permissionSet.setDescription(textOrNull(request, "Description"));
        permissionSet.setSessionDuration(textOr(request, "SessionDuration", "PT1H"));
        permissionSet.setRelayState(textOrNull(request, "RelayState"));
        permissionSet.setCreatedDate(now());
        permissionSets.put(permissionSetArn, permissionSet);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PermissionSet", toPermissionSetNode(permissionSet));
        return response;
    }

    public ObjectNode describePermissionSet(JsonNode request) {
        requireInstance(requireText(request, "InstanceArn"));
        SsoPermissionSet permissionSet = requirePermissionSet(requireText(request, "PermissionSetArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PermissionSet", toPermissionSetNode(permissionSet));
        return response;
    }

    public ObjectNode listPermissionSets(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("PermissionSets");
        for (SsoPermissionSet permissionSet : permissionSets.values()) {
            if (instance.getInstanceArn().equals(permissionSet.getInstanceArn())) {
                list.add(permissionSet.getPermissionSetArn());
            }
        }
        return response;
    }

    public ObjectNode updatePermissionSet(JsonNode request) {
        requireInstance(requireText(request, "InstanceArn"));
        SsoPermissionSet permissionSet = requirePermissionSet(requireText(request, "PermissionSetArn"));
        if (request.has("Description")) {
            permissionSet.setDescription(textOrNull(request, "Description"));
        }
        if (request.has("SessionDuration")) {
            permissionSet.setSessionDuration(textOrNull(request, "SessionDuration"));
        }
        if (request.has("RelayState")) {
            permissionSet.setRelayState(textOrNull(request, "RelayState"));
        }
        permissionSets.put(permissionSet.getPermissionSetArn(), permissionSet);
        return objectMapper.createObjectNode();
    }

    public ObjectNode deletePermissionSet(JsonNode request) {
        requireInstance(requireText(request, "InstanceArn"));
        String permissionSetArn = requireText(request, "PermissionSetArn");
        requirePermissionSet(permissionSetArn);
        List<String> toRemove = new ArrayList<>();
        for (SsoAccountAssignment assignment : assignments.values()) {
            if (permissionSetArn.equals(assignment.getPermissionSetArn())) {
                toRemove.add(assignmentKey(assignment));
            }
        }
        for (String key : toRemove) {
            assignments.delete(key);
        }
        permissionSets.delete(permissionSetArn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode createAccountAssignment(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String permissionSetArn = requireText(request, "PermissionSetArn");
        requirePermissionSet(permissionSetArn);
        String principalType = requireText(request, "PrincipalType");
        String principalId = requireText(request, "PrincipalId");
        String targetId = requireText(request, "TargetId");
        String targetType = textOr(request, "TargetType", "AWS_ACCOUNT");
        SsoAccountAssignment assignment = findAssignment(
                instance.getInstanceArn(), permissionSetArn, principalType, principalId, targetId);
        if (assignment == null) {
            assignment = new SsoAccountAssignment();
            assignment.setInstanceArn(instance.getInstanceArn());
            assignment.setPermissionSetArn(permissionSetArn);
            assignment.setPrincipalType(principalType);
            assignment.setPrincipalId(principalId);
            assignment.setAccountId(targetId);
            assignment.setTargetType(targetType);
            assignments.put(assignmentKey(assignment), assignment);
        }
        AssignmentOperation operation = storeOperation("CREATE", assignment);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", toOperationNode(operation));
        return response;
    }

    public ObjectNode deleteAccountAssignment(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String permissionSetArn = requireText(request, "PermissionSetArn");
        String principalType = requireText(request, "PrincipalType");
        String principalId = requireText(request, "PrincipalId");
        String targetId = requireText(request, "TargetId");
        String targetType = textOr(request, "TargetType", "AWS_ACCOUNT");
        SsoAccountAssignment assignment = findAssignment(
                instance.getInstanceArn(), permissionSetArn, principalType, principalId, targetId);
        if (assignment != null) {
            assignments.delete(assignmentKey(assignment));
        } else {
            assignment = new SsoAccountAssignment();
            assignment.setInstanceArn(instance.getInstanceArn());
            assignment.setPermissionSetArn(permissionSetArn);
            assignment.setPrincipalType(principalType);
            assignment.setPrincipalId(principalId);
            assignment.setAccountId(targetId);
            assignment.setTargetType(targetType);
        }
        AssignmentOperation operation = storeOperation("DELETE", assignment);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AccountAssignmentDeletionStatus", toOperationNode(operation));
        return response;
    }

    public ObjectNode describeAccountAssignmentCreationStatus(JsonNode request) {
        requireInstance(requireText(request, "InstanceArn"));
        AssignmentOperation operation = requireOperation(
                requireText(request, "AccountAssignmentCreationRequestId"), "CREATE");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", toOperationNode(operation));
        return response;
    }

    public ObjectNode describeAccountAssignmentDeletionStatus(JsonNode request) {
        requireInstance(requireText(request, "InstanceArn"));
        AssignmentOperation operation = requireOperation(
                requireText(request, "AccountAssignmentDeletionRequestId"), "DELETE");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AccountAssignmentDeletionStatus", toOperationNode(operation));
        return response;
    }

    public ObjectNode listAccountAssignments(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String permissionSetArn = requireText(request, "PermissionSetArn");
        String accountId = requireText(request, "AccountId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AccountAssignments");
        for (SsoAccountAssignment assignment : assignments.values()) {
            if (instance.getInstanceArn().equals(assignment.getInstanceArn())
                    && permissionSetArn.equals(assignment.getPermissionSetArn())
                    && accountId.equals(assignment.getAccountId())) {
                ObjectNode node = list.addObject();
                node.put("AccountId", assignment.getAccountId());
                node.put("PermissionSetArn", assignment.getPermissionSetArn());
                node.put("PrincipalType", assignment.getPrincipalType());
                node.put("PrincipalId", assignment.getPrincipalId());
            }
        }
        return response;
    }

    public ObjectNode listAccountAssignmentsForPrincipal(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String principalId = requireText(request, "PrincipalId");
        String principalType = requireText(request, "PrincipalType");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AccountAssignments");
        for (SsoAccountAssignment assignment : assignments.values()) {
            if (instance.getInstanceArn().equals(assignment.getInstanceArn())
                    && principalId.equals(assignment.getPrincipalId())
                    && principalType.equalsIgnoreCase(assignment.getPrincipalType())) {
                ObjectNode node = list.addObject();
                node.put("AccountId", assignment.getAccountId());
                node.put("PermissionSetArn", assignment.getPermissionSetArn());
                node.put("PrincipalId", assignment.getPrincipalId());
                node.put("PrincipalType", assignment.getPrincipalType());
            }
        }
        return response;
    }

    public ObjectNode listAccountsForProvisionedPermissionSet(JsonNode request) {
        SsoInstance instance = requireInstance(requireText(request, "InstanceArn"));
        String permissionSetArn = requireText(request, "PermissionSetArn");
        requirePermissionSet(permissionSetArn);
        List<String> accountIds = new ArrayList<>();
        for (SsoAccountAssignment assignment : assignments.values()) {
            if (instance.getInstanceArn().equals(assignment.getInstanceArn())
                    && permissionSetArn.equals(assignment.getPermissionSetArn())
                    && !accountIds.contains(assignment.getAccountId())) {
                accountIds.add(assignment.getAccountId());
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AccountIds");
        for (String accountId : accountIds) {
            list.add(accountId);
        }
        return response;
    }

    public ObjectNode createGroup(JsonNode request) {
        SsoInstance instance = requireIdentityStore(requireText(request, "IdentityStoreId"));
        String displayName = requireText(request, "DisplayName");
        for (IdentityStoreGroup existing : groups.values()) {
            if (instance.getIdentityStoreId().equals(existing.getIdentityStoreId())
                    && displayName.equals(existing.getDisplayName())) {
                return groupIdResponse(existing);
            }
        }
        long now = now();
        IdentityStoreGroup group = new IdentityStoreGroup();
        group.setIdentityStoreId(instance.getIdentityStoreId());
        group.setGroupId(UUID.randomUUID().toString());
        group.setDisplayName(displayName);
        group.setDescription(textOrNull(request, "Description"));
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groups.put(group.getGroupId(), group);
        return groupIdResponse(group);
    }

    public ObjectNode describeGroup(JsonNode request) {
        requireIdentityStore(requireText(request, "IdentityStoreId"));
        IdentityStoreGroup group = requireGroup(requireText(request, "GroupId"));
        return toGroupNode(group);
    }

    public ObjectNode listGroups(JsonNode request) {
        SsoInstance instance = requireIdentityStore(requireText(request, "IdentityStoreId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Groups");
        for (IdentityStoreGroup group : groups.values()) {
            if (instance.getIdentityStoreId().equals(group.getIdentityStoreId())) {
                list.add(toGroupNode(group));
            }
        }
        return response;
    }

    public ObjectNode deleteGroup(JsonNode request) {
        requireIdentityStore(requireText(request, "IdentityStoreId"));
        String groupId = requireText(request, "GroupId");
        requireGroup(groupId);
        List<String> toRemove = new ArrayList<>();
        for (SsoAccountAssignment assignment : assignments.values()) {
            if (groupId.equals(assignment.getPrincipalId()) && "GROUP".equals(assignment.getPrincipalType())) {
                toRemove.add(assignmentKey(assignment));
            }
        }
        for (String key : toRemove) {
            assignments.delete(key);
        }
        groups.delete(groupId);
        return objectMapper.createObjectNode();
    }

    private synchronized SsoInstance ensureDefaultInstance() {
        if (!instances.values().isEmpty()) {
            return instances.values().iterator().next();
        }
        SsoInstance instance = newInstance(DEFAULT_INSTANCE_ID, DEFAULT_IDENTITY_STORE_ID, "Floci");
        instances.put(instance.getInstanceArn(), instance);
        return instance;
    }

    private SsoInstance newInstance(String instanceId, String identityStoreId, String name) {
        SsoInstance instance = new SsoInstance();
        instance.setInstanceArn("arn:aws:sso:::instance/" + instanceId);
        instance.setIdentityStoreId(identityStoreId);
        instance.setOwnerAccountId(regionResolver != null ? regionResolver.getAccountId() : "000000000000");
        instance.setName(name);
        instance.setStatus("ACTIVE");
        instance.setCreatedDate(now());
        if (identityStore != null && identityStoreId != null) {
            identityStore.ensureStore(identityStoreId);
        }
        return instance;
    }

    private SsoInstance requireInstance(String instanceArn) {
        return instances.get(instanceArn).orElseThrow(() -> notFound(
                "Identity Center instance " + instanceArn + " was not found."));
    }

    private SsoInstance requireIdentityStore(String identityStoreId) {
        for (SsoInstance instance : instances.values()) {
            if (identityStoreId.equals(instance.getIdentityStoreId())) {
                return instance;
            }
        }
        throw notFound("Identity store " + identityStoreId + " was not found.");
    }

    private SsoPermissionSet requirePermissionSet(String permissionSetArn) {
        return permissionSets.get(permissionSetArn).orElseThrow(() -> notFound(
                "Permission set " + permissionSetArn + " was not found."));
    }

    private IdentityStoreGroup requireGroup(String groupId) {
        return groups.get(groupId).orElseThrow(() -> notFound("Group " + groupId + " was not found."));
    }

    private AssignmentOperation requireOperation(String requestId, String kind) {
        AssignmentOperation operation = operations.get(requestId).orElseThrow(() -> notFound(
                "Request " + requestId + " was not found."));
        if (kind != null && !kind.equals(operation.getKind())) {
            throw notFound("Request " + requestId + " was not found.");
        }
        return operation;
    }

    private SsoAccountAssignment findAssignment(String instanceArn, String permissionSetArn,
                                                String principalType, String principalId, String accountId) {
        return assignments.get(assignmentKey(instanceArn, permissionSetArn, principalType, principalId, accountId))
                .orElse(null);
    }

    private AssignmentOperation storeOperation(String kind, SsoAccountAssignment assignment) {
        AssignmentOperation operation = new AssignmentOperation();
        operation.setRequestId(UUID.randomUUID().toString());
        operation.setStatus("SUCCEEDED");
        operation.setKind(kind);
        operation.setInstanceArn(assignment.getInstanceArn());
        operation.setPermissionSetArn(assignment.getPermissionSetArn());
        operation.setPrincipalType(assignment.getPrincipalType());
        operation.setPrincipalId(assignment.getPrincipalId());
        operation.setTargetId(assignment.getAccountId());
        operation.setTargetType(assignment.getTargetType());
        operation.setCreatedDate(now());
        operations.put(operation.getRequestId(), operation);
        return operation;
    }

    private ObjectNode toInstanceNode(SsoInstance instance) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("InstanceArn", instance.getInstanceArn());
        node.put("IdentityStoreId", instance.getIdentityStoreId());
        if (instance.getOwnerAccountId() != null) {
            node.put("OwnerAccountId", instance.getOwnerAccountId());
        }
        if (instance.getName() != null) {
            node.put("Name", instance.getName());
        }
        node.put("CreatedDate", instance.getCreatedDate());
        node.put("Status", instance.getStatus());
        return node;
    }

    private ObjectNode toPermissionSetNode(SsoPermissionSet permissionSet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", permissionSet.getName());
        node.put("PermissionSetArn", permissionSet.getPermissionSetArn());
        if (permissionSet.getDescription() != null) {
            node.put("Description", permissionSet.getDescription());
        }
        node.put("CreatedDate", permissionSet.getCreatedDate());
        if (permissionSet.getSessionDuration() != null) {
            node.put("SessionDuration", permissionSet.getSessionDuration());
        }
        if (permissionSet.getRelayState() != null) {
            node.put("RelayState", permissionSet.getRelayState());
        }
        return node;
    }

    private ObjectNode toOperationNode(AssignmentOperation operation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Status", operation.getStatus());
        node.put("RequestId", operation.getRequestId());
        node.put("TargetId", operation.getTargetId());
        node.put("TargetType", operation.getTargetType());
        node.put("PermissionSetArn", operation.getPermissionSetArn());
        node.put("PrincipalType", operation.getPrincipalType());
        node.put("PrincipalId", operation.getPrincipalId());
        node.put("CreatedDate", operation.getCreatedDate());
        return node;
    }

    private ObjectNode toGroupNode(IdentityStoreGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GroupId", group.getGroupId());
        if (group.getDisplayName() != null) {
            node.put("DisplayName", group.getDisplayName());
        }
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        node.put("CreatedAt", group.getCreatedAt());
        node.put("UpdatedAt", group.getUpdatedAt());
        node.put("IdentityStoreId", group.getIdentityStoreId());
        return node;
    }

    private ObjectNode groupIdResponse(IdentityStoreGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GroupId", group.getGroupId());
        response.put("IdentityStoreId", group.getIdentityStoreId());
        return response;
    }

    private static String assignmentKey(SsoAccountAssignment assignment) {
        return assignmentKey(assignment.getInstanceArn(), assignment.getPermissionSetArn(),
                assignment.getPrincipalType(), assignment.getPrincipalId(), assignment.getAccountId());
    }

    private static String assignmentKey(String instanceArn, String permissionSetArn,
                                        String principalType, String principalId, String accountId) {
        return instanceArn + "|" + permissionSetArn + "|" + principalType + "|" + principalId + "|" + accountId;
    }

    private static String instanceIdFromArn(String instanceArn) {
        int slash = instanceArn.lastIndexOf('/');
        return slash >= 0 ? instanceArn.substring(slash + 1) : instanceArn;
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String text = textOrNull(request, field);
        return text == null ? fallback : text;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
