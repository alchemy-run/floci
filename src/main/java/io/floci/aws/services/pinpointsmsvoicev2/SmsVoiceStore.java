package io.floci.aws.services.pinpointsmsvoicev2;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AWS End User Messaging SMS and Voice records in every account, keyed {@code <region>/<kind>/<id>}. A record's
 * {@code _} member holds what no response shows: its order, owning account, Region, tags and resource policy.
 */
@ApplicationScoped
public class SmsVoiceStore implements Resettable {

    static final String SERVICE = "sms-voice";
    static final String CONFIG_SET = "configuration-set";
    static final String OPT_OUT_LIST = "opt-out-list";
    static final String OPTED_OUT = "opted-out";
    static final String PHONE = "phone-number";
    static final String POOL = "pool";
    static final String SENDER_ID = "sender-id";
    static final String KEYWORD = "keyword";
    static final String MESSAGE = "message";
    static final String PROTECT = "protect-configuration";
    static final String OVERRIDE = "rule-set-override";
    static final String REGISTRATION = "registration";
    static final String VERSION = "registration-version";
    static final String FIELD = "registration-field";
    static final String ASSOCIATION = "registration-association";
    static final String ATTACHMENT = "registration-attachment";
    static final String VERIFIED = "verified-destination-number";
    static final String RCS = "rcs-agent";
    static final String NOTIFY = "notify-configuration";
    static final String ACCOUNT = "account";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public SmsVoiceStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("pinpointsmsvoicev2", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.PinpointSmsVoiceV2ServiceConfig config() {
        return config.services().pinpointSmsVoiceV2();
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
        record.put("CreatedTimestamp", now());
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

    static AwsException validation(String reason, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("Reason", reason);
        return new AwsException("ValidationException", message, 400, extra);
    }

    static AwsException notFound(String type, String id) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("ResourceType", type);
        extra.put("ResourceId", id);
        return new AwsException("ResourceNotFoundException", "The " + type.toLowerCase(Locale.ROOT)
                .replace('_', ' ') + " " + id + " doesn't exist.", 400, extra);
    }

    static AwsException conflict(String reason, String type, String id, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("Reason", reason);
        extra.put("ResourceType", type);
        extra.put("ResourceId", id);
        return new AwsException("ConflictException", message, 400, extra);
    }

    static AwsException quota(String reason, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("Reason", reason);
        return new AwsException("ServiceQuotaExceededException", message, 400, extra);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    /** Adds tags given as {@code [{"Key": ..., "Value": ...}]}. */
    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return;
        }
        ObjectNode map = tags(record);
        for (JsonNode tag : tags) {
            String key = tag.path("Key").asText("");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("INVALID_PARAMETER", "Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("Value").asText(""));
        }
        if (map.size() > 200) {
            throw quota("TAGS_PER_RESOURCE", "A resource can have at most 200 tags.");
        }
    }

    static ArrayNode tagList(ObjectNode record, ObjectMapper mapper) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).properties().forEach(tag -> list.addObject().put("Key", tag.getKey())
                .put("Value", tag.getValue().asText()));
        return list;
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name) {
        String token = in.path("NextToken").asText(null);
        int size = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : 100;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("INVALID_NEXT_TOKEN", "The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw validation("INVALID_NEXT_TOKEN", "The NextToken is not valid.");
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
