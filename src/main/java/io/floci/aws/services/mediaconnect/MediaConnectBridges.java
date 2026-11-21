package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MediaConnect Gateway: gateways (the on-premises networks a gateway instance joins), their instances, and bridges.
 * A gateway is {@code CREATING}, then {@code ACTIVE}. Instances register through the MediaConnect Gateway agent on
 * an on-premises machine, so none exist in Floci: listing them is empty, and a bridge stays {@code DEPLOYING} with a
 * message that no instance is available to run it. An egress bridge's flow source adds an output to that flow, as on
 * AWS, removed with the source.
 */
@ApplicationScoped
public class MediaConnectBridges {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    private final MediaConnectStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaConnectFlows> flows;

    @Inject
    public MediaConnectBridges(MediaConnectStore store, Provider<MediaConnectFlows> flows) {
        this.store = store;
        this.mapper = store.mapper();
        this.flows = flows;
    }

    private static String name(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw MediaConnectStore.badRequest("The " + what + " name must be 1-128 letters, digits, hyphens and "
                    + "underscores.");
        }
        return name;
    }

    /** Applies a state change whose time has come. */
    private void settle(String region, String kind, ObjectNode record, String stateMember) {
        ObjectNode meta = MediaConnectStore.meta(record);
        long at = meta.path("transitionAt").asLong(0);
        if (at == 0 || at > System.currentTimeMillis()) {
            return;
        }
        meta.remove("transitionAt");
        String next = meta.path("next").asText();
        meta.remove("next");
        if ("GONE".equals(next)) {
            store.delete(region, kind, meta.path("id").asText());
            return;
        }
        record.put(stateMember, next);
        store.put(region, kind, meta.path("id").asText(), record);
    }

    private void later(ObjectNode record, String next) {
        MediaConnectStore.meta(record).put("transitionAt", System.currentTimeMillis()
                + Math.max(1, store.config().transitionMillis())).put("next", next);
    }

    // ================================================================ gateways

    ObjectNode gateway(String region, String arn) {
        String id = MediaConnectStore.idOf(arn);
        ObjectNode gateway = store.get(region, MediaConnectStore.GATEWAY, id).orElse(null);
        if (gateway != null) {
            settle(region, MediaConnectStore.GATEWAY, gateway, "GatewayState");
            gateway = store.get(region, MediaConnectStore.GATEWAY, id).orElse(null);
        }
        if (gateway == null || !arn.equals(gateway.path("GatewayArn").asText())) {
            throw MediaConnectStore.notFound("The gateway " + arn + " was not found.");
        }
        return gateway;
    }

    public ObjectNode createGateway(JsonNode in, String region) {
        String name = name(in.path("Name").asText(null), "gateway");
        for (ObjectNode existing : store.list(region, MediaConnectStore.GATEWAY)) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("A gateway named " + name + " already exists.");
            }
        }
        if (!in.path("Networks").isArray() || in.path("Networks").isEmpty()) {
            throw MediaConnectStore.badRequest("A gateway needs at least one network.");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode network : in.path("Networks")) {
            name(network.path("Name").asText(null), "network");
            if (!MediaConnectRelay.validCidr(network.path("CidrBlock").asText(""))) {
                throw MediaConnectStore.badRequest("The network " + network.path("Name").asText()
                        + " needs a valid CidrBlock.");
            }
            if (names.contains(network.path("Name").asText())) {
                throw MediaConnectStore.badRequest("Network names are unique within a gateway.");
            }
            names.add(network.path("Name").asText());
        }
        for (JsonNode cidr : in.path("EgressCidrBlocks")) {
            if (!MediaConnectRelay.validCidr(cidr.asText())) {
                throw MediaConnectStore.badRequest("The egress CIDR block " + cidr.asText() + " is not valid.");
            }
        }
        String id = MediaConnectStore.flowId();
        ObjectNode gateway = store.record();
        gateway.put("GatewayArn", store.arn(region, "gateway:" + id + ":" + name));
        gateway.put("Name", name);
        gateway.set("Networks", in.get("Networks").deepCopy());
        gateway.set("EgressCidrBlocks", in.path("EgressCidrBlocks").isArray() ? in.get("EgressCidrBlocks")
                .deepCopy() : mapper.createArrayNode());
        gateway.put("GatewayState", "CREATING");
        gateway.putArray("GatewayMessages");
        later(gateway, "ACTIVE");
        store.put(region, MediaConnectStore.GATEWAY, id, gateway);
        ObjectNode out = mapper.createObjectNode();
        out.set("Gateway", MediaConnectStore.view(gateway));
        return out;
    }

    public ObjectNode describeGateway(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Gateway", MediaConnectStore.view(gateway(region, in.path("GatewayArn").asText())));
        return out;
    }

    public ObjectNode deleteGateway(JsonNode in, String region) {
        ObjectNode gateway = gateway(region, in.path("GatewayArn").asText());
        String arn = gateway.path("GatewayArn").asText();
        for (ObjectNode bridge : store.list(region, MediaConnectStore.BRIDGE)) {
            if (arn.equals(bridge.path("PlacementArn").asText())) {
                throw MediaConnectStore.conflict("The gateway has the bridge " + bridge.path("Name").asText()
                        + "; delete it first.");
            }
        }
        gateway.put("GatewayState", "DELETING");
        later(gateway, "GONE");
        store.put(region, MediaConnectStore.GATEWAY, MediaConnectStore.meta(gateway).path("id").asText(), gateway);
        ObjectNode out = mapper.createObjectNode();
        out.put("GatewayArn", arn);
        return out;
    }

    public ObjectNode listGateways(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, MediaConnectStore.GATEWAY)) {
            settle(region, MediaConnectStore.GATEWAY, stored, "GatewayState");
            if (store.get(region, MediaConnectStore.GATEWAY, MediaConnectStore.meta(stored).path("id").asText())
                    .isEmpty()) {
                continue;
            }
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(stored, listed, "GatewayArn", "GatewayState", "Name");
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "Gateways", 10);
        return out;
    }

    // ================================================================ gateway instances

    private static AwsException instanceMissing(String arn) {
        return MediaConnectStore.notFound("The gateway instance " + arn + " was not found. Instances register "
                + "through the MediaConnect Gateway agent, which Floci doesn't run.");
    }

    public ObjectNode describeGatewayInstance(JsonNode in, String region) {
        throw instanceMissing(in.path("GatewayInstanceArn").asText());
    }

    public ObjectNode deregisterGatewayInstance(JsonNode in, String region) {
        throw instanceMissing(in.path("GatewayInstanceArn").asText());
    }

    public ObjectNode updateGatewayInstance(JsonNode in, String region) {
        throw instanceMissing(in.path("GatewayInstanceArn").asText());
    }

    public ObjectNode listGatewayInstances(JsonNode in, String region) {
        if (in.hasNonNull("FilterArn")) {
            gateway(region, in.get("FilterArn").asText());
        }
        ObjectNode out = mapper.createObjectNode();
        out.putArray("Instances");
        return out;
    }

    // ================================================================ bridges

    ObjectNode bridge(String region, String arn) {
        String id = MediaConnectStore.idOf(arn);
        ObjectNode bridge = store.get(region, MediaConnectStore.BRIDGE, id).orElse(null);
        if (bridge != null) {
            settle(region, MediaConnectStore.BRIDGE, bridge, "BridgeState");
            bridge = store.get(region, MediaConnectStore.BRIDGE, id).orElse(null);
        }
        if (bridge == null || !arn.equals(bridge.path("BridgeArn").asText())) {
            throw MediaConnectStore.notFound("The bridge " + arn + " was not found.");
        }
        return bridge;
    }

    private ObjectNode view(ObjectNode bridge) {
        ObjectNode view = MediaConnectStore.view(bridge);
        ArrayNode messages = view.putArray("BridgeMessages");
        String state = view.path("BridgeState").asText();
        if ("DEPLOYING".equals(state) || "ACTIVE".equals(state)) {
            messages.addObject().put("Code", "NO_AVAILABLE_INSTANCE").put("Message",
                    "No gateway instance is available to run the bridge; instances register through the "
                            + "MediaConnect Gateway agent, which Floci doesn't run.");
        }
        return view;
    }

    public ObjectNode createBridge(JsonNode in, String region) {
        String name = name(in.path("Name").asText(null), "bridge");
        for (ObjectNode existing : store.list(region, MediaConnectStore.BRIDGE)) {
            if (name.equals(existing.path("Name").asText())) {
                throw MediaConnectStore.conflict("A bridge named " + name + " already exists.");
            }
        }
        ObjectNode gateway = gateway(region, in.path("PlacementArn").asText());
        boolean ingress = in.has("IngressGatewayBridge");
        if (ingress == in.has("EgressGatewayBridge")) {
            throw MediaConnectStore.badRequest("A bridge is an IngressGatewayBridge or an EgressGatewayBridge.");
        }
        String id = MediaConnectStore.flowId();
        ObjectNode bridge = store.record();
        bridge.put("BridgeArn", store.arn(region, "bridge:" + id + ":" + name));
        bridge.put("Name", name);
        bridge.put("PlacementArn", gateway.path("GatewayArn").asText());
        bridge.put("BridgeState", "CREATING");
        if (ingress) {
            bridge.set("IngressGatewayBridge", in.get("IngressGatewayBridge").deepCopy());
        } else {
            bridge.set("EgressGatewayBridge", in.get("EgressGatewayBridge").deepCopy());
        }
        if (in.has("SourceFailoverConfig")) {
            bridge.set("SourceFailoverConfig", in.get("SourceFailoverConfig").deepCopy());
        }
        bridge.putArray("Sources");
        bridge.putArray("Outputs");
        MediaConnectStore.meta(bridge).put("id", id);
        if (!in.path("Sources").isArray() || in.path("Sources").isEmpty()) {
            throw MediaConnectStore.badRequest("A bridge needs at least one source.");
        }
        for (JsonNode source : in.path("Sources")) {
            addSource(region, bridge, gateway, source);
        }
        for (JsonNode output : in.path("Outputs")) {
            addOutput(bridge, gateway, output);
        }
        later(bridge, "DEPLOYING");
        store.put(region, MediaConnectStore.BRIDGE, id, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.set("Bridge", view(bridge));
        return out;
    }

    private void addSource(String region, ObjectNode bridge, ObjectNode gateway, JsonNode in) {
        ArrayNode sources = (ArrayNode) bridge.withArray("Sources");
        if (sources.size() >= 2) {
            throw MediaConnectStore.badRequest("A bridge can have at most two sources.");
        }
        ObjectNode source = mapper.createObjectNode();
        if (in.has("FlowSource")) {
            if (!bridge.has("EgressGatewayBridge")) {
                throw MediaConnectStore.badRequest("Only an egress bridge takes flow sources.");
            }
            JsonNode request = in.get("FlowSource");
            String sourceName = name(request.path("Name").asText(null), "bridge source");
            unique(sources, sourceName);
            String flowArn = request.path("FlowArn").asText();
            ObjectNode flow = flows.get().flow(region, flowArn);
            ObjectNode flowSource = source.putObject("FlowSource");
            flowSource.put("FlowArn", flowArn);
            flowSource.put("Name", sourceName);
            MediaConnectStore.copy(request, flowSource, "FlowVpcInterfaceAttachment");
            String outputName = "bridge-" + bridge.path("Name").asText() + "-" + sourceName;
            ObjectNode output = mapper.createObjectNode();
            output.put("OutputArn", store.arn(region, "output:" + MediaConnectStore.idOf(flowArn) + ":"
                    + outputName));
            output.put("Name", outputName);
            output.put("BridgeArn", bridge.path("BridgeArn").asText());
            output.putArray("BridgePorts").add(2000 + flow.withArray("Outputs").size());
            output.put("OutputStatus", "ENABLED");
            output.putObject("Transport").put("Protocol", "rtp");
            ((ArrayNode) flow.withArray("Outputs")).add(output);
            flows.get().saveFlowOf(region, flow);
            flowSource.put("OutputArn", output.path("OutputArn").asText());
        } else if (in.has("NetworkSource")) {
            if (!bridge.has("IngressGatewayBridge")) {
                throw MediaConnectStore.badRequest("Only an ingress bridge takes network sources.");
            }
            JsonNode request = in.get("NetworkSource");
            String sourceName = name(request.path("Name").asText(null), "bridge source");
            unique(sources, sourceName);
            network(gateway, request.path("NetworkName").asText());
            ObjectNode networkSource = request.deepCopy();
            source.set("NetworkSource", networkSource);
        } else {
            throw MediaConnectStore.badRequest("A bridge source is a FlowSource or a NetworkSource.");
        }
        sources.add(source);
    }

    private void addOutput(ObjectNode bridge, ObjectNode gateway, JsonNode in) {
        ArrayNode outputs = (ArrayNode) bridge.withArray("Outputs");
        JsonNode request = in.path("NetworkOutput");
        if (!request.isObject()) {
            throw MediaConnectStore.badRequest("A bridge output is a NetworkOutput.");
        }
        if (!bridge.has("EgressGatewayBridge")) {
            throw MediaConnectStore.badRequest("Only an egress bridge takes network outputs.");
        }
        String outputName = name(request.path("Name").asText(null), "bridge output");
        for (JsonNode existing : outputs) {
            if (outputName.equals(existing.path("NetworkOutput").path("Name").asText())) {
                throw MediaConnectStore.conflict("The bridge already has an output named " + outputName + ".");
            }
        }
        network(gateway, request.path("NetworkName").asText());
        ObjectNode output = mapper.createObjectNode();
        output.set("NetworkOutput", request.deepCopy());
        outputs.add(output);
    }

    private static void unique(ArrayNode sources, String name) {
        for (JsonNode existing : sources) {
            String other = existing.path("FlowSource").path("Name").asText(existing.path("NetworkSource")
                    .path("Name").asText());
            if (name.equals(other)) {
                throw MediaConnectStore.conflict("The bridge already has a source named " + name + ".");
            }
        }
    }

    private static void network(ObjectNode gateway, String name) {
        for (JsonNode network : gateway.path("Networks")) {
            if (name.equals(network.path("Name").asText())) {
                return;
            }
        }
        throw MediaConnectStore.badRequest("The gateway has no network named " + name + ".");
    }

    public ObjectNode describeBridge(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Bridge", view(bridge(region, in.path("BridgeArn").asText())));
        return out;
    }

    public ObjectNode updateBridge(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        if (in.has("IngressGatewayBridge")) {
            if (!bridge.has("IngressGatewayBridge")) {
                throw MediaConnectStore.badRequest("The bridge is an egress bridge.");
            }
            ((ObjectNode) bridge.get("IngressGatewayBridge")).setAll((ObjectNode) in.get("IngressGatewayBridge"));
        }
        if (in.has("EgressGatewayBridge")) {
            if (!bridge.has("EgressGatewayBridge")) {
                throw MediaConnectStore.badRequest("The bridge is an ingress bridge.");
            }
            ((ObjectNode) bridge.get("EgressGatewayBridge")).setAll((ObjectNode) in.get("EgressGatewayBridge"));
        }
        if (in.has("SourceFailoverConfig")) {
            ObjectNode config = bridge.has("SourceFailoverConfig") ? (ObjectNode) bridge.get("SourceFailoverConfig")
                    : bridge.putObject("SourceFailoverConfig");
            config.setAll((ObjectNode) in.get("SourceFailoverConfig"));
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.set("Bridge", view(bridge));
        return out;
    }

    public ObjectNode updateBridgeState(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        String desired = in.path("DesiredState").asText();
        switch (desired) {
            case "ACTIVE" -> {
                bridge.put("BridgeState", "STARTING");
                later(bridge, "DEPLOYING");
            }
            case "STANDBY" -> {
                bridge.put("BridgeState", "STOPPING");
                later(bridge, "STANDBY");
            }
            case "DELETED" -> {
                removeFlowOutputs(region, bridge);
                bridge.put("BridgeState", "DELETING");
                later(bridge, "GONE");
            }
            default -> throw MediaConnectStore.badRequest("DesiredState is ACTIVE, STANDBY or DELETED.");
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.put("DesiredState", desired);
        return out;
    }

    public ObjectNode deleteBridge(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        removeFlowOutputs(region, bridge);
        bridge.put("BridgeState", "DELETING");
        later(bridge, "GONE");
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        return out;
    }

    private void removeFlowOutputs(String region, ObjectNode bridge) {
        for (JsonNode source : bridge.withArray("Sources")) {
            removeFlowOutput(region, source);
        }
    }

    private void removeFlowOutput(String region, JsonNode source) {
        JsonNode flowSource = source.path("FlowSource");
        if (!flowSource.isObject()) {
            return;
        }
        try {
            ObjectNode flow = flows.get().flow(region, flowSource.path("FlowArn").asText());
            ArrayNode outputs = (ArrayNode) flow.withArray("Outputs");
            for (int index = 0; index < outputs.size(); index++) {
                if (flowSource.path("OutputArn").asText().equals(outputs.get(index).path("OutputArn").asText())) {
                    outputs.remove(index);
                    break;
                }
            }
            flows.get().saveFlowOf(region, flow);
        } catch (RuntimeException e) {
            // The flow is gone: nothing of the bridge's is left on it.
        }
    }

    public ObjectNode listBridges(JsonNode in, String region) {
        String filter = in.path("FilterArn").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, MediaConnectStore.BRIDGE)) {
            settle(region, MediaConnectStore.BRIDGE, stored, "BridgeState");
            if (store.get(region, MediaConnectStore.BRIDGE, MediaConnectStore.meta(stored).path("id").asText())
                    .isEmpty() || (filter != null && !filter.equals(stored.path("PlacementArn").asText()))) {
                continue;
            }
            ObjectNode listed = mapper.createObjectNode();
            MediaConnectStore.copy(stored, listed, "BridgeArn", "BridgeState", "Name", "PlacementArn");
            listed.put("BridgeType", stored.has("IngressGatewayBridge") ? "ingress-gateway" : "egress-gateway");
            items.add(listed);
        }
        ObjectNode out = mapper.createObjectNode();
        MediaConnectStore.page(items, in, out, "Bridges", 10);
        return out;
    }

    public ObjectNode addBridgeSources(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        ObjectNode gateway = gateway(region, bridge.path("PlacementArn").asText());
        int before = bridge.withArray("Sources").size();
        for (JsonNode source : in.path("Sources")) {
            addSource(region, bridge, gateway, source);
        }
        save(region, bridge);
        ArrayNode added = mapper.createArrayNode();
        ArrayNode sources = (ArrayNode) bridge.withArray("Sources");
        for (int index = before; index < sources.size(); index++) {
            added.add(sources.get(index).deepCopy());
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.set("Sources", added);
        return out;
    }

    public ObjectNode updateBridgeSource(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        ObjectNode source = source(bridge, in.path("SourceName").asText());
        if (in.has("FlowSource") && source.has("FlowSource")) {
            ((ObjectNode) source.get("FlowSource")).setAll((ObjectNode) in.get("FlowSource"));
        } else if (in.has("NetworkSource") && source.has("NetworkSource")) {
            if (in.get("NetworkSource").has("NetworkName")) {
                network(gateway(region, bridge.path("PlacementArn").asText()), in.get("NetworkSource")
                        .path("NetworkName").asText());
            }
            ((ObjectNode) source.get("NetworkSource")).setAll((ObjectNode) in.get("NetworkSource"));
        } else if (in.has("FlowSource") || in.has("NetworkSource")) {
            throw MediaConnectStore.badRequest("The source " + in.path("SourceName").asText()
                    + " is of the other kind.");
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.set("Source", source.deepCopy());
        return out;
    }

    public ObjectNode removeBridgeSource(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        String name = in.path("SourceName").asText();
        ArrayNode sources = (ArrayNode) bridge.withArray("Sources");
        ObjectNode source = source(bridge, name);
        if (sources.size() == 1) {
            throw MediaConnectStore.badRequest("A bridge keeps at least one source.");
        }
        removeFlowOutput(region, source);
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index) == source) {
                sources.remove(index);
                break;
            }
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.put("SourceName", name);
        return out;
    }

    private static ObjectNode source(ObjectNode bridge, String name) {
        for (JsonNode source : bridge.withArray("Sources")) {
            String other = source.path("FlowSource").path("Name").asText(source.path("NetworkSource").path("Name")
                    .asText());
            if (name.equals(other)) {
                return (ObjectNode) source;
            }
        }
        throw MediaConnectStore.notFound("The bridge source " + name + " was not found.");
    }

    public ObjectNode addBridgeOutputs(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        ObjectNode gateway = gateway(region, bridge.path("PlacementArn").asText());
        int before = bridge.withArray("Outputs").size();
        for (JsonNode output : in.path("Outputs")) {
            addOutput(bridge, gateway, output);
        }
        save(region, bridge);
        ArrayNode added = mapper.createArrayNode();
        ArrayNode outputs = (ArrayNode) bridge.withArray("Outputs");
        for (int index = before; index < outputs.size(); index++) {
            added.add(outputs.get(index).deepCopy());
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.set("Outputs", added);
        return out;
    }

    public ObjectNode updateBridgeOutput(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        ObjectNode output = output(bridge, in.path("OutputName").asText());
        if (in.has("NetworkOutput")) {
            if (in.get("NetworkOutput").has("NetworkName")) {
                network(gateway(region, bridge.path("PlacementArn").asText()), in.get("NetworkOutput")
                        .path("NetworkName").asText());
            }
            ((ObjectNode) output.get("NetworkOutput")).setAll((ObjectNode) in.get("NetworkOutput"));
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.set("Output", output.deepCopy());
        return out;
    }

    public ObjectNode removeBridgeOutput(JsonNode in, String region) {
        ObjectNode bridge = bridge(region, in.path("BridgeArn").asText());
        String name = in.path("OutputName").asText();
        ObjectNode output = output(bridge, name);
        ArrayNode outputs = (ArrayNode) bridge.withArray("Outputs");
        for (int index = 0; index < outputs.size(); index++) {
            if (outputs.get(index) == output) {
                outputs.remove(index);
                break;
            }
        }
        save(region, bridge);
        ObjectNode out = mapper.createObjectNode();
        out.put("BridgeArn", bridge.path("BridgeArn").asText());
        out.put("OutputName", name);
        return out;
    }

    private static ObjectNode output(ObjectNode bridge, String name) {
        for (JsonNode output : bridge.withArray("Outputs")) {
            if (name.equals(output.path("NetworkOutput").path("Name").asText())) {
                return (ObjectNode) output;
            }
        }
        throw MediaConnectStore.notFound("The bridge output " + name + " was not found.");
    }

    private void save(String region, ObjectNode bridge) {
        store.put(region, MediaConnectStore.BRIDGE, MediaConnectStore.meta(bridge).path("id").asText(), bridge);
    }
}
