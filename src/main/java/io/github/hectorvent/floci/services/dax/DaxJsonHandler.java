package io.github.hectorvent.floci.services.dax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.dax.model.Cluster;
import io.github.hectorvent.floci.services.dax.model.DaxSubnet;
import io.github.hectorvent.floci.services.dax.model.Node;
import io.github.hectorvent.floci.services.dax.model.ParameterGroup;
import io.github.hectorvent.floci.services.dax.model.SubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for Amazon DAX. Dispatched from {@code AwsJson11Controller}
 * under the {@code AmazonDAXV3.} target prefix.
 */
@ApplicationScoped
public class DaxJsonHandler {

    private final DaxService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DaxJsonHandler(DaxService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "DescribeClusters" -> describeClusters(body);
                case "DescribeEvents" -> describeEvents(body);
                case "DescribeDefaultParameters" -> describeDefaultParameters();
                case "CreateCluster" -> createCluster(body, region);
                case "UpdateCluster" -> updateCluster(body);
                case "DeleteCluster" -> deleteCluster(body);
                case "IncreaseReplicationFactor" -> increaseReplicationFactor(body);
                case "DecreaseReplicationFactor" -> decreaseReplicationFactor(body);
                case "RebootNode" -> rebootNode(body);
                case "ListTags" -> listTags(body);
                case "TagResource" -> tagResource(body);
                case "UntagResource" -> untagResource(body);
                case "CreateSubnetGroup" -> createSubnetGroup(body, region);
                case "DescribeSubnetGroups" -> describeSubnetGroups(body);
                case "UpdateSubnetGroup" -> updateSubnetGroup(body, region);
                case "DeleteSubnetGroup" -> deleteSubnetGroup(body);
                case "CreateParameterGroup" -> createParameterGroup(body);
                case "DescribeParameterGroups" -> describeParameterGroups(body);
                case "DescribeParameters" -> describeParameters(body);
                case "UpdateParameterGroup" -> updateParameterGroup(body);
                case "DeleteParameterGroup" -> deleteParameterGroup(body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("AmazonDAXV3." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response describeEvents(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Events");
        for (var event : service.describeEvents(
                textOrNull(request, "SourceName"), textOrNull(request, "SourceType"))) {
            ObjectNode node = list.addObject();
            node.put("SourceName", event.getSourceName());
            node.put("SourceType", event.getSourceType());
            node.put("Message", event.getMessage());
            node.put("Date", event.getDate());
        }
        return Response.ok(response).build();
    }

    private Response describeDefaultParameters() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Parameters");
        service.describeDefaultParameters().forEach((name, value) ->
                list.add(toParameterNode(name, value, "system", Map.of())));
        return Response.ok(response).build();
    }

    private Response describeClusters(JsonNode request) {
        List<Cluster> clusters = service.describeClusters(stringList(request.path("ClusterNames")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Clusters");
        for (Cluster cluster : clusters) {
            list.add(toClusterNode(cluster));
        }
        return Response.ok(response).build();
    }

    private Response createCluster(JsonNode request, String region) {
        Cluster spec = new Cluster();
        spec.setClusterName(textOrNull(request, "ClusterName"));
        spec.setNodeType(textOrNull(request, "NodeType"));
        spec.setDescription(textOrNull(request, "Description"));
        spec.setIamRoleArn(textOrNull(request, "IamRoleArn"));
        spec.setSubnetGroupName(textOrNull(request, "SubnetGroupName"));
        spec.setParameterGroupName(textOrNull(request, "ParameterGroupName"));
        spec.setPreferredMaintenanceWindow(textOrNull(request, "PreferredMaintenanceWindow"));
        spec.setNotificationTopicArn(textOrNull(request, "NotificationTopicArn"));
        spec.setClusterEndpointEncryptionType(textOrNull(request, "ClusterEndpointEncryptionType"));
        spec.setNetworkType(textOrNull(request, "NetworkType"));
        spec.setAvailabilityZones(stringList(request.path("AvailabilityZones")));
        int replicationFactor = request.hasNonNull("ReplicationFactor")
                ? request.get("ReplicationFactor").asInt()
                : 1;
        JsonNode sse = request.path("SSESpecification");
        if (sse.hasNonNull("Enabled")) {
            spec.setSseEnabled(sse.get("Enabled").asBoolean());
        }
        spec.setSecurityGroupIds(stringList(request.path("SecurityGroupIds")));
        spec.setTags(readTags(request.path("Tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(service.createCluster(spec, replicationFactor, region)));
        return Response.ok(response).build();
    }

    private Response updateCluster(JsonNode request) {
        Cluster patch = new Cluster();
        patch.setClusterName(textOrNull(request, "ClusterName"));
        patch.setDescription(textOrNull(request, "Description"));
        patch.setPreferredMaintenanceWindow(textOrNull(request, "PreferredMaintenanceWindow"));
        patch.setNotificationTopicArn(textOrNull(request, "NotificationTopicArn"));
        patch.setParameterGroupName(textOrNull(request, "ParameterGroupName"));
        if (request.has("SecurityGroupIds")) {
            patch.setSecurityGroupIds(stringList(request.path("SecurityGroupIds")));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(service.updateCluster(patch)));
        return Response.ok(response).build();
    }

    private Response deleteCluster(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(
                service.deleteCluster(textOrNull(request, "ClusterName"))));
        return Response.ok(response).build();
    }

    private Response increaseReplicationFactor(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(service.increaseReplicationFactor(
                textOrNull(request, "ClusterName"),
                request.path("NewReplicationFactor").asInt(),
                stringList(request.path("AvailabilityZones")))));
        return Response.ok(response).build();
    }

    private Response decreaseReplicationFactor(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(service.decreaseReplicationFactor(
                textOrNull(request, "ClusterName"),
                request.path("NewReplicationFactor").asInt(),
                stringList(request.path("NodeIdsToRemove")))));
        return Response.ok(response).build();
    }

    private Response rebootNode(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(service.rebootNode(
                textOrNull(request, "ClusterName"), textOrNull(request, "NodeId"))));
        return Response.ok(response).build();
    }

    private Response listTags(JsonNode request) {
        return Response.ok(tagListResponse(service.listTags(textOrNull(request, "ResourceName")))).build();
    }

    private Response tagResource(JsonNode request) {
        return Response.ok(tagListResponse(
                service.tagResource(textOrNull(request, "ResourceName"), readTags(request.path("Tags")))))
                .build();
    }

    private Response untagResource(JsonNode request) {
        return Response.ok(tagListResponse(
                service.untagResource(textOrNull(request, "ResourceName"), stringList(request.path("TagKeys")))))
                .build();
    }

    private Response createSubnetGroup(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SubnetGroup", toSubnetGroupNode(service.createSubnetGroup(
                textOrNull(request, "SubnetGroupName"),
                textOrNull(request, "Description"),
                stringList(request.path("SubnetIds")),
                region)));
        return Response.ok(response).build();
    }

    private Response describeSubnetGroups(JsonNode request) {
        List<SubnetGroup> groups = service.describeSubnetGroups(stringList(request.path("SubnetGroupNames")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("SubnetGroups");
        for (SubnetGroup group : groups) {
            list.add(toSubnetGroupNode(group));
        }
        return Response.ok(response).build();
    }

    private Response updateSubnetGroup(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SubnetGroup", toSubnetGroupNode(service.updateSubnetGroup(
                textOrNull(request, "SubnetGroupName"),
                textOrNull(request, "Description"),
                stringList(request.path("SubnetIds")),
                region)));
        return Response.ok(response).build();
    }

    private Response deleteSubnetGroup(JsonNode request) {
        SubnetGroup deleted = service.deleteSubnetGroup(textOrNull(request, "SubnetGroupName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DeletionMessage", "Subnet group " + deleted.getSubnetGroupName() + " has been deleted.");
        return Response.ok(response).build();
    }

    private Response createParameterGroup(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", toParameterGroupNode(service.createParameterGroup(
                textOrNull(request, "ParameterGroupName"), textOrNull(request, "Description"))));
        return Response.ok(response).build();
    }

    private Response describeParameterGroups(JsonNode request) {
        List<ParameterGroup> groups = service.describeParameterGroups(
                stringList(request.path("ParameterGroupNames")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ParameterGroups");
        for (ParameterGroup group : groups) {
            list.add(toParameterGroupNode(group));
        }
        return Response.ok(response).build();
    }

    private Response describeParameters(JsonNode request) {
        String groupName = textOrNull(request, "ParameterGroupName");
        String sourceFilter = textOrNull(request, "Source");
        Map<String, String> values = service.describeParameters(groupName);
        ParameterGroup group = service.describeParameterGroups(List.of(groupName)).get(0);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Parameters");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String defaultValue = DaxService.DEFAULT_PARAMETERS.get(entry.getKey());
            boolean overridden = defaultValue != null && !defaultValue.equals(entry.getValue());
            String source = overridden ? "user" : "system";
            if (sourceFilter != null && !sourceFilter.equalsIgnoreCase(source)) {
                continue;
            }
            list.add(toParameterNode(entry.getKey(), entry.getValue(), source, group.getParameters()));
        }
        return Response.ok(response).build();
    }

    private Response updateParameterGroup(JsonNode request) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (JsonNode node : request.path("ParameterNameValues")) {
            String parameterName = textOrNull(node, "ParameterName");
            String parameterValue = textOrNull(node, "ParameterValue");
            if (parameterName != null) {
                updates.put(parameterName, parameterValue);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParameterGroup", toParameterGroupNode(service.updateParameterGroup(
                textOrNull(request, "ParameterGroupName"), updates)));
        return Response.ok(response).build();
    }

    private Response deleteParameterGroup(JsonNode request) {
        String name = textOrNull(request, "ParameterGroupName");
        service.deleteParameterGroup(name);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DeletionMessage", "Parameter group " + name + " has been deleted.");
        return Response.ok(response).build();
    }

    private ObjectNode toClusterNode(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClusterName", cluster.getClusterName());
        if (cluster.getDescription() != null) {
            node.put("Description", cluster.getDescription());
        }
        if (cluster.getClusterArn() != null) {
            node.put("ClusterArn", cluster.getClusterArn());
        }
        int nodeCount = cluster.getNodes() == null ? 0 : cluster.getNodes().size();
        node.put("TotalNodes", nodeCount);
        node.put("ActiveNodes", nodeCount);
        node.put("NodeType", cluster.getNodeType());
        node.put("Status", cluster.getStatus());
        ObjectNode endpoint = node.putObject("ClusterDiscoveryEndpoint");
        endpoint.put("Address", cluster.getDiscoveryAddress());
        endpoint.put("Port", cluster.getDiscoveryPort());
        endpoint.put("URL", cluster.getDiscoveryUrl());
        if (cluster.getPreferredMaintenanceWindow() != null) {
            node.put("PreferredMaintenanceWindow", cluster.getPreferredMaintenanceWindow());
        }
        if (cluster.getNotificationTopicArn() != null) {
            ObjectNode notification = node.putObject("NotificationConfiguration");
            notification.put("TopicArn", cluster.getNotificationTopicArn());
            notification.put("TopicStatus", "active");
        }
        if (cluster.getSubnetGroupName() != null) {
            node.put("SubnetGroup", cluster.getSubnetGroupName());
        }
        ArrayNode securityGroups = node.putArray("SecurityGroups");
        for (String id : cluster.getSecurityGroupIds()) {
            ObjectNode sg = securityGroups.addObject();
            sg.put("SecurityGroupIdentifier", id);
            sg.put("Status", "active");
        }
        if (cluster.getIamRoleArn() != null) {
            node.put("IamRoleArn", cluster.getIamRoleArn());
        }
        if (cluster.getParameterGroupName() != null) {
            ObjectNode parameterGroup = node.putObject("ParameterGroup");
            parameterGroup.put("ParameterGroupName", cluster.getParameterGroupName());
            parameterGroup.put("ParameterApplyStatus", "in-sync");
        }
        node.putObject("SSEDescription").put("Status", cluster.isSseEnabled() ? "ENABLED" : "DISABLED");
        if (cluster.getClusterEndpointEncryptionType() != null) {
            node.put("ClusterEndpointEncryptionType", cluster.getClusterEndpointEncryptionType());
        }
        if (cluster.getNetworkType() != null) {
            node.put("NetworkType", cluster.getNetworkType());
        }
        ArrayNode nodes = node.putArray("Nodes");
        for (Node member : cluster.getNodes()) {
            ObjectNode n = nodes.addObject();
            n.put("NodeId", member.getNodeId());
            ObjectNode ep = n.putObject("Endpoint");
            ep.put("Address", member.getAddress());
            ep.put("Port", member.getPort());
            ep.put("URL", member.getUrl());
            n.put("NodeCreateTime", member.getNodeCreateTime());
            n.put("AvailabilityZone", member.getAvailabilityZone());
            n.put("NodeStatus", member.getNodeStatus());
            n.put("ParameterGroupStatus", member.getParameterGroupStatus());
        }
        return node;
    }

    private ObjectNode toSubnetGroupNode(SubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SubnetGroupName", group.getSubnetGroupName());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        if (group.getVpcId() != null) {
            node.put("VpcId", group.getVpcId());
        }
        ArrayNode subnets = node.putArray("Subnets");
        for (DaxSubnet subnet : group.getSubnets()) {
            ObjectNode s = subnets.addObject();
            s.put("SubnetIdentifier", subnet.getSubnetIdentifier());
            s.put("SubnetAvailabilityZone", subnet.getAvailabilityZone());
        }
        return node;
    }

    private ObjectNode toParameterGroupNode(ParameterGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ParameterGroupName", group.getParameterGroupName());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        return node;
    }

    private ObjectNode toParameterNode(String name, String value, String source,
                                       Map<String, String> unused) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ParameterName", name);
        node.put("ParameterType", "DEFAULT");
        node.put("ParameterValue", value);
        if ("query-ttl-millis".equals(name)) {
            node.put("Description", "TTL for cached query results, in milliseconds.");
        } else if ("record-ttl-millis".equals(name)) {
            node.put("Description", "TTL for cached item records, in milliseconds.");
        }
        node.put("Source", source);
        node.put("DataType", "integer");
        node.put("AllowedValues", "0-2147483647");
        node.put("IsModifiable", "TRUE");
        node.put("ChangeType", "IMMEDIATE");
        return node;
    }

    private ObjectNode tagListResponse(Map<String, String> tags) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return response;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (!item.isNull()) {
                    values.add(item.asText());
                }
            });
        }
        return values;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
