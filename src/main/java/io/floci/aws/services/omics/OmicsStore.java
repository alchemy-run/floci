package io.floci.aws.services.omics;

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
 * HealthOmics records in every account, keyed {@code <region>/<kind>/<id>}: stores, references, read sets, jobs,
 * workflows, runs and the rest. The bytes of references and read sets are kept apart, base64-encoded, in their own
 * file. A record's {@code _} member holds what no response shows: its order, owning account and tags.
 */
@ApplicationScoped
public class OmicsStore implements Resettable {

    static final String SERVICE = "omics";
    static final String REFERENCE_STORE = "referencestore";
    static final String REFERENCE = "reference";
    static final String REFERENCE_IMPORT = "referenceimport";
    static final String SEQUENCE_STORE = "sequencestore";
    static final String READ_SET = "readset";
    static final String READ_SET_IMPORT = "readsetimport";
    static final String READ_SET_EXPORT = "readsetexport";
    static final String ACTIVATION = "activation";
    static final String UPLOAD = "upload";
    static final String ACCESS_POLICY = "accesspolicy";
    static final String ANNOTATION_STORE = "annotationstore";
    static final String ANNOTATION_VERSION = "annotationversion";
    static final String VARIANT_STORE = "variantstore";
    static final String ANNOTATION_IMPORT = "annotationimport";
    static final String VARIANT_IMPORT = "variantimport";
    static final String SHARE = "share";
    static final String WORKFLOW = "workflow";
    static final String WORKFLOW_VERSION = "workflowversion";
    static final String RUN_GROUP = "rungroup";
    static final String RUN_CACHE = "runcache";
    static final String RUN = "run";
    static final String BATCH = "batch";
    static final String CONFIGURATION = "configuration";
    static final String TOKEN = "token";
    /** The key the tags of every resource, by ARN, are kept under. */
    static final String GLOBAL_TAGS = "global";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<String> blobs;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public OmicsStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("omics", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.blobs = storageFactory.create("omics", "data.json", new TypeReference<Map<String, String>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.OmicsServiceConfig config() {
        return config.services().omics();
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
        blobs.clear();
    }

    // ---------------------------------------------------------------- file bytes

    void putData(String account, String key, byte[] data) {
        blobs.putForAccount(account, key, Base64.getEncoder().encodeToString(data));
    }

    Optional<byte[]> data(String account, String key) {
        return blobs.getForAccount(account, key).map(Base64.getDecoder()::decode);
    }

    void deleteData(String account, String key) {
        blobs.deleteForAccount(account, key);
    }

    /** A 10-digit id, as stores, references, read sets and jobs have. */
    static String numericId() {
        return random(10, "0123456789").replaceFirst("^0", "1");
    }

    static String timestamp() {
        return Instant.now().toString();
    }

    /**
     * The earlier answer to a request that repeats a client token, or null. A token reused with other parameters is a
     * ConflictException.
     */
    ObjectNode repeated(String region, String operation, String token, JsonNode request) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ObjectNode earlier = get(region, TOKEN, operation + "/" + token).orElse(null);
        if (earlier == null) {
            return null;
        }
        if (!earlier.path("request").equals(request)) {
            throw conflict("The token " + token + " was used with different parameters.");
        }
        return earlier.path("response").deepCopy();
    }

    void remember(String region, String operation, String token, JsonNode request, ObjectNode response) {
        if (token == null || token.isBlank()) {
            return;
        }
        ObjectNode entry = record();
        entry.set("request", request.deepCopy());
        entry.set("response", response.deepCopy());
        put(region, TOKEN, operation + "/" + token, entry);
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
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    static AwsException range(String message) {
        return new AwsException("RangeNotSatisfiableException", message, 416);
    }

    static AwsException notSupported(String message) {
        return new AwsException("NotSupportedOperationException", message, 405);
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
            throw quota("A resource can have at most 50 tags.");
        }
    }

    // ---------------------------------------------------------------- lists

    /** A page of items by {@code NextToken} and {@code MaxResults}. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name, int defaultSize) {
        String token = in.path(in.has("startingToken") ? "startingToken" : "nextToken").asText(null);
        int size = in.path("maxResults").asInt(in.path("maxItems").asInt(0)) > 0
                ? in.path("maxResults").asInt(in.path("maxItems").asInt(0)) : defaultSize;
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
