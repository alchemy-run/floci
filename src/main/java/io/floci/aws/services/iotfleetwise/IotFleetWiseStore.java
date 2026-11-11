package io.floci.aws.services.iotfleetwise;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * IoT FleetWise's resources in every account, keyed {@code <region>/<kind>/<name>}: the resource part of the ARN,
 * so a signal catalog is {@code signal-catalog/<name>}. Account-wide settings are {@code <region>/settings/<name>}.
 * A record's {@code _} member holds what no response shows.
 */
@ApplicationScoped
public class IotFleetWiseStore implements Resettable {

    static final String SERVICE = "iotfleetwise";
    static final String CATALOG = "signal-catalog";
    static final String MODEL = "model-manifest";
    static final String DECODER = "decoder-manifest";
    static final String FLEET = "fleet";
    static final String VEHICLE = "vehicle";
    static final String CAMPAIGN = "campaign";
    static final String STATE_TEMPLATE = "state-template";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public IotFleetWiseStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.IotFleetWiseServiceConfig config() {
        return config.services().iotfleetwise();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String key(String region, String kind, String name) {
        return region + "/" + kind + "/" + name;
    }

    Optional<ObjectNode> get(String account, String key) {
        return storage.getForAccount(account, key);
    }

    void put(String account, String key, ObjectNode record) {
        storage.putForAccount(account, key, record);
    }

    void delete(String account, String key) {
        storage.deleteForAccount(account, key);
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

    String arn(String account, String region, String kind, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, kind + "/" + name).toString();
    }

    /** A new record with its creation and modification times and its hidden members. */
    ObjectNode record(String kind, String name) {
        ObjectNode record = mapper.createObjectNode();
        Instant now = Instant.now();
        record.put("creationTime", seconds(now)).put("lastModificationTime", seconds(now));
        record.putObject("_").put("kind", kind).put("name", name).put("seq", System.nanoTime());
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }

    static void touch(ObjectNode record) {
        record.put("lastModificationTime", seconds(Instant.now()));
    }

    static double seconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    /** A record as a response shows it: without its hidden members. */
    static ObjectNode view(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_");
        return view;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", "other"));
    }

    static AwsException field(String name, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", "fieldValidationFailed");
        data.put("fieldList", List.of(Map.of("name", name, "message", message)));
        return new AwsException("ValidationException", message, 400, data);
    }

    static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + id + " doesn't exist.", 400,
                Map.of("resourceId", id, "resourceType", type));
    }

    static AwsException conflict(String type, String id, String message) {
        return new AwsException("ConflictException", message, 400, Map.of("resource", id, "resourceType", type));
    }

    static AwsException limit(String type, String id, String message) {
        return new AwsException("LimitExceededException", message, 400, Map.of("resourceId", id,
                "resourceType", type));
    }

    static AwsException invalidSignals(String message, List<Map<String, String>> signals) {
        return new AwsException("InvalidSignalsException", message, 400, Map.of("invalidSignals", signals));
    }

    static AwsException invalidNodes(String reason, List<JsonNode> nodes) {
        return new AwsException("InvalidNodeException", reason, 400, Map.of("reason", reason, "invalidNodes", nodes));
    }

    // ---------------------------------------------------------------- inputs

    /** The {@code Key}/{@code Value} tag list as a map, checked for size. */
    static Map<String, String> tags(JsonNode list) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : list) {
            String key = tag.path("Key").asText();
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
            tags.put(key, tag.path("Value").asText());
        }
        return tags;
    }

    static ArrayNode tagList(ObjectMapper mapper, JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.fields().forEachRemaining(tag -> list.addObject().put("Key", tag.getKey())
                .put("Value", tag.getValue().asText()));
        return list;
    }

    /** A timestamp given as epoch seconds or ISO 8601. */
    static Instant instant(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return Instant.ofEpochMilli((long) (value.asDouble() * 1000));
        }
        return Instant.parse(value.asText());
    }

    /** A page of items: {@code nextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, int defaultMax, ObjectNode out, String name) {
        String token = in.path("nextToken").asText(null);
        int max = in.path("maxResults").asInt(defaultMax);
        int start = 0;
        if (token != null) {
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
}
