package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The system tables the CQL endpoint serves from the control plane: {@code system.local} and {@code system.peers}
 * (one node, the endpoint itself), {@code system_schema.keyspaces}, {@code tables}, {@code columns} and {@code types}
 * (the schema drivers build their metadata from), empty {@code functions}, {@code aggregates}, {@code indexes},
 * {@code triggers}, {@code views} and {@code dropped_columns}, and Keyspaces' {@code system_schema_mcs} tables with
 * each keyspace's and table's status, which is how a CQL client waits for a table to become {@code ACTIVE}.
 */
@ApplicationScoped
public class CqlSystemTables {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final UUID HOST_ID = UUID.nameUUIDFromBytes("floci-keyspaces".getBytes(StandardCharsets.UTF_8));

    /** A virtual table: its columns and rows (values in Floci's stored form). */
    record Virtual(Map<String, CqlType> columns, List<Map<String, JsonNode>> rows) {
    }

    private final KeyspacesService service;
    private final KeyspacesStore store;

    @Inject
    public CqlSystemTables(KeyspacesService service, KeyspacesStore store) {
        this.service = service;
        this.store = store;
    }

    private static Map<String, CqlType> columns(String... pairs) {
        Map<String, CqlType> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], CqlType.parse(pairs[i + 1], u -> true));
        }
        return out;
    }

    private static ArrayNode textList(List<String> items) {
        ArrayNode out = NODES.arrayNode();
        items.forEach(out::add);
        return out;
    }

    private static ArrayNode textMap(Map<String, String> map) {
        ArrayNode out = NODES.arrayNode();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.addArray().add(e.getKey())
                .add(e.getValue()));
        return out;
    }

    Virtual table(CqlExecutor.Session session, String keyspace, String table) {
        return switch (keyspace + "." + table) {
            case "system.local" -> local(session);
            case "system.peers" -> new Virtual(columns("peer", "inet", "data_center", "text", "host_id", "uuid",
                    "preferred_ip", "inet", "rack", "text", "release_version", "text", "rpc_address", "inet",
                    "schema_version", "uuid", "tokens", "set<text>"), List.of());
            case "system.peers_v2" -> throw CqlError.invalid("unconfigured table peers_v2");
            case "system_schema.keyspaces", "system_schema_mcs.keyspaces" -> keyspaces(session,
                    keyspace.endsWith("_mcs"));
            case "system_schema.tables", "system_schema_mcs.tables" -> tables(session, keyspace.endsWith("_mcs"));
            case "system_schema.columns", "system_schema_mcs.columns" -> columnsTable(session);
            case "system_schema.types", "system_schema_mcs.types" -> types(session);
            case "system_schema.functions" -> new Virtual(columns("keyspace_name", "text", "function_name", "text",
                    "argument_types", "frozen<list<text>>", "argument_names", "frozen<list<text>>", "body", "text",
                    "called_on_null_input", "boolean", "language", "text", "return_type", "text"), List.of());
            case "system_schema.aggregates" -> new Virtual(columns("keyspace_name", "text", "aggregate_name", "text",
                    "argument_types", "frozen<list<text>>", "final_func", "text", "initcond", "text",
                    "return_type", "text", "state_func", "text", "state_type", "text"), List.of());
            case "system_schema.indexes" -> new Virtual(columns("keyspace_name", "text", "table_name", "text",
                    "index_name", "text", "kind", "text", "options", "frozen<map<text, text>>"), List.of());
            case "system_schema.triggers" -> new Virtual(columns("keyspace_name", "text", "table_name", "text",
                    "trigger_name", "text", "options", "frozen<map<text, text>>"), List.of());
            case "system_schema.views" -> new Virtual(columns("keyspace_name", "text", "view_name", "text",
                    "base_table_id", "uuid", "base_table_name", "text", "include_all_columns", "boolean",
                    "where_clause", "text"), List.of());
            case "system_schema.dropped_columns" -> new Virtual(columns("keyspace_name", "text", "table_name",
                    "text", "column_name", "text", "dropped_time", "timestamp", "type", "text"), List.of());
            case "system_multiregion_info.tables" -> multiRegion(session);
            default -> null;
        };
    }

    private Virtual local(CqlExecutor.Session session) {
        Map<String, JsonNode> row = new LinkedHashMap<>();
        row.put("key", NODES.textNode("local"));
        row.put("bootstrapped", NODES.textNode("COMPLETED"));
        row.put("broadcast_address", NODES.textNode("127.0.0.1"));
        row.put("cluster_name", NODES.textNode("Amazon Keyspaces"));
        row.put("cql_version", NODES.textNode("3.4.4"));
        row.put("data_center", NODES.textNode(session.region));
        row.put("host_id", NODES.textNode(HOST_ID.toString()));
        row.put("listen_address", NODES.textNode("127.0.0.1"));
        row.put("native_protocol_version", NODES.textNode("4"));
        row.put("partitioner", NODES.textNode("org.apache.cassandra.dht.Murmur3Partitioner"));
        row.put("rack", NODES.textNode("rack1"));
        row.put("release_version", NODES.textNode("3.11.2"));
        row.put("rpc_address", NODES.textNode("127.0.0.1"));
        row.put("schema_version", NODES.textNode(HOST_ID.toString()));
        row.put("tokens", textList(List.of("0")));
        return new Virtual(columns("key", "text", "bootstrapped", "text", "broadcast_address", "inet",
                "cluster_name", "text", "cql_version", "text", "data_center", "text", "host_id", "uuid",
                "listen_address", "inet", "native_protocol_version", "text", "partitioner", "text", "rack", "text",
                "release_version", "text", "rpc_address", "inet", "schema_version", "uuid", "tokens", "set<text>"),
                List.of(row));
    }

    private List<String> keyspaceNames(CqlExecutor.Session session) {
        List<String> names = new ArrayList<>();
        ObjectNode listed = service.listKeyspaces(store.mapper().createObjectNode().put("maxResults", 1000),
                session.region);
        listed.path("keyspaces").forEach(k -> names.add(k.path("keyspaceName").asText()));
        return names;
    }

    private Virtual keyspaces(CqlExecutor.Session session, boolean mcs) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (String name : keyspaceNames(session)) {
            ObjectNode keyspace = service.keyspace(session.account, session.region, name);
            Map<String, JsonNode> row = new LinkedHashMap<>();
            row.put("keyspace_name", NODES.textNode(name));
            row.put("durable_writes", NODES.booleanNode(true));
            Map<String, String> replication = new LinkedHashMap<>();
            if ("MULTI_REGION".equals(keyspace.path("replicationStrategy").asText())) {
                replication.put("class", "org.apache.cassandra.locator.NetworkTopologyStrategy");
                keyspace.path("replicationRegions").forEach(r -> replication.put(r.asText(), "3"));
            } else {
                replication.put("class", "org.apache.cassandra.locator.SimpleStrategy");
                replication.put("replication_factor", "3");
            }
            row.put("replication", textMap(replication));
            if (mcs) {
                row.put("status", NODES.textNode(keyspace.path("_status").asText("ACTIVE")));
            }
            rows.add(row);
        }
        Map<String, CqlType> columns = columns("keyspace_name", "text", "durable_writes", "boolean", "replication",
                "frozen<map<text, text>>");
        if (mcs) {
            columns.put("status", CqlType.parse("text", u -> true));
        }
        return new Virtual(columns, rows);
    }

    private List<ObjectNode> tables(CqlExecutor.Session session) {
        List<ObjectNode> tables = new ArrayList<>();
        for (String keyspace : keyspaceNames(session)) {
            if (!KeyspacesService.SYSTEM_KEYSPACES.contains(keyspace)) {
                tables.addAll(service.tables(session.account, session.region, keyspace));
            }
        }
        return tables;
    }

    private Virtual tables(CqlExecutor.Session session, boolean mcs) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (ObjectNode table : tables(session)) {
            Map<String, JsonNode> row = new LinkedHashMap<>();
            row.put("keyspace_name", NODES.textNode(table.path("keyspaceName").asText()));
            row.put("table_name", NODES.textNode(table.path("tableName").asText()));
            row.put("bloom_filter_fp_chance", NODES.numberNode(0.01));
            row.put("caching", textMap(Map.of("keys", "ALL", "rows_per_partition", "NONE")));
            row.put("comment", NODES.textNode(table.path("comment").path("message").asText("")));
            row.put("compaction", textMap(Map.of("class",
                    "org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy")));
            row.put("compression", textMap(Map.of()));
            row.put("crc_check_chance", NODES.numberNode(1.0));
            row.put("dclocal_read_repair_chance", NODES.numberNode(0.0));
            row.put("default_time_to_live", NODES.numberNode(table.path("defaultTimeToLive").asInt(0)));
            row.put("extensions", NODES.arrayNode());
            row.put("flags", textList(List.of("compound")));
            row.put("gc_grace_seconds", NODES.numberNode(7776000));
            row.put("id", NODES.textNode(UUID.nameUUIDFromBytes(table.path("resourceArn").asText()
                    .getBytes(StandardCharsets.UTF_8)).toString()));
            row.put("max_index_interval", NODES.numberNode(2048));
            row.put("memtable_flush_period_in_ms", NODES.numberNode(3600000));
            row.put("min_index_interval", NODES.numberNode(128));
            row.put("read_repair_chance", NODES.numberNode(0.0));
            row.put("speculative_retry", NODES.textNode("99PERCENTILE"));
            if (mcs) {
                row.put("status", NODES.textNode(table.path("status").asText()));
                row.put("custom_properties", customProperties(table));
            }
            rows.add(row);
        }
        Map<String, CqlType> columns = columns("keyspace_name", "text", "table_name", "text",
                "bloom_filter_fp_chance", "double", "caching", "frozen<map<text, text>>", "comment", "text",
                "compaction", "frozen<map<text, text>>", "compression", "frozen<map<text, text>>",
                "crc_check_chance", "double", "dclocal_read_repair_chance", "double", "default_time_to_live", "int",
                "extensions", "frozen<map<text, blob>>", "flags", "frozen<set<text>>", "gc_grace_seconds", "int",
                "id", "uuid", "max_index_interval", "int", "memtable_flush_period_in_ms", "int", "min_index_interval",
                "int", "read_repair_chance", "double", "speculative_retry", "text");
        if (mcs) {
            columns.put("status", CqlType.parse("text", u -> true));
            columns.put("custom_properties", CqlType.parse("frozen<map<text, frozen<map<text, text>>>>", u -> true));
        }
        return new Virtual(columns, rows);
    }

    private static ArrayNode customProperties(ObjectNode table) {
        ArrayNode out = NODES.arrayNode();
        JsonNode capacity = table.path("capacitySpecification");
        Map<String, String> mode = new LinkedHashMap<>();
        mode.put("throughput_mode", capacity.path("throughputMode").asText());
        if (capacity.has("readCapacityUnits")) {
            mode.put("read_capacity_units", capacity.get("readCapacityUnits").asText());
            mode.put("write_capacity_units", capacity.get("writeCapacityUnits").asText());
        }
        out.addArray().add("capacity_mode").add(textMap(mode));
        out.addArray().add("encryption_specification").add(textMap(Map.of("encryption_type",
                table.path("encryptionSpecification").path("type").asText())));
        out.addArray().add("point_in_time_recovery").add(textMap(Map.of("status",
                table.path("pointInTimeRecovery").path("status").asText().toLowerCase())));
        if (table.has("ttl")) {
            out.addArray().add("ttl").add(textMap(Map.of("status", "enabled")));
        }
        return out;
    }

    private Virtual columnsTable(CqlExecutor.Session session) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (ObjectNode table : tables(session)) {
            JsonNode schema = table.path("schemaDefinition");
            List<String> pk = new ArrayList<>();
            schema.path("partitionKeys").forEach(k -> pk.add(k.path("name").asText()));
            List<String> ck = new ArrayList<>();
            List<String> orders = new ArrayList<>();
            schema.path("clusteringKeys").forEach(k -> {
                ck.add(k.path("name").asText());
                orders.add(k.path("orderBy").asText("ASC"));
            });
            List<String> statics = new ArrayList<>();
            schema.path("staticColumns").forEach(k -> statics.add(k.path("name").asText()));
            for (JsonNode column : schema.path("allColumns")) {
                String name = column.path("name").asText();
                Map<String, JsonNode> row = new LinkedHashMap<>();
                row.put("keyspace_name", NODES.textNode(table.path("keyspaceName").asText()));
                row.put("table_name", NODES.textNode(table.path("tableName").asText()));
                row.put("column_name", NODES.textNode(name));
                String kind;
                int position;
                String order = "none";
                if (pk.contains(name)) {
                    kind = "partition_key";
                    position = pk.indexOf(name);
                } else if (ck.contains(name)) {
                    kind = "clustering";
                    position = ck.indexOf(name);
                    order = orders.get(position).toLowerCase();
                } else {
                    kind = statics.contains(name) ? "static" : "regular";
                    position = -1;
                }
                row.put("clustering_order", NODES.textNode(order));
                row.put("column_name_bytes", NODES.textNode(Base64.getEncoder().encodeToString(
                        name.getBytes(StandardCharsets.UTF_8))));
                row.put("kind", NODES.textNode(kind));
                row.put("position", NODES.numberNode(position));
                row.put("type", NODES.textNode(column.path("type").asText()));
                rows.add(row);
            }
        }
        return new Virtual(columns("keyspace_name", "text", "table_name", "text", "column_name", "text",
                "clustering_order", "text", "column_name_bytes", "blob", "kind", "text", "position", "int", "type",
                "text"), rows);
    }

    private Virtual types(CqlExecutor.Session session) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (String keyspace : keyspaceNames(session)) {
            for (ObjectNode type : store.list(session.account, session.region, KeyspacesStore.TYPE, keyspace + "/")) {
                Map<String, JsonNode> row = new LinkedHashMap<>();
                row.put("keyspace_name", NODES.textNode(keyspace));
                row.put("type_name", NODES.textNode(type.path("typeName").asText()));
                List<String> names = new ArrayList<>();
                List<String> types = new ArrayList<>();
                type.path("fieldDefinitions").forEach(f -> {
                    names.add(f.path("name").asText());
                    types.add(f.path("type").asText());
                });
                row.put("field_names", textList(names));
                row.put("field_types", textList(types));
                rows.add(row);
            }
        }
        return new Virtual(columns("keyspace_name", "text", "type_name", "text", "field_names",
                "frozen<list<text>>", "field_types", "frozen<list<text>>"), rows);
    }

    private Virtual multiRegion(CqlExecutor.Session session) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        for (ObjectNode table : tables(session)) {
            ObjectNode keyspace = service.keyspace(session.account, session.region, table.path("keyspaceName")
                    .asText());
            List<String> regions = new ArrayList<>();
            keyspace.path("replicationRegions").forEach(r -> regions.add(r.asText()));
            if (regions.isEmpty()) {
                regions.add(session.region);
            }
            for (String region : regions) {
                Map<String, JsonNode> row = new LinkedHashMap<>();
                row.put("keyspace_name", NODES.textNode(table.path("keyspaceName").asText()));
                row.put("table_name", NODES.textNode(table.path("tableName").asText()));
                row.put("region", NODES.textNode(region));
                row.put("status", NODES.textNode(table.path("status").asText()));
                rows.add(row);
            }
        }
        return new Virtual(columns("keyspace_name", "text", "table_name", "text", "region", "text", "status",
                "text"), rows);
    }
}
