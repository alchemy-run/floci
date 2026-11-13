package io.floci.aws.services.fsx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Amazon FSx resources of every account, keyed {@code <region>/<kind>/<id>}, with the identifiers, ARNs,
 * lifecycle transitions, tags, pagination and errors its operations share. A record's {@code _} member holds what no
 * response shows: its sequence, the state a transition settles in and when, and its owner.
 *
 * <p>Tags are kept as AWS returns them, a {@code Tags} list of {@code Key}/{@code Value} on the record.
 */
@ApplicationScoped
public class FsxStore implements Resettable {

    static final String FILE_SYSTEM = "file-system";
    static final String BACKUP = "backup";
    static final String VOLUME = "volume";
    static final String SNAPSHOT = "snapshot";
    static final String SVM = "storage-virtual-machine";
    static final String ASSOCIATION = "association";
    static final String TASK = "task";
    static final String CACHE = "file-cache";
    static final String ACCESS_POINT = "s3-access-point-attachment";
    private static final String HEX = "0123456789abcdef";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public FsxStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("fsx", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    String account() {
        return storage.accountId();
    }

    long transitionMillis() {
        return config.services().fsx().transitionMillis();
    }

    // ---------------------------------------------------------------- records

    Optional<ObjectNode> get(String region, String kind, String id) {
        return storage.getForAccount(account(), key(region, kind, id)).map(record -> settle(region, kind, record))
                .filter(record -> record != null);
    }

    void put(String region, String kind, String id, ObjectNode record) {
        storage.putForAccount(account(), key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        storage.deleteForAccount(account(), key(region, kind, id));
    }

    /** The records of a kind that match, oldest first, their transitions applied. */
    List<ObjectNode> list(String region, String kind, Predicate<ObjectNode> filter) {
        String prefix = key(region, kind, "");
        List<ObjectNode> records = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(prefix)));
        records.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        List<ObjectNode> live = new ArrayList<>();
        for (ObjectNode record : records) {
            ObjectNode settled = settle(region, kind, record);
            if (settled != null && filter.test(settled)) {
                live.add(settled);
            }
        }
        return live;
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    ObjectNode record(String idField, String id) {
        ObjectNode record = mapper.createObjectNode();
        record.put(idField, id);
        record.putObject("_").put("seq", System.nanoTime()).put("idField", idField);
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }

    static ObjectNode view(ObjectNode record) {
        ObjectNode copy = record.deepCopy();
        copy.remove("_");
        return copy;
    }

    // ---------------------------------------------------------------- lifecycles

    /**
     * Puts a record in a transitional lifecycle state that settles after the configured delay; a {@code null}
     * settled state removes the record when it settles.
     */
    void transition(String region, String kind, ObjectNode record, String field, String state, String settled) {
        record.put(field, state);
        ObjectNode meta = meta(record);
        meta.put("settleField", field).put("settle", settled == null ? "GONE" : settled)
                .put("readyAt", System.currentTimeMillis() + transitionMillis());
        put(region, kind, idOf(record), record);
        settle(region, kind, record);
    }

    /** Applies a transition whose time has come; null when the record is gone. */
    ObjectNode settle(String region, String kind, ObjectNode record) {
        ObjectNode meta = meta(record);
        String next = meta.path("settle").asText(null);
        if (next == null || meta.path("readyAt").asLong() > System.currentTimeMillis()) {
            return record;
        }
        String field = meta.path("settleField").asText();
        meta.remove(List.of("settle", "settleField", "readyAt"));
        if ("GONE".equals(next)) {
            delete(region, kind, idOf(record));
            return null;
        }
        record.put(field, next);
        for (JsonNode action : record.path("AdministrativeActions")) {
            if (!"COMPLETED".equals(action.path("Status").asText())) {
                ((ObjectNode) action).put("Status", "COMPLETED").put("ProgressPercent", 100);
            }
        }
        put(region, kind, idOf(record), record);
        return record;
    }

    static String idOf(ObjectNode record) {
        return record.path(meta(record).path("idField").asText()).asText();
    }

    // ---------------------------------------------------------------- identifiers

    /** An FSx identifier: the prefix and 17 lowercase hexadecimal digits ({@code fs-0123456789abcdef0}). */
    String id(String prefix) {
        StringBuilder id = new StringBuilder(prefix).append('-');
        for (int index = 0; index < 17; index++) {
            id.append(HEX.charAt(random.nextInt(16)));
        }
        return id.toString();
    }

    String random(String alphabet, int length) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("fsx", region, account(), resource).toString();
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    // ---------------------------------------------------------------- tags

    ArrayNode tags(JsonNode input) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (input != null) {
            for (JsonNode tag : input) {
                merged.put(tag.path("Key").asText(), tag.path("Value").asText(""));
            }
        }
        if (merged.size() > 50) {
            throw badRequest("A resource can have at most 50 tags.");
        }
        ArrayNode tags = mapper.createArrayNode();
        merged.forEach((key, value) -> tags.addObject().put("Key", key).put("Value", value));
        return tags;
    }

    // ---------------------------------------------------------------- input and output

    /** A page of items in their order, sized by {@code MaxResults}, with FSx's {@code NextToken}. */
    ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.hasNonNull("MaxResults") ? input.get("MaxResults").asInt() : 2147483647;
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), Math.max(1, limit),
                input.path("NextToken").asText(null), field, bad -> badRequest("The NextToken is not valid."));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode out = response.putArray(field);
        paged.items().forEach(item -> out.add(view(item)));
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    /** Whether a record matches the request's {@code Filters} ({@code Name} and {@code Values}). */
    static boolean filtered(JsonNode filters, Map<String, String> values) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText();
            boolean any = false;
            for (JsonNode value : filter.path("Values")) {
                any |= value.asText().equals(values.get(name));
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw badRequest(field + " is required.");
        }
        return value.asText();
    }

    static String optional(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static List<String> strings(JsonNode list) {
        List<String> values = new ArrayList<>();
        if (list != null) {
            list.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    // ---------------------------------------------------------------- errors

    static AwsException badRequest(String message) {
        return new AwsException("BadRequest", message, 400);
    }

    static AwsException incompatible(String parameter, String message) {
        return new AwsException("IncompatibleParameterError", message, 400, Map.of("Parameter", parameter));
    }

    static AwsException notFound(String code, String message) {
        return new AwsException(code, message, 400);
    }
}
