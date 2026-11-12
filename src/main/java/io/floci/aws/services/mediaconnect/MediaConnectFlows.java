package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MediaConnect flows: their sources (one, or two for failover), outputs, entitlements, media streams and VPC
 * interfaces, and their status. A started flow is {@code STARTING}, then {@code ACTIVE} after a transition, when
 * {@link MediaConnectRelay} starts carrying its source's packets to its outputs and entitled flows; stopping it is
 * {@code STOPPING}, then {@code STANDBY}; deleting it is {@code DELETING} until it is gone. Status changes and
 * source connections are sent to the default event bus as {@code aws.mediaconnect} events. An active flow's sources
 * show one member AWS doesn't, {@code localIngestPort}: the UDP port the relay listens on for them.
 */
@ApplicationScoped
public class MediaConnectFlows {

    private static final Logger LOG = Logger.getLogger(MediaConnectFlows.class);
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
    private static final Pattern START_HOUR = Pattern.compile("([01][0-9]|2[0-3]):00");
    private static final Set<String> PUSH_OUTPUTS = Set.of("zixi-push", "rtp-fec", "rtp", "rist", "srt-caller",
            "st2110-jpegxs", "cdi", "fujitsu-qos", "udp");
    private static final Set<String> LISTENER_OUTPUTS = Set.of("zixi-pull", "srt-listener");
    private static final Set<String> PULL_SOURCES = Set.of("zixi-pull", "srt-caller");

    private final MediaConnectStore store;
    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Provider<MediaConnectRelay> relay;
    private final Provider<EventBridgeService> events;
    private final Provider<Ec2Service> ec2;

    @Inject
    public MediaConnectFlows(MediaConnectStore store, Vertx vertx, Provider<MediaConnectRelay> relay,
                             Provider<EventBridgeService> events, Provider<Ec2Service> ec2) {
        this.store = store;
        this.mapper = store.mapper();
        this.vertx = vertx;
        this.relay = relay;
        this.events = events;
        this.ec2 = ec2;
    }

    // ================================================================ lookups and transitions

    /** A flow of this account by its ARN, settled: a finished transition applied first. */
    ObjectNode flow(String region, String flowArn) {
        String id = MediaConnectStore.idOf(flowArn);
        ObjectNode flow = store.get(region, MediaConnectStore.FLOW, id).orElse(null);
        if (flow == null || !flowArn.equals(flow.path("FlowArn").asText(flowArn))) {
            throw MediaConnectStore.notFound("The flow " + flowArn + " was not found.");
        }
        settle(store.account(), region, flow);
        if (!store.get(region, MediaConnectStore.FLOW, id).isPresent()) {
            throw MediaConnectStore.notFound("The flow " + flowArn + " was not found.");
        }
        return flow;
    }

    /** Applies a transition whose time has come: STARTING to ACTIVE, STOPPING to STANDBY, DELETING to gone. */
    private void settle(String account, String region, ObjectNode flow) {
        ObjectNode meta = MediaConnectStore.meta(flow);
        long at = meta.path("transitionAt").asLong(0);
        if (at == 0 || at > System.currentTimeMillis()) {
            return;
        }
        String id = meta.path("id").asText();
        String status = flow.path("Status").asText();
        meta.remove("transitionAt");
        switch (status) {
            case "STARTING" -> {
                flow.put("Status", "ACTIVE");
                store.putForAccount(account, region, MediaConnectStore.FLOW, id, flow);
                relay.get().start(account, region, flow);
                statusChanged(account, region, flow, "STARTING");
            }
            case "STOPPING" -> {
                relay.get().stop(flow.path("FlowArn").asText());
                flow.put("Status", "STANDBY");
                store.putForAccount(account, region, MediaConnectStore.FLOW, id, flow);
                statusChanged(account, region, flow, "STOPPING");
            }
            case "UPDATING" -> {
                flow.put("Status", meta.path("statusAfterUpdate").asText("STANDBY"));
                meta.remove("statusAfterUpdate");
                store.putForAccount(account, region, MediaConnectStore.FLOW, id, flow);
            }
            case "DELETING" -> {
                relay.get().stop(flow.path("FlowArn").asText());
                releaseInterfaces(region, flow.path("VpcInterfaces"));
                store.deleteForAccount(account, region, MediaConnectStore.FLOW, id);
            }
            default -> store.putForAccount(account, region, MediaConnectStore.FLOW, id, flow);
        }
    }

    /** Schedules a flow's transition to finish after the configured time, read or not. */
    private void transition(ObjectNode flow, String status, String region) {
        String account = store.account();
        long delay = Math.max(1, store.config().transitionMillis());
        flow.put("Status", status);
        MediaConnectStore.meta(flow).put("transitionAt", System.currentTimeMillis() + delay);
        String id = MediaConnectStore.meta(flow).path("id").asText(MediaConnectStore.idOf(flow.path("FlowArn")
                .asText()));
        vertx.setTimer(delay, timer -> vertx.executeBlocking(() -> {
            RequestScopes.runAs(account, region, () -> store.getForAccount(account, region, MediaConnectStore.FLOW,
                    id).ifPresent(current -> settle(account, region, current)));
            return null;
        }, false));
    }

    private void save(String region, ObjectNode flow) {
        store.put(region, MediaConnectStore.FLOW, MediaConnectStore.idOf(flow.path("FlowArn").asText()), flow);
        if ("ACTIVE".equals(flow.path("Status").asText())) {
            relay.get().update(store.account(), region, flow);
        }
    }

    private void statusChanged(String account, String region, ObjectNode flow, String previous) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("status", flow.path("Status").asText());
        detail.put("previous-status", previous);
        detail.put("flow-arn", flow.path("FlowArn").asText());
        publish(account, region, "MediaConnect Flow Status Change", detail, flow.path("FlowArn").asText());
    }

    /** Sends an {@code aws.mediaconnect} event to the account's default bus; a failed send is logged. */
    void publish(String account, String region, String detailType, ObjectNode detail, String resourceArn) {
        try {
            RequestScopes.runAs(account, region, () -> events.get().publishServiceEvent("aws.mediaconnect",
                    detailType, detail.toString(), resourceArn, region));
        } catch (RuntimeException e) {
            LOG.debugv("MediaConnect could not send {0} for {1}: {2}", detailType, resourceArn, e.getMessage());
        }
    }

    // ================================================================ flows

    public ObjectNode createFlow(JsonNode in, String region) {
        String name = name(in.path("Name").asText(null), "flow");
        for (ObjectNode existing : store.list(region, MediaConnectStore.FLOW)) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("A flow named " + name + " already exists.");
            }
        }
        if (store.list(region, MediaConnectStore.FLOW).size() >= store.config().maxFlows()) {
            throw MediaConnectStore.limit("CreateFlow", "The account has the maximum number of flows.");
        }
        if (in.has("Source") && in.has("Sources")) {
            throw MediaConnectStore.badRequest("Specify Source or Sources, not both.");
        }
        String id = MediaConnectStore.flowId();
        ObjectNode flow = store.record();
        flow.put("FlowArn", store.arn(region, "flow:" + id + ":" + name));
        flow.put("Name", name);
        flow.put("Description", name);
        flow.put("Status", "STANDBY");
        flow.put("EgressIp", store.config().egressIp());
        flow.put("FlowSize", in.path("FlowSize").asText("MEDIUM"));
        MediaConnectStore.copy(in, flow, "NdiConfig", "EncodingConfig");
        flow.set("SourceMonitoringConfig", monitoring(in.path("SourceMonitoringConfig"), null));
        flow.set("Maintenance", maintenance(in.path("Maintenance"), null));
        flow.putArray("VpcInterfaces");
        flow.putArray("MediaStreams");
        flow.putArray("Outputs");
        flow.putArray("Entitlements");
        flow.putArray("Sources");
        MediaConnectStore.meta(flow).put("id", id);
        String zone = in.path("AvailabilityZone").asText(null);
        List<String> created = new ArrayList<>();
        try {
            for (JsonNode request : in.path("VpcInterfaces")) {
                ObjectNode vpcInterface = vpcInterface(region, flow, request, created);
                String subnetZone = MediaConnectStore.meta(vpcInterface).path("zone").asText(null);
                if (zone != null && subnetZone != null && !zone.equals(subnetZone)) {
                    throw MediaConnectStore.badRequest("The VPC interface " + request.path("Name").asText()
                            + " is in " + subnetZone + ", not the flow's Availability Zone " + zone + ".");
                }
                zone = zone == null ? subnetZone : zone;
                vpcInterface.remove("_");
                ((ArrayNode) flow.get("VpcInterfaces")).add(vpcInterface);
            }
            flow.put("AvailabilityZone", zone(region, zone));
            for (JsonNode request : in.path("MediaStreams")) {
                ((ArrayNode) flow.get("MediaStreams")).add(mediaStream(flow, request));
            }
            List<JsonNode> sources = new ArrayList<>();
            if (in.has("Source")) {
                sources.add(in.get("Source"));
            }
            in.path("Sources").forEach(sources::add);
            if (sources.size() > 2) {
                throw MediaConnectStore.badRequest("A flow can have at most two sources.");
            }
            for (JsonNode request : sources) {
                addSource(region, flow, id, request);
            }
            if (in.has("SourceFailoverConfig")) {
                flow.set("SourceFailoverConfig", failover(in.get("SourceFailoverConfig"), null, flow));
            }
            for (JsonNode request : in.path("Outputs")) {
                addOutput(region, flow, id, request);
            }
            for (JsonNode request : in.path("Entitlements")) {
                grant(region, flow, id, request);
            }
        } catch (RuntimeException e) {
            created.forEach(eni -> deleteInterface(region, eni));
            throw e;
        }
        primary(flow);
        MediaConnectStore.addTags(flow, in.path("FlowTags"));
        store.put(region, MediaConnectStore.FLOW, id, flow);
        ObjectNode out = mapper.createObjectNode();
        out.set("Flow", view(flow));
        return out;
    }

    public ObjectNode describeFlow(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("Flow", view(flow));
        ArrayNode errors = mapper.createArrayNode();
        relay.get().errors(flow.path("FlowArn").asText()).forEach(errors::add);
        if (!errors.isEmpty()) {
            out.putObject("Messages").set("Errors", errors);
        }
        return out;
    }

    public ObjectNode updateFlow(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        if (in.has("SourceFailoverConfig")) {
            flow.set("SourceFailoverConfig", failover(in.get("SourceFailoverConfig"), flow.get(
                    "SourceFailoverConfig"), flow));
            primary(flow);
        }
        if (in.has("Maintenance")) {
            flow.set("Maintenance", maintenance(in.get("Maintenance"), flow.get("Maintenance")));
        }
        if (in.has("SourceMonitoringConfig")) {
            flow.set("SourceMonitoringConfig", monitoring(in.get("SourceMonitoringConfig"),
                    flow.get("SourceMonitoringConfig")));
        }
        if (in.has("FlowSize")) {
            if ("ACTIVE".equals(flow.path("Status").asText())) {
                throw MediaConnectStore.badRequest("A flow's size changes only while it is in STANDBY.");
            }
            flow.put("FlowSize", in.get("FlowSize").asText());
        }
        MediaConnectStore.copy(in, flow, "NdiConfig", "EncodingConfig");
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.set("Flow", view(flow));
        return out;
    }

    public ObjectNode deleteFlow(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        String status = flow.path("Status").asText();
        if (!"STANDBY".equals(status) && !"ERROR".equals(status)) {
            throw MediaConnectStore.badRequest("The flow is " + status + "; stop it before deleting it.");
        }
        transition(flow, "DELETING", region);
        store.put(region, MediaConnectStore.FLOW, MediaConnectStore.meta(flow).path("id").asText(), flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("Status", "DELETING");
        return out;
    }

    public ObjectNode listFlows(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, MediaConnectStore.FLOW)) {
            settle(store.account(), region, stored);
            ObjectNode flow = store.get(region, MediaConnectStore.FLOW, MediaConnectStore.meta(stored).path("id")
                    .asText()).orElse(null);
            if (flow == null) {
                continue;
            }
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(flow, listed, "AvailabilityZone", "Description", "FlowArn", "Name", "Status",
                    "Maintenance");
            listed.put("SourceType", flow.path("Source").hasNonNull("EntitlementArn") ? "ENTITLED" : "OWNED");
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "Flows", 10);
        return out;
    }

    public ObjectNode startFlow(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        String status = flow.path("Status").asText();
        if (!"STANDBY".equals(status) && !"ERROR".equals(status)) {
            throw MediaConnectStore.badRequest("The flow is " + status + " and can't be started.");
        }
        if (!flow.path("Source").isObject() || flow.path("Source").isEmpty()) {
            throw MediaConnectStore.badRequest("The flow has no source.");
        }
        transition(flow, "STARTING", region);
        store.put(region, MediaConnectStore.FLOW, MediaConnectStore.meta(flow).path("id").asText(), flow);
        statusChanged(store.account(), region, flow, status);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("Status", "STARTING");
        return out;
    }

    public ObjectNode stopFlow(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        String status = flow.path("Status").asText();
        if (!"ACTIVE".equals(status) && !"STARTING".equals(status)) {
            throw MediaConnectStore.badRequest("The flow is " + status + " and can't be stopped.");
        }
        transition(flow, "STOPPING", region);
        store.put(region, MediaConnectStore.FLOW, MediaConnectStore.meta(flow).path("id").asText(), flow);
        statusChanged(store.account(), region, flow, status);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("Status", "STOPPING");
        return out;
    }

    private static void requireSettled(ObjectNode flow) {
        String status = flow.path("Status").asText();
        if (!"STANDBY".equals(status) && !"ACTIVE".equals(status) && !"ERROR".equals(status)) {
            throw MediaConnectStore.badRequest("The flow is " + status + "; wait for it to finish.");
        }
    }

    /** A flow as the API shows it: its primary source first, the internal members dropped. */
    ObjectNode view(ObjectNode flow) {
        ObjectNode view = MediaConnectStore.view(flow);
        if (view.path("Sources").size() < 2 && !view.has("SourceFailoverConfig")) {
            view.remove("Sources");
        }
        for (JsonNode source : view.path("Sources")) {
            ((ObjectNode) source).remove("_");
        }
        if (view.path("Source").isObject()) {
            ((ObjectNode) view.get("Source")).remove("_");
            int port = relay.get().localPort(flow.path("FlowArn").asText(), view.path("Source").path("SourceArn")
                    .asText());
            if (port > 0) {
                ((ObjectNode) view.get("Source")).put("localIngestPort", port);
            }
        }
        for (JsonNode source : view.path("Sources")) {
            int port = relay.get().localPort(flow.path("FlowArn").asText(), source.path("SourceArn").asText());
            if (port > 0) {
                ((ObjectNode) source).put("localIngestPort", port);
            }
        }
        for (JsonNode output : view.path("Outputs")) {
            ((ObjectNode) output).remove("_");
        }
        for (JsonNode entitlement : view.path("Entitlements")) {
            ((ObjectNode) entitlement).remove("_");
        }
        return view;
    }

    // ================================================================ sources

    private static String name(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw MediaConnectStore.badRequest("The " + what + " name must be 1-128 letters, digits, hyphens and "
                    + "underscores.");
        }
        return name;
    }

    private String zone(String region, String zone) {
        if (zone == null) {
            return region + "a";
        }
        if (!zone.startsWith(region) || zone.length() != region.length() + 1) {
            throw MediaConnectStore.badRequest("The Availability Zone " + zone + " isn't in " + region + ".");
        }
        return zone;
    }

    /** Makes the source failover's primary (or the first) source the flow's {@code Source}. */
    private static void primary(ObjectNode flow) {
        ArrayNode sources = (ArrayNode) flow.withArray("Sources");
        if (sources.isEmpty()) {
            flow.remove("Source");
            return;
        }
        String wanted = flow.path("SourceFailoverConfig").path("SourcePriority").path("PrimarySource").asText(null);
        JsonNode chosen = sources.get(0);
        for (JsonNode source : sources) {
            if (source.path("Name").asText().equals(wanted)) {
                chosen = source;
            }
        }
        flow.set("Source", chosen.deepCopy());
    }

    private ObjectNode addSource(String region, ObjectNode flow, String flowId, JsonNode in) {
        String name = name(in.path("Name").asText(null), "source");
        for (JsonNode existing : flow.withArray("Sources")) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("The flow already has a source named " + name + ".");
            }
        }
        ObjectNode source = mapper.createObjectNode();
        source.put("SourceArn", store.arn(region, "source:" + flowId + ":" + name));
        source.put("Name", name);
        applySource(region, flow, source, in);
        ((ArrayNode) flow.withArray("Sources")).add(source);
        return source;
    }

    /** Applies a source request (a new source's or an update's members) to {@code source}. */
    private void applySource(String region, ObjectNode flow, ObjectNode source, JsonNode in) {
        MediaConnectStore.copy(in, source, "Description", "VpcInterfaceName", "GatewayBridgeSource",
                "RouterIntegrationState", "RouterIntegrationTransitDecryption");
        if (in.has("Decryption")) {
            source.set("Decryption", in.get("Decryption").deepCopy());
        }
        if (in.hasNonNull("EntitlementArn")) {
            ObjectNode entitlement = entitlementFor(in.get("EntitlementArn").asText());
            source.put("EntitlementArn", entitlement.path("EntitlementArn").asText());
            source.put("DataTransferSubscriberFeePercent", entitlement.path("DataTransferSubscriberFeePercent")
                    .asInt(0));
            source.remove("Transport");
            source.remove("IngestIp");
            source.remove("IngestPort");
            source.remove("WhitelistCidr");
            return;
        }
        ObjectNode transport = source.has("Transport") ? (ObjectNode) source.get("Transport")
                : mapper.createObjectNode();
        MediaConnectStore.copy(in, transport, "Protocol", "MaxBitrate", "MaxLatency", "MaxSyncBuffer", "MinLatency",
                "SenderControlPort", "SenderIpAddress", "SourceListenerAddress", "SourceListenerPort", "StreamId",
                "NdiSourceSettings");
        if (!transport.has("Protocol") && !in.has("GatewayBridgeSource")) {
            throw MediaConnectStore.badRequest("The source needs a Protocol or an EntitlementArn.");
        }
        String protocol = transport.path("Protocol").asText("");
        if (in.hasNonNull("WhitelistCidr")) {
            String cidr = in.get("WhitelistCidr").asText();
            if (!MediaConnectRelay.validCidr(cidr)) {
                throw MediaConnectStore.badRequest("The WhitelistCidr " + cidr + " is not a valid CIDR block.");
            }
            source.put("WhitelistCidr", cidr);
        }
        if (in.has("IngestPort")) {
            source.put("IngestPort", in.get("IngestPort").asInt());
        }
        if ("zixi-push".equals(protocol)) {
            source.put("IngestPort", 2088);
        }
        if (PULL_SOURCES.contains(protocol)) {
            if (!transport.has("SourceListenerAddress") && !"zixi-pull".equals(protocol)) {
                throw MediaConnectStore.badRequest("An " + protocol + " source needs a SourceListenerAddress.");
            }
        } else if (!protocol.isEmpty() && !"ndi-speed-hq".equals(protocol) && !"cdi".equals(protocol)
                && !"st2110-jpegxs".equals(protocol) && !source.has("IngestPort")) {
            throw MediaConnectStore.badRequest("An " + protocol + " source needs an IngestPort.");
        }
        if (in.has("MediaStreamSourceConfigurations")) {
            ArrayNode configurations = source.putArray("MediaStreamSourceConfigurations");
            for (JsonNode configuration : in.get("MediaStreamSourceConfigurations")) {
                requireMediaStream(flow, configuration.path("MediaStreamName").asText());
                ObjectNode copy = configuration.deepCopy();
                for (JsonNode input : copy.path("InputConfigurations")) {
                    ((ObjectNode) input).put("InputIp", store.config().ingestIp());
                }
                configurations.add(copy);
            }
        }
        if (in.hasNonNull("VpcInterfaceName")) {
            requireVpcInterface(flow, in.get("VpcInterfaceName").asText());
        }
        source.put("IngestIp", store.config().ingestIp());
        source.set("Transport", transport);
    }

    public ObjectNode addFlowSources(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        if (flow.withArray("Sources").size() + in.path("Sources").size() > 2) {
            throw MediaConnectStore.badRequest("A flow can have at most two sources.");
        }
        ArrayNode added = mapper.createArrayNode();
        for (JsonNode request : in.path("Sources")) {
            added.add(addSource(region, flow, MediaConnectStore.meta(flow).path("id").asText(), request));
        }
        primary(flow);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Sources", added);
        return out;
    }

    public ObjectNode updateFlowSource(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ObjectNode source = find(flow.withArray("Sources"), "SourceArn", in.path("SourceArn").asText(), "source");
        applySource(region, flow, source, in);
        primary(flow);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Source", source.deepCopy());
        return out;
    }

    public ObjectNode removeFlowSource(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode sources = (ArrayNode) flow.withArray("Sources");
        ObjectNode source = find(sources, "SourceArn", in.path("SourceArn").asText(), "source");
        if (sources.size() == 1 && "ACTIVE".equals(flow.path("Status").asText())) {
            throw MediaConnectStore.badRequest("An active flow's only source can't be removed.");
        }
        remove(sources, source);
        primary(flow);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("SourceArn", in.path("SourceArn").asText());
        return out;
    }

    private ObjectNode failover(JsonNode in, JsonNode current, ObjectNode flow) {
        ObjectNode config = current instanceof ObjectNode existing ? existing.deepCopy() : mapper.createObjectNode();
        MediaConnectStore.copy(in, config, "FailoverMode", "RecoveryWindow", "SourcePriority", "State");
        if (!config.has("State")) {
            config.put("State", "DISABLED");
        }
        if (!config.has("FailoverMode")) {
            config.put("FailoverMode", "MERGE");
        }
        if ("MERGE".equals(config.path("FailoverMode").asText()) && !config.has("RecoveryWindow")) {
            config.put("RecoveryWindow", 200);
        }
        String primary = config.path("SourcePriority").path("PrimarySource").asText(null);
        if (primary != null) {
            boolean known = false;
            for (JsonNode source : flow.withArray("Sources")) {
                known |= primary.equals(source.path("Name").asText());
            }
            if (!known) {
                throw MediaConnectStore.badRequest("The primary source " + primary + " isn't one of the flow's.");
            }
        }
        return config;
    }

    // ================================================================ outputs

    private ObjectNode addOutput(String region, ObjectNode flow, String flowId, JsonNode in) {
        String name = name(in.path("Name").asText(null), "output");
        ArrayNode outputs = (ArrayNode) flow.withArray("Outputs");
        for (JsonNode existing : outputs) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("The flow already has an output named " + name + ".");
            }
        }
        if (outputs.size() >= 50) {
            throw MediaConnectStore.limit("AddFlowOutputs", "A flow can have at most 50 outputs.");
        }
        ObjectNode output = mapper.createObjectNode();
        output.put("OutputArn", store.arn(region, "output:" + flowId + ":" + name));
        output.put("Name", name);
        output.put("OutputStatus", "ENABLED");
        applyOutput(flow, output, in);
        outputs.add(output);
        return output;
    }

    private void applyOutput(ObjectNode flow, ObjectNode output, JsonNode in) {
        MediaConnectStore.copy(in, output, "Description", "Destination", "Port", "VpcInterfaceAttachment",
                "OutputStatus", "RouterIntegrationState", "RouterIntegrationTransitEncryption");
        if (in.has("Encryption")) {
            output.set("Encryption", in.get("Encryption").deepCopy());
        }
        ObjectNode transport = output.has("Transport") ? (ObjectNode) output.get("Transport")
                : mapper.createObjectNode();
        MediaConnectStore.copy(in, transport, "Protocol", "CidrAllowList", "MaxLatency", "MinLatency", "RemoteId",
                "SenderControlPort", "SmoothingLatency", "StreamId", "NdiSpeedHqQuality", "NdiProgramName",
                "NdiOutputTimecodeSource", "NdiAudioReferenceLevel");
        if (in.hasNonNull("SenderIpAddress")) {
            transport.put("SenderIpAddress", in.get("SenderIpAddress").asText());
        }
        String protocol = transport.path("Protocol").asText(null);
        if (protocol == null && !"ENABLED".equals(output.path("RouterIntegrationState").asText())) {
            throw MediaConnectStore.badRequest("The output needs a Protocol.");
        }
        if (protocol != null && PUSH_OUTPUTS.contains(protocol) && !"cdi".equals(protocol)
                && !"st2110-jpegxs".equals(protocol)
                && (!output.hasNonNull("Destination") || !output.hasNonNull("Port"))) {
            throw MediaConnectStore.badRequest("An " + protocol + " output needs a Destination and a Port.");
        }
        if (protocol != null && LISTENER_OUTPUTS.contains(protocol)) {
            output.put("ListenerAddress", store.config().egressIp());
            if ("zixi-pull".equals(protocol)) {
                output.put("Port", 2088);
            } else if (!output.has("Port")) {
                throw MediaConnectStore.badRequest("An srt-listener output needs a Port.");
            }
        }
        for (JsonNode cidr : transport.path("CidrAllowList")) {
            if (!MediaConnectRelay.validCidr(cidr.asText())) {
                throw MediaConnectStore.badRequest("The CIDR " + cidr.asText() + " is not a valid CIDR block.");
            }
        }
        if (in.hasNonNull("VpcInterfaceAttachment")) {
            requireVpcInterface(flow, in.path("VpcInterfaceAttachment").path("VpcInterfaceName").asText());
        }
        if (in.has("MediaStreamOutputConfigurations")) {
            ArrayNode configurations = output.putArray("MediaStreamOutputConfigurations");
            for (JsonNode configuration : in.get("MediaStreamOutputConfigurations")) {
                requireMediaStream(flow, configuration.path("MediaStreamName").asText());
                ObjectNode copy = configuration.deepCopy();
                for (JsonNode destination : copy.path("DestinationConfigurations")) {
                    ((ObjectNode) destination).put("OutboundIp", store.config().egressIp());
                }
                configurations.add(copy);
            }
        }
        if (protocol != null) {
            output.set("Transport", transport);
        }
    }

    public ObjectNode addFlowOutputs(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode added = mapper.createArrayNode();
        for (JsonNode request : in.path("Outputs")) {
            added.add(addOutput(region, flow, MediaConnectStore.meta(flow).path("id").asText(), request));
        }
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Outputs", added);
        return out;
    }

    public ObjectNode updateFlowOutput(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ObjectNode output = find(flow.withArray("Outputs"), "OutputArn", in.path("OutputArn").asText(), "output");
        if (output.hasNonNull("EntitlementArn")) {
            throw MediaConnectStore.badRequest("An entitlement's output changes through its entitlement.");
        }
        applyOutput(flow, output, in);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Output", output.deepCopy());
        return out;
    }

    public ObjectNode removeFlowOutput(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode outputs = (ArrayNode) flow.withArray("Outputs");
        ObjectNode output = find(outputs, "OutputArn", in.path("OutputArn").asText(), "output");
        if (output.hasNonNull("EntitlementArn")) {
            throw MediaConnectStore.badRequest("An entitlement's output is removed by revoking the entitlement.");
        }
        remove(outputs, output);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("OutputArn", in.path("OutputArn").asText());
        return out;
    }

    // ================================================================ entitlements

    private ObjectNode grant(String region, ObjectNode flow, String flowId, JsonNode in) {
        String name = in.hasNonNull("Name") ? name(in.get("Name").asText(), "entitlement")
                : "entitlement-" + MediaConnectStore.random(8, "abcdefghijklmnopqrstuvwxyz0123456789");
        ArrayNode entitlements = (ArrayNode) flow.withArray("Entitlements");
        for (JsonNode existing : entitlements) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("The flow already has an entitlement named " + name + ".");
            }
        }
        if (entitlements.size() >= 50) {
            throw MediaConnectStore.limit("GrantFlowEntitlements", "A flow can have at most 50 entitlements.");
        }
        if (!in.path("Subscribers").isArray() || in.path("Subscribers").isEmpty()) {
            throw MediaConnectStore.badRequest("An entitlement needs at least one subscriber.");
        }
        for (JsonNode subscriber : in.path("Subscribers")) {
            if (!subscriber.asText().matches("[0-9]{12}")) {
                throw MediaConnectStore.badRequest("The subscriber " + subscriber.asText()
                        + " is not an AWS account ID.");
            }
        }
        int fee = in.path("DataTransferSubscriberFeePercent").asInt(0);
        if (fee < 0 || fee > 100) {
            throw MediaConnectStore.badRequest("DataTransferSubscriberFeePercent is between 0 and 100.");
        }
        ObjectNode entitlement = mapper.createObjectNode();
        entitlement.put("EntitlementArn", store.arn(region, "entitlement:" + flowId + ":" + name));
        entitlement.put("Name", name);
        entitlement.put("DataTransferSubscriberFeePercent", fee);
        entitlement.put("EntitlementStatus", in.path("EntitlementStatus").asText("ENABLED"));
        entitlement.set("Subscribers", in.get("Subscribers").deepCopy());
        MediaConnectStore.copy(in, entitlement, "Description", "Encryption");
        MediaConnectStore.addTags(entitlement, in.path("EntitlementTags"));
        entitlements.add(entitlement);
        return entitlement;
    }

    public ObjectNode grantFlowEntitlements(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode granted = mapper.createArrayNode();
        for (JsonNode request : in.path("Entitlements")) {
            ObjectNode entitlement = grant(region, flow, MediaConnectStore.meta(flow).path("id").asText(), request);
            ObjectNode shown = entitlement.deepCopy();
            shown.remove("_");
            granted.add(shown);
        }
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Entitlements", granted);
        return out;
    }

    public ObjectNode updateFlowEntitlement(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ObjectNode entitlement = find(flow.withArray("Entitlements"), "EntitlementArn",
                in.path("EntitlementArn").asText(), "entitlement");
        MediaConnectStore.copy(in, entitlement, "Description", "EntitlementStatus", "Subscribers");
        if (in.has("Encryption")) {
            ObjectNode encryption = entitlement.has("Encryption") ? (ObjectNode) entitlement.get("Encryption")
                    : entitlement.putObject("Encryption");
            encryption.setAll((ObjectNode) in.get("Encryption"));
        }
        save(region, flow);
        ObjectNode shown = entitlement.deepCopy();
        shown.remove("_");
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("Entitlement", shown);
        return out;
    }

    public ObjectNode revokeFlowEntitlement(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        ArrayNode entitlements = (ArrayNode) flow.withArray("Entitlements");
        ObjectNode entitlement = find(entitlements, "EntitlementArn", in.path("EntitlementArn").asText(),
                "entitlement");
        remove(entitlements, entitlement);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("EntitlementArn", in.path("EntitlementArn").asText());
        return out;
    }

    /** The entitlement an ARN names, from any account's flow, granted to this account and enabled. */
    private ObjectNode entitlementFor(String entitlementArn) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaConnectStore.FLOW)) {
            for (JsonNode entitlement : entry.value().path("Entitlements")) {
                if (!entitlementArn.equals(entitlement.path("EntitlementArn").asText())) {
                    continue;
                }
                boolean subscribed = false;
                for (JsonNode subscriber : entitlement.path("Subscribers")) {
                    subscribed |= store.account().equals(subscriber.asText());
                }
                if (!subscribed) {
                    throw MediaConnectStore.forbidden("The entitlement " + entitlementArn
                            + " isn't granted to this account.");
                }
                if (!"ENABLED".equals(entitlement.path("EntitlementStatus").asText("ENABLED"))) {
                    throw MediaConnectStore.badRequest("The entitlement " + entitlementArn + " is disabled.");
                }
                return (ObjectNode) entitlement;
            }
        }
        throw MediaConnectStore.notFound("The entitlement " + entitlementArn + " was not found.");
    }

    public ObjectNode listEntitlements(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaConnectStore.FLOW)) {
            if (!region.equals(entry.value().path("_").path("region").asText(region))) {
                continue;
            }
            for (JsonNode entitlement : entry.value().path("Entitlements")) {
                boolean subscribed = false;
                for (JsonNode subscriber : entitlement.path("Subscribers")) {
                    subscribed |= store.account().equals(subscriber.asText());
                }
                if (subscribed) {
                    ObjectNode listed = mapper.createObjectNode();
                    listed.put("EntitlementArn", entitlement.path("EntitlementArn").asText());
                    listed.put("EntitlementName", entitlement.path("Name").asText());
                    listed.put("DataTransferSubscriberFeePercent",
                            entitlement.path("DataTransferSubscriberFeePercent").asInt(0));
                    items.add(listed);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "Entitlements", 20);
        return out;
    }

    // ================================================================ media streams

    private ObjectNode mediaStream(ObjectNode flow, JsonNode in) {
        String name = in.path("MediaStreamName").asText(null);
        if (name == null || name.isBlank()) {
            throw MediaConnectStore.badRequest("A media stream needs a MediaStreamName.");
        }
        for (JsonNode existing : flow.withArray("MediaStreams")) {
            if (name.equals(existing.path("MediaStreamName").asText())
                    || in.path("MediaStreamId").asInt() == existing.path("MediaStreamId").asInt()) {
                throw MediaConnectStore.conflict("The flow already has a media stream named " + name
                        + " or with id " + in.path("MediaStreamId").asInt() + ".");
            }
        }
        ObjectNode stream = mapper.createObjectNode();
        MediaConnectStore.copy(in, stream, "MediaStreamId", "MediaStreamName", "MediaStreamType", "Description",
                "VideoFormat");
        String type = in.path("MediaStreamType").asText("video");
        stream.put("ClockRate", in.path("ClockRate").asInt("audio".equals(type) ? 48000 : 90000));
        stream.put("Fmt", 96 + flow.withArray("MediaStreams").size());
        if (in.has("Attributes")) {
            ObjectNode attributes = in.get("Attributes").deepCopy();
            if (!attributes.has("Fmtp")) {
                attributes.putObject("Fmtp");
            }
            stream.set("Attributes", attributes);
        }
        return stream;
    }

    private static void requireMediaStream(ObjectNode flow, String name) {
        for (JsonNode stream : flow.withArray("MediaStreams")) {
            if (name.equals(stream.path("MediaStreamName").asText())) {
                return;
            }
        }
        throw MediaConnectStore.badRequest("The flow has no media stream named " + name + ".");
    }

    public ObjectNode addFlowMediaStreams(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode added = mapper.createArrayNode();
        for (JsonNode request : in.path("MediaStreams")) {
            ObjectNode stream = mediaStream(flow, request);
            ((ArrayNode) flow.withArray("MediaStreams")).add(stream);
            added.add(stream.deepCopy());
        }
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("MediaStreams", added);
        return out;
    }

    public ObjectNode updateFlowMediaStream(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ObjectNode stream = find(flow.withArray("MediaStreams"), "MediaStreamName",
                in.path("MediaStreamName").asText(), "media stream");
        MediaConnectStore.copy(in, stream, "ClockRate", "Description", "MediaStreamType", "VideoFormat");
        if (in.has("Attributes")) {
            ObjectNode attributes = in.get("Attributes").deepCopy();
            if (!attributes.has("Fmtp")) {
                attributes.putObject("Fmtp");
            }
            stream.set("Attributes", attributes);
        }
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("MediaStream", stream.deepCopy());
        return out;
    }

    public ObjectNode removeFlowMediaStream(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        String name = in.path("MediaStreamName").asText();
        ArrayNode streams = (ArrayNode) flow.withArray("MediaStreams");
        ObjectNode stream = find(streams, "MediaStreamName", name, "media stream");
        for (String kind : List.of("Sources", "Outputs")) {
            for (JsonNode user : flow.withArray(kind)) {
                for (JsonNode configuration : user.path("MediaStreamSourceConfigurations")) {
                    if (name.equals(configuration.path("MediaStreamName").asText())) {
                        throw MediaConnectStore.badRequest("The media stream " + name + " is used by "
                                + user.path("Name").asText() + ".");
                    }
                }
                for (JsonNode configuration : user.path("MediaStreamOutputConfigurations")) {
                    if (name.equals(configuration.path("MediaStreamName").asText())) {
                        throw MediaConnectStore.badRequest("The media stream " + name + " is used by "
                                + user.path("Name").asText() + ".");
                    }
                }
            }
        }
        remove(streams, stream);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("MediaStreamName", name);
        return out;
    }

    // ================================================================ VPC interfaces

    /** A VPC interface with its network interface created in the subnet; {@code _.zone} is the subnet's zone. */
    private ObjectNode vpcInterface(String region, ObjectNode flow, JsonNode in, List<String> created) {
        String name = name(in.path("Name").asText(null), "VPC interface");
        for (JsonNode existing : flow.withArray("VpcInterfaces")) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("The flow already has a VPC interface named " + name + ".");
            }
        }
        String subnetId = in.path("SubnetId").asText(null);
        String role = in.path("RoleArn").asText(null);
        if (subnetId == null || role == null || !AwsArnUtils.isArn(role)) {
            throw MediaConnectStore.badRequest("A VPC interface needs a SubnetId and a RoleArn.");
        }
        Subnet subnet = ec2.get().findSubnetById(region, subnetId).orElseThrow(() ->
                MediaConnectStore.badRequest("The subnet " + subnetId + " does not exist."));
        List<String> groups = new ArrayList<>();
        in.path("SecurityGroupIds").forEach(group -> groups.add(group.asText()));
        NetworkInterface eni;
        try {
            eni = ec2.get().createNetworkInterface(region, subnetId, "MediaConnect VPC interface " + name + " of "
                    + flow.path("Name").asText(), null, null, groups, null, "interface");
        } catch (AwsException e) {
            throw MediaConnectStore.badRequest("The VPC interface " + name + " couldn't be created: "
                    + e.getMessage());
        }
        created.add(eni.getNetworkInterfaceId());
        ObjectNode vpcInterface = mapper.createObjectNode();
        vpcInterface.put("Name", name);
        vpcInterface.putArray("NetworkInterfaceIds").add(eni.getNetworkInterfaceId());
        vpcInterface.put("NetworkInterfaceType", in.path("NetworkInterfaceType").asText("ena"));
        vpcInterface.put("RoleArn", role);
        ArrayNode securityGroups = vpcInterface.putArray("SecurityGroupIds");
        groups.forEach(securityGroups::add);
        vpcInterface.put("SubnetId", subnetId);
        MediaConnectStore.meta(vpcInterface).put("zone", subnet.getAvailabilityZone());
        return vpcInterface;
    }

    private void deleteInterface(String region, String eniId) {
        try {
            ec2.get().deleteNetworkInterface(region, eniId);
        } catch (RuntimeException e) {
            LOG.debugv("MediaConnect could not delete network interface {0}: {1}", eniId, e.getMessage());
        }
    }

    private void releaseInterfaces(String region, JsonNode interfaces) {
        for (JsonNode vpcInterface : interfaces) {
            vpcInterface.path("NetworkInterfaceIds").forEach(eni -> deleteInterface(region, eni.asText()));
        }
    }

    private static void requireVpcInterface(ObjectNode flow, String name) {
        for (JsonNode vpcInterface : flow.withArray("VpcInterfaces")) {
            if (name.equals(vpcInterface.path("Name").asText())) {
                return;
            }
        }
        throw MediaConnectStore.badRequest("The flow has no VPC interface named " + name + ".");
    }

    public ObjectNode addFlowVpcInterfaces(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        ArrayNode added = mapper.createArrayNode();
        List<String> created = new ArrayList<>();
        try {
            for (JsonNode request : in.path("VpcInterfaces")) {
                ObjectNode vpcInterface = vpcInterface(region, flow, request, created);
                String zone = MediaConnectStore.meta(vpcInterface).path("zone").asText(null);
                if (zone != null && !zone.equals(flow.path("AvailabilityZone").asText())) {
                    throw MediaConnectStore.badRequest("The subnet " + request.path("SubnetId").asText()
                            + " is in " + zone + ", not the flow's Availability Zone "
                            + flow.path("AvailabilityZone").asText() + ".");
                }
                vpcInterface.remove("_");
                ((ArrayNode) flow.withArray("VpcInterfaces")).add(vpcInterface);
                added.add(vpcInterface.deepCopy());
            }
        } catch (RuntimeException e) {
            created.forEach(eni -> deleteInterface(region, eni));
            throw e;
        }
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.set("VpcInterfaces", added);
        return out;
    }

    public ObjectNode removeFlowVpcInterface(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        requireSettled(flow);
        String name = in.path("VpcInterfaceName").asText();
        ArrayNode interfaces = (ArrayNode) flow.withArray("VpcInterfaces");
        ObjectNode vpcInterface = find(interfaces, "Name", name, "VPC interface");
        for (JsonNode source : flow.withArray("Sources")) {
            if (name.equals(source.path("VpcInterfaceName").asText())) {
                throw MediaConnectStore.badRequest("The VPC interface " + name + " is used by the source "
                        + source.path("Name").asText() + ".");
            }
        }
        for (JsonNode output : flow.withArray("Outputs")) {
            if (name.equals(output.path("VpcInterfaceAttachment").path("VpcInterfaceName").asText())) {
                throw MediaConnectStore.badRequest("The VPC interface " + name + " is used by the output "
                        + output.path("Name").asText() + ".");
            }
        }
        releaseInterfaces(region, mapper.createArrayNode().add(vpcInterface));
        remove(interfaces, vpcInterface);
        save(region, flow);
        ObjectNode out = mapper.createObjectNode();
        out.put("FlowArn", flow.path("FlowArn").asText());
        out.put("VpcInterfaceName", name);
        out.putArray("NonDeletedNetworkInterfaceIds");
        return out;
    }

    // ================================================================ maintenance and monitoring

    private ObjectNode maintenance(JsonNode in, JsonNode current) {
        ObjectNode maintenance = current instanceof ObjectNode existing ? existing.deepCopy()
                : mapper.createObjectNode();
        if (!maintenance.has("MaintenanceDay")) {
            maintenance.put("MaintenanceDay", "Sunday");
            maintenance.put("MaintenanceStartHour", "02:00");
        }
        MediaConnectStore.copy(in, maintenance, "MaintenanceDay", "MaintenanceStartHour",
                "MaintenanceScheduledDate");
        String hour = maintenance.path("MaintenanceStartHour").asText();
        if (!START_HOUR.matcher(hour).matches()) {
            throw MediaConnectStore.badRequest("MaintenanceStartHour is an hour, HH:00, such as 02:00.");
        }
        LocalDate date;
        if (maintenance.hasNonNull("MaintenanceScheduledDate") && in.has("MaintenanceScheduledDate")) {
            try {
                date = LocalDate.parse(maintenance.get("MaintenanceScheduledDate").asText());
            } catch (RuntimeException e) {
                throw MediaConnectStore.badRequest("MaintenanceScheduledDate is a date, YYYY-MM-DD.");
            }
            maintenance.put("MaintenanceDay", date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        } else {
            DayOfWeek day = DayOfWeek.valueOf(maintenance.path("MaintenanceDay").asText().toUpperCase(Locale.ROOT));
            date = LocalDate.now(ZoneOffset.UTC).plusDays(1).with(TemporalAdjusters.nextOrSame(day));
            maintenance.remove("MaintenanceScheduledDate");
        }
        maintenance.put("MaintenanceDeadline", date.plusWeeks(1) + "T" + hour + ":00Z");
        return maintenance;
    }

    private ObjectNode monitoring(JsonNode in, JsonNode current) {
        ObjectNode config = current instanceof ObjectNode existing ? existing.deepCopy() : mapper.createObjectNode();
        if (!config.has("ThumbnailState")) {
            config.put("ThumbnailState", "DISABLED");
        }
        if (!config.has("ContentQualityAnalysisState")) {
            config.put("ContentQualityAnalysisState", "DISABLED");
        }
        MediaConnectStore.copy(in, config, "ThumbnailState", "ContentQualityAnalysisState",
                "AudioMonitoringSettings", "VideoMonitoringSettings");
        return config;
    }

    // ================================================================ source metadata and thumbnails

    public ObjectNode describeFlowSourceMetadata(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        if (!"ACTIVE".equals(flow.path("Status").asText())) {
            throw MediaConnectStore.badRequest("The flow is " + flow.path("Status").asText()
                    + "; source metadata is read from an active flow.");
        }
        ObjectNode out = relay.get().metadata(flow.path("FlowArn").asText());
        out.put("FlowArn", flow.path("FlowArn").asText());
        return out;
    }

    public ObjectNode describeFlowSourceThumbnail(JsonNode in, String region) {
        ObjectNode flow = flow(region, in.path("FlowArn").asText());
        if (!"ACTIVE".equals(flow.path("Status").asText())) {
            throw MediaConnectStore.badRequest("The flow is " + flow.path("Status").asText()
                    + "; thumbnails come from an active flow.");
        }
        ObjectNode details = mapper.createObjectNode();
        details.put("FlowArn", flow.path("FlowArn").asText());
        ArrayNode messages = details.putArray("ThumbnailMessages");
        if (!"ENABLED".equals(flow.path("SourceMonitoringConfig").path("ThumbnailState").asText())) {
            messages.addObject().put("Code", "THUMBNAIL_DISABLED")
                    .put("Message", "Thumbnails are disabled in the flow's source monitoring configuration.");
        } else {
            MediaConnectRelay.Thumbnail thumbnail = relay.get().thumbnail(flow.path("FlowArn").asText());
            if (thumbnail.image() == null) {
                messages.addObject().put("Code", "THUMBNAIL_UNAVAILABLE").put("Message", thumbnail.message());
            } else {
                details.put("Thumbnail", thumbnail.image());
                details.put("Timestamp", thumbnail.at());
                if (thumbnail.timecode() != null) {
                    details.put("Timecode", thumbnail.timecode());
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("ThumbnailDetails", details);
        return out;
    }

    // ================================================================ tags and helpers

    /** The flow, or the flow holding the output, source or entitlement, an ARN names. */
    ObjectNode taggable(String region, String arn) {
        String resource = MediaConnectStore.resourceOf(arn);
        String type = resource.contains(":") ? resource.substring(0, resource.indexOf(':')) : resource;
        ObjectNode flow = store.get(region, MediaConnectStore.FLOW, MediaConnectStore.idOf(arn)).orElseThrow(() ->
                MediaConnectStore.notFound("The resource " + arn + " was not found."));
        if ("flow".equals(type)) {
            return flow;
        }
        String member = switch (type) {
            case "output" -> "Outputs";
            case "source" -> "Sources";
            case "entitlement" -> "Entitlements";
            default -> throw MediaConnectStore.notFound("The resource " + arn + " was not found.");
        };
        String key = switch (type) {
            case "output" -> "OutputArn";
            case "source" -> "SourceArn";
            default -> "EntitlementArn";
        };
        return find(flow.withArray(member), key, arn, type);
    }

    void saveFlowOf(String region, ObjectNode flow) {
        store.put(region, MediaConnectStore.FLOW, MediaConnectStore.meta(flow).path("id").asText(), flow);
    }

    private static ObjectNode find(JsonNode items, String key, String value, String what) {
        for (JsonNode item : items) {
            if (value.equals(item.path(key).asText())) {
                return (ObjectNode) item;
            }
        }
        throw MediaConnectStore.notFound("The " + what + " " + value + " was not found.");
    }

    private static void remove(ArrayNode items, JsonNode item) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index) == item) {
                items.remove(index);
                return;
            }
        }
    }

    /** Every ACTIVE flow, in every account, whose source is the entitlement an ARN names. */
    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> entitledFlows(String entitlementArn) {
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> found = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(MediaConnectStore.FLOW)) {
            if (!"ACTIVE".equals(entry.value().path("Status").asText())) {
                continue;
            }
            for (JsonNode source : entry.value().path("Sources")) {
                if (entitlementArn.equals(source.path("EntitlementArn").asText())) {
                    found.add(entry);
                }
            }
        }
        return found;
    }

}
