package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static io.floci.aws.services.keyspaces.KeyspacesStore.meta;

/**
 * The rows of Keyspaces tables, as the CQL endpoint reads and writes them. A row is
 * {@code {"_key", "pk", "ck", "static", "marker", "cells"}}: the key columns' values, the row marker an
 * {@code INSERT} writes (so the row exists with no other cells), and each other column's cell
 * ({@code {"v", "wt", "exp"}}: value, write time in microseconds, expiry in epoch seconds). Static columns live in
 * one row per partition. Every write is logged with the row before and after it when the table has point-in-time
 * recovery or CDC, for 35 days or a day; expired cells are dropped when read, logged as {@code TTL} deletes.
 */
@ApplicationScoped
public class KeyspacesData {

    private final KeyspacesStore store;
    private final KeyspacesService service;
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1000);

    @Inject
    public KeyspacesData(KeyspacesStore store, KeyspacesService service) {
        this.store = store;
        this.service = service;
    }

    ObjectMapper mapper() {
        return store.mapper();
    }

    /** A table's schema as the CQL endpoint uses it. */
    static final class Schema {
        final String account;
        final String region;
        final String keyspace;
        final String table;
        final ObjectNode record;
        final Map<String, CqlType> columns = new LinkedHashMap<>();
        final List<String> partitionKeys = new ArrayList<>();
        final List<String> clusteringKeys = new ArrayList<>();
        final List<Boolean> descending = new ArrayList<>();
        final List<String> statics = new ArrayList<>();
        final List<String> regulars = new ArrayList<>();
        final Map<String, List<Map.Entry<String, CqlType>>> udts;

        Schema(String account, String region, String keyspace, String table, ObjectNode record,
               Map<String, List<Map.Entry<String, CqlType>>> udts) {
            this.account = account;
            this.region = region;
            this.keyspace = keyspace;
            this.table = table;
            this.record = record;
            this.udts = udts;
            JsonNode schema = record.path("schemaDefinition");
            schema.path("allColumns").forEach(c -> columns.put(c.path("name").asText(), CqlType.parse(
                    c.path("type").asText(), u -> true)));
            schema.path("partitionKeys").forEach(k -> partitionKeys.add(k.path("name").asText()));
            schema.path("clusteringKeys").forEach(k -> {
                clusteringKeys.add(k.path("name").asText());
                descending.add("DESC".equals(k.path("orderBy").asText()));
            });
            schema.path("staticColumns").forEach(k -> statics.add(k.path("name").asText()));
            List<String> others = new ArrayList<>();
            for (String c : columns.keySet()) {
                if (!partitionKeys.contains(c) && !clusteringKeys.contains(c) && !statics.contains(c)) {
                    others.add(c);
                }
            }
            others.sort(Comparator.naturalOrder());
            regulars.addAll(others);
        }

        /** Columns in Cassandra's order: partition keys, clustering keys, then static and regular alphabetically. */
        List<String> ordered() {
            List<String> out = new ArrayList<>(partitionKeys);
            out.addAll(clusteringKeys);
            List<String> rest = new ArrayList<>(statics);
            rest.addAll(regulars);
            rest.sort(Comparator.naturalOrder());
            out.addAll(rest);
            return out;
        }

        boolean isKey(String column) {
            return partitionKeys.contains(column) || clusteringKeys.contains(column);
        }

        boolean ttlEnabled() {
            return record.has("ttl");
        }

        boolean clientTimestamps() {
            return record.has("clientSideTimestamps");
        }

        int defaultTtl() {
            return record.path("defaultTimeToLive").asInt(0);
        }

        boolean logged() {
            return "ENABLED".equals(record.path("pointInTimeRecovery").path("status").asText())
                    || "ENABLED".equals(record.path("cdcSpecification").path("status").asText());
        }

        long retention() {
            return "ENABLED".equals(record.path("pointInTimeRecovery").path("status").asText())
                    ? KeyspacesService.PITR_WINDOW_MILLIS : KeyspacesService.STREAM_RETENTION_MILLIS;
        }

        List<Map.Entry<String, CqlType>> udt(String name) {
            List<Map.Entry<String, CqlType>> fields = udts.get(name);
            if (fields == null) {
                throw new IllegalArgumentException("Unknown type " + keyspace + "." + name);
            }
            return fields;
        }
    }

    /** The schema of an active table. */
    Schema schema(String account, String region, String keyspace, String table) {
        ObjectNode record = service.table(account, region, keyspace, table);
        String status = record.path("status").asText();
        if (!"ACTIVE".equals(status) && !"UPDATING".equals(status)) {
            throw new CqlError(CqlError.INVALID, "Table " + keyspace + "." + table + " is " + status
                    + "; it can be read and written once it's ACTIVE.");
        }
        Map<String, List<Map.Entry<String, CqlType>>> udts = new HashMap<>();
        for (ObjectNode type : store.list(account, region, KeyspacesStore.TYPE, keyspace + "/")) {
            List<Map.Entry<String, CqlType>> fields = new ArrayList<>();
            type.path("fieldDefinitions").forEach(f -> fields.add(Map.entry(f.path("name").asText(),
                    CqlType.parse(f.path("type").asText(), u -> true))));
            udts.put(type.path("typeName").asText(), fields);
        }
        return new Schema(account, region, keyspace, table, record, udts);
    }

    // ---------------------------------------------------------------- keys

    static String rowKey(Schema schema, ArrayNode pk, ArrayNode ck) {
        return KeyspacesStore.tableKey(schema.region, schema.keyspace, schema.table) + pk.toString() + "/"
                + (ck == null ? "#static" : ck.toString());
    }

    static String partitionPrefix(Schema schema, ArrayNode pk) {
        return KeyspacesStore.tableKey(schema.region, schema.keyspace, schema.table) + pk.toString() + "/";
    }

    // ---------------------------------------------------------------- liveness

    static boolean live(JsonNode cell, long nowSeconds) {
        return cell != null && cell.isObject() && (!cell.hasNonNull("exp") || cell.get("exp").asLong() > nowSeconds);
    }

    /** Whether a row exists: its marker or a cell is live. */
    static boolean exists(JsonNode row, long nowSeconds) {
        if (row == null) {
            return false;
        }
        if (live(row.get("marker"), nowSeconds)) {
            return true;
        }
        for (JsonNode cell : row.path("cells")) {
            if (live(cell, nowSeconds)) {
                return true;
            }
        }
        return false;
    }

    /** Reads a row, dropping expired cells (logged as TTL deletes); null when the row doesn't exist. */
    ObjectNode read(Schema schema, String key) {
        ObjectNode row = store.row(schema.account, key).orElse(null);
        if (row == null) {
            return null;
        }
        return expire(schema, row);
    }

    private ObjectNode expire(Schema schema, ObjectNode row) {
        long now = System.currentTimeMillis() / 1000;
        boolean expired = row.has("marker") && !live(row.get("marker"), now);
        List<String> gone = new ArrayList<>();
        row.path("cells").fields().forEachRemaining(e -> {
            if (!live(e.getValue(), now)) {
                gone.add(e.getKey());
            }
        });
        if (!expired && gone.isEmpty()) {
            return row;
        }
        ObjectNode before = row.deepCopy();
        ObjectNode after = row.deepCopy();
        if (expired) {
            after.remove("marker");
        }
        gone.forEach(((ObjectNode) after.get("cells"))::remove);
        boolean remains = exists(after, now);
        String key = row.path("_key").asText();
        if (remains) {
            store.putRow(schema.account, key, after);
        } else {
            store.deleteRow(schema.account, key);
        }
        log(schema, key, before, remains ? after : null, "TTL");
        return remains ? after : null;
    }

    /** The rows of one partition (static row first), live only. */
    List<ObjectNode> partition(Schema schema, ArrayNode pk) {
        String prefix = partitionPrefix(schema, pk);
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode row : store.rows(schema.account, schema.region, schema.keyspace, schema.table)) {
            if (row.path("_key").asText().startsWith(prefix)) {
                ObjectNode live = expire(schema, row);
                if (live != null) {
                    rows.add(live);
                }
            }
        }
        return rows;
    }

    /** Every live row of the table. */
    List<ObjectNode> scan(Schema schema) {
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode row : store.rows(schema.account, schema.region, schema.keyspace, schema.table)) {
            ObjectNode live = expire(schema, row);
            if (live != null) {
                rows.add(live);
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- writes

    /** Applies a change to a row and logs it; the change returns null to delete the row. */
    ObjectNode mutate(Schema schema, ArrayNode pk, ArrayNode ck, UnaryOperator<ObjectNode> change) {
        String key = rowKey(schema, pk, ck);
        ObjectNode before = read(schema, key);
        ObjectNode working = before == null ? newRow(schema, key, pk, ck) : before.deepCopy();
        ObjectNode after = change.apply(working);
        long now = System.currentTimeMillis() / 1000;
        if (after != null && !exists(after, now)) {
            after = null;
        }
        if (after == null) {
            if (before != null) {
                store.deleteRow(schema.account, key);
            }
        } else {
            store.putRow(schema.account, key, after);
        }
        if (before != null || after != null) {
            log(schema, key, before, after, "USER");
        }
        return after;
    }

    private ObjectNode newRow(Schema schema, String key, ArrayNode pk, ArrayNode ck) {
        ObjectNode row = mapper().createObjectNode();
        row.put("_key", key);
        ObjectNode pkObject = row.putObject("pk");
        for (int i = 0; i < schema.partitionKeys.size(); i++) {
            pkObject.set(schema.partitionKeys.get(i), pk.get(i));
        }
        ObjectNode ckObject = row.putObject("ck");
        if (ck == null) {
            row.put("static", true);
        } else {
            for (int i = 0; i < schema.clusteringKeys.size(); i++) {
                ckObject.set(schema.clusteringKeys.get(i), ck.get(i));
            }
        }
        row.putObject("cells");
        return row;
    }

    private void log(Schema schema, String key, ObjectNode before, ObjectNode after, String origin) {
        if (!schema.logged()) {
            return;
        }
        long now = System.currentTimeMillis();
        ObjectNode change = mapper().createObjectNode();
        ObjectNode sample = after != null ? after : before;
        change.put("seq", sequence.incrementAndGet()).put("at", now).put("origin", origin).put("rowKey", key);
        change.set("pk", sample.path("pk").deepCopy());
        change.set("ck", sample.path("ck").deepCopy());
        change.set("old", before == null ? mapper().nullNode() : before.deepCopy());
        change.set("new", after == null ? mapper().nullNode() : after.deepCopy());
        store.putChange(schema.account, schema.region, schema.keyspace, schema.table, change);
        if (sequence.get() % 500 == 0) {
            store.deleteChanges(schema.account, schema.region, schema.keyspace, schema.table,
                    now - schema.retention());
        }
        if (!"ENABLED".equals(schema.record.path("pointInTimeRecovery").path("status").asText())) {
            return;
        }
        meta(schema.record).put("written", now);
    }

    // ---------------------------------------------------------------- ordering

    /** Compares stored values of a type as Cassandra orders them. */
    static int compare(CqlType type, JsonNode a, JsonNode b) {
        if (a == null || a.isNull()) {
            return b == null || b.isNull() ? 0 : -1;
        }
        if (b == null || b.isNull()) {
            return 1;
        }
        switch (type.name()) {
            case "int", "bigint", "smallint", "tinyint", "counter", "timestamp", "time" -> {
                return Long.compare(a.asLong(), b.asLong());
            }
            case "float", "double" -> {
                return Double.compare(a.asDouble(), b.asDouble());
            }
            case "varint" -> {
                return new BigInteger(a.asText()).compareTo(new BigInteger(b.asText()));
            }
            case "decimal" -> {
                return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText()));
            }
            case "boolean" -> {
                return Boolean.compare(a.asBoolean(), b.asBoolean());
            }
            case "timeuuid" -> {
                UUID x = UUID.fromString(a.asText());
                UUID y = UUID.fromString(b.asText());
                int c = Long.compare(x.timestamp(), y.timestamp());
                return c != 0 ? c : x.toString().compareTo(y.toString());
            }
            case "uuid" -> {
                UUID x = UUID.fromString(a.asText());
                UUID y = UUID.fromString(b.asText());
                int c = Integer.compare(x.version(), y.version());
                if (c == 0 && x.version() == 1) {
                    c = Long.compare(x.timestamp(), y.timestamp());
                }
                return c != 0 ? c : unsigned(x, y);
            }
            case "blob" -> {
                return Arrays.compareUnsigned(Base64.getDecoder().decode(a.asText()),
                        Base64.getDecoder().decode(b.asText()));
            }
            case "list", "set", "tuple" -> {
                for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                    CqlType element = "tuple".equals(type.name()) ? type.args().get(i) : type.args().get(0);
                    int c = compare(element, a.get(i), b.get(i));
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(a.size(), b.size());
            }
            case "map" -> {
                for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                    int c = compare(type.args().get(0), a.get(i).get(0), b.get(i).get(0));
                    if (c == 0) {
                        c = compare(type.args().get(1), a.get(i).get(1), b.get(i).get(1));
                    }
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(a.size(), b.size());
            }
            default -> {
                return a.asText().compareTo(b.asText());
            }
        }
    }

    private static int unsigned(UUID x, UUID y) {
        int c = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    }

    /** Orders a partition's rows by their clustering columns. */
    static Comparator<ObjectNode> clusteringOrder(Schema schema) {
        return (x, y) -> {
            if (x.path("static").asBoolean(false) != y.path("static").asBoolean(false)) {
                return x.path("static").asBoolean(false) ? -1 : 1;
            }
            for (int i = 0; i < schema.clusteringKeys.size(); i++) {
                String column = schema.clusteringKeys.get(i);
                int c = compare(schema.columns.get(column), x.path("ck").get(column), y.path("ck").get(column));
                if (c != 0) {
                    return schema.descending.get(i) ? -c : c;
                }
            }
            return 0;
        };
    }

    /** The partition token of a row's partition key values. */
    static long token(Schema schema, JsonNode pk) {
        byte[][] parts = new byte[schema.partitionKeys.size()][];
        for (int i = 0; i < parts.length; i++) {
            String column = schema.partitionKeys.get(i);
            parts[i] = CqlCodec.encode(schema.columns.get(column), pk.get(column), schema::udt);
        }
        return CqlMurmur3.token(parts);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
