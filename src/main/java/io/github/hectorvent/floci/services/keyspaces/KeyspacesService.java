package io.github.hectorvent.floci.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local Amazon Keyspaces (for Apache Cassandra) stub. Keyspaces and tables are
 * in-memory; provisioning is instantaneous ({@code ACTIVE}).
 *
 * @see <a href="https://docs.aws.amazon.com/keyspaces/latest/APIReference/API_Operations.html">Keyspaces API</a>
 */
@ApplicationScoped
public class KeyspacesService implements Resettable {

    static final class Keyspace {
        String name;
        String arn;
        String region;
        String replicationStrategy = "SINGLE_REGION";
        final List<String> replicationRegions = new ArrayList<>();
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, Table> tables = new LinkedHashMap<>();
        final Map<String, UserType> types = new LinkedHashMap<>();

        Map<String, String> tags() {
            return tags;
        }
    }

    static final class Table {
        String keyspaceName;
        String tableName;
        String arn;
        String status = "ACTIVE";
        long creationTimestamp;
        ObjectNode schema;
        String throughputMode = "PAY_PER_REQUEST";
        Long readCapacityUnits;
        Long writeCapacityUnits;
        String pitrStatus = "DISABLED";
        Long earliestRestorableTimestamp;
        String ttlStatus;
        Integer defaultTimeToLive;
        String cdcStatus;
        String cdcViewType;
        String latestStreamArn;
        final Map<String, String> tags = new LinkedHashMap<>();

        Map<String, String> tags() {
            return tags;
        }
    }

    static final class UserType {
        String keyspaceName;
        String typeName;
        String keyspaceArn;
        String status = "ACTIVE";
        long lastModifiedTimestamp;
        int maxNestingDepth = 1;
        final List<Field> fields = new ArrayList<>();
        final List<String> directReferringTables = new ArrayList<>();
        final List<String> directParentTypes = new ArrayList<>();
    }

    static final class Field {
        String name;
        String type;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, Keyspace> keyspaces = new ConcurrentHashMap<>();

    @Inject
    public KeyspacesService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        keyspaces.clear();
    }

    public ObjectNode createKeyspace(JsonNode request, String region) {
        String name = requireText(request, "keyspaceName");
        if (keyspaces.containsKey(name)) {
            throw conflict("Keyspace " + name + " already exists.");
        }
        Keyspace keyspace = new Keyspace();
        keyspace.name = name;
        keyspace.region = region;
        keyspace.arn = keyspaceArn(region, name);
        JsonNode replication = request.get("replicationSpecification");
        if (replication != null && replication.isObject()) {
            String strategy = textOrNull(replication, "replicationStrategy");
            if (strategy != null) {
                keyspace.replicationStrategy = strategy;
            }
            JsonNode regions = replication.get("regionList");
            if (regions != null && regions.isArray()) {
                for (JsonNode item : regions) {
                    if (!item.isNull()) {
                        keyspace.replicationRegions.add(item.asText());
                    }
                }
            }
        }
        keyspace.tags.putAll(readTagsField(request.get("tags")));
        keyspaces.put(name, keyspace);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("resourceArn", keyspace.arn);
        return response;
    }

    public ObjectNode getKeyspace(JsonNode request) {
        Keyspace keyspace = requireKeyspace(requireText(request, "keyspaceName"));
        return keyspaceNode(keyspace);
    }

    public ObjectNode deleteKeyspace(JsonNode request) {
        Keyspace keyspace = requireKeyspace(requireText(request, "keyspaceName"));
        if (!keyspace.tables.isEmpty()) {
            throw conflict("Cannot delete keyspace " + keyspace.name + " while it contains tables.");
        }
        if (!keyspace.types.isEmpty()) {
            throw conflict("Cannot delete keyspace " + keyspace.name + " while it contains types.");
        }
        keyspaces.remove(keyspace.name);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listKeyspaces() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("keyspaces");
        for (Keyspace keyspace : keyspaces.values()) {
            ObjectNode summary = list.addObject();
            summary.put("keyspaceName", keyspace.name);
            summary.put("resourceArn", keyspace.arn);
            summary.put("replicationStrategy", keyspace.replicationStrategy);
            if (!keyspace.replicationRegions.isEmpty()) {
                ArrayNode regions = summary.putArray("replicationRegions");
                keyspace.replicationRegions.forEach(regions::add);
            }
        }
        return response;
    }

    public ObjectNode updateKeyspace(JsonNode request) {
        Keyspace keyspace = requireKeyspace(requireText(request, "keyspaceName"));
        JsonNode replication = request.get("replicationSpecification");
        if (replication == null || !replication.isObject()) {
            throw invalid("replicationSpecification is required.");
        }
        String strategy = requireText(replication, "replicationStrategy");
        keyspace.replicationStrategy = strategy;
        keyspace.replicationRegions.clear();
        JsonNode regions = replication.get("regionList");
        if (regions != null && regions.isArray()) {
            for (JsonNode item : regions) {
                if (!item.isNull()) {
                    keyspace.replicationRegions.add(item.asText());
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("resourceArn", keyspace.arn);
        return response;
    }

    public ObjectNode createTable(JsonNode request, String region) {
        String keyspaceName = requireText(request, "keyspaceName");
        String tableName = requireText(request, "tableName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        if (keyspace.tables.containsKey(tableName)) {
            throw conflict("Table " + tableName + " already exists in keyspace " + keyspaceName + ".");
        }
        JsonNode schema = request.get("schemaDefinition");
        if (schema == null || !schema.isObject()) {
            throw invalid("schemaDefinition is required.");
        }
        Table table = new Table();
        table.keyspaceName = keyspaceName;
        table.tableName = tableName;
        table.arn = tableArn(region, keyspaceName, tableName);
        table.creationTimestamp = nowSeconds();
        table.schema = (ObjectNode) schema.deepCopy();
        applyCapacity(table, request.get("capacitySpecification"), false);
        applyPitr(table, request.get("pointInTimeRecovery"));
        JsonNode ttl = request.get("ttl");
        if (ttl != null && ttl.isObject()) {
            String status = textOrNull(ttl, "status");
            if (status != null) {
                table.ttlStatus = status;
            }
        }
        if (request.hasNonNull("defaultTimeToLive")) {
            table.defaultTimeToLive = request.get("defaultTimeToLive").asInt();
        }
        applyCdc(table, request.get("cdcSpecification"));
        table.tags.putAll(readTagsField(request.get("tags")));
        keyspace.tables.put(tableName, table);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("resourceArn", table.arn);
        return response;
    }

    public ObjectNode getTable(JsonNode request) {
        return tableNode(requireTable(request));
    }

    public ObjectNode updateTable(JsonNode request) {
        Table table = requireTable(request);
        JsonNode addColumns = request.get("addColumns");
        if (addColumns != null && addColumns.isArray()) {
            ArrayNode allColumns = (ArrayNode) table.schema.get("allColumns");
            if (allColumns == null || !allColumns.isArray()) {
                allColumns = table.schema.putArray("allColumns");
            }
            for (JsonNode column : addColumns) {
                String name = textOrNull(column, "name");
                if (name == null) {
                    continue;
                }
                boolean exists = false;
                for (JsonNode existing : allColumns) {
                    if (name.equals(textOrNull(existing, "name"))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    allColumns.add(column.deepCopy());
                }
            }
        }
        applyCapacity(table, request.get("capacitySpecification"), true);
        applyPitr(table, request.get("pointInTimeRecovery"));
        JsonNode ttl = request.get("ttl");
        if (ttl != null && ttl.isObject()) {
            String status = textOrNull(ttl, "status");
            if (status != null) {
                table.ttlStatus = status;
            }
        }
        if (request.hasNonNull("defaultTimeToLive")) {
            table.defaultTimeToLive = request.get("defaultTimeToLive").asInt();
        }
        applyCdc(table, request.get("cdcSpecification"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("resourceArn", table.arn);
        return response;
    }

    public ObjectNode deleteTable(JsonNode request) {
        String keyspaceName = requireText(request, "keyspaceName");
        String tableName = requireText(request, "tableName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        Table removed = keyspace.tables.remove(tableName);
        if (removed == null) {
            throw notFound("Table " + tableName + " not found in keyspace " + keyspaceName + ".");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTables(JsonNode request) {
        Keyspace keyspace = requireKeyspace(requireText(request, "keyspaceName"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("tables");
        for (Table table : keyspace.tables.values()) {
            ObjectNode summary = list.addObject();
            summary.put("keyspaceName", table.keyspaceName);
            summary.put("tableName", table.tableName);
            summary.put("resourceArn", table.arn);
        }
        return response;
    }

    public ObjectNode restoreTable(JsonNode request, String region) {
        String sourceKeyspaceName = requireText(request, "sourceKeyspaceName");
        String sourceTableName = requireText(request, "sourceTableName");
        String targetKeyspaceName = requireText(request, "targetKeyspaceName");
        String targetTableName = requireText(request, "targetTableName");

        Keyspace sourceKeyspace = requireKeyspace(sourceKeyspaceName);
        Table source = sourceKeyspace.tables.get(sourceTableName);
        if (source == null) {
            throw notFound("Table " + sourceTableName + " not found in keyspace " + sourceKeyspaceName + ".");
        }
        Keyspace targetKeyspace = requireKeyspace(targetKeyspaceName);
        if (targetKeyspace.tables.containsKey(targetTableName)) {
            throw conflict("Table " + targetTableName + " already exists in keyspace " + targetKeyspaceName + ".");
        }
        if (!"ENABLED".equals(source.pitrStatus)) {
            throw invalid("Point in time recovery is not enabled for table " + sourceTableName + ".");
        }
        if (request.hasNonNull("restoreTimestamp")) {
            long timestamp = request.get("restoreTimestamp").asLong();
            long now = nowSeconds();
            if (timestamp > now
                    || (source.earliestRestorableTimestamp != null
                    && timestamp < source.earliestRestorableTimestamp)) {
                throw invalid("restoreTimestamp is outside the restorable window.");
            }
        }

        Table restored = new Table();
        restored.keyspaceName = targetKeyspaceName;
        restored.tableName = targetTableName;
        restored.arn = tableArn(region, targetKeyspaceName, targetTableName);
        restored.status = "ACTIVE";
        restored.creationTimestamp = nowSeconds();
        restored.schema = source.schema.deepCopy();
        restored.throughputMode = source.throughputMode;
        restored.readCapacityUnits = source.readCapacityUnits;
        restored.writeCapacityUnits = source.writeCapacityUnits;
        restored.pitrStatus = source.pitrStatus;
        restored.earliestRestorableTimestamp = "ENABLED".equals(source.pitrStatus)
                ? restored.creationTimestamp
                : null;
        restored.ttlStatus = source.ttlStatus;
        restored.defaultTimeToLive = source.defaultTimeToLive;
        restored.tags.putAll(source.tags);
        applyCapacity(restored, request.get("capacitySpecificationOverride"), true);
        applyPitr(restored, request.get("pointInTimeRecoveryOverride"));
        JsonNode tagsOverride = request.get("tagsOverride");
        if (tagsOverride != null && tagsOverride.isArray()) {
            restored.tags.clear();
            for (JsonNode tag : tagsOverride) {
                String key = textOrNull(tag, "key");
                if (key != null) {
                    restored.tags.put(key, tag.path("value").asText(""));
                }
            }
        }
        targetKeyspace.tables.put(targetTableName, restored);

        ObjectNode restoredResponse = objectMapper.createObjectNode();
        restoredResponse.put("restoredTableARN", restored.arn);
        return restoredResponse;
    }

    public ObjectNode createType(JsonNode request) {
        String keyspaceName = requireText(request, "keyspaceName");
        String typeName = requireText(request, "typeName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        if (keyspace.types.containsKey(typeName)) {
            throw conflict("Type " + typeName + " already exists in keyspace " + keyspaceName + ".");
        }
        JsonNode fieldsNode = request.get("fieldDefinitions");
        if (fieldsNode == null || !fieldsNode.isArray() || fieldsNode.isEmpty()) {
            throw invalid("fieldDefinitions is required.");
        }
        UserType type = new UserType();
        type.keyspaceName = keyspaceName;
        type.typeName = typeName;
        type.keyspaceArn = keyspace.arn;
        type.lastModifiedTimestamp = nowSeconds();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode fieldNode : fieldsNode) {
            String fieldName = textOrNull(fieldNode, "name");
            String fieldType = textOrNull(fieldNode, "type");
            if (fieldName == null || fieldType == null) {
                throw invalid("Each field definition must include name and type.");
            }
            if (!names.add(fieldName)) {
                throw invalid("Duplicate field name " + fieldName + " in type " + typeName + ".");
            }
            Field field = new Field();
            field.name = fieldName;
            field.type = fieldType;
            type.fields.add(field);
        }
        keyspace.types.put(typeName, type);
        ObjectNode created = objectMapper.createObjectNode();
        created.put("keyspaceArn", keyspace.arn);
        created.put("typeName", typeName);
        return created;
    }

    public ObjectNode getType(JsonNode request) {
        return typeNode(requireType(request));
    }

    public ObjectNode deleteType(JsonNode request) {
        String keyspaceName = requireText(request, "keyspaceName");
        String typeName = requireText(request, "typeName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        UserType type = keyspace.types.get(typeName);
        if (type == null) {
            throw notFound("Type " + typeName + " not found in keyspace " + keyspaceName + ".");
        }
        for (Table table : keyspace.tables.values()) {
            if (table.schema != null && table.schema.toString().contains(typeName)) {
                throw conflict("Cannot delete type " + typeName + " because it is used by table "
                        + table.tableName + ".");
            }
        }
        for (UserType other : keyspace.types.values()) {
            if (other.typeName.equals(typeName)) {
                continue;
            }
            for (Field field : other.fields) {
                if (field.type != null && field.type.contains(typeName)) {
                    throw conflict("Cannot delete type " + typeName + " because it is used by type "
                            + other.typeName + ".");
                }
            }
        }
        keyspace.types.remove(typeName);
        ObjectNode deleted = objectMapper.createObjectNode();
        deleted.put("keyspaceArn", keyspace.arn);
        deleted.put("typeName", typeName);
        return deleted;
    }

    public ObjectNode listTypes(JsonNode request) {
        Keyspace keyspace = requireKeyspace(requireText(request, "keyspaceName"));
        ObjectNode listed = objectMapper.createObjectNode();
        ArrayNode list = listed.putArray("types");
        for (UserType type : keyspace.types.values()) {
            list.add(type.typeName);
        }
        return listed;
    }

    public ObjectNode tagResource(JsonNode request) {
        Tagged resource = requireTagged(requireText(request, "resourceArn"));
        resource.tags().putAll(readTagsField(request.get("tags")));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Tagged resource = requireTagged(requireText(request, "resourceArn"));
        JsonNode tags = request.get("tags");
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = textOrNull(tag, "key");
                if (key != null) {
                    resource.tags().remove(key);
                }
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Tagged resource = requireTagged(requireText(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("tags");
        writeTags(list, resource.tags());
        return response;
    }

    private ObjectNode keyspaceNode(Keyspace keyspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("keyspaceName", keyspace.name);
        node.put("resourceArn", keyspace.arn);
        node.put("replicationStrategy", keyspace.replicationStrategy);
        if (!keyspace.replicationRegions.isEmpty()) {
            ArrayNode regions = node.putArray("replicationRegions");
            keyspace.replicationRegions.forEach(regions::add);
        }
        return node;
    }

    private ObjectNode typeNode(UserType type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("keyspaceName", type.keyspaceName);
        node.put("typeName", type.typeName);
        ArrayNode fields = node.putArray("fieldDefinitions");
        for (Field field : type.fields) {
            ObjectNode fieldNode = fields.addObject();
            fieldNode.put("name", field.name);
            fieldNode.put("type", field.type);
        }
        node.put("lastModifiedTimestamp", type.lastModifiedTimestamp);
        node.put("status", type.status);
        ArrayNode referring = node.putArray("directReferringTables");
        type.directReferringTables.forEach(referring::add);
        ArrayNode parents = node.putArray("directParentTypes");
        type.directParentTypes.forEach(parents::add);
        node.put("maxNestingDepth", type.maxNestingDepth);
        node.put("keyspaceArn", type.keyspaceArn);
        return node;
    }

    private ObjectNode tableNode(Table table) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("keyspaceName", table.keyspaceName);
        node.put("tableName", table.tableName);
        node.put("resourceArn", table.arn);
        node.put("creationTimestamp", table.creationTimestamp);
        node.put("status", table.status);
        node.set("schemaDefinition", table.schema);
        ObjectNode capacity = node.putObject("capacitySpecification");
        capacity.put("throughputMode", table.throughputMode);
        if (table.readCapacityUnits != null) {
            capacity.put("readCapacityUnits", table.readCapacityUnits);
        }
        if (table.writeCapacityUnits != null) {
            capacity.put("writeCapacityUnits", table.writeCapacityUnits);
        }
        ObjectNode encryption = node.putObject("encryptionSpecification");
        encryption.put("type", "AWS_OWNED_KMS_KEY");
        ObjectNode pitr = node.putObject("pointInTimeRecovery");
        pitr.put("status", table.pitrStatus);
        if (table.earliestRestorableTimestamp != null) {
            pitr.put("earliestRestorableTimestamp", table.earliestRestorableTimestamp);
        }
        if (table.ttlStatus != null) {
            node.putObject("ttl").put("status", table.ttlStatus);
        }
        if (table.defaultTimeToLive != null) {
            node.put("defaultTimeToLive", table.defaultTimeToLive);
        }
        if (table.cdcStatus != null) {
            ObjectNode cdc = node.putObject("cdcSpecification");
            cdc.put("status", table.cdcStatus);
            if (table.cdcViewType != null) {
                cdc.put("viewType", table.cdcViewType);
            }
        }
        if (table.latestStreamArn != null) {
            node.put("latestStreamArn", table.latestStreamArn);
        }
        return node;
    }

    private void applyCapacity(Table table, JsonNode capacity, boolean requiredIfPresent) {
        if (capacity == null || capacity.isNull() || capacity.isMissingNode()) {
            if (requiredIfPresent) {
                return;
            }
            table.throughputMode = "PAY_PER_REQUEST";
            return;
        }
        String mode = textOrNull(capacity, "throughputMode");
        if (mode != null) {
            table.throughputMode = mode;
        }
        if (capacity.hasNonNull("readCapacityUnits")) {
            table.readCapacityUnits = capacity.get("readCapacityUnits").asLong();
        }
        if (capacity.hasNonNull("writeCapacityUnits")) {
            table.writeCapacityUnits = capacity.get("writeCapacityUnits").asLong();
        }
    }

    private void applyPitr(Table table, JsonNode pitr) {
        if (pitr == null || !pitr.isObject()) {
            return;
        }
        String status = textOrNull(pitr, "status");
        if (status == null) {
            return;
        }
        table.pitrStatus = status;
        if ("ENABLED".equals(status) && table.earliestRestorableTimestamp == null) {
            table.earliestRestorableTimestamp = nowSeconds();
        }
        if ("DISABLED".equals(status)) {
            table.earliestRestorableTimestamp = null;
        }
    }

    private void applyCdc(Table table, JsonNode cdc) {
        if (cdc == null || !cdc.isObject()) {
            return;
        }
        String status = textOrNull(cdc, "status");
        if (status == null) {
            return;
        }
        table.cdcStatus = status;
        String viewType = textOrNull(cdc, "viewType");
        if (viewType != null) {
            table.cdcViewType = viewType;
        } else if ("ENABLED".equals(status) && table.cdcViewType == null) {
            table.cdcViewType = "NEW_AND_OLD_IMAGES";
        }
        if ("ENABLED".equals(status) && table.latestStreamArn == null) {
            table.latestStreamArn = table.arn + "/stream/" + Instant.now();
        }
        if ("DISABLED".equals(status)) {
            table.latestStreamArn = null;
        }
    }

    private Keyspace requireKeyspace(String name) {
        Keyspace keyspace = keyspaces.get(name);
        if (keyspace == null) {
            throw notFound("Keyspace " + name + " not found.");
        }
        return keyspace;
    }

    private Table requireTable(JsonNode request) {
        String keyspaceName = requireText(request, "keyspaceName");
        String tableName = requireText(request, "tableName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        Table table = keyspace.tables.get(tableName);
        if (table == null) {
            throw notFound("Table " + tableName + " not found in keyspace " + keyspaceName + ".");
        }
        return table;
    }

    private UserType requireType(JsonNode request) {
        String keyspaceName = requireText(request, "keyspaceName");
        String typeName = requireText(request, "typeName");
        Keyspace keyspace = requireKeyspace(keyspaceName);
        UserType type = keyspace.types.get(typeName);
        if (type == null) {
            throw notFound("Type " + typeName + " not found in keyspace " + keyspaceName + ".");
        }
        return type;
    }

    private Tagged requireTagged(String arn) {
        if (arn == null) {
            throw invalid("resourceArn is required.");
        }
        for (Keyspace keyspace : keyspaces.values()) {
            if (arn.equals(keyspace.arn)) {
                return keyspace::tags;
            }
            for (Table table : keyspace.tables.values()) {
                if (arn.equals(table.arn)) {
                    return table::tags;
                }
            }
        }
        throw notFound("Resource " + arn + " not found.");
    }

    private String keyspaceArn(String region, String name) {
        return regionResolver.buildArn("cassandra", region, "/keyspace/" + name + "/");
    }

    private String tableArn(String region, String keyspaceName, String tableName) {
        return regionResolver.buildArn("cassandra", region, "/keyspace/" + keyspaceName + "/table/" + tableName);
    }

    private static Map<String, String> readTagsField(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "key");
                if (key != null) {
                    tags.put(key, tag.path("value").asText(""));
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    @FunctionalInterface
    private interface Tagged {
        Map<String, String> tags();
    }
}
