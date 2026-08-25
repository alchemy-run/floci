package io.github.hectorvent.floci.services.dms;

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
import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local Amazon DMS stub. Endpoints are metadata-only; schema refresh,
 * replication tasks, and serverless replications are not executed.
 *
 * @see <a href="https://docs.aws.amazon.com/dms/latest/APIReference/Welcome.html">DMS API</a>
 */
@ApplicationScoped
public class DmsService implements Resettable {

    private static final Set<String> SETTING_FIELDS = Set.of(
            "DynamoDbSettings",
            "S3Settings",
            "DmsTransferSettings",
            "MongoDbSettings",
            "KinesisSettings",
            "KafkaSettings",
            "ElasticsearchSettings",
            "NeptuneSettings",
            "RedshiftSettings",
            "PostgreSQLSettings",
            "MySQLSettings",
            "OracleSettings",
            "SybaseSettings",
            "MicrosoftSQLServerSettings",
            "IBMDb2Settings",
            "DocDbSettings",
            "RedisSettings",
            "GcpMySQLSettings",
            "TimestreamSettings");

    private static final String[] MYSQL_SETTINGS = {
            "ServerName", "Port", "Username", "Password", "DatabaseName",
            "ExtraConnectionAttributes", "SecretsManagerAccessRoleArn",
            "SecretsManagerSecretId"
    };

    private final StorageBackend<String, DmsEndpoint> endpoints;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DmsService(StorageFactory storageFactory, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.endpoints = storageFactory.create("dms", "dms-endpoints.json",
                new TypeReference<Map<String, DmsEndpoint>>() {});
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        endpoints.clear();
    }

    public ObjectNode createEndpoint(JsonNode request, String region) {
        String identifier = requireText(request, "EndpointIdentifier");
        String endpointType = requireText(request, "EndpointType");
        String engineName = requireText(request, "EngineName");
        validateIdentifier(identifier);
        if (endpoints.get(identifier).isPresent()) {
            throw fault("ResourceAlreadyExistsFault",
                    "Endpoint " + identifier + " already exists.", 400);
        }

        DmsEndpoint endpoint = new DmsEndpoint();
        Map<String, Object> attrs = endpoint.getAttributes();
        attrs.put("EndpointIdentifier", identifier);
        attrs.put("EndpointType", endpointType.toUpperCase(Locale.ROOT));
        attrs.put("EngineName", engineName);
        attrs.put("Status", "active");
        attrs.put("SslMode", textOr(request, "SslMode", "none"));
        attrs.put("EndpointArn", regionResolver.buildArn("dms", region, "endpoint:" + identifier));
        applyMutableFields(request, attrs);
        if (request.hasNonNull("Password")) {
            endpoint.setPassword(request.get("Password").asText());
        }
        applyTags(request, endpoint);
        endpoints.put(identifier, endpoint);
        return wrap("Endpoint", toApi(endpoint));
    }

    public ObjectNode modifyEndpoint(JsonNode request) {
        DmsEndpoint endpoint = requireEndpointByArn(text(request, "EndpointArn"));
        String previousId = endpoint.identifier();
        Map<String, Object> attrs = endpoint.getAttributes();
        if (request.hasNonNull("EndpointType")) {
            attrs.put("EndpointType", request.get("EndpointType").asText().toUpperCase(Locale.ROOT));
        }
        if (request.hasNonNull("EngineName")) {
            attrs.put("EngineName", request.get("EngineName").asText());
        }
        applyMutableFields(request, attrs);
        if (request.hasNonNull("Password")) {
            endpoint.setPassword(request.get("Password").asText());
        }
        if (request.hasNonNull("EndpointIdentifier")) {
            String nextId = request.get("EndpointIdentifier").asText();
            validateIdentifier(nextId);
            if (!nextId.equals(previousId) && endpoints.get(nextId).isPresent()) {
                throw fault("ResourceAlreadyExistsFault",
                        "Endpoint " + nextId + " already exists.", 400);
            }
            attrs.put("EndpointIdentifier", nextId);
            if (!nextId.equals(previousId)) {
                endpoints.delete(previousId);
            }
            endpoints.put(nextId, endpoint);
        } else {
            endpoints.put(previousId, endpoint);
        }
        return wrap("Endpoint", toApi(endpoint));
    }

    public ObjectNode deleteEndpoint(JsonNode request) {
        DmsEndpoint endpoint = requireEndpointByArn(text(request, "EndpointArn"));
        ObjectNode snapshot = toApi(endpoint);
        endpoints.delete(endpoint.identifier());
        snapshot.put("Status", "deleting");
        return wrap("Endpoint", snapshot);
    }

    public ObjectNode describeEndpoints(JsonNode request) {
        List<DmsEndpoint> matches = filterEndpoints(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Endpoints");
        for (DmsEndpoint endpoint : matches) {
            list.add(toApi(endpoint));
        }
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = text(request, "ResourceArn");
        DmsEndpoint endpoint = requireEndpointByArn(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagList = response.putArray("TagList");
        for (Map.Entry<String, String> entry : endpoint.getTags().entrySet()) {
            ObjectNode tag = tagList.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
            tag.put("ResourceArn", arn);
        }
        return response;
    }

    public ObjectNode addTagsToResource(JsonNode request) {
        DmsEndpoint endpoint = requireEndpointByArn(text(request, "ResourceArn"));
        applyTags(request, endpoint);
        endpoints.put(endpoint.identifier(), endpoint);
        return objectMapper.createObjectNode();
    }

    public ObjectNode removeTagsFromResource(JsonNode request) {
        DmsEndpoint endpoint = requireEndpointByArn(text(request, "ResourceArn"));
        JsonNode keys = request.path("TagKeys");
        if (keys.isArray()) {
            for (JsonNode key : keys) {
                endpoint.getTags().remove(key.asText());
            }
        }
        endpoints.put(endpoint.identifier(), endpoint);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeSchemas(JsonNode request) {
        requireEndpointByArn(text(request, "EndpointArn"));
        throw fault("InvalidResourceStateFault",
                "Schemas for the specified endpoint are unavailable until RefreshSchemas completes.",
                400);
    }

    public ObjectNode describeRefreshSchemasStatus(JsonNode request) {
        requireEndpointByArn(text(request, "EndpointArn"));
        throw fault("InvalidResourceStateFault",
                "No schema refresh has been performed for the specified endpoint.",
                400);
    }

    public ObjectNode describeConnections(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Connections");
        return response;
    }

    public ObjectNode describeEvents(JsonNode ignored) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Events");
        return response;
    }

    public ObjectNode describeEndpointSettings(JsonNode request) {
        String engineName = requireText(request, "EngineName");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode settings = response.putArray("EndpointSettings");
        if ("mysql".equalsIgnoreCase(engineName) || "mariadb".equalsIgnoreCase(engineName)
                || "aurora".equalsIgnoreCase(engineName)) {
            for (String name : MYSQL_SETTINGS) {
                ObjectNode setting = settings.addObject();
                setting.put("Name", name);
                setting.put("Type", "Password".equals(name) || name.contains("Secret") ? "string" : typeFor(name));
                if ("Password".equals(name)) {
                    setting.put("Sensitive", true);
                }
            }
        } else {
            ObjectNode setting = settings.addObject();
            setting.put("Name", "ServerName");
            setting.put("Type", "string");
        }
        return response;
    }

    public ObjectNode describeOrderableReplicationInstances(JsonNode ignored) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("OrderableReplicationInstances");
        for (String instanceClass : List.of(
                "dms.t3.micro", "dms.t3.small", "dms.t3.medium", "dms.c5.large", "dms.c5.xlarge")) {
            ObjectNode item = list.addObject();
            item.put("EngineVersion", "3.5.4");
            item.put("ReplicationInstanceClass", instanceClass);
            item.put("StorageType", "gp3");
            item.put("MinAllocatedStorage", 20);
            item.put("MaxAllocatedStorage", 6144);
            item.put("DefaultAllocatedStorage", 50);
            item.put("IncludedAllocatedStorage", 50);
            ArrayNode zones = item.putArray("AvailabilityZones");
            zones.add("us-east-1a");
            zones.add("us-east-1b");
            zones.add("us-east-1c");
            item.put("ReleaseStatus", "prod");
        }
        return response;
    }

    public ObjectNode describeReplicationTasks(JsonNode ignored) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ReplicationTasks");
        return response;
    }

    public ObjectNode describeReplications(JsonNode ignored) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Replications");
        return response;
    }

    public ObjectNode startReplicationTask(JsonNode request) {
        throw missingTask(text(request, "ReplicationTaskArn"));
    }

    public ObjectNode stopReplicationTask(JsonNode request) {
        throw missingTask(text(request, "ReplicationTaskArn"));
    }

    public ObjectNode describeTableStatistics(JsonNode request) {
        throw missingTask(text(request, "ReplicationTaskArn"));
    }

    public ObjectNode reloadTables(JsonNode request) {
        throw missingTask(text(request, "ReplicationTaskArn"));
    }

    public ObjectNode startReplication(JsonNode request) {
        throw missingReplication(text(request, "ReplicationConfigArn"));
    }

    public ObjectNode stopReplication(JsonNode request) {
        throw missingReplication(text(request, "ReplicationConfigArn"));
    }

    private List<DmsEndpoint> filterEndpoints(JsonNode request) {
        List<DmsEndpoint> all = new ArrayList<>(endpoints.values());
        if (request == null || !request.has("Filters") || !request.get("Filters").isArray()) {
            return all;
        }
        List<DmsEndpoint> matches = new ArrayList<>();
        for (DmsEndpoint endpoint : all) {
            if (matchesFilters(endpoint, request.get("Filters"))) {
                matches.add(endpoint);
            }
        }
        return matches;
    }

    private boolean matchesFilters(DmsEndpoint endpoint, JsonNode filters) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText("");
            List<String> values = new ArrayList<>();
            if (filter.path("Values").isArray()) {
                filter.get("Values").forEach(v -> values.add(v.asText()));
            }
            String actual = switch (name) {
                case "endpoint-id", "endpoint-identifier" -> endpoint.identifier();
                case "endpoint-arn" -> endpoint.arn();
                case "endpoint-type" -> stringAttr(endpoint, "EndpointType");
                case "engine-name" -> stringAttr(endpoint, "EngineName");
                default -> null;
            };
            if (actual == null) {
                return false;
            }
            boolean hit = false;
            for (String value : values) {
                if (actual.equalsIgnoreCase(value)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private void applyMutableFields(JsonNode request, Map<String, Object> attrs) {
        copyText(request, attrs, "Username");
        copyText(request, attrs, "ServerName");
        copyText(request, attrs, "DatabaseName");
        copyText(request, attrs, "ExtraConnectionAttributes");
        copyText(request, attrs, "KmsKeyId");
        copyText(request, attrs, "CertificateArn");
        copyText(request, attrs, "SslMode");
        copyText(request, attrs, "ServiceAccessRoleArn");
        copyText(request, attrs, "ExternalTableDefinition");
        if (request.hasNonNull("Port")) {
            attrs.put("Port", request.get("Port").asInt());
        }
        for (String field : SETTING_FIELDS) {
            if (request.has(field) && !request.get(field).isNull()) {
                attrs.put(field, objectMapper.convertValue(request.get(field), Object.class));
            }
        }
    }

    private void applyTags(JsonNode request, DmsEndpoint endpoint) {
        JsonNode tags = request.path("Tags");
        if (!tags.isArray()) {
            return;
        }
        for (JsonNode tag : tags) {
            String key = tag.path("Key").asText(null);
            String value = tag.path("Value").asText("");
            if (key != null && !key.isBlank()) {
                endpoint.getTags().put(key, value);
            }
        }
    }

    private DmsEndpoint requireEndpointByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw fault("InvalidParameterValueException", "EndpointArn must not be blank.", 400);
        }
        for (DmsEndpoint endpoint : endpoints.values()) {
            if (arn.equals(endpoint.arn())) {
                return endpoint;
            }
        }
        String suffix = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        return endpoints.get(suffix).orElseThrow(() ->
                fault("ResourceNotFoundFault", "Endpoint not found: " + arn, 400));
    }

    private ObjectNode toApi(DmsEndpoint endpoint) {
        return objectMapper.valueToTree(endpoint.getAttributes());
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return response;
    }

    private static String stringAttr(DmsEndpoint endpoint, String key) {
        Object value = endpoint.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static void copyText(JsonNode request, Map<String, Object> attrs, String field) {
        if (request.hasNonNull(field)) {
            attrs.put(field, request.get(field).asText());
        }
    }

    private static String text(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            throw fault("InvalidParameterValueException", field + " is required.", 400);
        }
        return request.get(field).asText();
    }

    private static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value.isBlank()) {
            throw fault("InvalidParameterValueException", field + " must not be blank.", 400);
        }
        return value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        return request != null && request.hasNonNull(field) ? request.get(field).asText() : fallback;
    }

    private static void validateIdentifier(String identifier) {
        if (identifier.length() > 255 || !identifier.matches("[A-Za-z][A-Za-z0-9-]*")) {
            throw fault("InvalidParameterValueException",
                    "EndpointIdentifier must begin with a letter and contain only letters, digits, and hyphens.",
                    400);
        }
        if (identifier.endsWith("-") || identifier.contains("--")) {
            throw fault("InvalidParameterValueException",
                    "EndpointIdentifier cannot end with a hyphen or contain two consecutive hyphens.",
                    400);
        }
    }

    private static String typeFor(String name) {
        return "Port".equals(name) ? "integer" : "string";
    }

    private static AwsException missingTask(String arn) {
        return fault("ResourceNotFoundFault", "Replication task not found: " + arn, 400);
    }

    private static AwsException missingReplication(String arn) {
        return fault("ResourceNotFoundFault", "Replication config not found: " + arn, 400);
    }

    private static AwsException fault(String code, String message, int status) {
        return new AwsException(code, message, status);
    }
}
