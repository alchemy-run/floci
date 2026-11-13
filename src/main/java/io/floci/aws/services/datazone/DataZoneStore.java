package io.floci.aws.services.datazone;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The DataZone resources of every account, keyed {@code <region>/<domain>/<kind>/<id>} (domains themselves
 * {@code <region>/domains/<id>}), with the identifiers, timestamps, pagination and errors every DataZone operation
 * shares.
 */
@ApplicationScoped
public class DataZoneStore implements Resettable {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public DataZoneStore(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver) {
        this.storage = storageFactory.create("datazone", "resources.json",
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

    Optional<ObjectNode> get(String region, String domainId, String kind, String id) {
        return storage.getForAccount(account(), key(region, domainId, kind, id));
    }

    ObjectNode require(String region, String domainId, String kind, String id, String label) {
        return get(region, domainId, kind, id).orElseThrow(() -> notFound(label + " " + id + " was not found."));
    }

    void put(String region, String domainId, String kind, String id, ObjectNode value) {
        storage.putForAccount(account(), key(region, domainId, kind, id), value);
    }

    void delete(String region, String domainId, String kind, String id) {
        storage.deleteForAccount(account(), key(region, domainId, kind, id));
    }

    List<ObjectNode> list(String region, String domainId, String kind) {
        String prefix = key(region, domainId, kind, "");
        List<ObjectNode> result = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(prefix)
                && key.indexOf('/', prefix.length()) < 0));
        result.sort(Comparator.comparing(value -> value.path("createdAt").asDouble()));
        return result;
    }

    /** Every resource stored under a domain, of every kind. */
    void deleteDomainContents(String region, String domainId) {
        String prefix = region + "/" + domainId + "/";
        for (String key : new ArrayList<>(storage.keysForAccount(account()))) {
            if (key.startsWith(prefix)) {
                storage.deleteForAccount(account(), key);
            }
        }
    }

    Optional<ObjectNode> domain(String region, String id) {
        return storage.getForAccount(account(), region + "/domains/" + id);
    }

    void putDomain(String region, String id, ObjectNode domain) {
        storage.putForAccount(account(), region + "/domains/" + id, domain);
    }

    void deleteDomain(String region, String id) {
        storage.deleteForAccount(account(), region + "/domains/" + id);
    }

    List<ObjectNode> domains(String region) {
        String prefix = region + "/domains/";
        List<ObjectNode> result = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(value -> value.path("createdAt").asDouble()));
        return result;
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> allDomains() {
        return storage.scanAllAccountEntries(key -> key.contains("/domains/"));
    }

    private static String key(String region, String domainId, String kind, String id) {
        return region + "/" + domainId + "/" + kind + "/" + id;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    // ---------------------------------------------------------------- values

    String id(int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    String id() {
        return id(14);
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    String arn(String region, String resource) {
        return regionResolver.buildArn("datazone", region, account(), resource);
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
        int max = 50;
        if (input.hasNonNull("maxResults")) {
            max = input.get("maxResults").asInt(-1);
            if (max < 1 || max > 50) {
                throw invalid("maxResults must be between 1 and 50.");
            }
        }
        String token = input.path("nextToken").asText(null);
        // Pagination orders by cursor; a positional cursor keeps the order the list was built in.
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        Function<ObjectNode, String> positional = item -> String.format("%09d", positions.get(item)) + ":"
                + cursor.apply(item);
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, positional, max, token, field,
                bad -> invalid("Invalid nextToken."));
        ObjectNode response = object();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("nextToken", paged.nextToken());
        }
        return response;
    }

    // ---------------------------------------------------------------- input

    static String text(JsonNode input, String field, int maximum) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(field + " is required.");
        }
        if (value.textValue().length() > maximum) {
            throw invalid(field + " must be at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    static String optional(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        return value == null || value.isNull() ? null : value.asText();
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

    static boolean present(JsonNode input, String field) {
        return input != null && input.hasNonNull(field);
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }
}
