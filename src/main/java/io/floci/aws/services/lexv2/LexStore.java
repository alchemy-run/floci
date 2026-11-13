package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerReachableUrls;
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
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Lex V2 records in every account, keyed {@code <region>/<kind>/<path>}: bots, their versions and aliases, the
 * locales, intents, slots and slot types of each version, sessions, conversation turns, exports, imports, test sets and
 * the rest. A record's {@code _} member holds what no response shows: its tags, its order, the time a transient
 * status settles, and a built locale's model.
 */
@ApplicationScoped
public class LexStore implements Resettable {

    static final String SERVICE = "lex";
    static final String DRAFT = "DRAFT";
    static final String TEST_ALIAS = "TSTALIASID";
    static final String TEST_ALIAS_NAME = "TestBotAlias";
    static final String FALLBACK_ID = "FALLBCKINT";
    static final String FALLBACK = "FallbackIntent";
    static final String FALLBACK_SIGNATURE = "AMAZON.FallbackIntent";

    static final String BOT = "bot";
    static final String VERSION = "version";
    static final String ALIAS = "alias";
    static final String LOCALE = "locale";
    static final String INTENT = "intent";
    static final String SLOT = "slot";
    static final String SLOT_TYPE = "slottype";
    static final String VOCABULARY = "vocabulary";
    static final String REPLICA = "replica";
    static final String POLICY = "policy";
    static final String EXPORT = "export";
    static final String IMPORT = "import";
    static final String UPLOAD = "upload";
    static final String SESSION = "session";
    static final String TURN = "turn";
    static final String TEST_SET = "testset";
    static final String TEST_SET_GENERATION = "testsetgeneration";
    static final String TEST_EXECUTION = "testexecution";
    static final String DISCREPANCY = "discrepancy";
    static final String RECOMMENDATION = "recommendation";
    static final String GENERATION = "generation";
    static final String ANALYZER = "analyzer";
    static final String FILE = "file";
    static final String FILE_REGION = "files";
    static final String FILES_PATH = "/_floci/lexv2/files/";

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public LexStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("lexv2", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.LexV2ServiceConfig config() {
        return config.services().lexv2();
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
    }

    static String key(String region, String kind, String path) {
        return region + "/" + kind + "/" + path;
    }

    Optional<ObjectNode> get(String region, String kind, String path) {
        return storage.getForAccount(account(), key(region, kind, path));
    }

    Optional<ObjectNode> getForAccount(String account, String region, String kind, String path) {
        return storage.getForAccount(account, key(region, kind, path));
    }

    /** A record that exists now: due status transitions are applied, and a deleted record past its time is gone. */
    Optional<ObjectNode> live(String region, String kind, String path) {
        Optional<ObjectNode> found = get(region, kind, path);
        if (found.isEmpty()) {
            return found;
        }
        ObjectNode record = found.get();
        if (gone(record)) {
            delete(region, kind, path);
            return Optional.empty();
        }
        if (advance(record)) {
            put(region, kind, path, record);
        }
        return found;
    }

    /** The live records of a kind whose path starts with {@code prefix}, oldest first. */
    List<ObjectNode> liveList(String region, String kind, String prefix) {
        List<ObjectNode> live = new ArrayList<>();
        for (ObjectNode record : list(region, kind, prefix)) {
            String path = record.path("_").path("path").asText();
            live(region, kind, path).ifPresent(live::add);
        }
        return live;
    }

    void put(String region, String kind, String path, ObjectNode record) {
        meta(record).put("path", path);
        storage.putForAccount(account(), key(region, kind, path), record);
    }

    void putForAccount(String account, String region, String kind, String path, ObjectNode record) {
        meta(record).put("path", path);
        storage.putForAccount(account, key(region, kind, path), record);
    }

    void delete(String region, String kind, String path) {
        storage.deleteForAccount(account(), key(region, kind, path));
    }

    /** The records of a kind whose path starts with {@code prefix}, oldest first. */
    List<ObjectNode> list(String region, String kind, String prefix) {
        String start = key(region, kind, prefix);
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account(), key -> key.startsWith(start)));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    /** Deletes every record of a kind whose path starts with {@code prefix}. */
    void deleteAll(String region, String kind, String prefix) {
        String start = key(region, kind, prefix);
        for (ObjectNode record : storage.scanForAccount(account(), k -> k.startsWith(start))) {
            storage.deleteForAccount(account(), key(region, kind, record.path("_").path("path").asText()));
        }
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(Predicate<String> key) {
        return storage.scanAllAccountEntries(key);
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

    /** A ten-character identifier of upper-case letters and digits, as Lex assigns to bots, intents and the rest. */
    static String id() {
        StringBuilder id = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            id.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static long millis() {
        return System.currentTimeMillis();
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account(), resource).toString();
    }

    String botArn(String region, String botId) {
        return arn(region, "bot/" + botId);
    }

    String aliasArn(String region, String botId, String aliasId) {
        return arn(region, "bot-alias/" + botId + "/" + aliasId);
    }

    String testSetArn(String region, String testSetId) {
        return arn(region, "test-set/" + testSetId);
    }

    // ---------------------------------------------------------------- files

    /** The base URL Floci is reached at, named so Lambda containers reach it too. */
    String endpoint() {
        String base = config.effectiveBaseUrl().replaceAll("/+$", "");
        return base.replaceFirst("(?i)^((?:https?)://)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?=[:/]|$)",
                "$1" + ContainerReachableUrls.FLOCI_DNS_HOST);
    }

    /** Keeps a file for download and returns its URL. */
    String putFile(byte[] content, String contentType, String name) {
        String token = uuid();
        ObjectNode file = record();
        file.put("content", Base64.getEncoder().encodeToString(content));
        file.put("contentType", contentType);
        file.put("name", name);
        put(FILE_REGION, FILE, token, file);
        return endpoint() + FILES_PATH + token;
    }

    /** Reserves a URL a file can be uploaded to, for the import {@code importId}. */
    String reserveUpload(String importId) {
        ObjectNode file = record();
        file.put("upload", true);
        put(FILE_REGION, FILE, importId, file);
        return endpoint() + FILES_PATH + importId;
    }

    /** A kept file in any account, by its token. */
    Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> file(String token) {
        String suffix = "/" + FILE + "/" + token;
        return everywhere(key -> key.endsWith(FILE_REGION + suffix)).stream().findFirst();
    }

    void putFileForAccount(String account, String token, ObjectNode file) {
        putForAccount(account, FILE_REGION, FILE, token, file);
    }

    /** The bytes uploaded for an import, or empty when nothing was uploaded. */
    Optional<byte[]> uploaded(String importId) {
        return get(FILE_REGION, FILE, importId).filter(f -> f.hasNonNull("content"))
                .map(f -> Base64.getDecoder().decode(f.get("content").asText()));
    }

    // ---------------------------------------------------------------- transient statuses

    /** Marks a record as settling into {@code settled} once the configured transition time has passed. */
    void settle(ObjectNode record, String field, String transientStatus, String settled) {
        record.put(field, transientStatus);
        meta(record).put("settleAt", millis() + config().transitionMillis()).put("settleField", field)
                .put("settled", settled);
    }

    /** Applies a due status transition; true when the record changed. */
    static boolean advance(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("settleAt") || meta.get("settleAt").asLong() > millis()) {
            return false;
        }
        record.put(meta.get("settleField").asText(), meta.get("settled").asText());
        meta.remove(List.of("settleAt", "settleField", "settled"));
        return true;
    }

    /** Whether a record marked for deletion is now gone. */
    static boolean gone(ObjectNode record) {
        JsonNode at = record.path("_").path("deleteAt");
        return at.isNumber() && at.asLong() <= millis();
    }

    void markDeleted(ObjectNode record, String field, String status) {
        record.put(field, status);
        meta(record).put("deleteAt", millis() + config().transitionMillis());
        meta(record).remove(List.of("settleAt", "settleField", "settled"));
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException precondition(String message) {
        return new AwsException("PreconditionFailedException", message, 412);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    static void checkTags(JsonNode tags) {
        if (tags == null || !tags.isObject()) {
            return;
        }
        if (tags.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        tags.fieldNames().forEachRemaining(key -> {
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
        });
    }

    // ---------------------------------------------------------------- lists

    /** A page of items: {@code nextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultMax) {
        String token = in.path("nextToken").asText(null);
        int max = in.hasNonNull("maxResults") && in.get("maxResults").asInt() > 0 ? in.get("maxResults").asInt()
                : defaultMax;
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
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Whether {@code value} passes a list operation's {@code filters} ({@code EQ}, {@code CO}, {@code NE}). */
    static boolean filtered(JsonNode filters, Map<String, String> fields) {
        for (JsonNode filter : filters) {
            String value = fields.get(filter.path("name").asText());
            if (value == null) {
                value = "";
            }
            String operator = filter.path("operator").asText("EQ");
            boolean any = false;
            for (JsonNode wanted : filter.path("values")) {
                String w = wanted.asText();
                boolean match = switch (operator) {
                    case "CO" -> value.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT));
                    case "NE" -> !value.equals(w);
                    default -> value.equals(w);
                };
                any |= match;
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Sorts summaries by a {@code sortBy} attribute mapped onto a member, ascending unless asked otherwise. */
    static void sort(List<ObjectNode> items, JsonNode sortBy, Map<String, String> members, String fallback) {
        String member = sortBy.isObject() ? members.getOrDefault(sortBy.path("attribute").asText(), fallback)
                : fallback;
        Comparator<ObjectNode> order = Comparator.comparing(item -> {
            JsonNode v = item.path(member);
            return v.isNumber() ? String.format(Locale.ROOT, "%020.6f", v.asDouble()) : v.asText("");
        });
        if (sortBy.isObject() && "Descending".equals(sortBy.path("order").asText())) {
            order = order.reversed();
        }
        items.sort(order);
    }

    // ---------------------------------------------------------------- names

    static void checkName(String what, String name) {
        if (name == null || !name.matches("^([0-9a-zA-Z][_-]?){1,100}$")) {
            throw validation("The " + what + " name " + name + " is not valid. Names contain letters, numbers, "
                    + "hyphens and underscores, and begin with a letter or number.");
        }
    }
}
