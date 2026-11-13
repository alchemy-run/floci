package io.floci.aws.services.emrcontainers;

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
 * The EMR on EKS resources of every account, keyed {@code <region>/<kind>/<id>} (a job run's id is
 * {@code <virtualClusterId>/<jobRunId>}), with the identifiers, ARNs, pagination and errors its operations share. A
 * record's {@code _} member holds bookkeeping no response shows.
 */
@ApplicationScoped
public class EmrContainersStore implements Resettable {

    static final String SERVICE = "emr-containers";
    static final String VIRTUAL_CLUSTER = "virtualcluster";
    static final String JOB_RUN = "jobrun";
    static final String TEMPLATE = "jobtemplate";
    static final String ENDPOINT = "endpoint";
    static final String SECURITY_CONFIGURATION = "securityconfiguration";
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EmrContainersStore(StorageFactory storageFactory, ObjectMapper mapper) {
        this.storage = storageFactory.create("emr-containers", "resources.json",
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

    /** An EMR on EKS identifier: lowercase letters and digits. */
    String id(int length) {
        StringBuilder id = new StringBuilder();
        for (int index = 0; index < length; index++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /** An EMR on EKS ARN, whose resource begins with a slash ({@code ...:emr-containers:<region>:<account>:/...}). */
    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), "/" + resource).toString();
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

    /** Whether a record was created inside the request's {@code createdAfter} / {@code createdBefore} bounds. */
    static boolean createdWithin(ObjectNode record, JsonNode input) {
        double created = record.path("createdAt").asDouble();
        if (input.hasNonNull("createdAfter") && created < input.get("createdAfter").asDouble()) {
            return false;
        }
        return !input.hasNonNull("createdBefore") || created <= input.get("createdBefore").asDouble();
    }

    /** A page of items in their order, sized by {@code maxResults}, with EMR on EKS's {@code nextToken}. */
    ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.hasNonNull("maxResults") ? input.get("maxResults").asInt() : 50;
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
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
