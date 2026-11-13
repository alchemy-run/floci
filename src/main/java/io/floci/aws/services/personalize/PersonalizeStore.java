package io.floci.aws.services.personalize;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon Personalize records in every account, keyed {@code <region>/<kind>/<name>}: schemas, dataset groups, datasets,
 * jobs, event trackers, solutions and their versions, campaigns, recommenders, filters and metric attributions. Dataset
 * rows and trained models are kept apart, in their own file. A record's {@code _} member holds what no response shows:
 * its order, owning account and tags.
 */
@ApplicationScoped
public class PersonalizeStore implements Resettable {

    static final String SERVICE = "personalize";
    static final String SCHEMA = "schema";
    static final String GROUP = "datasetgroup";
    static final String DATASET = "dataset";
    static final String IMPORT = "importjob";
    static final String EXPORT = "exportjob";
    static final String DELETION = "deletionjob";
    static final String TRACKER = "tracker";
    static final String SOLUTION = "solution";
    static final String VERSION = "solutionversion";
    static final String CAMPAIGN = "campaign";
    static final String RECOMMENDER = "recommender";
    static final String FILTER = "filter";
    static final String BATCH = "batchinference";
    static final String SEGMENT = "batchsegment";
    static final String ATTRIBUTION = "metricattribution";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final AccountAwareStorageBackend<ObjectNode> data;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public PersonalizeStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create("personalize", "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.data = storageFactory.create("personalize", "data.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.PersonalizeServiceConfig config() {
        return config.services().personalize();
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
        data.clear();
    }

    /** A dataset's rows or a solution version's model. */
    Optional<ObjectNode> data(String account, String key) {
        return data.getForAccount(account, key).map(ObjectNode::deepCopy);
    }

    void putData(String account, String key, ObjectNode value) {
        data.putForAccount(account, key, value);
    }

    void deleteData(String account, String key) {
        data.deleteForAccount(account, key);
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

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    static String random(int length, String alphabet) {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    String arnFor(String account, String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    /** The ARN resource type of each kind of record. */
    static final Map<String, String> TYPES = Map.ofEntries(Map.entry(SCHEMA, "schema"),
            Map.entry(GROUP, "dataset-group"), Map.entry(DATASET, "dataset"),
            Map.entry(IMPORT, "dataset-import-job"), Map.entry(EXPORT, "dataset-export-job"),
            Map.entry(DELETION, "data-deletion-job"), Map.entry(TRACKER, "event-tracker"),
            Map.entry(SOLUTION, "solution"), Map.entry(VERSION, "solution"), Map.entry(CAMPAIGN, "campaign"),
            Map.entry(RECOMMENDER, "recommender"), Map.entry(FILTER, "filter"),
            Map.entry(BATCH, "batch-inference-job"), Map.entry(SEGMENT, "batch-segment-job"),
            Map.entry(ATTRIBUTION, "metric-attribution"));

    /** The kind of record an ARN names, or null when it names none. */
    static String kindOf(String arn) {
        if (!AwsArnUtils.isArnFor(arn, SERVICE)) {
            return null;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        int slash = resource.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String type = resource.substring(0, slash);
        if (type.equals("solution")) {
            return resource.indexOf('/', slash + 1) > 0 ? VERSION : SOLUTION;
        }
        for (Map.Entry<String, String> entry : TYPES.entrySet()) {
            if (entry.getValue().equals(type) && !entry.getKey().equals(VERSION)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** A record by its ARN, after any status change that's come due; fails when it isn't of the kind or is gone. */
    ObjectNode require(String arn, String kind, String label) {
        if (arn == null || !kind.equals(kindOf(arn))) {
            throw invalid("The ARN " + arn + " isn't a " + label + " ARN.");
        }
        return find(arn).orElseThrow(() -> notFound("The " + label + " " + arn + " doesn't exist."));
    }

    /** A record by its ARN, in the caller's account. */
    Optional<ObjectNode> find(String arn) {
        String kind = kindOf(arn);
        if (kind == null) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.accountId().equals(account())) {
            return Optional.empty();
        }
        String id = parsed.resource().substring(parsed.resource().indexOf('/') + 1);
        return get(parsed.region(), kind, id).map(record -> settle(parsed.region(), kind, record));
    }

    /** The region and id a record is kept under. */
    static String region(ObjectNode record) {
        return meta(record).path("region").asText();
    }

    static String id(ObjectNode record) {
        return meta(record).path("id").asText();
    }

    /** Sets a status that becomes another once the transition time has passed. */
    void transition(ObjectNode record, String now, String later) {
        double at = now();
        record.put("status", now).put("lastUpdatedDateTime", at);
        if (later == null) {
            meta(record).remove("next");
        } else {
            meta(record).put("next", later).put("due", at + config().transitionMillis() / 1000.0);
        }
    }

    /** Applies a status change that's come due, saving the record. */
    ObjectNode settle(String region, String kind, ObjectNode record) {
        ObjectNode meta = meta(record);
        if (meta.has("next") && now() >= meta.path("due").asDouble()) {
            record.put("status", meta.path("next").asText()).put("lastUpdatedDateTime", meta.path("due").asDouble());
            meta.remove("next");
            meta.remove("due");
            putForAccount(meta.path("account").asText(account()), region, kind, id(record), record);
        }
        return record;
    }

    /** Every record of a kind in a Region, with status changes applied. */
    List<ObjectNode> all(String region, String kind) {
        List<ObjectNode> records = new ArrayList<>();
        for (ObjectNode record : list(region, kind)) {
            records.add(settle(region, kind, record));
        }
        return records;
    }

    // ---------------------------------------------------------------- errors

    static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    static AwsException exists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }

    static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 400);
    }

    static AwsException limit(String message) {
        return new AwsException("LimitExceededException", message, 400);
    }

    // ---------------------------------------------------------------- tags

    static ObjectNode tags(ObjectNode record) {
        ObjectNode meta = meta(record);
        if (!meta.has("tags")) {
            meta.putObject("tags");
        }
        return (ObjectNode) meta.get("tags");
    }

    /** Adds tags given as {@code [{"tagKey": ..., "tagValue": ...}]}. */
    static void addTags(ObjectNode record, JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return;
        }
        ObjectNode map = tags(record);
        for (JsonNode tag : tags) {
            String key = tag.path("tagKey").asText("");
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw invalid("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("tagValue").asText(""));
        }
        if (map.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400);
        }
    }

    /** A record's tags as {@code [{"tagKey": ..., "tagValue": ...}]}. */
    static ArrayNode tagList(ObjectNode record, ObjectMapper mapper) {
        ArrayNode list = mapper.createArrayNode();
        tags(record).properties().forEach(tag -> list.addObject().put("tagKey", tag.getKey())
                .put("tagValue", tag.getValue().asText()));
        return list;
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
                throw new AwsException("InvalidNextTokenException", "The nextToken is not valid.", 400);
            }
            if (start < 0 || start > items.size()) {
                throw new AwsException("InvalidNextTokenException", "The nextToken is not valid.", 400);
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
