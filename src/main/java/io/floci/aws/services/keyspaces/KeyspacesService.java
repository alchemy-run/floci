package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.applicationautoscaling.ApplicationAutoScalingService;
import io.floci.aws.services.applicationautoscaling.model.PredefinedMetricSpecification;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingPolicy;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.floci.aws.services.keyspaces.KeyspacesStore.KEYSPACE;
import static io.floci.aws.services.keyspaces.KeyspacesStore.STREAM;
import static io.floci.aws.services.keyspaces.KeyspacesStore.TABLE;
import static io.floci.aws.services.keyspaces.KeyspacesStore.TYPE;
import static io.floci.aws.services.keyspaces.KeyspacesStore.conflict;
import static io.floci.aws.services.keyspaces.KeyspacesStore.meta;
import static io.floci.aws.services.keyspaces.KeyspacesStore.notFound;
import static io.floci.aws.services.keyspaces.KeyspacesStore.text;
import static io.floci.aws.services.keyspaces.KeyspacesStore.validation;
import static io.floci.aws.services.keyspaces.KeyspacesStore.view;

/**
 * Amazon Keyspaces' control plane: keyspaces (single- or multi-Region), tables, user-defined types, tags, and the
 * table settings Keyspaces manages: capacity mode, encryption, point-in-time recovery, TTL, client-side timestamps,
 * auto scaling (registered with Application Auto Scaling, as Keyspaces does), replicas, warm throughput and CDC
 * streams.
 *
 * <p>Tables are {@code CREATING} for a transition before they are {@code ACTIVE}, {@code UPDATING} after an update and
 * {@code DELETING} before they're gone; a restored table is {@code RESTORING}. Schemas are checked as Cassandra checks
 * them: keys must be columns, collections can't be keys unless frozen, static columns need clustering columns,
 * counter tables hold only counters, and user-defined types must exist. A table with point-in-time recovery keeps its
 * mutations for 35 days and restores to any second of them; CDC streams read the same log.
 */
@ApplicationScoped
public class KeyspacesService {

    static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_]{0,47}");
    static final Set<String> SYSTEM_KEYSPACES = Set.of("system", "system_schema", "system_schema_mcs",
            "system_multiregion_info");
    static final long PITR_WINDOW_MILLIS = 35L * 86_400_000L;
    static final long STREAM_RETENTION_MILLIS = 86_400_000L;
    static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);

    private final KeyspacesStore store;
    private final KmsService kms;
    private final Instance<ApplicationAutoScalingService> autoScaling;

    @Inject
    public KeyspacesService(KeyspacesStore store, KmsService kms, Instance<ApplicationAutoScalingService> autoScaling) {
        this.store = store;
        this.kms = kms;
        this.autoScaling = autoScaling;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private ObjectNode empty() {
        return mapper().createObjectNode();
    }

    // ---------------------------------------------------------------- pending statuses

    void pending(ObjectNode record, String field, String now, String then) {
        record.put(field, now);
        meta(record).put("settleAt", System.currentTimeMillis() + store.config().transitionMillis())
                .put("settleTo", then).put("settleField", field);
    }

    ObjectNode settle(String account, String region, String kind, ObjectNode record) {
        if (record == null) {
            return null;
        }
        ObjectNode m = meta(record);
        if (m.has("settleAt") && m.get("settleAt").asLong() <= System.currentTimeMillis()) {
            String to = m.get("settleTo").asText();
            String field = m.get("settleField").asText();
            String path = m.path("path").asText();
            m.remove(List.of("settleAt", "settleTo", "settleField"));
            if ("DELETED".equals(to)) {
                store.delete(account, region, kind, path);
                return null;
            }
            record.put(field, to);
            store.put(account, region, kind, path, record);
        }
        return record;
    }

    ObjectNode find(String account, String region, String kind, String path) {
        return settle(account, region, kind, store.get(account, region, kind, path).orElse(null));
    }

    // ---------------------------------------------------------------- keyspaces

    private static void checkName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw validation("The " + what + " name " + name + " is not valid: use up to 48 letters, digits and "
                    + "underscores, starting with a letter or digit.");
        }
    }

    ObjectNode keyspace(String account, String region, String name) {
        if (SYSTEM_KEYSPACES.contains(name)) {
            ObjectNode system = empty();
            system.put("keyspaceName", name).put("resourceArn", store.keyspaceArn(account, region, name))
                    .put("replicationStrategy", "SINGLE_REGION");
            return system;
        }
        ObjectNode keyspace = find(account, region, KEYSPACE, name);
        if (keyspace == null || "DELETING".equals(meta(keyspace).path("status").asText())) {
            throw notFound("Keyspace " + name + " doesn't exist.", store.keyspaceArn(account, region, name));
        }
        return keyspace;
    }

    private ArrayNode replicationRegions(String region, JsonNode spec) {
        ArrayNode regions = mapper().createArrayNode();
        if (spec == null || !"MULTI_REGION".equals(text(spec, "replicationStrategy"))) {
            if (spec != null && spec.has("regionList") && !spec.get("regionList").isEmpty()) {
                throw validation("A SINGLE_REGION keyspace has no regionList.");
            }
            return regions;
        }
        Set<String> list = new LinkedHashSet<>();
        spec.path("regionList").forEach(r -> list.add(r.asText()));
        if (!list.contains(region)) {
            throw validation("The regionList of a MULTI_REGION keyspace must include " + region + ".");
        }
        if (list.size() < 2) {
            throw validation("A MULTI_REGION keyspace replicates to at least two Regions.");
        }
        for (String r : list) {
            if (!AwsRegions.partitionFor(r).equals(AwsRegions.partitionFor(region))) {
                throw validation("The Region " + r + " is not in " + region + "'s partition.");
            }
        }
        list.forEach(regions::add);
        return regions;
    }

    public ObjectNode createKeyspace(JsonNode in, String region) {
        String account = store.account();
        String name = text(in, "keyspaceName");
        checkName(name, "keyspace");
        if (SYSTEM_KEYSPACES.contains(name.toLowerCase(Locale.ROOT))
                || find(account, region, KEYSPACE, name) != null) {
            throw conflict("Keyspace " + name + " already exists.");
        }
        if (store.list(account, region, KEYSPACE, "").size() >= store.config().maxKeyspaces()) {
            throw KeyspacesStore.quota("The account has reached its quota of " + store.config().maxKeyspaces()
                    + " keyspaces.");
        }
        ObjectNode keyspace = store.record();
        String arn = store.keyspaceArn(account, region, name);
        JsonNode spec = in.get("replicationSpecification");
        ArrayNode regions = replicationRegions(region, spec);
        keyspace.put("keyspaceName", name).put("resourceArn", arn)
                .put("replicationStrategy", regions.isEmpty() ? "SINGLE_REGION" : "MULTI_REGION");
        if (!regions.isEmpty()) {
            keyspace.set("replicationRegions", regions);
        }
        meta(keyspace).set("tags", KeyspacesStore.tagsOf(mapper(), in.get("tags")));
        pending(keyspace, "_status", "CREATING", "ACTIVE");
        meta(keyspace).put("status", "ACTIVE");
        store.put(account, region, KEYSPACE, name, keyspace);
        return empty().put("resourceArn", arn);
    }

    public ObjectNode getKeyspace(JsonNode in, String region) {
        String account = store.account();
        ObjectNode keyspace = keyspace(account, region, text(in, "keyspaceName"));
        ObjectNode out = view(keyspace);
        out.remove("_status");
        if ("MULTI_REGION".equals(keyspace.path("replicationStrategy").asText())) {
            ArrayNode statuses = out.putArray("replicationGroupStatuses");
            String status = keyspace.path("_status").asText("ACTIVE");
            for (JsonNode r : keyspace.path("replicationRegions")) {
                boolean added = meta(keyspace).path("addedRegions").has(r.asText());
                statuses.addObject().put("region", r.asText()).put("keyspaceStatus", added ? status : "ACTIVE")
                        .put("tablesReplicationProgress", added && !"ACTIVE".equals(status) ? "0%" : "100%");
            }
        }
        return out;
    }

    public ObjectNode listKeyspaces(JsonNode in, String region) {
        String account = store.account();
        List<ObjectNode> items = new ArrayList<>();
        for (String system : List.of("system", "system_multiregion_info", "system_schema", "system_schema_mcs")) {
            items.add(empty().put("keyspaceName", system).put("resourceArn", store.keyspaceArn(account, region,
                    system)).put("replicationStrategy", "SINGLE_REGION"));
        }
        for (ObjectNode keyspace : store.list(account, region, KEYSPACE, "")) {
            ObjectNode settled = settle(account, region, KEYSPACE, keyspace);
            if (settled == null || "DELETING".equals(meta(settled).path("status").asText())) {
                continue;
            }
            ObjectNode item = empty();
            item.put("keyspaceName", settled.path("keyspaceName").asText())
                    .put("resourceArn", settled.path("resourceArn").asText())
                    .put("replicationStrategy", settled.path("replicationStrategy").asText());
            if (settled.has("replicationRegions")) {
                item.set("replicationRegions", settled.get("replicationRegions"));
            }
            items.add(item);
        }
        ObjectNode out = empty();
        KeyspacesStore.page(items, in, out, "keyspaces", 100);
        return out;
    }

    public ObjectNode updateKeyspace(JsonNode in, String region) {
        String account = store.account();
        String name = text(in, "keyspaceName");
        if (SYSTEM_KEYSPACES.contains(name)) {
            throw validation("System keyspaces can't be changed.");
        }
        ObjectNode keyspace = keyspace(account, region, name);
        ArrayNode regions = replicationRegions(region, in.get("replicationSpecification"));
        if (regions.isEmpty()) {
            throw validation("A keyspace can only be updated to add Regions to a MULTI_REGION replication.");
        }
        Set<String> current = new LinkedHashSet<>();
        keyspace.path("replicationRegions").forEach(r -> current.add(r.asText()));
        for (String r : current) {
            boolean kept = false;
            for (JsonNode n : regions) {
                kept |= n.asText().equals(r);
            }
            if (!kept) {
                throw validation("Regions can't be removed from a keyspace: " + r + ".");
            }
        }
        ObjectNode added = meta(keyspace).putObject("addedRegions");
        for (JsonNode r : regions) {
            if (!current.contains(r.asText()) && !current.isEmpty() || current.isEmpty() && !r.asText().equals(
                    region)) {
                added.put(r.asText(), true);
            }
        }
        keyspace.put("replicationStrategy", "MULTI_REGION").set("replicationRegions", regions);
        if (in.has("clientSideTimestamps")) {
            for (ObjectNode table : tables(account, region, name)) {
                table.set("clientSideTimestamps", in.get("clientSideTimestamps").deepCopy());
                store.put(account, region, TABLE, meta(table).path("path").asText(), table);
            }
        }
        pending(keyspace, "_status", "UPDATING", "ACTIVE");
        store.put(account, region, KEYSPACE, name, keyspace);
        return empty().put("resourceArn", keyspace.path("resourceArn").asText());
    }

    public ObjectNode deleteKeyspace(JsonNode in, String region) {
        String account = store.account();
        String name = text(in, "keyspaceName");
        if (SYSTEM_KEYSPACES.contains(name)) {
            throw validation("System keyspaces can't be deleted.");
        }
        ObjectNode keyspace = keyspace(account, region, name);
        for (ObjectNode table : tables(account, region, name)) {
            if ("RESTORING".equals(table.path("status").asText()) || "CREATING".equals(table.path("status")
                    .asText())) {
                throw conflict("Table " + table.path("tableName").asText() + " is " + table.path("status").asText()
                        + "; the keyspace can be deleted once it's ACTIVE.");
            }
        }
        for (ObjectNode table : tables(account, region, name)) {
            dropTable(account, region, table);
        }
        for (ObjectNode type : store.list(account, region, TYPE, name + "/")) {
            store.delete(account, region, TYPE, meta(type).path("path").asText());
        }
        meta(keyspace).put("status", "DELETING");
        pending(keyspace, "_status", "DELETING", "DELETED");
        store.put(account, region, KEYSPACE, name, keyspace);
        return empty();
    }

    // ---------------------------------------------------------------- types

    private ObjectNode type(String account, String region, String keyspace, String name) {
        ObjectNode type = find(account, region, TYPE, keyspace + "/" + name);
        if (type == null) {
            throw notFound("Type " + name + " doesn't exist in keyspace " + keyspace + ".",
                    store.keyspaceArn(account, region, keyspace));
        }
        return type;
    }

    boolean typeExists(String account, String region, String keyspace, String name) {
        ObjectNode type = find(account, region, TYPE, keyspace + "/" + name);
        return type != null && !"DELETING".equals(type.path("status").asText());
    }

    public ObjectNode createType(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        keyspace(account, region, keyspace);
        String name = text(in, "typeName");
        checkName(name, "type");
        if (CqlType.NATIVE.contains(name.toLowerCase(Locale.ROOT)) || Set.of("list", "set", "map", "tuple",
                "frozen", "byte", "smallint").contains(name.toLowerCase(Locale.ROOT))) {
            throw validation("The type name " + name + " is reserved.");
        }
        if (find(account, region, TYPE, keyspace + "/" + name) != null) {
            throw conflict("Type " + name + " already exists in keyspace " + keyspace + ".");
        }
        ArrayNode fields = mapper().createArrayNode();
        Set<String> names = new LinkedHashSet<>();
        int depth = 1;
        for (JsonNode field : in.path("fieldDefinitions")) {
            String fieldName = field.path("name").asText();
            if (!names.add(fieldName)) {
                throw validation("The field " + fieldName + " is defined twice.");
            }
            CqlType type = CqlType.parse(field.path("type").asText(), udt -> typeExists(account, region, keyspace,
                    udt));
            if ("counter".equals(type.name())) {
                throw validation("A user-defined type can't have a counter field.");
            }
            if (type.isUdt() && !type.frozen() || type.isCollection() && !type.frozen()
                    && containsUdt(type)) {
                throw validation("A user-defined type inside another must be frozen: " + type + ".");
            }
            List<String> nested = new ArrayList<>();
            type.udts(nested);
            for (String udt : nested) {
                depth = Math.max(depth, 1 + type(account, region, keyspace, udt).path("maxNestingDepth").asInt(1));
            }
            fields.addObject().put("name", fieldName).put("type", type.toString());
        }
        if (fields.isEmpty()) {
            throw validation("A type needs at least one field.");
        }
        if (depth > 8) {
            throw validation("User-defined types can nest at most 8 levels.");
        }
        ObjectNode type = store.record();
        type.put("keyspaceName", keyspace).put("typeName", name).set("fieldDefinitions", fields);
        type.put("lastModifiedTimestamp", KeyspacesStore.epoch(System.currentTimeMillis()))
                .put("maxNestingDepth", depth)
                .put("keyspaceArn", store.keyspaceArn(account, region, keyspace));
        pending(type, "status", "CREATING", "ACTIVE");
        store.put(account, region, TYPE, keyspace + "/" + name, type);
        return empty().put("keyspaceArn", store.keyspaceArn(account, region, keyspace)).put("typeName", name);
    }

    private static boolean containsUdt(CqlType type) {
        List<String> udts = new ArrayList<>();
        type.udts(udts);
        return !udts.isEmpty();
    }

    public ObjectNode getType(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        keyspace(account, region, keyspace);
        String name = text(in, "typeName");
        ObjectNode out = view(type(account, region, keyspace, name));
        ArrayNode tables = out.putArray("directReferringTables");
        for (ObjectNode table : tables(account, region, keyspace)) {
            for (JsonNode column : table.path("schemaDefinition").path("allColumns")) {
                if (references(column.path("type").asText(), name)) {
                    tables.add(table.path("tableName").asText());
                    break;
                }
            }
        }
        ArrayNode parents = out.putArray("directParentTypes");
        for (ObjectNode other : store.list(account, region, TYPE, keyspace + "/")) {
            for (JsonNode field : other.path("fieldDefinitions")) {
                if (references(field.path("type").asText(), name)) {
                    parents.add(other.path("typeName").asText());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean references(String type, String udt) {
        return Pattern.compile("(^|[<,\\s])" + Pattern.quote(udt) + "($|[>,\\s])").matcher(type).find();
    }

    public ObjectNode listTypes(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        keyspace(account, region, keyspace);
        List<JsonNode> names = new ArrayList<>();
        for (ObjectNode type : store.list(account, region, TYPE, keyspace + "/")) {
            ObjectNode settled = settle(account, region, TYPE, type);
            if (settled != null) {
                names.add(mapper().getNodeFactory().textNode(settled.path("typeName").asText()));
            }
        }
        ObjectNode out = empty();
        KeyspacesStore.page(names, in, out, "types", 100);
        return out;
    }

    public ObjectNode deleteType(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        keyspace(account, region, keyspace);
        String name = text(in, "typeName");
        ObjectNode type = type(account, region, keyspace, name);
        if ("DELETING".equals(type.path("status").asText())) {
            throw conflict("Type " + name + " is already being deleted.");
        }
        ObjectNode described = getType(in, region);
        if (!described.path("directReferringTables").isEmpty() || !described.path("directParentTypes").isEmpty()) {
            throw validation("Type " + name + " is used by " + (!described.path("directReferringTables").isEmpty()
                    ? "table " + described.path("directReferringTables").get(0).asText()
                    : "type " + described.path("directParentTypes").get(0).asText()) + ".");
        }
        pending(type, "status", "DELETING", "DELETED");
        store.put(account, region, TYPE, keyspace + "/" + name, type);
        return empty().put("keyspaceArn", store.keyspaceArn(account, region, keyspace)).put("typeName", name);
    }

    // ---------------------------------------------------------------- tables

    List<ObjectNode> tables(String account, String region, String keyspace) {
        List<ObjectNode> tables = new ArrayList<>();
        for (ObjectNode table : store.list(account, region, TABLE, keyspace + "/")) {
            ObjectNode settled = settle(account, region, TABLE, table);
            if (settled != null && !"DELETING".equals(settled.path("status").asText())) {
                tables.add(settled);
            }
        }
        return tables;
    }

    ObjectNode table(String account, String region, String keyspace, String name) {
        keyspace(account, region, keyspace);
        ObjectNode table = find(account, region, TABLE, keyspace + "/" + name);
        if (table == null) {
            throw notFound("Table " + keyspace + "." + name + " doesn't exist.", store.tableArn(account, region,
                    keyspace, name));
        }
        return table;
    }

    /** Checks a schema as Cassandra does and returns it with types in canonical form. */
    ObjectNode schema(String account, String region, String keyspace, JsonNode schema) {
        Map<String, CqlType> columns = new LinkedHashMap<>();
        ObjectNode out = empty();
        ArrayNode all = out.putArray("allColumns");
        for (JsonNode column : schema.path("allColumns")) {
            String name = column.path("name").asText();
            if (columns.containsKey(name)) {
                throw validation("The column " + name + " is defined twice.");
            }
            CqlType type = CqlType.parse(column.path("type").asText(), udt -> typeExists(account, region, keyspace,
                    udt));
            columns.put(name, type);
            all.addObject().put("name", name).put("type", type.toString());
        }
        Set<String> keys = new LinkedHashSet<>();
        ArrayNode partition = out.putArray("partitionKeys");
        for (JsonNode key : schema.path("partitionKeys")) {
            String name = key.path("name").asText();
            keyColumn(columns, keys, name, "partition");
            partition.addObject().put("name", name);
        }
        if (partition.isEmpty()) {
            throw validation("A table needs at least one partition key.");
        }
        ArrayNode clustering = out.putArray("clusteringKeys");
        for (JsonNode key : schema.path("clusteringKeys")) {
            String name = key.path("name").asText();
            keyColumn(columns, keys, name, "clustering");
            clustering.addObject().put("name", name).put("orderBy", key.path("orderBy").asText("ASC"));
        }
        ArrayNode statics = out.putArray("staticColumns");
        for (JsonNode column : schema.path("staticColumns")) {
            String name = column.path("name").asText();
            if (!columns.containsKey(name)) {
                throw validation("The static column " + name + " is not a column of the table.");
            }
            if (keys.contains(name)) {
                throw validation("The key column " + name + " can't be static.");
            }
            if (clustering.isEmpty()) {
                throw validation("Static columns need clustering columns.");
            }
            statics.addObject().put("name", name);
        }
        boolean counters = columns.entrySet().stream().anyMatch(e -> !keys.contains(e.getKey())
                && "counter".equals(e.getValue().name()));
        boolean others = columns.entrySet().stream().anyMatch(e -> !keys.contains(e.getKey())
                && !"counter".equals(e.getValue().name()));
        if (counters && others) {
            throw validation("A table with counter columns can only have counter columns besides its keys.");
        }
        return out;
    }

    private static void keyColumn(Map<String, CqlType> columns, Set<String> keys, String name, String kind) {
        CqlType type = columns.get(name);
        if (type == null) {
            throw validation("The " + kind + " key " + name + " is not a column of the table.");
        }
        if (!keys.add(name)) {
            throw validation("The column " + name + " is a key twice.");
        }
        if (type.multiCell()) {
            throw validation("The " + kind + " key " + name + " can't be a non-frozen collection or type.");
        }
        if ("counter".equals(type.name()) || "duration".equals(type.name())) {
            throw validation("The " + kind + " key " + name + " can't be a " + type.name() + ".");
        }
    }

    private ObjectNode capacity(JsonNode spec, ObjectNode current) {
        ObjectNode out = empty();
        String mode = spec == null ? "PAY_PER_REQUEST" : text(spec, "throughputMode");
        out.put("throughputMode", mode);
        if ("PROVISIONED".equals(mode)) {
            if (!spec.hasNonNull("readCapacityUnits") || !spec.hasNonNull("writeCapacityUnits")) {
                throw validation("PROVISIONED capacity needs readCapacityUnits and writeCapacityUnits.");
            }
            out.put("readCapacityUnits", spec.get("readCapacityUnits").asLong())
                    .put("writeCapacityUnits", spec.get("writeCapacityUnits").asLong());
        } else if (spec != null && (spec.has("readCapacityUnits") || spec.has("writeCapacityUnits"))) {
            throw validation("PAY_PER_REQUEST capacity has no capacity units.");
        }
        if (current != null && current.has("lastUpdateToPayPerRequestTimestamp")) {
            out.set("lastUpdateToPayPerRequestTimestamp", current.get("lastUpdateToPayPerRequestTimestamp"));
        }
        if ("PAY_PER_REQUEST".equals(mode) && current != null && "PROVISIONED".equals(current.path("throughputMode")
                .asText())) {
            out.put("lastUpdateToPayPerRequestTimestamp", KeyspacesStore.epoch(System.currentTimeMillis()));
        }
        return out;
    }

    private ObjectNode encryption(JsonNode spec, String region) {
        ObjectNode out = empty();
        String type = spec == null ? "AWS_OWNED_KMS_KEY" : text(spec, "type");
        out.put("type", type);
        if ("CUSTOMER_MANAGED_KMS_KEY".equals(type)) {
            String key = text(spec, "kmsKeyIdentifier");
            if (key == null) {
                throw validation("CUSTOMER_MANAGED_KMS_KEY encryption needs a kmsKeyIdentifier.");
            }
            String arn = kms.findKeyArn(key, region).orElseThrow(() -> validation("The KMS key " + key
                    + " doesn't exist."));
            out.put("kmsKeyIdentifier", arn);
        }
        return out;
    }

    public ObjectNode createTable(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        if (SYSTEM_KEYSPACES.contains(keyspace)) {
            throw validation("Tables can't be created in system keyspaces.");
        }
        ObjectNode ks = keyspace(account, region, keyspace);
        String name = text(in, "tableName");
        checkName(name, "table");
        if (find(account, region, TABLE, keyspace + "/" + name) != null) {
            throw conflict("Table " + keyspace + "." + name + " already exists.");
        }
        long count = store.list(account, region, TABLE, "").size();
        if (count >= store.config().maxTables()) {
            throw KeyspacesStore.quota("The account has reached its quota of " + store.config().maxTables()
                    + " tables.");
        }
        ObjectNode table = store.record();
        long now = System.currentTimeMillis();
        table.put("keyspaceName", keyspace).put("tableName", name)
                .put("resourceArn", store.tableArn(account, region, keyspace, name))
                .put("creationTimestamp", KeyspacesStore.epoch(now));
        table.set("schemaDefinition", schema(account, region, keyspace, in.get("schemaDefinition")));
        settings(account, region, table, in, true);
        if (in.has("comment")) {
            table.set("comment", in.get("comment").deepCopy());
        }
        if ("MULTI_REGION".equals(ks.path("replicationStrategy").asText())) {
            replicas(table, ks, in.get("replicaSpecifications"));
        } else if (in.has("replicaSpecifications") && !in.get("replicaSpecifications").isEmpty()) {
            throw validation("Replica specifications are for tables in MULTI_REGION keyspaces.");
        }
        meta(table).set("tags", KeyspacesStore.tagsOf(mapper(), in.get("tags")));
        pending(table, "status", "CREATING", "ACTIVE");
        store.put(account, region, TABLE, keyspace + "/" + name, table);
        applyAutoScaling(account, region, table, in.get("autoScalingSpecification"));
        return empty().put("resourceArn", table.path("resourceArn").asText());
    }

    /** Applies the table settings a create or update gives. */
    private void settings(String account, String region, ObjectNode table, JsonNode in, boolean creating) {
        if (in.has("capacitySpecification") || creating) {
            table.set("capacitySpecification", capacity(in.get("capacitySpecification"),
                    creating ? null : (ObjectNode) table.get("capacitySpecification")));
        }
        if (in.has("encryptionSpecification") || creating) {
            table.set("encryptionSpecification", encryption(in.get("encryptionSpecification"), region));
        }
        if (in.has("pointInTimeRecovery") || creating) {
            String status = in.has("pointInTimeRecovery") ? in.get("pointInTimeRecovery").path("status").asText()
                    : "DISABLED";
            ObjectNode pitr = empty().put("status", status);
            if ("ENABLED".equals(status)) {
                long since = "ENABLED".equals(table.path("pointInTimeRecovery").path("status").asText())
                        ? meta(table).path("pitrSince").asLong(System.currentTimeMillis())
                        : System.currentTimeMillis();
                meta(table).put("pitrSince", since);
            } else {
                meta(table).remove("pitrSince");
            }
            table.set("pointInTimeRecovery", pitr);
        }
        if (in.has("ttl")) {
            table.set("ttl", empty().put("status", "ENABLED"));
        }
        if (in.has("defaultTimeToLive")) {
            int ttl = in.get("defaultTimeToLive").asInt();
            if (ttl > 0 && !table.has("ttl")) {
                throw validation("A default time to live needs TTL enabled on the table.");
            }
            table.put("defaultTimeToLive", ttl);
        } else if (creating) {
            table.put("defaultTimeToLive", 0);
        }
        if (in.has("clientSideTimestamps")) {
            table.set("clientSideTimestamps", empty().put("status", "ENABLED"));
        }
        if (in.has("warmThroughputSpecification")) {
            JsonNode warm = in.get("warmThroughputSpecification");
            table.set("warmThroughputSpecification", empty()
                    .put("readUnitsPerSecond", warm.path("readUnitsPerSecond").asLong(12_000))
                    .put("writeUnitsPerSecond", warm.path("writeUnitsPerSecond").asLong(4_000))
                    .put("status", creating ? "AVAILABLE" : "UPDATING"));
            if (!creating) {
                meta(table).put("warmUpdating", true);
            }
        } else if (creating) {
            table.set("warmThroughputSpecification", empty().put("readUnitsPerSecond", 12_000)
                    .put("writeUnitsPerSecond", 4_000).put("status", "AVAILABLE"));
        }
        if (in.has("autoScalingSpecification") && !"PROVISIONED".equals(table.path("capacitySpecification")
                .path("throughputMode").asText())) {
            throw validation("Auto scaling needs PROVISIONED capacity.");
        }
        if (in.has("cdcSpecification")) {
            cdc(account, region, table, in.get("cdcSpecification"));
        }
    }

    private void replicas(ObjectNode table, ObjectNode keyspace, JsonNode specs) {
        Set<String> regions = new LinkedHashSet<>();
        keyspace.path("replicationRegions").forEach(r -> regions.add(r.asText()));
        Map<String, JsonNode> byRegion = new HashMap<>();
        if (specs != null) {
            for (JsonNode spec : specs) {
                String r = spec.path("region").asText();
                if (!regions.contains(r)) {
                    throw validation("The keyspace doesn't replicate to " + r + ".");
                }
                byRegion.put(r, spec);
            }
        }
        meta(table).set("replicas", mapper().valueToTree(byRegion));
    }

    // ---------------------------------------------------------------- CDC

    private void cdc(String account, String region, ObjectNode table, JsonNode spec) {
        String status = text(spec, "status");
        String keyspace = table.path("keyspaceName").asText();
        String name = table.path("tableName").asText();
        boolean enabled = "ENABLED".equals(table.path("cdcSpecification").path("status").asText())
                || "ENABLING".equals(table.path("cdcSpecification").path("status").asText());
        if ("ENABLED".equals(status)) {
            String viewType = spec.hasNonNull("viewType") ? text(spec, "viewType") : "NEW_AND_OLD_IMAGES";
            if (enabled) {
                if (!viewType.equals(table.path("cdcSpecification").path("viewType").asText())) {
                    throw validation("Disable CDC before changing the stream's view type.");
                }
                return;
            }
            long now = System.currentTimeMillis();
            String label = LABEL.format(Instant.ofEpochMilli(now));
            String arn = table.path("resourceArn").asText() + "/stream/" + label;
            ObjectNode stream = store.record();
            stream.put("streamArn", arn).put("streamLabel", label).put("keyspaceName", keyspace)
                    .put("tableName", name).put("streamViewType", viewType)
                    .put("creationRequestDateTime", KeyspacesStore.epoch(now));
            meta(stream).put("createdAt", now).put("shard", "shardId-" + String.format("%020d", now) + "-"
                    + Long.toHexString(now & 0xffffffffL));
            if ("TABLE".equals(text(spec, "propagateTags"))) {
                meta(stream).set("tags", meta(table).path("tags").deepCopy());
            } else {
                meta(stream).set("tags", KeyspacesStore.tagsOf(mapper(), spec.get("tags")));
            }
            pending(stream, "streamStatus", "ENABLING", "ENABLED");
            store.put(account, region, STREAM, keyspace + "/" + name + "/" + label, stream);
            table.set("cdcSpecification", empty().put("status", "ENABLED").put("viewType", viewType));
            table.put("latestStreamArn", arn);
        } else if ("DISABLED".equals(status)) {
            if (enabled) {
                String arn = table.path("latestStreamArn").asText();
                ObjectNode stream = stream(account, region, arn);
                if (stream != null) {
                    meta(stream).put("endedAt", System.currentTimeMillis());
                    pending(stream, "streamStatus", "DISABLING", "DISABLED");
                    store.put(account, region, STREAM, meta(stream).path("path").asText(), stream);
                }
            }
            table.set("cdcSpecification", empty().put("status", "DISABLED"));
        } else {
            throw validation("CDC can be ENABLED or DISABLED.");
        }
    }

    ObjectNode stream(String account, String region, String arn) {
        int at = arn.indexOf("/keyspace/");
        if (at < 0) {
            return null;
        }
        String[] parts = arn.substring(at + "/keyspace/".length()).split("/");
        if (parts.length != 5 || !"table".equals(parts[1]) || !"stream".equals(parts[3])) {
            return null;
        }
        return find(account, region, STREAM, parts[0] + "/" + parts[2] + "/" + parts[4]);
    }

    // ---------------------------------------------------------------- get / list / update / delete

    public ObjectNode getTable(JsonNode in, String region) {
        String account = store.account();
        ObjectNode table = table(account, region, text(in, "keyspaceName"), text(in, "tableName"));
        ObjectNode out = view(table);
        if (meta(table).path("warmUpdating").asBoolean(false) && !"UPDATING".equals(table.path("status")
                .asText())) {
            ((ObjectNode) table.get("warmThroughputSpecification")).put("status", "AVAILABLE");
            meta(table).remove("warmUpdating");
            store.put(account, region, TABLE, meta(table).path("path").asText(), table);
            out.set("warmThroughputSpecification", table.get("warmThroughputSpecification").deepCopy());
        }
        if ("ENABLED".equals(table.path("pointInTimeRecovery").path("status").asText())) {
            long since = Math.max(meta(table).path("pitrSince").asLong(), System.currentTimeMillis()
                    - PITR_WINDOW_MILLIS);
            ((ObjectNode) out.get("pointInTimeRecovery")).put("earliestRestorableTimestamp",
                    KeyspacesStore.epoch(since));
        }
        JsonNode replicas = meta(table).get("replicas");
        if (replicas != null) {
            ObjectNode ks = keyspace(account, region, table.path("keyspaceName").asText());
            ArrayNode summaries = out.putArray("replicaSpecifications");
            for (JsonNode r : ks.path("replicationRegions")) {
                ObjectNode summary = summaries.addObject().put("region", r.asText())
                        .put("status", table.path("status").asText());
                ObjectNode capacity = table.get("capacitySpecification").deepCopy();
                JsonNode spec = replicas.get(r.asText());
                if (spec != null && spec.has("readCapacityUnits") && capacity.has("readCapacityUnits")) {
                    capacity.put("readCapacityUnits", spec.get("readCapacityUnits").asLong());
                }
                summary.set("capacitySpecification", capacity);
                summary.set("warmThroughputSpecification", table.get("warmThroughputSpecification").deepCopy());
            }
        }
        return out;
    }

    public ObjectNode listTables(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        keyspace(account, region, keyspace);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode table : tables(account, region, keyspace)) {
            items.add(empty().put("keyspaceName", keyspace).put("tableName", table.path("tableName").asText())
                    .put("resourceArn", table.path("resourceArn").asText()));
        }
        ObjectNode out = empty();
        KeyspacesStore.page(items, in, out, "tables", 100);
        return out;
    }

    public ObjectNode updateTable(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        String name = text(in, "tableName");
        ObjectNode table = table(account, region, keyspace, name);
        if (!"ACTIVE".equals(table.path("status").asText())) {
            throw conflict("Table " + keyspace + "." + name + " is " + table.path("status").asText()
                    + "; it can be updated once it's ACTIVE.");
        }
        if (in.has("addColumns")) {
            ObjectNode schema = (ObjectNode) table.get("schemaDefinition");
            ArrayNode all = (ArrayNode) schema.get("allColumns");
            boolean counterTable = false;
            boolean regularTable = false;
            Set<String> keys = new LinkedHashSet<>();
            schema.path("partitionKeys").forEach(k -> keys.add(k.path("name").asText()));
            schema.path("clusteringKeys").forEach(k -> keys.add(k.path("name").asText()));
            for (JsonNode column : all) {
                if (!keys.contains(column.path("name").asText())) {
                    if ("counter".equals(column.path("type").asText())) {
                        counterTable = true;
                    } else {
                        regularTable = true;
                    }
                }
            }
            for (JsonNode column : in.get("addColumns")) {
                String columnName = column.path("name").asText();
                for (JsonNode existing : all) {
                    if (existing.path("name").asText().equals(columnName)) {
                        throw validation("The column " + columnName + " already exists.");
                    }
                }
                CqlType type = CqlType.parse(column.path("type").asText(), udt -> typeExists(account, region,
                        keyspace, udt));
                if ("counter".equals(type.name()) ? regularTable : counterTable) {
                    throw validation("A table with counter columns can only have counter columns besides its "
                            + "keys.");
                }
                all.addObject().put("name", columnName).put("type", type.toString());
            }
        }
        if (in.has("ttl") && !"ENABLED".equals(in.get("ttl").path("status").asText())) {
            throw validation("TTL can't be disabled once it's enabled.");
        }
        settings(account, region, table, in, false);
        if (in.has("replicaSpecifications")) {
            ObjectNode ks = keyspace(account, region, keyspace);
            if (!"MULTI_REGION".equals(ks.path("replicationStrategy").asText())) {
                throw validation("Replica specifications are for tables in MULTI_REGION keyspaces.");
            }
            replicas(table, ks, in.get("replicaSpecifications"));
        }
        pending(table, "status", "UPDATING", "ACTIVE");
        store.put(account, region, TABLE, keyspace + "/" + name, table);
        if (in.has("autoScalingSpecification") || in.has("capacitySpecification")) {
            applyAutoScaling(account, region, table, in.has("autoScalingSpecification")
                    ? in.get("autoScalingSpecification") : null);
        }
        return empty().put("resourceArn", table.path("resourceArn").asText());
    }

    public ObjectNode deleteTable(JsonNode in, String region) {
        String account = store.account();
        String keyspace = text(in, "keyspaceName");
        String name = text(in, "tableName");
        ObjectNode table = table(account, region, keyspace, name);
        String status = table.path("status").asText();
        if ("CREATING".equals(status) || "RESTORING".equals(status) || "UPDATING".equals(status)) {
            throw conflict("Table " + keyspace + "." + name + " is " + status + "; it can be deleted once it's "
                    + "ACTIVE.");
        }
        if ("DELETING".equals(status)) {
            throw conflict("Table " + keyspace + "." + name + " is already being deleted.");
        }
        dropTable(account, region, table);
        return empty();
    }

    private void dropTable(String account, String region, ObjectNode table) {
        String keyspace = table.path("keyspaceName").asText();
        String name = table.path("tableName").asText();
        if (table.has("latestStreamArn") && "ENABLED".equals(table.path("cdcSpecification").path("status")
                .asText())) {
            cdc(account, region, table, empty().put("status", "DISABLED"));
        }
        removeAutoScaling(account, region, table);
        store.deleteRows(account, region, keyspace, name);
        store.deleteAllChanges(account, region, keyspace, name);
        pending(table, "status", "DELETING", "DELETED");
        store.put(account, region, TABLE, keyspace + "/" + name, table);
    }

    // ---------------------------------------------------------------- auto scaling

    static String scalingResource(ObjectNode table) {
        return "keyspace/" + table.path("keyspaceName").asText() + "/table/" + table.path("tableName").asText();
    }

    private void applyAutoScaling(String account, String region, ObjectNode table, JsonNode spec) {
        ApplicationAutoScalingService aas = autoScaling.get();
        String resource = scalingResource(table);
        boolean provisioned = "PROVISIONED".equals(table.path("capacitySpecification").path("throughputMode")
                .asText());
        for (String dimension : List.of("Read", "Write")) {
            String scalable = "cassandra:table:" + dimension + "CapacityUnits";
            JsonNode settings = spec == null ? null : spec.get(dimension.toLowerCase(Locale.ROOT)
                    + "CapacityAutoScaling");
            if (!provisioned || settings != null && settings.path("autoScalingDisabled").asBoolean(false)) {
                deregister(aas, resource, scalable, region);
                continue;
            }
            if (settings == null) {
                continue;
            }
            long min = settings.path("minimumUnits").asLong(1);
            long max = settings.path("maximumUnits").asLong(Math.max(min, 1));
            if (min > max) {
                throw validation("The minimum units are more than the maximum units.");
            }
            aas.registerScalableTarget("cassandra", resource, scalable, (int) min, (int) max, null, null, Map.of(),
                    region);
            JsonNode policy = settings.path("scalingPolicy").path("targetTrackingScalingPolicyConfiguration");
            if (!policy.isMissingNode()) {
                TargetTrackingConfiguration tracking = new TargetTrackingConfiguration();
                tracking.setTargetValue(policy.path("targetValue").asDouble(70));
                PredefinedMetricSpecification metric = new PredefinedMetricSpecification();
                metric.setPredefinedMetricType("Cassandra" + dimension + "CapacityUtilization");
                tracking.setPredefinedMetricSpecification(metric);
                if (policy.has("disableScaleIn")) {
                    tracking.setDisableScaleIn(policy.get("disableScaleIn").asBoolean());
                }
                if (policy.has("scaleInCooldown")) {
                    tracking.setScaleInCooldown(policy.get("scaleInCooldown").asInt());
                }
                if (policy.has("scaleOutCooldown")) {
                    tracking.setScaleOutCooldown(policy.get("scaleOutCooldown").asInt());
                }
                aas.putScalingPolicy(policyName(table, dimension), "TargetTrackingScaling", "cassandra", resource,
                        scalable, tracking, null, region);
            }
        }
    }

    static String policyName(ObjectNode table, String dimension) {
        return table.path("keyspaceName").asText() + "." + table.path("tableName").asText() + "-"
                + dimension.toLowerCase(Locale.ROOT) + "-capacity-target-tracking";
    }

    private static void deregister(ApplicationAutoScalingService aas, String resource, String scalable,
                                   String region) {
        try {
            aas.deregisterScalableTarget("cassandra", resource, scalable, region);
        } catch (AwsException e) {
            // Not registered.
        }
    }

    private void removeAutoScaling(String account, String region, ObjectNode table) {
        ApplicationAutoScalingService aas = autoScaling.get();
        for (String dimension : List.of("Read", "Write")) {
            deregister(aas, scalingResource(table), "cassandra:table:" + dimension + "CapacityUnits", region);
        }
    }

    public ObjectNode getTableAutoScalingSettings(JsonNode in, String region) {
        String account = store.account();
        ObjectNode table = table(account, region, text(in, "keyspaceName"), text(in, "tableName"));
        ObjectNode out = empty();
        out.put("keyspaceName", table.path("keyspaceName").asText()).put("tableName", table.path("tableName")
                .asText()).put("resourceArn", table.path("resourceArn").asText());
        ObjectNode spec = out.putObject("autoScalingSpecification");
        ApplicationAutoScalingService aas = autoScaling.get();
        String resource = scalingResource(table);
        for (String dimension : List.of("Read", "Write")) {
            String scalable = "cassandra:table:" + dimension + "CapacityUnits";
            ObjectNode settings = spec.putObject(dimension.toLowerCase(Locale.ROOT) + "CapacityAutoScaling");
            List<ScalableTarget> targets = aas.describeScalableTargets("cassandra", List.of(resource), scalable,
                    region);
            if (targets.isEmpty()) {
                settings.put("autoScalingDisabled", true);
                continue;
            }
            ScalableTarget target = targets.get(0);
            settings.put("autoScalingDisabled", false).put("minimumUnits", target.getMinCapacity())
                    .put("maximumUnits", target.getMaxCapacity());
            for (ScalingPolicy policy : aas.describeScalingPolicies("cassandra", resource, scalable, null, region)) {
                TargetTrackingConfiguration tracking = policy.getTargetTrackingConfiguration();
                if (tracking == null) {
                    continue;
                }
                ObjectNode config = settings.putObject("scalingPolicy")
                        .putObject("targetTrackingScalingPolicyConfiguration");
                config.put("targetValue", tracking.getTargetValue());
                if (tracking.getDisableScaleIn() != null) {
                    config.put("disableScaleIn", tracking.getDisableScaleIn());
                }
                if (tracking.getScaleInCooldown() != null) {
                    config.put("scaleInCooldown", tracking.getScaleInCooldown());
                }
                if (tracking.getScaleOutCooldown() != null) {
                    config.put("scaleOutCooldown", tracking.getScaleOutCooldown());
                }
            }
        }
        ObjectNode ks = keyspace(account, region, table.path("keyspaceName").asText());
        if ("MULTI_REGION".equals(ks.path("replicationStrategy").asText())) {
            ArrayNode replicas = out.putArray("replicaSpecifications");
            for (JsonNode r : ks.path("replicationRegions")) {
                ObjectNode replica = replicas.addObject().put("region", r.asText());
                replica.set("autoScalingSpecification", spec.deepCopy());
            }
        }
        return out;
    }

    /** Sets a table's provisioned capacity, as Application Auto Scaling does when it scales. */
    void setCapacity(String account, String region, String resource, String dimension, int units) {
        String[] parts = resource.split("/");
        if (parts.length != 4) {
            return;
        }
        ObjectNode table = find(account, region, TABLE, parts[1] + "/" + parts[3]);
        if (table == null) {
            return;
        }
        ((ObjectNode) table.get("capacitySpecification")).put(dimension, units);
        store.put(account, region, TABLE, parts[1] + "/" + parts[3], table);
    }

    // ---------------------------------------------------------------- restore

    public ObjectNode restoreTable(JsonNode in, String region) {
        String account = store.account();
        String sourceKeyspace = text(in, "sourceKeyspaceName");
        String sourceName = text(in, "sourceTableName");
        ObjectNode source = table(account, region, sourceKeyspace, sourceName);
        if (!"ENABLED".equals(source.path("pointInTimeRecovery").path("status").asText())) {
            throw validation("Point-in-time recovery is not enabled on " + sourceKeyspace + "." + sourceName + ".");
        }
        String targetKeyspace = text(in, "targetKeyspaceName");
        String targetName = text(in, "targetTableName");
        checkName(targetName, "table");
        keyspace(account, region, targetKeyspace);
        if (find(account, region, TABLE, targetKeyspace + "/" + targetName) != null) {
            throw conflict("Table " + targetKeyspace + "." + targetName + " already exists.");
        }
        long now = System.currentTimeMillis();
        long at = in.hasNonNull("restoreTimestamp") ? (long) (in.get("restoreTimestamp").asDouble() * 1000) : now;
        long earliest = Math.max(meta(source).path("pitrSince").asLong(), now - PITR_WINDOW_MILLIS);
        if (at < earliest - 1000 || at > now + 1000) {
            throw validation("The restore timestamp must be between " + Instant.ofEpochMilli(earliest) + " and now.");
        }
        ObjectNode table = store.record();
        table.put("keyspaceName", targetKeyspace).put("tableName", targetName)
                .put("resourceArn", store.tableArn(account, region, targetKeyspace, targetName))
                .put("creationTimestamp", KeyspacesStore.epoch(now));
        if (!sourceKeyspace.equals(targetKeyspace)) {
            for (JsonNode column : source.path("schemaDefinition").path("allColumns")) {
                List<String> udts = new ArrayList<>();
                CqlType.parse(column.path("type").asText(), u -> true).udts(udts);
                for (String udt : udts) {
                    if (!typeExists(account, region, targetKeyspace, udt)) {
                        ObjectNode type = type(account, region, sourceKeyspace, udt).deepCopy();
                        type.put("keyspaceName", targetKeyspace).put("keyspaceArn", store.keyspaceArn(account,
                                region, targetKeyspace)).put("status", "ACTIVE");
                        store.put(account, region, TYPE, targetKeyspace + "/" + udt, type);
                    }
                }
            }
        }
        table.set("schemaDefinition", source.get("schemaDefinition").deepCopy());
        for (String field : List.of("capacitySpecification", "encryptionSpecification", "ttl", "defaultTimeToLive",
                "comment", "clientSideTimestamps", "warmThroughputSpecification")) {
            if (source.has(field)) {
                table.set(field, source.get(field).deepCopy());
            }
        }
        table.set("pointInTimeRecovery", empty().put("status", "DISABLED"));
        table.set("cdcSpecification", empty().put("status", "DISABLED"));
        ObjectNode overrides = empty();
        if (in.has("capacitySpecificationOverride")) {
            overrides.set("capacitySpecification", in.get("capacitySpecificationOverride"));
        }
        if (in.has("encryptionSpecificationOverride")) {
            overrides.set("encryptionSpecification", in.get("encryptionSpecificationOverride"));
        }
        if (in.has("pointInTimeRecoveryOverride")) {
            overrides.set("pointInTimeRecovery", in.get("pointInTimeRecoveryOverride"));
        }
        if (in.has("autoScalingSpecification")) {
            overrides.set("autoScalingSpecification", in.get("autoScalingSpecification"));
        }
        settings(account, region, table, overrides, false);
        meta(table).set("tags", in.has("tagsOverride") ? KeyspacesStore.tagsOf(mapper(), in.get("tagsOverride"))
                : meta(source).path("tags").deepCopy());
        pending(table, "status", "RESTORING", "ACTIVE");
        store.put(account, region, TABLE, targetKeyspace + "/" + targetName, table);
        copyRows(account, region, source, targetKeyspace, targetName, at);
        applyAutoScaling(account, region, table, in.get("autoScalingSpecification"));
        return empty().put("restoredTableARN", table.path("resourceArn").asText());
    }

    /** Writes a table's rows as they were at a time into another table, undoing the mutations made since. */
    private void copyRows(String account, String region, ObjectNode source, String keyspace, String name, long at) {
        String sourceKeyspace = source.path("keyspaceName").asText();
        String sourceName = source.path("tableName").asText();
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        for (ObjectNode row : store.rows(account, region, sourceKeyspace, sourceName)) {
            rows.put(row.path("_key").asText(), row);
        }
        List<ObjectNode> changes = store.changes(account, region, sourceKeyspace, sourceName);
        for (int i = changes.size() - 1; i >= 0; i--) {
            ObjectNode change = changes.get(i);
            if (change.path("at").asLong() <= at) {
                break;
            }
            String key = change.path("rowKey").asText();
            if (change.path("old").isObject()) {
                rows.put(key, (ObjectNode) change.get("old"));
            } else {
                rows.remove(key);
            }
        }
        String from = KeyspacesStore.tableKey(region, sourceKeyspace, sourceName);
        String to = KeyspacesStore.tableKey(region, keyspace, name);
        for (Map.Entry<String, ObjectNode> e : rows.entrySet()) {
            ObjectNode copy = e.getValue().deepCopy();
            String key = to + e.getKey().substring(from.length());
            copy.put("_key", key);
            store.putRow(account, key, copy);
        }
    }

    // ---------------------------------------------------------------- tags

    private ObjectNode tagged(String account, String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("The ARN " + arn + " is not a Keyspaces resource.");
        }
        if (!parsed.resource().startsWith("/keyspace/")) {
            throw validation("The ARN " + arn + " is not a Keyspaces resource.");
        }
        String[] parts = parsed.resource().substring("/keyspace/".length()).split("/");
        String arnRegion = parsed.region();
        ObjectNode found;
        String kind;
        String path;
        if (parts.length == 1) {
            kind = KEYSPACE;
            path = parts[0];
        } else if (parts.length == 3 && "table".equals(parts[1])) {
            kind = TABLE;
            path = parts[0] + "/" + parts[2];
        } else if (parts.length == 5 && "stream".equals(parts[3])) {
            kind = STREAM;
            path = parts[0] + "/" + parts[2] + "/" + parts[4];
        } else {
            throw validation("The ARN " + arn + " is not a Keyspaces resource.");
        }
        found = find(account, arnRegion, kind, path);
        if (found == null || KEYSPACE.equals(kind) && "DELETING".equals(meta(found).path("status").asText())) {
            throw notFound("The resource " + arn + " doesn't exist.", arn);
        }
        meta(found).put("kind", kind).put("region", arnRegion);
        return found;
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = tagged(account, region, text(in, "resourceArn"));
        ObjectNode tags = meta(record).has("tags") ? (ObjectNode) meta(record).get("tags")
                : meta(record).putObject("tags");
        tags.setAll(KeyspacesStore.tagsOf(mapper(), in.get("tags")));
        if (tags.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        store.put(account, meta(record).path("region").asText(), meta(record).path("kind").asText(),
                meta(record).path("path").asText(), record);
        return empty();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = tagged(account, region, text(in, "resourceArn"));
        if (meta(record).has("tags")) {
            ObjectNode tags = (ObjectNode) meta(record).get("tags");
            for (JsonNode tag : in.path("tags")) {
                tags.remove(tag.path("key").asText());
            }
        }
        store.put(account, meta(record).path("region").asText(), meta(record).path("kind").asText(),
                meta(record).path("path").asText(), record);
        return empty();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode record = tagged(store.account(), region, text(in, "resourceArn"));
        List<JsonNode> tags = new ArrayList<>();
        KeyspacesStore.tagList(mapper(), meta(record).get("tags")).forEach(tags::add);
        ObjectNode out = empty();
        KeyspacesStore.page(tags, in, out, "tags", 100);
        return out;
    }
}
