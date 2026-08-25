package io.github.hectorvent.floci.services.mediapackagev2;

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
import io.github.hectorvent.floci.services.mediapackagev2.model.Channel;
import io.github.hectorvent.floci.services.mediapackagev2.model.ChannelGroup;
import io.github.hectorvent.floci.services.mediapackagev2.model.HarvestJob;
import io.github.hectorvent.floci.services.mediapackagev2.model.OriginEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Elemental MediaPackage v2 restJson1 — channel groups, channels, origin
 * endpoints, harvest jobs, and tags.
 *
 * <p>Public paths are {@code /channelGroup} and peers. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code mediapackagev2}.
 */
@ApplicationScoped
public class MediaPackageV2Service implements TagHandler {

    static final String SERVICE = "mediapackagev2";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Set<String> INPUT_TYPES = Set.of("HLS", "CMAF");
    private static final Set<String> CONTAINER_TYPES = Set.of("TS", "CMAF", "ISM");
    private static final TypeReference<Map<String, ChannelGroup>> GROUP_MAP = new GroupMap();
    private static final TypeReference<Map<String, Channel>> CHANNEL_MAP = new ChannelMap();
    private static final TypeReference<Map<String, OriginEndpoint>> ENDPOINT_MAP = new EndpointMap();
    private static final TypeReference<Map<String, HarvestJob>> HARVEST_MAP = new HarvestMap();

    private static final class GroupMap extends TypeReference<Map<String, ChannelGroup>> {
    }

    private static final class ChannelMap extends TypeReference<Map<String, Channel>> {
    }

    private static final class EndpointMap extends TypeReference<Map<String, OriginEndpoint>> {
    }

    private static final class HarvestMap extends TypeReference<Map<String, HarvestJob>> {
    }

    private final StorageBackend<String, ChannelGroup> groups;
    private final StorageBackend<String, Channel> channels;
    private final StorageBackend<String, OriginEndpoint> endpoints;
    private final StorageBackend<String, HarvestJob> harvestJobs;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaPackageV2Service(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("mediapackagev2", "mediapackagev2-channel-groups.json", GROUP_MAP),
                storageFactory.create("mediapackagev2", "mediapackagev2-channels.json", CHANNEL_MAP),
                storageFactory.create("mediapackagev2", "mediapackagev2-origin-endpoints.json", ENDPOINT_MAP),
                storageFactory.create("mediapackagev2", "mediapackagev2-harvest-jobs.json", HARVEST_MAP),
                regionResolver, objectMapper);
    }

    MediaPackageV2Service(
            StorageBackend<String, ChannelGroup> groups,
            StorageBackend<String, Channel> channels,
            StorageBackend<String, OriginEndpoint> endpoints,
            StorageBackend<String, HarvestJob> harvestJobs,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.groups = groups;
        this.channels = channels;
        this.endpoints = endpoints;
        this.harvestJobs = harvestJobs;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ChannelGroup createChannelGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireName(request, "ChannelGroupName");
        String key = groupKey(region, name);
        if (groups.get(key).isPresent()) {
            throw conflict("Channel group " + name + " already exists.");
        }
        long now = now();
        ChannelGroup group = new ChannelGroup();
        group.setChannelGroupName(name);
        group.setArn(arn(region, "channelGroup/" + name));
        group.setEgressDomain(egressDomain(region, name));
        group.setDescription(optionalText(request, "Description"));
        group.setRegion(region);
        group.setETag(newEtag());
        group.setCreatedAt(now);
        group.setModifiedAt(now);
        group.setTags(readTags(request));
        groups.put(key, group);
        return group;
    }

    public ChannelGroup getChannelGroup(String region, String channelGroupName) {
        return requireGroup(region, channelGroupName);
    }

    public synchronized ChannelGroup updateChannelGroup(String region, String channelGroupName, JsonNode request) {
        requireObject(request, "Request body");
        ChannelGroup group = requireGroup(region, channelGroupName);
        if (request.has("Description") || request.has("description")) {
            group.setDescription(optionalText(request, "Description"));
        }
        group.setModifiedAt(now());
        group.setETag(newEtag());
        groups.put(groupKey(region, group.getChannelGroupName()), group);
        return group;
    }

    public synchronized void deleteChannelGroup(String region, String channelGroupName) {
        if (lookupGroup(region, channelGroupName) == null) {
            return;
        }
        if (!listChannels(region, channelGroupName).isEmpty()) {
            throw conflict("Channel group " + channelGroupName + " still has channels.");
        }
        groups.delete(groupKey(region, channelGroupName));
    }

    public List<ChannelGroup> listChannelGroups(String region) {
        List<ChannelGroup> result = new ArrayList<>();
        for (ChannelGroup group : groups.values()) {
            if (region.equals(group.getRegion())) {
                result.add(group);
            }
        }
        result.sort(Comparator.comparing(ChannelGroup::getChannelGroupName,
                Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized Channel createChannel(String region, String channelGroupName, JsonNode request) {
        requireObject(request, "Request body");
        ChannelGroup group = requireGroup(region, channelGroupName);
        String name = requireName(request, "ChannelName");
        String key = channelKey(region, channelGroupName, name);
        if (channels.get(key).isPresent()) {
            throw conflict("Channel " + name + " already exists.");
        }
        String inputType = optionalText(request, "InputType");
        if (inputType == null) {
            inputType = "HLS";
        }
        requireEnum(inputType, INPUT_TYPES, "InputType");
        long now = now();
        Channel channel = new Channel();
        channel.setChannelGroupName(group.getChannelGroupName());
        channel.setChannelName(name);
        channel.setArn(arn(region, "channelGroup/" + channelGroupName + "/channel/" + name));
        channel.setDescription(optionalText(request, "Description"));
        channel.setInputType(inputType);
        channel.setRegion(region);
        channel.setETag(newEtag());
        channel.setCreatedAt(now);
        channel.setModifiedAt(now);
        channel.setInputSwitchConfiguration(copyNode(node(request, "InputSwitchConfiguration")));
        channel.setOutputHeaderConfiguration(copyNode(node(request, "OutputHeaderConfiguration")));
        channel.setTags(readTags(request));
        channels.put(key, channel);
        return channel;
    }

    public Channel getChannel(String region, String channelGroupName, String channelName) {
        return requireChannel(region, channelGroupName, channelName);
    }

    public synchronized Channel updateChannel(
            String region, String channelGroupName, String channelName, JsonNode request) {
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, channelGroupName, channelName);
        if (request.has("Description") || request.has("description")) {
            channel.setDescription(optionalText(request, "Description"));
        }
        if (node(request, "InputSwitchConfiguration") != null) {
            channel.setInputSwitchConfiguration(copyNode(node(request, "InputSwitchConfiguration")));
        }
        if (node(request, "OutputHeaderConfiguration") != null) {
            channel.setOutputHeaderConfiguration(copyNode(node(request, "OutputHeaderConfiguration")));
        }
        channel.setModifiedAt(now());
        channel.setETag(newEtag());
        channels.put(channelKey(region, channelGroupName, channelName), channel);
        return channel;
    }

    public synchronized void deleteChannel(String region, String channelGroupName, String channelName) {
        if (lookupChannel(region, channelGroupName, channelName) == null) {
            return;
        }
        if (!listOriginEndpoints(region, channelGroupName, channelName).isEmpty()) {
            throw conflict("Channel " + channelName + " still has origin endpoints.");
        }
        channels.delete(channelKey(region, channelGroupName, channelName));
    }

    public List<Channel> listChannels(String region, String channelGroupName) {
        requireGroup(region, channelGroupName);
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels.values()) {
            if (region.equals(channel.getRegion()) && channelGroupName.equals(channel.getChannelGroupName())) {
                result.add(channel);
            }
        }
        result.sort(Comparator.comparing(Channel::getChannelName, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized OriginEndpoint createOriginEndpoint(
            String region, String channelGroupName, String channelName, JsonNode request) {
        requireObject(request, "Request body");
        requireChannel(region, channelGroupName, channelName);
        String name = requireName(request, "OriginEndpointName");
        String containerType = requireText(request, "ContainerType");
        requireEnum(containerType, CONTAINER_TYPES, "ContainerType");
        String key = endpointKey(region, channelGroupName, channelName, name);
        if (endpoints.get(key).isPresent()) {
            throw conflict("Origin endpoint " + name + " already exists.");
        }
        long now = now();
        OriginEndpoint endpoint = new OriginEndpoint();
        endpoint.setChannelGroupName(channelGroupName);
        endpoint.setChannelName(channelName);
        endpoint.setOriginEndpointName(name);
        endpoint.setArn(arn(region, "channelGroup/" + channelGroupName + "/channel/" + channelName
                + "/originEndpoint/" + name));
        endpoint.setContainerType(containerType);
        endpoint.setDescription(optionalText(request, "Description"));
        endpoint.setRegion(region);
        endpoint.setETag(newEtag());
        endpoint.setUriSeparator(optionalText(request, "UriSeparator"));
        endpoint.setStartoverWindowSeconds(optionalInt(request, "StartoverWindowSeconds"));
        endpoint.setCreatedAt(now);
        endpoint.setModifiedAt(now);
        JsonNode segment = copyNode(node(request, "Segment"));
        if (segment == null) {
            ObjectNode defaultSegment = objectMapper.createObjectNode();
            defaultSegment.put("SegmentDurationSeconds", 6);
            segment = defaultSegment;
        }
        endpoint.setSegment(segment);
        endpoint.setHlsManifests(copyNode(node(request, "HlsManifests")));
        endpoint.setLowLatencyHlsManifests(copyNode(node(request, "LowLatencyHlsManifests")));
        endpoint.setDashManifests(copyNode(node(request, "DashManifests")));
        endpoint.setMssManifests(copyNode(node(request, "MssManifests")));
        endpoint.setForceEndpointErrorConfiguration(copyNode(node(request, "ForceEndpointErrorConfiguration")));
        endpoint.setTags(readTags(request));
        endpoints.put(key, endpoint);
        return endpoint;
    }

    public OriginEndpoint getOriginEndpoint(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        return requireEndpoint(region, channelGroupName, channelName, originEndpointName);
    }

    public synchronized OriginEndpoint updateOriginEndpoint(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            JsonNode request) {
        requireObject(request, "Request body");
        OriginEndpoint endpoint = requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        if (request.has("ContainerType") || request.has("containerType")) {
            String containerType = requireText(request, "ContainerType");
            requireEnum(containerType, CONTAINER_TYPES, "ContainerType");
            endpoint.setContainerType(containerType);
        }
        if (request.has("Description") || request.has("description")) {
            endpoint.setDescription(optionalText(request, "Description"));
        }
        if (request.has("StartoverWindowSeconds") || request.has("startoverWindowSeconds")) {
            endpoint.setStartoverWindowSeconds(optionalInt(request, "StartoverWindowSeconds"));
        }
        if (request.has("UriSeparator") || request.has("uriSeparator")) {
            endpoint.setUriSeparator(optionalText(request, "UriSeparator"));
        }
        if (node(request, "Segment") != null) {
            endpoint.setSegment(copyNode(node(request, "Segment")));
        }
        if (node(request, "HlsManifests") != null) {
            endpoint.setHlsManifests(copyNode(node(request, "HlsManifests")));
        }
        if (node(request, "LowLatencyHlsManifests") != null) {
            endpoint.setLowLatencyHlsManifests(copyNode(node(request, "LowLatencyHlsManifests")));
        }
        if (node(request, "DashManifests") != null) {
            endpoint.setDashManifests(copyNode(node(request, "DashManifests")));
        }
        if (node(request, "MssManifests") != null) {
            endpoint.setMssManifests(copyNode(node(request, "MssManifests")));
        }
        if (node(request, "ForceEndpointErrorConfiguration") != null) {
            endpoint.setForceEndpointErrorConfiguration(copyNode(node(request, "ForceEndpointErrorConfiguration")));
        }
        endpoint.setModifiedAt(now());
        endpoint.setETag(newEtag());
        endpoints.put(endpointKey(region, channelGroupName, channelName, originEndpointName), endpoint);
        return endpoint;
    }

    public synchronized void deleteOriginEndpoint(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        if (lookupEndpoint(region, channelGroupName, channelName, originEndpointName) == null) {
            return;
        }
        endpoints.delete(endpointKey(region, channelGroupName, channelName, originEndpointName));
    }

    public List<OriginEndpoint> listOriginEndpoints(String region, String channelGroupName, String channelName) {
        requireChannel(region, channelGroupName, channelName);
        List<OriginEndpoint> result = new ArrayList<>();
        for (OriginEndpoint endpoint : endpoints.values()) {
            if (region.equals(endpoint.getRegion())
                    && channelGroupName.equals(endpoint.getChannelGroupName())
                    && channelName.equals(endpoint.getChannelName())) {
                result.add(endpoint);
            }
        }
        result.sort(Comparator.comparing(OriginEndpoint::getOriginEndpointName,
                Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized void putChannelPolicy(
            String region, String channelGroupName, String channelName, JsonNode request) {
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, channelGroupName, channelName);
        channel.setPolicy(requireText(request, "Policy"));
        channel.setModifiedAt(now());
        channel.setETag(newEtag());
        channels.put(channelKey(region, channelGroupName, channelName), channel);
    }

    public String getChannelPolicy(String region, String channelGroupName, String channelName) {
        Channel channel = requireChannel(region, channelGroupName, channelName);
        if (channel.getPolicy() == null || channel.getPolicy().isBlank()) {
            throw notFound("Channel policy not found.");
        }
        return channel.getPolicy();
    }

    public synchronized void deleteChannelPolicy(String region, String channelGroupName, String channelName) {
        Channel channel = lookupChannel(region, channelGroupName, channelName);
        if (channel == null) {
            return;
        }
        channel.setPolicy(null);
        channel.setModifiedAt(now());
        channel.setETag(newEtag());
        channels.put(channelKey(region, channelGroupName, channelName), channel);
    }

    public synchronized void putOriginEndpointPolicy(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            JsonNode request) {
        requireObject(request, "Request body");
        OriginEndpoint endpoint = requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        endpoint.setPolicy(requireText(request, "Policy"));
        endpoint.setCdnAuthConfiguration(copyNode(node(request, "CdnAuthConfiguration")));
        endpoint.setModifiedAt(now());
        endpoint.setETag(newEtag());
        endpoints.put(endpointKey(region, channelGroupName, channelName, originEndpointName), endpoint);
    }

    public OriginEndpoint getOriginEndpointPolicy(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        OriginEndpoint endpoint = requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        if (endpoint.getPolicy() == null || endpoint.getPolicy().isBlank()) {
            throw notFound("Origin endpoint policy not found.");
        }
        return endpoint;
    }

    public synchronized void deleteOriginEndpointPolicy(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        OriginEndpoint endpoint = lookupEndpoint(region, channelGroupName, channelName, originEndpointName);
        if (endpoint == null) {
            return;
        }
        endpoint.setPolicy(null);
        endpoint.setCdnAuthConfiguration(null);
        endpoint.setModifiedAt(now());
        endpoint.setETag(newEtag());
        endpoints.put(endpointKey(region, channelGroupName, channelName, originEndpointName), endpoint);
    }

    public synchronized long resetChannelState(String region, String channelGroupName, String channelName) {
        Channel channel = requireChannel(region, channelGroupName, channelName);
        long resetAt = now();
        channel.setModifiedAt(resetAt);
        channel.setETag(newEtag());
        channels.put(channelKey(region, channelGroupName, channelName), channel);
        return resetAt;
    }

    public synchronized long resetOriginEndpointState(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        OriginEndpoint endpoint = requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        long resetAt = now();
        endpoint.setModifiedAt(resetAt);
        endpoint.setETag(newEtag());
        endpoints.put(endpointKey(region, channelGroupName, channelName, originEndpointName), endpoint);
        return resetAt;
    }

    public synchronized HarvestJob createHarvestJob(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            JsonNode request) {
        requireObject(request, "Request body");
        requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        String name = optionalText(request, "HarvestJobName");
        if (name == null) {
            name = "harvest-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } else {
            requireNameValue(name);
        }
        String key = harvestKey(region, channelGroupName, channelName, originEndpointName, name);
        if (harvestJobs.get(key).isPresent()) {
            throw conflict("Harvest job " + name + " already exists.");
        }
        JsonNode destination = copyNode(node(request, "Destination"));
        JsonNode harvested = copyNode(node(request, "HarvestedManifests"));
        JsonNode schedule = copyNode(node(request, "ScheduleConfiguration"));
        if (destination == null || harvested == null || schedule == null) {
            throw validation("Destination, HarvestedManifests, and ScheduleConfiguration are required.");
        }
        long now = now();
        HarvestJob job = new HarvestJob();
        job.setChannelGroupName(channelGroupName);
        job.setChannelName(channelName);
        job.setOriginEndpointName(originEndpointName);
        job.setHarvestJobName(name);
        job.setArn(arn(region, "channelGroup/" + channelGroupName + "/channel/" + channelName
                + "/originEndpoint/" + originEndpointName + "/harvestJob/" + name));
        job.setDescription(optionalText(request, "Description"));
        job.setRegion(region);
        job.setETag(newEtag());
        job.setStatus("COMPLETED");
        job.setCreatedAt(now);
        job.setModifiedAt(now);
        job.setDestination(destination);
        job.setHarvestedManifests(harvested);
        job.setScheduleConfiguration(schedule);
        job.setTags(readTags(request));
        harvestJobs.put(key, job);
        return job;
    }

    public HarvestJob getHarvestJob(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            String harvestJobName) {
        return requireHarvestJob(region, channelGroupName, channelName, originEndpointName, harvestJobName);
    }

    public synchronized HarvestJob cancelHarvestJob(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            String harvestJobName) {
        HarvestJob job = requireHarvestJob(region, channelGroupName, channelName, originEndpointName, harvestJobName);
        job.setStatus("CANCELLED");
        job.setModifiedAt(now());
        job.setETag(newEtag());
        harvestJobs.put(harvestKey(region, channelGroupName, channelName, originEndpointName, harvestJobName), job);
        return job;
    }

    public List<HarvestJob> listHarvestJobs(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        requireGroup(region, channelGroupName);
        List<HarvestJob> result = new ArrayList<>();
        for (HarvestJob job : harvestJobs.values()) {
            if (!region.equals(job.getRegion()) || !channelGroupName.equals(job.getChannelGroupName())) {
                continue;
            }
            if (channelName != null && !channelName.equals(job.getChannelName())) {
                continue;
            }
            if (originEndpointName != null && !originEndpointName.equals(job.getOriginEndpointName())) {
                continue;
            }
            result.add(job);
        }
        result.sort(Comparator.comparing(HarvestJob::getHarvestJobName, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public ObjectNode toChannelGroup(ChannelGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelGroupName", group.getChannelGroupName());
        node.put("Arn", group.getArn());
        node.put("EgressDomain", group.getEgressDomain());
        node.put("CreatedAt", group.getCreatedAt());
        node.put("ModifiedAt", group.getModifiedAt());
        putText(node, "Description", group.getDescription());
        putText(node, "ETag", group.getETag());
        putTags(node, group.getTags());
        return node;
    }

    public ObjectNode toListedChannelGroup(ChannelGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelGroupName", group.getChannelGroupName());
        node.put("Arn", group.getArn());
        node.put("CreatedAt", group.getCreatedAt());
        node.put("ModifiedAt", group.getModifiedAt());
        putText(node, "Description", group.getDescription());
        return node;
    }

    public ObjectNode toChannel(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", channel.getArn());
        node.put("ChannelName", channel.getChannelName());
        node.put("ChannelGroupName", channel.getChannelGroupName());
        node.put("CreatedAt", channel.getCreatedAt());
        node.put("ModifiedAt", channel.getModifiedAt());
        putText(node, "Description", channel.getDescription());
        putText(node, "InputType", channel.getInputType());
        putText(node, "ETag", channel.getETag());
        ArrayNode ingest = node.putArray("IngestEndpoints");
        ChannelGroup group = lookupGroup(channel.getRegion(), channel.getChannelGroupName());
        String domain = group != null ? group.getEgressDomain() : egressDomain(channel.getRegion(), channel.getChannelGroupName());
        String ingestHost = domain.replace(".egress.", ".ingest.");
        for (int i = 1; i <= 2; i++) {
            ObjectNode endpoint = ingest.addObject();
            endpoint.put("Id", String.valueOf(i));
            endpoint.put("Url", "https://" + ingestHost + "/" + channel.getChannelGroupName() + "/"
                    + channel.getChannelName() + "/" + i + "/index");
        }
        if (channel.getInputSwitchConfiguration() != null) {
            node.set("InputSwitchConfiguration", channel.getInputSwitchConfiguration());
        }
        if (channel.getOutputHeaderConfiguration() != null) {
            node.set("OutputHeaderConfiguration", channel.getOutputHeaderConfiguration());
        }
        putTags(node, channel.getTags());
        return node;
    }

    public ObjectNode toListedChannel(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", channel.getArn());
        node.put("ChannelName", channel.getChannelName());
        node.put("ChannelGroupName", channel.getChannelGroupName());
        node.put("CreatedAt", channel.getCreatedAt());
        node.put("ModifiedAt", channel.getModifiedAt());
        putText(node, "Description", channel.getDescription());
        putText(node, "InputType", channel.getInputType());
        return node;
    }

    public ObjectNode toOriginEndpoint(OriginEndpoint endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", endpoint.getArn());
        node.put("ChannelGroupName", endpoint.getChannelGroupName());
        node.put("ChannelName", endpoint.getChannelName());
        node.put("OriginEndpointName", endpoint.getOriginEndpointName());
        node.put("ContainerType", endpoint.getContainerType());
        if (endpoint.getSegment() != null) {
            node.set("Segment", endpoint.getSegment());
        } else {
            node.set("Segment", objectMapper.createObjectNode());
        }
        node.put("CreatedAt", endpoint.getCreatedAt());
        node.put("ModifiedAt", endpoint.getModifiedAt());
        putText(node, "Description", endpoint.getDescription());
        if (endpoint.getStartoverWindowSeconds() != null) {
            node.put("StartoverWindowSeconds", endpoint.getStartoverWindowSeconds());
        }
        putText(node, "UriSeparator", endpoint.getUriSeparator());
        putText(node, "ETag", endpoint.getETag());
        ChannelGroup group = lookupGroup(endpoint.getRegion(), endpoint.getChannelGroupName());
        String egress = group != null
                ? group.getEgressDomain()
                : egressDomain(endpoint.getRegion(), endpoint.getChannelGroupName());
        setManifests(node, "HlsManifests", endpoint.getHlsManifests(), egress, endpoint, ".m3u8");
        setManifests(node, "LowLatencyHlsManifests", endpoint.getLowLatencyHlsManifests(), egress, endpoint, ".m3u8");
        setManifests(node, "DashManifests", endpoint.getDashManifests(), egress, endpoint, ".mpd");
        setManifests(node, "MssManifests", endpoint.getMssManifests(), egress, endpoint, ".ism/Manifest");
        if (endpoint.getForceEndpointErrorConfiguration() != null) {
            node.set("ForceEndpointErrorConfiguration", endpoint.getForceEndpointErrorConfiguration());
        }
        putTags(node, endpoint.getTags());
        return node;
    }

    public ObjectNode toListedOriginEndpoint(OriginEndpoint endpoint) {
        ObjectNode node = toOriginEndpoint(endpoint);
        node.remove("Segment");
        node.remove("StartoverWindowSeconds");
        node.remove("ETag");
        node.remove("tags");
        node.remove("Tags");
        return node;
    }

    public ObjectNode toHarvestJob(HarvestJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelGroupName", job.getChannelGroupName());
        node.put("ChannelName", job.getChannelName());
        node.put("OriginEndpointName", job.getOriginEndpointName());
        node.put("HarvestJobName", job.getHarvestJobName());
        node.put("Arn", job.getArn());
        node.put("CreatedAt", job.getCreatedAt());
        node.put("ModifiedAt", job.getModifiedAt());
        node.put("Status", job.getStatus());
        putText(node, "Description", job.getDescription());
        putText(node, "ErrorMessage", job.getErrorMessage());
        putText(node, "ETag", job.getETag());
        if (job.getDestination() != null) {
            node.set("Destination", job.getDestination());
        }
        if (job.getHarvestedManifests() != null) {
            node.set("HarvestedManifests", job.getHarvestedManifests());
        }
        if (job.getScheduleConfiguration() != null) {
            node.set("ScheduleConfiguration", job.getScheduleConfiguration());
        }
        putTags(node, job.getTags());
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
        tagged.store();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        tagged.setTags(current);
        tagged.store();
    }

    private ChannelGroup requireGroup(String region, String channelGroupName) {
        ChannelGroup group = lookupGroup(region, channelGroupName);
        if (group == null) {
            throw notFound("Channel group " + channelGroupName + " was not found.");
        }
        return group;
    }

    private ChannelGroup lookupGroup(String region, String channelGroupName) {
        requireNameValue(channelGroupName);
        return groups.get(groupKey(region, channelGroupName)).orElse(null);
    }

    private Channel requireChannel(String region, String channelGroupName, String channelName) {
        requireGroup(region, channelGroupName);
        Channel channel = lookupChannel(region, channelGroupName, channelName);
        if (channel == null) {
            throw notFound("Channel " + channelName + " was not found.");
        }
        return channel;
    }

    private Channel lookupChannel(String region, String channelGroupName, String channelName) {
        requireNameValue(channelName);
        return channels.get(channelKey(region, channelGroupName, channelName)).orElse(null);
    }

    private OriginEndpoint requireEndpoint(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        requireChannel(region, channelGroupName, channelName);
        OriginEndpoint endpoint = lookupEndpoint(region, channelGroupName, channelName, originEndpointName);
        if (endpoint == null) {
            throw notFound("Origin endpoint " + originEndpointName + " was not found.");
        }
        return endpoint;
    }

    private OriginEndpoint lookupEndpoint(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        requireNameValue(originEndpointName);
        return endpoints.get(endpointKey(region, channelGroupName, channelName, originEndpointName)).orElse(null);
    }

    private HarvestJob requireHarvestJob(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            String harvestJobName) {
        requireEndpoint(region, channelGroupName, channelName, originEndpointName);
        HarvestJob job = harvestJobs.get(
                harvestKey(region, channelGroupName, channelName, originEndpointName, harvestJobName)).orElse(null);
        if (job == null) {
            throw notFound("Harvest job " + harvestJobName + " was not found.");
        }
        return job;
    }

    private Tagged requireTagged(String region, String arn) {
        String decoded = arn == null ? "" : arn;
        for (ChannelGroup group : groups.values()) {
            if (decoded.equals(group.getArn()) && region.equals(group.getRegion())) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return group.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        group.setTags(tags);
                        group.setModifiedAt(now());
                        group.setETag(newEtag());
                    }

                    @Override
                    public void store() {
                        groups.put(groupKey(region, group.getChannelGroupName()), group);
                    }
                };
            }
        }
        for (Channel channel : channels.values()) {
            if (decoded.equals(channel.getArn()) && region.equals(channel.getRegion())) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return channel.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        channel.setTags(tags);
                        channel.setModifiedAt(now());
                        channel.setETag(newEtag());
                    }

                    @Override
                    public void store() {
                        channels.put(channelKey(region, channel.getChannelGroupName(), channel.getChannelName()),
                                channel);
                    }
                };
            }
        }
        for (OriginEndpoint endpoint : endpoints.values()) {
            if (decoded.equals(endpoint.getArn()) && region.equals(endpoint.getRegion())) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return endpoint.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        endpoint.setTags(tags);
                        endpoint.setModifiedAt(now());
                        endpoint.setETag(newEtag());
                    }

                    @Override
                    public void store() {
                        endpoints.put(endpointKey(region, endpoint.getChannelGroupName(), endpoint.getChannelName(),
                                endpoint.getOriginEndpointName()), endpoint);
                    }
                };
            }
        }
        for (HarvestJob job : harvestJobs.values()) {
            if (decoded.equals(job.getArn()) && region.equals(job.getRegion())) {
                return new Tagged() {
                    @Override
                    public Map<String, String> tags() {
                        return job.getTags();
                    }

                    @Override
                    public void setTags(Map<String, String> tags) {
                        job.setTags(tags);
                        job.setModifiedAt(now());
                        job.setETag(newEtag());
                    }

                    @Override
                    public void store() {
                        harvestJobs.put(harvestKey(region, job.getChannelGroupName(), job.getChannelName(),
                                job.getOriginEndpointName(), job.getHarvestJobName()), job);
                    }
                };
            }
        }
        throw notFound("Resource " + decoded + " was not found.");
    }

    private interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);

        void store();
    }

    private void setManifests(
            ObjectNode parent,
            String field,
            JsonNode manifests,
            String egressDomain,
            OriginEndpoint endpoint,
            String suffix) {
        if (manifests == null || !manifests.isArray() || manifests.isEmpty()) {
            return;
        }
        ArrayNode out = parent.putArray(field);
        for (JsonNode item : manifests) {
            ObjectNode copy = item.isObject() ? item.deepCopy() : objectMapper.createObjectNode();
            String manifestName = textOrNull(copy, "ManifestName");
            if (manifestName == null) {
                manifestName = "index";
                copy.put("ManifestName", manifestName);
            }
            if (!copy.hasNonNull("Url")) {
                copy.put("Url", "https://" + egressDomain + "/out/v1/" + endpoint.getOriginEndpointName()
                        + "/" + manifestName + suffix);
            }
            out.add(copy);
        }
    }

    private JsonNode copyNode(JsonNode node) {
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    private Map<String, String> readTags(JsonNode request) {
        JsonNode tagsNode = node(request, "tags", "Tags");
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("Tags must be a string map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("Tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private void putTags(ObjectNode node, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode tagsNode = objectMapper.createObjectNode();
        tags.forEach(tagsNode::put);
        node.set("Tags", tagsNode);
        node.set("tags", tagsNode.deepCopy());
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw validation(label + " must be a JSON object.");
        }
    }

    private String requireName(JsonNode request, String field) {
        String value = requireText(request, field);
        requireNameValue(value);
        return value;
    }

    private static void requireNameValue(String value) {
        if (value == null || value.isBlank() || !NAME_PATTERN.matcher(value).matches()) {
            throw validation("Name must match ^[a-zA-Z0-9_-]+$.");
        }
    }

    private static void requireEnum(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        String value = textOrNull(parent, field);
        if (value != null) {
            return value;
        }
        if (field.isEmpty()) {
            return null;
        }
        String alt = Character.toLowerCase(field.charAt(0)) + field.substring(1);
        return alt.equals(field) ? null : textOrNull(parent, alt);
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        JsonNode value = node(parent, field);
        if (value == null) {
            String alt = Character.toLowerCase(field.charAt(0)) + field.substring(1);
            value = parent.get(alt);
        }
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
            if (field.isEmpty()) {
                continue;
            }
            String alt = Character.toLowerCase(field.charAt(0)) + field.substring(1);
            if (!alt.equals(field)) {
                value = parent.get(alt);
                if (value != null && !value.isNull()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private String egressDomain(String region, String name) {
        String id = Integer.toHexString(Math.abs((region + ":" + name).hashCode()));
        return id + ".egress.floci.mediapackagev2." + region + ".amazonaws.com";
    }

    private static String newEtag() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String groupKey(String region, String channelGroupName) {
        return region.toLowerCase(Locale.ROOT) + ":" + channelGroupName;
    }

    private static String channelKey(String region, String channelGroupName, String channelName) {
        return groupKey(region, channelGroupName) + ":" + channelName;
    }

    private static String endpointKey(
            String region, String channelGroupName, String channelName, String originEndpointName) {
        return channelKey(region, channelGroupName, channelName) + ":" + originEndpointName;
    }

    private static String harvestKey(
            String region,
            String channelGroupName,
            String channelName,
            String originEndpointName,
            String harvestJobName) {
        return endpointKey(region, channelGroupName, channelName, originEndpointName) + ":" + harvestJobName;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
