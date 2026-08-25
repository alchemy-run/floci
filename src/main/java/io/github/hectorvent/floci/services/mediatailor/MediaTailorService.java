package io.github.hectorvent.floci.services.mediatailor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mediatailor.model.PlaybackConfiguration;
import io.github.hectorvent.floci.services.mediatailor.model.PrefetchSchedule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Elemental MediaTailor restJson1 — playback configurations, prefetch
 * schedules, channel-assembly reads, and tags.
 *
 * <p>{@code PutPlaybackConfiguration} is a full-replace upsert: omitted optional
 * fields are cleared. Logs are a separate {@code ConfigureLogsForPlaybackConfiguration}
 * call. Tag APIs share {@code /tags/{arn}} via {@link TagHandler}. Requests are signed
 * as {@code mediatailor}.
 */
@ApplicationScoped
public class MediaTailorService implements TagHandler {

    static final String SERVICE = "mediatailor";
    private static final String DEFAULT_INSERTION_MODE = "STITCHED_ONLY";
    private static final String DEFAULT_LOGGING_STRATEGY = "LEGACY_CLOUDWATCH";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Set<String> CHANNEL_ASSEMBLY_PREFIXES = Set.of(
            "channel/", "sourceLocation/", "vodSource/", "liveSource/", "program/");
    private static final TypeReference<Map<String, PlaybackConfiguration>> CONFIG_MAP = new ConfigMap();
    private static final TypeReference<Map<String, PrefetchSchedule>> PREFETCH_MAP = new PrefetchMap();

    private static final class ConfigMap extends TypeReference<Map<String, PlaybackConfiguration>> {
    }

    private static final class PrefetchMap extends TypeReference<Map<String, PrefetchSchedule>> {
    }

    private final StorageBackend<String, PlaybackConfiguration> configurations;
    private final StorageBackend<String, PrefetchSchedule> prefetchSchedules;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaTailorService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("mediatailor", "mediatailor-playback-configurations.json", CONFIG_MAP),
                storageFactory.create("mediatailor", "mediatailor-prefetch-schedules.json", PREFETCH_MAP),
                regionResolver, objectMapper);
    }

    MediaTailorService(
            StorageBackend<String, PlaybackConfiguration> configurations,
            StorageBackend<String, PrefetchSchedule> prefetchSchedules,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.configurations = configurations;
        this.prefetchSchedules = prefetchSchedules;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized PlaybackConfiguration putPlaybackConfiguration(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireName(request, "Name");
        String adsUrl = requireText(request, "AdDecisionServerUrl");
        String videoUrl = requireText(request, "VideoContentSourceUrl");
        PlaybackConfiguration config = lookup(region, name);
        if (config == null) {
            config = new PlaybackConfiguration();
            config.setName(name);
            config.setRegion(region);
            config.setArn(arn(region, "playbackConfiguration/" + name));
            config.setPlaybackEndpointPrefix(
                    "https://" + regionResolver.getAccountId() + ".mediatailor." + region + ".amazonaws.com");
            config.setPercentEnabled(0);
            config.setEnabledLoggingStrategies(List.of(DEFAULT_LOGGING_STRATEGY));
        }
        config.setAdDecisionServerUrl(adsUrl);
        config.setVideoContentSourceUrl(videoUrl);
        config.setSlateAdUrl(optionalText(request, "SlateAdUrl"));
        config.setTranscodeProfileName(optionalText(request, "TranscodeProfileName"));
        String insertionMode = optionalText(request, "InsertionMode");
        config.setInsertionMode(insertionMode == null ? DEFAULT_INSERTION_MODE : insertionMode);
        config.setPersonalizationThresholdSeconds(optionalInt(request, "PersonalizationThresholdSeconds"));
        config.setAvailSuppression(copyNode(node(request, "AvailSuppression")));
        config.setBumper(copyNode(node(request, "Bumper")));
        config.setCdnConfiguration(copyNode(node(request, "CdnConfiguration")));
        config.setDashConfiguration(copyNode(node(request, "DashConfiguration")));
        config.setLivePreRollConfiguration(copyNode(node(request, "LivePreRollConfiguration")));
        config.setManifestProcessingRules(copyNode(node(request, "ManifestProcessingRules")));
        config.setConfigurationAliases(copyNode(node(request, "ConfigurationAliases")));
        config.setAdConditioningConfiguration(copyNode(node(request, "AdConditioningConfiguration")));
        config.setAdDecisionServerConfiguration(copyNode(node(request, "AdDecisionServerConfiguration")));
        config.setFunctionMapping(copyNode(node(request, "FunctionMapping")));
        if (node(request, "tags", "Tags") != null) {
            config.setTags(readTags(request));
        }
        configurations.put(storageKey(region, name), config);
        return config;
    }

    public PlaybackConfiguration getPlaybackConfiguration(String region, String name) {
        return requireConfig(region, name);
    }

    public synchronized void deletePlaybackConfiguration(String region, String name) {
        PlaybackConfiguration config = lookup(region, name);
        if (config == null) {
            return;
        }
        configurations.delete(storageKey(region, config.getName()));
        String prefix = region + "::" + name + "::";
        for (String key : Set.copyOf(prefetchSchedules.keys())) {
            if (key.startsWith(prefix)) {
                prefetchSchedules.delete(key);
            }
        }
    }

    public List<PlaybackConfiguration> listPlaybackConfigurations(String region) {
        List<PlaybackConfiguration> result = new ArrayList<>();
        for (PlaybackConfiguration config : configurations.values()) {
            if (region.equals(config.getRegion())) {
                result.add(config);
            }
        }
        result.sort(Comparator.comparing(PlaybackConfiguration::getName, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized PlaybackConfiguration configureLogs(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireName(request, "PlaybackConfigurationName");
        PlaybackConfiguration config = requireConfig(region, name);
        Integer percent = optionalInt(request, "PercentEnabled");
        if (percent == null) {
            throw badRequest("PercentEnabled is required.");
        }
        if (percent < 0 || percent > 100) {
            throw badRequest("PercentEnabled must be between 0 and 100.");
        }
        config.setPercentEnabled(percent);
        JsonNode strategies = node(request, "EnabledLoggingStrategies");
        if (strategies != null) {
            config.setEnabledLoggingStrategies(stringList(strategies));
        }
        if (node(request, "AdsInteractionLog") != null) {
            config.setAdsInteractionLog(copyNode(node(request, "AdsInteractionLog")));
        }
        if (node(request, "ManifestServiceInteractionLog") != null) {
            config.setManifestServiceInteractionLog(copyNode(node(request, "ManifestServiceInteractionLog")));
        }
        configurations.put(storageKey(region, config.getName()), config);
        return config;
    }

    public synchronized PrefetchSchedule createPrefetchSchedule(
            String region, String playbackConfigurationName, String name, JsonNode request) {
        requireConfig(region, playbackConfigurationName);
        requireObject(request, "Request body");
        String key = prefetchKey(region, playbackConfigurationName, name);
        if (prefetchSchedules.get(key).isPresent()) {
            throw badRequest("A prefetch schedule named " + name + " already exists.");
        }
        PrefetchSchedule schedule = new PrefetchSchedule();
        schedule.setName(name);
        schedule.setPlaybackConfigurationName(playbackConfigurationName);
        schedule.setRegion(region);
        schedule.setArn(arn(region, "prefetchSchedule/" + playbackConfigurationName + "/" + name));
        schedule.setConsumption(copyNode(node(request, "Consumption")));
        schedule.setRetrieval(copyNode(node(request, "Retrieval")));
        schedule.setRecurringPrefetchConfiguration(copyNode(node(request, "RecurringPrefetchConfiguration")));
        schedule.setScheduleType(optionalText(request, "ScheduleType"));
        schedule.setStreamId(optionalText(request, "StreamId"));
        if (node(request, "tags", "Tags") != null) {
            schedule.setTags(readTags(request));
        }
        prefetchSchedules.put(key, schedule);
        return schedule;
    }

    public PrefetchSchedule getPrefetchSchedule(String region, String playbackConfigurationName, String name) {
        return requirePrefetch(region, playbackConfigurationName, name);
    }

    public List<PrefetchSchedule> listPrefetchSchedules(String region, String playbackConfigurationName) {
        requireConfig(region, playbackConfigurationName);
        List<PrefetchSchedule> result = new ArrayList<>();
        for (PrefetchSchedule schedule : prefetchSchedules.values()) {
            if (region.equals(schedule.getRegion())
                    && playbackConfigurationName.equals(schedule.getPlaybackConfigurationName())) {
                result.add(schedule);
            }
        }
        result.sort(Comparator.comparing(PrefetchSchedule::getName, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized void deletePrefetchSchedule(String region, String playbackConfigurationName, String name) {
        PrefetchSchedule schedule = prefetchSchedules.get(prefetchKey(region, playbackConfigurationName, name))
                .orElse(null);
        if (schedule == null) {
            throw notFound("The prefetch schedule was not found.");
        }
        prefetchSchedules.delete(prefetchKey(region, playbackConfigurationName, name));
    }

    public ObjectNode listAlerts(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw badRequest("ResourceArn is required.");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decode(resourceArn));
        } catch (IllegalArgumentException e) {
            throw badRequest("ResourceArn is not a valid channel-assembly ARN.");
        }
        String resource = parsed.resource() == null ? "" : parsed.resource();
        boolean channelAssembly = CHANNEL_ASSEMBLY_PREFIXES.stream().anyMatch(resource::startsWith);
        if (!SERVICE.equals(parsed.service()) || !channelAssembly) {
            throw badRequest("ListAlerts only accepts channel-assembly resource ARNs.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Items");
        return response;
    }

    public ObjectNode getChannelSchedule(String channelName) {
        throw channelNotFound(channelName);
    }

    public void startChannel(String channelName) {
        throw channelNotFound(channelName);
    }

    public void stopChannel(String channelName) {
        throw channelNotFound(channelName);
    }

    public ObjectNode createProgram(String channelName, String programName, JsonNode request) {
        throw channelNotFound(channelName);
    }

    public ObjectNode describeProgram(String channelName, String programName) {
        throw programNotFound(programName);
    }

    public ObjectNode updateProgram(String channelName, String programName, JsonNode request) {
        throw programNotFound(programName);
    }

    public void deleteProgram(String channelName, String programName) {
        throw programNotFound(programName);
    }

    public ObjectNode toPrefetchSchedule(PrefetchSchedule schedule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", schedule.getArn());
        node.put("Name", schedule.getName());
        node.put("PlaybackConfigurationName", schedule.getPlaybackConfigurationName());
        setOptional(node, "Consumption", schedule.getConsumption());
        setOptional(node, "Retrieval", schedule.getRetrieval());
        setOptional(node, "RecurringPrefetchConfiguration", schedule.getRecurringPrefetchConfiguration());
        putText(node, "ScheduleType", schedule.getScheduleType());
        putText(node, "StreamId", schedule.getStreamId());
        if (schedule.getTags() != null && !schedule.getTags().isEmpty()) {
            putTags(node, schedule.getTags());
        }
        return node;
    }

    public ObjectNode listPrefetchSchedulesResponse(String region, String playbackConfigurationName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (PrefetchSchedule schedule : listPrefetchSchedules(region, playbackConfigurationName)) {
            items.add(toPrefetchSchedule(schedule));
        }
        return response;
    }

    public ObjectNode toPlaybackConfiguration(PlaybackConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", config.getName());
        node.put("PlaybackConfigurationArn", config.getArn());
        putText(node, "AdDecisionServerUrl", config.getAdDecisionServerUrl());
        putText(node, "VideoContentSourceUrl", config.getVideoContentSourceUrl());
        putText(node, "SlateAdUrl", config.getSlateAdUrl());
        putText(node, "TranscodeProfileName", config.getTranscodeProfileName());
        putText(node, "InsertionMode", config.getInsertionMode());
        if (config.getPersonalizationThresholdSeconds() != null) {
            node.put("PersonalizationThresholdSeconds", config.getPersonalizationThresholdSeconds());
        }
        putText(node, "PlaybackEndpointPrefix", config.getPlaybackEndpointPrefix());
        putText(node, "SessionInitializationEndpointPrefix",
                config.getPlaybackEndpointPrefix() + "/v1/session");
        ObjectNode hls = node.putObject("HlsConfiguration");
        hls.put("ManifestEndpointPrefix", config.getPlaybackEndpointPrefix() + "/v1/master/" + config.getName());
        ObjectNode dash = node.putObject("DashConfiguration");
        dash.put("ManifestEndpointPrefix", config.getPlaybackEndpointPrefix() + "/v1/dash/" + config.getName());
        copyInto(dash, config.getDashConfiguration());
        setOptional(node, "AvailSuppression", config.getAvailSuppression());
        setOptional(node, "Bumper", config.getBumper());
        setOptional(node, "CdnConfiguration", config.getCdnConfiguration());
        setOptional(node, "LivePreRollConfiguration", config.getLivePreRollConfiguration());
        setOptional(node, "ManifestProcessingRules", config.getManifestProcessingRules());
        setOptional(node, "ConfigurationAliases", config.getConfigurationAliases());
        setOptional(node, "AdConditioningConfiguration", config.getAdConditioningConfiguration());
        setOptional(node, "AdDecisionServerConfiguration", config.getAdDecisionServerConfiguration());
        setOptional(node, "FunctionMapping", config.getFunctionMapping());
        node.set("LogConfiguration", toLogConfiguration(config));
        putTags(node, config.getTags());
        return node;
    }

    public ObjectNode toConfigureLogsResponse(PlaybackConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PlaybackConfigurationName", config.getName());
        node.put("PercentEnabled", config.getPercentEnabled());
        ArrayNode strategies = node.putArray("EnabledLoggingStrategies");
        for (String strategy : config.getEnabledLoggingStrategies()) {
            strategies.add(strategy);
        }
        setOptional(node, "AdsInteractionLog", config.getAdsInteractionLog());
        setOptional(node, "ManifestServiceInteractionLog", config.getManifestServiceInteractionLog());
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        PlaybackConfiguration config = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(config.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        config.setTags(current);
        configurations.put(storageKey(region, config.getName()), config);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        PlaybackConfiguration config = requireByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(config.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        config.setTags(current);
        configurations.put(storageKey(region, config.getName()), config);
    }

    private ObjectNode toLogConfiguration(PlaybackConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PercentEnabled", config.getPercentEnabled());
        ArrayNode strategies = node.putArray("EnabledLoggingStrategies");
        for (String strategy : config.getEnabledLoggingStrategies()) {
            strategies.add(strategy);
        }
        setOptional(node, "AdsInteractionLog", config.getAdsInteractionLog());
        setOptional(node, "ManifestServiceInteractionLog", config.getManifestServiceInteractionLog());
        return node;
    }

    private PlaybackConfiguration requireConfig(String region, String name) {
        PlaybackConfiguration config = lookup(region, name);
        if (config == null) {
            throw notFound("The playback configuration " + name + " was not found.");
        }
        return config;
    }

    private PlaybackConfiguration lookup(String region, String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("Name is required.");
        }
        return configurations.get(storageKey(region, name)).orElse(null);
    }

    private PlaybackConfiguration requireByArn(String region, String arn) {
        String decoded = decode(arn);
        for (PlaybackConfiguration config : configurations.values()) {
            if (decoded.equals(config.getArn()) && region.equals(config.getRegion())) {
                return config;
            }
        }
        throw notFound("The playback configuration was not found.");
    }

    private JsonNode copyNode(JsonNode node) {
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    private void setOptional(ObjectNode parent, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            parent.set(field, value.deepCopy());
        }
    }

    private void copyInto(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        source.fields().forEachRemaining(entry -> {
            if (!"ManifestEndpointPrefix".equals(entry.getKey())) {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }

    private Map<String, String> readTags(JsonNode request) {
        JsonNode tagsNode = node(request, "tags", "Tags");
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw badRequest("Tags must be a string map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            tags.put(entry.getKey(), entry.getValue().asText());
        });
        return tags;
    }

    private void putTags(ObjectNode node, Map<String, String> tags) {
        ObjectNode tagsNode = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(tagsNode::put);
        }
        node.set("tags", tagsNode);
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw badRequest(label + " must be a JSON object.");
        }
    }

    private static String requireName(JsonNode request, String field) {
        String value = requireText(request, field);
        if (!NAME_PATTERN.matcher(value).matches()) {
            throw badRequest(field + " must be 1-64 letters, digits, hyphens, or underscores.");
        }
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = node(parent, field);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        JsonNode value = node(parent, field);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private static JsonNode node(JsonNode parent, String... fields) {
        if (parent == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = parent.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.textValue().isBlank()) {
                    list.add(item.textValue());
                }
            }
        }
        return list;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String prefetchKey(String region, String playbackConfigurationName, String name) {
        return region + "::" + playbackConfigurationName + "::" + name;
    }

    private PrefetchSchedule requirePrefetch(String region, String playbackConfigurationName, String name) {
        PrefetchSchedule schedule = prefetchSchedules.get(prefetchKey(region, playbackConfigurationName, name))
                .orElse(null);
        if (schedule == null) {
            throw notFound("The prefetch schedule was not found.");
        }
        return schedule;
    }

    static AwsException channelNotFound(String channelName) {
        return notFound("Channel " + channelName + " was not found.");
    }

    static AwsException programNotFound(String programName) {
        return notFound("Program " + programName + " was not found.");
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }
}
