package io.github.hectorvent.floci.services.medialive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.medialive.model.Channel;
import io.github.hectorvent.floci.services.medialive.model.Input;
import io.github.hectorvent.floci.services.medialive.model.Input.Destination;
import io.github.hectorvent.floci.services.medialive.model.Input.Source;
import io.github.hectorvent.floci.services.medialive.model.InputSecurityGroup;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * AWS Elemental MediaLive restJson1 — input security groups, inputs, and
 * channels. Create leaves inputs {@code DETACHED} and channels {@code IDLE}.
 */
@ApplicationScoped
public class MediaLiveService {

    static final String SERVICE = "medialive";
    private static final String STATE_DETACHED = "DETACHED";
    private static final String STATE_ATTACHED = "ATTACHED";
    private static final String STATE_IDLE = "IDLE";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_IN_USE = "IN_USE";
    private static final String CLASS_SINGLE = "SINGLE_PIPELINE";
    private static final String CLASS_STANDARD = "STANDARD";
    private static final Set<String> PUSH_TYPES = Set.of("UDP_PUSH", "RTP_PUSH", "RTMP_PUSH");
    private static final TypeReference<Map<String, Input>> INPUT_MAP = new InputMap();
    private static final TypeReference<Map<String, Channel>> CHANNEL_MAP = new ChannelMap();
    private static final TypeReference<Map<String, InputSecurityGroup>> GROUP_MAP = new GroupMap();

    private static final class InputMap extends TypeReference<Map<String, Input>> {
    }

    private static final class ChannelMap extends TypeReference<Map<String, Channel>> {
    }

    private static final class GroupMap extends TypeReference<Map<String, InputSecurityGroup>> {
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    private final StorageBackend<String, Input> inputs;
    private final StorageBackend<String, Channel> channels;
    private final StorageBackend<String, InputSecurityGroup> groups;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaLiveService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("medialive", "medialive-inputs.json", INPUT_MAP),
                storageFactory.create("medialive", "medialive-channels.json", CHANNEL_MAP),
                storageFactory.create("medialive", "medialive-input-security-groups.json", GROUP_MAP),
                regionResolver, objectMapper);
    }

    MediaLiveService(
            StorageBackend<String, Input> inputs,
            StorageBackend<String, Channel> channels,
            StorageBackend<String, InputSecurityGroup> groups,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.inputs = inputs;
        this.channels = channels;
        this.groups = groups;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized InputSecurityGroup createInputSecurityGroup(String region, JsonNode request) {
        requireObject(request, "Request body");
        String id = newUniqueId(region, "isg");
        InputSecurityGroup group = new InputSecurityGroup();
        group.setId(id);
        group.setArn(arn(region, "inputSecurityGroup:" + id));
        group.setState(STATE_IDLE);
        group.setRegion(region);
        group.setWhitelistRules(cidrList(request.get("whitelistRules")));
        group.setTags(readTags(request.get("tags")));
        groups.put(storageKey(region, id), group);
        return group;
    }

    public InputSecurityGroup describeInputSecurityGroup(String region, String groupId) {
        return requireGroup(region, groupId);
    }

    public synchronized InputSecurityGroup updateInputSecurityGroup(
            String region, String groupId, JsonNode request) {
        requireObject(request, "Request body");
        InputSecurityGroup group = requireGroup(region, groupId);
        if (request.has("whitelistRules")) {
            group.setWhitelistRules(cidrList(request.get("whitelistRules")));
        }
        if (request.has("tags")) {
            group.setTags(readTags(request.get("tags")));
        }
        groups.put(storageKey(region, group.getId()), group);
        return group;
    }

    public synchronized void deleteInputSecurityGroup(String region, String groupId) {
        InputSecurityGroup group = requireGroup(region, groupId);
        if (!group.getInputs().isEmpty()) {
            throw badRequest("Input security group " + groupId + " is in use and cannot be deleted.");
        }
        groups.delete(storageKey(region, group.getId()));
    }

    public Page<InputSecurityGroup> listInputSecurityGroups(String region, String maxResults, String nextToken) {
        List<InputSecurityGroup> result = new ArrayList<>();
        for (InputSecurityGroup group : groups.values()) {
            if (region.equals(group.getRegion())) {
                result.add(group);
            }
        }
        result.sort(Comparator.comparing(InputSecurityGroup::getId, Comparator.nullsLast(String::compareTo)));
        return paginate(result, maxResults, nextToken);
    }

    public synchronized Input createInput(String region, JsonNode request) {
        requireObject(request, "Request body");
        String type = optionalText(request, "type");
        if (type == null) {
            type = "UDP_PUSH";
        }
        String name = optionalText(request, "name");
        if (name == null) {
            name = "input-" + newId();
        }
        String id = newUniqueId(region, "input");
        Input input = new Input();
        input.setId(id);
        input.setArn(arn(region, "input:" + id));
        input.setName(name);
        input.setType(type);
        input.setState(STATE_DETACHED);
        input.setRoleArn(optionalText(request, "roleArn"));
        input.setRegion(region);
        input.setSources(readSources(request.get("sources")));
        input.setMediaConnectFlows(flowArns(request.get("mediaConnectFlows")));
        input.setSecurityGroups(stringList(request.get("inputSecurityGroups")));
        input.setTags(readTags(request.get("tags")));
        input.setDestinations(resolveDestinations(type, request.get("destinations")));
        input.setInputClass(inferClass(input.getSources().size(), input.getDestinations().size()));
        attachGroupsToInput(region, input);
        inputs.put(storageKey(region, id), input);
        return input;
    }

    public Input describeInput(String region, String inputId) {
        return requireInput(region, inputId);
    }

    public synchronized Input updateInput(String region, String inputId, JsonNode request) {
        requireObject(request, "Request body");
        Input input = requireInput(region, inputId);
        if (request.hasNonNull("name")) {
            input.setName(requireText(request, "name"));
        }
        if (request.has("roleArn")) {
            input.setRoleArn(textOrNull(request, "roleArn"));
        }
        if (request.has("sources")) {
            input.setSources(readSources(request.get("sources")));
        }
        if (request.has("mediaConnectFlows")) {
            input.setMediaConnectFlows(flowArns(request.get("mediaConnectFlows")));
        }
        if (request.has("destinations")) {
            input.setDestinations(resolveDestinations(input.getType(), request.get("destinations")));
        }
        if (request.has("inputSecurityGroups")) {
            detachGroupsFromInput(region, input);
            input.setSecurityGroups(stringList(request.get("inputSecurityGroups")));
            attachGroupsToInput(region, input);
        }
        input.setInputClass(inferClass(input.getSources().size(), input.getDestinations().size()));
        inputs.put(storageKey(region, input.getId()), input);
        return input;
    }

    public synchronized void deleteInput(String region, String inputId) {
        Input input = requireInput(region, inputId);
        if (!input.getAttachedChannels().isEmpty()) {
            throw conflict("Input " + inputId + " is attached to a channel and cannot be deleted.");
        }
        detachGroupsFromInput(region, input);
        inputs.delete(storageKey(region, input.getId()));
    }

    public Page<Input> listInputs(String region, String maxResults, String nextToken) {
        List<Input> result = new ArrayList<>();
        for (Input input : inputs.values()) {
            if (region.equals(input.getRegion())) {
                result.add(input);
            }
        }
        result.sort(Comparator.comparing(Input::getId, Comparator.nullsLast(String::compareTo)));
        return paginate(result, maxResults, nextToken);
    }

    public synchronized Channel createChannel(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = optionalText(request, "name");
        if (name == null) {
            name = "channel-" + newId();
        }
        String channelClass = optionalText(request, "channelClass");
        if (channelClass == null) {
            channelClass = CLASS_STANDARD;
        }
        String id = newUniqueId(region, "channel");
        Channel channel = new Channel();
        channel.setId(id);
        channel.setArn(arn(region, "channel:" + id));
        channel.setName(name);
        channel.setState(STATE_IDLE);
        channel.setChannelClass(channelClass);
        channel.setRoleArn(optionalText(request, "roleArn"));
        String logLevel = optionalText(request, "logLevel");
        channel.setLogLevel(logLevel == null ? "DISABLED" : logLevel);
        channel.setRegion(region);
        channel.setPipelinesRunningCount(0);
        channel.setInputAttachments(request.get("inputAttachments"));
        channel.setEncoderSettings(request.get("encoderSettings"));
        channel.setDestinations(request.get("destinations"));
        channel.setInputSpecification(request.get("inputSpecification"));
        channel.setCdiInputSpecification(request.get("cdiInputSpecification"));
        channel.setMaintenance(request.get("maintenance"));
        channel.setScheduleActions(objectMapper.createArrayNode());
        channel.setTags(readTags(request.get("tags")));
        attachInputsToChannel(region, channel);
        channels.put(storageKey(region, id), channel);
        return channel;
    }

    public Channel describeChannel(String region, String channelId) {
        return requireChannel(region, channelId);
    }

    public synchronized Channel updateChannel(String region, String channelId, JsonNode request) {
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, channelId);
        if (STATE_RUNNING.equals(channel.getState())) {
            throw conflict("Channel " + channelId + " is running and cannot be updated.");
        }
        if (request.hasNonNull("name")) {
            channel.setName(requireText(request, "name"));
        }
        if (request.has("roleArn")) {
            channel.setRoleArn(textOrNull(request, "roleArn"));
        }
        if (request.hasNonNull("logLevel")) {
            channel.setLogLevel(requireText(request, "logLevel"));
        }
        if (request.has("destinations")) {
            channel.setDestinations(request.get("destinations"));
        }
        if (request.has("encoderSettings")) {
            channel.setEncoderSettings(request.get("encoderSettings"));
        }
        if (request.has("inputSpecification")) {
            channel.setInputSpecification(request.get("inputSpecification"));
        }
        if (request.has("cdiInputSpecification")) {
            channel.setCdiInputSpecification(request.get("cdiInputSpecification"));
        }
        if (request.has("maintenance")) {
            channel.setMaintenance(request.get("maintenance"));
        }
        if (request.has("inputAttachments")) {
            detachInputsFromChannel(region, channel);
            channel.setInputAttachments(request.get("inputAttachments"));
            attachInputsToChannel(region, channel);
        }
        channels.put(storageKey(region, channel.getId()), channel);
        return channel;
    }

    public synchronized void deleteChannel(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        if (STATE_RUNNING.equals(channel.getState())) {
            throw conflict("Channel " + channelId + " is running and cannot be deleted.");
        }
        detachInputsFromChannel(region, channel);
        channels.delete(storageKey(region, channel.getId()));
    }

    public Page<Channel> listChannels(String region, String maxResults, String nextToken) {
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels.values()) {
            if (region.equals(channel.getRegion())) {
                result.add(channel);
            }
        }
        result.sort(Comparator.comparing(Channel::getId, Comparator.nullsLast(String::compareTo)));
        return paginate(result, maxResults, nextToken);
    }

    public synchronized Channel startChannel(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        if (STATE_RUNNING.equals(channel.getState())) {
            throw conflict("Channel " + channelId + " is already running.");
        }
        channel.setState(STATE_RUNNING);
        channel.setPipelinesRunningCount(CLASS_STANDARD.equals(channel.getChannelClass()) ? 2 : 1);
        channels.put(storageKey(region, channel.getId()), channel);
        return channel;
    }

    public synchronized Channel stopChannel(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        if (STATE_IDLE.equals(channel.getState())) {
            throw conflict("Channel " + channelId + " is already idle.");
        }
        channel.setState(STATE_IDLE);
        channel.setPipelinesRunningCount(0);
        channels.put(storageKey(region, channel.getId()), channel);
        return channel;
    }

    public synchronized Channel restartChannelPipelines(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        if (!STATE_RUNNING.equals(channel.getState())) {
            throw badRequest("Channel " + channelId + " is not running.");
        }
        return channel;
    }

    public JsonNode describeSchedule(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        JsonNode actions = channel.getScheduleActions();
        return actions == null || !actions.isArray() ? objectMapper.createArrayNode() : actions;
    }

    public synchronized ObjectNode batchUpdateSchedule(String region, String channelId, JsonNode request) {
        requireObject(request, "Request body");
        Channel channel = requireChannel(region, channelId);
        ArrayNode current = copyArray(channel.getScheduleActions());
        ArrayNode created = objectMapper.createArrayNode();
        ArrayNode deleted = objectMapper.createArrayNode();
        JsonNode creates = request.get("creates");
        if (creates != null && creates.isObject()) {
            JsonNode actions = creates.get("scheduleActions");
            if (actions != null && actions.isArray()) {
                for (JsonNode action : actions) {
                    current.add(action);
                    created.add(action);
                }
            }
        }
        JsonNode deletes = request.get("deletes");
        if (deletes != null && deletes.isObject()) {
            List<String> names = stringList(deletes.get("actionNames"));
            ArrayNode remaining = objectMapper.createArrayNode();
            for (JsonNode action : current) {
                JsonNode name = action.get("actionName");
                if (name != null && name.isTextual() && names.contains(name.textValue())) {
                    deleted.add(action);
                } else {
                    remaining.add(action);
                }
            }
            current = remaining;
        }
        channel.setScheduleActions(current);
        channels.put(storageKey(region, channel.getId()), channel);
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("creates").set("scheduleActions", created);
        response.putObject("deletes").set("scheduleActions", deleted);
        return response;
    }

    public synchronized void deleteSchedule(String region, String channelId) {
        Channel channel = requireChannel(region, channelId);
        channel.setScheduleActions(objectMapper.createArrayNode());
        channels.put(storageKey(region, channel.getId()), channel);
    }

    public Map<String, String> listTagsForResource(String region, String resourceArn) {
        return new LinkedHashMap<>(requireTagged(region, resourceArn).tags());
    }

    public synchronized void createTags(String region, String resourceArn, JsonNode request) {
        requireObject(request, "Request body");
        TaggedResource tagged = requireTagged(region, resourceArn);
        tagged.tags().putAll(readTags(request.get("tags")));
        persistTagged(region, tagged);
    }

    public synchronized void deleteTags(String region, String resourceArn, List<String> tagKeys) {
        TaggedResource tagged = requireTagged(region, resourceArn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                if (key != null && !key.isBlank()) {
                    for (String part : key.split(",")) {
                        if (!part.isBlank()) {
                            tagged.tags().remove(part.trim());
                        }
                    }
                }
            }
        }
        persistTagged(region, tagged);
    }

    private void attachInputsToChannel(String region, Channel channel) {
        for (String inputId : attachedInputIds(channel)) {
            Input input = requireInput(region, inputId);
            if (!input.getAttachedChannels().contains(channel.getId())) {
                input.getAttachedChannels().add(channel.getId());
            }
            input.setState(STATE_ATTACHED);
            inputs.put(storageKey(region, input.getId()), input);
        }
    }

    private void detachInputsFromChannel(String region, Channel channel) {
        for (String inputId : attachedInputIds(channel)) {
            Input input = findInput(region, inputId);
            if (input == null) {
                continue;
            }
            input.getAttachedChannels().remove(channel.getId());
            input.setState(input.getAttachedChannels().isEmpty() ? STATE_DETACHED : STATE_ATTACHED);
            inputs.put(storageKey(region, input.getId()), input);
        }
    }

    private void attachGroupsToInput(String region, Input input) {
        for (String groupId : input.getSecurityGroups()) {
            InputSecurityGroup group = requireGroup(region, groupId);
            if (!group.getInputs().contains(input.getId())) {
                group.getInputs().add(input.getId());
            }
            group.setState(STATE_IN_USE);
            groups.put(storageKey(region, group.getId()), group);
        }
    }

    private void detachGroupsFromInput(String region, Input input) {
        for (String groupId : input.getSecurityGroups()) {
            InputSecurityGroup group = findGroup(region, groupId);
            if (group == null) {
                continue;
            }
            group.getInputs().remove(input.getId());
            group.setState(group.getInputs().isEmpty() ? STATE_IDLE : STATE_IN_USE);
            groups.put(storageKey(region, group.getId()), group);
        }
    }

    private List<String> attachedInputIds(Channel channel) {
        List<String> ids = new ArrayList<>();
        JsonNode attachments = channel.getInputAttachments();
        if (attachments == null || !attachments.isArray()) {
            return ids;
        }
        for (JsonNode attachment : attachments) {
            JsonNode inputId = attachment.get("inputId");
            if (inputId != null && inputId.isTextual() && !inputId.textValue().isBlank()) {
                ids.add(inputId.textValue());
            }
        }
        return ids;
    }

    private Input requireInput(String region, String inputId) {
        Input input = findInput(region, inputId);
        if (input == null) {
            throw notFound("Input not found: " + decode(inputId));
        }
        return input;
    }

    private Input findInput(String region, String inputId) {
        String decoded = decode(inputId);
        Input input = inputs.get(storageKey(region, decoded)).orElse(null);
        if (input != null) {
            return input;
        }
        for (Input candidate : inputs.values()) {
            if (decoded.equals(candidate.getId()) || decoded.equals(candidate.getArn())) {
                return candidate;
            }
        }
        return null;
    }

    private Channel requireChannel(String region, String channelId) {
        Channel channel = findChannel(region, channelId);
        if (channel == null) {
            throw notFound("Channel not found: " + decode(channelId));
        }
        return channel;
    }

    private Channel findChannel(String region, String channelId) {
        String decoded = decode(channelId);
        Channel channel = channels.get(storageKey(region, decoded)).orElse(null);
        if (channel != null) {
            return channel;
        }
        for (Channel candidate : channels.values()) {
            if (decoded.equals(candidate.getId()) || decoded.equals(candidate.getArn())) {
                return candidate;
            }
        }
        return null;
    }

    private InputSecurityGroup requireGroup(String region, String groupId) {
        InputSecurityGroup group = findGroup(region, groupId);
        if (group == null) {
            throw notFound("Input security group not found: " + decode(groupId));
        }
        return group;
    }

    private InputSecurityGroup findGroup(String region, String groupId) {
        String decoded = decode(groupId);
        InputSecurityGroup group = groups.get(storageKey(region, decoded)).orElse(null);
        if (group != null) {
            return group;
        }
        for (InputSecurityGroup candidate : groups.values()) {
            if (decoded.equals(candidate.getId()) || decoded.equals(candidate.getArn())) {
                return candidate;
            }
        }
        return null;
    }

    private TaggedResource requireTagged(String region, String resourceArn) {
        String decoded = decode(resourceArn);
        Input input = findInput(region, decoded);
        if (input != null) {
            return new TaggedResource("input", input.getId(), input.getTags());
        }
        Channel channel = findChannel(region, decoded);
        if (channel != null) {
            return new TaggedResource("channel", channel.getId(), channel.getTags());
        }
        InputSecurityGroup group = findGroup(region, decoded);
        if (group != null) {
            return new TaggedResource("isg", group.getId(), group.getTags());
        }
        throw notFound("Resource not found: " + decoded);
    }

    private void persistTagged(String region, TaggedResource tagged) {
        switch (tagged.kind()) {
            case "input" -> {
                Input input = requireInput(region, tagged.id());
                input.setTags(tagged.tags());
                inputs.put(storageKey(region, input.getId()), input);
            }
            case "channel" -> {
                Channel channel = requireChannel(region, tagged.id());
                channel.setTags(tagged.tags());
                channels.put(storageKey(region, channel.getId()), channel);
            }
            default -> {
                InputSecurityGroup group = requireGroup(region, tagged.id());
                group.setTags(tagged.tags());
                groups.put(storageKey(region, group.getId()), group);
            }
        }
    }

    private List<Destination> resolveDestinations(String type, JsonNode destinations) {
        List<Destination> resolved = new ArrayList<>();
        if (destinations == null || !destinations.isArray() || !PUSH_TYPES.contains(type)) {
            return resolved;
        }
        int index = 0;
        for (JsonNode destination : destinations) {
            String stream = textOrNull(destination, "streamName");
            if (stream == null) {
                stream = "live/stream";
            }
            String ip = "192.0.2." + (10 + index);
            String port = "RTMP_PUSH".equals(type) ? "1935" : "5000";
            String scheme = "RTMP_PUSH".equals(type) ? "rtmp" : "rtp";
            resolved.add(new Destination(scheme + "://" + ip + ":" + port + "/" + stream, ip, port));
            index++;
        }
        return resolved;
    }

    private List<Source> readSources(JsonNode sources) {
        List<Source> result = new ArrayList<>();
        if (sources == null || !sources.isArray()) {
            return result;
        }
        for (JsonNode node : sources) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Source source = new Source();
            source.setUrl(textOrNull(node, "url"));
            source.setUsername(textOrNull(node, "username"));
            source.setPasswordParam(textOrNull(node, "passwordParam"));
            result.add(source);
        }
        return result;
    }

    private static String inferClass(int sources, int destinations) {
        return Math.max(sources, destinations) >= 2 ? CLASS_STANDARD : CLASS_SINGLE;
    }

    private Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw badRequest("Tags must be a string map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw badRequest("Tags values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static List<String> cidrList(JsonNode rules) {
        List<String> cidrs = new ArrayList<>();
        if (rules == null || !rules.isArray()) {
            return cidrs;
        }
        for (JsonNode rule : rules) {
            if (rule != null && rule.isObject()) {
                JsonNode cidr = rule.get("cidr");
                if (cidr != null && cidr.isTextual()) {
                    cidrs.add(cidr.textValue());
                }
            } else if (rule != null && rule.isTextual()) {
                cidrs.add(rule.textValue());
            }
        }
        return cidrs;
    }

    private static List<String> flowArns(JsonNode flows) {
        List<String> arns = new ArrayList<>();
        if (flows == null || !flows.isArray()) {
            return arns;
        }
        for (JsonNode flow : flows) {
            if (flow != null && flow.isObject()) {
                JsonNode arn = flow.get("flowArn");
                if (arn != null && arn.isTextual()) {
                    arns.add(arn.textValue());
                }
            } else if (flow != null && flow.isTextual()) {
                arns.add(flow.textValue());
            }
        }
        return arns;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || node.isNull()) {
            return list;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    list.add(item.textValue());
                }
            }
            return list;
        }
        if (node.isTextual() && !node.textValue().isBlank()) {
            for (String part : node.textValue().split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        return list;
    }

    private ArrayNode copyArray(JsonNode node) {
        ArrayNode copy = objectMapper.createArrayNode();
        if (node != null && node.isArray()) {
            node.forEach(copy::add);
        }
        return copy;
    }

    private static <T> Page<T> paginate(List<T> items, String maxResults, String nextToken) {
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                start = 0;
            }
        }
        int limit = items.size();
        if (maxResults != null && !maxResults.isBlank()) {
            try {
                limit = Math.max(1, Integer.parseInt(maxResults));
            } catch (NumberFormatException e) {
                limit = items.size();
            }
        }
        if (start < 0) {
            start = 0;
        }
        if (start >= items.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(items.size(), start + limit);
        String token = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(new ArrayList<>(items.subList(start, end)), token);
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw badRequest(label + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        String value = textOrNull(parent, field);
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        return textOrNull(parent, field);
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), resource).toString();
    }

    private String newUniqueId(String region, String kind) {
        String id = newId();
        while (lookup(region, kind, id) != null) {
            id = newId();
        }
        return id;
    }

    private Object lookup(String region, String kind, String id) {
        return switch (kind) {
            case "input" -> findInput(region, id);
            case "channel" -> findChannel(region, id);
            default -> findGroup(region, id);
        };
    }

    private static String newId() {
        return Long.toString(1_000_000_000L + ThreadLocalRandom.current().nextLong(9_000_000_000L));
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
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

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private record TaggedResource(String kind, String id, Map<String, String> tags) {
    }
}
