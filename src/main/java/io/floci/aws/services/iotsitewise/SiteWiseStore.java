package io.floci.aws.services.iotsitewise;

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
 * IoT SiteWise's records in every account, keyed {@code <region>/<kind>/<id>}, where the kind is the resource type of
 * the record's ARN ({@code asset-model}, {@code asset}, {@code gateway}, {@code portal} and the rest). A record's
 * {@code _} member holds what no response shows: its tags, client token and runtime state. Time series values live
 * apart, one record per time series, in {@code values.json}.
 */
@ApplicationScoped
public class SiteWiseStore implements Resettable {

    static final String SERVICE = "iotsitewise";
    static final String ASSET_MODEL = "asset-model";
    static final String ASSET = "asset";
    static final String GATEWAY = "gateway";
    static final String PORTAL = "portal";
    static final String PROJECT = "project";
    static final String DASHBOARD = "dashboard";
    static final String ACCESS_POLICY = "access-policy";
    static final String TIME_SERIES = "time-series";
    static final String JOB = "bulk-import-job";
    static final String COMPUTATION_MODEL = "computation-model";
    static final String DATASET = "dataset";
    static final String ACTION = "action";
    static final String EXECUTION = "execution";
    static final String WORKSPACE = "workspace";
    static final String SETTINGS = "settings";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<ObjectNode> values;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public SiteWiseStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.values = storageFactory.create(SERVICE, "values.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.IotSiteWiseServiceConfig config() {
        return config.services().iotsitewise();
    }

    EmulatorConfig emulator() {
        return config;
    }

    String baseUrl() {
        return config.effectiveBaseUrl();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
        values.clear();
    }

    static String key(String region, String kind, String id) {
        return region + "/" + kind + "/" + id;
    }

    Optional<ObjectNode> get(String account, String region, String kind, String id) {
        return storage.getForAccount(account, key(region, kind, id));
    }

    void put(String account, String region, String kind, String id, ObjectNode record) {
        storage.putForAccount(account, key(region, kind, id), record);
    }

    void delete(String account, String region, String kind, String id) {
        storage.deleteForAccount(account, key(region, kind, id));
    }

    /** The records of a kind, oldest first. */
    List<ObjectNode> list(String account, String region, String kind) {
        String prefix = region + "/" + kind + "/";
        List<ObjectNode> found = new ArrayList<>(storage.scanForAccount(account, key -> key.startsWith(prefix)));
        found.sort(Comparator.comparingLong(record -> record.path("_").path("seq").asLong()));
        return found;
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(Predicate<String> key) {
        return storage.scanAllAccountEntries(key);
    }

    // ---------------------------------------------------------------- values

    /** A time series' points, {@code {points: [{t, n, q, v}]}} sorted by time, or a new empty record. */
    ObjectNode points(String account, String region, String timeSeriesId) {
        return values.getForAccount(account, region + "/" + timeSeriesId).orElseGet(() -> {
            ObjectNode record = mapper.createObjectNode();
            record.putArray("points");
            return record;
        });
    }

    void putPoints(String account, String region, String timeSeriesId, ObjectNode record) {
        values.putForAccount(account, region + "/" + timeSeriesId, record);
    }

    void deletePoints(String account, String region, String timeSeriesId) {
        values.deleteForAccount(account, region + "/" + timeSeriesId);
    }

    // ---------------------------------------------------------------- records

    /** A new record with its hidden members. */
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

    /** A record as a response shows it. */
    static ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    String arn(String account, String region, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, kind + "/" + id).toString();
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static double now() {
        return seconds(Instant.now());
    }

    static double seconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflicting(String message, String id, String arn) {
        return new AwsException("ConflictingOperationException", message, 409,
                Map.of("resourceId", id, "resourceArn", arn));
    }

    static AwsException exists(String message, String id, String arn) {
        return new AwsException("ResourceAlreadyExistsException", message, 409,
                Map.of("resourceId", id, "resourceArn", arn));
    }

    static AwsException limit(String message) {
        return new AwsException("LimitExceededException", message, 410);
    }

    static AwsException precondition(String message, String id, String arn) {
        return new AwsException("PreconditionFailedException", message, 412,
                Map.of("resourceId", id, "resourceArn", arn));
    }

    // ---------------------------------------------------------------- inputs

    static ObjectNode tagsOf(ObjectMapper mapper, JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags != null && tags.isObject()) {
            if (tags.size() > 50) {
                throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400);
            }
            tags.fields().forEachRemaining(tag -> {
                if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw invalid("Tag keys can't start with aws:.");
                }
                map.put(tag.getKey(), tag.getValue().asText());
            });
        }
        return map;
    }

    /**
     * Repeats an earlier create that carried the same client token: returns that create's response, or null when the
     * token is new. The same token with different parameters is an InvalidRequestException.
     */
    ObjectNode repeated(String account, String region, String operation, JsonNode in) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = storage.getForAccount(account, key(region, "client-token", operation + "/" + token))
                .orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        if (!earlier.path("request").equals(request)) {
            throw invalid("The client token " + token + " was used with different parameters.");
        }
        return (ObjectNode) earlier.path("response").deepCopy();
    }

    void remember(String account, String region, String operation, JsonNode in, ObjectNode response) {
        String token = in.path("clientToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("clientToken");
        ObjectNode entry = mapper.createObjectNode();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        storage.putForAccount(account, key(region, "client-token", operation + "/" + token), entry);
    }

    /** A page of items: {@code nextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, int defaultMax, ObjectNode out, String name) {
        String token = in.path("nextToken").asText(null);
        int max = in.hasNonNull("maxResults") ? in.get("maxResults").asInt() : defaultMax;
        int start = start(token, items.size());
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", token(end));
        }
    }

    static int start(String token, int size) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        int start;
        try {
            start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw invalid("The nextToken is not valid.");
        }
        if (start < 0 || start > size) {
            throw invalid("The nextToken is not valid.");
        }
        return start;
    }

    static String token(int index) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(index).getBytes(StandardCharsets.UTF_8));
    }
}
