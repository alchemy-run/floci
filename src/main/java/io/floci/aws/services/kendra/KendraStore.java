package io.floci.aws.services.kendra;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Kendra records in every account. Resources are keyed {@code <region>/<kind>/<path>}, where the path is the
 * resource's id under its index ({@code <indexId>/<id>}); documents live in their own storage, keyed
 * {@code <region>/<indexId>/<documentId>}. A record's {@code _} member holds what no response shows: its tags,
 * creation order, client token and the time its pending status settles.
 */
@ApplicationScoped
public class KendraStore implements Resettable {

    static final String SERVICE = "kendra";
    static final String INDEX = "index";
    static final String DATA_SOURCE = "data-source";
    static final String SYNC_JOB = "sync-job";
    static final String FAQ = "faq";
    static final String THESAURUS = "thesaurus";
    static final String EXPERIENCE = "experience";
    static final String FEATURED = "featured-results-set";
    static final String BLOCK_LIST = "query-suggestions-block-list";
    static final String ACCESS_CONTROL = "access-control-configuration";
    static final String GROUP = "group";
    static final String QUERY = "query";
    static final String SUGGESTIONS = "query-suggestions";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<ObjectNode> documents;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public KendraStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.documents = storageFactory.create(SERVICE, "documents.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.KendraServiceConfig config() {
        return config.services().kendra();
    }

    EmulatorConfig emulator() {
        return config;
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
        documents.clear();
    }

    // ---------------------------------------------------------------- records

    static String key(String region, String kind, String path) {
        return region + "/" + kind + "/" + path;
    }

    Optional<ObjectNode> get(String account, String region, String kind, String path) {
        return storage.getForAccount(account, key(region, kind, path));
    }

    void put(String account, String region, String kind, String path, ObjectNode record) {
        storage.putForAccount(account, key(region, kind, path), record);
    }

    void delete(String account, String region, String kind, String path) {
        storage.deleteForAccount(account, key(region, kind, path));
    }

    /** The records of a kind whose path starts with a prefix, oldest first. */
    List<ObjectNode> list(String account, String region, String kind, String pathPrefix) {
        String prefix = key(region, kind, pathPrefix);
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    /** Every record of a kind in every account and Region. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(String kind) {
        String marker = "/" + kind + "/";
        return storage.scanAllAccountEntries(key -> key.contains(marker));
    }

    void deleteAll(String account, String region, String kind, String pathPrefix) {
        String prefix = key(region, kind, pathPrefix);
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : storage.scanAllAccountEntries(key -> key.startsWith(prefix))) {
            if (entry.accountId().equals(account)) {
                storage.deleteForAccount(account, entry.key());
            }
        }
    }

    // ---------------------------------------------------------------- documents

    static String documentKey(String region, String indexId, String documentId) {
        return region + "/" + indexId + "/" + documentId;
    }

    Optional<ObjectNode> document(String account, String region, String indexId, String documentId) {
        return documents.getForAccount(account, documentKey(region, indexId, documentId));
    }

    void putDocument(String account, String region, String indexId, String documentId, ObjectNode document) {
        documents.putForAccount(account, documentKey(region, indexId, documentId), document);
    }

    void deleteDocument(String account, String region, String indexId, String documentId) {
        documents.deleteForAccount(account, documentKey(region, indexId, documentId));
    }

    List<ObjectNode> documents(String account, String region, String indexId) {
        String prefix = region + "/" + indexId + "/";
        List<ObjectNode> found = new ArrayList<>(documents.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort(Comparator.comparingLong(document -> document.path("_").path("seq").asLong()));
        return found;
    }

    void deleteDocuments(String account, String region, String indexId, Predicate<ObjectNode> which) {
        for (ObjectNode document : documents(account, region, indexId)) {
            if (which.test(document)) {
                deleteDocument(account, region, indexId, document.path("Id").asText());
            }
        }
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

    String indexArn(String account, String region, String indexId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "index/" + indexId).toString();
    }

    String arn(String account, String region, String indexId, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "index/" + indexId + "/" + kind + "/" + id).toString();
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static double epoch(long millis) {
        return millis / 1000.0;
    }

    static String text(JsonNode in, String name) {
        JsonNode value = in == null ? null : in.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 400);
    }

    static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 400);
    }

    // ---------------------------------------------------------------- inputs

    /** Tags given as a {@code [{Key, Value}]} list, as an object. */
    static ObjectNode tagsOf(ObjectMapper mapper, JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = tag.path("Key").asText();
                if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw validation("Tag keys can't start with aws:.");
                }
                map.put(key, tag.path("Value").asText(""));
            }
        }
        if (map.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        return map;
    }

    static ArrayNode tagList(ObjectMapper mapper, JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        if (tags != null) {
            tags.fields().forEachRemaining(e -> list.addObject().put("Key", e.getKey()).put("Value",
                    e.getValue().asText()));
        }
        return list;
    }

    /** An earlier create's response for the same client token, or null for a new token. */
    ObjectNode repeated(String account, String region, String operation, JsonNode in) {
        String token = text(in, "ClientToken");
        if (token == null) {
            return null;
        }
        ObjectNode earlier = get(account, region, "client-token", operation + "/" + token).orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        if (!earlier.path("request").equals(request)) {
            throw conflict("The client token " + token + " was used with different parameters.");
        }
        return (ObjectNode) earlier.path("response").deepCopy();
    }

    void remember(String account, String region, String operation, JsonNode in, ObjectNode response) {
        String token = text(in, "ClientToken");
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        ObjectNode entry = mapper.createObjectNode();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        put(account, region, "client-token", operation + "/" + token, entry);
    }

    /** A page of items: {@code NextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultMax) {
        String token = text(in, "NextToken");
        int max = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : defaultMax;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw validation("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
