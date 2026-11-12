package io.floci.aws.services.opensearchserverless;

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
 * OpenSearch Serverless records in every account, keyed {@code <region>/<kind>/<id>}: collections, collection groups,
 * security, access and lifecycle policies, security configurations, VPC endpoints, ingestion jobs and streams, and
 * account settings. A record's {@code _} member holds what no response shows: its order, owning account and tags.
 */
@ApplicationScoped
public class AossStore implements Resettable {

    static final String SERVICE = "aoss";
    static final String COLLECTION = "collection";
    static final String GROUP = "collectiongroup";
    static final String SECURITY_POLICY = "securitypolicy";
    static final String ACCESS_POLICY = "accesspolicy";
    static final String LIFECYCLE_POLICY = "lifecyclepolicy";
    static final String SECURITY_CONFIG = "securityconfig";
    static final String VPC_ENDPOINT = "vpcendpoint";
    static final String INGESTION_JOB = "ingestionjob";
    static final String INGESTION_STREAM = "ingestionstream";
    static final String SETTINGS = "settings";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public AossStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("opensearchserverless", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.OpenSearchServerlessServiceConfig config() {
        return config.services().opensearchServerless();
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

    static long now() {
        return System.currentTimeMillis();
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
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 400);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    /** Adds tags given as {@code [{"key": ..., "value": ...}]}. */
    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return;
        }
        ObjectNode map = tags(record);
        for (JsonNode tag : tags) {
            String key = tag.path("key").asText("");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("value").asText(""));
        }
        if (map.size() > 50) {
            throw quota("A resource can have at most 50 tags.");
        }
    }

    /** A record's tags as {@code [{"key": ..., "value": ...}]}. */
    static ArrayNode tagList(ObjectNode record, ObjectMapper mapper) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).properties().forEach(tag -> list.addObject().put("key", tag.getKey())
                .put("value", tag.getValue().asText()));
        return list;
    }

    /** A new policy version: an opaque base64 token. */
    static String version() {
        return Base64.getEncoder().encodeToString(random(18, "abcdefghijklmnopqrstuvwxyz0123456789")
                .getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultSize) {
        String token = in.path("nextToken").asText(null);
        int size = in.hasNonNull("maxResults") && in.get("maxResults").asInt() > 0 ? in.get("maxResults").asInt()
                : defaultSize;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw validation("The nextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
