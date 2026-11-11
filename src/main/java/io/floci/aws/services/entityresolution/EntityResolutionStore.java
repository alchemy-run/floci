package io.floci.aws.services.entityresolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The AWS Entity Resolution resources of every account, keyed {@code <region>/<kind>/<name>} (a job's is
 * {@code <workflowName>/<jobId>}), with the ARNs, pagination and errors its operations share. A record's {@code _}
 * member holds bookkeeping no response shows.
 */
@ApplicationScoped
public class EntityResolutionStore implements Resettable {

    static final String SERVICE = "entityresolution";
    static final String SCHEMA = "schemamapping";
    static final String MATCHING_WORKFLOW = "matchingworkflow";
    static final String ID_MAPPING_WORKFLOW = "idmappingworkflow";
    static final String ID_NAMESPACE = "idnamespace";
    static final String MATCHING_JOB = "matchingjob";
    static final String ID_MAPPING_JOB = "idmappingjob";
    static final String RECORDS = "records";
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EntityResolutionStore(StorageFactory storageFactory, ObjectMapper mapper) {
        this.storage = storageFactory.create("entityresolution", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
    }

    ObjectMapper mapper() {
        return mapper;
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

    void put(String region, String kind, String id, ObjectNode record) {
        storage.putForAccount(account(), key(region, kind, id), record);
    }

    void putForAccount(String accountId, String region, String kind, String id, ObjectNode record) {
        storage.putForAccount(accountId, key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        storage.deleteForAccount(account(), key(region, kind, id));
    }

    /** The records of a kind whose id starts with {@code idPrefix} and that match, oldest first. */
    List<ObjectNode> list(String region, String kind, String idPrefix, Predicate<ObjectNode> filter) {
        String prefix = key(region, kind, idPrefix);
        List<ObjectNode> records = new ArrayList<>();
        for (ObjectNode record : storage.scanForAccount(account(), key -> key.startsWith(prefix))) {
            if (filter.test(record)) {
                records.add(record);
            }
        }
        records.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return records;
    }

    private static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    /** An identifier of lowercase letters and digits. */
    String id(int length) {
        StringBuilder id = new StringBuilder();
        for (int index = 0; index < length; index++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /** An Entity Resolution ARN: {@code arn:<partition>:entityresolution:<region>:<account>:<kind>/<name>}. */
    String arn(String region, String kind, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), kind + "/" + name).toString();
    }

    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
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

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    /** A page of items in their order, sized by {@code maxResults}, with Entity Resolution's {@code nextToken}. */
    ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : 25;
        if (limit < 1) {
            throw invalid("maxResults must be a positive number.");
        }
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), limit, input.path("nextToken").asText(null),
                field, bad -> invalid("The nextToken is not valid."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("nextToken", paged.nextToken());
        }
        return response;
    }

    static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw invalid(field + " is required.");
        }
        return value.asText();
    }

    static String optional(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
