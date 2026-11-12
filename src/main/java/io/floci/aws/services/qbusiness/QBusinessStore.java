package io.floci.aws.services.qbusiness;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon Q Business and Q Apps records in every account, keyed {@code <region>/<kind>/<id>}. A record's {@code _}
 * member holds what no response shows: its order, owning account, Region, tags and working state.
 */
@ApplicationScoped
public class QBusinessStore implements Resettable {

    static final String SERVICE = "qbusiness";
    static final String APPLICATION = "application";
    static final String INDEX = "index";
    static final String RETRIEVER = "retriever";
    static final String DATA_SOURCE = "data-source";
    static final String SYNC_JOB = "sync-job";
    static final String DOCUMENT = "document";
    static final String GROUP = "group";
    static final String USER = "user";
    static final String WEB = "web-experience";
    static final String PLUGIN = "plugin";
    static final String ACCESSOR = "data-accessor";
    static final String RESPONSE_CONFIG = "chat-response-configuration";
    static final String SUBSCRIPTION = "subscription";
    static final String CONVERSATION = "conversation";
    static final String MESSAGE = "message";
    static final String ATTACHMENT = "attachment";
    static final String QAPP = "qapp";
    static final String SESSION = "qapp-session";
    static final String LIBRARY = "library-item";
    static final String CATEGORY = "category";
    static final String FILE = "qapp-file";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public QBusinessStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("qbusiness", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.QBusinessServiceConfig config() {
        return config.services().qbusiness();
    }

    /** The base URL Floci is reached at. */
    String endpoint() {
        return config.effectiveBaseUrl().replaceAll("/+$", "");
    }

    /** An instant as ISO 8601, as Q Apps writes its timestamps. */
    static String iso(double seconds) {
        return Instant.ofEpochMilli((long) (seconds * 1000)).toString();
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
        meta(record).put("id", id).put("account", account).put("region", region).put("kind", kind);
        storage.putForAccount(account, key(region, kind, id), record);
    }

    void delete(String region, String kind, String id) {
        deleteForAccount(account(), region, kind, id);
    }

    void deleteForAccount(String account, String region, String kind, String id) {
        storage.deleteForAccount(account, key(region, kind, id));
    }

    /** Every record of a kind in a Region, oldest first, optionally under an id prefix. */
    List<ObjectNode> list(String region, String kind, String idPrefix) {
        return listForAccount(account(), region, kind, idPrefix);
    }

    List<ObjectNode> list(String region, String kind) {
        return list(region, kind, "");
    }

    List<ObjectNode> listForAccount(String account, String region, String kind, String idPrefix) {
        String start = key(region, kind, idPrefix);
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

    static String id(ObjectNode record) {
        return meta(record).path("id").asText();
    }

    static String region(ObjectNode record) {
        return meta(record).path("region").asText();
    }

    static String owner(ObjectNode record) {
        return meta(record).path("account").asText();
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    static String hex(int length) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) {
            id.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return id.toString();
    }

    static int randomInt(int bound) {
        return RANDOM.nextInt(bound);
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }

    // ---------------------------------------------------------------- transitions

    /** Sets a status member that becomes another value once the transition time has passed. */
    void transition(ObjectNode record, String member, String now, String later) {
        record.put(member, now);
        if (later == null) {
            meta(record).remove("next");
        } else {
            meta(record).put("member", member).put("next", later)
                    .put("due", now() + config().transitionMillis() / 1000.0);
        }
    }

    /** Applies a status change that's come due, saving the record. */
    ObjectNode settle(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (meta.has("next") && now() >= meta.path("due").asDouble()) {
            record.put(meta.path("member").asText(), meta.path("next").asText());
            meta.remove("next");
            meta.remove("due");
            meta.remove("member");
            putForAccount(owner(record), region(record), meta.path("kind").asText(), id(record), record);
        }
        return record;
    }

    Optional<ObjectNode> find(String region, String kind, String id) {
        return get(region, kind, id).map(this::settle);
    }

    List<ObjectNode> all(String region, String kind) {
        List<ObjectNode> records = new ArrayList<>();
        list(region, kind).forEach(record -> records.add(settle(record)));
        return records;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String type, String id) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", id);
        extra.put("resourceType", type);
        return new AwsException("ResourceNotFoundException", "The " + type.replace('-', ' ') + " " + id
                + " doesn't exist.", 404, extra);
    }

    static AwsException conflict(String type, String id, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", id);
        extra.put("resourceType", type);
        return new AwsException("ConflictException", message, 409, extra);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    /** Adds tags given as {@code [{"key": ..., "value": ...}]} or as a map. */
    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || tags.isNull() || tags.isMissingNode()) {
            return;
        }
        ObjectNode map = tags(record);
        if (tags.isObject()) {
            tags.properties().forEach(tag -> map.put(checkKey(tag.getKey()), tag.getValue().asText("")));
        } else {
            for (JsonNode tag : tags) {
                map.put(checkKey(tag.path("key").asText("")), tag.path("value").asText(""));
            }
        }
        if (map.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
    }

    private static String checkKey(String key) {
        if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
            throw validation("Tag keys can't start with aws:.");
        }
        return key;
    }

    static ArrayNode tagList(ObjectNode record, ObjectMapper mapper) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).properties().forEach(tag -> list.addObject().put("key", tag.getKey())
                .put("value", tag.getValue().asText()));
        return list;
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name) {
        String token = in.path("nextToken").asText(null);
        int size = in.hasNonNull("maxResults") && in.get("maxResults").asInt() > 0 ? in.get("maxResults").asInt()
                : in.hasNonNull("limit") && in.get("limit").asInt() > 0 ? in.get("limit").asInt() : 100;
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
