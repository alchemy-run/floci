package io.floci.aws.services.iotwireless;

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
 * IoT Wireless records in every account, keyed {@code <region>/<kind>/<id>}, where the kind is the resource type of
 * the record's ARN ({@code WirelessDevice}, {@code WirelessGateway}, {@code Destination} and the rest). A record's
 * {@code _} member holds what no response shows: its tags, client token and network state (session keys, frame
 * counters, the downlink queue, statistics).
 */
@ApplicationScoped
public class WirelessStore implements Resettable {

    static final String SERVICE = "iotwireless";
    static final String DESTINATION = "Destination";
    static final String DEVICE_PROFILE = "DeviceProfile";
    static final String SERVICE_PROFILE = "ServiceProfile";
    static final String DEVICE = "WirelessDevice";
    static final String GATEWAY = "WirelessGateway";
    static final String TASK_DEFINITION = "WirelessGatewayTaskDefinition";
    static final String PARTNER_ACCOUNT = "SidewalkAccount";
    static final String MULTICAST_GROUP = "MulticastGroup";
    static final String FUOTA_TASK = "FuotaTask";
    static final String NETWORK_ANALYZER = "NetworkAnalyzerConfiguration";
    static final String IMPORT_TASK = "ImportTask";
    static final String SETTINGS = "settings";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public WirelessStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.IotWirelessServiceConfig config() {
        return config.services().iotwireless();
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

    String arn(String account, String region, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, kind + "/" + id).toString();
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static String now() {
        return Instant.now().toString();
    }

    static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", type + " " + id + " not found.", 404,
                Map.of("ResourceId", id, "ResourceType", type));
    }

    static AwsException conflict(String message, String id, String type) {
        return new AwsException("ConflictException", message, 409, Map.of("ResourceId", id, "ResourceType", type));
    }

    // ---------------------------------------------------------------- inputs

    /** Tags given as a {@code [{Key, Value}]} list, as an object. */
    static ObjectNode tagsOf(ObjectMapper mapper, JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = tag.get("Key").asText();
                if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw validation("Tag keys can't start with aws:.");
                }
                map.put(key, tag.path("Value").asText(""));
            }
        }
        if (map.size() > 200) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 200 tags.", 400);
        }
        return map;
    }

    static ArrayNode tagList(ObjectMapper mapper, JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        if (tags != null) {
            tags.fields().forEachRemaining(e -> list.addObject().put("Key", e.getKey()).put("Value",
                    e.getValue().asText()));
        }
        return list;
    }

    /** An earlier create's response for the same client request token, or null for a new token. */
    ObjectNode repeated(String account, String region, String operation, JsonNode in) {
        String token = in.path("ClientRequestToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = storage.getForAccount(account, key(region, "client-token", operation + "/" + token))
                .orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientRequestToken");
        if (!earlier.path("request").equals(request)) {
            throw validation("The client request token " + token + " was used with different parameters.");
        }
        return (ObjectNode) earlier.path("response").deepCopy();
    }

    void remember(String account, String region, String operation, JsonNode in, ObjectNode response) {
        String token = in.path("ClientRequestToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientRequestToken");
        ObjectNode entry = mapper.createObjectNode();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        storage.putForAccount(account, key(region, "client-token", operation + "/" + token), entry);
    }

    /** A page of items: {@code NextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, JsonNode in, ObjectNode out, String name) {
        String token = in.path("NextToken").asText(null);
        int max = in.hasNonNull("MaxResults") && in.get("MaxResults").asInt() > 0 ? in.get("MaxResults").asInt()
                : 100;
        int start = 0;
        if (token != null && !token.isBlank()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
            if (start < 0 || start > items.size()) {
                throw validation("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + max);
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
