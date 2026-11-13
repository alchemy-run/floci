package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MediaConnect Router: inputs, outputs and the network interfaces they're reached on. Inputs and outputs are
 * {@code CREATING}, then {@code STANDBY}; starting one is {@code STARTING}, then {@code ACTIVE}; stopping it is
 * {@code STOPPING}, then {@code STANDBY}; restarting an active one starts it again. {@code TakeRouterInput} routes an
 * output to an input ({@code ROUTING}, then {@code ROUTED}) or, without an input, unroutes it. An active standard
 * input of RTP or RIST receives on its port through {@link MediaConnectRelay}, from the CIDRs its public network
 * interface allows, and sends to the active outputs routed to it, standard RTP or RIST outputs sending to their
 * destinations. A VPC network interface creates a network interface in its subnet.
 */
@ApplicationScoped
public class MediaConnectRouters {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9]([a-zA-Z0-9\\-_]*[a-zA-Z0-9])?");
    private static final Map<String, String> INPUT_TYPES = Map.of("Standard", "STANDARD", "Failover", "FAILOVER",
            "Merge", "MERGE", "MediaConnectFlow", "MEDIACONNECT_FLOW", "MediaLiveChannel", "MEDIALIVE_CHANNEL");
    private static final Map<String, String> OUTPUT_TYPES = Map.of("Standard", "STANDARD", "MediaConnectFlow",
            "MEDIACONNECT_FLOW", "MediaLiveInput", "MEDIALIVE_INPUT");

    private final MediaConnectStore store;
    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Provider<MediaConnectRelay> relay;
    private final Provider<Ec2Service> ec2;

    @Inject
    public MediaConnectRouters(MediaConnectStore store, Vertx vertx, Provider<MediaConnectRelay> relay,
                               Provider<Ec2Service> ec2) {
        this.store = store;
        this.mapper = store.mapper();
        this.vertx = vertx;
        this.relay = relay;
        this.ec2 = ec2;
    }

    private static String name(String name) {
        if (name == null || name.length() > 128 || !NAME.matcher(name).matches()) {
            throw MediaConnectStore.badRequest("Names are 1-128 letters, digits, hyphens and underscores, starting "
                    + "and ending with a letter or digit.");
        }
        return name;
    }

    // ================================================================ transitions

    private void settle(String account, String region, String kind, ObjectNode record) {
        ObjectNode meta = MediaConnectStore.meta(record);
        String id = meta.path("id").asText();
        long routedAt = meta.path("routedAt").asLong(0);
        boolean routed = routedAt != 0 && routedAt <= System.currentTimeMillis();
        if (routed) {
            meta.remove("routedAt");
            record.put("RoutedState", "ROUTED");
        }
        long at = meta.path("transitionAt").asLong(0);
        if (at == 0 || at > System.currentTimeMillis()) {
            if (routed) {
                store.putForAccount(account, region, kind, id, record);
                store.getForAccount(account, region, MediaConnectStore.ROUTER_INPUT,
                        MediaConnectStore.idOf(record.path("RoutedInputArn").asText()))
                        .ifPresent(input -> carry(account, region, input));
            }
            return;
        }
        String next = meta.path("next").asText();
        meta.remove("transitionAt");
        meta.remove("next");
        if ("GONE".equals(next)) {
            store.deleteForAccount(account, region, kind, id);
            relay.get().stop(record.path("Arn").asText());
            if (MediaConnectStore.ROUTER_INTERFACE.equals(kind)) {
                meta.path("eni").forEach(eni -> deleteInterface(region, eni.asText()));
            }
            return;
        }
        record.put("State", next);
        record.put("UpdatedAt", MediaConnectStore.epoch());
        store.putForAccount(account, region, kind, id, record);
        if (MediaConnectStore.ROUTER_INPUT.equals(kind)) {
            carry(account, region, record);
        } else if (MediaConnectStore.ROUTER_OUTPUT.equals(kind) && record.hasNonNull("RoutedInputArn")) {
            store.getForAccount(account, region, MediaConnectStore.ROUTER_INPUT,
                    MediaConnectStore.idOf(record.get("RoutedInputArn").asText()))
                    .ifPresent(input -> carry(account, region, input));
        }
    }

    private void later(ObjectNode record, String kind, String next, String region) {
        String account = store.account();
        long delay = Math.max(1, store.config().transitionMillis());
        if ("ROUTED".equals(next)) {
            MediaConnectStore.meta(record).put("routedAt", System.currentTimeMillis() + delay);
        } else {
            MediaConnectStore.meta(record).put("transitionAt", System.currentTimeMillis() + delay).put("next", next);
        }
        String id = MediaConnectStore.meta(record).path("id").asText();
        vertx.setTimer(delay, timer -> vertx.executeBlocking(() -> {
            RequestScopes.runAs(account, region, () -> store.getForAccount(account, region, kind, id)
                    .ifPresent(current -> settle(account, region, kind, current)));
            return null;
        }, false));
    }

    private ObjectNode find(String region, String kind, String arn, String what) {
        String id = MediaConnectStore.idOf(arn);
        ObjectNode record = store.get(region, kind, id).orElse(null);
        if (record != null) {
            settle(store.account(), region, kind, record);
            record = store.get(region, kind, id).orElse(null);
        }
        if (record == null || !arn.equals(record.path("Arn").asText())) {
            throw MediaConnectStore.notFound("The " + what + " " + arn + " was not found.");
        }
        return record;
    }

    private List<ObjectNode> all(String region, String kind) {
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode record : store.list(region, kind)) {
            settle(store.account(), region, kind, record);
            store.get(region, kind, MediaConnectStore.meta(record).path("id").asText()).ifPresent(found::add);
        }
        return found;
    }

    /** Starts, reconfigures or stops the relay an input's packets go through. */
    private void carry(String account, String region, ObjectNode input) {
        String arn = input.path("Arn").asText();
        JsonNode standard = input.path("Configuration").path("Standard");
        String protocol = protocol(standard.path("ProtocolConfiguration"));
        if (!"ACTIVE".equals(input.path("State").asText()) || protocol == null) {
            relay.get().stop(arn);
            return;
        }
        JsonNode configuration = standard.path("ProtocolConfiguration").path(protocol);
        ObjectNode flow = mapper.createObjectNode();
        flow.put("FlowArn", arn);
        ObjectNode source = flow.putArray("Sources").addObject();
        source.put("SourceArn", arn);
        source.put("Name", input.path("Name").asText());
        source.putObject("Transport").put("Protocol", "Rist".equals(protocol) ? "rist" : "rtp");
        source.put("IngestPort", configuration.path("Port").asInt());
        store.getForAccount(account, region, MediaConnectStore.ROUTER_INTERFACE,
                MediaConnectStore.idOf(standard.path("NetworkInterfaceArn").asText())).ifPresent(networkInterface -> {
                    JsonNode rules = networkInterface.path("Configuration").path("Public").path("AllowRules");
                    if (rules.size() == 1) {
                        source.put("WhitelistCidr", rules.get(0).path("Cidr").asText());
                    }
                });
        flow.set("Source", source.deepCopy());
        ArrayNode outputs = flow.putArray("Outputs");
        for (ObjectNode output : store.listForAccount(account, region, MediaConnectStore.ROUTER_OUTPUT)) {
            if (!arn.equals(output.path("RoutedInputArn").asText()) || !"ROUTED".equals(output.path("RoutedState")
                    .asText()) || !"ACTIVE".equals(output.path("State").asText())) {
                continue;
            }
            JsonNode protocols = output.path("Configuration").path("Standard").path("ProtocolConfiguration");
            String outputProtocol = protocol(protocols);
            if (outputProtocol == null) {
                continue;
            }
            JsonNode destination = protocols.path(outputProtocol);
            ObjectNode target = outputs.addObject();
            target.put("Name", output.path("Name").asText());
            target.putObject("Transport").put("Protocol", "Rist".equals(outputProtocol) ? "rist" : "rtp");
            target.put("Destination", destination.path("DestinationAddress").asText());
            target.put("Port", destination.path("DestinationPort").asInt());
        }
        relay.get().update(account, region, flow);
    }

    /** The datagram protocol (Rtp or Rist) a protocol configuration names, or null. */
    private static String protocol(JsonNode configuration) {
        if (configuration.has("Rtp")) {
            return "Rtp";
        }
        if (configuration.has("Rist")) {
            return "Rist";
        }
        return null;
    }

    private ObjectNode maintenanceDefaults(JsonNode in) {
        ObjectNode configuration = in.isObject() ? in.deepCopy() : mapper.createObjectNode();
        if (!configuration.has("PreferredDayTime") && !configuration.has("Default")) {
            configuration.putObject("Default");
        }
        return configuration;
    }

    private void schedule(ObjectNode record) {
        JsonNode preferred = record.path("MaintenanceConfiguration").path("PreferredDayTime");
        record.put("MaintenanceType", preferred.isObject() ? "PREFERRED_DAY_TIME" : "DEFAULT");
        DayOfWeek day = DayOfWeek.SUNDAY;
        LocalTime time = LocalTime.of(2, 0);
        if (preferred.isObject()) {
            try {
                day = DayOfWeek.valueOf(preferred.path("Day").asText().toUpperCase(Locale.ROOT));
                time = LocalTime.parse(preferred.path("Time").asText());
            } catch (RuntimeException e) {
                throw MediaConnectStore.badRequest("PreferredDayTime takes a Day and a Time, HH:MM.");
            }
        }
        ZonedDateTime start = LocalDate.now(ZoneOffset.UTC).plusDays(1).with(TemporalAdjusters.nextOrSame(day))
                .atTime(time).atZone(ZoneOffset.UTC);
        record.put("MaintenanceScheduleType", "WINDOW");
        ObjectNode window = record.putObject("MaintenanceSchedule").putObject("Window");
        window.put("Start", start.toInstant().toEpochMilli() / 1000.0);
        window.put("End", start.plusHours(2).toInstant().toEpochMilli() / 1000.0);
        window.put("ScheduledTime", start.toInstant().toEpochMilli() / 1000.0);
    }

    private ObjectNode common(String region, String kind, String type, JsonNode in) {
        String name = name(in.path("Name").asText(null));
        for (ObjectNode existing : store.list(region, kind)) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("A router resource named " + name + " already exists.");
            }
        }
        String id = MediaConnectStore.routerId();
        ObjectNode record = store.record();
        record.put("Name", name);
        record.put("Arn", store.arn(region, type + ":" + id));
        record.put("Id", id);
        record.put("RegionName", in.path("RegionName").asText(region));
        double now = MediaConnectStore.epoch();
        record.put("CreatedAt", now);
        record.put("UpdatedAt", now);
        record.set("Tags", in.path("Tags").isObject() ? in.get("Tags").deepCopy() : mapper.createObjectNode());
        MediaConnectStore.meta(record).put("id", id);
        return record;
    }

    private ObjectNode networkInterface(String region, String arn) {
        return find(region, MediaConnectStore.ROUTER_INTERFACE, arn, "router network interface");
    }

    private void checkInterfaces(String region, JsonNode configuration) {
        configuration.findValues("NetworkInterfaceArn").forEach(arn -> networkInterface(region, arn.asText()));
    }

    // ================================================================ inputs

    public ObjectNode createRouterInput(JsonNode in, String region) {
        ObjectNode earlier = store.repeated(region, "CreateRouterInput", in);
        if (earlier != null) {
            return earlier;
        }
        ObjectNode input = common(region, MediaConnectStore.ROUTER_INPUT, "routerInput", in);
        JsonNode configuration = in.path("Configuration");
        String type = type(configuration, INPUT_TYPES, "input");
        checkInterfaces(region, configuration);
        input.put("State", "CREATING");
        input.put("InputType", type);
        input.set("Configuration", configuration.deepCopy());
        input.put("RoutedOutputs", 0);
        input.put("MaximumRoutedOutputs", in.path("Tier").asText("INPUT_100").endsWith("_500") ? 500 : 100);
        input.put("AvailabilityZone", in.path("AvailabilityZone").asText(region + "a"));
        input.put("MaximumBitrate", in.path("MaximumBitrate").asLong());
        input.put("Tier", in.path("Tier").asText());
        input.put("RoutingScope", in.path("RoutingScope").asText());
        input.putArray("Messages");
        input.set("TransitEncryption", in.path("TransitEncryption").isObject() ? in.get("TransitEncryption")
                .deepCopy() : automaticEncryption());
        input.set("StreamDetails", streamDetails(type, configuration, true));
        input.put("IpAddress", store.config().ingestIp());
        input.set("MaintenanceConfiguration", maintenanceDefaults(in.path("MaintenanceConfiguration")));
        schedule(input);
        input.put("ContentQualityAnalysisType", "CONTENT_LEVEL");
        input.set("ContentQualityAnalysisConfiguration", in.path("ContentQualityAnalysisConfiguration").isObject()
                ? in.get("ContentQualityAnalysisConfiguration").deepCopy() : mapper.createObjectNode());
        later(input, MediaConnectStore.ROUTER_INPUT, "STANDBY", region);
        store.put(region, MediaConnectStore.ROUTER_INPUT, input.path("Id").asText(), input);
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterInput", MediaConnectStore.view(input));
        store.remember(region, "CreateRouterInput", in, out);
        return out;
    }

    private ObjectNode automaticEncryption() {
        ObjectNode encryption = mapper.createObjectNode();
        encryption.put("EncryptionKeyType", "AUTOMATIC");
        encryption.putObject("EncryptionKeyConfiguration").putObject("Automatic");
        return encryption;
    }

    private static String type(JsonNode configuration, Map<String, String> types, String what) {
        String type = null;
        for (Map.Entry<String, String> candidate : types.entrySet()) {
            if (configuration.has(candidate.getKey())) {
                if (type != null) {
                    throw MediaConnectStore.badRequest("A router " + what + " configuration names one type.");
                }
                type = candidate.getValue();
            }
        }
        if (type == null) {
            throw MediaConnectStore.badRequest("A router " + what + " needs a Configuration.");
        }
        return type;
    }

    private ObjectNode streamDetails(String type, JsonNode configuration, boolean input) {
        ObjectNode details = mapper.createObjectNode();
        switch (type) {
            case "STANDARD" -> {
                ObjectNode standard = details.putObject("Standard");
                if (!input) {
                    JsonNode protocols = configuration.path("Standard").path("ProtocolConfiguration");
                    for (JsonNode protocol : protocols) {
                        if (protocol.hasNonNull("DestinationAddress")) {
                            standard.put("DestinationIpAddress", protocol.get("DestinationAddress").asText());
                        }
                    }
                }
            }
            case "FAILOVER" -> {
                ObjectNode failover = details.putObject("Failover");
                failover.putObject("SourceIndexZeroStreamDetails").put("SourceIndex", 0);
                failover.putObject("SourceIndexOneStreamDetails").put("SourceIndex", 1);
            }
            case "MERGE" -> {
                ObjectNode merge = details.putObject("Merge");
                merge.putObject("SourceIndexZeroStreamDetails").put("SourceIndex", 0);
                merge.putObject("SourceIndexOneStreamDetails").put("SourceIndex", 1);
            }
            case "MEDIACONNECT_FLOW" -> details.putObject("MediaConnectFlow");
            case "MEDIALIVE_CHANNEL" -> details.putObject("MediaLiveChannel");
            default -> details.putObject("MediaLiveInput");
        }
        return details;
    }

    public ObjectNode getRouterInput(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterInput", MediaConnectStore.view(input(region, in.path("Arn").asText())));
        return out;
    }

    private ObjectNode input(String region, String arn) {
        return find(region, MediaConnectStore.ROUTER_INPUT, arn, "router input");
    }

    public ObjectNode batchGetRouterInput(JsonNode in, String region) {
        return batch(in, region, MediaConnectStore.ROUTER_INPUT, "RouterInputs", "router input");
    }

    private ObjectNode batch(JsonNode in, String region, String kind, String member, String what) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode found = out.putArray(member);
        ArrayNode errors = out.putArray("Errors");
        for (JsonNode arn : in.path("Arns")) {
            try {
                found.add(MediaConnectStore.view(find(region, kind, arn.asText(), what)));
            } catch (AwsException e) {
                errors.addObject().put("Arn", arn.asText()).put("Code", "NOT_FOUND").put("Message", e.getMessage());
            }
        }
        return out;
    }

    public ObjectNode updateRouterInput(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("Arn").asText());
        if (in.hasNonNull("Name")) {
            input.put("Name", name(in.get("Name").asText()));
        }
        if (in.has("Configuration")) {
            String type = type(in.get("Configuration"), INPUT_TYPES, "input");
            checkInterfaces(region, in.get("Configuration"));
            input.set("Configuration", in.get("Configuration").deepCopy());
            input.put("InputType", type);
            input.set("StreamDetails", streamDetails(type, in.get("Configuration"), true));
        }
        MediaConnectStore.copy(in, input, "MaximumBitrate", "RoutingScope", "Tier", "TransitEncryption",
                "ContentQualityAnalysisConfiguration");
        if (in.has("MaintenanceConfiguration")) {
            input.set("MaintenanceConfiguration", maintenanceDefaults(in.get("MaintenanceConfiguration")));
            schedule(input);
        }
        input.put("UpdatedAt", MediaConnectStore.epoch());
        store.put(region, MediaConnectStore.ROUTER_INPUT, input.path("Id").asText(), input);
        carry(store.account(), region, input);
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterInput", MediaConnectStore.view(input));
        return out;
    }

    public ObjectNode deleteRouterInput(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("Arn").asText());
        if (!"STANDBY".equals(input.path("State").asText()) && !"ERROR".equals(input.path("State").asText())) {
            throw MediaConnectStore.badRequest("The router input is " + input.path("State").asText()
                    + "; stop it before deleting it.");
        }
        for (ObjectNode output : all(region, MediaConnectStore.ROUTER_OUTPUT)) {
            if (input.path("Arn").asText().equals(output.path("RoutedInputArn").asText())) {
                throw MediaConnectStore.conflict("The router output " + output.path("Name").asText()
                        + " is routed to the input.");
            }
        }
        input.put("State", "DELETING");
        later(input, MediaConnectStore.ROUTER_INPUT, "GONE", region);
        store.put(region, MediaConnectStore.ROUTER_INPUT, input.path("Id").asText(), input);
        return stateOf(input);
    }

    private ObjectNode stateOf(ObjectNode record) {
        ObjectNode out = mapper.createObjectNode();
        out.put("Arn", record.path("Arn").asText());
        out.put("Name", record.path("Name").asText());
        out.put("State", record.path("State").asText());
        return out;
    }

    public ObjectNode startRouterInput(JsonNode in, String region) {
        return start(region, MediaConnectStore.ROUTER_INPUT, input(region, in.path("Arn").asText()));
    }

    public ObjectNode stopRouterInput(JsonNode in, String region) {
        return stop(region, MediaConnectStore.ROUTER_INPUT, input(region, in.path("Arn").asText()));
    }

    public ObjectNode restartRouterInput(JsonNode in, String region) {
        return restart(region, MediaConnectStore.ROUTER_INPUT, input(region, in.path("Arn").asText()));
    }

    private ObjectNode start(String region, String kind, ObjectNode record) {
        if (!"STANDBY".equals(record.path("State").asText()) && !"ERROR".equals(record.path("State").asText())) {
            throw MediaConnectStore.badRequest("The router resource is " + record.path("State").asText()
                    + " and can't be started.");
        }
        record.put("State", "STARTING");
        later(record, kind, "ACTIVE", region);
        store.put(region, kind, record.path("Id").asText(), record);
        ObjectNode out = stateOf(record);
        out.put("MaintenanceScheduleType", record.path("MaintenanceScheduleType").asText("WINDOW"));
        out.set("MaintenanceSchedule", record.path("MaintenanceSchedule").deepCopy());
        return out;
    }

    private ObjectNode stop(String region, String kind, ObjectNode record) {
        String state = record.path("State").asText();
        if (!"ACTIVE".equals(state) && !"STARTING".equals(state)) {
            throw MediaConnectStore.badRequest("The router resource is " + state + " and can't be stopped.");
        }
        record.put("State", "STOPPING");
        later(record, kind, "STANDBY", region);
        store.put(region, kind, record.path("Id").asText(), record);
        relay.get().stop(record.path("Arn").asText());
        refresh(region, kind, record);
        return stateOf(record);
    }

    private ObjectNode restart(String region, String kind, ObjectNode record) {
        if (!"ACTIVE".equals(record.path("State").asText())) {
            throw MediaConnectStore.badRequest("Only an active router resource restarts.");
        }
        record.put("State", "STARTING");
        later(record, kind, "ACTIVE", region);
        store.put(region, kind, record.path("Id").asText(), record);
        refresh(region, kind, record);
        return stateOf(record);
    }

    /** Re-carries the input an output is routed to, after the output's state changed. */
    private void refresh(String region, String kind, ObjectNode record) {
        if (MediaConnectStore.ROUTER_OUTPUT.equals(kind) && record.hasNonNull("RoutedInputArn")) {
            store.get(region, MediaConnectStore.ROUTER_INPUT, MediaConnectStore.idOf(record.get("RoutedInputArn")
                    .asText())).ifPresent(input -> carry(store.account(), region, input));
        } else if (MediaConnectStore.ROUTER_INPUT.equals(kind)) {
            carry(store.account(), region, record);
        }
    }

    public ObjectNode listRouterInputs(JsonNode in, String region) {
        JsonNode filters = in.path("Filters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode input : all(region, MediaConnectStore.ROUTER_INPUT)) {
            if (!matches(filters, input, "InputTypes", "InputType")) {
                continue;
            }
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(input, listed, "Name", "Arn", "Id", "InputType", "State", "RoutedOutputs",
                    "RegionName", "AvailabilityZone", "MaximumBitrate", "RoutingScope", "CreatedAt", "UpdatedAt",
                    "MaintenanceScheduleType", "MaintenanceSchedule");
            listed.put("MessageCount", input.path("Messages").size());
            input.path("Configuration").findValues("NetworkInterfaceArn").stream().findFirst()
                    .ifPresent(arn -> listed.put("NetworkInterfaceArn", arn.asText()));
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "RouterInputs", 20);
        return out;
    }

    /** Whether a record passes a list's filters: each filter's conditions are alternatives, filters combine. */
    private static boolean matches(JsonNode filters, ObjectNode record, String typeFilter, String typeMember) {
        for (JsonNode filter : filters) {
            boolean any = false;
            boolean constrained = false;
            for (JsonNode name : filter.path("NameContains")) {
                constrained = true;
                any |= record.path("Name").asText().contains(name.asText());
            }
            for (JsonNode value : filter.path("RegionNames")) {
                constrained = true;
                any |= value.asText().equals(record.path("RegionName").asText());
            }
            for (JsonNode value : filter.path("RoutingScopes")) {
                constrained = true;
                any |= value.asText().equals(record.path("RoutingScope").asText());
            }
            for (JsonNode value : filter.path(typeFilter)) {
                constrained = true;
                any |= value.asText().equals(record.path(typeMember).asText());
            }
            for (JsonNode value : filter.path("NetworkInterfaceTypes")) {
                constrained = true;
                any |= value.asText().equals(record.path("NetworkInterfaceType").asText());
            }
            for (JsonNode value : filter.path("NetworkInterfaceArns")) {
                constrained = true;
                for (JsonNode arn : record.path("Configuration").findValues("NetworkInterfaceArn")) {
                    any |= value.asText().equals(arn.asText());
                }
            }
            for (JsonNode value : filter.path("RoutedInputArns")) {
                constrained = true;
                any |= value.asText().equals(record.path("RoutedInputArn").asText());
            }
            if (constrained && !any) {
                return false;
            }
        }
        return true;
    }

    public ObjectNode getRouterInputSourceMetadata(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("Arn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("Arn", input.path("Arn").asText());
        out.put("Name", input.path("Name").asText());
        ObjectNode details = out.putObject("SourceMetadataDetails");
        ArrayNode messages = details.putArray("SourceMetadataMessages");
        details.put("Timestamp", MediaConnectStore.epoch());
        if (!"ACTIVE".equals(input.path("State").asText())) {
            messages.addObject().put("Code", "INPUT_NOT_ACTIVE").put("Message", "The router input is "
                    + input.path("State").asText() + "; source metadata is read from an active input.");
            return out;
        }
        ObjectNode metadata = relay.get().metadata(input.path("Arn").asText());
        metadata.path("Messages").forEach(message -> messages.add(message.deepCopy()));
        if (metadata.has("TransportMediaInfo")) {
            details.putObject("RouterInputMetadata").set("TransportStreamMediaInfo", metadata.get("TransportMediaInfo"));
            details.put("Timestamp", metadata.path("Timestamp").asDouble());
        }
        return out;
    }

    public ObjectNode getRouterInputThumbnail(JsonNode in, String region) {
        ObjectNode input = input(region, in.path("Arn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("Arn", input.path("Arn").asText());
        out.put("Name", input.path("Name").asText());
        ObjectNode details = out.putObject("ThumbnailDetails");
        ArrayNode messages = details.putArray("ThumbnailMessages");
        if (!"ACTIVE".equals(input.path("State").asText())) {
            messages.addObject().put("Code", "INPUT_NOT_ACTIVE").put("Message", "The router input is "
                    + input.path("State").asText() + "; thumbnails come from an active input.");
            return out;
        }
        MediaConnectRelay.Thumbnail thumbnail = relay.get().thumbnail(input.path("Arn").asText());
        if (thumbnail.image() == null) {
            messages.addObject().put("Code", "THUMBNAIL_UNAVAILABLE").put("Message", thumbnail.message());
        } else {
            details.put("Thumbnail", thumbnail.image());
            details.put("Timestamp", Instant.parse(thumbnail.at()).toEpochMilli() / 1000.0);
        }
        return out;
    }

    // ================================================================ outputs

    public ObjectNode createRouterOutput(JsonNode in, String region) {
        ObjectNode earlier = store.repeated(region, "CreateRouterOutput", in);
        if (earlier != null) {
            return earlier;
        }
        ObjectNode output = common(region, MediaConnectStore.ROUTER_OUTPUT, "routerOutput", in);
        JsonNode configuration = in.path("Configuration");
        String type = type(configuration, OUTPUT_TYPES, "output");
        checkInterfaces(region, configuration);
        output.put("State", "CREATING");
        output.put("OutputType", type);
        output.set("Configuration", configuration.deepCopy());
        output.put("RoutedState", "UNROUTED");
        output.put("AvailabilityZone", in.path("AvailabilityZone").asText(region + "a"));
        output.put("MaximumBitrate", in.path("MaximumBitrate").asLong());
        output.put("RoutingScope", in.path("RoutingScope").asText());
        output.put("Tier", in.path("Tier").asText());
        output.putArray("Messages");
        output.set("StreamDetails", streamDetails(type, configuration, false));
        output.put("IpAddress", store.config().egressIp());
        output.set("MaintenanceConfiguration", maintenanceDefaults(in.path("MaintenanceConfiguration")));
        schedule(output);
        output.set("FabricConfiguration", in.path("FabricConfiguration").isObject() ? in.get("FabricConfiguration")
                .deepCopy() : mapper.createObjectNode().put("RecoveryLatencyMode", "BALANCED"));
        later(output, MediaConnectStore.ROUTER_OUTPUT, "STANDBY", region);
        store.put(region, MediaConnectStore.ROUTER_OUTPUT, output.path("Id").asText(), output);
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterOutput", MediaConnectStore.view(output));
        store.remember(region, "CreateRouterOutput", in, out);
        return out;
    }

    private ObjectNode output(String region, String arn) {
        return find(region, MediaConnectStore.ROUTER_OUTPUT, arn, "router output");
    }

    public ObjectNode getRouterOutput(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterOutput", MediaConnectStore.view(output(region, in.path("Arn").asText())));
        return out;
    }

    public ObjectNode batchGetRouterOutput(JsonNode in, String region) {
        return batch(in, region, MediaConnectStore.ROUTER_OUTPUT, "RouterOutputs", "router output");
    }

    public ObjectNode updateRouterOutput(JsonNode in, String region) {
        ObjectNode output = output(region, in.path("Arn").asText());
        if (in.hasNonNull("Name")) {
            output.put("Name", name(in.get("Name").asText()));
        }
        if (in.has("Configuration")) {
            String type = type(in.get("Configuration"), OUTPUT_TYPES, "output");
            checkInterfaces(region, in.get("Configuration"));
            output.set("Configuration", in.get("Configuration").deepCopy());
            output.put("OutputType", type);
            output.set("StreamDetails", streamDetails(type, in.get("Configuration"), false));
        }
        MediaConnectStore.copy(in, output, "MaximumBitrate", "RoutingScope", "Tier", "FabricConfiguration");
        if (in.has("MaintenanceConfiguration")) {
            output.set("MaintenanceConfiguration", maintenanceDefaults(in.get("MaintenanceConfiguration")));
            schedule(output);
        }
        output.put("UpdatedAt", MediaConnectStore.epoch());
        store.put(region, MediaConnectStore.ROUTER_OUTPUT, output.path("Id").asText(), output);
        refresh(region, MediaConnectStore.ROUTER_OUTPUT, output);
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterOutput", MediaConnectStore.view(output));
        return out;
    }

    public ObjectNode deleteRouterOutput(JsonNode in, String region) {
        ObjectNode output = output(region, in.path("Arn").asText());
        if (!"STANDBY".equals(output.path("State").asText()) && !"ERROR".equals(output.path("State").asText())) {
            throw MediaConnectStore.badRequest("The router output is " + output.path("State").asText()
                    + "; stop it before deleting it.");
        }
        unroute(region, output);
        output.put("State", "DELETING");
        later(output, MediaConnectStore.ROUTER_OUTPUT, "GONE", region);
        store.put(region, MediaConnectStore.ROUTER_OUTPUT, output.path("Id").asText(), output);
        return stateOf(output);
    }

    public ObjectNode startRouterOutput(JsonNode in, String region) {
        return start(region, MediaConnectStore.ROUTER_OUTPUT, output(region, in.path("Arn").asText()));
    }

    public ObjectNode stopRouterOutput(JsonNode in, String region) {
        return stop(region, MediaConnectStore.ROUTER_OUTPUT, output(region, in.path("Arn").asText()));
    }

    public ObjectNode restartRouterOutput(JsonNode in, String region) {
        return restart(region, MediaConnectStore.ROUTER_OUTPUT, output(region, in.path("Arn").asText()));
    }

    public ObjectNode takeRouterInput(JsonNode in, String region) {
        ObjectNode output = output(region, in.path("RouterOutputArn").asText());
        unroute(region, output);
        ObjectNode out = mapper.createObjectNode();
        out.put("RouterOutputArn", output.path("Arn").asText());
        out.put("RouterOutputName", output.path("Name").asText());
        if (in.hasNonNull("RouterInputArn")) {
            ObjectNode input = input(region, in.get("RouterInputArn").asText());
            if (input.path("RoutedOutputs").asInt() >= input.path("MaximumRoutedOutputs").asInt(100)) {
                throw MediaConnectStore.badRequest("The router input has its maximum number of routed outputs.");
            }
            input.put("RoutedOutputs", input.path("RoutedOutputs").asInt() + 1);
            store.put(region, MediaConnectStore.ROUTER_INPUT, input.path("Id").asText(), input);
            output.put("RoutedInputArn", input.path("Arn").asText());
            output.put("RoutedState", "ROUTING");
            later(output, MediaConnectStore.ROUTER_OUTPUT, "ROUTED", region);
            out.put("RouterInputArn", input.path("Arn").asText());
            out.put("RouterInputName", input.path("Name").asText());
        } else {
            output.put("RoutedState", "UNROUTED");
        }
        output.put("UpdatedAt", MediaConnectStore.epoch());
        store.put(region, MediaConnectStore.ROUTER_OUTPUT, output.path("Id").asText(), output);
        out.put("RoutedState", output.path("RoutedState").asText());
        return out;
    }

    private void unroute(String region, ObjectNode output) {
        if (!output.hasNonNull("RoutedInputArn")) {
            return;
        }
        String inputArn = output.get("RoutedInputArn").asText();
        output.remove("RoutedInputArn");
        output.put("RoutedState", "UNROUTED");
        MediaConnectStore.meta(output).remove("routedAt");
        store.put(region, MediaConnectStore.ROUTER_OUTPUT, output.path("Id").asText(), output);
        store.get(region, MediaConnectStore.ROUTER_INPUT, MediaConnectStore.idOf(inputArn)).ifPresent(input -> {
            input.put("RoutedOutputs", Math.max(0, input.path("RoutedOutputs").asInt() - 1));
            store.put(region, MediaConnectStore.ROUTER_INPUT, input.path("Id").asText(), input);
            carry(store.account(), region, input);
        });
    }

    public ObjectNode listRouterOutputs(JsonNode in, String region) {
        JsonNode filters = in.path("Filters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode output : all(region, MediaConnectStore.ROUTER_OUTPUT)) {
            if (!matches(filters, output, "OutputTypes", "OutputType")) {
                continue;
            }
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(output, listed, "Name", "Arn", "Id", "OutputType", "State", "RoutedState",
                    "RegionName", "AvailabilityZone", "MaximumBitrate", "RoutingScope", "CreatedAt", "UpdatedAt",
                    "RoutedInputArn", "MaintenanceScheduleType", "MaintenanceSchedule");
            listed.put("MessageCount", output.path("Messages").size());
            output.path("Configuration").findValues("NetworkInterfaceArn").stream().findFirst()
                    .ifPresent(arn -> listed.put("NetworkInterfaceArn", arn.asText()));
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "RouterOutputs", 20);
        return out;
    }

    // ================================================================ network interfaces

    public ObjectNode createRouterNetworkInterface(JsonNode in, String region) {
        ObjectNode earlier = store.repeated(region, "CreateRouterNetworkInterface", in);
        if (earlier != null) {
            return earlier;
        }
        ObjectNode networkInterface = common(region, MediaConnectStore.ROUTER_INTERFACE, "routerNetworkInterface", in);
        JsonNode configuration = in.path("Configuration");
        boolean vpc = configuration.has("Vpc");
        if (vpc == configuration.has("Public")) {
            throw MediaConnectStore.badRequest("A network interface configuration is Public or Vpc.");
        }
        if (vpc) {
            JsonNode settings = configuration.get("Vpc");
            List<String> groups = new ArrayList<>();
            settings.path("SecurityGroupIds").forEach(group -> groups.add(group.asText()));
            try {
                NetworkInterface eni = ec2.get().createNetworkInterface(region, settings.path("SubnetId").asText(),
                        "MediaConnect router network interface " + networkInterface.path("Name").asText(), null,
                        null, groups, null, "interface");
                MediaConnectStore.meta(networkInterface).putArray("eni").add(eni.getNetworkInterfaceId());
            } catch (AwsException e) {
                throw MediaConnectStore.badRequest("The VPC network interface couldn't be created: "
                        + e.getMessage());
            }
        } else {
            for (JsonNode rule : configuration.path("Public").path("AllowRules")) {
                if (!MediaConnectRelay.validCidr(rule.path("Cidr").asText(""))) {
                    throw MediaConnectStore.badRequest("The allow rule " + rule.path("Cidr").asText()
                            + " is not a valid CIDR block.");
                }
            }
        }
        networkInterface.put("State", "CREATING");
        networkInterface.put("NetworkInterfaceType", vpc ? "VPC" : "PUBLIC");
        networkInterface.set("Configuration", configuration.deepCopy());
        networkInterface.put("AssociatedOutputCount", 0);
        networkInterface.put("AssociatedInputCount", 0);
        later(networkInterface, MediaConnectStore.ROUTER_INTERFACE, "ACTIVE", region);
        store.put(region, MediaConnectStore.ROUTER_INTERFACE, networkInterface.path("Id").asText(), networkInterface);
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterNetworkInterface", interfaceView(region, networkInterface));
        store.remember(region, "CreateRouterNetworkInterface", in, out);
        return out;
    }

    private ObjectNode interfaceView(String region, ObjectNode networkInterface) {
        ObjectNode view = MediaConnectStore.view(networkInterface);
        String arn = networkInterface.path("Arn").asText();
        int inputs = 0;
        int outputs = 0;
        for (ObjectNode input : store.list(region, MediaConnectStore.ROUTER_INPUT)) {
            for (JsonNode used : input.path("Configuration").findValues("NetworkInterfaceArn")) {
                inputs += arn.equals(used.asText()) ? 1 : 0;
            }
        }
        for (ObjectNode output : store.list(region, MediaConnectStore.ROUTER_OUTPUT)) {
            for (JsonNode used : output.path("Configuration").findValues("NetworkInterfaceArn")) {
                outputs += arn.equals(used.asText()) ? 1 : 0;
            }
        }
        view.put("AssociatedInputCount", inputs);
        view.put("AssociatedOutputCount", outputs);
        return view;
    }

    public ObjectNode getRouterNetworkInterface(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterNetworkInterface", interfaceView(region, networkInterface(region, in.path("Arn").asText())));
        return out;
    }

    public ObjectNode batchGetRouterNetworkInterface(JsonNode in, String region) {
        ObjectNode out = batch(in, region, MediaConnectStore.ROUTER_INTERFACE, "RouterNetworkInterfaces",
                "router network interface");
        ArrayNode viewed = mapper.createArrayNode();
        for (JsonNode found : out.path("RouterNetworkInterfaces")) {
            viewed.add(interfaceView(region, networkInterface(region, found.path("Arn").asText())));
        }
        out.set("RouterNetworkInterfaces", viewed);
        return out;
    }

    public ObjectNode updateRouterNetworkInterface(JsonNode in, String region) {
        ObjectNode networkInterface = networkInterface(region, in.path("Arn").asText());
        if (in.hasNonNull("Name")) {
            networkInterface.put("Name", name(in.get("Name").asText()));
        }
        if (in.has("Configuration")) {
            JsonNode configuration = in.get("Configuration");
            if (configuration.has("Vpc") != "VPC".equals(networkInterface.path("NetworkInterfaceType").asText())) {
                throw MediaConnectStore.badRequest("A network interface keeps its type.");
            }
            for (JsonNode rule : configuration.path("Public").path("AllowRules")) {
                if (!MediaConnectRelay.validCidr(rule.path("Cidr").asText(""))) {
                    throw MediaConnectStore.badRequest("The allow rule " + rule.path("Cidr").asText()
                            + " is not a valid CIDR block.");
                }
            }
            networkInterface.set("Configuration", configuration.deepCopy());
        }
        networkInterface.put("UpdatedAt", MediaConnectStore.epoch());
        store.put(region, MediaConnectStore.ROUTER_INTERFACE, networkInterface.path("Id").asText(), networkInterface);
        for (ObjectNode input : store.list(region, MediaConnectStore.ROUTER_INPUT)) {
            carry(store.account(), region, input);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("RouterNetworkInterface", interfaceView(region, networkInterface));
        return out;
    }

    public ObjectNode deleteRouterNetworkInterface(JsonNode in, String region) {
        ObjectNode networkInterface = networkInterface(region, in.path("Arn").asText());
        ObjectNode view = interfaceView(region, networkInterface);
        if (view.path("AssociatedInputCount").asInt() + view.path("AssociatedOutputCount").asInt() > 0) {
            throw MediaConnectStore.conflict("The network interface is used by router inputs or outputs.");
        }
        networkInterface.put("State", "DELETING");
        later(networkInterface, MediaConnectStore.ROUTER_INTERFACE, "GONE", region);
        store.put(region, MediaConnectStore.ROUTER_INTERFACE, networkInterface.path("Id").asText(), networkInterface);
        return stateOf(networkInterface);
    }

    public ObjectNode listRouterNetworkInterfaces(JsonNode in, String region) {
        JsonNode filters = in.path("Filters");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode networkInterface : all(region, MediaConnectStore.ROUTER_INTERFACE)) {
            if (!matches(filters, networkInterface, "NetworkInterfaceTypes", "NetworkInterfaceType")) {
                continue;
            }
            ObjectNode view = interfaceView(region, networkInterface);
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(view, listed, "Name", "Arn", "Id", "NetworkInterfaceType", "AssociatedOutputCount",
                    "AssociatedInputCount", "State", "RegionName", "CreatedAt", "UpdatedAt");
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "RouterNetworkInterfaces", 20);
        return out;
    }

    private void deleteInterface(String region, String eniId) {
        try {
            ec2.get().deleteNetworkInterface(region, eniId);
        } catch (RuntimeException e) {
            // Already gone.
        }
    }

    // ================================================================ tags

    /** The router resource an ARN names, for tagging. */
    ObjectNode taggable(String region, String arn) {
        String resource = MediaConnectStore.resourceOf(arn);
        String kind = resource.startsWith("routerInput:") ? MediaConnectStore.ROUTER_INPUT
                : resource.startsWith("routerOutput:") ? MediaConnectStore.ROUTER_OUTPUT
                : resource.startsWith("routerNetworkInterface:") ? MediaConnectStore.ROUTER_INTERFACE : null;
        if (kind == null) {
            return null;
        }
        return find(region, kind, arn, "router resource");
    }

    void saveTagged(String region, ObjectNode record) {
        String resource = MediaConnectStore.resourceOf(record.path("Arn").asText());
        String kind = resource.startsWith("routerInput:") ? MediaConnectStore.ROUTER_INPUT
                : resource.startsWith("routerOutput:") ? MediaConnectStore.ROUTER_OUTPUT
                : MediaConnectStore.ROUTER_INTERFACE;
        store.put(region, kind, record.path("Id").asText(), record);
    }
}
