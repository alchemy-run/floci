package io.floci.aws.services.neptunegraph;

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
 * Neptune Analytics records in every account, keyed {@code <region>/<kind>/<id>}: graphs, snapshots, private graph
 * endpoints, and import and export tasks. Snapshots' graph data is kept apart, in its own file, as it's large. A
 * record's {@code _} member holds what no response shows: its order, owning account and tags.
 */
@ApplicationScoped
public class NeptuneGraphStore implements Resettable {

    static final String SERVICE = "neptune-graph";
    static final String GRAPH = "graph";
    static final String SNAPSHOT = "snapshot";
    static final String ENDPOINT = "endpoint";
    static final String IMPORT = "importtask";
    static final String EXPORT = "exporttask";
    static final String CLIENT_TOKEN = "clienttoken";
    /** The DNS suffix graph endpoints are named under: Floci's wildcard localhost domain. */
    static final String DNS_SUFFIX = "localhost.floci.io";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<String> frames;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public NeptuneGraphStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("neptunegraph", "resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.frames = storageFactory.create("neptunegraph", "snapshots.json", new TypeReference<Map<String, String>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.NeptuneGraphServiceConfig config() {
        return config.services().neptuneGraph();
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
        frames.clear();
    }

    // ---------------------------------------------------------------- snapshot data

    void putData(String account, String region, String snapshot, String data) {
        frames.putForAccount(account, region + "/" + snapshot, data);
    }

    Optional<String> data(String account, String region, String snapshot) {
        return frames.getForAccount(account, region + "/" + snapshot);
    }

    void deleteData(String account, String region, String snapshot) {
        frames.deleteForAccount(account, region + "/" + snapshot);
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

    static AwsException validation(String reason, String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", reason));
    }

    static AwsException validation(String message) {
        return validation("BAD_REQUEST", message);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409, Map.of("reason", "CONCURRENT_MODIFICATION"));
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    static AwsException unprocessable(String reason, String message) {
        return new AwsException("UnprocessableException", message, 422, Map.of("reason", reason));
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
                throw validation("Tag keys can't start with aws:.");
            }
            map.put(tag.getKey(), tag.getValue().asText(""));
        });
        if (map.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
    }

    // ---------------------------------------------------------------- client tokens

    ObjectNode repeated(String region, String operation, JsonNode in) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = get(region, CLIENT_TOKEN, operation + "/" + token).orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        if (!earlier.path("request").equals(request)) {
            throw conflict("The client token " + token + " was used with different parameters.");
        }
        return earlier.path("response").deepCopy();
    }

    void remember(String region, String operation, JsonNode in, ObjectNode response) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        ObjectNode entry = record();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        put(region, CLIENT_TOKEN, operation + "/" + token, entry);
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

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }
}
