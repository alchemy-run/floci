package io.floci.aws.services.directoryservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The AWS Directory Service resources of every account, keyed {@code <region>/<kind>/<id>}, with the identifiers,
 * timestamps, pagination and errors its operations share. A record's {@code _} member holds bookkeeping no
 * response shows.
 */
@ApplicationScoped
public class DirectoryStore implements Resettable {

    private static final String HEX = "0123456789abcdef";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public DirectoryStore(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver) {
        this.storage = storageFactory.create("ds", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    RegionResolver regions() {
        return regionResolver;
    }

    String account() {
        return storage.accountId();
    }

    Optional<ObjectNode> get(String region, String kind, String id) {
        return storage.getForAccount(account(), key(region, kind, id));
    }

    Optional<ObjectNode> getForAccount(String accountId, String region, String kind, String id) {
        return storage.getForAccount(accountId, key(region, kind, id));
    }

    void put(String region, String kind, String id, ObjectNode value) {
        storage.putForAccount(account(), key(region, kind, id), value);
    }

    void putForAccount(String accountId, String region, String kind, String id, ObjectNode value) {
        storage.putForAccount(accountId, key(region, kind, id), value);
    }

    void delete(String region, String kind, String id) {
        storage.deleteForAccount(account(), key(region, kind, id));
    }

    void deleteForAccount(String accountId, String region, String kind, String id) {
        storage.deleteForAccount(accountId, key(region, kind, id));
    }

    /** The records of a kind that match, oldest first. */
    List<ObjectNode> list(String region, String kind, Predicate<ObjectNode> filter) {
        String prefix = key(region, kind, "");
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode value : storage.scanForAccount(account(), key -> key.startsWith(prefix))) {
            if (filter.test(value)) {
                result.add(value);
            }
        }
        result.sort(Comparator.comparing(value -> value.path("_").path("seq").asLong()));
        return result;
    }

    /** Every account's records of a kind in a region. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> listAll(String region, String kind) {
        String prefix = key(region, kind, "");
        return storage.scanAllAccountEntries(key -> key.startsWith(prefix));
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    /** A Directory Service identifier: the prefix and ten lowercase hexadecimal digits ({@code d-0123456789}). */
    String id(String prefix) {
        StringBuilder id = new StringBuilder(prefix).append('-');
        for (int index = 0; index < 10; index++) {
            id.append(HEX.charAt(random.nextInt(16)));
        }
        return id.toString();
    }

    int nextInt(int bound) {
        return random.nextInt(bound);
    }

    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
        return record;
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    ObjectNode object() {
        return mapper.createObjectNode();
    }

    ArrayNode array() {
        return mapper.createArrayNode();
    }

    ObjectNode view(ObjectNode stored) {
        ObjectNode copy = stored.deepCopy();
        copy.remove("_");
        return copy;
    }

    /** A page of items in their order, sized by {@code Limit}, with Directory Service's {@code NextToken}. */
    ObjectNode page(List<ObjectNode> items, JsonNode input, String field, int max) {
        int limit = input.hasNonNull("Limit") ? input.get("Limit").asInt(-1) : max;
        if (limit < 0) {
            throw invalid("Limit must be a positive number.");
        }
        if (limit == 0) {
            limit = max;
        }
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, item -> String.format("%09d",
                positions.get(item)), Math.min(limit, max), input.path("NextToken").asText(null), field,
                bad -> new AwsException("InvalidNextTokenException", "The NextToken value is not valid.", 400));
        ObjectNode response = object();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    // ---------------------------------------------------------------- input

    static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(field + " is required.");
        }
        return value.textValue();
    }

    static String optional(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static List<String> strings(JsonNode list) {
        List<String> result = new ArrayList<>();
        if (list != null) {
            list.forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    // ---------------------------------------------------------------- errors

    static AwsException error(String code, String message) {
        return new AwsException(code, message, 400, Map.of("RequestId", UUID.randomUUID().toString()));
    }

    static AwsException invalid(String message) {
        return error("InvalidParameterException", message);
    }

    static AwsException missing(String message) {
        return error("EntityDoesNotExistException", message);
    }

    static AwsException exists(String message) {
        return error("EntityAlreadyExistsException", message);
    }

    static AwsException unsupported(String message) {
        return error("UnsupportedOperationException", message);
    }

    static AwsException unavailable(String message) {
        return error("DirectoryUnavailableException", message);
    }

    static AwsException client(String message) {
        return error("ClientException", message);
    }
}
