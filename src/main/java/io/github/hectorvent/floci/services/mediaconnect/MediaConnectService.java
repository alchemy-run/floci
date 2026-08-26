package io.github.hectorvent.floci.services.mediaconnect;

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
import io.github.hectorvent.floci.services.mediaconnect.model.Flow;
import io.github.hectorvent.floci.services.mediaconnect.model.FlowEntitlement;
import io.github.hectorvent.floci.services.mediaconnect.model.FlowOutput;
import io.github.hectorvent.floci.services.mediaconnect.model.FlowSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Elemental MediaConnect restJson1 — flow, source, and output lifecycle.
 *
 * <p>Create leaves the flow in {@code STANDBY}. Tag APIs share {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController} using ARN service
 * {@code mediaconnect}.
 */
@ApplicationScoped
public class MediaConnectService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MediaConnectService.class);

    static final String SERVICE = "mediaconnect";
    private static final String INGEST_IP = "192.0.2.11";
    private static final String EGRESS_IP = "192.0.2.10";
    private static final String STATUS_STANDBY = "STANDBY";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_STARTING = "STARTING";

    private final StorageBackend<String, Flow> flows;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaConnectService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("mediaconnect", "mediaconnect-flows.json",
                        new TypeReference<Map<String, Flow>>() {
                        }),
                regionResolver, objectMapper);
    }

    MediaConnectService(
            StorageBackend<String, Flow> flows, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.flows = flows;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Flow createFlow(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        JsonNode sourceNode = request.get("source");
        if (sourceNode == null || !sourceNode.isObject()) {
            throw badRequest("Source is required.");
        }

        String flowId = newFlowId();
        String account = regionResolver.getAccountId();
        String availabilityZone = optionalText(request, "availabilityZone");
        if (availabilityZone == null) {
            availabilityZone = region + "a";
        }

        Flow flow = new Flow();
        flow.setFlowId(flowId);
        flow.setName(name);
        flow.setStatus(STATUS_STANDBY);
        flow.setAvailabilityZone(availabilityZone);
        flow.setDescription(optionalText(request, "description"));
        flow.setEgressIp(EGRESS_IP);
        flow.setRegion(region);
        flow.setFlowArn(arn(region, account, "flow:" + flowId + ":" + name));
        flow.setSource(sourceFromRequest(region, account, flowId, sourceNode));
        flow.setTags(readTags(request.get("flowTags")));

        JsonNode outputsNode = request.get("outputs");
        if (outputsNode != null && outputsNode.isArray()) {
            List<FlowOutput> outputs = new ArrayList<>();
            for (JsonNode outputNode : outputsNode) {
                outputs.add(outputFromRequest(region, account, flowId, outputNode));
            }
            flow.setOutputs(outputs);
        }

        flows.put(flow.getFlowArn(), flow);
        LOG.infov("Created MediaConnect flow {0}", flow.getFlowArn());
        return flow;
    }

    public Flow describeFlow(String region, String flowArn) {
        return requireFlow(region, flowArn);
    }

    public List<Flow> listFlows(String region) {
        List<Flow> result = new ArrayList<>();
        for (Flow flow : flows.values()) {
            if (region.equals(flow.getRegion())) {
                result.add(flow);
            }
        }
        result.sort(Comparator.comparing(Flow::getFlowArn, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public synchronized Flow updateFlowSource(String region, String flowArn, String sourceArn, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, flowArn);
        FlowSource source = flow.getSource();
        if (source == null || !decode(sourceArn).equals(source.getSourceArn())) {
            throw notFound("Source " + decode(sourceArn) + " was not found.");
        }
        if (request.hasNonNull("description")) {
            source.setDescription(textOrNull(request, "description"));
        }
        if (request.hasNonNull("protocol")) {
            source.setProtocol(requireText(request, "protocol"));
        }
        if (request.hasNonNull("whitelistCidr")) {
            source.setWhitelistCidr(requireText(request, "whitelistCidr"));
        }
        if (request.has("ingestPort") && request.get("ingestPort").isNumber()) {
            source.setIngestPort(request.get("ingestPort").intValue());
        }
        if (request.has("maxBitrate") && request.get("maxBitrate").isNumber()) {
            source.setMaxBitrate(request.get("maxBitrate").intValue());
        }
        if (request.has("maxLatency") && request.get("maxLatency").isNumber()) {
            source.setMaxLatency(request.get("maxLatency").intValue());
        }
        if (request.has("minLatency") && request.get("minLatency").isNumber()) {
            source.setMinLatency(request.get("minLatency").intValue());
        }
        if (request.hasNonNull("streamId")) {
            source.setStreamId(textOrNull(request, "streamId"));
        }
        if (request.hasNonNull("senderIpAddress")) {
            source.setSenderIpAddress(textOrNull(request, "senderIpAddress"));
        }
        if (request.has("senderControlPort") && request.get("senderControlPort").isNumber()) {
            source.setSenderControlPort(request.get("senderControlPort").intValue());
        }
        flows.put(flow.getFlowArn(), flow);
        return flow;
    }

    public synchronized List<FlowOutput> addFlowOutputs(String region, String flowArn, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, flowArn);
        JsonNode outputsNode = request.get("outputs");
        if (outputsNode == null || !outputsNode.isArray() || outputsNode.isEmpty()) {
            throw badRequest("Outputs is required.");
        }
        String account = regionResolver.getAccountId();
        List<FlowOutput> added = new ArrayList<>();
        List<FlowOutput> current = new ArrayList<>(flow.getOutputs());
        for (JsonNode outputNode : outputsNode) {
            FlowOutput output = outputFromRequest(region, account, flow.getFlowId(), outputNode);
            current.add(output);
            added.add(output);
        }
        flow.setOutputs(current);
        flows.put(flow.getFlowArn(), flow);
        return added;
    }

    public synchronized FlowOutput updateFlowOutput(
            String region, String flowArn, String outputArn, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, flowArn);
        FlowOutput output = requireOutput(flow, outputArn);
        if (request.hasNonNull("description")) {
            output.setDescription(textOrNull(request, "description"));
        }
        if (request.hasNonNull("destination")) {
            output.setDestination(requireText(request, "destination"));
        }
        if (request.has("port") && request.get("port").isNumber()) {
            output.setPort(request.get("port").intValue());
        }
        if (request.hasNonNull("protocol")) {
            output.setProtocol(requireText(request, "protocol"));
        }
        if (request.has("cidrAllowList") && request.get("cidrAllowList").isArray()) {
            List<String> cidrs = new ArrayList<>();
            for (JsonNode cidr : request.get("cidrAllowList")) {
                if (cidr.isTextual()) {
                    cidrs.add(cidr.textValue());
                }
            }
            output.setCidrAllowList(cidrs);
        }
        if (request.has("maxLatency") && request.get("maxLatency").isNumber()) {
            output.setMaxLatency(request.get("maxLatency").intValue());
        }
        if (request.has("minLatency") && request.get("minLatency").isNumber()) {
            output.setMinLatency(request.get("minLatency").intValue());
        }
        if (request.has("smoothingLatency") && request.get("smoothingLatency").isNumber()) {
            output.setSmoothingLatency(request.get("smoothingLatency").intValue());
        }
        if (request.hasNonNull("streamId")) {
            output.setStreamId(textOrNull(request, "streamId"));
        }
        if (request.hasNonNull("remoteId")) {
            output.setRemoteId(textOrNull(request, "remoteId"));
        }
        flows.put(flow.getFlowArn(), flow);
        return output;
    }

    public synchronized FlowOutput removeFlowOutput(String region, String flowArn, String outputArn) {
        Flow flow = requireFlow(region, flowArn);
        FlowOutput output = requireOutput(flow, outputArn);
        if (output.getEntitlementArn() != null) {
            throw badRequest("Entitlement outputs cannot be removed.");
        }
        List<FlowOutput> remaining = new ArrayList<>();
        for (FlowOutput candidate : flow.getOutputs()) {
            if (!output.getOutputArn().equals(candidate.getOutputArn())) {
                remaining.add(candidate);
            }
        }
        flow.setOutputs(remaining);
        flows.put(flow.getFlowArn(), flow);
        return output;
    }

    public synchronized Flow startFlow(String region, String flowArn) {
        Flow flow = requireFlow(region, flowArn);
        if (STATUS_ACTIVE.equals(flow.getStatus()) || STATUS_STARTING.equals(flow.getStatus())) {
            throw badRequest("The flow is already started.");
        }
        flow.setStatus(STATUS_ACTIVE);
        flows.put(flow.getFlowArn(), flow);
        return flow;
    }

    public synchronized Flow stopFlow(String region, String flowArn) {
        Flow flow = requireFlow(region, flowArn);
        if (STATUS_STANDBY.equals(flow.getStatus())) {
            throw badRequest("The flow is already stopped.");
        }
        flow.setStatus(STATUS_STANDBY);
        flows.put(flow.getFlowArn(), flow);
        return flow;
    }

    public synchronized Flow deleteFlow(String region, String flowArn) {
        Flow flow = requireFlow(region, flowArn);
        if (STATUS_ACTIVE.equals(flow.getStatus()) || STATUS_STARTING.equals(flow.getStatus())) {
            throw badRequest("The requested flow is in a state that cannot be deleted.");
        }
        flows.delete(flow.getFlowArn());
        return flow;
    }

    public void describeFlowSourceMetadata(String region, String flowArn) {
        Flow flow = requireFlow(region, flowArn);
        if (!STATUS_ACTIVE.equals(flow.getStatus())) {
            throw badRequest("DescribeFlowSourceMetadata is not available because the flow is not active.");
        }
    }

    public void describeFlowSourceThumbnail(String region, String flowArn) {
        Flow flow = requireFlow(region, flowArn);
        if (!STATUS_ACTIVE.equals(flow.getStatus())) {
            throw badRequest("DescribeFlowSourceThumbnail is not available because the flow is not active.");
        }
    }

    public synchronized List<FlowEntitlement> grantFlowEntitlements(
            String region, String flowArn, JsonNode request) {
        requireObject(request, "Request body");
        Flow flow = requireFlow(region, flowArn);
        JsonNode entitlementsNode = request.get("entitlements");
        if (entitlementsNode == null || !entitlementsNode.isArray() || entitlementsNode.size() == 0) {
            throw badRequest("entitlements is required.");
        }
        String account = regionResolver.getAccountId();
        List<FlowEntitlement> granted = new ArrayList<>();
        List<FlowEntitlement> current = flow.getEntitlements() == null
                ? new ArrayList<>()
                : new ArrayList<>(flow.getEntitlements());
        for (JsonNode entitlementNode : entitlementsNode) {
            FlowEntitlement entitlement = entitlementFromRequest(region, account, flow.getFlowId(), entitlementNode);
            current.add(entitlement);
            granted.add(entitlement);
        }
        flow.setEntitlements(current);
        flows.put(flow.getFlowArn(), flow);
        return granted;
    }

    public synchronized FlowEntitlement revokeFlowEntitlement(
            String region, String flowArn, String entitlementArn) {
        Flow flow = requireFlow(region, flowArn);
        String decoded = decode(entitlementArn);
        FlowEntitlement match = null;
        List<FlowEntitlement> remaining = new ArrayList<>();
        List<FlowEntitlement> existing = flow.getEntitlements() == null ? List.of() : flow.getEntitlements();
        for (FlowEntitlement entitlement : existing) {
            if (decoded.equals(entitlement.getEntitlementArn())) {
                match = entitlement;
            } else {
                remaining.add(entitlement);
            }
        }
        if (match == null) {
            throw notFound("Entitlement " + decoded + " was not found.");
        }
        flow.setEntitlements(remaining);
        flows.put(flow.getFlowArn(), flow);
        return match;
    }

    public List<FlowEntitlement> listEntitlements(String region) {
        List<FlowEntitlement> listed = new ArrayList<>();
        for (Flow flow : listFlows(region)) {
            if (flow.getEntitlements() != null) {
                listed.addAll(flow.getEntitlements());
            }
        }
        listed.sort(Comparator.comparing(
                FlowEntitlement::getEntitlementArn, Comparator.nullsLast(String::compareTo)));
        return listed;
    }

    public ObjectNode toFlow(Flow flow) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "availabilityZone", flow.getAvailabilityZone());
        putText(node, "description", flow.getDescription());
        putText(node, "egressIp", flow.getEgressIp());
        putText(node, "flowArn", flow.getFlowArn());
        putText(node, "name", flow.getName());
        putText(node, "status", flow.getStatus());
        if (flow.getSource() != null) {
            node.set("source", toSource(flow.getSource()));
            ArrayNode sources = node.putArray("sources");
            sources.add(toSource(flow.getSource()));
        }
        ArrayNode outputs = node.putArray("outputs");
        for (FlowOutput output : flow.getOutputs()) {
            outputs.add(toOutput(output));
        }
        ArrayNode entitlements = node.putArray("entitlements");
        if (flow.getEntitlements() != null) {
            for (FlowEntitlement entitlement : flow.getEntitlements()) {
                entitlements.add(toEntitlement(entitlement));
            }
        }
        return node;
    }

    public ObjectNode toListedFlow(Flow flow) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "availabilityZone", flow.getAvailabilityZone());
        node.put("description", flow.getDescription() == null ? "" : flow.getDescription());
        putText(node, "flowArn", flow.getFlowArn());
        putText(node, "name", flow.getName());
        node.put("sourceType", "OWNED");
        putText(node, "status", flow.getStatus());
        return node;
    }

    public ObjectNode toSource(FlowSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "name", source.getName());
        putText(node, "sourceArn", source.getSourceArn());
        putText(node, "description", source.getDescription());
        putText(node, "whitelistCidr", source.getWhitelistCidr());
        if (source.getIngestPort() != null) {
            node.put("ingestPort", source.getIngestPort());
        }
        putText(node, "ingestIp", source.getIngestIp());
        putText(node, "senderIpAddress", source.getSenderIpAddress());
        if (source.getSenderControlPort() != null) {
            node.put("senderControlPort", source.getSenderControlPort());
        }
        ObjectNode transport = objectMapper.createObjectNode();
        putText(transport, "protocol", source.getProtocol());
        if (source.getMaxBitrate() != null) {
            transport.put("maxBitrate", source.getMaxBitrate());
        }
        if (source.getMaxLatency() != null) {
            transport.put("maxLatency", source.getMaxLatency());
        }
        if (source.getMinLatency() != null) {
            transport.put("minLatency", source.getMinLatency());
        }
        putText(transport, "streamId", source.getStreamId());
        if (transport.size() > 0) {
            node.set("transport", transport);
        }
        return node;
    }

    public ObjectNode toOutput(FlowOutput output) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "name", output.getName());
        putText(node, "outputArn", output.getOutputArn());
        putText(node, "description", output.getDescription());
        putText(node, "destination", output.getDestination());
        if (output.getPort() != null) {
            node.put("port", output.getPort());
        }
        putText(node, "listenerAddress", output.getListenerAddress());
        putText(node, "entitlementArn", output.getEntitlementArn());
        ObjectNode transport = objectMapper.createObjectNode();
        putText(transport, "protocol", output.getProtocol());
        if (output.getCidrAllowList() != null && !output.getCidrAllowList().isEmpty()) {
            ArrayNode cidrs = transport.putArray("cidrAllowList");
            output.getCidrAllowList().forEach(cidrs::add);
        }
        if (output.getMaxLatency() != null) {
            transport.put("maxLatency", output.getMaxLatency());
        }
        if (output.getMinLatency() != null) {
            transport.put("minLatency", output.getMinLatency());
        }
        if (output.getSmoothingLatency() != null) {
            transport.put("smoothingLatency", output.getSmoothingLatency());
        }
        putText(transport, "streamId", output.getStreamId());
        putText(transport, "remoteId", output.getRemoteId());
        if (transport.size() > 0) {
            node.set("transport", transport);
        }
        return node;
    }

    public ObjectNode toEntitlement(FlowEntitlement entitlement) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "name", entitlement.getName());
        putText(node, "entitlementArn", entitlement.getEntitlementArn());
        putText(node, "description", entitlement.getDescription());
        putText(node, "entitlementStatus", entitlement.getEntitlementStatus());
        ArrayNode subscribers = node.putArray("subscribers");
        for (String subscriber : entitlement.getSubscribers()) {
            subscribers.add(subscriber);
        }
        if (entitlement.getDataTransferSubscriberFeePercent() != null) {
            node.put("dataTransferSubscriberFeePercent", entitlement.getDataTransferSubscriberFeePercent());
        }
        return node;
    }

    public ObjectNode toListedEntitlement(FlowEntitlement entitlement) {
        ObjectNode node = objectMapper.createObjectNode();
        putText(node, "entitlementArn", entitlement.getEntitlementArn());
        putText(node, "entitlementName", entitlement.getName());
        if (entitlement.getDataTransferSubscriberFeePercent() != null) {
            node.put("dataTransferSubscriberFeePercent", entitlement.getDataTransferSubscriberFeePercent());
        }
        return node;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireFlow(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Flow flow = requireFlow(region, arn);
        Map<String, String> current = new LinkedHashMap<>(flow.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        flow.setTags(current);
        flows.put(flow.getFlowArn(), flow);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Flow flow = requireFlow(region, arn);
        Map<String, String> current = new LinkedHashMap<>(flow.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        flow.setTags(current);
        flows.put(flow.getFlowArn(), flow);
    }

    private Flow requireFlow(String region, String flowArn) {
        String decoded = decode(flowArn);
        Flow flow = flows.get(decoded).orElse(null);
        if (flow == null) {
            for (Flow candidate : flows.values()) {
                if (decoded.equals(candidate.getFlowArn())) {
                    flow = candidate;
                    break;
                }
            }
        }
        if (flow == null || (region != null && !region.equals(flow.getRegion()))) {
            throw notFound("Flow not found: " + decoded);
        }
        return flow;
    }

    private FlowOutput requireOutput(Flow flow, String outputArn) {
        String decoded = decode(outputArn);
        for (FlowOutput output : flow.getOutputs()) {
            if (decoded.equals(output.getOutputArn())) {
                return output;
            }
        }
        throw notFound("Output " + decoded + " was not found.");
    }

    private FlowSource sourceFromRequest(String region, String account, String flowId, JsonNode request) {
        requireObject(request, "Source");
        FlowSource source = new FlowSource();
        String name = optionalText(request, "name");
        if (name == null) {
            name = "AwardsShow";
        }
        source.setName(name);
        source.setDescription(optionalText(request, "description"));
        source.setProtocol(optionalText(request, "protocol"));
        source.setWhitelistCidr(optionalText(request, "whitelistCidr"));
        source.setIngestPort(optionalInt(request, "ingestPort"));
        source.setIngestIp(INGEST_IP);
        source.setMaxBitrate(optionalInt(request, "maxBitrate"));
        source.setMaxLatency(optionalInt(request, "maxLatency"));
        source.setMinLatency(optionalInt(request, "minLatency"));
        source.setStreamId(optionalText(request, "streamId"));
        source.setSenderIpAddress(optionalText(request, "senderIpAddress"));
        source.setSenderControlPort(optionalInt(request, "senderControlPort"));
        source.setSourceArn(arn(region, account, "source:" + flowId + ":" + name));
        return source;
    }

    private FlowOutput outputFromRequest(String region, String account, String flowId, JsonNode request) {
        requireObject(request, "Output");
        String name = requireText(request, "name");
        FlowOutput output = new FlowOutput();
        output.setName(name);
        output.setDescription(optionalText(request, "description"));
        output.setDestination(optionalText(request, "destination"));
        output.setPort(optionalInt(request, "port"));
        output.setProtocol(optionalText(request, "protocol"));
        output.setListenerAddress(EGRESS_IP);
        output.setMaxLatency(optionalInt(request, "maxLatency"));
        output.setMinLatency(optionalInt(request, "minLatency"));
        output.setSmoothingLatency(optionalInt(request, "smoothingLatency"));
        output.setStreamId(optionalText(request, "streamId"));
        output.setRemoteId(optionalText(request, "remoteId"));
        JsonNode cidrs = request.get("cidrAllowList");
        if (cidrs != null && cidrs.isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode cidr : cidrs) {
                if (cidr.isTextual()) {
                    list.add(cidr.textValue());
                }
            }
            output.setCidrAllowList(list);
        }
        output.setOutputArn(arn(region, account, "output:" + flowId + ":" + name));
        return output;
    }

    private FlowEntitlement entitlementFromRequest(
            String region, String account, String flowId, JsonNode request) {
        requireObject(request, "Entitlement");
        String name = requireText(request, "name");
        FlowEntitlement entitlement = new FlowEntitlement();
        entitlement.setName(name);
        entitlement.setDescription(optionalText(request, "description"));
        String status = optionalText(request, "entitlementStatus");
        entitlement.setEntitlementStatus(status == null ? "ENABLED" : status);
        entitlement.setDataTransferSubscriberFeePercent(optionalInt(request, "dataTransferSubscriberFeePercent"));
        JsonNode subscribers = request.get("subscribers");
        if (subscribers == null || !subscribers.isArray() || subscribers.size() == 0) {
            throw badRequest("entitlements.subscribers is required.");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode subscriber : subscribers) {
            if (subscriber.isTextual()) {
                list.add(subscriber.textValue());
            }
        }
        if (list.isEmpty()) {
            throw badRequest("entitlements.subscribers is required.");
        }
        entitlement.setSubscribers(list);
        entitlement.setEntitlementArn(arn(region, account, "entitlement:" + flowId + ":" + name));
        return entitlement;
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
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    private static String newFlowId() {
        return "1-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String decode(String value) {
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

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }
}
