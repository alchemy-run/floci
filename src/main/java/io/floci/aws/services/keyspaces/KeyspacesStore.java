package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keyspaces records in every account. Keyspaces, tables and types are keyed {@code <region>/<kind>/<path>} (a
 * table's path is {@code <keyspace>/<table>}); rows are keyed {@code <region>/<keyspace>/<table>/<partition>/<row>}
 * by the JSON of their key values; each table's mutation log, which point-in-time recovery restores from and CDC
 * streams read, is keyed {@code <region>/<keyspace>/<table>/<sequence>}. A record's {@code _} member holds what no
 * response shows: its tags and the time its pending status settles.
 */
@ApplicationScoped
public class KeyspacesStore implements Resettable {

    static final String SERVICE = "cassandra";
    static final String KEYSPACE = "keyspace";
    static final String TABLE = "table";
    static final String TYPE = "type";
    static final String STREAM = "stream";

    private final AccountAwareStorageBackend<ObjectNode> resources;
    private final AccountAwareStorageBackend<ObjectNode> rows;
    private final AccountAwareStorageBackend<ObjectNode> changes;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public KeyspacesStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.resources = storageFactory.create("keyspaces", "resources.json", type);
        this.rows = storageFactory.create("keyspaces", "rows.json", type);
        this.changes = storageFactory.create("keyspaces", "changes.json", type);
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.KeyspacesServiceConfig config() {
        return config.services().keyspaces();
    }

    String account() {
        return resources.accountId();
    }

    @Override
    public void clear() {
        resources.clear();
        rows.clear();
        changes.clear();
    }

    // ---------------------------------------------------------------- resources

    static String key(String region, String kind, String path) {
        return region + "/" + kind + "/" + path;
    }

    Optional<ObjectNode> get(String account, String region, String kind, String path) {
        return resources.getForAccount(account, key(region, kind, path));
    }

    void put(String account, String region, String kind, String path, ObjectNode record) {
        meta(record).put("path", path);
        resources.putForAccount(account, key(region, kind, path), record);
    }

    void delete(String account, String region, String kind, String path) {
        resources.deleteForAccount(account, key(region, kind, path));
    }

    List<ObjectNode> list(String account, String region, String kind, String pathPrefix) {
        String prefix = key(region, kind, pathPrefix);
        List<ObjectNode> found = new ArrayList<>(resources.scanForAccount(account, k -> k.startsWith(prefix)));
        found.sort(Comparator.comparingLong(r -> r.path("_").path("seq").asLong()));
        return found;
    }

    // ---------------------------------------------------------------- rows

    static String tableKey(String region, String keyspace, String table) {
        return region + "/" + keyspace + "/" + table + "/";
    }

    Optional<ObjectNode> row(String account, String rowKey) {
        return rows.getForAccount(account, rowKey);
    }

    void putRow(String account, String rowKey, ObjectNode row) {
        rows.putForAccount(account, rowKey, row);
    }

    void deleteRow(String account, String rowKey) {
        rows.deleteForAccount(account, rowKey);
    }

    /** A table's rows (and static rows), in key order. */
    List<ObjectNode> rows(String account, String region, String keyspace, String table) {
        String prefix = tableKey(region, keyspace, table);
        List<ObjectNode> found = new ArrayList<>(rows.scanForAccount(account, k -> k.startsWith(prefix)));
        found.sort(Comparator.comparing(r -> r.path("_key").asText()));
        return found;
    }

    void deleteRows(String account, String region, String keyspace, String table) {
        for (ObjectNode row : rows(account, region, keyspace, table)) {
            rows.deleteForAccount(account, row.path("_key").asText());
        }
    }

    // ---------------------------------------------------------------- mutation log

    void putChange(String account, String region, String keyspace, String table, ObjectNode change) {
        changes.putForAccount(account, tableKey(region, keyspace, table) + String.format("%021d",
                change.path("seq").asLong()), change);
    }

    List<ObjectNode> changes(String account, String region, String keyspace, String table) {
        String prefix = tableKey(region, keyspace, table);
        List<ObjectNode> found = new ArrayList<>(changes.scanForAccount(account, k -> k.startsWith(prefix)));
        found.sort(Comparator.comparingLong(c -> c.path("seq").asLong()));
        return found;
    }

    void deleteChanges(String account, String region, String keyspace, String table, long before) {
        for (ObjectNode change : changes(account, region, keyspace, table)) {
            if (change.path("at").asLong() < before) {
                changes.deleteForAccount(account, tableKey(region, keyspace, table) + String.format("%021d",
                        change.path("seq").asLong()));
            }
        }
    }

    void deleteAllChanges(String account, String region, String keyspace, String table) {
        deleteChanges(account, region, keyspace, table, Long.MAX_VALUE);
    }

    // ---------------------------------------------------------------- helpers

    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
        if (!record.has("_")) {
            record.putObject("_");
        }
        return (ObjectNode) record.get("_");
    }

    static ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    String keyspaceArn(String account, String region, String keyspace) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "/keyspace/" + keyspace + "/").toString();
    }

    String tableArn(String account, String region, String keyspace, String table) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "/keyspace/" + keyspace + "/table/" + table)
                .toString();
    }

    static double epoch(long millis) {
        return millis / 1000.0;
    }

    static String text(JsonNode in, String name) {
        JsonNode value = in == null ? null : in.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message, String arn) {
        return new AwsException("ResourceNotFoundException", message, 404,
                arn == null ? null : Map.of("resourceArn", arn));
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    static ArrayNode tagList(ObjectMapper mapper, JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        if (tags != null) {
            tags.fields().forEachRemaining(e -> list.addObject().put("key", e.getKey()).put("value",
                    e.getValue().asText()));
        }
        return list;
    }

    static ObjectNode tagsOf(ObjectMapper mapper, JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags != null) {
            for (JsonNode tag : tags) {
                map.put(tag.path("key").asText(), tag.path("value").asText());
            }
        }
        if (map.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        return map;
    }

    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultMax) {
        String token = text(in, "nextToken");
        int max = in.hasNonNull("maxResults") && in.get("maxResults").asInt() > 0 ? in.get("maxResults").asInt()
                : defaultMax;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        if (start < items.size()) {
            items.subList(start, end).forEach(array::add);
        }
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
