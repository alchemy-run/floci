package io.github.hectorvent.floci.services.ivsrealtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivsrealtime.model.ParticipantToken;
import io.github.hectorvent.floci.services.ivsrealtime.model.Stage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
import java.util.regex.Pattern;

/**
 * Amazon IVS Real-Time restJson1 — stage lifecycle and participant/composition
 * data-plane operations used by Alchemy {@code Stage.test.ts} and
 * {@code Bindings.test.ts}.
 *
 * <p>ARNs use service {@code ivs} (same as low-latency IVS). Tag APIs share
 * {@code /tags/{arn}} via {@code IvsService} which delegates here for stage
 * and composition ARNs.
 */
@ApplicationScoped
public class IvsRealtimeService {

    static final String SERVICE = "ivs";
    static final String STAGE_RESOURCE = "stage/";
    static final String COMPOSITION_RESOURCE = "composition/";
    static final String STORAGE_RESOURCE = "storage-configuration/";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final int DEFAULT_DURATION_MINUTES = 720;
    private static final int MIN_DURATION_MINUTES = 1;
    private static final int MAX_DURATION_MINUTES = 20160;
    private static final int MAX_RECONNECT = 300;
    private static final String TOKEN_PREFIX = "ivsrt:v1:";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final Set<String> CAPABILITIES = Set.of("PUBLISH", "SUBSCRIBE");
    private static final Set<String> MEDIA_TYPES = Set.of("AUDIO_VIDEO", "AUDIO_ONLY", "NONE");

    private final StorageBackend<String, Stage> stages;
    private final RegionResolver regionResolver;

    @Inject
    public IvsRealtimeService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("ivs", "ivs-stages.json",
                        new TypeReference<Map<String, Stage>>() {
                        }),
                regionResolver);
    }

    IvsRealtimeService(StorageBackend<String, Stage> stages, RegionResolver regionResolver) {
        this.stages = stages;
        this.regionResolver = regionResolver;
    }

    public boolean ownsArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service()) || parsed.resource() == null) {
                return false;
            }
            String resource = parsed.resource();
            return resource.startsWith(STAGE_RESOURCE)
                    || resource.startsWith(COMPOSITION_RESOURCE)
                    || resource.startsWith(STORAGE_RESOURCE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public synchronized Stage createStage(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name != null) {
            validateName(name);
        }
        Recording recording = readRecording(request.get("autoParticipantRecordingConfiguration"), false);
        Map<String, String> tags = readTags(request.get("tags"));

        String account = regionResolver.getAccountId();
        String id = newId();
        while (stages.get(storageKey(region, id)).isPresent()) {
            id = newId();
        }
        Stage stage = new Stage();
        stage.setId(id);
        stage.setArn(AwsArnUtils.Arn.of(SERVICE, region, account, STAGE_RESOURCE + id).toString());
        stage.setName(name);
        applyRecording(stage, recording);
        stage.setTags(tags);
        stages.put(storageKey(region, id), stage);
        return stage;
    }

    public Stage getStage(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireStage(region, requireText(request, "arn"));
    }

    public synchronized Stage updateStage(String region, JsonNode request) {
        requireObject(request, "Request body");
        Stage stage = requireStage(region, requireText(request, "arn"));
        if (request.has("name") && !request.get("name").isNull()) {
            String name = requireText(request, "name");
            validateName(name);
            stage.setName(name);
        }
        if (request.has("autoParticipantRecordingConfiguration")) {
            applyRecording(stage, readRecording(request.get("autoParticipantRecordingConfiguration"), true));
        }
        stages.put(storageKey(stageRegion(stage, region), stage.getId()), stage);
        return stage;
    }

    public synchronized void deleteStage(String region, JsonNode request) {
        requireObject(request, "Request body");
        Stage stage = requireStage(region, requireText(request, "arn"));
        stages.delete(storageKey(stageRegion(stage, region), stage.getId()));
    }

    public Page listStages(String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = parseMaxResults(request);
        List<Stage> items = new ArrayList<>(stages.scan(key -> key.startsWith(region + "::")));
        items.sort(Comparator.comparing(Stage::getArn, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(optionalText(request, "nextToken"), items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page(items.subList(offset, end), responseToken);
    }

    public ParticipantToken createParticipantToken(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        int duration = optionalInt(request, "duration", DEFAULT_DURATION_MINUTES,
                MIN_DURATION_MINUTES, MAX_DURATION_MINUTES);
        String userId = optionalText(request, "userId");
        Map<String, String> attributes = readTags(request.get("attributes"));
        List<String> capabilities = readCapabilities(request);

        ParticipantToken token = new ParticipantToken();
        token.setParticipantId(newId());
        token.setToken(newToken());
        token.setUserId(userId);
        token.setAttributes(attributes);
        token.setDuration(duration);
        token.setCapabilities(capabilities);
        token.setExpirationTime(Instant.now().plus(duration, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MILLIS)
                .toString());
        return token;
    }

    public Page listStageSessions(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        parseMaxResults(request);
        return new Page(List.of(), null);
    }

    public void getStageSession(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        throw notFound(requireText(request, "sessionId"));
    }

    public Page listParticipants(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        requireText(request, "sessionId");
        parseMaxResults(request);
        return new Page(List.of(), null);
    }

    public void getParticipant(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        requireText(request, "sessionId");
        throw notFound(requireText(request, "participantId"));
    }

    public Page listParticipantEvents(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        requireText(request, "sessionId");
        requireText(request, "participantId");
        parseMaxResults(request);
        return new Page(List.of(), null);
    }

    public Page listParticipantReplicas(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "sourceStageArn"));
        requireText(request, "participantId");
        parseMaxResults(request);
        return new Page(List.of(), null);
    }

    public void disconnectParticipant(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        throw notFound(requireText(request, "participantId"));
    }

    public void startParticipantReplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "sourceStageArn"));
        requireStage(region, requireText(request, "destinationStageArn"));
        throw notFound(requireText(request, "participantId"));
    }

    public void stopParticipantReplication(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "sourceStageArn"));
        requireStage(region, requireText(request, "destinationStageArn"));
        throw notFound(requireText(request, "participantId"));
    }

    public Page listCompositions(String region, JsonNode request) {
        requireObject(request, "Request body");
        parseMaxResults(request);
        return new Page(List.of(), null);
    }

    public void getComposition(String region, JsonNode request) {
        requireObject(request, "Request body");
        throw notFound(requireText(request, "arn"));
    }

    public void stopComposition(String region, JsonNode request) {
        requireObject(request, "Request body");
        throw notFound(requireText(request, "arn"));
    }

    public void startComposition(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireStage(region, requireText(request, "stageArn"));
        JsonNode destinations = request.get("destinations");
        if (destinations == null || !destinations.isArray() || destinations.isEmpty()) {
            throw validation("destinations must be a non-empty array.");
        }
        for (JsonNode destination : destinations) {
            if (destination == null || !destination.isObject()) {
                throw validation("destinations must be objects.");
            }
            JsonNode s3 = destination.get("s3");
            if (s3 != null && !s3.isNull()) {
                requireObject(s3, "s3");
                throw notFound(requireText(s3, "storageConfigurationArn"));
            }
            JsonNode channel = destination.get("channel");
            if (channel != null && !channel.isNull()) {
                requireObject(channel, "channel");
                throw notFound(requireText(channel, "channelArn"));
            }
        }
        throw validation("destinations must include an s3 or channel configuration.");
    }

    public Map<String, String> listTags(String region, String arn) {
        Stage stage = requireStage(region, arn);
        return stage.getTags() == null ? Map.of() : Map.copyOf(stage.getTags());
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Stage stage = requireStage(region, arn);
        Map<String, String> current = stage.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(stage.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        stage.setTags(current);
        stages.put(storageKey(stageRegion(stage, region), stage.getId()), stage);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Stage stage = requireStage(region, arn);
        if (stage.getTags() != null && tagKeys != null) {
            tagKeys.forEach(stage.getTags()::remove);
        }
        stages.put(storageKey(stageRegion(stage, region), stage.getId()), stage);
    }

    private Stage requireStage(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("arn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith(STAGE_RESOURCE)) {
            throw notFound(arn);
        }
        String id = parsed.resource().substring(STAGE_RESOURCE.length());
        if (id.isBlank() || !ID_PATTERN.matcher(id).matches()) {
            throw notFound(arn);
        }
        String stageRegion = parsed.region() == null || parsed.region().isBlank()
                ? region
                : parsed.region();
        return stages.get(storageKey(stageRegion, id)).orElseThrow(() -> notFound(arn));
    }

    private static Recording readRecording(JsonNode node, boolean allowClear) {
        if (node == null || node.isNull()) {
            return allowClear ? new Recording(null, List.of(), null, null) : null;
        }
        requireObject(node, "autoParticipantRecordingConfiguration");
        String storageArn = requireText(node, "storageConfigurationArn");
        List<String> mediaTypes = readStringList(node, "mediaTypes");
        for (String mediaType : mediaTypes) {
            if (!MEDIA_TYPES.contains(mediaType)) {
                throw validation("mediaTypes must be one of " + MEDIA_TYPES + ".");
            }
        }
        Integer reconnect = optionalInteger(node, "recordingReconnectWindowSeconds");
        if (reconnect != null && (reconnect < 0 || reconnect > MAX_RECONNECT)) {
            throw validation("recordingReconnectWindowSeconds must be between 0 and 300.");
        }
        Boolean replicas = optionalBoolean(node, "recordParticipantReplicas");
        return new Recording(storageArn, mediaTypes, reconnect, replicas);
    }

    private static void applyRecording(Stage stage, Recording recording) {
        if (recording == null) {
            return;
        }
        stage.setRecordingStorageConfigurationArn(recording.storageConfigurationArn());
        stage.setRecordingMediaTypes(recording.mediaTypes());
        stage.setRecordingReconnectWindowSeconds(recording.recordingReconnectWindowSeconds());
        stage.setRecordParticipantReplicas(recording.recordParticipantReplicas());
    }

    private static List<String> readCapabilities(JsonNode request) {
        List<String> capabilities = readStringList(request, "capabilities");
        if (capabilities.isEmpty()) {
            return List.of("PUBLISH", "SUBSCRIBE");
        }
        for (String capability : capabilities) {
            if (!CAPABILITIES.contains(capability)) {
                throw validation("capabilities must be one of " + CAPABILITIES + ".");
            }
        }
        return capabilities;
    }

    private static String stageRegion(Stage stage, String fallback) {
        try {
            String region = AwsArnUtils.parse(stage.getArn()).region();
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

    private static String newToken() {
        byte[] bytes = (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void validateName(String name) {
        if (name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must be 0-128 letters, digits, hyphens, or underscores.");
        }
    }

    private static int parseMaxResults(JsonNode request) {
        Integer parsed = optionalInteger(request, "maxResults");
        if (parsed == null) {
            return DEFAULT_MAX_RESULTS;
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

    private static int optionalInt(JsonNode parent, String field, int defaultValue, int min, int max) {
        Integer parsed = optionalInteger(parent, field);
        if (parsed == null) {
            return defaultValue;
        }
        if (parsed < min || parsed > max) {
            throw validation(field + " must be between " + min + " and " + max + ".");
        }
        return parsed;
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
            return value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw validation(field + " must be an integer.");
        }
    }

    private static Boolean optionalBoolean(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
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

    public record Page(List<?> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record Recording(
            String storageConfigurationArn,
            List<String> mediaTypes,
            Integer recordingReconnectWindowSeconds,
            Boolean recordParticipantReplicas) {
        private Recording {
            mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        }
    }
}
