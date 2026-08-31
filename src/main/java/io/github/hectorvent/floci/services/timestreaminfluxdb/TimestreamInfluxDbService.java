package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Amazon Timestream for InfluxDB (awsJson1_0, {@code AmazonTimestreamInfluxDB.*}).
 * In-memory DB instances; create converges to {@code AVAILABLE} immediately.
 */
@ApplicationScoped
public class TimestreamInfluxDbService implements Resettable {

    static final String SERVICE = "timestream-influxdb";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9]{3,64}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*");
    private static final int NAME_MIN = 3;
    private static final int NAME_MAX = 40;

    static final class DbInstance {
        String id;
        String name;
        String arn;
        String region;
        String status;
        String endpoint;
        int port;
        String networkType;
        String dbInstanceType;
        String dbStorageType;
        int allocatedStorage;
        String deploymentType;
        List<String> vpcSubnetIds = new ArrayList<>();
        boolean publiclyAccessible;
        List<String> vpcSecurityGroupIds = new ArrayList<>();
        String dbParameterGroupIdentifier;
        String availabilityZone;
        JsonNode logDeliveryConfiguration;
        String influxAuthParametersSecretArn;
        String instanceMode;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, DbInstance> instances = new ConcurrentHashMap<>();

    @Inject
    public TimestreamInfluxDbService(RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    TimestreamInfluxDbService() {
        this.regionResolver = null;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void clear() {
        instances.clear();
    }

    public synchronized ObjectNode createDbInstance(JsonNode request, String region) {
        String name = requireName(textOrNull(request, "name"));
        if (findByName(name).isPresent()) {
            throw conflict(name, "A DB instance named " + name + " already exists.");
        }
        String dbInstanceType = requireText(request, "dbInstanceType");
        int allocatedStorage = requireInt(request, "allocatedStorage");
        requireText(request, "password");
        List<String> subnetIds = requireStringList(request, "vpcSubnetIds");
        List<String> securityGroupIds = requireStringList(request, "vpcSecurityGroupIds");

        String resolvedRegion = resolvedRegion(region);
        String id = UUID.randomUUID().toString().replace("-", "");
        DbInstance instance = new DbInstance();
        instance.id = id;
        instance.name = name;
        instance.arn = instanceArn(resolvedRegion, id);
        instance.region = resolvedRegion;
        instance.status = "AVAILABLE";
        instance.endpoint = id + ".db.influxdb.local";
        instance.port = request.hasNonNull("port") ? request.get("port").asInt() : 8086;
        instance.networkType = textOrDefault(request, "networkType", "IPV4");
        instance.dbInstanceType = dbInstanceType;
        instance.dbStorageType = textOrDefault(request, "dbStorageType", "InfluxIOIncludedT1");
        instance.allocatedStorage = allocatedStorage;
        instance.deploymentType = textOrDefault(request, "deploymentType", "SINGLE_AZ");
        instance.vpcSubnetIds.addAll(subnetIds);
        instance.publiclyAccessible = request.hasNonNull("publiclyAccessible")
                && request.get("publiclyAccessible").asBoolean();
        instance.vpcSecurityGroupIds.addAll(securityGroupIds);
        instance.dbParameterGroupIdentifier = textOrNull(request, "dbParameterGroupIdentifier");
        instance.availabilityZone = resolvedRegion + "a";
        instance.logDeliveryConfiguration = copyNode(request.get("logDeliveryConfiguration"));
        instance.influxAuthParametersSecretArn = AwsArnUtils.Arn.of(
                "secretsmanager",
                resolvedRegion,
                accountId(),
                "secret:timestream-influxdb/" + name).toString();
        instance.instanceMode = "PRIMARY";
        instance.tags.putAll(readTagMap(request.get("tags")));
        instances.put(id, instance);
        return instanceNode(instance);
    }

    public synchronized ObjectNode getDbInstance(JsonNode request) {
        return instanceNode(requireInstance(requireIdentifier(request)));
    }

    public synchronized ObjectNode updateDbInstance(JsonNode request) {
        DbInstance instance = requireInstance(requireIdentifier(request));
        if (request.hasNonNull("dbInstanceType")) {
            instance.dbInstanceType = request.get("dbInstanceType").asText();
        }
        if (request.hasNonNull("allocatedStorage")) {
            instance.allocatedStorage = request.get("allocatedStorage").asInt();
        }
        if (request.hasNonNull("port")) {
            instance.port = request.get("port").asInt();
        }
        if (request.hasNonNull("dbParameterGroupIdentifier")) {
            instance.dbParameterGroupIdentifier = request.get("dbParameterGroupIdentifier").asText();
        }
        if (request.has("logDeliveryConfiguration") && !request.get("logDeliveryConfiguration").isNull()) {
            instance.logDeliveryConfiguration = copyNode(request.get("logDeliveryConfiguration"));
        }
        if (request.hasNonNull("deploymentType")) {
            instance.deploymentType = request.get("deploymentType").asText();
        }
        if (request.hasNonNull("dbStorageType")) {
            instance.dbStorageType = request.get("dbStorageType").asText();
        }
        instance.status = "AVAILABLE";
        return instanceNode(instance);
    }

    public synchronized ObjectNode deleteDbInstance(JsonNode request) {
        DbInstance instance = requireInstance(requireIdentifier(request));
        ObjectNode snapshot = instanceNode(instance);
        snapshot.put("status", "DELETED");
        instances.remove(instance.id);
        return snapshot;
    }

    public synchronized ObjectNode listDbInstances(JsonNode request) {
        List<DbInstance> all = new ArrayList<>(instances.values());
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        int offset = 0;
        String nextToken = textOrNull(request, "nextToken");
        if (nextToken != null) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw validation("Invalid nextToken.");
            }
            if (offset < 0) {
                offset = 0;
            }
        }
        int maxResults = request.hasNonNull("maxResults") ? request.get("maxResults").asInt() : all.size();
        if (maxResults < 1) {
            maxResults = all.size();
        }
        int end = Math.min(all.size(), offset + maxResults);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode items = resp.putArray("items");
        for (int i = offset; i < end; i++) {
            items.add(instanceSummary(all.get(i)));
        }
        if (end < all.size()) {
            resp.put("nextToken", Integer.toString(end));
        }
        return resp;
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        DbInstance instance = requireInstanceByArn(requireText(request, "resourceArn"));
        instance.tags.putAll(readTagMap(request.get("tags")));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        DbInstance instance = requireInstanceByArn(requireText(request, "resourceArn"));
        JsonNode keys = request.get("tagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                instance.tags.remove(key.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode request) {
        DbInstance instance = requireInstanceByArn(requireText(request, "resourceArn"));
        ObjectNode resp = objectMapper.createObjectNode();
        ObjectNode tags = resp.putObject("tags");
        instance.tags.forEach(tags::put);
        return resp;
    }

    private java.util.Optional<DbInstance> findByName(String name) {
        return instances.values().stream().filter(instance -> instance.name.equals(name)).findFirst();
    }

    private DbInstance requireInstance(String identifier) {
        DbInstance instance = instances.get(identifier);
        if (instance == null) {
            throw notFound(identifier);
        }
        return instance;
    }

    private DbInstance requireInstanceByArn(String arn) {
        return instances.values().stream()
                .filter(instance -> instance.arn.equals(arn))
                .findFirst()
                .orElseThrow(() -> notFound(arn));
    }

    private ObjectNode instanceNode(DbInstance instance) {
        ObjectNode node = instanceSummary(instance);
        putStringList(node, "vpcSubnetIds", instance.vpcSubnetIds);
        node.put("publiclyAccessible", instance.publiclyAccessible);
        putStringList(node, "vpcSecurityGroupIds", instance.vpcSecurityGroupIds);
        if (instance.dbParameterGroupIdentifier != null) {
            node.put("dbParameterGroupIdentifier", instance.dbParameterGroupIdentifier);
        }
        if (instance.availabilityZone != null) {
            node.put("availabilityZone", instance.availabilityZone);
        }
        if (instance.logDeliveryConfiguration != null) {
            node.set("logDeliveryConfiguration", instance.logDeliveryConfiguration);
        }
        if (instance.influxAuthParametersSecretArn != null) {
            node.put("influxAuthParametersSecretArn", instance.influxAuthParametersSecretArn);
        }
        if (instance.instanceMode != null) {
            node.put("instanceMode", instance.instanceMode);
            ArrayNode modes = node.putArray("instanceModes");
            modes.add(instance.instanceMode);
        }
        return node;
    }

    private ObjectNode instanceSummary(DbInstance instance) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", instance.id);
        node.put("name", instance.name);
        node.put("arn", instance.arn);
        node.put("status", instance.status);
        node.put("endpoint", instance.endpoint);
        node.put("port", instance.port);
        node.put("networkType", instance.networkType);
        node.put("dbInstanceType", instance.dbInstanceType);
        node.put("dbStorageType", instance.dbStorageType);
        node.put("allocatedStorage", instance.allocatedStorage);
        node.put("deploymentType", instance.deploymentType);
        return node;
    }

    private static void putStringList(ObjectNode node, String field, List<String> values) {
        ArrayNode array = node.putArray(field);
        for (String value : values) {
            array.add(value);
        }
    }

    private String instanceArn(String region, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId(), "db-instance/" + id).toString();
    }

    private String resolvedRegion(String region) {
        if (region != null && !region.isBlank()) {
            return region;
        }
        return regionResolver != null ? regionResolver.getDefaultRegion() : "us-east-1";
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private JsonNode copyNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    static String requireIdentifier(JsonNode request) {
        String identifier = textOrNull(request, "identifier");
        if (identifier == null) {
            throw validation("1 validation error detected: Value null at 'identifier' failed to satisfy constraint: Member must not be null");
        }
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw validation("1 validation error detected: Value '" + identifier
                    + "' at 'identifier' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9]+");
        }
        return identifier;
    }

    private static String requireName(String name) {
        if (name == null || name.length() < NAME_MIN || name.length() > NAME_MAX || !NAME.matcher(name).matches()) {
            throw validation("Invalid DB instance name.");
        }
        return name;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation("Missing required parameter " + field + ".");
        }
        return value;
    }

    private static int requireInt(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw validation("Missing required parameter " + field + ".");
        }
        return value.asInt();
    }

    private static List<String> requireStringList(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw validation("Missing required parameter " + field + ".");
        }
        List<String> items = new ArrayList<>();
        value.forEach(node -> items.add(node.asText()));
        return items;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value != null ? value : fallback;
    }

    private static Map<String, String> readTagMap(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull() || tagsNode.isMissingNode()) {
            return tags;
        }
        if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    tags.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return tags;
        }
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = textOrNull(tag, "Key");
                if (key == null) {
                    key = textOrNull(tag, "key");
                }
                if (key == null) {
                    continue;
                }
                String value = textOrNull(tag, "Value");
                if (value == null) {
                    value = textOrNull(tag, "value");
                }
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400,
                Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }

    private static AwsException notFound(String resourceId) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + resourceId + " not found",
                404,
                Map.of("resourceId", resourceId, "resourceType", "db-instance"));
    }

    private static AwsException conflict(String resourceId, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", "db-instance"));
    }
}
