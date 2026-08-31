package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.globalaccelerator.model.Accelerator;
import io.github.hectorvent.floci.services.globalaccelerator.model.EndpointGroup;
import io.github.hectorvent.floci.services.globalaccelerator.model.Listener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Global Accelerator JSON 1.1 ({@code GlobalAccelerator_V20180706.*}).
 *
 * <p>Control-plane ARNs are global (empty region). Provisioning settles to
 * {@code DEPLOYED} immediately so local reconcilers do not wait on the live
 * anycast propagation window.
 */
@ApplicationScoped
public class GlobalAcceleratorService implements Resettable {

    static final String SERVICE = "globalaccelerator";
    static final String TARGET_PREFIX = "GlobalAccelerator_V20180706.";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?$");
    private static final Set<String> IP_ADDRESS_TYPES = Set.of("IPV4", "DUAL_STACK");
    private static final Set<String> PROTOCOLS = Set.of("TCP", "UDP");
    private static final Set<String> CLIENT_AFFINITIES = Set.of("NONE", "SOURCE_IP");
    private static final Set<String> HEALTH_CHECK_PROTOCOLS = Set.of("TCP", "HTTP", "HTTPS");

    private final StorageBackend<String, Accelerator> accelerators;
    private final StorageBackend<String, Listener> listeners;
    private final StorageBackend<String, EndpointGroup> endpointGroups;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public GlobalAcceleratorService(StorageFactory storageFactory, RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this(storageFactory.create(SERVICE, "globalaccelerator-accelerators.json",
                        new TypeReference<Map<String, Accelerator>>() {
                        }),
                storageFactory.create(SERVICE, "globalaccelerator-listeners.json",
                        new TypeReference<Map<String, Listener>>() {
                        }),
                storageFactory.create(SERVICE, "globalaccelerator-endpoint-groups.json",
                        new TypeReference<Map<String, EndpointGroup>>() {
                        }),
                regionResolver, objectMapper);
    }

    GlobalAcceleratorService(StorageBackend<String, Accelerator> accelerators,
                             StorageBackend<String, Listener> listeners,
                             StorageBackend<String, EndpointGroup> endpointGroups,
                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.accelerators = accelerators;
        this.listeners = listeners;
        this.endpointGroups = endpointGroups;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        accelerators.clear();
        listeners.clear();
        endpointGroups.clear();
    }

    public synchronized ObjectNode createAccelerator(JsonNode request) {
        requireObject(request);
        String name = requireText(request, "Name");
        validateName(name);
        String token = requireText(request, "IdempotencyToken");
        Accelerator existingToken = findByToken(token);
        if (existingToken != null) {
            return wrapAccelerator(existingToken);
        }
        if (findByName(name) != null) {
            throw invalid("An accelerator with the specified name already exists.");
        }
        String ipAddressType = optionalText(request, "IpAddressType");
        if (ipAddressType == null) {
            ipAddressType = "IPV4";
        }
        requireIpAddressType(ipAddressType);
        boolean enabled = request.has("Enabled") ? request.get("Enabled").asBoolean(true) : true;

        String id = newId();
        String arn = acceleratorArn(id);
        long now = nowSeconds();
        Accelerator accelerator = new Accelerator();
        accelerator.setAcceleratorArn(arn);
        accelerator.setName(name);
        accelerator.setIpAddressType(ipAddressType);
        accelerator.setEnabled(enabled);
        accelerator.setIpv4Addresses(assignIpv4(id, request.get("IpAddresses")));
        if ("DUAL_STACK".equals(ipAddressType)) {
            accelerator.setIpv6Addresses(assignIpv6(id));
            accelerator.setDualStackDnsName("dualstack." + id.replace("-", "") + ".awsglobalaccelerator.com");
        }
        accelerator.setDnsName(id.replace("-", "") + ".awsglobalaccelerator.com");
        accelerator.setStatus("DEPLOYED");
        accelerator.setCreatedTime(now);
        accelerator.setLastModifiedTime(now);
        accelerator.setIdempotencyToken(token);
        accelerator.getTags().putAll(readTags(request));
        accelerators.put(arn, accelerator);
        return wrapAccelerator(accelerator);
    }

    public ObjectNode describeAccelerator(JsonNode request) {
        requireObject(request);
        return wrapAccelerator(requireAccelerator(requireText(request, "AcceleratorArn")));
    }

    public ObjectNode listAccelerators(JsonNode request) {
        List<Accelerator> items = new ArrayList<>(accelerators.values());
        items.sort(Comparator.comparing(Accelerator::getAcceleratorArn, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Accelerators");
        for (Accelerator accelerator : page(items, request, response)) {
            list.add(acceleratorNode(accelerator));
        }
        return response;
    }

    public synchronized ObjectNode updateAccelerator(JsonNode request) {
        requireObject(request);
        Accelerator accelerator = requireAccelerator(requireText(request, "AcceleratorArn"));
        boolean dirty = false;
        String name = optionalText(request, "Name");
        if (name != null && !name.equals(accelerator.getName())) {
            validateName(name);
            Accelerator conflict = findByName(name);
            if (conflict != null && !accelerator.getAcceleratorArn().equals(conflict.getAcceleratorArn())) {
                throw invalid("An accelerator with the specified name already exists.");
            }
            accelerator.setName(name);
            dirty = true;
        }
        String ipAddressType = optionalText(request, "IpAddressType");
        if (ipAddressType != null && !ipAddressType.equals(accelerator.getIpAddressType())) {
            requireIpAddressType(ipAddressType);
            accelerator.setIpAddressType(ipAddressType);
            if ("DUAL_STACK".equals(ipAddressType) && accelerator.getIpv6Addresses().isEmpty()) {
                String id = acceleratorId(accelerator.getAcceleratorArn());
                accelerator.setIpv6Addresses(assignIpv6(id));
                accelerator.setDualStackDnsName("dualstack." + id.replace("-", "") + ".awsglobalaccelerator.com");
            }
            dirty = true;
        }
        if (request.has("Enabled")) {
            boolean enabled = request.get("Enabled").asBoolean();
            if (enabled != accelerator.isEnabled()) {
                accelerator.setEnabled(enabled);
                dirty = true;
            }
        }
        if (dirty) {
            accelerator.setLastModifiedTime(nowSeconds());
            accelerators.put(accelerator.getAcceleratorArn(), accelerator);
        }
        return wrapAccelerator(accelerator);
    }

    public synchronized ObjectNode deleteAccelerator(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "AcceleratorArn");
        Accelerator accelerator = requireAccelerator(arn);
        if (accelerator.isEnabled()) {
            throw new AwsException(
                    "AcceleratorNotDisabledException",
                    "Accelerator must be disabled before it can be deleted.",
                    400);
        }
        if (!listenersFor(arn).isEmpty()) {
            throw new AwsException(
                    "AssociatedListenerFoundException",
                    "Listeners are still associated with the accelerator.",
                    400);
        }
        accelerators.delete(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeAcceleratorAttributes(JsonNode request) {
        requireObject(request);
        return wrapAttributes(requireAccelerator(requireText(request, "AcceleratorArn")));
    }

    public synchronized ObjectNode updateAcceleratorAttributes(JsonNode request) {
        requireObject(request);
        Accelerator accelerator = requireAccelerator(requireText(request, "AcceleratorArn"));
        if (request.has("FlowLogsEnabled")) {
            boolean enabled = request.get("FlowLogsEnabled").asBoolean();
            if (enabled) {
                String bucket = optionalText(request, "FlowLogsS3Bucket");
                if (bucket == null) {
                    bucket = accelerator.getFlowLogsS3Bucket();
                }
                if (bucket == null) {
                    throw invalid("FlowLogsS3Bucket is required when FlowLogsEnabled is true.");
                }
                accelerator.setFlowLogsEnabled(true);
                accelerator.setFlowLogsS3Bucket(bucket);
                String prefix = optionalText(request, "FlowLogsS3Prefix");
                if (prefix != null) {
                    accelerator.setFlowLogsS3Prefix(prefix);
                }
            } else {
                accelerator.setFlowLogsEnabled(false);
            }
        } else {
            String bucket = optionalText(request, "FlowLogsS3Bucket");
            if (bucket != null) {
                accelerator.setFlowLogsS3Bucket(bucket);
            }
            String prefix = optionalText(request, "FlowLogsS3Prefix");
            if (prefix != null) {
                accelerator.setFlowLogsS3Prefix(prefix);
            }
        }
        accelerator.setLastModifiedTime(nowSeconds());
        accelerators.put(accelerator.getAcceleratorArn(), accelerator);
        return wrapAttributes(accelerator);
    }

    public synchronized ObjectNode createListener(JsonNode request) {
        requireObject(request);
        String acceleratorArn = requireText(request, "AcceleratorArn");
        requireAccelerator(acceleratorArn);
        String token = requireText(request, "IdempotencyToken");
        Listener existingToken = findListenerByToken(acceleratorArn, token);
        if (existingToken != null) {
            return wrapListener(existingToken);
        }
        List<Listener.PortRange> ranges = readPortRanges(request, true);
        String protocol = requireText(request, "Protocol").toUpperCase();
        if (!PROTOCOLS.contains(protocol)) {
            throw invalid("Protocol must be TCP or UDP.");
        }
        String affinity = optionalText(request, "ClientAffinity");
        if (affinity == null) {
            affinity = "NONE";
        }
        affinity = affinity.toUpperCase();
        if (!CLIENT_AFFINITIES.contains(affinity)) {
            throw invalid("ClientAffinity must be NONE or SOURCE_IP.");
        }
        String listenerArn = acceleratorArn + "/listener/" + newId();
        Listener listener = new Listener();
        listener.setListenerArn(listenerArn);
        listener.setAcceleratorArn(acceleratorArn);
        listener.setPortRanges(ranges);
        listener.setProtocol(protocol);
        listener.setClientAffinity(affinity);
        listener.setIdempotencyToken(token);
        listeners.put(listenerArn, listener);
        return wrapListener(listener);
    }

    public ObjectNode describeListener(JsonNode request) {
        requireObject(request);
        return wrapListener(requireListener(requireText(request, "ListenerArn")));
    }

    public ObjectNode listListeners(JsonNode request) {
        requireObject(request);
        String acceleratorArn = requireText(request, "AcceleratorArn");
        requireAccelerator(acceleratorArn);
        List<Listener> items = listenersFor(acceleratorArn);
        items.sort(Comparator.comparing(Listener::getListenerArn, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Listeners");
        for (Listener listener : page(items, request, response)) {
            list.add(listenerNode(listener));
        }
        return response;
    }

    public synchronized ObjectNode updateListener(JsonNode request) {
        requireObject(request);
        Listener listener = requireListener(requireText(request, "ListenerArn"));
        if (request.has("PortRanges")) {
            listener.setPortRanges(readPortRanges(request, true));
        }
        String protocol = optionalText(request, "Protocol");
        if (protocol != null) {
            protocol = protocol.toUpperCase();
            if (!PROTOCOLS.contains(protocol)) {
                throw invalid("Protocol must be TCP or UDP.");
            }
            listener.setProtocol(protocol);
        }
        String affinity = optionalText(request, "ClientAffinity");
        if (affinity != null) {
            affinity = affinity.toUpperCase();
            if (!CLIENT_AFFINITIES.contains(affinity)) {
                throw invalid("ClientAffinity must be NONE or SOURCE_IP.");
            }
            listener.setClientAffinity(affinity);
        }
        listeners.put(listener.getListenerArn(), listener);
        return wrapListener(listener);
    }

    public synchronized ObjectNode deleteListener(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "ListenerArn");
        requireListener(arn);
        if (!endpointGroupsFor(arn).isEmpty()) {
            throw new AwsException(
                    "AssociatedEndpointGroupFoundException",
                    "Endpoint groups are still associated with the listener.",
                    400);
        }
        listeners.delete(arn);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode createEndpointGroup(JsonNode request) {
        requireObject(request);
        String listenerArn = requireText(request, "ListenerArn");
        Listener listener = requireListener(listenerArn);
        String region = requireText(request, "EndpointGroupRegion");
        String token = requireText(request, "IdempotencyToken");
        EndpointGroup existingToken = findEndpointGroupByToken(listenerArn, token);
        if (existingToken != null) {
            return wrapEndpointGroup(existingToken);
        }
        if (findEndpointGroup(listenerArn, region) != null) {
            throw new AwsException(
                    "EndpointGroupAlreadyExistsException",
                    "An endpoint group already exists for this listener in " + region + ".",
                    400);
        }
        EndpointGroup group = new EndpointGroup();
        group.setEndpointGroupArn(listenerArn + "/endpoint-group/" + newId());
        group.setListenerArn(listenerArn);
        group.setEndpointGroupRegion(region);
        group.setIdempotencyToken(token);
        applyEndpointGroupConfig(group, request, listener, true);
        endpointGroups.put(group.getEndpointGroupArn(), group);
        return wrapEndpointGroup(group);
    }

    public ObjectNode describeEndpointGroup(JsonNode request) {
        requireObject(request);
        return wrapEndpointGroup(requireEndpointGroup(requireText(request, "EndpointGroupArn")));
    }

    public ObjectNode listEndpointGroups(JsonNode request) {
        requireObject(request);
        String listenerArn = requireText(request, "ListenerArn");
        requireListener(listenerArn);
        List<EndpointGroup> items = endpointGroupsFor(listenerArn);
        items.sort(Comparator.comparing(EndpointGroup::getEndpointGroupArn, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("EndpointGroups");
        for (EndpointGroup group : page(items, request, response)) {
            list.add(endpointGroupNode(group));
        }
        return response;
    }

    public synchronized ObjectNode updateEndpointGroup(JsonNode request) {
        requireObject(request);
        EndpointGroup group = requireEndpointGroup(requireText(request, "EndpointGroupArn"));
        Listener listener = requireListener(group.getListenerArn());
        applyEndpointGroupConfig(group, request, listener, false);
        endpointGroups.put(group.getEndpointGroupArn(), group);
        return wrapEndpointGroup(group);
    }

    public synchronized ObjectNode deleteEndpointGroup(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "EndpointGroupArn");
        requireEndpointGroup(arn);
        endpointGroups.delete(arn);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode addEndpoints(JsonNode request) {
        requireObject(request);
        EndpointGroup group = requireEndpointGroup(requireText(request, "EndpointGroupArn"));
        List<EndpointGroup.EndpointDescription> added = readEndpoints(request.get("EndpointConfigurations"));
        if (added.isEmpty()) {
            throw invalid("EndpointConfigurations is required.");
        }
        for (EndpointGroup.EndpointDescription incoming : added) {
            if (findEndpoint(group, incoming.getEndpointId()) != null) {
                throw invalid("Endpoint " + incoming.getEndpointId() + " already exists in the endpoint group.");
            }
            incoming.setHealthState("HEALTHY");
            group.getEndpoints().add(incoming);
        }
        endpointGroups.put(group.getEndpointGroupArn(), group);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointGroupArn", group.getEndpointGroupArn());
        ArrayNode descriptions = response.putArray("EndpointDescriptions");
        for (EndpointGroup.EndpointDescription endpoint : added) {
            descriptions.add(endpointNode(endpoint));
        }
        return response;
    }

    public synchronized ObjectNode removeEndpoints(JsonNode request) {
        requireObject(request);
        EndpointGroup group = requireEndpointGroup(requireText(request, "EndpointGroupArn"));
        JsonNode identifiers = request.get("EndpointIdentifiers");
        if (identifiers == null || !identifiers.isArray()) {
            throw invalid("EndpointIdentifiers is required.");
        }
        for (JsonNode identifier : identifiers) {
            String endpointId = optionalText(identifier, "EndpointId");
            if (endpointId == null) {
                throw invalid("EndpointId is required.");
            }
            group.getEndpoints().removeIf(endpoint -> endpointId.equals(endpoint.getEndpointId()));
        }
        endpointGroups.put(group.getEndpointGroupArn(), group);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        requireObject(request);
        Accelerator accelerator = requireAccelerator(requireText(request, "ResourceArn"));
        accelerator.getTags().putAll(readTags(request));
        accelerators.put(accelerator.getAcceleratorArn(), accelerator);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        requireObject(request);
        Accelerator accelerator = requireAccelerator(requireText(request, "ResourceArn"));
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                if (!key.isNull()) {
                    accelerator.getTags().remove(key.asText());
                }
            }
        }
        accelerators.put(accelerator.getAcceleratorArn(), accelerator);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        requireObject(request);
        Accelerator accelerator = requireAccelerator(requireText(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        writeTags(tags, accelerator.getTags());
        return response;
    }

    private void applyEndpointGroupConfig(EndpointGroup group, JsonNode request, Listener listener, boolean creating) {
        if (request.has("TrafficDialPercentage")) {
            double dial = request.get("TrafficDialPercentage").asDouble();
            if (dial < 0 || dial > 100) {
                throw invalid("TrafficDialPercentage must be between 0 and 100.");
            }
            group.setTrafficDialPercentage(dial);
        } else if (creating) {
            group.setTrafficDialPercentage(100.0);
        }
        String protocol = optionalText(request, "HealthCheckProtocol");
        if (protocol != null) {
            protocol = protocol.toUpperCase();
            if (!HEALTH_CHECK_PROTOCOLS.contains(protocol)) {
                throw invalid("HealthCheckProtocol must be TCP, HTTP, or HTTPS.");
            }
            group.setHealthCheckProtocol(protocol);
        } else if (creating) {
            group.setHealthCheckProtocol("TCP");
        }
        Integer port = intOrNull(request, "HealthCheckPort");
        if (port != null) {
            group.setHealthCheckPort(port);
        } else if (creating) {
            group.setHealthCheckPort(defaultHealthCheckPort(listener));
        }
        String path = optionalText(request, "HealthCheckPath");
        if (path != null) {
            group.setHealthCheckPath(path);
        } else if (creating && group.getHealthCheckProtocol() != null
                && !"TCP".equals(group.getHealthCheckProtocol())) {
            group.setHealthCheckPath("/");
        }
        Integer interval = intOrNull(request, "HealthCheckIntervalSeconds");
        if (interval != null) {
            group.setHealthCheckIntervalSeconds(interval);
        } else if (creating) {
            group.setHealthCheckIntervalSeconds(30);
        }
        Integer threshold = intOrNull(request, "ThresholdCount");
        if (threshold != null) {
            group.setThresholdCount(threshold);
        } else if (creating) {
            group.setThresholdCount(3);
        }
        if (request.has("PortOverrides")) {
            group.setPortOverrides(readPortOverrides(request.get("PortOverrides")));
        } else if (creating) {
            group.setPortOverrides(new ArrayList<>());
        }
        if (request.has("EndpointConfigurations")) {
            group.setEndpoints(readEndpoints(request.get("EndpointConfigurations")));
        } else if (creating) {
            group.setEndpoints(new ArrayList<>());
        }
    }

    private int defaultHealthCheckPort(Listener listener) {
        if (listener.getPortRanges() != null && !listener.getPortRanges().isEmpty()
                && listener.getPortRanges().get(0).getFromPort() != null) {
            return listener.getPortRanges().get(0).getFromPort();
        }
        return 80;
    }

    private ObjectNode wrapAccelerator(Accelerator accelerator) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Accelerator", acceleratorNode(accelerator));
        return response;
    }

    private ObjectNode acceleratorNode(Accelerator accelerator) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AcceleratorArn", accelerator.getAcceleratorArn());
        node.put("Name", accelerator.getName());
        node.put("IpAddressType", accelerator.getIpAddressType());
        node.put("Enabled", accelerator.isEnabled());
        node.put("DnsName", accelerator.getDnsName());
        if (accelerator.getDualStackDnsName() != null) {
            node.put("DualStackDnsName", accelerator.getDualStackDnsName());
        }
        node.put("Status", accelerator.getStatus());
        node.put("CreatedTime", accelerator.getCreatedTime());
        node.put("LastModifiedTime", accelerator.getLastModifiedTime());
        ArrayNode ipSets = node.putArray("IpSets");
        ObjectNode ipv4 = ipSets.addObject();
        ipv4.put("IpFamily", "IPv4");
        ipv4.put("IpAddressFamily", "IPv4");
        ArrayNode ipv4Addresses = ipv4.putArray("IpAddresses");
        for (String ip : accelerator.getIpv4Addresses()) {
            ipv4Addresses.add(ip);
        }
        if (accelerator.getIpv6Addresses() != null && !accelerator.getIpv6Addresses().isEmpty()) {
            ObjectNode ipv6 = ipSets.addObject();
            ipv6.put("IpFamily", "IPv6");
            ipv6.put("IpAddressFamily", "IPv6");
            ArrayNode ipv6Addresses = ipv6.putArray("IpAddresses");
            for (String ip : accelerator.getIpv6Addresses()) {
                ipv6Addresses.add(ip);
            }
        }
        return node;
    }

    private ObjectNode wrapAttributes(Accelerator accelerator) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrs = response.putObject("AcceleratorAttributes");
        attrs.put("FlowLogsEnabled", accelerator.isFlowLogsEnabled());
        if (accelerator.getFlowLogsS3Bucket() != null) {
            attrs.put("FlowLogsS3Bucket", accelerator.getFlowLogsS3Bucket());
        }
        if (accelerator.getFlowLogsS3Prefix() != null) {
            attrs.put("FlowLogsS3Prefix", accelerator.getFlowLogsS3Prefix());
        }
        return response;
    }

    private ObjectNode wrapListener(Listener listener) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Listener", listenerNode(listener));
        return response;
    }

    private ObjectNode listenerNode(Listener listener) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ListenerArn", listener.getListenerArn());
        node.put("Protocol", listener.getProtocol());
        node.put("ClientAffinity", listener.getClientAffinity());
        ArrayNode ranges = node.putArray("PortRanges");
        for (Listener.PortRange range : listener.getPortRanges()) {
            ObjectNode item = ranges.addObject();
            item.put("FromPort", range.getFromPort());
            item.put("ToPort", range.getToPort());
        }
        return node;
    }

    private ObjectNode wrapEndpointGroup(EndpointGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("EndpointGroup", endpointGroupNode(group));
        return response;
    }

    private ObjectNode endpointGroupNode(EndpointGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointGroupArn", group.getEndpointGroupArn());
        node.put("EndpointGroupRegion", group.getEndpointGroupRegion());
        node.put("TrafficDialPercentage", group.getTrafficDialPercentage());
        node.put("HealthCheckProtocol", group.getHealthCheckProtocol());
        if (group.getHealthCheckPort() != null) {
            node.put("HealthCheckPort", group.getHealthCheckPort());
        }
        if (group.getHealthCheckPath() != null) {
            node.put("HealthCheckPath", group.getHealthCheckPath());
        }
        node.put("HealthCheckIntervalSeconds", group.getHealthCheckIntervalSeconds());
        node.put("ThresholdCount", group.getThresholdCount());
        ArrayNode endpoints = node.putArray("EndpointDescriptions");
        for (EndpointGroup.EndpointDescription endpoint : group.getEndpoints()) {
            endpoints.add(endpointNode(endpoint));
        }
        ArrayNode overrides = node.putArray("PortOverrides");
        for (EndpointGroup.PortOverride override : group.getPortOverrides()) {
            ObjectNode item = overrides.addObject();
            item.put("ListenerPort", override.getListenerPort());
            item.put("EndpointPort", override.getEndpointPort());
        }
        return node;
    }

    private Accelerator requireAccelerator(String arn) {
        return accelerators.get(arn).orElseThrow(() -> new AwsException(
                "AcceleratorNotFoundException",
                "Accelerator not found.",
                404));
    }

    private Listener requireListener(String arn) {
        return listeners.get(arn).orElseThrow(() -> new AwsException(
                "ListenerNotFoundException",
                "Listener not found.",
                404));
    }

    private EndpointGroup requireEndpointGroup(String arn) {
        return endpointGroups.get(arn).orElseThrow(() -> new AwsException(
                "EndpointGroupNotFoundException",
                "Endpoint group not found.",
                404));
    }

    private Accelerator findByName(String name) {
        for (Accelerator accelerator : accelerators.values()) {
            if (name.equals(accelerator.getName())) {
                return accelerator;
            }
        }
        return null;
    }

    private Accelerator findByToken(String token) {
        for (Accelerator accelerator : accelerators.values()) {
            if (token.equals(accelerator.getIdempotencyToken())) {
                return accelerator;
            }
        }
        return null;
    }

    private Listener findListenerByToken(String acceleratorArn, String token) {
        for (Listener listener : listenersFor(acceleratorArn)) {
            if (token.equals(listener.getIdempotencyToken())) {
                return listener;
            }
        }
        return null;
    }

    private EndpointGroup findEndpointGroupByToken(String listenerArn, String token) {
        for (EndpointGroup group : endpointGroupsFor(listenerArn)) {
            if (token.equals(group.getIdempotencyToken())) {
                return group;
            }
        }
        return null;
    }

    private static EndpointGroup.EndpointDescription findEndpoint(EndpointGroup group, String endpointId) {
        for (EndpointGroup.EndpointDescription endpoint : group.getEndpoints()) {
            if (endpointId.equals(endpoint.getEndpointId())) {
                return endpoint;
            }
        }
        return null;
    }

    private ObjectNode endpointNode(EndpointGroup.EndpointDescription endpoint) {
        ObjectNode item = objectMapper.createObjectNode();
        if (endpoint.getEndpointId() != null) {
            item.put("EndpointId", endpoint.getEndpointId());
        }
        if (endpoint.getWeight() != null) {
            item.put("Weight", endpoint.getWeight());
        }
        if (endpoint.getHealthState() != null) {
            item.put("HealthState", endpoint.getHealthState());
        }
        if (endpoint.getClientIPPreservationEnabled() != null) {
            item.put("ClientIPPreservationEnabled", endpoint.getClientIPPreservationEnabled());
        }
        return item;
    }

    private EndpointGroup findEndpointGroup(String listenerArn, String region) {
        for (EndpointGroup group : endpointGroupsFor(listenerArn)) {
            if (region.equals(group.getEndpointGroupRegion())) {
                return group;
            }
        }
        return null;
    }

    private List<Listener> listenersFor(String acceleratorArn) {
        List<Listener> result = new ArrayList<>();
        for (Listener listener : listeners.values()) {
            if (acceleratorArn.equals(listener.getAcceleratorArn())) {
                result.add(listener);
            }
        }
        return result;
    }

    private List<EndpointGroup> endpointGroupsFor(String listenerArn) {
        List<EndpointGroup> result = new ArrayList<>();
        for (EndpointGroup group : endpointGroups.values()) {
            if (listenerArn.equals(group.getListenerArn())) {
                result.add(group);
            }
        }
        return result;
    }

    private List<Listener.PortRange> readPortRanges(JsonNode request, boolean required) {
        JsonNode node = request.get("PortRanges");
        if (node == null || node.isNull()) {
            if (required) {
                throw invalid("PortRanges is required.");
            }
            return List.of();
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("PortRanges must contain at least one range.");
        }
        List<Listener.PortRange> ranges = new ArrayList<>();
        for (JsonNode item : node) {
            Integer from = intOrNull(item, "FromPort");
            Integer to = intOrNull(item, "ToPort");
            if (from == null || to == null) {
                throw invalid("FromPort and ToPort are required.");
            }
            if (from < 1 || to > 65535 || from > to) {
                throw new AwsException("InvalidPortRangeException", "The port range is invalid.", 400);
            }
            Listener.PortRange range = new Listener.PortRange();
            range.setFromPort(from);
            range.setToPort(to);
            ranges.add(range);
        }
        return ranges;
    }

    private List<EndpointGroup.PortOverride> readPortOverrides(JsonNode node) {
        List<EndpointGroup.PortOverride> overrides = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return overrides;
        }
        for (JsonNode item : node) {
            EndpointGroup.PortOverride override = new EndpointGroup.PortOverride();
            override.setListenerPort(intOrNull(item, "ListenerPort"));
            override.setEndpointPort(intOrNull(item, "EndpointPort"));
            overrides.add(override);
        }
        return overrides;
    }

    private List<EndpointGroup.EndpointDescription> readEndpoints(JsonNode node) {
        List<EndpointGroup.EndpointDescription> endpoints = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return endpoints;
        }
        for (JsonNode item : node) {
            String endpointId = optionalText(item, "EndpointId");
            if (endpointId == null) {
                continue;
            }
            EndpointGroup.EndpointDescription endpoint = new EndpointGroup.EndpointDescription();
            endpoint.setEndpointId(endpointId);
            Integer weight = intOrNull(item, "Weight");
            endpoint.setWeight(weight != null ? weight : 128);
            if (item.has("ClientIPPreservationEnabled")) {
                endpoint.setClientIPPreservationEnabled(item.get("ClientIPPreservationEnabled").asBoolean());
            } else {
                endpoint.setClientIPPreservationEnabled(true);
            }
            endpoint.setAttachmentArn(optionalText(item, "AttachmentArn"));
            endpoint.setHealthState("INITIAL");
            endpoints.add(endpoint);
        }
        return endpoints;
    }

    private List<String> assignIpv4(String id, JsonNode supplied) {
        List<String> addresses = new ArrayList<>();
        if (supplied != null && supplied.isArray()) {
            for (JsonNode item : supplied) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    addresses.add(item.asText());
                }
            }
        }
        if (addresses.size() >= 2) {
            return addresses.subList(0, 2);
        }
        int hash = Math.abs(id.hashCode());
        addresses.add("192.0.2." + (1 + (hash % 250)));
        addresses.add("198.51.100." + (1 + ((hash / 251) % 250)));
        return addresses;
    }

    private List<String> assignIpv6(String id) {
        int hash = Math.abs(id.hashCode());
        return List.of(
                String.format("2001:db8:1::%x", hash % 0xffff),
                String.format("2001:db8:2::%x", (hash / 17) % 0xffff));
    }

    private <T> List<T> page(List<T> items, JsonNode request, ObjectNode response) {
        int maxResults = 100;
        if (request != null && request.hasNonNull("MaxResults")) {
            maxResults = Math.max(1, request.get("MaxResults").asInt(100));
        }
        int offset = 0;
        String token = optionalText(request, "NextToken");
        if (token != null) {
            try {
                offset = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextTokenException", "The NextToken is invalid.", 400);
            }
            if (offset < 0 || offset > items.size()) {
                throw new AwsException("InvalidNextTokenException", "The NextToken is invalid.", 400);
            }
        }
        int end = Math.min(offset + maxResults, items.size());
        if (end < items.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return items.subList(offset, end);
    }

    private String acceleratorArn(String id) {
        return "arn:aws:globalaccelerator::" + regionResolver.getAccountId() + ":accelerator/" + id;
    }

    private static String acceleratorId(String arn) {
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("Request body is required.");
        }
    }

    private static void validateName(String name) {
        if (name.length() > 64 || !NAME.matcher(name).matches()) {
            throw invalid("Name must be 1-64 characters of letters, numbers, and hyphens, "
                    + "and must not begin or end with a hyphen.");
        }
    }

    private static void requireIpAddressType(String ipAddressType) {
        if (!IP_ADDRESS_TYPES.contains(ipAddressType)) {
            throw invalid("IpAddressType must be IPV4 or DUAL_STACK.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request == null ? null : request.get("Tags");
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = optionalText(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isNumber() || value.isTextual()) {
            return value.asInt();
        }
        return null;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }
}
