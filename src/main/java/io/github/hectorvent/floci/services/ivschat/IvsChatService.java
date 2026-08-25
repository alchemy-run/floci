package io.github.hectorvent.floci.services.ivschat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivschat.model.LoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.Room;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Amazon IVS Chat restJson1 — rooms, logging configurations, and chat data-plane
 * operations used by Alchemy {@code Room.test.ts} / {@code Bindings.test.ts}.
 *
 * <p>Tag APIs share {@code /tags/{arn}} via {@link TagHandler} using ARN service
 * {@code ivschat}.
 */
@ApplicationScoped
public class IvsChatService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(IvsChatService.class);

    static final String SERVICE = "ivschat";
    static final String CHAT_TOKEN_PREFIX = "ivschat.";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 50;
    private static final int DEFAULT_RATE = 10;
    private static final int DEFAULT_LENGTH = 500;
    private static final int DEFAULT_SESSION_MINUTES = 60;
    private static final int TOKEN_DURATION_MINUTES = 60;
    private static final String TOKEN_PREFIX = "ivschat:v1:";
    private static final String ROOM_RESOURCE = "room/";
    private static final String LOGGING_RESOURCE = "logging-configuration/";
    private static final String DEFAULT_FALLBACK = "ALLOW";
    private static final Set<String> FALLBACK_RESULTS = Set.of("ALLOW", "DENY");
    private static final Set<String> TOKEN_CAPABILITIES = Set.of(
            "SEND_MESSAGE", "DELETE_MESSAGE", "DISCONNECT_USER");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");

    private final StorageBackend<String, Room> store;
    private final StorageBackend<String, LoggingConfiguration> loggingStore;
    private final RegionResolver regionResolver;
    private final Instance<LambdaService> lambdaService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatSession>> sessions =
            new ConcurrentHashMap<>();

    @Inject
    public IvsChatService(StorageFactory storageFactory, RegionResolver regionResolver,
                          Instance<LambdaService> lambdaService, ObjectMapper objectMapper) {
        this(storageFactory.create("ivschat", "ivschat-rooms.json",
                        new TypeReference<Map<String, Room>>() {
                        }),
                storageFactory.create("ivschat", "ivschat-logging-configurations.json",
                        new TypeReference<Map<String, LoggingConfiguration>>() {
                        }),
                regionResolver, lambdaService, objectMapper);
    }

    IvsChatService(StorageBackend<String, Room> store, RegionResolver regionResolver) {
        this(store, null, regionResolver, null, new ObjectMapper());
    }

    IvsChatService(
            StorageBackend<String, Room> store,
            StorageBackend<String, LoggingConfiguration> loggingStore,
            RegionResolver regionResolver) {
        this(store, loggingStore, regionResolver, null, new ObjectMapper());
    }

    IvsChatService(
            StorageBackend<String, Room> store,
            StorageBackend<String, LoggingConfiguration> loggingStore,
            RegionResolver regionResolver,
            Instance<LambdaService> lambdaService,
            ObjectMapper objectMapper) {
        this.store = store;
        this.loggingStore = loggingStore;
        this.regionResolver = regionResolver;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public synchronized Room createRoom(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        int rate = optionalInt(request, "maximumMessageRatePerSecond", DEFAULT_RATE, 1, 10);
        int length = optionalInt(request, "maximumMessageLength", DEFAULT_LENGTH, 1, 500);
        Handler handler = readHandler(request.get("messageReviewHandler"), true);
        List<String> loggingIds = readStringList(request, "loggingConfigurationIdentifiers");
        Map<String, String> tags = readTags(request.get("tags"));

        String account = regionResolver.getAccountId();
        String id = newId();
        while (store.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        String now = now();
        Room room = new Room();
        room.setId(id);
        room.setArn(AwsArnUtils.Arn.of(SERVICE, region, account, ROOM_RESOURCE + id).toString());
        room.setName(name);
        room.setMaximumMessageRatePerSecond(rate);
        room.setMaximumMessageLength(length);
        applyHandler(room, handler);
        room.setLoggingConfigurationIdentifiers(loggingIds);
        room.setCreateTime(now);
        room.setUpdateTime(now);
        room.setTags(tags);
        store.put(storageKey(region, id), room);
        return room;
    }

    public Room getRoom(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireRoom(region, requireText(request, "identifier"));
    }

    public synchronized Room updateRoom(String region, JsonNode request) {
        requireObject(request, "Request body");
        Room room = requireRoom(region, requireText(request, "identifier"));
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            room.setName(name);
        }
        if (request.has("maximumMessageRatePerSecond")
                && !request.get("maximumMessageRatePerSecond").isNull()) {
            room.setMaximumMessageRatePerSecond(
                    optionalInt(request, "maximumMessageRatePerSecond",
                            room.getMaximumMessageRatePerSecond(), 1, 10));
        }
        if (request.has("maximumMessageLength") && !request.get("maximumMessageLength").isNull()) {
            room.setMaximumMessageLength(
                    optionalInt(request, "maximumMessageLength",
                            room.getMaximumMessageLength(), 1, 500));
        }
        if (request.has("messageReviewHandler")) {
            applyHandler(room, readHandler(request.get("messageReviewHandler"), false));
        }
        if (request.has("loggingConfigurationIdentifiers")) {
            room.setLoggingConfigurationIdentifiers(
                    readStringList(request, "loggingConfigurationIdentifiers"));
        }
        room.setUpdateTime(now());
        store.put(storageKey(roomRegion(room, region), room.getId()), room);
        return room;
    }

    public synchronized void deleteRoom(String region, JsonNode request) {
        requireObject(request, "Request body");
        Room room = requireRoom(region, requireText(request, "identifier"));
        store.delete(storageKey(roomRegion(room, region), room.getId()));
    }

    public Page listRooms(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        String filterByName = optionalText(request, "name");
        String filterByHandler = optionalText(request, "messageReviewHandlerUri");
        String filterByLogging = optionalText(request, "loggingConfigurationIdentifier");

        List<Room> rooms = new ArrayList<>(store.scan(key -> key.startsWith(region + "::")));
        rooms.sort(Comparator
                .comparing(Room::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Room::getArn, Comparator.nullsLast(String::compareTo)));
        if (filterByName != null) {
            rooms = rooms.stream().filter(room -> filterByName.equals(room.getName())).toList();
        }
        if (filterByHandler != null) {
            rooms = rooms.stream()
                    .filter(room -> filterByHandler.equals(room.getMessageReviewHandlerUri()))
                    .toList();
        }
        if (filterByLogging != null) {
            rooms = rooms.stream()
                    .filter(room -> room.getLoggingConfigurationIdentifiers() != null
                            && room.getLoggingConfigurationIdentifiers().contains(filterByLogging))
                    .toList();
        }

        int offset = decodeOffset(optionalText(request, "nextToken"), rooms.size());
        int end = Math.min(offset + maxResults, rooms.size());
        String responseToken = end < rooms.size() ? encodeOffset(end) : null;
        return new Page(rooms.subList(offset, end), responseToken);
    }

    public synchronized LoggingConfiguration createLoggingConfiguration(String region, JsonNode request) {
        requireLoggingStore();
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        Destination destination = readDestination(request.get("destinationConfiguration"));
        Map<String, String> tags = readTags(request.get("tags"));
        String account = regionResolver.getAccountId();
        String id = newId();
        while (loggingStore.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        String now = now();
        LoggingConfiguration config = new LoggingConfiguration();
        config.setId(id);
        config.setArn(AwsArnUtils.Arn.of(SERVICE, region, account, LOGGING_RESOURCE + id).toString());
        config.setName(name == null ? id : name);
        config.setState("ACTIVE");
        applyDestination(config, destination);
        config.setCreateTime(now);
        config.setUpdateTime(now);
        config.setTags(tags);
        loggingStore.put(storageKey(region, id), config);
        return config;
    }

    public LoggingConfiguration getLoggingConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireLogging(region, requireText(request, "identifier"));
    }

    public synchronized LoggingConfiguration updateLoggingConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        LoggingConfiguration config = requireLogging(region, requireText(request, "identifier"));
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            config.setName(name);
        }
        if (request.has("destinationConfiguration") && !request.get("destinationConfiguration").isNull()) {
            applyDestination(config, readDestination(request.get("destinationConfiguration")));
        }
        config.setState("ACTIVE");
        config.setUpdateTime(now());
        loggingStore.put(storageKey(resourceRegion(config.getArn(), region), config.getId()), config);
        return config;
    }

    public synchronized void deleteLoggingConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        LoggingConfiguration config = requireLogging(region, requireText(request, "identifier"));
        String arn = config.getArn();
        boolean attached = store.scan(key -> true).stream()
                .anyMatch(room -> room.getLoggingConfigurationIdentifiers() != null
                        && room.getLoggingConfigurationIdentifiers().contains(arn));
        if (attached) {
            throw conflict(arn, "LOGGING_CONFIGURATION",
                    "Resource: " + arn + " is associated with a room");
        }
        loggingStore.delete(storageKey(resourceRegion(arn, region), config.getId()));
    }

    public LoggingPage listLoggingConfigurations(String region, JsonNode request) {
        requireLoggingStore();
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        List<LoggingConfiguration> configs = new ArrayList<>(
                loggingStore.scan(key -> key.startsWith(region + "::")));
        configs.sort(Comparator.comparing(LoggingConfiguration::getArn, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), configs.size());
        int end = Math.min(offset + maxResults, configs.size());
        String responseToken = end < configs.size() ? encodeOffset(end) : null;
        return new LoggingPage(configs.subList(offset, end), responseToken);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (isLoggingArn(arn)) {
            LoggingConfiguration config = requireLogging(region, arn);
            return config.getTags() == null ? Map.of() : Map.copyOf(config.getTags());
        }
        Room room = requireRoom(region, arn);
        return room.getTags() == null ? Map.of() : Map.copyOf(room.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        if (isLoggingArn(arn)) {
            LoggingConfiguration config = requireLogging(region, arn);
            Map<String, String> current = config.getTags() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(config.getTags());
            if (tags != null) {
                current.putAll(tags);
            }
            config.setTags(current);
            loggingStore.put(storageKey(resourceRegion(config.getArn(), region), config.getId()), config);
            return;
        }
        Room room = requireRoom(region, arn);
        Map<String, String> current = room.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(room.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        room.setTags(current);
        store.put(storageKey(roomRegion(room, region), room.getId()), room);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        if (isLoggingArn(arn)) {
            LoggingConfiguration config = requireLogging(region, arn);
            if (config.getTags() != null && tagKeys != null) {
                tagKeys.forEach(config.getTags()::remove);
            }
            loggingStore.put(storageKey(resourceRegion(config.getArn(), region), config.getId()), config);
            return;
        }
        Room room = requireRoom(region, arn);
        if (room.getTags() != null && tagKeys != null) {
            tagKeys.forEach(room.getTags()::remove);
        }
        store.put(storageKey(roomRegion(room, region), room.getId()), room);
    }

    private Room requireRoom(String region, String identifier) {
        if (identifier.startsWith("arn:")) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(identifier);
            } catch (IllegalArgumentException e) {
                throw validation("identifier is invalid.");
            }
            if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                    || !parsed.resource().startsWith(ROOM_RESOURCE)) {
                throw notFound(identifier);
            }
            String id = parsed.resource().substring(ROOM_RESOURCE.length());
            if (id.isBlank() || !ROOM_ID_PATTERN.matcher(id).matches()) {
                throw notFound(identifier);
            }
            String roomRegion = parsed.region() == null || parsed.region().isBlank()
                    ? region
                    : parsed.region();
            return store.get(storageKey(roomRegion, id)).orElseThrow(() -> notFound(identifier));
        }
        if (!ROOM_ID_PATTERN.matcher(identifier).matches()) {
            throw validation("identifier is invalid.");
        }
        return store.get(storageKey(region, identifier)).orElseThrow(() -> notFound(identifier));
    }

    private LoggingConfiguration requireLogging(String region, String identifier) {
        requireLoggingStore();
        if (identifier.startsWith("arn:")) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(identifier);
            } catch (IllegalArgumentException e) {
                throw validation("identifier is invalid.");
            }
            if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                    || !parsed.resource().startsWith(LOGGING_RESOURCE)) {
                throw notFound(identifier, "LOGGING_CONFIGURATION");
            }
            String id = parsed.resource().substring(LOGGING_RESOURCE.length());
            if (id.isBlank() || !ROOM_ID_PATTERN.matcher(id).matches()) {
                throw notFound(identifier, "LOGGING_CONFIGURATION");
            }
            String configRegion = parsed.region() == null || parsed.region().isBlank()
                    ? region
                    : parsed.region();
            return loggingStore.get(storageKey(configRegion, id))
                    .orElseThrow(() -> notFound(identifier, "LOGGING_CONFIGURATION"));
        }
        if (!ROOM_ID_PATTERN.matcher(identifier).matches()) {
            throw validation("identifier is invalid.");
        }
        return loggingStore.get(storageKey(region, identifier))
                .orElseThrow(() -> notFound(identifier, "LOGGING_CONFIGURATION"));
    }

    private void requireLoggingStore() {
        if (loggingStore == null) {
            throw new AwsException("InternalServerException", "Logging configuration store is unavailable.", 500);
        }
    }

    private static boolean isLoggingArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            return resource != null && resource.startsWith(LOGGING_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Destination readDestination(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw validation("destinationConfiguration must be an object.");
        }
        JsonNode s3 = node.get("s3");
        JsonNode logs = node.get("cloudWatchLogs");
        JsonNode firehose = node.get("firehose");
        int present = 0;
        Destination destination = new Destination(null, null, null);
        if (s3 != null && !s3.isNull()) {
            requireObject(s3, "s3");
            destination = new Destination(requireText(s3, "bucketName"), null, null);
            present++;
        }
        if (logs != null && !logs.isNull()) {
            requireObject(logs, "cloudWatchLogs");
            destination = new Destination(null, requireText(logs, "logGroupName"), null);
            present++;
        }
        if (firehose != null && !firehose.isNull()) {
            requireObject(firehose, "firehose");
            destination = new Destination(null, null, requireText(firehose, "deliveryStreamName"));
            present++;
        }
        if (present != 1) {
            throw validation("destinationConfiguration must specify exactly one destination.");
        }
        return destination;
    }

    private static void applyDestination(LoggingConfiguration config, Destination destination) {
        config.setS3BucketName(destination.bucketName());
        config.setCloudWatchLogsLogGroupName(destination.logGroupName());
        config.setFirehoseDeliveryStreamName(destination.deliveryStreamName());
    }

    private static void applyHandler(Room room, Handler handler) {
        if (handler == null || handler.uri == null || handler.uri.isBlank()) {
            room.setMessageReviewHandlerUri(null);
            room.setMessageReviewHandlerFallbackResult(null);
            return;
        }
        room.setMessageReviewHandlerUri(handler.uri);
        room.setMessageReviewHandlerFallbackResult(
                handler.fallbackResult == null ? DEFAULT_FALLBACK : handler.fallbackResult);
    }

    private static Handler readHandler(JsonNode node, boolean create) {
        if (node == null || node.isNull()) {
            return create ? null : new Handler(null, null);
        }
        if (!node.isObject()) {
            throw validation("messageReviewHandler must be an object.");
        }
        String uri = optionalText(node, "uri");
        String fallback = optionalText(node, "fallbackResult");
        if (fallback != null && !FALLBACK_RESULTS.contains(fallback)) {
            throw validation("fallbackResult must be one of " + FALLBACK_RESULTS + ".");
        }
        return new Handler(uri, fallback);
    }

    private static String roomRegion(Room room, String fallback) {
        return resourceRegion(room.getArn(), fallback);
    }

    private static String resourceRegion(String arn, String fallback) {
        try {
            String region = AwsArnUtils.parse(arn).region();
            return region == null || region.isBlank() ? fallback : region;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static void validateName(String name) {
        if (name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 0-128 letters, digits, hyphens, or underscores.");
        }
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("maxResults") || request.get("maxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        return optionalInt(request, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
    }

    private static int optionalInt(JsonNode parent, String field, int defaultValue, int min, int max) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber() && !value.isTextual()) {
            throw validation(field + " must be an integer between " + min + " and " + max + ".");
        }
        int parsed;
        try {
            parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation(field + " must be an integer between " + min + " and " + max + ".");
        }
        if (parsed < min || parsed > max) {
            throw validation(field + " must be between " + min + " and " + max + ".");
        }
        return parsed;
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isNull()) {
                return;
            }
            if (!valueNode.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return new ArrayList<>();
        }
        JsonNode value = parent.get(field);
        if (!value.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw validation(field + " must be an array of strings.");
            }
            items.add(item.textValue());
        }
        return items;
    }

    static AwsException notFound(String identifier) {
        return notFound(identifier, "ROOM");
    }

    static AwsException notFound(String identifier, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource: " + identifier + " not found",
                404,
                Map.of("resourceId", identifier, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        return new AwsException(
                "ConflictException",
                message,
                409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private record Handler(String uri, String fallbackResult) {
    }

    private record Destination(String bucketName, String logGroupName, String deliveryStreamName) {
    }

    public record Page(List<Room> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record LoggingPage(List<LoggingConfiguration> items, String nextToken) {
        public LoggingPage {
            items = List.copyOf(items);
        }
    }
}
