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
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.EngineVersion;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Event;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Parameter;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Snapshot;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Subnet;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.SubnetGroup;
import io.github.hectorvent.floci.services.memorydb.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

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
                case "DescribeClusters" -> handleDescribeClusters(request, region);
                case "UpdateCluster" -> handleUpdateCluster(request, region);
                case "DeleteCluster" -> handleDeleteCluster(request, region);
                case "CreateUser" -> handleCreateUser(request, region);
                case "UpdateUser" -> handleUpdateUser(request, region);
                case "CreateParameterGroup" -> single("ParameterGroup", parameterGroupNode(service.createParameterGroup(
                        text(request, "ParameterGroupName"), text(request, "Family"), text(request, "Description"),
                        parseTags(request.path("Tags")), region)));
                case "DescribeParameterGroups" -> page("ParameterGroups", request, region,
                        service.describeParameterGroups(text(request, "ParameterGroupName"), region), this::parameterGroupNode);
                case "DescribeParameters" -> page("Parameters", request, region,
                        service.describeParameters(text(request, "ParameterGroupName"), region),
                        this::parameterNode);
                case "UpdateParameterGroup" -> handleUpdateParameterGroup(request, region);
                case "ResetParameterGroup" -> handleResetParameterGroup(request, region);
                case "DeleteParameterGroup" -> single("ParameterGroup", parameterGroupNode(
                        service.deleteParameterGroup(text(request, "ParameterGroupName"), region)));
                case "CreateSubnetGroup" -> single("SubnetGroup", subnetGroupNode(service.createSubnetGroup(
                        text(request, "SubnetGroupName"), text(request, "Description"),
                        parseStringList(request.path("SubnetIds")), parseTags(request.path("Tags")), region)));
                case "DescribeSubnetGroups" -> page("SubnetGroups", request, region,
                        service.describeSubnetGroups(text(request, "SubnetGroupName"), region), this::subnetGroupNode);
                case "UpdateSubnetGroup" -> single("SubnetGroup", subnetGroupNode(service.updateSubnetGroup(
                        text(request, "SubnetGroupName"), text(request, "Description"),
                        request.hasNonNull("SubnetIds") ? parseStringList(request.path("SubnetIds")) : null, region)));
                case "DeleteSubnetGroup" -> single("SubnetGroup", subnetGroupNode(
                        service.deleteSubnetGroup(text(request, "SubnetGroupName"), region)));
                case "CreateSnapshot" -> handleCreateSnapshot(request, region);
                case "DescribeSnapshots" -> page("Snapshots", request, region,
                        service.describeSnapshots(text(request, "SnapshotName"), text(request, "ClusterName"),
                                text(request, "Source"), region), this::snapshotNode);
                case "CopySnapshot" -> handleCopySnapshot(request, region);
                case "DeleteSnapshot" -> single("Snapshot", snapshotNode(
                        service.deleteSnapshot(text(request, "SnapshotName"), region)));
                case "DescribeEvents" -> page("Events", request, region,
                        service.describeEvents(text(request, "SourceName"), text(request, "SourceType"),
                                decimal(request, "StartTime"), decimal(request, "EndTime"),
                                integer(request, "Duration"), region), this::eventNode);
                case "DescribeEngineVersions" -> page("EngineVersions", request, region,
                        service.describeEngineVersions(text(request, "Engine"), text(request, "EngineVersion"),
                                text(request, "ParameterGroupFamily"), request.path("DefaultOnly").asBoolean(false)),
                        this::engineVersionNode);
                case "DescribeServiceUpdates" -> page("ServiceUpdates", request, region,
                        service.describeServiceUpdates(parseStringList(request.path("ClusterNames")),
                                text(request, "ServiceUpdateName"), parseStringList(request.path("Status"))),
                        value -> objectMapper.createObjectNode());
                case "BatchUpdateCluster" -> handleBatchUpdateCluster(request);
                case "DescribeUsers" -> handleDescribeUsers(request, region);
                case "DeleteUser" -> handleDeleteUser(request, region);
                case "CreateACL" -> handleCreateAcl(request, region);
                case "DescribeACLs" -> handleDescribeAcls(request, region);
                case "DeleteACL" -> handleDeleteAcl(request, region);
                case "ListTags" -> handleListTags(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
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
        if (request.hasNonNull("SnapshotName") || !request.path("SnapshotArns").isEmpty()) {
            throw new AwsException("InvalidParameterValueException", "Snapshot restore is not supported.", 400);
        }
        Cluster spec = new Cluster();
        spec.setName(text(request, "ClusterName"));
        spec.setDescription(text(request, "Description"));
        spec.setNodeType(text(request, "NodeType"));
        if (request.hasNonNull("NumShards")) {
            spec.setNumberOfShards(request.get("NumShards").asInt());
        }
        spec.setEngine(text(request, "Engine"));
        spec.setEngineVersion(text(request, "EngineVersion"));
        spec.setAclName(text(request, "ACLName"));
        spec.setParameterGroupName(text(request, "ParameterGroupName"));
        spec.setSubnetGroupName(text(request, "SubnetGroupName"));
        spec.setTlsEnabled(request.path("TLSEnabled").asBoolean(false));
        spec.setTags(parseTags(request.path("Tags")));
        Cluster created = service.createCluster(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeClusters(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (Cluster cluster : service.describeClusters(text(request, "ClusterName"), region)) {
            arr.add(clusterNode(cluster));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateCluster(JsonNode request, String region) {
        Cluster updated = service.updateCluster(text(request, "ClusterName"), text(request, "Description"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteCluster(JsonNode request, String region) {
        Cluster deleted = service.deleteCluster(text(request, "ClusterName"), region, text(request, "FinalSnapshotName"));
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

    private Response handleDeleteUser(JsonNode request, String region) {
        User deleted = service.deleteUser(text(request, "UserName"), region);
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

    private Response handleDeleteAcl(JsonNode request, String region) {
        Acl deleted = service.deleteAcl(text(request, "ACLName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ACL", aclNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request, String region) {
        Map<String, String> tags = service.listTags(text(request, "ResourceArn"), region);
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        Map<String, String> tags = service.tagResource(text(request, "ResourceArn"),
                parseTags(request.path("Tags")), region);
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        List<String> keys = parseStringList(request.path("TagKeys"));
        Map<String, String> tags = service.untagResource(text(request, "ResourceArn"), keys, region);
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleUpdateUser(JsonNode request, String region) {
        JsonNode authentication = request.path("AuthenticationMode");
        return single("User", userNode(service.updateUser(text(request, "UserName"),
                text(request, "AccessString"), parseAuthMode(authentication),
                parseStringList(authentication.path("Passwords")), region)));
    }

    private Response handleUpdateParameterGroup(JsonNode request, String region) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (JsonNode parameter : request.path("ParameterNameValues")) {
            parameters.put(text(parameter, "ParameterName"), text(parameter, "ParameterValue"));
        }
        String name = text(request, "ParameterGroupName");
        service.updateParameterGroup(name, parameters, region);
        return single("ParameterGroup", parameterGroupNode(service.getParameterGroup(name, region)));
    }

    private Response handleResetParameterGroup(JsonNode request, String region) {
        String name = text(request, "ParameterGroupName");
        service.resetParameterGroup(name, request.path("AllParameters").asBoolean(false),
                parseStringList(request.path("ParameterNames")), region);
        return single("ParameterGroup", parameterGroupNode(service.getParameterGroup(name, region)));
    }

    private Response handleCreateSnapshot(JsonNode request, String region) {
        rejectSnapshotEncryption(request);
        return single("Snapshot", snapshotNode(service.createSnapshot(text(request, "ClusterName"),
                text(request, "SnapshotName"), parseTags(request.path("Tags")), region)));
    }

    private Response handleCopySnapshot(JsonNode request, String region) {
        rejectSnapshotEncryption(request);
        if (request.hasNonNull("TargetBucket")) {
            throw new AwsException("InvalidParameterValueException", "Snapshot export to S3 is not supported.", 400);
        }
        return single("Snapshot", snapshotNode(service.copySnapshot(text(request, "SourceSnapshotName"),
                text(request, "TargetSnapshotName"), request.hasNonNull("Tags")
                        ? parseTags(request.path("Tags")) : null, region)));
    }

    private void rejectSnapshotEncryption(JsonNode request) {
        if (request.hasNonNull("KmsKeyId")) {
            throw new AwsException("InvalidParameterValueException", "KMS-encrypted snapshots are not supported.", 400);
        }
    }

    private Response handleBatchUpdateCluster(JsonNode request) {
        service.batchUpdateCluster(parseStringList(request.path("ClusterNames")),
                text(request.path("ServiceUpdate"), "ServiceUpdateNameToApply"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response single(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return Response.ok(response).build();
    }

    private <T> Response page(String field, JsonNode request, String region, List<T> values,
                              Function<T, ObjectNode> encode) {
        Map<String, String> filters = new TreeMap<>();
        request.properties().forEach(entry -> {
            if (!entry.getKey().equals("NextToken") && !entry.getKey().equals("MaxResults")) {
                filters.put(entry.getKey(), entry.getValue().toString());
            }
        });
        MemoryDbService.Page<T> page = service.page(values, field + filters, region,
                integer(request, "MaxResults"), text(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray(field);
        page.values().forEach(value -> array.add(encode.apply(value)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Integer integer(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        if (!request.get(field).isIntegralNumber() || !request.get(field).canConvertToInt()) {
            throw new AwsException("InvalidParameterValueException", field + " must be an integer.", 400);
        }
        return request.get(field).intValue();
    }

    private Double decimal(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            return null;
        }
        if (!request.get(field).isNumber()) {
            throw new AwsException("InvalidParameterValueException", field + " must be epoch seconds.", 400);
        }
        return request.get(field).doubleValue();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private ObjectNode parameterGroupNode(ParameterGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", group.name());
        node.put("Family", group.family());
        if (group.description() != null) {
            node.put("Description", group.description());
        }
        node.put("ARN", group.arn());
        return node;
    }

    private ObjectNode parameterNode(Parameter parameter) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", parameter.name());
        node.put("Value", parameter.value());
        node.put("DataType", parameter.dataType());
        node.put("AllowedValues", parameter.allowedValues());
        return node;
    }

    private ObjectNode subnetGroupNode(SubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", group.name());
        if (group.description() != null) {
            node.put("Description", group.description());
        }
        node.put("VpcId", group.vpcId());
        node.put("ARN", group.arn());
        node.putArray("SupportedNetworkTypes").add("ipv4");
        ArrayNode subnets = node.putArray("Subnets");
        for (Subnet subnet : group.subnets()) {
            ObjectNode entry = subnets.addObject();
            entry.put("Identifier", subnet.identifier());
            entry.putObject("AvailabilityZone").put("Name", subnet.availabilityZone());
            entry.putArray("SupportedNetworkTypes").add("ipv4");
        }
        return node;
    }

    private ObjectNode snapshotNode(Snapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", snapshot.name());
        node.put("ARN", snapshot.arn());
        node.put("Status", "available");
        node.put("Source", "manual");
        ObjectNode configuration = node.putObject("ClusterConfiguration");
        configuration.put("Name", snapshot.clusterName());
        configuration.put("NodeType", snapshot.nodeType());
        configuration.put("Engine", snapshot.engine());
        configuration.put("EngineVersion", snapshot.engineVersion());
        configuration.put("NumShards", snapshot.numShards());
        return node;
    }

    private ObjectNode eventNode(Event event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SourceName", event.sourceName());
        node.put("SourceType", event.sourceType());
        node.put("Message", event.message());
        node.put("Date", event.date());
        return node;
    }

    private ObjectNode engineVersionNode(EngineVersion version) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Engine", version.engine());
        node.put("EngineVersion", version.version());
        node.put("ParameterGroupFamily", version.family());
        return node;
    }

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
        if (cluster.getParameterGroupName() != null) {
            node.put("ParameterGroupName", cluster.getParameterGroupName());
        }
        if (cluster.getSubnetGroupName() != null) {
            node.put("SubnetGroupName", cluster.getSubnetGroupName());
        }
        node.put("TLSEnabled", cluster.isTlsEnabled());
        node.put("ARN", cluster.getArn());
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
        service.aclNamesForUser(user.getName(), user.getRegion()).forEach(aclNames::add);
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
        service.clustersUsingAcl(acl.getName(), acl.getRegion()).forEach(clustersArr::add);
        if (acl.getArn() != null) {
            node.put("ARN", acl.getArn());
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
        List<String> values = new ArrayList<>();
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
