package io.floci.aws.services.mediatailor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MediaTailor's control plane. Playback configurations (each with its session, HLS and DASH endpoints, which
 * {@link MediaTailorPlayback} serves), their log configuration and prefetch schedules; functions; and channel
 * assembly's source locations, VOD and live sources, channels (each output with its playback URL, which
 * {@link MediaTailorChannels} serves while the channel runs), channel policies and programs, the channel schedule and
 * alerts.
 */
@ApplicationScoped
public class MediaTailorService {

    private static final Pattern HMS = Pattern.compile("\\d{2}:[0-5]\\d:[0-5]\\d");
    private static final Set<String> FUNCTION_TYPES = Set.of("HTTP_REQUEST", "AWS_SERVICE_REQUEST", "CUSTOM_OUTPUT",
            "CONCURRENT_EXECUTOR", "SEQUENTIAL_EXECUTOR", "VAST_REQUEST");

    private final MediaTailorStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaTailorPlayback> playback;
    private final Provider<MediaTailorChannels> channels;

    @Inject
    public MediaTailorService(MediaTailorStore store, Provider<MediaTailorPlayback> playback,
                              Provider<MediaTailorChannels> channels) {
        this.store = store;
        this.mapper = store.mapper();
        this.playback = playback;
        this.channels = channels;
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw MediaTailorStore.badRequest(member + " is required.");
        }
        return value;
    }

    private ObjectNode stamp(ObjectNode record) {
        double now = MediaTailorStore.epoch();
        if (!record.has("CreationTime")) {
            record.put("CreationTime", now);
        }
        record.put("LastModifiedTime", now);
        return record;
    }

    private static ObjectNode view(ObjectNode record) {
        ObjectNode view = MediaTailorStore.view(record);
        view.set("Tags", MediaTailorStore.tags(record).deepCopy());
        return view;
    }

    private static void page(List<ObjectNode> items, JsonNode in, ObjectNode out) {
        MediaTailorStore.page(items, in, out, "Items", 50);
    }

    // ================================================================ playback configurations

    ObjectNode configuration(String region, String name) {
        return store.get(region, MediaTailorStore.CONFIG, name).orElseThrow(() -> MediaTailorStore.notFound(
                "Playback configuration " + name + " not found."));
    }

    /** A configuration as Get and Put show it: its settings, endpoints and tags. */
    ObjectNode configurationView(ObjectNode record) {
        return view(record);
    }

    public ObjectNode putPlaybackConfiguration(JsonNode in, String region) {
        String name = required(in, "Name");
        ObjectNode existing = store.get(region, MediaTailorStore.CONFIG, name).orElse(null);
        JsonNode suppression = in.path("AvailSuppression");
        if (suppression.isObject() && !"OFF".equals(suppression.path("Mode").asText("OFF"))
                && !HMS.matcher(suppression.path("Value").asText("")).matches()) {
            throw MediaTailorStore.badRequest("AvailSuppression.Value must be HH:MM:SS when its mode isn't OFF.");
        }
        JsonNode preRoll = in.path("LivePreRollConfiguration");
        if (preRoll.hasNonNull("MaxDurationSeconds") && preRoll.get("MaxDurationSeconds").asInt() < 0) {
            throw MediaTailorStore.badRequest("LivePreRollConfiguration.MaxDurationSeconds can't be negative.");
        }
        for (String url : List.of("AdDecisionServerUrl", "VideoContentSourceUrl", "SlateAdUrl")) {
            String value = in.path(url).asText("");
            if (!value.isEmpty() && !value.matches("(?i)https?://.+")) {
                throw MediaTailorStore.badRequest(url + " must be an http:// or https:// URL.");
            }
        }
        in.path("FunctionMapping").properties().forEach(hook -> {
            String id = hook.getValue().path("FunctionId").asText("");
            if (!id.isEmpty() && store.get(region, MediaTailorStore.FUNCTION, id).isEmpty()) {
                throw MediaTailorStore.badRequest("The function " + id + " doesn't exist.");
            }
        });
        ObjectNode record = store.record();
        if (existing != null) {
            record.set("_", MediaTailorStore.meta(existing).deepCopy());
        }
        for (String member : List.of("AdDecisionServerUrl", "AvailSuppression", "Bumper", "CdnConfiguration",
                "ConfigurationAliases", "InsertionMode", "LivePreRollConfiguration", "ManifestProcessingRules",
                "PersonalizationThresholdSeconds", "SlateAdUrl", "TranscodeProfileName", "VideoContentSourceUrl",
                "AdConditioningConfiguration", "AdDecisionServerConfiguration", "YieldOptimizationConfiguration",
                "FunctionMapping", "AdsPersonalizationTimeouts", "AdsPersonalizationConcurrency",
                "BeaconingConfiguration")) {
            if (in.hasNonNull(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        record.put("Name", name);
        if (!record.has("AvailSuppression")) {
            record.putObject("AvailSuppression").put("Mode", "OFF");
        }
        ObjectNode avail = (ObjectNode) record.get("AvailSuppression");
        if (!avail.has("Mode")) {
            avail.put("Mode", "OFF");
        }
        if (!avail.has("FillPolicy")) {
            avail.put("FillPolicy", "FULL_AVAIL_ONLY");
        }
        if (!record.has("Bumper")) {
            record.putObject("Bumper");
        }
        if (!record.has("CdnConfiguration")) {
            record.putObject("CdnConfiguration");
        }
        if (!record.has("InsertionMode")) {
            record.put("InsertionMode", "STITCHED_ONLY");
        }
        if (!record.has("LivePreRollConfiguration")) {
            record.putObject("LivePreRollConfiguration").put("MaxDurationSeconds", 0);
        }
        if (!record.has("ManifestProcessingRules")) {
            record.putObject("ManifestProcessingRules").putObject("AdMarkerPassthrough").put("Enabled", false);
        }
        if (!record.has("AdConditioningConfiguration")) {
            record.putObject("AdConditioningConfiguration").put("StreamingMediaFileConditioning", "TRANSCODE");
        }
        String account = store.account();
        String prefix = store.hostPrefix(MediaTailorStore.PLAYBACK, account, region);
        String hostId = MediaTailorStore.hostId(account, region);
        ObjectNode dash = record.putObject("DashConfiguration");
        dash.put("ManifestEndpointPrefix", prefix + "/v1/dash/" + hostId + "/" + name + "/");
        dash.put("DualStackManifestEndpointPrefix", prefix + "/v1/dash/" + hostId + "/" + name + "/");
        dash.put("MpdLocation", in.path("DashConfiguration").path("MpdLocation").asText("EMT_DEFAULT"));
        dash.put("OriginManifestType", in.path("DashConfiguration").path("OriginManifestType")
                .asText("MULTI_PERIOD"));
        ObjectNode hls = record.putObject("HlsConfiguration");
        hls.put("ManifestEndpointPrefix", prefix + "/v1/master/" + hostId + "/" + name + "/");
        hls.put("DualStackManifestEndpointPrefix", prefix + "/v1/master/" + hostId + "/" + name + "/");
        record.put("PlaybackConfigurationArn", store.arn(region, "playbackConfiguration/" + name));
        record.put("PlaybackEndpointPrefix", prefix);
        record.put("DualStackPlaybackEndpointPrefix", prefix);
        record.put("SessionInitializationEndpointPrefix", prefix + "/v1/session/" + hostId + "/" + name + "/");
        record.put("DualStackSessionInitializationEndpointPrefix", prefix + "/v1/session/" + hostId + "/" + name
                + "/");
        if (existing != null && existing.has("LogConfiguration")) {
            record.set("LogConfiguration", existing.get("LogConfiguration").deepCopy());
        } else {
            ObjectNode logs = record.putObject("LogConfiguration");
            logs.put("PercentEnabled", 0);
            logs.putArray("EnabledLoggingStrategies").add("LEGACY_CLOUDWATCH");
        }
        MediaTailorStore.addTags(record, in.path("Tags"));
        store.put(region, MediaTailorStore.CONFIG, name, record);
        playback.get().configurationChanged(store.account(), region, name);
        return configurationView(record);
    }

    public ObjectNode getPlaybackConfiguration(JsonNode in, String region) {
        return configurationView(configuration(region, required(in, "Name")));
    }

    public ObjectNode deletePlaybackConfiguration(JsonNode in, String region) {
        String name = required(in, "Name");
        if (store.get(region, MediaTailorStore.CONFIG, name).isPresent()) {
            for (ObjectNode schedule : store.list(region, MediaTailorStore.PREFETCH)) {
                if (name.equals(schedule.path("PlaybackConfigurationName").asText())) {
                    store.delete(region, MediaTailorStore.PREFETCH, prefetchKey(name, schedule.path("Name").asText()));
                }
            }
            store.delete(region, MediaTailorStore.CONFIG, name);
            playback.get().configurationChanged(store.account(), region, name);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode listPlaybackConfigurations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MediaTailorStore.CONFIG).forEach(record -> items.add(configurationView(record)));
        ObjectNode out = mapper.createObjectNode();
        page(items, in, out);
        return out;
    }

    public ObjectNode configureLogsForPlaybackConfiguration(JsonNode in, String region) {
        String name = required(in, "PlaybackConfigurationName");
        ObjectNode record = configuration(region, name);
        int percent = in.path("PercentEnabled").asInt(-1);
        if (percent < 0 || percent > 100) {
            throw MediaTailorStore.badRequest("PercentEnabled must be from 0 to 100.");
        }
        ObjectNode logs = mapper.createObjectNode();
        logs.put("PercentEnabled", percent);
        ArrayNode strategies = logs.putArray("EnabledLoggingStrategies");
        if (in.path("EnabledLoggingStrategies").isArray() && !in.get("EnabledLoggingStrategies").isEmpty()) {
            in.get("EnabledLoggingStrategies").forEach(strategies::add);
        } else {
            strategies.add("LEGACY_CLOUDWATCH");
        }
        MediaTailorStore.copy(in, logs, "AdsInteractionLog", "ManifestServiceInteractionLog");
        record.set("LogConfiguration", logs);
        store.put(region, MediaTailorStore.CONFIG, name, record);
        ObjectNode out = logs.deepCopy();
        out.put("PlaybackConfigurationName", name);
        return out;
    }

    // ================================================================ prefetch schedules

    static String prefetchKey(String configuration, String name) {
        return configuration + "/" + name;
    }

    ObjectNode prefetch(String region, String configuration, String name) {
        ObjectNode schedule = store.get(region, MediaTailorStore.PREFETCH, prefetchKey(configuration, name))
                .orElse(null);
        if (schedule == null || expired(schedule)) {
            if (schedule != null) {
                store.delete(region, MediaTailorStore.PREFETCH, prefetchKey(configuration, name));
            }
            throw MediaTailorStore.notFound("Prefetch schedule " + name + " not found.");
        }
        return schedule;
    }

    /** Whether a schedule's consumption (or recurring) window has ended: MediaTailor then deletes it. */
    static boolean expired(JsonNode schedule) {
        double end = "RECURRING".equals(schedule.path("ScheduleType").asText())
                ? schedule.path("RecurringPrefetchConfiguration").path("EndTime").asDouble(Double.MAX_VALUE)
                : schedule.path("Consumption").path("EndTime").asDouble(Double.MAX_VALUE);
        return end < MediaTailorStore.epoch();
    }

    public ObjectNode createPrefetchSchedule(JsonNode in, String region) {
        String configName = required(in, "PlaybackConfigurationName");
        String name = required(in, "Name");
        configuration(region, configName);
        String key = prefetchKey(configName, name);
        ObjectNode existing = store.get(region, MediaTailorStore.PREFETCH, key).orElse(null);
        if (existing != null && !expired(existing)) {
            throw MediaTailorStore.badRequest("A prefetch schedule named " + name + " already exists.");
        }
        String type = in.path("ScheduleType").asText("SINGLE");
        double now = MediaTailorStore.epoch();
        if ("SINGLE".equals(type)) {
            JsonNode retrieval = in.path("Retrieval");
            JsonNode consumption = in.path("Consumption");
            if (!retrieval.isObject() || !consumption.isObject()) {
                throw MediaTailorStore.badRequest("A SINGLE prefetch schedule needs Retrieval and Consumption.");
            }
            double retrievalEnd = retrieval.path("EndTime").asDouble(0);
            double retrievalStart = retrieval.path("StartTime").asDouble(now);
            double consumptionEnd = consumption.path("EndTime").asDouble(0);
            double consumptionStart = consumption.path("StartTime").asDouble(retrievalStart);
            if (retrievalEnd <= retrievalStart || retrievalEnd <= now) {
                throw MediaTailorStore.badRequest("Retrieval.EndTime must be in the future and after its StartTime.");
            }
            if (consumptionEnd <= consumptionStart || consumptionEnd < retrievalEnd) {
                throw MediaTailorStore.badRequest("Consumption.EndTime must be after its StartTime and after "
                        + "Retrieval.EndTime.");
            }
            if (consumption.path("AvailMatchingCriteria").size() > 5) {
                throw MediaTailorStore.badRequest("Consumption takes at most 5 AvailMatchingCriteria.");
            }
        } else if ("RECURRING".equals(type)) {
            JsonNode recurring = in.path("RecurringPrefetchConfiguration");
            if (!recurring.isObject()) {
                throw MediaTailorStore.badRequest("A RECURRING prefetch schedule needs RecurringPrefetchConfiguration.");
            }
            if (recurring.path("EndTime").asDouble(0) <= recurring.path("StartTime").asDouble(now)) {
                throw MediaTailorStore.badRequest("RecurringPrefetchConfiguration.EndTime must be after its StartTime.");
            }
        } else {
            throw MediaTailorStore.badRequest("ScheduleType must be SINGLE or RECURRING.");
        }
        ObjectNode schedule = store.record();
        MediaTailorStore.copy(in, schedule, "Consumption", "Retrieval", "RecurringPrefetchConfiguration", "StreamId");
        schedule.put("Name", name);
        schedule.put("PlaybackConfigurationName", configName);
        schedule.put("ScheduleType", type);
        schedule.put("Arn", store.arn(region, "prefetchSchedule/" + configName + "/" + name));
        MediaTailorStore.addTags(schedule, in.path("Tags"));
        store.put(region, MediaTailorStore.PREFETCH, key, schedule);
        playback.get().schedulePrefetch(store.account(), region, configName, name);
        return view(schedule);
    }

    public ObjectNode getPrefetchSchedule(JsonNode in, String region) {
        return view(prefetch(region, required(in, "PlaybackConfigurationName"), required(in, "Name")));
    }

    public ObjectNode deletePrefetchSchedule(JsonNode in, String region) {
        String configName = required(in, "PlaybackConfigurationName");
        String name = required(in, "Name");
        prefetch(region, configName, name);
        store.delete(region, MediaTailorStore.PREFETCH, prefetchKey(configName, name));
        playback.get().forgetPrefetch(store.account(), region, configName, name);
        return mapper.createObjectNode();
    }

    public ObjectNode listPrefetchSchedules(JsonNode in, String region) {
        String configName = required(in, "PlaybackConfigurationName");
        configuration(region, configName);
        String type = in.path("ScheduleType").asText("ALL");
        String stream = in.path("StreamId").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode schedule : store.list(region, MediaTailorStore.PREFETCH)) {
            if (!configName.equals(schedule.path("PlaybackConfigurationName").asText())) {
                continue;
            }
            if (expired(schedule)) {
                store.delete(region, MediaTailorStore.PREFETCH, prefetchKey(configName, schedule.path("Name")
                        .asText()));
                continue;
            }
            if (!"ALL".equals(type) && !type.equals(schedule.path("ScheduleType").asText())) {
                continue;
            }
            if (stream != null && !stream.equals(schedule.path("StreamId").asText(null))) {
                continue;
            }
            items.add(view(schedule));
        }
        ObjectNode out = mapper.createObjectNode();
        page(items, in, out);
        return out;
    }

    // ================================================================ functions

    ObjectNode function(String region, String id) {
        return store.get(region, MediaTailorStore.FUNCTION, id).orElseThrow(() -> MediaTailorStore.notFound(
                "Function " + id + " not found."));
    }

    public ObjectNode putFunction(JsonNode in, String region) {
        String id = required(in, "FunctionId");
        String type = required(in, "FunctionType");
        if (!FUNCTION_TYPES.contains(type)) {
            throw MediaTailorStore.badRequest("FunctionType must be one of " + String.join(", ", FUNCTION_TYPES)
                    + ".");
        }
        String member = switch (type) {
            case "HTTP_REQUEST" -> "HttpRequestConfiguration";
            case "AWS_SERVICE_REQUEST" -> "AwsServiceRequestConfiguration";
            case "CUSTOM_OUTPUT" -> "CustomOutputConfiguration";
            case "CONCURRENT_EXECUTOR" -> "ConcurrentExecutorConfiguration";
            case "SEQUENTIAL_EXECUTOR" -> "SequentialExecutorConfiguration";
            default -> "VastRequestConfiguration";
        };
        if (!in.path(member).isObject()) {
            throw MediaTailorStore.badRequest("A " + type + " function needs " + member + ".");
        }
        ObjectNode existing = store.get(region, MediaTailorStore.FUNCTION, id).orElse(null);
        ObjectNode record = store.record();
        if (existing != null) {
            record.set("_", MediaTailorStore.meta(existing).deepCopy());
        }
        record.put("FunctionId", id);
        record.put("FunctionType", type);
        MediaTailorStore.copy(in, record, "Description", member);
        record.put("Arn", store.arn(region, "function/" + id));
        MediaTailorStore.addTags(record, in.path("Tags"));
        store.put(region, MediaTailorStore.FUNCTION, id, record);
        return view(record);
    }

    public ObjectNode getFunction(JsonNode in, String region) {
        return view(function(region, required(in, "FunctionId")));
    }

    public ObjectNode deleteFunction(JsonNode in, String region) {
        String id = required(in, "FunctionId");
        function(region, id);
        for (ObjectNode config : store.list(region, MediaTailorStore.CONFIG)) {
            for (JsonNode hook : config.path("FunctionMapping")) {
                if (id.equals(hook.path("FunctionId").asText())) {
                    throw MediaTailorStore.badRequest("The function " + id + " is used by the playback configuration "
                            + config.path("Name").asText() + ".");
                }
            }
        }
        store.delete(region, MediaTailorStore.FUNCTION, id);
        return mapper.createObjectNode();
    }

    public ObjectNode listFunctions(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MediaTailorStore.FUNCTION).forEach(record -> items.add(view(record)));
        ObjectNode out = mapper.createObjectNode();
        page(items, in, out);
        return out;
    }

    // ================================================================ source locations

    ObjectNode location(String region, String name) {
        return store.get(region, MediaTailorStore.LOCATION, name).orElseThrow(() -> MediaTailorStore.notFound(
                "Source location " + name + " not found."));
    }

    private static void checkLocation(JsonNode in, boolean create) {
        if (create || in.has("HttpConfiguration")) {
            String base = in.path("HttpConfiguration").path("BaseUrl").asText("");
            if (!base.matches("(?i)https?://.+")) {
                throw MediaTailorStore.badRequest("HttpConfiguration.BaseUrl must be an http:// or https:// URL.");
            }
        }
        String access = in.path("AccessConfiguration").path("AccessType").asText("");
        if ("SECRETS_MANAGER_ACCESS_TOKEN".equals(access) && !in.path("AccessConfiguration")
                .path("SecretsManagerAccessTokenConfiguration").isObject()) {
            throw MediaTailorStore.badRequest("SECRETS_MANAGER_ACCESS_TOKEN access needs "
                    + "SecretsManagerAccessTokenConfiguration.");
        }
    }

    public ObjectNode createSourceLocation(JsonNode in, String region) {
        String name = required(in, "SourceLocationName");
        if (store.get(region, MediaTailorStore.LOCATION, name).isPresent()) {
            throw MediaTailorStore.badRequest("A source location named " + name + " already exists.");
        }
        checkLocation(in, true);
        ObjectNode record = store.record();
        MediaTailorStore.copy(in, record, "AccessConfiguration", "DefaultSegmentDeliveryConfiguration",
                "HttpConfiguration", "SegmentDeliveryConfigurations");
        record.put("SourceLocationName", name);
        record.put("Arn", store.arn(region, "sourceLocation/" + name));
        MediaTailorStore.addTags(record, in.path("Tags"));
        stamp(record);
        store.put(region, MediaTailorStore.LOCATION, name, record);
        return view(record);
    }

    public ObjectNode describeSourceLocation(JsonNode in, String region) {
        return view(location(region, required(in, "SourceLocationName")));
    }

    public ObjectNode updateSourceLocation(JsonNode in, String region) {
        String name = required(in, "SourceLocationName");
        ObjectNode record = location(region, name);
        checkLocation(in, false);
        MediaTailorStore.copy(in, record, "AccessConfiguration", "DefaultSegmentDeliveryConfiguration",
                "HttpConfiguration", "SegmentDeliveryConfigurations");
        stamp(record);
        store.put(region, MediaTailorStore.LOCATION, name, record);
        return view(record);
    }

    public ObjectNode deleteSourceLocation(JsonNode in, String region) {
        String name = required(in, "SourceLocationName");
        location(region, name);
        for (String kind : List.of(MediaTailorStore.VOD, MediaTailorStore.LIVE)) {
            for (ObjectNode source : store.list(region, kind)) {
                if (name.equals(source.path("SourceLocationName").asText())) {
                    throw MediaTailorStore.badRequest("The source location " + name + " still has sources; delete "
                            + "them first.");
                }
            }
        }
        store.delete(region, MediaTailorStore.LOCATION, name);
        return mapper.createObjectNode();
    }

    public ObjectNode listSourceLocations(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MediaTailorStore.LOCATION).forEach(record -> items.add(view(record)));
        ObjectNode out = mapper.createObjectNode();
        page(items, in, out);
        return out;
    }

    // ================================================================ VOD and live sources

    static String sourceKey(String location, String name) {
        return location + "/" + name;
    }

    ObjectNode source(String region, String kind, String location, String name) {
        return store.get(region, kind, sourceKey(location, name)).orElseThrow(() -> MediaTailorStore.notFound(
                (MediaTailorStore.VOD.equals(kind) ? "VOD source " : "Live source ") + name + " not found."));
    }

    private static void checkPackages(JsonNode in) {
        JsonNode packages = in.path("HttpPackageConfigurations");
        if (!packages.isArray() || packages.isEmpty()) {
            throw MediaTailorStore.badRequest("HttpPackageConfigurations needs at least one package.");
        }
        Set<String> groups = new HashSet<>();
        for (JsonNode config : packages) {
            if (config.path("Path").asText("").isEmpty() || config.path("SourceGroup").asText("").isEmpty()) {
                throw MediaTailorStore.badRequest("Each HTTP package configuration needs a Path and a SourceGroup.");
            }
            if (!groups.add(config.path("SourceGroup").asText() + "/" + config.path("Type").asText())) {
                throw MediaTailorStore.badRequest("The source group " + config.path("SourceGroup").asText()
                        + " has two " + config.path("Type").asText() + " packages.");
            }
        }
    }

    private ObjectNode createSource(JsonNode in, String region, String kind, String member, String arnKind) {
        String locationName = required(in, "SourceLocationName");
        String name = required(in, member);
        location(region, locationName);
        if (store.get(region, kind, sourceKey(locationName, name)).isPresent()) {
            throw MediaTailorStore.badRequest("A source named " + name + " already exists in " + locationName + ".");
        }
        checkPackages(in);
        ObjectNode record = store.record();
        record.set("HttpPackageConfigurations", in.get("HttpPackageConfigurations").deepCopy());
        record.put("SourceLocationName", locationName);
        record.put(member, name);
        record.put("Arn", store.arn(region, arnKind + "/" + locationName + "/" + name));
        MediaTailorStore.addTags(record, in.path("Tags"));
        stamp(record);
        store.put(region, kind, sourceKey(locationName, name), record);
        return view(record);
    }

    private ObjectNode updateSource(JsonNode in, String region, String kind, String member) {
        String locationName = required(in, "SourceLocationName");
        String name = required(in, member);
        ObjectNode record = source(region, kind, locationName, name);
        checkPackages(in);
        record.set("HttpPackageConfigurations", in.get("HttpPackageConfigurations").deepCopy());
        stamp(record);
        store.put(region, kind, sourceKey(locationName, name), record);
        channels.get().sourceChanged(store.account(), region, locationName);
        return view(record);
    }

    private ObjectNode deleteSource(JsonNode in, String region, String kind, String member) {
        String locationName = required(in, "SourceLocationName");
        String name = required(in, member);
        source(region, kind, locationName, name);
        String programMember = MediaTailorStore.VOD.equals(kind) ? "VodSourceName" : "LiveSourceName";
        for (ObjectNode program : store.list(region, MediaTailorStore.PROGRAM)) {
            if (locationName.equals(program.path("SourceLocationName").asText())
                    && name.equals(program.path(programMember).asText())) {
                throw MediaTailorStore.badRequest("The source " + name + " is used by the program "
                        + program.path("ProgramName").asText() + ".");
            }
        }
        if (MediaTailorStore.VOD.equals(kind)) {
            for (ObjectNode channel : store.list(region, MediaTailorStore.CHANNEL)) {
                JsonNode slate = channel.path("FillerSlate");
                if (locationName.equals(slate.path("SourceLocationName").asText())
                        && name.equals(slate.path("VodSourceName").asText())) {
                    throw MediaTailorStore.badRequest("The source " + name + " is the filler slate of the channel "
                            + channel.path("ChannelName").asText() + ".");
                }
            }
        }
        store.delete(region, kind, sourceKey(locationName, name));
        return mapper.createObjectNode();
    }

    private ObjectNode listSources(JsonNode in, String region, String kind) {
        String locationName = required(in, "SourceLocationName");
        location(region, locationName);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind)) {
            if (locationName.equals(record.path("SourceLocationName").asText())) {
                items.add(view(record));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        page(items, in, out);
        return out;
    }

    public ObjectNode createVodSource(JsonNode in, String region) {
        return createSource(in, region, MediaTailorStore.VOD, "VodSourceName", "vodSource");
    }

    public ObjectNode describeVodSource(JsonNode in, String region) {
        ObjectNode out = view(source(region, MediaTailorStore.VOD, required(in, "SourceLocationName"),
                required(in, "VodSourceName")));
        ArrayNode breaks = out.putArray("AdBreakOpportunities");
        channels.get().adBreakOpportunities(store.account(), region, out).forEach(offset ->
                breaks.addObject().put("OffsetMillis", offset));
        return out;
    }

    public ObjectNode updateVodSource(JsonNode in, String region) {
        return updateSource(in, region, MediaTailorStore.VOD, "VodSourceName");
    }

    public ObjectNode deleteVodSource(JsonNode in, String region) {
        return deleteSource(in, region, MediaTailorStore.VOD, "VodSourceName");
    }

    public ObjectNode listVodSources(JsonNode in, String region) {
        return listSources(in, region, MediaTailorStore.VOD);
    }

    public ObjectNode createLiveSource(JsonNode in, String region) {
        return createSource(in, region, MediaTailorStore.LIVE, "LiveSourceName", "liveSource");
    }

    public ObjectNode describeLiveSource(JsonNode in, String region) {
        return view(source(region, MediaTailorStore.LIVE, required(in, "SourceLocationName"),
                required(in, "LiveSourceName")));
    }

    public ObjectNode updateLiveSource(JsonNode in, String region) {
        return updateSource(in, region, MediaTailorStore.LIVE, "LiveSourceName");
    }

    public ObjectNode deleteLiveSource(JsonNode in, String region) {
        return deleteSource(in, region, MediaTailorStore.LIVE, "LiveSourceName");
    }

    public ObjectNode listLiveSources(JsonNode in, String region) {
        return listSources(in, region, MediaTailorStore.LIVE);
    }

    // ================================================================ channels

    ObjectNode channel(String region, String name) {
        return store.get(region, MediaTailorStore.CHANNEL, name).orElseThrow(() -> MediaTailorStore.notFound(
                "Channel " + name + " not found."));
    }

    ObjectNode channelView(String region, ObjectNode record) {
        ObjectNode view = view(record);
        String prefix = store.hostPrefix(MediaTailorStore.ASSEMBLY, store.account(), region);
        for (JsonNode output : view.path("Outputs")) {
            String extension = output.has("DashPlaylistSettings") ? ".mpd" : ".m3u8";
            String url = prefix + "/v1/channel/" + record.path("ChannelName").asText() + "/"
                    + output.path("ManifestName").asText() + extension;
            ((ObjectNode) output).put("PlaybackUrl", url);
            ((ObjectNode) output).put("DualStackPlaybackUrl", url);
        }
        return view;
    }

    private void checkChannel(JsonNode in, String region, String mode, String tier) {
        JsonNode outputs = in.path("Outputs");
        if (!outputs.isArray() || outputs.isEmpty()) {
            throw MediaTailorStore.badRequest("A channel needs at least one output.");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode output : outputs) {
            String manifest = output.path("ManifestName").asText("");
            if (manifest.isEmpty() || output.path("SourceGroup").asText("").isEmpty()) {
                throw MediaTailorStore.badRequest("Each output needs a ManifestName and a SourceGroup.");
            }
            if (!names.add(manifest)) {
                throw MediaTailorStore.badRequest("Two outputs are named " + manifest + ".");
            }
            if (output.has("HlsPlaylistSettings") == output.has("DashPlaylistSettings")) {
                throw MediaTailorStore.badRequest("Each output needs either HlsPlaylistSettings or "
                        + "DashPlaylistSettings.");
            }
        }
        JsonNode slate = in.path("FillerSlate");
        if (slate.isObject() && !slate.isEmpty()) {
            if (!"LINEAR".equals(mode)) {
                throw MediaTailorStore.badRequest("Only LINEAR channels have a filler slate.");
            }
            source(region, MediaTailorStore.VOD, slate.path("SourceLocationName").asText(""),
                    slate.path("VodSourceName").asText(""));
        }
        if (in.path("TimeShiftConfiguration").isObject() && !"STANDARD".equals(tier)) {
            throw MediaTailorStore.badRequest("Time-shifted viewing needs a STANDARD channel.");
        }
        if (in.path("Audiences").size() > 0 && !"LINEAR".equals(mode)) {
            throw MediaTailorStore.badRequest("Only LINEAR channels have audiences.");
        }
    }

    public ObjectNode createChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        if (store.get(region, MediaTailorStore.CHANNEL, name).isPresent()) {
            throw MediaTailorStore.badRequest("A channel named " + name + " already exists.");
        }
        String mode = required(in, "PlaybackMode");
        if (!Set.of("LOOP", "LINEAR").contains(mode)) {
            throw MediaTailorStore.badRequest("PlaybackMode must be LOOP or LINEAR.");
        }
        String tier = in.path("Tier").asText("BASIC");
        checkChannel(in, region, mode, tier);
        ObjectNode record = store.record();
        MediaTailorStore.copy(in, record, "FillerSlate", "Outputs", "TimeShiftConfiguration", "Audiences");
        record.put("ChannelName", name);
        record.put("PlaybackMode", mode);
        record.put("Tier", tier);
        record.put("ChannelState", "STOPPED");
        record.put("Arn", store.arn(region, "channel/" + name));
        record.putObject("LogConfiguration").putArray("LogTypes");
        MediaTailorStore.addTags(record, in.path("Tags"));
        stamp(record);
        store.put(region, MediaTailorStore.CHANNEL, name, record);
        return channelView(region, record);
    }

    public ObjectNode describeChannel(JsonNode in, String region) {
        return channelView(region, channel(region, required(in, "ChannelName")));
    }

    public ObjectNode updateChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        if (!"STOPPED".equals(record.path("ChannelState").asText())) {
            throw MediaTailorStore.badRequest("The channel " + name + " must be stopped to be updated.");
        }
        checkChannel(in, region, record.path("PlaybackMode").asText(), record.path("Tier").asText());
        record.set("Outputs", in.get("Outputs").deepCopy());
        for (String member : List.of("FillerSlate", "TimeShiftConfiguration", "Audiences")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        stamp(record);
        store.put(region, MediaTailorStore.CHANNEL, name, record);
        return channelView(region, record);
    }

    public ObjectNode deleteChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        if (!"STOPPED".equals(record.path("ChannelState").asText())) {
            throw MediaTailorStore.badRequest("The channel " + name + " must be stopped to be deleted.");
        }
        for (ObjectNode program : store.list(region, MediaTailorStore.PROGRAM)) {
            if (name.equals(program.path("ChannelName").asText())) {
                store.delete(region, MediaTailorStore.PROGRAM, programKey(name, program.path("ProgramName").asText()));
            }
        }
        store.delete(region, MediaTailorStore.CHANNEL, name);
        channels.get().forget(store.account(), region, name);
        return mapper.createObjectNode();
    }

    public ObjectNode listChannels(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        store.list(region, MediaTailorStore.CHANNEL).forEach(record -> items.add(channelView(region, record)));
        ObjectNode out = mapper.createObjectNode();
        MediaTailorStore.page(items, in, out, "Items", 100);
        return out;
    }

    public ObjectNode startChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        if (!"RUNNING".equals(record.path("ChannelState").asText())) {
            record.put("ChannelState", "RUNNING");
            MediaTailorStore.meta(record).put("startedAt", System.currentTimeMillis());
            stamp(record);
            store.put(region, MediaTailorStore.CHANNEL, name, record);
            channels.get().started(store.account(), region, name);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode stopChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        if (!"STOPPED".equals(record.path("ChannelState").asText())) {
            record.put("ChannelState", "STOPPED");
            MediaTailorStore.meta(record).remove("startedAt");
            stamp(record);
            store.put(region, MediaTailorStore.CHANNEL, name, record);
            channels.get().forget(store.account(), region, name);
        }
        return mapper.createObjectNode();
    }

    public ObjectNode putChannelPolicy(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        String policy = required(in, "Policy");
        try {
            JsonNode parsed = mapper.readTree(policy);
            if (!parsed.isObject() || !parsed.has("Statement")) {
                throw MediaTailorStore.badRequest("The policy must be a JSON policy document with a Statement.");
            }
        } catch (JsonProcessingException e) {
            throw MediaTailorStore.badRequest("The policy isn't valid JSON.");
        }
        MediaTailorStore.meta(record).put("policy", policy);
        store.put(region, MediaTailorStore.CHANNEL, name, record);
        return mapper.createObjectNode();
    }

    public ObjectNode getChannelPolicy(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        String policy = MediaTailorStore.meta(channel(region, name)).path("policy").asText(null);
        if (policy == null) {
            throw MediaTailorStore.notFound("Channel " + name + " has no policy.");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("Policy", policy);
        return out;
    }

    public ObjectNode deleteChannelPolicy(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        MediaTailorStore.meta(record).remove("policy");
        store.put(region, MediaTailorStore.CHANNEL, name, record);
        return mapper.createObjectNode();
    }

    public ObjectNode configureLogsForChannel(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        ArrayNode types = mapper.createArrayNode();
        for (JsonNode type : in.path("LogTypes")) {
            if (!"AS_RUN".equals(type.asText())) {
                throw MediaTailorStore.badRequest("LogTypes takes only AS_RUN.");
            }
            types.add(type.asText());
        }
        record.putObject("LogConfiguration").set("LogTypes", types);
        store.put(region, MediaTailorStore.CHANNEL, name, record);
        ObjectNode out = mapper.createObjectNode();
        out.put("ChannelName", name);
        out.set("LogTypes", types.deepCopy());
        return out;
    }

    public ObjectNode getChannelSchedule(JsonNode in, String region) {
        String name = required(in, "ChannelName");
        ObjectNode record = channel(region, name);
        int minutes;
        try {
            minutes = Integer.parseInt(in.path("DurationMinutes").asText("1440"));
        } catch (NumberFormatException e) {
            throw MediaTailorStore.badRequest("DurationMinutes must be a whole number of minutes.");
        }
        if (minutes < 1 || minutes > 43200) {
            throw MediaTailorStore.badRequest("DurationMinutes must be from 1 to 43200.");
        }
        String audience = in.path("Audience").asText(null);
        if (audience != null && !audiences(record).contains(audience)) {
            throw MediaTailorStore.badRequest("The channel has no audience " + audience + ".");
        }
        List<ObjectNode> entries = channels.get().schedule(store.account(), region, record, minutes, audience);
        ObjectNode out = mapper.createObjectNode();
        MediaTailorStore.page(entries, in, out, "Items", 100);
        return out;
    }

    private static Set<String> audiences(JsonNode channel) {
        Set<String> found = new HashSet<>();
        channel.path("Audiences").forEach(audience -> found.add(audience.asText()));
        return found;
    }

    // ================================================================ programs

    static String programKey(String channel, String program) {
        return channel + "/" + program;
    }

    ObjectNode program(String region, String channel, String name) {
        return store.get(region, MediaTailorStore.PROGRAM, programKey(channel, name)).orElseThrow(() ->
                MediaTailorStore.notFound("Program " + name + " not found."));
    }

    /** Checks a program's schedule, ad breaks and sources, and records how long it plays. */
    private void checkProgram(JsonNode in, ObjectNode channel, ObjectNode program, String region) {
        String mode = channel.path("PlaybackMode").asText();
        JsonNode transition = program.path("ScheduleConfiguration").path("Transition");
        String type = transition.path("Type").asText("");
        if ("ABSOLUTE".equals(type)) {
            if (!"LINEAR".equals(mode)) {
                throw MediaTailorStore.badRequest("Only LINEAR channels schedule programs at absolute times.");
            }
            if (!transition.hasNonNull("ScheduledStartTimeMillis")) {
                throw MediaTailorStore.badRequest("An ABSOLUTE transition needs ScheduledStartTimeMillis.");
            }
        } else if ("RELATIVE".equals(type)) {
            String relative = transition.path("RelativeProgram").asText("");
            if (!relative.isEmpty() && store.get(region, MediaTailorStore.PROGRAM, programKey(
                    channel.path("ChannelName").asText(), relative)).isEmpty()) {
                throw MediaTailorStore.badRequest("The relative program " + relative + " doesn't exist.");
            }
        } else {
            throw MediaTailorStore.badRequest("Transition.Type must be ABSOLUTE or RELATIVE.");
        }
        String locationName = program.path("SourceLocationName").asText();
        ObjectNode location = location(region, locationName);
        boolean vod = program.hasNonNull("VodSourceName");
        if (vod == program.hasNonNull("LiveSourceName")) {
            throw MediaTailorStore.badRequest("A program plays either a VodSourceName or a LiveSourceName.");
        }
        long duration;
        if (vod) {
            ObjectNode source = source(region, MediaTailorStore.VOD, locationName, program.path("VodSourceName")
                    .asText());
            long full = channels.get().sourceDuration(store.account(), region, location, source, channel);
            JsonNode clip = program.path("ScheduleConfiguration").path("ClipRange");
            long start = clip.path("StartOffsetMillis").asLong(0);
            long end = clip.hasNonNull("EndOffsetMillis") ? clip.get("EndOffsetMillis").asLong() : full;
            if (start < 0 || end > full || end <= start) {
                throw MediaTailorStore.badRequest("The clip range must lie within the source's " + full + " ms.");
            }
            duration = end - start;
        } else {
            if (!"STANDARD".equals(channel.path("Tier").asText())) {
                throw MediaTailorStore.badRequest("Only STANDARD channels play live sources.");
            }
            source(region, MediaTailorStore.LIVE, locationName, program.path("LiveSourceName").asText());
            if (!transition.hasNonNull("DurationMillis") || transition.get("DurationMillis").asLong() <= 0) {
                throw MediaTailorStore.badRequest("A live source program needs Transition.DurationMillis.");
            }
            duration = transition.get("DurationMillis").asLong();
        }
        for (JsonNode adBreak : program.path("AdBreaks")) {
            long offset = adBreak.path("OffsetMillis").asLong(-1);
            if (offset < 0 || offset > duration) {
                throw MediaTailorStore.badRequest("An ad break's OffsetMillis must lie within the program.");
            }
            JsonNode slate = adBreak.path("Slate");
            if (slate.isObject() && !slate.isEmpty()) {
                source(region, MediaTailorStore.VOD, slate.path("SourceLocationName").asText(""),
                        slate.path("VodSourceName").asText(""));
            }
        }
        for (JsonNode media : program.path("AudienceMedia")) {
            if (!audiences(channel).contains(media.path("Audience").asText())) {
                throw MediaTailorStore.badRequest("The channel has no audience " + media.path("Audience").asText()
                        + ".");
            }
        }
        program.put("DurationMillis", duration);
        if (program.path("ScheduleConfiguration").has("ClipRange")) {
            program.set("ClipRange", program.path("ScheduleConfiguration").get("ClipRange").deepCopy());
        }
    }

    public ObjectNode createProgram(JsonNode in, String region) {
        String channelName = required(in, "ChannelName");
        String name = required(in, "ProgramName");
        ObjectNode channel = channel(region, channelName);
        if (store.get(region, MediaTailorStore.PROGRAM, programKey(channelName, name)).isPresent()) {
            throw MediaTailorStore.badRequest("A program named " + name + " already exists on " + channelName + ".");
        }
        required(in, "SourceLocationName");
        if (!in.path("ScheduleConfiguration").isObject()) {
            throw MediaTailorStore.badRequest("ScheduleConfiguration is required.");
        }
        ObjectNode program = store.record();
        MediaTailorStore.copy(in, program, "AdBreaks", "LiveSourceName", "SourceLocationName", "VodSourceName",
                "ScheduleConfiguration", "AudienceMedia");
        program.put("ChannelName", channelName);
        program.put("ProgramName", name);
        program.put("Arn", store.arn(region, "program/" + channelName + "/" + name));
        checkProgram(in, channel, program, region);
        MediaTailorStore.addTags(program, in.path("Tags"));
        stamp(program);
        channels.get().place(store.account(), region, channel, program);
        store.put(region, MediaTailorStore.PROGRAM, programKey(channelName, name), program);
        store.put(region, MediaTailorStore.CHANNEL, channelName, channel);
        return programView(region, channel, program);
    }

    private ObjectNode programView(String region, ObjectNode channel, ObjectNode program) {
        ObjectNode view = view(program);
        view.remove("ScheduleConfiguration");
        Double start = channels.get().scheduledStart(store.account(), region, channel, program);
        if (start != null) {
            view.put("ScheduledStartTime", start);
        }
        return view;
    }

    public ObjectNode describeProgram(JsonNode in, String region) {
        String channelName = required(in, "ChannelName");
        ObjectNode program = program(region, channelName, required(in, "ProgramName"));
        return programView(region, channel(region, channelName), program);
    }

    public ObjectNode updateProgram(JsonNode in, String region) {
        String channelName = required(in, "ChannelName");
        String name = required(in, "ProgramName");
        ObjectNode program = program(region, channelName, name);
        ObjectNode channel = channel(region, channelName);
        ObjectNode updated = program.deepCopy();
        if (in.has("AdBreaks")) {
            updated.set("AdBreaks", in.get("AdBreaks").deepCopy());
        }
        if (in.has("AudienceMedia")) {
            updated.set("AudienceMedia", in.get("AudienceMedia").deepCopy());
        }
        JsonNode schedule = in.path("ScheduleConfiguration");
        if (schedule.isObject()) {
            ObjectNode current = (ObjectNode) updated.path("ScheduleConfiguration");
            ObjectNode transition = (ObjectNode) current.path("Transition");
            JsonNode change = schedule.path("Transition");
            if (change.hasNonNull("ScheduledStartTimeMillis")) {
                transition.set("ScheduledStartTimeMillis", change.get("ScheduledStartTimeMillis"));
            }
            if (change.hasNonNull("DurationMillis")) {
                transition.set("DurationMillis", change.get("DurationMillis"));
            }
            if (schedule.has("ClipRange")) {
                current.set("ClipRange", schedule.get("ClipRange").deepCopy());
            }
        }
        checkProgram(in, channel, updated, region);
        stamp(updated);
        store.put(region, MediaTailorStore.PROGRAM, programKey(channelName, name), updated);
        channels.get().sourceChanged(store.account(), region, updated.path("SourceLocationName").asText());
        ObjectNode out = programView(region, channel, updated);
        out.remove("Tags");
        return out;
    }

    public ObjectNode deleteProgram(JsonNode in, String region) {
        String channelName = required(in, "ChannelName");
        String name = required(in, "ProgramName");
        program(region, channelName, name);
        ObjectNode channel = channel(region, channelName);
        store.delete(region, MediaTailorStore.PROGRAM, programKey(channelName, name));
        channels.get().unplace(channel, name);
        store.put(region, MediaTailorStore.CHANNEL, channelName, channel);
        return mapper.createObjectNode();
    }

    // ================================================================ alerts and tags

    public ObjectNode listAlerts(JsonNode in, String region) {
        String arn = required(in, "ResourceArn");
        String kind = resourceKind(arn);
        if (!Set.of("channel", "program", "sourceLocation", "vodSource", "liveSource").contains(kind)) {
            throw MediaTailorStore.badRequest("ListAlerts takes the ARN of a channel assembly resource: a channel, "
                    + "program, source location, VOD source or live source.");
        }
        tagged(region, arn);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode alert : store.list(region, MediaTailorStore.ALERT)) {
            if (arn.equals(alert.path("ResourceArn").asText())) {
                items.add(MediaTailorStore.view(alert));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaTailorStore.page(items, in, out, "Items", 100);
        return out;
    }

    private static String resourceKind(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            int slash = resource.indexOf('/');
            return slash < 0 ? resource : resource.substring(0, slash);
        } catch (RuntimeException e) {
            throw MediaTailorStore.badRequest("The ARN " + arn + " isn't valid.");
        }
    }

    /** The record an ARN names, with its store kind and key. */
    private Object[] tagged(String region, String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (RuntimeException e) {
            throw MediaTailorStore.badRequest("The ARN " + arn + " isn't valid.");
        }
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw MediaTailorStore.badRequest("The ARN " + arn + " doesn't name a MediaTailor resource.");
        }
        String type = resource.substring(0, slash);
        String id = resource.substring(slash + 1);
        String kind = switch (type) {
            case "playbackConfiguration" -> MediaTailorStore.CONFIG;
            case "prefetchSchedule" -> MediaTailorStore.PREFETCH;
            case "function" -> MediaTailorStore.FUNCTION;
            case "sourceLocation" -> MediaTailorStore.LOCATION;
            case "vodSource" -> MediaTailorStore.VOD;
            case "liveSource" -> MediaTailorStore.LIVE;
            case "channel" -> MediaTailorStore.CHANNEL;
            case "program" -> MediaTailorStore.PROGRAM;
            default -> throw MediaTailorStore.badRequest("The ARN " + arn + " doesn't name a MediaTailor resource.");
        };
        ObjectNode record = store.get(region, kind, id).orElseThrow(() -> MediaTailorStore.notFound("Resource "
                + arn + " not found."));
        return new Object[] {kind, id, record};
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "ResourceArn"));
        MediaTailorStore.addTags((ObjectNode) tagged[2], in.path("Tags"));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "ResourceArn"));
        in.path("TagKeys").forEach(key -> MediaTailorStore.tags((ObjectNode) tagged[2]).remove(key.asText()));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", MediaTailorStore.tags((ObjectNode) tagged(region, required(in, "ResourceArn"))[2])
                .deepCopy());
        return out;
    }
}
