package io.floci.aws.services.osis;

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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * OpenSearch Ingestion records in every account, keyed {@code <region>/<kind>/<id>}: pipelines, pipeline endpoints
 * and resource policies. A record's {@code _} member holds what no response shows: its order, its ingest host and its
 * engine's state.
 */
@ApplicationScoped
public class OsisStore implements Resettable {

    static final String SERVICE = "osis";
    static final String PIPELINE = "pipeline";
    static final String ENDPOINT = "endpoint";
    static final String POLICY = "policy";
    /** The DNS suffix ingest endpoints are named under: Floci's wildcard localhost domain. */
    static final String DNS_SUFFIX = "localhost.floci.io";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public OsisStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("osis", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.OsisServiceConfig config() {
        return config.services().osis();
    }

    /** The base URL Floci is reached at. */
    String endpoint() {
        return config.effectiveBaseUrl().replaceAll("/+$", "");
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    Optional<ObjectNode> get(String region, String kind, String id) {
        return getForAccount(account(), region, kind, id);
    }

    Optional<ObjectNode> getForAccount(String account, String region, String kind, String id) {
        return storage.getForAccount(account, key(region, kind, id)).map(ObjectNode::deepCopy);
    }

    void put(String region, String kind, String id, ObjectNode record) {
        putForAccount(account(), region, kind, id, record);
    }

    void putForAccount(String account, String region, String kind, String id, ObjectNode record) {
        meta(record).put("id", id).put("account", account).put("region", region);
        storage.putForAccount(account, key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        deleteForAccount(account(), region, kind, id);
    }

    void deleteForAccount(String account, String region, String kind, String id) {
        storage.deleteForAccount(account, key(region, kind, id));
    }

    List<ObjectNode> list(String region, String kind) {
        return listForAccount(account(), region, kind);
    }

    List<ObjectNode> listForAccount(String account, String region, String kind) {
        String start = key(region, kind, "");
        List<ObjectNode> found = new ArrayList<>();
        storage.scanForAccount(account, k -> k.startsWith(start)).forEach(item -> found.add(item.deepCopy()));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    /** The records of a kind in every account and Region. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(String kind) {
        String marker = "/" + kind + "/";
        return storage.scanAllAccountEntries(k -> k.contains(marker));
    }

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

    static String random(int length, String alphabet) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    String arnFor(String account, String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException exists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 409);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException limit(String message) {
        return new AwsException("LimitExceededException", message, 409);
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    // ---------------------------------------------------------------- tags

    /** A record's tags, as OpenSearch Ingestion answers them: {@code [{"Key": ..., "Value": ...}]}. */
    static ArrayNode tags(ObjectNode record) {
        if (!record.path("Tags").isArray()) {
            record.putArray("Tags");
        }
        return (ArrayNode) record.get("Tags");
    }

    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return;
        }
        ArrayNode list = tags(record);
        for (JsonNode tag : tags) {
            String key = tag.path("Key").asText("");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
            removeTag(list, key);
            ObjectNode entry = list.addObject();
            entry.put("Key", key);
            entry.put("Value", tag.path("Value").asText(""));
        }
        if (list.size() > 50) {
            throw limit("A resource can have at most 50 tags.");
        }
    }

    static void removeTag(ArrayNode tags, String key) {
        for (int i = tags.size() - 1; i >= 0; i--) {
            if (key.equals(tags.get(i).path("Key").asText())) {
                tags.remove(i);
            }
        }
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultSize) {
        String token = in.path("NextToken").asText(null);
        int size = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : defaultSize;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidPaginationTokenException", "The NextToken is not valid.", 400);
            }
            if (start < 0 || start > items.size()) {
                throw new AwsException("InvalidPaginationTokenException", "The NextToken is not valid.", 400);
            }
        }
        int end = Math.min(items.size(), start + size);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
