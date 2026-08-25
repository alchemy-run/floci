package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apprunner.model.VpcConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * App Runner JSON 1.0 VPC connector lifecycle and connector-scoped tags.
 *
 * <p>Deleted connectors are retained as {@code INACTIVE} so
 * {@code DescribeVpcConnector} matches AWS. Creating a previously-deleted
 * name starts a new revision.
 */
@ApplicationScoped
public class AppRunnerVpcConnectorService implements Resettable {

    private static final Logger LOG = Logger.getLogger(AppRunnerVpcConnectorService.class);

    static final String SERVICE = "apprunner";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 20;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_]{3,39}");

    private final StorageBackend<String, VpcConnector> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AppRunnerVpcConnectorService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this(storageFactory.create("apprunner", "apprunner-vpc-connectors.json",
                new TypeReference<Map<String, VpcConnector>>() {
                }), regionResolver, mapper);
    }

    AppRunnerVpcConnectorService(
            StorageBackend<String, VpcConnector> store, RegionResolver regionResolver, ObjectMapper mapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @Override
    public void clear() {
        store.clear();
    }

    public JsonNode handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? mapper.createObjectNode()
                : request;
        if (!body.isObject()) {
            throw invalidRequest("Request body must be a JSON object.");
        }
        return switch (action) {
            case "CreateVpcConnector" -> wrap(createVpcConnector(body, region));
            case "DescribeVpcConnector" -> wrap(describeVpcConnector(body, region));
            case "DeleteVpcConnector" -> wrap(deleteVpcConnector(body, region));
            case "ListVpcConnectors" -> listVpcConnectors(body, region);
            case "ListTagsForResource" -> listTagsForResource(body, region);
            case "TagResource" -> tagResource(body, region);
            case "UntagResource" -> untagResource(body, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: AppRunner." + action, 400);
        };
    }

    static boolean isVpcConnectorAction(String action, JsonNode request) {
        return switch (action) {
            case "CreateVpcConnector", "DescribeVpcConnector", "DeleteVpcConnector", "ListVpcConnectors" -> true;
            case "ListTagsForResource", "TagResource", "UntagResource" -> {
                JsonNode arn = request == null ? null : request.get("ResourceArn");
                yield arn != null && arn.isTextual() && arn.asText().contains(":vpcconnector/");
            }
            default -> false;
        };
    }

    private VpcConnector createVpcConnector(JsonNode request, String region) {
        String name = requireText(request, "VpcConnectorName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "VpcConnectorName must be 4-40 characters matching [A-Za-z0-9][A-Za-z0-9-_]{3,39}.");
        }
        List<String> subnets = requireStringList(request, "Subnets");
        if (subnets.isEmpty()) {
            throw invalidRequest("Subnets must contain at least one subnet ID.");
        }
        List<String> securityGroups = optionalStringList(request, "SecurityGroups");
        Map<String, String> tags = readTags(request);

        int nextRevision = 1;
        for (VpcConnector existing : connectorsNamed(region, name)) {
            if (existing.isActive()) {
                throw invalidRequest("A VPC connector named " + name + " already exists.");
            }
            nextRevision = Math.max(nextRevision, existing.getVpcConnectorRevision() + 1);
        }

        String account = regionResolver.getAccountId();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String arn = AwsArnUtils.Arn.of(SERVICE, region, account,
                "vpcconnector/" + name + "/" + nextRevision + "/" + id).toString();

        VpcConnector connector = new VpcConnector();
        connector.setVpcConnectorName(name);
        connector.setVpcConnectorArn(arn);
        connector.setVpcConnectorRevision(nextRevision);
        connector.setSubnets(subnets);
        connector.setSecurityGroups(securityGroups);
        connector.setStatus(STATUS_ACTIVE);
        connector.setCreatedAt(Instant.now().getEpochSecond());
        connector.setRegion(region);
        connector.setTags(tags);
        store.put(arn, connector);
        LOG.infov("Created App Runner VPC connector {0}", arn);
        return connector;
    }

    private VpcConnector describeVpcConnector(JsonNode request, String region) {
        return requireConnector(requireText(request, "VpcConnectorArn"), region);
    }

    private VpcConnector deleteVpcConnector(JsonNode request, String region) {
        VpcConnector connector = requireConnector(requireText(request, "VpcConnectorArn"), region);
        if (connector.isActive()) {
            connector.setStatus(STATUS_INACTIVE);
            connector.setDeletedAt(Instant.now().getEpochSecond());
            store.put(connector.getVpcConnectorArn(), connector);
            LOG.infov("Deleted App Runner VPC connector {0}", connector.getVpcConnectorArn());
        }
        return connector;
    }

    private JsonNode listVpcConnectors(JsonNode request, String region) {
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);

        List<VpcConnector> all = new ArrayList<>();
        for (VpcConnector connector : store.scan(key -> true)) {
            if (region.equals(connector.getRegion())) {
                all.add(connector);
            }
        }
        all.sort(Comparator.comparing(VpcConnector::getVpcConnectorName)
                .thenComparingInt(VpcConnector::getVpcConnectorRevision));

        int from = Math.min(offset, all.size());
        int to = Math.min(from + maxResults, all.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode connectors = response.putArray("VpcConnectors");
        for (VpcConnector connector : all.subList(from, to)) {
            connectors.add(toJson(connector));
        }
        if (to < all.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private JsonNode listTagsForResource(JsonNode request, String region) {
        VpcConnector connector = requireConnector(requireText(request, "ResourceArn"), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", tagsNode(connector));
        return response;
    }

    private JsonNode tagResource(JsonNode request, String region) {
        VpcConnector connector = requireConnector(requireText(request, "ResourceArn"), region);
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw invalidRequest("Tags is required.");
        }
        Map<String, String> tags = new LinkedHashMap<>(connector.getTags());
        tags.putAll(readTagList(tagsNode));
        connector.setTags(tags);
        store.put(connector.getVpcConnectorArn(), connector);
        return mapper.createObjectNode();
    }

    private JsonNode untagResource(JsonNode request, String region) {
        VpcConnector connector = requireConnector(requireText(request, "ResourceArn"), region);
        JsonNode keysNode = request.get("TagKeys");
        if (keysNode == null || !keysNode.isArray()) {
            throw invalidRequest("TagKeys is required.");
        }
        Map<String, String> tags = new LinkedHashMap<>(connector.getTags());
        for (JsonNode key : keysNode) {
            if (key != null && key.isTextual()) {
                tags.remove(key.asText());
            }
        }
        connector.setTags(tags);
        store.put(connector.getVpcConnectorArn(), connector);
        return mapper.createObjectNode();
    }

    private ObjectNode wrap(VpcConnector connector) {
        ObjectNode response = mapper.createObjectNode();
        response.set("VpcConnector", toJson(connector));
        return response;
    }

    private ObjectNode toJson(VpcConnector connector) {
        ObjectNode node = mapper.createObjectNode();
        node.put("VpcConnectorName", connector.getVpcConnectorName());
        node.put("VpcConnectorArn", connector.getVpcConnectorArn());
        node.put("VpcConnectorRevision", connector.getVpcConnectorRevision());
        ArrayNode subnets = node.putArray("Subnets");
        for (String subnet : connector.getSubnets()) {
            subnets.add(subnet);
        }
        ArrayNode securityGroups = node.putArray("SecurityGroups");
        for (String group : connector.getSecurityGroups()) {
            securityGroups.add(group);
        }
        node.put("Status", connector.getStatus());
        node.put("CreatedAt", connector.getCreatedAt());
        if (connector.getDeletedAt() != null) {
            node.put("DeletedAt", connector.getDeletedAt());
        }
        return node;
    }

    private ArrayNode tagsNode(VpcConnector connector) {
        ArrayNode tags = mapper.createArrayNode();
        for (Map.Entry<String, String> entry : connector.getTags().entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return tags;
    }

    private VpcConnector requireConnector(String arn, String region) {
        VpcConnector connector = store.get(arn).orElse(null);
        if (connector == null || (connector.getRegion() != null && !region.equals(connector.getRegion()))) {
            throw notFound(arn);
        }
        return connector;
    }

    private List<VpcConnector> connectorsNamed(String region, String name) {
        List<VpcConnector> matches = new ArrayList<>();
        for (VpcConnector connector : store.scan(key -> true)) {
            if (region.equals(connector.getRegion()) && name.equals(connector.getVpcConnectorName())) {
                matches.add(connector);
            }
        }
        return matches;
    }

    private Map<String, String> readTags(JsonNode request) {
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isArray()) {
            throw invalidRequest("Tags must be a list.");
        }
        return readTagList(tagsNode);
    }

    private Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = tag.path("Value").isMissingNode() || tag.path("Value").isNull()
                    ? ""
                    : tag.path("Value").asText();
            tags.put(key, value);
        }
        return tags;
    }

    private int readMaxResults(JsonNode request) {
        JsonNode value = request.get("MaxResults");
        if (value == null || value.isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        int maxResults = value.asInt();
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw invalidRequest("MaxResults must be an integer between 1 and 20.");
        }
        return maxResults;
    }

    private int readOffset(JsonNode request) {
        String token = optionalText(request, "NextToken");
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw invalidRequest("Invalid NextToken.");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw invalidRequest("Invalid NextToken.");
        }
    }

    private String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private List<String> requireStringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            throw invalidRequest(field + " is required.");
        }
        return toStringList(node);
    }

    private List<String> optionalStringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        return toStringList(node);
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Resource with the specified ARN (" + arn + ") is not found.", 400);
    }
}
