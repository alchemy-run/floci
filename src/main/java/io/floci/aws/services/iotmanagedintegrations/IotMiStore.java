package io.floci.aws.services.iotmanagedintegrations;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Managed integrations' records in every account, keyed {@code <region>/<kind>/<id>}, where the kind is the resource
 * type of the ARN ({@code managed-thing}, {@code credential-locker} and the rest) or, for records with no ARN,
 * the API's noun ({@code destination}, {@code notification-configuration}). A record's {@code _} member holds what
 * no response shows: its tags, client token and runtime state.
 */
@ApplicationScoped
public class IotMiStore implements Resettable {

    static final String SERVICE = "iotmanagedintegrations";
    static final String THING = "managed-thing";
    static final String LOCKER = "credential-locker";
    static final String PROFILE = "provisioning-profile";
    static final String OTA_TASK = "ota-task";
    static final String OTA_CONFIGURATION = "ota-task-configuration";
    static final String ASSOCIATION = "account-association";
    static final String CONNECTOR = "cloud-connector";
    static final String CONNECTOR_DESTINATION = "connector-destination";
    static final String DISCOVERY = "device-discovery";
    static final String DESTINATION = "destination";
    static final String NOTIFICATION = "notification-configuration";
    static final String EVENT_LOG = "event-log-configuration";
    static final String SETTINGS = "settings";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public IotMiStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.IotMiServiceConfig config() {
        return config.services().iotmanagedintegrations();
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

    ObjectNode require(String account, String region, String kind, String id, String type) {
        return get(account, region, kind, id).orElseThrow(() -> notFound(type, id));
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

    /** A new record with its hidden members. */
    ObjectNode record() {
        ObjectNode record = mapper.createObjectNode();
        record.putObject("_").put("seq", System.nanoTime());
        return record;
    }

    static ObjectNode meta(ObjectNode record) {
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

    /** A 32-character lowercase hexadecimal ID, as managed integrations' resources have. */
    static String id() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static double now() {
        return seconds(Instant.now());
    }

    static double seconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    // ---------------------------------------------------------------- errors

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + id + " doesn't exist.", 404,
                Map.of("ResourceId", id, "ResourceType", type));
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 402);
    }

    // ---------------------------------------------------------------- inputs

    static ObjectNode tagsOf(ObjectMapper mapper, JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags != null && tags.isObject()) {
            tags.fields().forEachRemaining(tag -> {
                if (tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")) {
                    throw validation("Tag keys can't start with aws:.");
                }
                map.put(tag.getKey(), tag.getValue().asText());
            });
        }
        return map;
    }

    /**
     * Repeats an earlier create that carried the same client token: returns that create's response, or null when the
     * token is new. The same token with different parameters is a ConflictException.
     */
    ObjectNode repeated(String account, String region, String operation, JsonNode in) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return null;
        }
        ObjectNode earlier = storage.getForAccount(account, key(region, "client-token", operation + "/" + token))
                .orElse(null);
        if (earlier == null) {
            return null;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        if (!earlier.path("request").equals(request)) {
            throw conflict("The client token " + token + " was used with different parameters.");
        }
        return (ObjectNode) earlier.path("response").deepCopy();
    }

    void remember(String account, String region, String operation, JsonNode in, ObjectNode response) {
        String token = in.path("ClientToken").asText(null);
        if (token == null) {
            return;
        }
        ObjectNode request = in.deepCopy();
        request.remove("ClientToken");
        ObjectNode entry = mapper.createObjectNode();
        entry.set("request", request);
        entry.set("response", response.deepCopy());
        storage.putForAccount(account, key(region, "client-token", operation + "/" + token), entry);
    }

    /** A page of items: {@code NextToken} is the base64 index of the first item not yet returned. */
    static void page(List<? extends JsonNode> items, String token, Integer max, ObjectNode out, String name) {
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
        int end = Math.min(items.size(), start + (max == null ? 100 : max));
        ArrayNode array = out.putArray(name);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
