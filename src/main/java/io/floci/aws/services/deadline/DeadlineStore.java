package io.floci.aws.services.deadline;

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
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Deadline Cloud resources of every account, keyed {@code <region>/<kind>/<id>}, with the identifiers,
 * timestamps, ARNs, pagination and errors every Deadline operation shares. A record's {@code _} member holds
 * bookkeeping no response shows.
 */
@ApplicationScoped
public class DeadlineStore implements Resettable {

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public DeadlineStore(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver) {
        this.storage = storageFactory.create("deadline", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    /** Fields of an injected bean are its client proxy's; read these through methods. */
    ObjectMapper mapper() {
        return mapper;
    }

    RegionResolver regions() {
        return regionResolver;
    }

    String account() {
        return storage.accountId();
    }

    // ---------------------------------------------------------------- storage

    Optional<ObjectNode> get(String region, String kind, String id) {
        return storage.getForAccount(account(), key(region, kind, id));
    }

    ObjectNode require(String region, String kind, String id, String label) {
        return get(region, kind, id).orElseThrow(() -> notFound(label + " " + id + " not found.", kind, id));
    }

    void put(String region, String kind, String id, ObjectNode value) {
        storage.putForAccount(account(), key(region, kind, id), value);
    }

    void delete(String region, String kind, String id) {
        storage.deleteForAccount(account(), key(region, kind, id));
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

    List<ObjectNode> list(String region, String kind) {
        return list(region, kind, value -> true);
    }

    /** Records of a kind under a parent: {@code _.farmId}, {@code _.queueId}, ... equal to {@code value}. */
    List<ObjectNode> children(String region, String kind, String field, String value) {
        return list(region, kind, record -> value.equals(record.path("_").path(field).asText()));
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    // ---------------------------------------------------------------- values

    /** A Deadline identifier: the prefix and 32 lowercase hexadecimal digits. */
    String id(String prefix) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return prefix + "-" + HexFormat.of().formatHex(bytes);
    }

    /** A fresh record carrying its creation order, so lists keep creation order. */
    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
        return record;
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    String arn(String region, String resource) {
        return regionResolver.buildArn("deadline", region, account(), resource);
    }

    ObjectNode object() {
        return mapper.createObjectNode();
    }

    ArrayNode array() {
        return mapper.createArrayNode();
    }

    /** The stored value without its internal {@code _} bookkeeping. */
    ObjectNode view(ObjectNode stored) {
        ObjectNode copy = stored.deepCopy();
        copy.remove("_");
        return copy;
    }

    ObjectNode view(ObjectNode stored, String... fields) {
        ObjectNode copy = object();
        for (String field : fields) {
            if (stored.has(field) && !stored.get(field).isNull()) {
                copy.set(field, stored.get(field).deepCopy());
            }
        }
        return copy;
    }

    ObjectNode page(List<ObjectNode> items, JsonNode input, String field, Function<ObjectNode, String> cursor) {
        int max = 100;
        if (input.hasNonNull("maxResults")) {
            max = input.get("maxResults").asInt(-1);
            if (max < 1 || max > 100) {
                throw invalid("maxResults must be between 1 and 100.");
            }
        }
        // Pagination orders by cursor; a positional cursor keeps the order the list was built in.
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        Function<ObjectNode, String> positional = item -> String.format("%09d", positions.get(item)) + ":"
                + cursor.apply(item);
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, positional, max,
                input.path("nextToken").asText(null), field, bad -> invalid("Invalid nextToken."));
        ObjectNode response = object();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("nextToken", paged.nextToken());
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

    static boolean present(JsonNode input, String field) {
        return input != null && input.hasNonNull(field);
    }

    static String oneOf(JsonNode input, String field, Set<String> allowed, String fallback) {
        String value = optional(input, field);
        if (value == null) {
            if (fallback == null) {
                throw invalid(field + " is required.");
            }
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw invalid(field + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return value;
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }

    /** Deadline's not-found error names the missing resource's type and identifier. */
    static AwsException notFound(String message, String kind, String id) {
        return new AwsException("ResourceNotFoundException", message, 404, Map.of("resourceId", id,
                "resourceType", resourceType(kind)));
    }

    /** A conflict on a resource: in use by another, in the wrong status, or already existing. */
    static AwsException conflict(String message, String reason, String kind, String id) {
        return new AwsException("ConflictException", message, 409, Map.of("reason", reason, "resourceId", id,
                "resourceType", resourceType(kind)));
    }

    static AwsException quota(String message, String kind) {
        return new AwsException("ServiceQuotaExceededException", message, 402, Map.of("reason",
                "SERVICE_QUOTA_EXCEEDED_EXCEPTION", "resourceType", resourceType(kind), "serviceCode", "deadline",
                "quotaCode", "L-" + kind.toUpperCase().replace("-", "")));
    }

    /** A storage kind as the resource type Deadline names in errors, such as QUEUE_FLEET_ASSOCIATION. */
    static String resourceType(String kind) {
        String singular = kind.endsWith("ies") ? kind.substring(0, kind.length() - 3) + "y"
                : kind.endsWith("s") ? kind.substring(0, kind.length() - 1) : kind;
        return singular.replace('-', '_').toUpperCase();
    }
}
