package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IAM Identity Center Identity Store JSON 1.1 ({@code AWSIdentityStore.*}).
 * Stores exist only after an SSO Admin instance is created.
 */
@ApplicationScoped
public class IdentityStoreService implements Resettable {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Store> stores = new ConcurrentHashMap<>();

    @Inject
    public IdentityStoreService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ensureStore("d-floci00000000");
    }

    @Override
    public void clear() {
        stores.clear();
        ensureStore("d-floci00000000");
    }

    public String createStore() {
        return ensureStore("d-" + hex(10));
    }

    public String ensureStore(String identityStoreId) {
        stores.computeIfAbsent(identityStoreId, Store::new);
        return identityStoreId;
    }

    public void deleteStore(String identityStoreId) {
        if (identityStoreId != null) {
            stores.remove(identityStoreId);
        }
    }

    public ObjectNode createUser(JsonNode request) {
        Store store = requireStore(request);
        String userName = optionalText(request, "UserName");
        if (userName != null && findUserByName(store, userName) != null) {
            throw conflict("A unique constraint was violated for userName.");
        }
        User user = new User();
        user.userId = UUID.randomUUID().toString();
        user.userName = userName;
        user.displayName = optionalText(request, "DisplayName");
        user.name = copyObject(request.get("Name"));
        user.emails = copyArray(request.get("Emails"));
        long now = nowSeconds();
        user.createdAt = now;
        user.updatedAt = now;
        store.users.put(user.userId, user);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UserId", user.userId);
        response.put("IdentityStoreId", store.id);
        return response;
    }

    public ObjectNode describeUser(JsonNode request) {
        Store store = requireStore(request);
        User user = requireUser(store, requireText(request, "UserId"));
        return toUserNode(store.id, user);
    }

    public ObjectNode updateUser(JsonNode request) {
        Store store = requireStore(request);
        User user = requireUser(store, requireText(request, "UserId"));
        applyOperations(user, request.get("Operations"));
        user.updatedAt = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteUser(JsonNode request) {
        Store store = requireStore(request);
        User user = requireUser(store, requireText(request, "UserId"));
        store.users.remove(user.userId);
        store.memberships.values().removeIf(membership -> user.userId.equals(membership.userId));
        return objectMapper.createObjectNode();
    }

    public ObjectNode getUserId(JsonNode request) {
        Store store = requireStore(request);
        String userName = uniqueAttribute(request, "userName");
        User user = userName == null ? null : findUserByName(store, userName);
        if (user == null) {
            throw notFound("USER", userName == null ? "unknown" : userName);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IdentityStoreId", store.id);
        response.put("UserId", user.userId);
        return response;
    }

    public ObjectNode listUsers(JsonNode request) {
        Store store = requireStore(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode users = response.putArray("Users");
        for (User user : store.users.values()) {
            users.add(toUserNode(store.id, user));
        }
        return response;
    }

    public ObjectNode createGroup(JsonNode request) {
        Store store = requireStore(request);
        String displayName = optionalText(request, "DisplayName");
        if (displayName != null && findGroupByDisplayName(store, displayName) != null) {
            throw conflict("A unique constraint was violated for displayName.");
        }
        Group group = new Group();
        group.groupId = UUID.randomUUID().toString();
        group.displayName = displayName;
        group.description = optionalText(request, "Description");
        long now = nowSeconds();
        group.createdAt = now;
        group.updatedAt = now;
        store.groups.put(group.groupId, group);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GroupId", group.groupId);
        response.put("IdentityStoreId", store.id);
        return response;
    }

    public ObjectNode describeGroup(JsonNode request) {
        Store store = requireStore(request);
        Group group = requireGroup(store, requireText(request, "GroupId"));
        return toGroupNode(store.id, group);
    }

    public ObjectNode updateGroup(JsonNode request) {
        Store store = requireStore(request);
        Group group = requireGroup(store, requireText(request, "GroupId"));
        JsonNode operations = request.get("Operations");
        if (operations != null && operations.isArray()) {
            for (JsonNode operation : operations) {
                String path = optionalText(operation, "AttributePath");
                String value = attributeValue(operation.get("AttributeValue"));
                if ("displayName".equalsIgnoreCase(path)) {
                    group.displayName = value;
                } else if ("description".equalsIgnoreCase(path)) {
                    group.description = value;
                }
            }
        }
        group.updatedAt = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteGroup(JsonNode request) {
        Store store = requireStore(request);
        Group group = requireGroup(store, requireText(request, "GroupId"));
        store.groups.remove(group.groupId);
        store.memberships.values().removeIf(membership -> group.groupId.equals(membership.groupId));
        return objectMapper.createObjectNode();
    }

    public ObjectNode getGroupId(JsonNode request) {
        Store store = requireStore(request);
        String displayName = uniqueAttribute(request, "displayName");
        Group group = displayName == null ? null : findGroupByDisplayName(store, displayName);
        if (group == null) {
            throw notFound("GROUP", displayName == null ? "unknown" : displayName);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GroupId", group.groupId);
        response.put("IdentityStoreId", store.id);
        return response;
    }

    public ObjectNode listGroups(JsonNode request) {
        Store store = requireStore(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("Groups");
        for (Group group : store.groups.values()) {
            groups.add(toGroupNode(store.id, group));
        }
        return response;
    }

    public ObjectNode createGroupMembership(JsonNode request) {
        Store store = requireStore(request);
        String groupId = requireText(request, "GroupId");
        requireGroup(store, groupId);
        String userId = requireUserId(request.get("MemberId"));
        requireUser(store, userId);
        for (Membership existing : store.memberships.values()) {
            if (existing.groupId.equals(groupId) && existing.userId.equals(userId)) {
                throw conflict("The membership already exists.");
            }
        }
        Membership membership = new Membership();
        membership.membershipId = UUID.randomUUID().toString();
        membership.groupId = groupId;
        membership.userId = userId;
        long now = nowSeconds();
        membership.createdAt = now;
        membership.updatedAt = now;
        store.memberships.put(membership.membershipId, membership);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MembershipId", membership.membershipId);
        response.put("IdentityStoreId", store.id);
        return response;
    }

    public ObjectNode deleteGroupMembership(JsonNode request) {
        Store store = requireStore(request);
        Membership membership = requireMembership(store, requireText(request, "MembershipId"));
        store.memberships.remove(membership.membershipId);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeGroupMembership(JsonNode request) {
        Store store = requireStore(request);
        Membership membership = requireMembership(store, requireText(request, "MembershipId"));
        return toMembershipNode(store.id, membership);
    }

    public ObjectNode getGroupMembershipId(JsonNode request) {
        Store store = requireStore(request);
        String groupId = requireText(request, "GroupId");
        String userId = requireUserId(request.get("MemberId"));
        Membership membership = findMembership(store, groupId, userId);
        if (membership == null) {
            throw notFound("GROUP_MEMBERSHIP", groupId + "/" + userId);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IdentityStoreId", store.id);
        response.put("MembershipId", membership.membershipId);
        return response;
    }

    public ObjectNode listGroupMemberships(JsonNode request) {
        Store store = requireStore(request);
        String groupId = requireText(request, "GroupId");
        requireGroup(store, groupId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode memberships = response.putArray("GroupMemberships");
        for (Membership membership : store.memberships.values()) {
            if (groupId.equals(membership.groupId)) {
                memberships.add(toMembershipNode(store.id, membership));
            }
        }
        return response;
    }

    public ObjectNode listGroupMembershipsForMember(JsonNode request) {
        Store store = requireStore(request);
        String userId = requireUserId(request.get("MemberId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode memberships = response.putArray("GroupMemberships");
        for (Membership membership : store.memberships.values()) {
            if (userId.equals(membership.userId)) {
                memberships.add(toMembershipNode(store.id, membership));
            }
        }
        return response;
    }

    public ObjectNode isMemberInGroups(JsonNode request) {
        Store store = requireStore(request);
        String userId = requireUserId(request.get("MemberId"));
        JsonNode groupIds = request.get("GroupIds");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("Results");
        if (groupIds != null && groupIds.isArray()) {
            for (JsonNode groupIdNode : groupIds) {
                String groupId = groupIdNode.asText();
                ObjectNode result = results.addObject();
                result.put("GroupId", groupId);
                ObjectNode memberId = result.putObject("MemberId");
                memberId.put("UserId", userId);
                result.put("MembershipExists", findMembership(store, groupId, userId) != null);
            }
        }
        return response;
    }

    private ObjectNode toUserNode(String storeId, User user) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityStoreId", storeId);
        node.put("UserId", user.userId);
        if (user.userName != null) {
            node.put("UserName", user.userName);
        }
        if (user.displayName != null) {
            node.put("DisplayName", user.displayName);
        }
        if (user.name != null) {
            node.set("Name", user.name.deepCopy());
        }
        if (user.emails != null) {
            node.set("Emails", user.emails.deepCopy());
        }
        node.put("UserStatus", "ENABLED");
        node.put("CreatedAt", user.createdAt);
        node.put("UpdatedAt", user.updatedAt);
        return node;
    }

    private ObjectNode toGroupNode(String storeId, Group group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GroupId", group.groupId);
        node.put("IdentityStoreId", storeId);
        if (group.displayName != null) {
            node.put("DisplayName", group.displayName);
        }
        if (group.description != null) {
            node.put("Description", group.description);
        }
        node.put("CreatedAt", group.createdAt);
        node.put("UpdatedAt", group.updatedAt);
        return node;
    }

    private ObjectNode toMembershipNode(String storeId, Membership membership) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityStoreId", storeId);
        node.put("MembershipId", membership.membershipId);
        node.put("GroupId", membership.groupId);
        ObjectNode memberId = node.putObject("MemberId");
        memberId.put("UserId", membership.userId);
        node.put("CreatedAt", membership.createdAt);
        node.put("UpdatedAt", membership.updatedAt);
        return node;
    }

    private Store requireStore(JsonNode request) {
        String id = requireText(request, "IdentityStoreId");
        Store store = stores.get(id);
        if (store == null) {
            throw notFound("IDENTITY_STORE", id);
        }
        return store;
    }

    private User requireUser(Store store, String userId) {
        User user = store.users.get(userId);
        if (user == null) {
            throw notFound("USER", userId);
        }
        return user;
    }

    private Group requireGroup(Store store, String groupId) {
        Group group = store.groups.get(groupId);
        if (group == null) {
            throw notFound("GROUP", groupId);
        }
        return group;
    }

    private Membership requireMembership(Store store, String membershipId) {
        Membership membership = store.memberships.get(membershipId);
        if (membership == null) {
            throw notFound("GROUP_MEMBERSHIP", membershipId);
        }
        return membership;
    }

    private static User findUserByName(Store store, String userName) {
        for (User user : store.users.values()) {
            if (user.userName != null && user.userName.equalsIgnoreCase(userName)) {
                return user;
            }
        }
        return null;
    }

    private static Group findGroupByDisplayName(Store store, String displayName) {
        for (Group group : store.groups.values()) {
            if (group.displayName != null && group.displayName.equalsIgnoreCase(displayName)) {
                return group;
            }
        }
        return null;
    }

    private static Membership findMembership(Store store, String groupId, String userId) {
        for (Membership membership : store.memberships.values()) {
            if (membership.groupId.equals(groupId) && membership.userId.equals(userId)) {
                return membership;
            }
        }
        return null;
    }

    private void applyOperations(User user, JsonNode operations) {
        if (operations == null || !operations.isArray()) {
            return;
        }
        for (JsonNode operation : operations) {
            String path = optionalText(operation, "AttributePath");
            String value = attributeValue(operation.get("AttributeValue"));
            if ("displayName".equalsIgnoreCase(path)) {
                user.displayName = value;
            } else if ("userName".equalsIgnoreCase(path)) {
                user.userName = value;
            }
        }
    }

    private static String uniqueAttribute(JsonNode request, String expectedPath) {
        JsonNode identifier = request == null ? null : request.get("AlternateIdentifier");
        if (identifier == null || !identifier.isObject()) {
            throw validation("AlternateIdentifier is required.");
        }
        JsonNode unique = identifier.get("UniqueAttribute");
        if (unique == null || !unique.isObject()) {
            throw validation("UniqueAttribute is required.");
        }
        String path = optionalText(unique, "AttributePath");
        if (path == null || !expectedPath.equalsIgnoreCase(path)) {
            throw validation("AttributePath must be " + expectedPath + ".");
        }
        return attributeValue(unique.get("AttributeValue"));
    }

    private static String requireUserId(JsonNode memberId) {
        if (memberId == null || !memberId.isObject() || !memberId.hasNonNull("UserId")) {
            throw validation("MemberId.UserId is required.");
        }
        String userId = memberId.get("UserId").asText();
        if (userId.isBlank()) {
            throw validation("MemberId.UserId is required.");
        }
        return userId;
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String attributeValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private JsonNode copyObject(JsonNode node) {
        return node != null && node.isObject() ? node.deepCopy() : null;
    }

    private JsonNode copyArray(JsonNode node) {
        return node != null && node.isArray() ? node.deepCopy() : null;
    }

    private static AwsException notFound(String resourceType, String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " not found.",
                404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String hex(int chars) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, chars);
    }

    private static final class Store {
        final String id;
        final Map<String, User> users = new ConcurrentHashMap<>();
        final Map<String, Group> groups = new ConcurrentHashMap<>();
        final Map<String, Membership> memberships = new ConcurrentHashMap<>();

        Store(String id) {
            this.id = id;
        }
    }

    private static final class User {
        String userId;
        String userName;
        String displayName;
        JsonNode name;
        JsonNode emails;
        long createdAt;
        long updatedAt;
    }

    private static final class Group {
        String groupId;
        String displayName;
        String description;
        long createdAt;
        long updatedAt;
    }

    private static final class Membership {
        String membershipId;
        String groupId;
        String userId;
        long createdAt;
        long updatedAt;
    }
}
