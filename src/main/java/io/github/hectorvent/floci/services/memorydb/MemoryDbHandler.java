package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.EngineVersion;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbEvent;
import io.github.hectorvent.floci.services.memorydb.model.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.model.Snapshot;
import io.github.hectorvent.floci.services.memorydb.model.SubnetGroup;
import io.github.hectorvent.floci.services.memorydb.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryDB JSON 1.1 handler. Dispatched from {@link AwsJson11Controller} under the
 * {@code AmazonMemoryDB.} target prefix.
 */
@ApplicationScoped
public class MemoryDbHandler {

    private static final Logger LOG = Logger.getLogger(MemoryDbHandler.class);

    private final MemoryDbService service;
    private final ObjectMapper objectMapper;

    @Inject
    public MemoryDbHandler(MemoryDbService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("MemoryDB action: {0}", action);
        try {
            return switch (action) {
                case "CreateCluster" -> handleCreateCluster(request, region);
                case "DescribeClusters" -> handleDescribeClusters(request);
                case "UpdateCluster" -> handleUpdateCluster(request);
                case "DeleteCluster" -> handleDeleteCluster(request);
                case "CreateUser" -> handleCreateUser(request, region);
                case "DescribeUsers" -> handleDescribeUsers(request, region);
                case "UpdateUser" -> handleUpdateUser(request);
                case "DeleteUser" -> handleDeleteUser(request);
                case "CreateACL" -> handleCreateAcl(request, region);
                case "DescribeACLs" -> handleDescribeAcls(request, region);
                case "UpdateACL" -> handleUpdateAcl(request);
                case "DeleteACL" -> handleDeleteAcl(request);
                case "CreateParameterGroup" -> handleCreateParameterGroup(request, region);
                case "DescribeParameterGroups" -> handleDescribeParameterGroups(request);
                case "DescribeParameters" -> handleDescribeParameters(request);
                case "UpdateParameterGroup" -> handleUpdateParameterGroup(request);
                case "ResetParameterGroup" -> handleResetParameterGroup(request);
                case "DeleteParameterGroup" -> handleDeleteParameterGroup(request);
                case "CreateSubnetGroup" -> handleCreateSubnetGroup(request, region);
                case "DescribeSubnetGroups" -> handleDescribeSubnetGroups(request);
                case "UpdateSubnetGroup" -> handleUpdateSubnetGroup(request, region);
                case "DeleteSubnetGroup" -> handleDeleteSubnetGroup(request);
                case "DescribeSnapshots" -> handleDescribeSnapshots(request);
                case "DescribeEvents" -> handleDescribeEvents(request);
                case "DescribeServiceUpdates" -> handleDescribeServiceUpdates();
                case "DescribeEngineVersions" -> handleDescribeEngineVersions(request);
                case "BatchUpdateCluster" -> handleBatchUpdateCluster(request);
                case "DeleteSnapshot" -> handleDeleteSnapshot(request);
                case "CopySnapshot" -> handleCopySnapshot(request, region);
                case "ListTags" -> handleListTags(request);
                case "TagResource" -> handleTagResource(request);
                case "UntagResource" -> handleUntagResource(request);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("MemoryDB error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalFailure", e.getMessage()))
                    .build();
        }
    }

    private Response handleCreateCluster(JsonNode request, String region) {
        Cluster spec = new Cluster();
        spec.setName(text(request, "ClusterName"));
        spec.setDescription(text(request, "Description"));
        spec.setNodeType(text(request, "NodeType"));
        if (request.hasNonNull("NumShards")) {
            spec.setNumberOfShards(request.get("NumShards").asInt());
        }
        if (request.hasNonNull("NumReplicasPerShard")) {
            spec.setNumReplicasPerShard(request.get("NumReplicasPerShard").asInt());
        }
        spec.setEngine(text(request, "Engine"));
        spec.setEngineVersion(text(request, "EngineVersion"));
        spec.setAclName(text(request, "ACLName"));
        spec.setSubnetGroupName(text(request, "SubnetGroupName"));
        if (request.has("SecurityGroupIds")) {
            spec.setSecurityGroupIds(parseStringList(request.path("SecurityGroupIds")));
        }
        spec.setParameterGroupName(text(request, "ParameterGroupName"));
        spec.setTlsEnabled(request.has("TLSEnabled") && !request.get("TLSEnabled").isNull()
                ? request.get("TLSEnabled").asBoolean()
                : true);
        spec.setTags(parseTags(request.path("Tags")));
        Cluster created = service.createCluster(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeClusters(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (Cluster cluster : service.describeClusters(text(request, "ClusterName"))) {
            arr.add(clusterNode(cluster));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateCluster(JsonNode request) {
        Cluster patch = new Cluster();
        patch.setName(text(request, "ClusterName"));
        patch.setDescription(text(request, "Description"));
        if (request.has("SecurityGroupIds")) {
            patch.setSecurityGroupIds(parseStringList(request.path("SecurityGroupIds")));
        }
        patch.setAclName(text(request, "ACLName"));
        patch.setNodeType(text(request, "NodeType"));
        patch.setEngineVersion(text(request, "EngineVersion"));
        patch.setParameterGroupName(text(request, "ParameterGroupName"));
        if (request.path("ShardConfiguration").hasNonNull("ShardCount")) {
            patch.setNumberOfShards(request.path("ShardConfiguration").get("ShardCount").asInt());
        }
        Cluster updated = service.updateCluster(patch);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteCluster(JsonNode request) {
        Cluster deleted = service.deleteCluster(text(request, "ClusterName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleCreateUser(JsonNode request, String region) {
        User spec = new User();
        spec.setName(text(request, "UserName"));
        spec.setAccessString(text(request, "AccessString"));
        JsonNode authNode = request.path("AuthenticationMode");
        spec.setAuthMode(parseAuthMode(authNode));
        spec.setPasswords(parsePasswords(authNode.path("Passwords")));
        spec.setTags(parseTags(request.path("Tags")));
        User created = service.createUser(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("User", userNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeUsers(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Users");
        for (User user : service.describeUsers(text(request, "UserName"), region)) {
            arr.add(userNode(user));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateUser(JsonNode request) {
        JsonNode authNode = request.path("AuthenticationMode");
        User updated = service.updateUser(
                text(request, "UserName"),
                text(request, "AccessString"),
                parseAuthMode(authNode),
                parsePasswords(authNode.path("Passwords")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("User", userNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteUser(JsonNode request) {
        User deleted = service.deleteUser(text(request, "UserName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("User", userNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleCreateAcl(JsonNode request, String region) {
        Acl spec = new Acl();
        spec.setName(text(request, "ACLName"));
        spec.setUserNames(parseStringList(request.path("UserNames")));
        spec.setTags(parseTags(request.path("Tags")));
        Acl created = service.createAcl(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ACL", aclNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeAcls(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ACLs");
        for (Acl acl : service.describeAcls(text(request, "ACLName"), region)) {
            arr.add(aclNode(acl));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateAcl(JsonNode request) {
        Acl updated = service.updateAcl(
                text(request, "ACLName"),
                parseStringList(request.path("UserNamesToAdd")),
                parseStringList(request.path("UserNamesToRemove")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ACL", aclNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteAcl(JsonNode request) {
        Acl deleted = service.deleteAcl(text(request, "ACLName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ACL", aclNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleCreateParameterGroup(JsonNode request, String region) {
        ParameterGroup spec = new ParameterGroup();
        spec.setName(text(request, "ParameterGroupName"));
        spec.setFamily(text(request, "Family"));
        spec.setDescription(text(request, "Description"));
        spec.setTags(parseTags(request.path("Tags")));
        ParameterGroup created = service.createParameterGroup(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", parameterGroupNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeParameterGroups(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("ParameterGroups");
        for (ParameterGroup group : service.describeParameterGroups(text(request, "ParameterGroupName"))) {
            arr.add(parameterGroupNode(group));
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeParameters(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Parameters");
        service.describeParameters(text(request, "ParameterGroupName")).forEach((name, value) ->
                arr.add(parameterNode(name, value)));
        return Response.ok(response).build();
    }

    private Response handleUpdateParameterGroup(JsonNode request) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (JsonNode node : request.path("ParameterNameValues")) {
            String parameterName = text(node, "ParameterName");
            if (parameterName != null) {
                updates.put(parameterName, text(node, "ParameterValue"));
            }
        }
        ParameterGroup updated = service.updateParameterGroup(text(request, "ParameterGroupName"), updates);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", parameterGroupNode(updated));
        return Response.ok(response).build();
    }

    private Response handleResetParameterGroup(JsonNode request) {
        List<String> names = new java.util.ArrayList<>();
        request.path("ParameterNames").forEach(n -> names.add(n.asText()));
        ParameterGroup reset = service.resetParameterGroup(
                text(request, "ParameterGroupName"),
                request.path("AllParameters").asBoolean(false),
                names);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", parameterGroupNode(reset));
        return Response.ok(response).build();
    }

    private Response handleDeleteParameterGroup(JsonNode request) {
        ParameterGroup deleted = service.deleteParameterGroup(text(request, "ParameterGroupName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", parameterGroupNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleCreateSubnetGroup(JsonNode request, String region) {
        SubnetGroup spec = new SubnetGroup();
        spec.setName(text(request, "SubnetGroupName"));
        spec.setDescription(text(request, "Description"));
        spec.setTags(parseTags(request.path("Tags")));
        List<SubnetGroup.SubnetRef> members = new java.util.ArrayList<>();
        for (String id : parseStringList(request.path("SubnetIds"))) {
            members.add(new SubnetGroup.SubnetRef(id, null));
        }
        spec.setSubnets(members);
        SubnetGroup created = service.createSubnetGroup(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SubnetGroup", subnetGroupNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeSubnetGroups(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("SubnetGroups");
        for (SubnetGroup group : service.describeSubnetGroups(text(request, "SubnetGroupName"))) {
            arr.add(subnetGroupNode(group));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateSubnetGroup(JsonNode request, String region) {
        SubnetGroup updated = service.updateSubnetGroup(
                text(request, "SubnetGroupName"),
                text(request, "Description"),
                parseStringList(request.path("SubnetIds")),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SubnetGroup", subnetGroupNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteSubnetGroup(JsonNode request) {
        SubnetGroup deleted = service.deleteSubnetGroup(text(request, "SubnetGroupName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SubnetGroup", subnetGroupNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleDescribeSnapshots(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Snapshots");
        for (Snapshot snapshot : service.describeSnapshots(
                text(request, "ClusterName"), text(request, "SnapshotName"))) {
            arr.add(snapshotNode(snapshot));
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeEvents(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Events");
        for (MemoryDbEvent event : service.describeEvents(
                text(request, "SourceName"), text(request, "SourceType"))) {
            ObjectNode node = arr.addObject();
            node.put("SourceName", event.getSourceName());
            node.put("SourceType", event.getSourceType());
            node.put("Message", event.getMessage());
            node.put("Date", event.getDate());
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeServiceUpdates() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ServiceUpdates");
        return Response.ok(response).build();
    }

    private Response handleDescribeEngineVersions(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("EngineVersions");
        for (EngineVersion version : service.describeEngineVersions(
                text(request, "Engine"),
                text(request, "EngineVersion"),
                text(request, "ParameterGroupFamily"),
                request.path("DefaultOnly").asBoolean(false))) {
            ObjectNode node = arr.addObject();
            node.put("Engine", version.getEngine());
            node.put("EngineVersion", version.getEngineVersion());
            node.put("EnginePatchVersion", version.getEnginePatchVersion());
            node.put("ParameterGroupFamily", version.getParameterGroupFamily());
        }
        return Response.ok(response).build();
    }

    private Response handleBatchUpdateCluster(JsonNode request) {
        List<String> clusterNames = parseStringList(request.path("ClusterNames"));
        String serviceUpdateName = text(request.path("ServiceUpdate"), "ServiceUpdateNameToApply");
        service.batchUpdateCluster(clusterNames, serviceUpdateName);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ProcessedClusters");
        response.putArray("UnprocessedClusters");
        return Response.ok(response).build();
    }

    private Response handleDeleteSnapshot(JsonNode request) {
        Snapshot deleted = service.deleteSnapshot(text(request, "SnapshotName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Snapshot", snapshotNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleCopySnapshot(JsonNode request, String region) {
        Snapshot copied = service.copySnapshot(
                text(request, "SourceSnapshotName"),
                text(request, "TargetSnapshotName"),
                text(request, "KmsKeyId"),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Snapshot", snapshotNode(copied));
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request) {
        Map<String, String> tags = service.listTags(text(request, "ResourceArn"));
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleTagResource(JsonNode request) {
        Map<String, String> tags = service.tagResource(text(request, "ResourceArn"),
                parseTags(request.path("Tags")));
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new java.util.ArrayList<>();
        request.path("TagKeys").forEach(k -> keys.add(k.asText()));
        Map<String, String> tags = service.untagResource(text(request, "ResourceArn"), keys);
        return Response.ok(tagListResponse(tags)).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private ObjectNode clusterNode(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", cluster.getName());
        if (cluster.getDescription() != null) {
            node.put("Description", cluster.getDescription());
        }
        node.put("Status", cluster.getStatus().wireValue());
        node.put("NodeType", cluster.getNodeType());
        node.put("NumberOfShards", cluster.getNumberOfShards());
        node.put("Engine", cluster.getEngine());
        node.put("EngineVersion", cluster.getEngineVersion());
        node.put("ACLName", cluster.getAclName());
        node.put("TLSEnabled", cluster.isTlsEnabled());
        node.put("ARN", cluster.getArn());
        if (cluster.getSubnetGroupName() != null) {
            node.put("SubnetGroupName", cluster.getSubnetGroupName());
        }
        if (cluster.getParameterGroupName() != null) {
            node.put("ParameterGroupName", cluster.getParameterGroupName());
        }
        ArrayNode securityGroups = node.putArray("SecurityGroups");
        for (String groupId : cluster.getSecurityGroupIds()) {
            ObjectNode membership = securityGroups.addObject();
            membership.put("SecurityGroupId", groupId);
            membership.put("Status", "active");
        }
        if (cluster.getClusterEndpoint() != null) {
            ObjectNode endpoint = node.putObject("ClusterEndpoint");
            endpoint.put("Address", cluster.getClusterEndpoint().address());
            endpoint.put("Port", cluster.getClusterEndpoint().port());
        }
        return node;
    }

    private ObjectNode userNode(User user) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", user.getName());
        node.put("Status", user.getStatus());
        if (user.getAccessString() != null) {
            node.put("AccessString", user.getAccessString());
        }
        if (user.getMinimumEngineVersion() != null) {
            node.put("MinimumEngineVersion", user.getMinimumEngineVersion());
        }
        ObjectNode authentication = node.putObject("Authentication");
        authentication.put("Type", user.getAuthMode().wireValue());
        if (user.getAuthMode() == AuthMode.PASSWORD) {
            authentication.put("PasswordCount", user.getPasswords() != null ? user.getPasswords().size() : 0);
        }
        ArrayNode aclNames = node.putArray("ACLNames");
        service.aclNamesForUser(user.getName()).forEach(aclNames::add);
        if (user.getArn() != null) {
            node.put("ARN", user.getArn());
        }
        return node;
    }

    private ObjectNode aclNode(Acl acl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", acl.getName());
        node.put("Status", acl.getStatus());
        ArrayNode userNames = node.putArray("UserNames");
        acl.getUserNames().forEach(userNames::add);
        if (acl.getMinimumEngineVersion() != null) {
            node.put("MinimumEngineVersion", acl.getMinimumEngineVersion());
        }
        ArrayNode clustersArr = node.putArray("Clusters");
        service.clustersUsingAcl(acl.getName()).forEach(clustersArr::add);
        if (acl.getArn() != null) {
            node.put("ARN", acl.getArn());
        }
        return node;
    }

    private ObjectNode parameterGroupNode(ParameterGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", group.getName());
        node.put("Family", group.getFamily());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        if (group.getArn() != null) {
            node.put("ARN", group.getArn());
        }
        return node;
    }

    private ObjectNode subnetGroupNode(SubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", group.getName());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        if (group.getVpcId() != null) {
            node.put("VpcId", group.getVpcId());
        }
        ArrayNode subnets = node.putArray("Subnets");
        for (SubnetGroup.SubnetRef subnet : group.getSubnets()) {
            ObjectNode member = subnets.addObject();
            member.put("Identifier", subnet.getIdentifier());
            if (subnet.getAvailabilityZone() != null) {
                member.putObject("AvailabilityZone").put("Name", subnet.getAvailabilityZone());
            }
        }
        if (group.getArn() != null) {
            node.put("ARN", group.getArn());
        }
        return node;
    }

    private ObjectNode snapshotNode(Snapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", snapshot.getName());
        node.put("Status", snapshot.getStatus());
        if (snapshot.getSource() != null) {
            node.put("Source", snapshot.getSource());
        }
        if (snapshot.getKmsKeyId() != null) {
            node.put("KmsKeyId", snapshot.getKmsKeyId());
        }
        if (snapshot.getArn() != null) {
            node.put("ARN", snapshot.getArn());
        }
        ObjectNode config = node.putObject("ClusterConfiguration");
        if (snapshot.getClusterName() != null) {
            config.put("Name", snapshot.getClusterName());
        }
        if (snapshot.getClusterDescription() != null) {
            config.put("Description", snapshot.getClusterDescription());
        }
        if (snapshot.getNodeType() != null) {
            config.put("NodeType", snapshot.getNodeType());
        }
        if (snapshot.getEngine() != null) {
            config.put("Engine", snapshot.getEngine());
        }
        if (snapshot.getEngineVersion() != null) {
            config.put("EngineVersion", snapshot.getEngineVersion());
        }
        if (snapshot.getNumberOfShards() > 0) {
            config.put("NumShards", snapshot.getNumberOfShards());
        }
        return node;
    }

    private ObjectNode parameterNode(String name, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", name);
        node.put("Value", value);
        if ("maxmemory-policy".equals(name)) {
            node.put("Description", "Eviction policy used when maxmemory is reached.");
            node.put("DataType", "string");
            node.put("AllowedValues",
                    "volatile-lru,allkeys-lru,volatile-lfu,allkeys-lfu,volatile-random,allkeys-random,volatile-ttl,noeviction");
        } else {
            node.put("DataType", "string");
        }
        return node;
    }

    private ObjectNode tagListResponse(Map<String, String> tags) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("TagList");
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            arr.add(tag);
        });
        return response;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    private AuthMode parseAuthMode(JsonNode authNode) {
        String type = authNode.path("Type").asText(null);
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return AuthMode.fromWire(type);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    private List<String> parsePasswords(JsonNode passwordsNode) {
        return parseStringList(passwordsNode);
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> values = new java.util.ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String value = n.asText(null);
                if (value != null) {
                    values.add(value);
                }
            });
        }
        return values;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }
}
