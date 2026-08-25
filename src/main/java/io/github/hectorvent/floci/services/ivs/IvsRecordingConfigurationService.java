package io.github.hectorvent.floci.services.ivs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivs.model.Channel;
import io.github.hectorvent.floci.services.ivs.model.RecordingConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon IVS restJson1 recording configurations.
 *
 * <p>Create is an upsert-free mint: there is no update API. State is
 * {@code ACTIVE} immediately (the live API transitions {@code CREATING} in
 * seconds when the bucket is in-region). Delete of a configuration still
 * attached to a channel is {@code ConflictException}.
 */
@ApplicationScoped
public class IvsRecordingConfigurationService {

    static final String SERVICE = "ivs";
    static final String RECORDING_RESOURCE = "recording-configuration/";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_RECONNECT = 300;
    private static final String TOKEN_PREFIX = "ivs-rec:v1:";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final Set<String> THUMBNAIL_MODES = Set.of("DISABLED", "INTERVAL");
    private static final Set<String> THUMBNAIL_RESOLUTIONS = Set.of(
            "SD", "HD", "FULL_HD", "LOWEST_RESOLUTION");
    private static final Set<String> THUMBNAIL_STORAGE = Set.of("SEQUENTIAL", "LATEST");
    private static final Set<String> RENDITION_SELECTIONS = Set.of("ALL", "NONE", "CUSTOM");
    private static final Set<String> RENDITIONS = Set.of(
            "SD", "HD", "FULL_HD", "LOWEST_RESOLUTION");

    private final StorageBackend<String, RecordingConfiguration> store;
    private final StorageBackend<String, Channel> channels;
    private final RegionResolver regionResolver;

    @Inject
    public IvsRecordingConfigurationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("ivs", "ivs-recording-configurations.json",
                        new TypeReference<Map<String, RecordingConfiguration>>() {
                        }),
                storageFactory.create("ivs", "ivs-channels.json",
                        new TypeReference<Map<String, Channel>>() {
                        }),
                regionResolver);
    }

    IvsRecordingConfigurationService(
            StorageBackend<String, RecordingConfiguration> store,
            StorageBackend<String, Channel> channels,
            RegionResolver regionResolver) {
        this.store = store;
        this.channels = channels;
        this.regionResolver = regionResolver;
    }

    public boolean ownsArn(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            return resource != null && resource.startsWith(RECORDING_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public synchronized RecordingConfiguration create(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        String bucketName = requireBucketName(request);
        int reconnect = optionalInteger(request, "recordingReconnectWindowSeconds", 0);
        if (reconnect < 0 || reconnect > MAX_RECONNECT) {
            throw validation("recordingReconnectWindowSeconds must be between 0 and 300.");
        }
        Thumbnail thumb = readThumbnail(request.get("thumbnailConfiguration"));
        Rendition rendition = readRendition(request.get("renditionConfiguration"));
        Map<String, String> tags = readTags(request.get("tags"));

        String account = regionResolver.getAccountId();
        String id = newId();
        while (store.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        String arn = AwsArnUtils.Arn.of(SERVICE, region, account, RECORDING_RESOURCE + id).toString();

        RecordingConfiguration config = new RecordingConfiguration();
        config.setId(id);
        config.setArn(arn);
        config.setName(name);
        config.setState(STATE_ACTIVE);
        config.setBucketName(bucketName);
        config.setRecordingReconnectWindowSeconds(reconnect);
        config.setThumbnailRecordingMode(thumb.recordingMode);
        config.setThumbnailTargetIntervalSeconds(thumb.targetIntervalSeconds);
        config.setThumbnailResolution(thumb.resolution);
        config.setThumbnailStorage(thumb.storage);
        config.setRenditionSelection(rendition.renditionSelection);
        config.setRenditions(rendition.renditions);
        config.setTags(tags);
        store.put(storageKey(region, id), config);
        return config;
    }

    public RecordingConfiguration get(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireConfig(region, requireText(request, "arn"));
    }

    public synchronized void delete(String region, JsonNode request) {
        requireObject(request, "Request body");
        RecordingConfiguration config = requireConfig(region, requireText(request, "arn"));
        String arn = config.getArn();
        boolean attached = channels.scan(key -> true).stream()
                .anyMatch(channel -> arn.equals(channel.getRecordingConfigurationArn()));
        if (attached) {
            throw conflict("Resource: " + arn + " is associated with a channel");
        }
        store.delete(storageKey(resourceRegion(arn, region), config.getId()));
    }

    public Page list(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        List<RecordingConfiguration> configs = new ArrayList<>(
                store.scan(key -> key.startsWith(region + "::")));
        configs.sort(Comparator.comparing(RecordingConfiguration::getArn, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), configs.size());
        int end = Math.min(offset + maxResults, configs.size());
        String responseToken = end < configs.size() ? encodeOffset(end) : null;
        return new Page(configs.subList(offset, end), responseToken);
    }

    public Map<String, String> listTags(String region, String arn) {
        RecordingConfiguration config = requireConfig(region, arn);
        return config.getTags() == null ? Map.of() : Map.copyOf(config.getTags());
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        RecordingConfiguration config = requireConfig(region, arn);
        Map<String, String> current = config.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(config.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        config.setTags(current);
        store.put(storageKey(resourceRegion(config.getArn(), region), config.getId()), config);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        RecordingConfiguration config = requireConfig(region, arn);
        if (config.getTags() != null && tagKeys != null) {
            tagKeys.forEach(config.getTags()::remove);
        }
        store.put(storageKey(resourceRegion(config.getArn(), region), config.getId()), config);
    }

    private RecordingConfiguration requireConfig(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith(RECORDING_RESOURCE)) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring(RECORDING_RESOURCE.length());
        if (id.isBlank() || !ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        String configRegion = parsed.region() == null || parsed.region().isBlank()
                ? region
                : parsed.region();
        return store.get(storageKey(configRegion, id)).orElseThrow(() -> notFound(arn));
    }

    private static String requireBucketName(JsonNode request) {
        JsonNode dest = request.get("destinationConfiguration");
        if (dest == null || dest.isNull()) {
            throw validation("destinationConfiguration is required.");
        }
        requireObject(dest, "destinationConfiguration");
        JsonNode s3 = dest.get("s3");
        if (s3 == null || s3.isNull()) {
            throw validation("destinationConfiguration.s3 is required.");
        }
        requireObject(s3, "destinationConfiguration.s3");
        return requireText(s3, "bucketName");
    }

    private static Thumbnail readThumbnail(JsonNode node) {
        String mode = "INTERVAL";
        Integer interval = 60;
        String resolution = null;
        List<String> storage = List.of("SEQUENTIAL");
        if (node == null || node.isNull()) {
            return new Thumbnail(mode, interval, resolution, storage);
        }
        requireObject(node, "thumbnailConfiguration");
        mode = optionalEnum(node, "recordingMode", THUMBNAIL_MODES, "INTERVAL");
        Integer requested = optionalInteger(node, "targetIntervalSeconds");
        if (requested != null) {
            if (requested < 1 || requested > 60) {
                throw validation("targetIntervalSeconds must be between 1 and 60.");
            }
            interval = requested;
        }
        if ("DISABLED".equals(mode) && requested == null) {
            interval = null;
        }
        resolution = optionalText(node, "resolution");
        if (resolution != null && !THUMBNAIL_RESOLUTIONS.contains(resolution)) {
            throw validation("resolution must be one of " + THUMBNAIL_RESOLUTIONS + ".");
        }
        if (node.has("storage") && !node.get("storage").isNull()) {
            storage = readStringList(node, "storage");
            for (String item : storage) {
                if (!THUMBNAIL_STORAGE.contains(item)) {
                    throw validation("storage members must be one of " + THUMBNAIL_STORAGE + ".");
                }
            }
        }
        return new Thumbnail(mode, interval, resolution, storage);
    }

    private static Rendition readRendition(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Rendition(null, List.of());
        }
        requireObject(node, "renditionConfiguration");
        String selection = optionalEnum(node, "renditionSelection", RENDITION_SELECTIONS, null);
        List<String> renditions = List.of();
        if (node.has("renditions") && !node.get("renditions").isNull()) {
            renditions = readStringList(node, "renditions");
            for (String item : renditions) {
                if (!RENDITIONS.contains(item)) {
                    throw validation("renditions members must be one of " + RENDITIONS + ".");
                }
            }
        }
        return new Rendition(selection, renditions);
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void validateName(String name) {
        if (name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 0-128 letters, digits, hyphens, or underscores.");
        }
    }

    private static String resourceRegion(String arn, String fallback) {
        try {
            String region = AwsArnUtils.parse(arn).region();
            return region == null || region.isBlank() ? fallback : region;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("maxResults") || request.get("maxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("maxResults");
        if (!value.isNumber() && !value.isTextual()) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
        int parsed;
        try {
            parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and 100.");
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

    private static String optionalEnum(JsonNode parent, String field, Set<String> allowed, String defaultValue) {
        String value = optionalText(parent, field);
        if (value == null) {
            return defaultValue;
        }
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static Integer optionalInteger(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber() && !value.isTextual()) {
            throw validation(field + " must be an integer.");
        }
        try {
            return value.isNumber() ? value.intValue() : Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            throw validation(field + " must be an integer.");
        }
    }

    private static int optionalInteger(JsonNode parent, String field, int defaultValue) {
        Integer value = optionalInteger(parent, field);
        return value == null ? defaultValue : value;
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

    private static AwsException notFound(String arn) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource: " + arn + " not found",
                404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    public record Page(List<RecordingConfiguration> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record Thumbnail(
            String recordingMode,
            Integer targetIntervalSeconds,
            String resolution,
            List<String> storage) {
        private Thumbnail {
            storage = storage == null ? List.of() : List.copyOf(storage);
        }
    }

    private record Rendition(String renditionSelection, List<String> renditions) {
        private Rendition {
            renditions = renditions == null ? List.of() : List.copyOf(renditions);
        }
    }
}
