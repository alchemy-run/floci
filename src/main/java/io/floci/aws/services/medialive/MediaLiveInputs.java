package io.floci.aws.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MediaLive inputs and input security groups. A push input (RTMP, UDP, RTP) gets one destination per pipeline: an
 * address at the configured ingest IP and a port from the configured range, which a running channel listens on. Its
 * security groups' CIDRs are where it accepts content from. A pull input (URL, RTMP pull, MP4 file) has one source per
 * pipeline. Inputs are {@code DETACHED}, or {@code ATTACHED} while a channel uses them. Input devices are physical
 * Elemental Link devices, which can't be claimed in Floci, so none exist.
 */
@ApplicationScoped
public class MediaLiveInputs {

    static final Set<String> PUSH = Set.of("UDP_PUSH", "RTP_PUSH", "RTMP_PUSH");
    static final Set<String> PULL = Set.of("URL_PULL", "RTMP_PULL", "MP4_FILE");

    private final MediaLiveStore store;
    private final ObjectMapper mapper;

    @Inject
    public MediaLiveInputs(MediaLiveStore store) {
        this.store = store;
        this.mapper = store.mapper();
    }

    static ObjectNode tagsOf(ObjectNode record) {
        if (!record.path("Tags").isObject()) {
            record.putObject("Tags");
        }
        return (ObjectNode) record.get("Tags");
    }

    static void setTags(ObjectNode record, JsonNode tags) {
        ObjectNode map = tagsOf(record);
        if (tags != null && tags.isObject()) {
            tags.properties().forEach(tag -> map.put(tag.getKey(), tag.getValue().asText("")));
        }
    }

    // ================================================================ security groups

    ObjectNode securityGroup(String region, String id) {
        return store.get(region, MediaLiveStore.SECURITY_GROUP, id).orElseThrow(() ->
                MediaLiveStore.notFound("The input security group " + id + " doesn't exist."));
    }

    private ArrayNode rules(JsonNode in) {
        ArrayNode rules = mapper.createArrayNode();
        for (JsonNode rule : in.path("WhitelistRules")) {
            String cidr = rule.path("Cidr").asText("");
            if (!validCidr(cidr)) {
                throw MediaLiveStore.badRequest("The whitelist rule " + cidr + " is not a valid CIDR block.");
            }
            rules.addObject().put("Cidr", cidr);
        }
        return rules;
    }

    private ObjectNode groupView(String region, ObjectNode group) {
        ObjectNode view = MediaLiveStore.view(group);
        ArrayNode inputs = view.putArray("Inputs");
        ArrayNode channels = view.putArray("Channels");
        Set<String> seen = new HashSet<>();
        for (ObjectNode input : store.list(region, MediaLiveStore.INPUT)) {
            for (JsonNode id : input.path("SecurityGroups")) {
                if (group.path("Id").asText().equals(id.asText())) {
                    inputs.add(input.path("Id").asText());
                    for (JsonNode channel : input.path("AttachedChannels")) {
                        if (seen.add(channel.asText())) {
                            channels.add(channel.asText());
                        }
                    }
                }
            }
        }
        view.put("State", inputs.isEmpty() ? "IDLE" : "IN_USE");
        return view;
    }

    public ObjectNode createInputSecurityGroup(JsonNode in, String region) {
        String id = store.numericId(region, MediaLiveStore.SECURITY_GROUP);
        ObjectNode group = store.record();
        group.put("Id", id);
        group.put("Arn", store.arn(region, "inputSecurityGroup:" + id));
        group.set("WhitelistRules", rules(in));
        group.putObject("Tags");
        setTags(group, in.path("Tags"));
        store.put(region, MediaLiveStore.SECURITY_GROUP, id, group);
        return wrap("SecurityGroup", groupView(region, group));
    }

    public ObjectNode describeInputSecurityGroup(JsonNode in, String region) {
        return groupView(region, securityGroup(region, in.path("InputSecurityGroupId").asText()));
    }

    public ObjectNode updateInputSecurityGroup(JsonNode in, String region) {
        ObjectNode group = securityGroup(region, in.path("InputSecurityGroupId").asText());
        if (in.has("WhitelistRules")) {
            group.set("WhitelistRules", rules(in));
        }
        if (in.has("Tags")) {
            group.putObject("Tags");
            setTags(group, in.get("Tags"));
        }
        store.put(region, MediaLiveStore.SECURITY_GROUP, group.path("Id").asText(), group);
        return wrap("SecurityGroup", groupView(region, group));
    }

    public ObjectNode deleteInputSecurityGroup(JsonNode in, String region) {
        ObjectNode group = securityGroup(region, in.path("InputSecurityGroupId").asText());
        if ("IN_USE".equals(groupView(region, group).path("State").asText())) {
            throw MediaLiveStore.badRequest("The input security group is used by inputs.");
        }
        store.delete(region, MediaLiveStore.SECURITY_GROUP, group.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listInputSecurityGroups(JsonNode in, String region) {
        List<ObjectNode> groups = new ArrayList<>();
        for (ObjectNode group : store.list(region, MediaLiveStore.SECURITY_GROUP)) {
            groups.add(groupView(region, group));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(groups, in, out, "InputSecurityGroups", 1000);
        return out;
    }

    /** The CIDRs an input accepts pushed content from: its security groups' rules. */
    List<String> allowedCidrs(String account, String region, ObjectNode input) {
        List<String> cidrs = new ArrayList<>();
        for (JsonNode id : input.path("SecurityGroups")) {
            store.getForAccount(account, region, MediaLiveStore.SECURITY_GROUP, id.asText()).ifPresent(group ->
                    group.path("WhitelistRules").forEach(rule -> cidrs.add(rule.path("Cidr").asText())));
        }
        return cidrs;
    }

    // ================================================================ inputs

    ObjectNode input(String region, String id) {
        return store.get(region, MediaLiveStore.INPUT, id).orElseThrow(() ->
                MediaLiveStore.notFound("The input " + id + " doesn't exist."));
    }

    private ObjectNode wrap(String member, ObjectNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(member, value);
        return out;
    }

    ObjectNode inputView(ObjectNode input) {
        ObjectNode view = MediaLiveStore.view(input);
        view.put("State", input.path("AttachedChannels").isEmpty() ? "DETACHED" : "ATTACHED");
        return view;
    }

    public ObjectNode createInput(JsonNode in, String region) {
        String token = in.path("RequestId").asText(null);
        if (token != null) {
            for (ObjectNode existing : store.list(region, MediaLiveStore.INPUT)) {
                if (token.equals(existing.path("_").path("requestId").asText())) {
                    return wrap("Input", inputView(existing));
                }
            }
        }
        String type = in.path("Type").asText(null);
        if (type == null) {
            throw MediaLiveStore.badRequest("An input needs a Type.");
        }
        String id = store.numericId(region, MediaLiveStore.INPUT);
        ObjectNode input = store.record();
        input.put("Id", id);
        input.put("Arn", store.arn(region, "input:" + id));
        input.put("Name", in.path("Name").asText(""));
        input.put("Type", type);
        input.put("InputSourceType", "STATIC");
        input.put("InputNetworkLocation", in.path("InputNetworkLocation").asText("AWS"));
        input.putArray("AttachedChannels");
        input.putObject("Tags");
        setTags(input, in.path("Tags"));
        MediaLiveStore.meta(input).put("requestId", token);
        apply(region, input, in, true);
        store.put(region, MediaLiveStore.INPUT, id, input);
        return wrap("Input", inputView(input));
    }

    /** Applies an input request's sources, destinations, flows and security groups. */
    private void apply(String region, ObjectNode input, JsonNode in, boolean create) {
        String type = input.path("Type").asText();
        if (in.hasNonNull("Name")) {
            input.put("Name", in.get("Name").asText());
        }
        MediaLiveStore.copy(in, input, "RoleArn", "SrtSettings", "MulticastSettings", "SdiSources");
        if (in.has("InputSecurityGroups") || create) {
            ArrayNode groups = input.putArray("SecurityGroups");
            for (JsonNode group : in.path("InputSecurityGroups")) {
                securityGroup(region, group.asText());
                groups.add(group.asText());
            }
            if (PUSH.contains(type) && groups.isEmpty() && !in.has("Vpc")) {
                throw MediaLiveStore.badRequest("A " + type + " input needs an input security group.");
            }
        }
        int pipelines = 1;
        if (PULL.contains(type) && (in.has("Sources") || create)) {
            ArrayNode sources = input.putArray("Sources");
            for (JsonNode source : in.path("Sources")) {
                String url = source.path("Url").asText("");
                if (url.isEmpty()) {
                    throw MediaLiveStore.badRequest("Each source needs a Url.");
                }
                ObjectNode copy = sources.addObject();
                copy.put("Url", url);
                MediaLiveStore.copy(source, copy, "Username", "PasswordParam");
            }
            if (sources.isEmpty() || sources.size() > 2) {
                throw MediaLiveStore.badRequest("A " + type + " input has one source per pipeline: one or two.");
            }
            pipelines = sources.size();
        }
        if (PUSH.contains(type) && (in.has("Destinations") || create)) {
            List<JsonNode> requested = new ArrayList<>();
            in.path("Destinations").forEach(requested::add);
            if ("RTMP_PUSH".equals(type) && (requested.isEmpty() || requested.size() > 2)) {
                throw MediaLiveStore.badRequest("An RTMP_PUSH input has one destination (a StreamName) per "
                        + "pipeline: one or two.");
            }
            if (!"RTMP_PUSH".equals(type) && requested.isEmpty()) {
                requested.add(mapper.createObjectNode());
                requested.add(mapper.createObjectNode());
            }
            ArrayNode previous = input.has("Destinations") ? (ArrayNode) input.get("Destinations") : null;
            ArrayNode destinations = input.putArray("Destinations");
            for (int i = 0; i < requested.size(); i++) {
                JsonNode request = requested.get(i);
                int port = previous != null && i < previous.size() ? previous.get(i).path("Port").asInt()
                        : port(region);
                String scheme = switch (type) {
                    case "RTMP_PUSH" -> "rtmp";
                    case "RTP_PUSH" -> "rtp";
                    default -> "udp";
                };
                String stream = request.path("StreamName").asText("");
                if ("RTMP_PUSH".equals(type) && !stream.matches("[^/]+/[^/]+")) {
                    throw MediaLiveStore.badRequest("An RTMP_PUSH destination's StreamName is app/instance.");
                }
                ObjectNode destination = destinations.addObject();
                destination.put("Ip", store.config().ingestIp());
                destination.put("Port", String.valueOf(port));
                destination.put("Url", scheme + "://" + store.config().ingestIp() + ":" + port
                        + ("RTMP_PUSH".equals(type) ? "/" + stream : ""));
                MediaLiveStore.copy(request, destination, "Network");
            }
            pipelines = destinations.size();
        }
        if ("MEDIACONNECT".equals(type) && (in.has("MediaConnectFlows") || create)) {
            ArrayNode flows = input.putArray("MediaConnectFlows");
            for (JsonNode flow : in.path("MediaConnectFlows")) {
                String arn = flow.path("FlowArn").asText("");
                if (!AwsArnUtils.isArnFor(arn, "mediaconnect")) {
                    throw MediaLiveStore.badRequest("Each MediaConnect flow needs a FlowArn.");
                }
                flows.addObject().put("FlowArn", arn);
            }
            if (flows.isEmpty() || flows.size() > 2) {
                throw MediaLiveStore.badRequest("A MEDIACONNECT input has one flow per pipeline: one or two.");
            }
            if (!input.hasNonNull("RoleArn")) {
                throw MediaLiveStore.badRequest("A MEDIACONNECT input needs a RoleArn.");
            }
            pipelines = flows.size();
        }
        if (create || in.has("Sources") || in.has("Destinations") || in.has("MediaConnectFlows")) {
            input.put("InputClass", pipelines == 2 ? "STANDARD" : "SINGLE_PIPELINE");
        }
        if (in.has("Vpc")) {
            input.set("Vpc", in.get("Vpc").deepCopy());
        }
    }

    /** A port no input of any account uses, from the configured range. */
    private int port(String region) {
        Set<Integer> used = new HashSet<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaLiveStore.INPUT)) {
            for (JsonNode destination : entry.value().path("Destinations")) {
                used.add(destination.path("Port").asInt());
            }
        }
        int start = store.config().ingestPortStart();
        for (int port = start; port < start + store.config().ingestPortCount(); port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw MediaLiveStore.tooMany("No ingest port is free for the input; raise ingest-port-count.");
    }

    public ObjectNode describeInput(JsonNode in, String region) {
        return inputView(input(region, in.path("InputId").asText()));
    }

    public ObjectNode updateInput(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("InputId").asText());
        for (JsonNode channel : input.path("AttachedChannels")) {
            store.get(region, MediaLiveStore.CHANNEL, channel.asText()).ifPresent(attached -> {
                if (!"IDLE".equals(attached.path("State").asText())) {
                    throw MediaLiveStore.conflict("The input is attached to the " + attached.path("State")
                            .asText() + " channel " + attached.path("Id").asText() + ".");
                }
            });
        }
        apply(region, input, in, false);
        store.put(region, MediaLiveStore.INPUT, input.path("Id").asText(), input);
        return wrap("Input", inputView(input));
    }

    public ObjectNode deleteInput(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("InputId").asText());
        if (!input.path("AttachedChannels").isEmpty()) {
            throw MediaLiveStore.conflict("The input is attached to the channel "
                    + input.path("AttachedChannels").get(0).asText() + ".");
        }
        store.delete(region, MediaLiveStore.INPUT, input.path("Id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listInputs(JsonNode in, String region) {
        List<ObjectNode> inputs = new ArrayList<>();
        for (ObjectNode input : store.list(region, MediaLiveStore.INPUT)) {
            inputs.add(inputView(input));
        }
        ObjectNode out = mapper.createObjectNode();
        MediaLiveStore.page(inputs, in, out, "Inputs", 1000);
        return out;
    }

    public ObjectNode createPartnerInput(JsonNode in, String region) {
        ObjectNode original = input(region, in.path("InputId").asText());
        if (!"STANDARD".equals(original.path("InputClass").asText())) {
            throw MediaLiveStore.badRequest("A partner input is created for a STANDARD input.");
        }
        String id = store.numericId(region, MediaLiveStore.INPUT);
        ObjectNode partner = original.deepCopy();
        partner.put("Id", id);
        partner.put("Arn", store.arn(region, "input:" + id));
        partner.putArray("AttachedChannels");
        partner.putArray("InputPartnerIds").add(original.path("Id").asText());
        partner.putObject("Tags");
        setTags(partner, in.path("Tags"));
        MediaLiveStore.meta(partner).put("seq", System.nanoTime()).remove("requestId");
        original.withArray("InputPartnerIds").add(id);
        store.put(region, MediaLiveStore.INPUT, original.path("Id").asText(), original);
        store.put(region, MediaLiveStore.INPUT, id, partner);
        return wrap("Input", inputView(partner));
    }

    /** Attaches or detaches a channel from the inputs its attachments name. */
    void attach(String region, String channelId, JsonNode attachments, boolean attached) {
        for (JsonNode attachment : attachments) {
            store.get(region, MediaLiveStore.INPUT, attachment.path("InputId").asText()).ifPresent(input -> {
                ArrayNode channels = (ArrayNode) input.withArray("AttachedChannels");
                for (int i = channels.size() - 1; i >= 0; i--) {
                    if (channelId.equals(channels.get(i).asText())) {
                        channels.remove(i);
                    }
                }
                if (attached) {
                    channels.add(channelId);
                }
                store.put(region, MediaLiveStore.INPUT, input.path("Id").asText(), input);
            });
        }
    }

    // ================================================================ input devices

    private static RuntimeException noDevice(String id) {
        return MediaLiveStore.notFound("The input device " + id + " doesn't exist. Elemental Link devices are "
                + "physical and can't be claimed in Floci.");
    }

    public ObjectNode listInputDevices(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.putArray("InputDevices");
        return out;
    }

    public ObjectNode listInputDeviceTransfers(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.putArray("InputDeviceTransfers");
        return out;
    }

    public ObjectNode claimDevice(JsonNode in, String region) {
        throw noDevice(in.path("Id").asText());
    }

    public ObjectNode describeInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode describeInputDeviceThumbnail(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode updateInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode rebootInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode startInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode stopInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode startInputDeviceMaintenanceWindow(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode transferInputDevice(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode acceptInputDeviceTransfer(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode rejectInputDeviceTransfer(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    public ObjectNode cancelInputDeviceTransfer(JsonNode in, String region) {
        throw noDevice(in.path("InputDeviceId").asText());
    }

    // ================================================================ CIDRs

    static boolean validCidr(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2 || !parts[0].matches("[0-9.]+|[0-9a-fA-F:]+")) {
            return false;
        }
        try {
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int bits = Integer.parseInt(parts[1]);
            return bits >= 0 && bits <= address.length * 8;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
