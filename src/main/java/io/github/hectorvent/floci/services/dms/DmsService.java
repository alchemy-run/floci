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
import io.github.hectorvent.floci.services.dms.model.DmsReplicationInstance;
import io.github.hectorvent.floci.services.dms.model.DmsReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.DmsSubnetMembership;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final StorageBackend<String, DmsReplicationInstance> instances;
    private final StorageBackend<String, DmsReplicationSubnetGroup> subnetGroups;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;

    @Inject
    public DmsService(StorageFactory storageFactory, ObjectMapper objectMapper, RegionResolver regionResolver,
                      Ec2Service ec2Service) {
        this.endpoints = storageFactory.create("dms", "dms-endpoints.json",
                new TypeReference<Map<String, DmsEndpoint>>() {});
        this.instances = storageFactory.create("dms", "dms-replication-instances.json",
                new TypeReference<Map<String, DmsReplicationInstance>>() {});
        this.subnetGroups = storageFactory.create("dms", "dms-subnet-groups.json",
                new TypeReference<Map<String, DmsReplicationSubnetGroup>>() {});
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
    }

    @Override
    public void clear() {
        endpoints.clear();
        instances.clear();
        subnetGroups.clear();
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
        applyTags(request, endpoint.getTags());
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
        Map<String, String> tags = tagsFor(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagList = response.putArray("TagList");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = tagList.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
            tag.put("ResourceArn", arn);
        }
        return response;
    }

    public ObjectNode addTagsToResource(JsonNode request) {
        String arn = text(request, "ResourceArn");
        DmsEndpoint endpoint = findEndpointByArn(arn);
        if (endpoint != null) {
            applyTags(request, endpoint.getTags());
            endpoints.put(endpoint.identifier(), endpoint);
            return objectMapper.createObjectNode();
        }
        DmsReplicationInstance instance = findInstanceByArn(arn);
        if (instance != null) {
            applyTags(request, instance.getTags());
            instances.put(instance.identifier(), instance);
            return objectMapper.createObjectNode();
        }
        DmsReplicationSubnetGroup group = findSubnetGroupByArn(arn);
        if (group != null) {
            applyTags(request, group.getTags());
            subnetGroups.put(group.getIdentifier(), group);
            return objectMapper.createObjectNode();
        }
        throw missingResource(arn);
    }

    public ObjectNode removeTagsFromResource(JsonNode request) {
        String arn = text(request, "ResourceArn");
        Map<String, String> tags = tagsFor(arn);
        JsonNode keys = request.path("TagKeys");
        if (keys.isArray()) {
            for (JsonNode key : keys) {
                tags.remove(key.asText());
            }
        }
        persistTagged(arn);
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

    public ObjectNode createReplicationInstance(JsonNode request, String region) {
        String identifier = requireText(request, "ReplicationInstanceIdentifier");
        String instanceClass = requireText(request, "ReplicationInstanceClass");
        validateInstanceIdentifier(identifier);
        if (instances.get(identifier).isPresent()) {
            throw fault("ResourceAlreadyExistsFault",
                    "Replication instance " + identifier + " already exists.", 400);
        }

        DmsReplicationInstance instance = new DmsReplicationInstance();
        Map<String, Object> attrs = instance.getAttributes();
        attrs.put("ReplicationInstanceIdentifier", identifier);
        attrs.put("ReplicationInstanceClass", instanceClass);
        attrs.put("ReplicationInstanceStatus", "available");
        attrs.put("ReplicationInstanceArn",
                regionResolver.buildArn("dms", region, "rep:" + identifier));
        attrs.put("AllocatedStorage", request.hasNonNull("AllocatedStorage")
                ? request.get("AllocatedStorage").asInt() : 50);
        boolean multiAz = request.hasNonNull("MultiAZ") && request.get("MultiAZ").asBoolean();
        attrs.put("MultiAZ", multiAz);
        boolean publiclyAccessible = !request.hasNonNull("PubliclyAccessible")
                || request.get("PubliclyAccessible").asBoolean();
        attrs.put("PubliclyAccessible", publiclyAccessible);
        attrs.put("EngineVersion", textOr(request, "EngineVersion", "3.5.4"));
        attrs.put("AutoMinorVersionUpgrade", !request.hasNonNull("AutoMinorVersionUpgrade")
                || request.get("AutoMinorVersionUpgrade").asBoolean());
        attrs.put("NetworkType", textOr(request, "NetworkType", "IPV4"));
        attrs.put("PreferredMaintenanceWindow",
                textOr(request, "PreferredMaintenanceWindow", "sun:06:00-sun:14:00"));
        attrs.put("AvailabilityZone", textOr(request, "AvailabilityZone", "us-east-1a"));
        if (multiAz) {
            attrs.put("SecondaryAvailabilityZone", "us-east-1b");
        }
        attrs.put("InstanceCreateTime", Instant.now().getEpochSecond());
        copyText(request, attrs, "KmsKeyId");
        copyText(request, attrs, "DnsNameServers");
        applySubnetGroup(request, attrs);
        applyVpcSecurityGroups(request, attrs);
        String privateIp = privateIpFor(identifier);
        attrs.put("ReplicationInstancePrivateIpAddress", privateIp);
        attrs.put("ReplicationInstancePrivateIpAddresses", List.of(privateIp));
        if (publiclyAccessible) {
            String publicIp = publicIpFor(identifier);
            attrs.put("ReplicationInstancePublicIpAddress", publicIp);
            attrs.put("ReplicationInstancePublicIpAddresses", List.of(publicIp));
        } else {
            attrs.put("ReplicationInstancePublicIpAddresses", List.of());
        }
        applyTags(request, instance.getTags());
        instances.put(identifier, instance);
        return wrap("ReplicationInstance", toInstanceApi(instance));
    }

    public ObjectNode modifyReplicationInstance(JsonNode request) {
        DmsReplicationInstance instance = requireInstanceByArn(text(request, "ReplicationInstanceArn"));
        String previousId = instance.identifier();
        Map<String, Object> attrs = instance.getAttributes();
        copyText(request, attrs, "ReplicationInstanceClass");
        copyText(request, attrs, "PreferredMaintenanceWindow");
        copyText(request, attrs, "EngineVersion");
        copyText(request, attrs, "NetworkType");
        if (request.hasNonNull("AllocatedStorage")) {
            attrs.put("AllocatedStorage", request.get("AllocatedStorage").asInt());
        }
        if (request.hasNonNull("MultiAZ")) {
            boolean multiAz = request.get("MultiAZ").asBoolean();
            attrs.put("MultiAZ", multiAz);
            if (multiAz && !attrs.containsKey("SecondaryAvailabilityZone")) {
                attrs.put("SecondaryAvailabilityZone", "us-east-1b");
            }
            if (!multiAz) {
                attrs.remove("SecondaryAvailabilityZone");
            }
        }
        if (request.hasNonNull("AutoMinorVersionUpgrade")) {
            attrs.put("AutoMinorVersionUpgrade", request.get("AutoMinorVersionUpgrade").asBoolean());
        }
        applyVpcSecurityGroups(request, attrs);
        if (request.hasNonNull("ReplicationInstanceIdentifier")) {
            String nextId = request.get("ReplicationInstanceIdentifier").asText();
            validateInstanceIdentifier(nextId);
            if (!nextId.equals(previousId) && instances.get(nextId).isPresent()) {
                throw fault("ResourceAlreadyExistsFault",
                        "Replication instance " + nextId + " already exists.", 400);
            }
            attrs.put("ReplicationInstanceIdentifier", nextId);
            if (!nextId.equals(previousId)) {
                instances.delete(previousId);
            }
            instances.put(nextId, instance);
        } else {
            instances.put(previousId, instance);
        }
        return wrap("ReplicationInstance", toInstanceApi(instance));
    }

    public ObjectNode deleteReplicationInstance(JsonNode request) {
        DmsReplicationInstance instance = requireInstanceByArn(text(request, "ReplicationInstanceArn"));
        ObjectNode snapshot = toInstanceApi(instance);
        instances.delete(instance.identifier());
        snapshot.put("ReplicationInstanceStatus", "deleting");
        return wrap("ReplicationInstance", snapshot);
    }

    public ObjectNode describeReplicationInstances(JsonNode request) {
        List<DmsReplicationInstance> matches = filterInstances(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ReplicationInstances");
        for (DmsReplicationInstance instance : matches) {
            list.add(toInstanceApi(instance));
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

    public ObjectNode createReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireText(request, "ReplicationSubnetGroupIdentifier");
        String description = requireText(request, "ReplicationSubnetGroupDescription");
        validateSubnetGroupIdentifier(identifier);
        if (subnetGroups.get(identifier).isPresent()) {
            throw fault("ResourceAlreadyExistsFault",
                    "The Replication Subnet Group already exists.", 400);
        }
        DmsReplicationSubnetGroup group = new DmsReplicationSubnetGroup();
        group.setIdentifier(identifier);
        group.setDescription(description);
        group.setArn(regionResolver.buildArn("dms", region, "subgrp:" + identifier));
        applySubnets(group, request.path("SubnetIds"), region);
        applyTags(request, group.getTags());
        subnetGroups.put(identifier, group);
        return wrap("ReplicationSubnetGroup", toApi(group));
    }

    public ObjectNode modifyReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireText(request, "ReplicationSubnetGroupIdentifier");
        DmsReplicationSubnetGroup group = requireSubnetGroup(identifier);
        if (request.hasNonNull("ReplicationSubnetGroupDescription")) {
            group.setDescription(request.get("ReplicationSubnetGroupDescription").asText());
        }
        if (request.has("SubnetIds") && request.get("SubnetIds").isArray()) {
            applySubnets(group, request.get("SubnetIds"), region);
        }
        subnetGroups.put(identifier, group);
        return wrap("ReplicationSubnetGroup", toApi(group));
    }

    public ObjectNode deleteReplicationSubnetGroup(JsonNode request) {
        String identifier = requireText(request, "ReplicationSubnetGroupIdentifier");
        requireSubnetGroup(identifier);
        subnetGroups.delete(identifier);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeReplicationSubnetGroups(JsonNode request) {
        List<DmsReplicationSubnetGroup> matches = filterSubnetGroups(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ReplicationSubnetGroups");
        for (DmsReplicationSubnetGroup group : matches) {
            list.add(toApi(group));
        }
        return response;
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
        applyTags(request, endpoint.getTags());
    }

    private void applyTags(JsonNode request, Map<String, String> tags) {
        JsonNode tagList = request.path("Tags");
        if (!tagList.isArray()) {
            return;
        }
        for (JsonNode tag : tagList) {
            String key = tag.path("Key").asText(null);
            String value = tag.path("Value").asText("");
            if (key != null && !key.isBlank()) {
                tags.put(key, value);
            }
        }
    }

    private Map<String, String> tagsFor(String arn) {
        DmsEndpoint endpoint = findEndpointByArn(arn);
        if (endpoint != null) {
            return endpoint.getTags();
        }
        DmsReplicationInstance instance = findInstanceByArn(arn);
        if (instance != null) {
            return instance.getTags();
        }
        DmsReplicationSubnetGroup group = findSubnetGroupByArn(arn);
        if (group != null) {
            return group.getTags();
        }
        throw missingResource(arn);
    }

    private void persistTagged(String arn) {
        DmsEndpoint endpoint = findEndpointByArn(arn);
        if (endpoint != null) {
            endpoints.put(endpoint.identifier(), endpoint);
            return;
        }
        DmsReplicationInstance instance = findInstanceByArn(arn);
        if (instance != null) {
            instances.put(instance.identifier(), instance);
            return;
        }
        DmsReplicationSubnetGroup group = findSubnetGroupByArn(arn);
        if (group != null) {
            subnetGroups.put(group.getIdentifier(), group);
            return;
        }
        throw missingResource(arn);
    }

    private DmsEndpoint findEndpointByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return null;
        }
        for (DmsEndpoint endpoint : endpoints.values()) {
            if (arn.equals(endpoint.arn())) {
                return endpoint;
            }
        }
        if (arn.contains(":subgrp:") || arn.contains(":rep:")) {
            return null;
        }
        String suffix = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        return endpoints.get(suffix).orElse(null);
    }

    private DmsEndpoint requireEndpointByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw fault("InvalidParameterValueException", "EndpointArn must not be blank.", 400);
        }
        DmsEndpoint endpoint = findEndpointByArn(arn);
        if (endpoint == null) {
            throw fault("ResourceNotFoundFault", "Endpoint not found: " + arn, 400);
        }
        return endpoint;
    }

    private DmsReplicationInstance findInstanceByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return null;
        }
        for (DmsReplicationInstance instance : instances.values()) {
            if (arn.equals(instance.arn())) {
                return instance;
            }
        }
        if (arn.contains(":subgrp:") || arn.contains(":endpoint:")) {
            return null;
        }
        String suffix = arn.contains(":") ? arn.substring(arn.lastIndexOf(':') + 1) : arn;
        return instances.get(suffix).orElse(null);
    }

    private DmsReplicationInstance requireInstanceByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw fault("InvalidParameterValueException", "ReplicationInstanceArn must not be blank.", 400);
        }
        DmsReplicationInstance instance = findInstanceByArn(arn);
        if (instance == null) {
            throw fault("ResourceNotFoundFault", "Replication instance not found: " + arn, 400);
        }
        return instance;
    }

    private List<DmsReplicationInstance> filterInstances(JsonNode request) {
        List<DmsReplicationInstance> all = new ArrayList<>(instances.values());
        if (request == null || !request.has("Filters") || !request.get("Filters").isArray()) {
            return all;
        }
        List<DmsReplicationInstance> matches = new ArrayList<>();
        for (DmsReplicationInstance instance : all) {
            if (matchesInstanceFilters(instance, request.get("Filters"))) {
                matches.add(instance);
            }
        }
        return matches;
    }

    private boolean matchesInstanceFilters(DmsReplicationInstance instance, JsonNode filters) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText("");
            List<String> values = new ArrayList<>();
            if (filter.path("Values").isArray()) {
                filter.get("Values").forEach(v -> values.add(v.asText()));
            }
            String actual = switch (name) {
                case "replication-instance-id" -> instance.identifier();
                case "replication-instance-arn" -> instance.arn();
                case "replication-instance-class" -> stringInstanceAttr(instance, "ReplicationInstanceClass");
                case "engine-version" -> stringInstanceAttr(instance, "EngineVersion");
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

    private void applySubnetGroup(JsonNode request, Map<String, Object> attrs) {
        if (!request.hasNonNull("ReplicationSubnetGroupIdentifier")) {
            return;
        }
        String identifier = request.get("ReplicationSubnetGroupIdentifier").asText();
        DmsReplicationSubnetGroup stored = subnetGroups.get(identifier).orElse(null);
        if (stored != null) {
            attrs.put("ReplicationSubnetGroup", objectMapper.convertValue(toApi(stored), Map.class));
            return;
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("ReplicationSubnetGroupIdentifier", identifier);
        group.put("SubnetGroupStatus", "Complete");
        attrs.put("ReplicationSubnetGroup", group);
    }

    private void applyVpcSecurityGroups(JsonNode request, Map<String, Object> attrs) {
        if (!request.has("VpcSecurityGroupIds") || !request.get("VpcSecurityGroupIds").isArray()) {
            return;
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (JsonNode id : request.get("VpcSecurityGroupIds")) {
            Map<String, Object> membership = new LinkedHashMap<>();
            membership.put("VpcSecurityGroupId", id.asText());
            membership.put("Status", "active");
            groups.add(membership);
        }
        attrs.put("VpcSecurityGroups", groups);
    }

    private ObjectNode toInstanceApi(DmsReplicationInstance instance) {
        return objectMapper.valueToTree(instance.getAttributes());
    }

    private static String stringInstanceAttr(DmsReplicationInstance instance, String key) {
        Object value = instance.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String privateIpFor(String identifier) {
        int last = Math.floorMod(identifier.hashCode(), 250) + 2;
        return "10.0.0." + last;
    }

    private static String publicIpFor(String identifier) {
        int last = Math.floorMod(identifier.hashCode(), 250) + 2;
        return "203.0.113." + last;
    }

    private static void validateInstanceIdentifier(String identifier) {
        if (identifier.length() > 63 || !identifier.matches("[A-Za-z][A-Za-z0-9-]*")) {
            throw fault("InvalidParameterValueException",
                    "ReplicationInstanceIdentifier must begin with a letter and contain only letters, digits, and hyphens.",
                    400);
        }
        if (identifier.endsWith("-") || identifier.contains("--")) {
            throw fault("InvalidParameterValueException",
                    "ReplicationInstanceIdentifier cannot end with a hyphen or contain two consecutive hyphens.",
                    400);
        }
    }

    private static AwsException missingResource(String arn) {
        return fault("ResourceNotFoundFault", "Resource not found: " + arn, 400);
    }

    private DmsReplicationSubnetGroup requireSubnetGroup(String identifier) {
        return subnetGroups.get(identifier).orElseThrow(() ->
                fault("ResourceNotFoundFault",
                        "Replication subnet group not found: " + identifier, 400));
    }

    private DmsReplicationSubnetGroup findSubnetGroupByArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return null;
        }
        for (DmsReplicationSubnetGroup group : subnetGroups.values()) {
            if (arn.equals(group.getArn())) {
                return group;
            }
        }
        int marker = arn.indexOf("subgrp:");
        if (marker >= 0) {
            return subnetGroups.get(arn.substring(marker + "subgrp:".length())).orElse(null);
        }
        if (!arn.contains(":")) {
            return subnetGroups.get(arn).orElse(null);
        }
        return null;
    }

    private List<DmsReplicationSubnetGroup> filterSubnetGroups(JsonNode request) {
        List<DmsReplicationSubnetGroup> all = new ArrayList<>(subnetGroups.values());
        if (request == null || !request.has("Filters") || !request.get("Filters").isArray()) {
            return all;
        }
        List<DmsReplicationSubnetGroup> matches = new ArrayList<>();
        for (DmsReplicationSubnetGroup group : all) {
            if (matchesSubnetGroupFilters(group, request.get("Filters"))) {
                matches.add(group);
            }
        }
        return matches;
    }

    private boolean matchesSubnetGroupFilters(DmsReplicationSubnetGroup group, JsonNode filters) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText("");
            List<String> values = new ArrayList<>();
            if (filter.path("Values").isArray()) {
                filter.get("Values").forEach(v -> values.add(v.asText()));
            }
            String actual = switch (name) {
                case "replication-subnet-group-id", "replication-subnet-group-identifier" ->
                        group.getIdentifier();
                case "vpc-id" -> group.getVpcId();
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

    private void applySubnets(DmsReplicationSubnetGroup group, JsonNode subnetIds, String region) {
        if (subnetIds == null || !subnetIds.isArray() || subnetIds.isEmpty()) {
            throw fault("InvalidParameterValueException", "SubnetIds is required.", 400);
        }
        List<DmsSubnetMembership> resolved = new ArrayList<>();
        String vpcId = null;
        Set<String> azs = new LinkedHashSet<>();
        for (JsonNode idNode : subnetIds) {
            String subnetId = idNode.asText();
            if (subnetId == null || subnetId.isBlank()) {
                throw fault("InvalidSubnet", "SubnetIds contains an empty subnet id.", 400);
            }
            Subnet subnet = ec2Service.findSubnetById(region, subnetId).orElseThrow(() ->
                    fault("InvalidSubnet", "The subnet ID '" + subnetId + "' does not exist.", 400));
            String subnetVpc = subnet.getVpcId();
            if (vpcId == null) {
                vpcId = subnetVpc;
            } else if (!vpcId.equals(subnetVpc)) {
                throw fault("InvalidSubnet", "All subnets must belong to the same VPC.", 400);
            }
            azs.add(subnet.getAvailabilityZone());
            resolved.add(new DmsSubnetMembership(subnetId, subnet.getAvailabilityZone()));
        }
        if (azs.size() < 2) {
            throw fault("ReplicationSubnetGroupDoesNotCoverEnoughAZs",
                    "The Replication Subnet Group does not cover enough Availability Zones.", 400);
        }
        group.setVpcId(vpcId);
        group.setSubnets(resolved);
        group.setStatus("Complete");
    }

    private ObjectNode toApi(DmsReplicationSubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationSubnetGroupIdentifier", group.getIdentifier());
        node.put("ReplicationSubnetGroupDescription", group.getDescription());
        if (group.getVpcId() != null) {
            node.put("VpcId", group.getVpcId());
        }
        node.put("SubnetGroupStatus", group.getStatus());
        ArrayNode subnets = node.putArray("Subnets");
        for (DmsSubnetMembership membership : group.getSubnets()) {
            ObjectNode subnet = subnets.addObject();
            subnet.put("SubnetIdentifier", membership.getSubnetIdentifier());
            ObjectNode az = subnet.putObject("SubnetAvailabilityZone");
            az.put("Name", membership.getAvailabilityZone());
            subnet.put("SubnetStatus", membership.getSubnetStatus());
        }
        return node;
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

    private static void validateSubnetGroupIdentifier(String identifier) {
        if ("default".equalsIgnoreCase(identifier)) {
            throw fault("InvalidParameterValueException",
                    "ReplicationSubnetGroupIdentifier cannot be 'default'.", 400);
        }
        if (identifier.length() > 255 || !identifier.matches("[A-Za-z][A-Za-z0-9-]*")) {
            throw fault("InvalidParameterValueException",
                    "ReplicationSubnetGroupIdentifier must begin with a letter and contain only letters, digits, and hyphens.",
                    400);
        }
        if (identifier.endsWith("-") || identifier.contains("--")) {
            throw fault("InvalidParameterValueException",
                    "ReplicationSubnetGroupIdentifier cannot end with a hyphen or contain two consecutive hyphens.",
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
