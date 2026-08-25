package io.github.hectorvent.floci.services.cloudhsmv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.cloudhsmv2.model.CloudHsm;
import io.github.hectorvent.floci.services.cloudhsmv2.model.CloudHsmCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for CloudHSM V2. Dispatched from {@code AwsJson11Controller}
 * under the {@code BaldrApiService.} target prefix.
 */
@ApplicationScoped
public class CloudHsmV2JsonHandler {

    private final CloudHsmV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudHsmV2JsonHandler(CloudHsmV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "DescribeClusters" -> describeClusters(body, region);
                case "CreateCluster" -> createCluster(body, region);
                case "DeleteCluster" -> deleteCluster(body, region);
                case "ModifyCluster" -> modifyCluster(body, region);
                case "CreateHsm" -> createHsm(body, region);
                case "DeleteHsm" -> deleteHsm(body, region);
                case "ListTags" -> listTags(body, region);
                case "TagResource" -> tagResource(body, region);
                case "UntagResource" -> untagResource(body, region);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("BaldrApiService." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response describeClusters(JsonNode request, String region) {
        Map<String, List<String>> filters = readFilters(request.path("Filters"));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        String nextToken = textOrNull(request, "NextToken");
        List<CloudHsmCluster> clusters = service.describeClusters(region, filters, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Clusters");
        for (CloudHsmCluster cluster : clusters) {
            list.add(toClusterNode(cluster));
        }
        String token = service.nextToken(region, filters, nextToken, maxResults);
        if (token != null) {
            response.put("NextToken", token);
        }
        return Response.ok(response).build();
    }

    private Response createCluster(JsonNode request, String region) {
        CloudHsmCluster cluster = service.createCluster(
                region,
                textOrNull(request, "HsmType"),
                stringList(request.path("SubnetIds")),
                textOrNull(request, "SourceBackupId"),
                textOrNull(request, "NetworkType"),
                textOrNull(request, "Mode"),
                textOrNull(request.path("BackupRetentionPolicy"), "Type"),
                textOrNull(request.path("BackupRetentionPolicy"), "Value"),
                readTagMap(request.path("TagList")));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(cluster));
        return Response.ok(response).build();
    }

    private Response deleteCluster(JsonNode request, String region) {
        CloudHsmCluster cluster = service.deleteCluster(region, textOrNull(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(cluster));
        return Response.ok(response).build();
    }

    private Response modifyCluster(JsonNode request, String region) {
        CloudHsmCluster cluster = service.modifyCluster(
                region,
                textOrNull(request, "ClusterId"),
                textOrNull(request, "HsmType"),
                textOrNull(request.path("BackupRetentionPolicy"), "Type"),
                textOrNull(request.path("BackupRetentionPolicy"), "Value"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", toClusterNode(cluster));
        return Response.ok(response).build();
    }

    private Response createHsm(JsonNode request, String region) {
        CloudHsm hsm = service.createHsm(
                region,
                textOrNull(request, "ClusterId"),
                textOrNull(request, "AvailabilityZone"),
                textOrNull(request, "IpAddress"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Hsm", toHsmNode(hsm));
        return Response.ok(response).build();
    }

    private Response deleteHsm(JsonNode request, String region) {
        String hsmId = service.deleteHsm(
                region,
                textOrNull(request, "ClusterId"),
                textOrNull(request, "HsmId"),
                textOrNull(request, "EniId"),
                textOrNull(request, "EniIp"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("HsmId", hsmId);
        return Response.ok(response).build();
    }

    private Response listTags(JsonNode request, String region) {
        Map<String, String> tags = service.listTags(region, textOrNull(request, "ResourceId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TagList", tagsArray(tags));
        return Response.ok(response).build();
    }

    private Response tagResource(JsonNode request, String region) {
        service.tagResource(region, textOrNull(request, "ResourceId"), readTagMap(request.path("TagList")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode request, String region) {
        service.untagResource(region, textOrNull(request, "ResourceId"), stringList(request.path("TagKeyList")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toClusterNode(CloudHsmCluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClusterId", cluster.getClusterId());
        node.put("HsmType", cluster.getHsmType());
        node.put("State", cluster.getState());
        if (cluster.getStateMessage() != null) {
            node.put("StateMessage", cluster.getStateMessage());
        }
        if (cluster.getVpcId() != null) {
            node.put("VpcId", cluster.getVpcId());
        }
        if (cluster.getSecurityGroup() != null) {
            node.put("SecurityGroup", cluster.getSecurityGroup());
        }
        if (cluster.getSourceBackupId() != null) {
            node.put("SourceBackupId", cluster.getSourceBackupId());
        }
        if (cluster.getNetworkType() != null) {
            node.put("NetworkType", cluster.getNetworkType());
        }
        if (cluster.getMode() != null) {
            node.put("Mode", cluster.getMode());
        }
        if (cluster.getBackupPolicy() != null) {
            node.put("BackupPolicy", cluster.getBackupPolicy());
        }
        if (cluster.getBackupRetentionValue() != null) {
            ObjectNode policy = node.putObject("BackupRetentionPolicy");
            policy.put("Type", cluster.getBackupRetentionType() != null ? cluster.getBackupRetentionType() : "DAYS");
            policy.put("Value", cluster.getBackupRetentionValue());
        }
        node.put("CreateTimestamp", cluster.getCreateTimestamp());
        ObjectNode mapping = node.putObject("SubnetMapping");
        for (Map.Entry<String, String> entry : cluster.getSubnetMapping().entrySet()) {
            mapping.put(entry.getKey(), entry.getValue());
        }
        ArrayNode hsms = node.putArray("Hsms");
        for (CloudHsm hsm : cluster.getHsms()) {
            hsms.add(toHsmNode(hsm));
        }
        node.set("TagList", tagsArray(cluster.getTags()));
        if (cluster.getClusterCsr() != null && "UNINITIALIZED".equals(cluster.getState())) {
            ObjectNode certificates = node.putObject("Certificates");
            certificates.put("ClusterCsr", cluster.getClusterCsr());
        }
        return node;
    }

    private ObjectNode toHsmNode(CloudHsm hsm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("HsmId", hsm.getHsmId());
        if (hsm.getClusterId() != null) {
            node.put("ClusterId", hsm.getClusterId());
        }
        if (hsm.getAvailabilityZone() != null) {
            node.put("AvailabilityZone", hsm.getAvailabilityZone());
        }
        if (hsm.getSubnetId() != null) {
            node.put("SubnetId", hsm.getSubnetId());
        }
        if (hsm.getEniId() != null) {
            node.put("EniId", hsm.getEniId());
        }
        if (hsm.getEniIp() != null) {
            node.put("EniIp", hsm.getEniIp());
        }
        if (hsm.getHsmType() != null) {
            node.put("HsmType", hsm.getHsmType());
        }
        if (hsm.getState() != null) {
            node.put("State", hsm.getState());
        }
        if (hsm.getStateMessage() != null) {
            node.put("StateMessage", hsm.getStateMessage());
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags == null) {
            return array;
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() != null ? entry.getValue() : "");
            array.add(tag);
        }
        return array;
    }

    private static Map<String, String> readTagMap(JsonNode tagList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagList == null || !tagList.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagList) {
            String key = textOrNull(tag, "Key");
            if (key != null) {
                String value = textOrNull(tag, "Value");
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static Map<String, List<String>> readFilters(JsonNode filters) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (filters == null || !filters.isObject()) {
            return result;
        }
        filters.fields().forEachRemaining(entry -> {
            List<String> values = stringList(entry.getValue());
            if (!values.isEmpty()) {
                result.put(entry.getKey(), values);
            }
        });
        return result;
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
}
