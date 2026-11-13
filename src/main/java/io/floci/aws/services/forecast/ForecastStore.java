package io.floci.aws.services.forecast;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Amazon Forecast resources of every account, keyed {@code <region>/<arn resource>} (a dataset is
 * {@code dataset/<name>}, an import job {@code dataset-import-job/<dataset>/<name>}), with the ARNs, status chains,
 * pagination and errors its operations share. A record's {@code _} member holds what no response shows: its kind,
 * sequence, tags, the steps its status still has to take, and the data or model it holds.
 */
@ApplicationScoped
public class ForecastStore implements Resettable {

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public ForecastStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("forecast", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
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
        return config.services().forecast().transitionMillis();
    }

    boolean existingCustomer() {
        return config.services().forecast().existingCustomer();
    }

    // ---------------------------------------------------------------- records

    /** A new record of a kind, its ARN {@code arn:<partition>:forecast:<region>:<account>:<resource>}. */
    ObjectNode record(String region, String kind, String resource, String arnField) {
        ObjectNode record = mapper.createObjectNode();
        record.put(arnField, arn(region, resource));
        double now = now();
        record.put("CreationTime", now).put("LastModificationTime", now);
        record.putObject("_").put("kind", kind).put("resource", resource).put("arnField", arnField)
                .put("seq", System.nanoTime()).putArray("tags");
        return record;
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("forecast", region, account(), resource).toString();
    }

    /** The resource part of a Forecast ARN of this account and Region, or null when it is none. */
    String resourceOf(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalid("The ARN " + arn + " is not valid.");
        }
        if (!"forecast".equals(parsed.service())) {
            throw invalid("The ARN " + arn + " is not an Amazon Forecast ARN.");
        }
        return region.equals(parsed.region()) && account().equals(parsed.accountId()) ? parsed.resource() : null;
    }

    Optional<ObjectNode> byArn(String region, String arn) {
        String resource = resourceOf(region, arn);
        return resource == null ? Optional.empty() : get(region, resource);
    }

    Optional<ObjectNode> get(String region, String resource) {
        return storage.getForAccount(account(), key(region, resource)).map(record -> settle(region, record));
    }

    void save(String region, ObjectNode record) {
        record.put("LastModificationTime", now());
        storage.putForAccount(account(), key(region, meta(record).path("resource").asText()), record);
    }

    void delete(String region, ObjectNode record) {
        storage.deleteForAccount(account(), key(region, meta(record).path("resource").asText()));
    }

    List<ObjectNode> list(String region, String kind, Predicate<ObjectNode> filter) {
        String prefix = region + "/" + kind + "/";
        List<ObjectNode> records = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(prefix)));
        records.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        List<ObjectNode> live = new ArrayList<>();
        for (ObjectNode record : records) {
            ObjectNode settled = settle(region, record);
            if (settled != null && kind.equals(meta(settled).path("kind").asText()) && filter.test(settled)) {
                live.add(settled);
            }
        }
        return live;
    }

    private static String key(String region, String resource) {
        return region + "/" + resource;
    }

    @Override
    public void clear() {
        storage.clear();
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

    // ---------------------------------------------------------------- status chains

    /**
     * Sets a record's status and the ones it will pass through, a transition apart each; a final {@code GONE}
     * removes the record.
     */
    void chain(String region, ObjectNode record, String status, String... next) {
        record.put("Status", status);
        ArrayNode steps = meta(record).putArray("steps");
        long at = System.currentTimeMillis();
        for (String step : next) {
            at += transitionMillis();
            steps.addObject().put("status", step).put("at", at);
        }
        save(region, record);
        settle(region, record);
    }

    /** Applies the steps whose time has come; null when the record is gone. */
    ObjectNode settle(String region, ObjectNode record) {
        ArrayNode steps = meta(record).withArray("/steps");
        boolean changed = false;
        while (!steps.isEmpty() && steps.get(0).path("at").asLong() <= System.currentTimeMillis()) {
            String status = steps.get(0).path("status").asText();
            steps.remove(0);
            if ("GONE".equals(status)) {
                delete(region, record);
                return null;
            }
            record.put("Status", status);
            changed = true;
        }
        if (changed) {
            save(region, record);
        }
        return record;
    }

    // ---------------------------------------------------------------- output

    ObjectNode page(List<ObjectNode> items, JsonNode input, String field) {
        int limit = input.hasNonNull("MaxResults") ? input.get("MaxResults").asInt() : 100;
        Map<ObjectNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            positions.put(items.get(index), index);
        }
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items,
                item -> String.format("%09d", positions.get(item)), Math.max(1, limit),
                input.path("NextToken").asText(null), field, bad -> new AwsException("InvalidNextTokenException",
                        "The token is not valid.", 400));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
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

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException exists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }
}
