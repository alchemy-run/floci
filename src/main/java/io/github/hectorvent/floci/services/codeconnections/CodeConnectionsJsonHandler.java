package io.github.hectorvent.floci.services.codeconnections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsConnection;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsHost;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsRepositoryLink;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsSyncConfiguration;
import io.github.hectorvent.floci.services.codeconnections.model.CodeConnectionsVpcConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.0 handler for CodeConnections. Dispatched from {@code AwsJsonController}
 * under the {@code CodeConnections_20231201.} target prefix.
 */
@ApplicationScoped
public class CodeConnectionsJsonHandler {

    private static final String TARGET_PREFIX = "CodeConnections_20231201.";

    private final CodeConnectionsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeConnectionsJsonHandler(CodeConnectionsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateHost" -> createHost(body, region);
                case "GetHost" -> getHost(body);
                case "ListHosts" -> listHosts(body, region);
                case "UpdateHost" -> updateHost(body);
                case "DeleteHost" -> deleteHost(body);
                case "CreateConnection" -> createConnection(body, region);
                case "GetConnection" -> getConnection(body);
                case "ListConnections" -> listConnections(body, region);
                case "DeleteConnection" -> deleteConnection(body);
                case "CreateRepositoryLink" -> createRepositoryLink(body, region);
                case "GetRepositoryLink" -> getRepositoryLink(body);
                case "ListRepositoryLinks" -> listRepositoryLinks(body, region);
                case "UpdateRepositoryLink" -> updateRepositoryLink(body);
                case "DeleteRepositoryLink" -> deleteRepositoryLink(body);
                case "CreateSyncConfiguration" -> createSyncConfiguration(body, region);
                case "GetSyncConfiguration" -> getSyncConfiguration(body);
                case "ListSyncConfigurations" -> listSyncConfigurations(body, region);
                case "UpdateSyncConfiguration" -> updateSyncConfiguration(body, region);
                case "DeleteSyncConfiguration" -> deleteSyncConfiguration(body);
                case "TagResource" -> tagResource(body);
                case "UntagResource" -> untagResource(body);
                case "ListTagsForResource" -> listTagsForResource(body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response createHost(JsonNode request, String region) {
        CodeConnectionsHost host = service.createHost(
                region,
                textOrNull(request, "Name"),
                textOrNull(request, "ProviderType"),
                textOrNull(request, "ProviderEndpoint"),
                parseVpc(request.get("VpcConfiguration")),
                parseTags(request.path("Tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("HostArn", host.getHostArn());
        response.set("Tags", tagsArray(host.getTags()));
        return Response.ok(response).build();
    }

    private Response getHost(JsonNode request) {
        CodeConnectionsHost host = service.getHost(textOrNull(request, "HostArn"));
        return Response.ok(toGetHost(host)).build();
    }

    private Response listHosts(JsonNode request, String region) {
        CodeConnectionsService.Page<CodeConnectionsHost> page = service.listHosts(
                region, textOrNull(request, "NextToken"), integerOrNull(request, "MaxResults"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Hosts");
        for (CodeConnectionsHost host : page.items()) {
            list.add(toListHost(host));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response updateHost(JsonNode request) {
        service.updateHost(
                textOrNull(request, "HostArn"),
                textOrNull(request, "ProviderEndpoint"),
                request.has("VpcConfiguration") ? parseVpc(request.get("VpcConfiguration")) : null);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response deleteHost(JsonNode request) {
        service.deleteHost(textOrNull(request, "HostArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response createConnection(JsonNode request, String region) {
        CodeConnectionsConnection connection = service.createConnection(
                region,
                textOrNull(request, "ConnectionName"),
                textOrNull(request, "ProviderType"),
                textOrNull(request, "HostArn"),
                parseTags(request.path("Tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ConnectionArn", connection.getConnectionArn());
        response.set("Tags", tagsArray(connection.getTags()));
        return Response.ok(response).build();
    }

    private Response getConnection(JsonNode request) {
        CodeConnectionsConnection connection = service.getConnection(textOrNull(request, "ConnectionArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Connection", toConnection(connection));
        return Response.ok(response).build();
    }

    private Response listConnections(JsonNode request, String region) {
        CodeConnectionsService.Page<CodeConnectionsConnection> page = service.listConnections(
                region,
                textOrNull(request, "ProviderTypeFilter"),
                textOrNull(request, "HostArnFilter"),
                textOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Connections");
        for (CodeConnectionsConnection connection : page.items()) {
            list.add(toConnection(connection));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response deleteConnection(JsonNode request) {
        service.deleteConnection(textOrNull(request, "ConnectionArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response createRepositoryLink(JsonNode request, String region) {
        CodeConnectionsRepositoryLink link = service.createRepositoryLink(
                region,
                textOrNull(request, "ConnectionArn"),
                textOrNull(request, "OwnerId"),
                textOrNull(request, "RepositoryName"),
                textOrNull(request, "EncryptionKeyArn"),
                parseTags(request.path("Tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RepositoryLinkInfo", toRepositoryLink(link));
        return Response.ok(response).build();
    }

    private Response getRepositoryLink(JsonNode request) {
        CodeConnectionsRepositoryLink link = service.getRepositoryLink(textOrNull(request, "RepositoryLinkId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RepositoryLinkInfo", toRepositoryLink(link));
        return Response.ok(response).build();
    }

    private Response listRepositoryLinks(JsonNode request, String region) {
        CodeConnectionsService.Page<CodeConnectionsRepositoryLink> page = service.listRepositoryLinks(
                region, textOrNull(request, "NextToken"), integerOrNull(request, "MaxResults"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("RepositoryLinks");
        for (CodeConnectionsRepositoryLink link : page.items()) {
            list.add(toRepositoryLink(link));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response updateRepositoryLink(JsonNode request) {
        CodeConnectionsRepositoryLink link = service.updateRepositoryLink(
                textOrNull(request, "RepositoryLinkId"),
                textOrNull(request, "ConnectionArn"),
                textOrNull(request, "EncryptionKeyArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RepositoryLinkInfo", toRepositoryLink(link));
        return Response.ok(response).build();
    }

    private Response deleteRepositoryLink(JsonNode request) {
        service.deleteRepositoryLink(textOrNull(request, "RepositoryLinkId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response createSyncConfiguration(JsonNode request, String region) {
        CodeConnectionsSyncConfiguration config = service.createSyncConfiguration(
                region,
                textOrNull(request, "Branch"),
                textOrNull(request, "ConfigFile"),
                textOrNull(request, "RepositoryLinkId"),
                textOrNull(request, "ResourceName"),
                textOrNull(request, "RoleArn"),
                textOrNull(request, "SyncType"),
                textOrNull(request, "PublishDeploymentStatus"),
                textOrNull(request, "TriggerResourceUpdateOn"),
                textOrNull(request, "PullRequestComment"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SyncConfiguration", toSync(config));
        return Response.ok(response).build();
    }

    private Response getSyncConfiguration(JsonNode request) {
        CodeConnectionsSyncConfiguration config = service.getSyncConfiguration(
                textOrNull(request, "SyncType"), textOrNull(request, "ResourceName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SyncConfiguration", toSync(config));
        return Response.ok(response).build();
    }

    private Response listSyncConfigurations(JsonNode request, String region) {
        CodeConnectionsService.Page<CodeConnectionsSyncConfiguration> page = service.listSyncConfigurations(
                region,
                textOrNull(request, "RepositoryLinkId"),
                textOrNull(request, "SyncType"),
                textOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("SyncConfigurations");
        for (CodeConnectionsSyncConfiguration config : page.items()) {
            list.add(toSync(config));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response updateSyncConfiguration(JsonNode request, String region) {
        CodeConnectionsSyncConfiguration config = service.updateSyncConfiguration(
                region,
                textOrNull(request, "SyncType"),
                textOrNull(request, "ResourceName"),
                textOrNull(request, "Branch"),
                textOrNull(request, "ConfigFile"),
                textOrNull(request, "RepositoryLinkId"),
                textOrNull(request, "RoleArn"),
                textOrNull(request, "PublishDeploymentStatus"),
                textOrNull(request, "TriggerResourceUpdateOn"),
                textOrNull(request, "PullRequestComment"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SyncConfiguration", toSync(config));
        return Response.ok(response).build();
    }

    private Response deleteSyncConfiguration(JsonNode request) {
        service.deleteSyncConfiguration(textOrNull(request, "SyncType"), textOrNull(request, "ResourceName"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response tagResource(JsonNode request) {
        service.tagResource(textOrNull(request, "ResourceArn"), parseTags(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode request) {
        service.untagResource(textOrNull(request, "ResourceArn"), stringList(request.path("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(service.listTags(textOrNull(request, "ResourceArn"))));
        return Response.ok(response).build();
    }

    private ObjectNode toGetHost(CodeConnectionsHost host) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", host.getName());
        node.put("Status", host.getStatus());
        node.put("ProviderType", host.getProviderType());
        node.put("ProviderEndpoint", host.getProviderEndpoint());
        putVpc(node, host.getVpcConfiguration());
        return node;
    }

    private ObjectNode toListHost(CodeConnectionsHost host) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", host.getName());
        node.put("HostArn", host.getHostArn());
        node.put("ProviderType", host.getProviderType());
        node.put("ProviderEndpoint", host.getProviderEndpoint());
        node.put("Status", host.getStatus());
        if (host.getStatusMessage() != null) {
            node.put("StatusMessage", host.getStatusMessage());
        }
        putVpc(node, host.getVpcConfiguration());
        return node;
    }

    private ObjectNode toConnection(CodeConnectionsConnection connection) {
        ObjectNode node = objectMapper.createObjectNode();
        putOptional(node, "ConnectionName", connection.getConnectionName());
        putOptional(node, "ConnectionArn", connection.getConnectionArn());
        putOptional(node, "ProviderType", connection.getProviderType());
        putOptional(node, "OwnerAccountId", connection.getOwnerAccountId());
        putOptional(node, "ConnectionStatus", connection.getConnectionStatus());
        putOptional(node, "HostArn", connection.getHostArn());
        return node;
    }

    private ObjectNode toRepositoryLink(CodeConnectionsRepositoryLink link) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ConnectionArn", link.getConnectionArn());
        putOptional(node, "EncryptionKeyArn", link.getEncryptionKeyArn());
        node.put("OwnerId", link.getOwnerId());
        node.put("ProviderType", link.getProviderType());
        node.put("RepositoryLinkArn", link.getRepositoryLinkArn());
        node.put("RepositoryLinkId", link.getRepositoryLinkId());
        node.put("RepositoryName", link.getRepositoryName());
        return node;
    }

    private ObjectNode toSync(CodeConnectionsSyncConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Branch", config.getBranch());
        putOptional(node, "ConfigFile", config.getConfigFile());
        node.put("OwnerId", config.getOwnerId());
        node.put("ProviderType", config.getProviderType());
        node.put("RepositoryLinkId", config.getRepositoryLinkId());
        node.put("RepositoryName", config.getRepositoryName());
        node.put("ResourceName", config.getResourceName());
        node.put("RoleArn", config.getRoleArn());
        node.put("SyncType", config.getSyncType());
        putOptional(node, "PublishDeploymentStatus", config.getPublishDeploymentStatus());
        putOptional(node, "TriggerResourceUpdateOn", config.getTriggerResourceUpdateOn());
        putOptional(node, "PullRequestComment", config.getPullRequestComment());
        return node;
    }

    private void putVpc(ObjectNode parent, CodeConnectionsVpcConfiguration vpc) {
        if (vpc == null || vpc.getVpcId() == null || vpc.getVpcId().isBlank()) {
            return;
        }
        ObjectNode node = parent.putObject("VpcConfiguration");
        node.put("VpcId", vpc.getVpcId());
        ArrayNode subnets = node.putArray("SubnetIds");
        for (String subnetId : vpc.getSubnetIds()) {
            subnets.add(subnetId);
        }
        ArrayNode groups = node.putArray("SecurityGroupIds");
        for (String groupId : vpc.getSecurityGroupIds()) {
            groups.add(groupId);
        }
        if (vpc.getTlsCertificate() != null) {
            node.put("TlsCertificate", vpc.getTlsCertificate());
        }
    }

    private void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags == null) {
            return array;
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = array.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() != null ? entry.getValue() : "");
        }
        return array;
    }

    private static CodeConnectionsVpcConfiguration parseVpc(JsonNode vpc) {
        if (vpc == null || vpc.isNull() || vpc.isMissingNode() || !vpc.isObject()) {
            return null;
        }
        CodeConnectionsVpcConfiguration config = new CodeConnectionsVpcConfiguration();
        config.setVpcId(textOrNull(vpc, "VpcId"));
        config.setSubnetIds(stringList(vpc.path("SubnetIds")));
        config.setSecurityGroupIds(stringList(vpc.path("SecurityGroupIds")));
        config.setTlsCertificate(textOrNull(vpc, "TlsCertificate"));
        return config;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String key = textOrNull(tag, "Key");
            if (key == null) {
                key = textOrNull(tag, "key");
            }
            String value = textOrNull(tag, "Value");
            if (value == null) {
                value = textOrNull(tag, "value");
            }
            if (key != null) {
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
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

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isNumber()) {
            return null;
        }
        return node.get(field).asInt();
    }
}
