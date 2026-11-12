package io.floci.aws.services.mediatailor;

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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MediaTailor records in every account, keyed {@code <region>/<kind>/<id>}: playback configurations, prefetch
 * schedules, functions, and channel assembly's source locations, VOD and live sources, channels and programs. A
 * record's {@code _} member holds what no response shows: its order, owning account, tags and policy.
 */
@ApplicationScoped
public class MediaTailorStore implements Resettable {

    static final String SERVICE = "mediatailor";
    static final String CONFIG = "playbackconfiguration";
    static final String PREFETCH = "prefetchschedule";
    static final String FUNCTION = "function";
    static final String LOCATION = "sourcelocation";
    static final String VOD = "vodsource";
    static final String LIVE = "livesource";
    static final String CHANNEL = "channel";
    static final String PROGRAM = "program";
    static final String ALERT = "alert";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public MediaTailorStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("mediatailor", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.MediaTailorServiceConfig config() {
        return config.services().mediaTailor();
    }

    /** The base URL Floci is reached at. */
    String endpoint() {
        return config.effectiveBaseUrl().replaceAll("/+$", "");
    }

    String account() {
        return storage.accountId();
    }

    // ---------------------------------------------------------------- URLs

    static final String DNS_SUFFIX = "localhost.floci.io";
    static final String PATH = "/_floci/mediatailor";
    static final String PLAYBACK = "playback";
    static final String ASSEMBLY = "channel-assembly";

    /** The id an account's playback and channel hosts have in a Region, as MediaTailor's are per account. */
    static String hostId(String account, String region) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("mediatailor/" + account + "/" + region)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether Floci is reached on localhost, so the wildcard DNS name of a host reaches it too. */
    private boolean wildcard() {
        URI base = URI.create(endpoint());
        return List.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost())
                && (base.getPath().isEmpty() || "/".equals(base.getPath()));
    }

    /**
     * The URL prefix of an account's playback ({@link #PLAYBACK}) or channel assembly ({@link #ASSEMBLY}) host:
     * {@code <id>.mediatailor.<region>....} or {@code <id>.channel-assembly.mediatailor.<region>....} under Floci's
     * wildcard DNS, or a path on Floci's endpoint when it isn't reached on localhost.
     */
    String hostPrefix(String kind, String account, String region) {
        String id = hostId(account, region);
        if (wildcard()) {
            URI base = URI.create(endpoint());
            String host = (PLAYBACK.equals(kind) ? id : id + "." + ASSEMBLY) + ".mediatailor." + region + "."
                    + DNS_SUFFIX;
            return base.getScheme() + "://" + host + (base.getPort() < 0 ? "" : ":" + base.getPort());
        }
        return endpoint() + PATH + "/" + kind + "/" + region + "/" + id;
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
        return storage.getForAccount(account, key(region, kind, id));
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
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, k -> k.startsWith(start)));
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

    static String iso(Instant instant) {
        return instant.toString();
    }

    String arn(String region, String resource) {
        return arnFor(account(), region, resource);
    }

    String arnFor(String account, String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }


    // ---------------------------------------------------------------- errors

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isObject()) {
            return;
        }
        ObjectNode map = tags(record);
        tags.properties().forEach(tag -> {
            if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw badRequest("Tag keys can't start with aws:.");
            }
            map.put(tag.getKey(), tag.getValue().asText(""));
        });
        if (map.size() > 50) {
            throw badRequest("A resource can have at most 50 tags.");
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
                throw badRequest("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw badRequest("The NextToken is not valid.");
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

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }
}
