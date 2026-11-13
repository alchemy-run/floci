package io.floci.aws.services.internetmonitor;

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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Internet Monitor's records in every account, keyed {@code <region>/monitor/<name>},
 * {@code <region>/health-event/<monitor>/<event id>} and {@code <region>/window/<monitor>/<epoch second>}: a monitor,
 * one of its health events, and the measurements of one of its windows.
 */
@ApplicationScoped
public class InternetMonitorStore implements Resettable {

    static final String SERVICE = "internetmonitor";

    private final AccountAwareStorageBackend<ObjectNode> storage;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public InternetMonitorStore(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.storage = storageFactory.create(SERVICE, "resources.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    EmulatorConfig.InternetMonitorServiceConfig config() {
        return config.services().internetmonitor();
    }

    String account() {
        return storage.accountId();
    }

    @Override
    public void clear() {
        storage.clear();
    }

    static String monitorKey(String region, String name) {
        return region + "/monitor/" + name;
    }

    static String eventKey(String region, String monitor, String eventId) {
        return region + "/health-event/" + monitor + "/" + eventId;
    }

    static String windowKey(String region, String monitor, long epochSecond) {
        return region + "/window/" + monitor + "/" + epochSecond;
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

    /** The records under a key prefix, ordered by key. */
    List<ObjectNode> list(String account, String prefix) {
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> found =
                new ArrayList<>(storage.scanAllAccountEntries(key -> key.startsWith(prefix)));
        found.removeIf(entry -> !entry.accountId().equals(account));
        found.sort(Comparator.comparing(AccountAwareStorageBackend.AccountEntry::key));
        List<ObjectNode> values = new ArrayList<>();
        found.forEach(entry -> values.add(entry.value()));
        return values;
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> everywhere(Predicate<String> key) {
        return storage.scanAllAccountEntries(key);
    }

    static String monitorArn(String account, String region, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "monitor/" + name).toString();
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

    // ---------------------------------------------------------------- inputs

    /** A timestamp given as ISO 8601 or epoch seconds. */
    static Instant instant(JsonNode value, String name) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            double seconds = value.asDouble();
            return Instant.ofEpochMilli((long) (seconds * 1000));
        }
        String text = value.asText();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return Instant.ofEpochMilli((long) (Double.parseDouble(text) * 1000));
            } catch (NumberFormatException ignored) {
                throw validation(name + " is not a valid timestamp: " + text);
            }
        }
    }

    /** A page of items: {@code NextToken} is the base64 index of the first item not yet returned. */
    static List<JsonNode> page(List<? extends JsonNode> items, String token, int maxResults, ObjectNode output,
                               String name) {
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
        int end = Math.min(items.size(), start + maxResults);
        ArrayNode array = output.putArray(name);
        List<JsonNode> page = new ArrayList<>(items.subList(start, end));
        page.forEach(array::add);
        if (end < items.size()) {
            output.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return page;
    }
}
