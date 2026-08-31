package io.github.hectorvent.floci.services.route53resolver;

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
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.route53resolver.model.ResolverEndpoint;
import io.github.hectorvent.floci.services.route53resolver.model.ResolverEndpointIpAddress;
import io.github.hectorvent.floci.services.route53resolver.model.ResolverRule;
import io.github.hectorvent.floci.services.route53resolver.model.ResolverRuleAssociation;
import io.github.hectorvent.floci.services.route53resolver.model.TargetIp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Route 53 Resolver JSON 1.1 ({@code Route53Resolver.*}).
 *
 * <p>Endpoints become {@code OPERATIONAL} immediately so FORWARD rules can
 * attach without the live ~1–2 minute provisioning window. Deletes remove
 * the row; Get then returns {@code ResourceNotFoundException}.
 */
@ApplicationScoped
public class Route53ResolverService implements Resettable {

    static final String SERVICE = "route53resolver";
    static final String TARGET_PREFIX = "Route53Resolver.";
    private static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "INBOUND_DELEGATION");
    private static final Set<String> RULE_TYPES = Set.of("FORWARD", "SYSTEM", "RECURSIVE", "DELEGATE");
    private static final Set<String> PROTOCOLS = Set.of("Do53", "DoH", "DoH-FIPS");
    private static final Set<String> ENDPOINT_TYPES = Set.of("IPV4", "IPV6", "DUALSTACK");

    private final StorageBackend<String, ResolverEndpoint> endpoints;
    private final StorageBackend<String, ResolverRule> rules;
    private final StorageBackend<String, ResolverRuleAssociation> associations;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Ec2Service ec2Service;

    @Inject
    public Route53ResolverService(StorageFactory storageFactory, RegionResolver regionResolver,
                                  ObjectMapper objectMapper, Ec2Service ec2Service) {
        this(storageFactory.create(SERVICE, "route53resolver-endpoints.json",
                        new TypeReference<Map<String, ResolverEndpoint>>() {
                        }),
                storageFactory.create(SERVICE, "route53resolver-rules.json",
                        new TypeReference<Map<String, ResolverRule>>() {
                        }),
                storageFactory.create(SERVICE, "route53resolver-associations.json",
                        new TypeReference<Map<String, ResolverRuleAssociation>>() {
                        }),
                regionResolver, objectMapper, ec2Service);
    }

    Route53ResolverService(StorageBackend<String, ResolverEndpoint> endpoints,
                           StorageBackend<String, ResolverRule> rules,
                           StorageBackend<String, ResolverRuleAssociation> associations,
                           RegionResolver regionResolver, ObjectMapper objectMapper,
                           Ec2Service ec2Service) {
        this.endpoints = endpoints;
        this.rules = rules;
        this.associations = associations;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.ec2Service = ec2Service;
    }

    @Override
    public void clear() {
        endpoints.clear();
        rules.clear();
        associations.clear();
    }

    public synchronized ObjectNode createResolverEndpoint(JsonNode request, String region) {
        requireObject(request);
        String creatorRequestId = requireText(request, "CreatorRequestId");
        ResolverEndpoint existing = findEndpointByCreator(creatorRequestId);
        if (existing != null) {
            if ("DELETING".equals(existing.getStatus())) {
                throw exists("A resolver endpoint with CreatorRequestId " + creatorRequestId
                        + " is being deleted.", "ResolverEndpoint");
            }
            return wrapEndpoint(existing);
        }
        String direction = requireText(request, "Direction");
        if (!DIRECTIONS.contains(direction)) {
            throw invalid("Direction must be INBOUND, OUTBOUND, or INBOUND_DELEGATION.");
        }
        List<String> securityGroupIds = requireStringList(request, "SecurityGroupIds");
        if (securityGroupIds.isEmpty()) {
            throw invalid("SecurityGroupIds must contain at least one security group.");
        }
        List<ResolverEndpointIpAddress> ipAddresses = readIpAddresses(request.get("IpAddresses"), region);
        if (ipAddresses.size() < 2) {
            throw invalid("IpAddresses must contain at least two IP addresses.");
        }
        String type = optionalText(request, "ResolverEndpointType");
        if (type == null) {
            type = "IPV4";
        }
        if (!ENDPOINT_TYPES.contains(type)) {
            throw invalid("ResolverEndpointType must be IPV4, IPV6, or DUALSTACK.");
        }
        List<String> protocols = readProtocols(request.get("Protocols"));
        if (protocols.isEmpty()) {
            protocols = new ArrayList<>(List.of("Do53"));
        }
        String now = now();
        String prefix = "OUTBOUND".equals(direction) ? "rslvr-out-" : "rslvr-in-";
        String id = hexId(prefix, 16);
        ResolverEndpoint endpoint = new ResolverEndpoint();
        endpoint.setId(id);
        endpoint.setCreatorRequestId(creatorRequestId);
        endpoint.setArn(arn(region, "resolver-endpoint/" + id));
        endpoint.setName(optionalText(request, "Name"));
        endpoint.setSecurityGroupIds(securityGroupIds);
        endpoint.setDirection(direction);
        endpoint.setHostVpcId(ipAddresses.get(0).getSubnetId() == null ? "vpc-local" : hostVpc(region, ipAddresses));
        endpoint.setStatus("OPERATIONAL");
        endpoint.setStatusMessage("The resolver endpoint is operational.");
        endpoint.setCreationTime(now);
        endpoint.setModificationTime(now);
        endpoint.setResolverEndpointType(type);
        endpoint.setProtocols(protocols);
        endpoint.setIpAddresses(ipAddresses);
        endpoint.getTags().putAll(readTags(request));
        endpoints.put(id, endpoint);
        return wrapEndpoint(endpoint);
    }

    public ObjectNode getResolverEndpoint(JsonNode request) {
        requireObject(request);
        return wrapEndpoint(requireEndpoint(requireText(request, "ResolverEndpointId")));
    }

    public ObjectNode listResolverEndpoints(JsonNode request) {
        requireObject(request);
        List<ResolverEndpoint> items = new ArrayList<>(endpoints.values());
        items.sort(Comparator.comparing(ResolverEndpoint::getId, Comparator.nullsLast(String::compareTo)));
        items.removeIf(endpoint -> !matchesEndpointFilters(endpoint, request.get("Filters")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ResolverEndpoints");
        List<ResolverEndpoint> page = page(items, request, response);
        for (ResolverEndpoint endpoint : page) {
            list.add(endpointNode(endpoint));
        }
        response.put("MaxResults", maxResults(request));
        return response;
    }

    public synchronized ObjectNode updateResolverEndpoint(JsonNode request) {
        requireObject(request);
        ResolverEndpoint endpoint = requireEndpoint(requireText(request, "ResolverEndpointId"));
        String name = optionalText(request, "Name");
        if (name != null) {
            endpoint.setName(name);
        }
        String type = optionalText(request, "ResolverEndpointType");
        if (type != null) {
            if (!ENDPOINT_TYPES.contains(type)) {
                throw invalid("ResolverEndpointType must be IPV4, IPV6, or DUALSTACK.");
            }
            endpoint.setResolverEndpointType(type);
        }
        if (request.has("Protocols")) {
            List<String> protocols = readProtocols(request.get("Protocols"));
            if (protocols.isEmpty()) {
                throw invalid("Protocols must contain at least one protocol.");
            }
            endpoint.setProtocols(protocols);
        }
        endpoint.setModificationTime(now());
        endpoints.put(endpoint.getId(), endpoint);
        return wrapEndpoint(endpoint);
    }

    public synchronized ObjectNode deleteResolverEndpoint(JsonNode request) {
        requireObject(request);
        String id = requireText(request, "ResolverEndpointId");
        ResolverEndpoint endpoint = requireEndpoint(id);
        boolean inUse = rules.values().stream()
                .anyMatch(rule -> id.equals(rule.getResolverEndpointId()) && !"DELETING".equals(rule.getStatus()));
        if (inUse) {
            throw new AwsException("InvalidRequestException",
                    "Resolver endpoint " + id + " is in use by one or more resolver rules.", 400);
        }
        endpoints.delete(id);
        endpoint.setStatus("DELETING");
        return wrapEndpoint(endpoint);
    }

    public ObjectNode listResolverEndpointIpAddresses(JsonNode request) {
        requireObject(request);
        ResolverEndpoint endpoint = requireEndpoint(requireText(request, "ResolverEndpointId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("IpAddresses");
        for (ResolverEndpointIpAddress address : endpoint.getIpAddresses()) {
            list.add(ipAddressNode(address));
        }
        response.put("MaxResults", maxResults(request));
        return response;
    }

    public synchronized ObjectNode createResolverRule(JsonNode request, String region) {
        requireObject(request);
        String creatorRequestId = requireText(request, "CreatorRequestId");
        ResolverRule existing = findRuleByCreator(creatorRequestId);
        if (existing != null) {
            if ("DELETING".equals(existing.getStatus())) {
                throw exists("A resolver rule with CreatorRequestId " + creatorRequestId
                        + " is being deleted.", "ResolverRule");
            }
            return wrapRule(existing);
        }
        String ruleType = requireText(request, "RuleType");
        if (!RULE_TYPES.contains(ruleType)) {
            throw invalid("RuleType must be FORWARD, SYSTEM, RECURSIVE, or DELEGATE.");
        }
        String domainName = optionalText(request, "DomainName");
        if ("FORWARD".equals(ruleType) || "SYSTEM".equals(ruleType) || "DELEGATE".equals(ruleType)) {
            if (domainName == null) {
                throw invalid("DomainName is required.");
            }
        }
        String endpointId = optionalText(request, "ResolverEndpointId");
        List<TargetIp> targetIps = readTargetIps(request.get("TargetIps"));
        if ("FORWARD".equals(ruleType)) {
            if (endpointId == null) {
                throw invalid("ResolverEndpointId is required for FORWARD rules.");
            }
            ResolverEndpoint endpoint = requireEndpoint(endpointId);
            if (!"OPERATIONAL".equals(endpoint.getStatus())) {
                throw new AwsException("ResourceUnavailableException",
                        "Resolver endpoint " + endpointId + " is not available.", 400);
            }
            if (!"OUTBOUND".equals(endpoint.getDirection())) {
                throw invalid("FORWARD rules require an OUTBOUND resolver endpoint.");
            }
            if (targetIps.isEmpty()) {
                throw invalid("TargetIps is required for FORWARD rules.");
            }
        }
        String now = now();
        String id = hexId("rslvr-rr-", 16);
        ResolverRule rule = new ResolverRule();
        rule.setId(id);
        rule.setCreatorRequestId(creatorRequestId);
        rule.setArn(arn(region, "resolver-rule/" + id));
        rule.setDomainName(normalizeDomain(domainName));
        rule.setStatus("COMPLETE");
        rule.setStatusMessage("The resolver rule is complete.");
        rule.setRuleType(ruleType);
        rule.setName(optionalText(request, "Name"));
        rule.setTargetIps(targetIps);
        rule.setResolverEndpointId(endpointId);
        rule.setOwnerId(regionResolver.getAccountId());
        rule.setShareStatus("NOT_SHARED");
        rule.setCreationTime(now);
        rule.setModificationTime(now);
        rule.setDelegationRecord(optionalText(request, "DelegationRecord"));
        rule.getTags().putAll(readTags(request));
        rules.put(id, rule);
        return wrapRule(rule);
    }

    public ObjectNode getResolverRule(JsonNode request) {
        requireObject(request);
        return wrapRule(requireRule(requireText(request, "ResolverRuleId")));
    }

    public ObjectNode listResolverRules(JsonNode request) {
        requireObject(request);
        List<ResolverRule> items = new ArrayList<>(rules.values());
        items.sort(Comparator.comparing(ResolverRule::getId, Comparator.nullsLast(String::compareTo)));
        items.removeIf(rule -> !matchesRuleFilters(rule, request.get("Filters")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ResolverRules");
        for (ResolverRule rule : page(items, request, response)) {
            list.add(ruleNode(rule));
        }
        response.put("MaxResults", maxResults(request));
        return response;
    }

    public synchronized ObjectNode updateResolverRule(JsonNode request) {
        requireObject(request);
        ResolverRule rule = requireRule(requireText(request, "ResolverRuleId"));
        JsonNode config = request.get("Config");
        if (config == null || !config.isObject()) {
            throw invalid("Config is required.");
        }
        String name = optionalText(config, "Name");
        if (name != null) {
            rule.setName(name);
        }
        if (config.has("TargetIps")) {
            List<TargetIp> targetIps = readTargetIps(config.get("TargetIps"));
            if ("FORWARD".equals(rule.getRuleType()) && targetIps.isEmpty()) {
                throw invalid("TargetIps is required for FORWARD rules.");
            }
            rule.setTargetIps(targetIps);
        }
        String endpointId = optionalText(config, "ResolverEndpointId");
        if (endpointId != null) {
            ResolverEndpoint endpoint = requireEndpoint(endpointId);
            if (!"OPERATIONAL".equals(endpoint.getStatus())) {
                throw new AwsException("ResourceUnavailableException",
                        "Resolver endpoint " + endpointId + " is not available.", 400);
            }
            rule.setResolverEndpointId(endpointId);
        }
        rule.setModificationTime(now());
        rules.put(rule.getId(), rule);
        return wrapRule(rule);
    }

    public synchronized ObjectNode deleteResolverRule(JsonNode request) {
        requireObject(request);
        String id = requireText(request, "ResolverRuleId");
        ResolverRule rule = requireRule(id);
        boolean inUse = associations.values().stream()
                .anyMatch(association -> id.equals(association.getResolverRuleId())
                        && !"DELETING".equals(association.getStatus()));
        if (inUse) {
            throw new AwsException("ResourceInUseException",
                    "Resolver rule " + id + " is associated with one or more VPCs.", 400);
        }
        rules.delete(id);
        rule.setStatus("DELETING");
        return wrapRule(rule);
    }

    public ObjectNode listResolverRuleAssociations(JsonNode request) {
        requireObject(request);
        List<ResolverRuleAssociation> items = new ArrayList<>(associations.values());
        items.sort(Comparator.comparing(ResolverRuleAssociation::getId, Comparator.nullsLast(String::compareTo)));
        items.removeIf(association -> !matchesAssociationFilters(association, request.get("Filters")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ResolverRuleAssociations");
        for (ResolverRuleAssociation association : page(items, request, response)) {
            list.add(associationNode(association));
        }
        response.put("MaxResults", maxResults(request));
        return response;
    }

    public ObjectNode getResolverRuleAssociation(JsonNode request) {
        requireObject(request);
        String id = requireText(request, "ResolverRuleAssociationId");
        ResolverRuleAssociation association = associations.get(id).orElse(null);
        if (association == null) {
            throw notFound("Resolver rule association " + id + " was not found.", "ResolverRuleAssociation");
        }
        return wrapAssociation(association);
    }

    public synchronized ObjectNode associateResolverRule(JsonNode request, String region) {
        requireObject(request);
        String ruleId = requireText(request, "ResolverRuleId");
        String vpcId = requireText(request, "VPCId");
        // AWS validates the VPC first (InvalidParameterException) before the rule.
        requireVpc(region, vpcId);
        requireRule(ruleId);
        for (ResolverRuleAssociation existing : associations.values()) {
            if (ruleId.equals(existing.getResolverRuleId()) && vpcId.equals(existing.getVpcId())
                    && !"DELETING".equals(existing.getStatus())
                    && !"FAILED".equals(existing.getStatus())) {
                return wrapAssociation(existing);
            }
        }
        ResolverRuleAssociation association = new ResolverRuleAssociation();
        association.setId(hexId("rslvr-rrassoc-", 16));
        association.setResolverRuleId(ruleId);
        association.setVpcId(vpcId);
        association.setName(optionalText(request, "Name"));
        association.setStatus("COMPLETE");
        association.setStatusMessage("The association is complete.");
        associations.put(association.getId(), association);
        return wrapAssociation(association);
    }

    public synchronized ObjectNode disassociateResolverRule(JsonNode request) {
        requireObject(request);
        String ruleId = requireText(request, "ResolverRuleId");
        String vpcId = requireText(request, "VPCId");
        ResolverRuleAssociation match = null;
        for (ResolverRuleAssociation association : associations.values()) {
            if (ruleId.equals(association.getResolverRuleId()) && vpcId.equals(association.getVpcId())) {
                match = association;
                break;
            }
        }
        if (match == null) {
            throw notFound("Resolver rule association for rule " + ruleId + " and VPC " + vpcId
                    + " was not found.", "ResolverRuleAssociation");
        }
        associations.delete(match.getId());
        match.setStatus("DELETING");
        return wrapAssociation(match);
    }

    public synchronized ObjectNode tagResource(JsonNode request) {
        requireObject(request);
        Tagged tagged = requireTagged(requireText(request, "ResourceArn"));
        tagged.tags().putAll(readTags(request));
        tagged.save();
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        requireObject(request);
        Tagged tagged = requireTagged(requireText(request, "ResourceArn"));
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                if (!key.isNull()) {
                    tagged.tags().remove(key.asText());
                }
            }
        }
        tagged.save();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        requireObject(request);
        Tagged tagged = requireTagged(requireText(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        writeTags(tags, tagged.tags());
        return response;
    }

    private Tagged requireTagged(String arn) {
        for (ResolverEndpoint endpoint : endpoints.values()) {
            if (arn.equals(endpoint.getArn())) {
                return new Tagged(endpoint.getTags(), () -> endpoints.put(endpoint.getId(), endpoint));
            }
        }
        for (ResolverRule rule : rules.values()) {
            if (arn.equals(rule.getArn())) {
                return new Tagged(rule.getTags(), () -> rules.put(rule.getId(), rule));
            }
        }
        throw notFound("Resource " + arn + " was not found.", "TagResource");
    }

    private static final class Tagged {
        private final Map<String, String> tags;
        private final Runnable persist;

        Tagged(Map<String, String> tags, Runnable persist) {
            this.tags = tags;
            this.persist = persist;
        }

        Map<String, String> tags() {
            return tags;
        }

        void save() {
            persist.run();
        }
    }

    private ResolverEndpoint requireEndpoint(String id) {
        ResolverEndpoint endpoint = endpoints.get(id).orElse(null);
        if (endpoint == null) {
            throw notFound("Resolver endpoint " + id + " was not found.", "ResolverEndpoint");
        }
        return endpoint;
    }

    private ResolverRule requireRule(String id) {
        ResolverRule rule = rules.get(id).orElse(null);
        if (rule == null) {
            throw notFound("Resolver rule " + id + " was not found.", "ResolverRule");
        }
        return rule;
    }

    private void requireVpc(String region, String vpcId) {
        boolean found = ec2Service.describeVpcs(region, List.of(), Map.of()).stream()
                .anyMatch(vpc -> vpcId.equals(vpc.getVpcId()));
        if (!found) {
            throw invalid("The vpc ID '" + vpcId + "' is invalid");
        }
    }

    private ResolverEndpoint findEndpointByCreator(String creatorRequestId) {
        for (ResolverEndpoint endpoint : endpoints.values()) {
            if (creatorRequestId.equals(endpoint.getCreatorRequestId())) {
                return endpoint;
            }
        }
        return null;
    }

    private ResolverRule findRuleByCreator(String creatorRequestId) {
        for (ResolverRule rule : rules.values()) {
            if (creatorRequestId.equals(rule.getCreatorRequestId())) {
                return rule;
            }
        }
        return null;
    }

    private List<ResolverEndpointIpAddress> readIpAddresses(JsonNode node, String region) {
        if (node == null || !node.isArray()) {
            throw invalid("IpAddresses is required.");
        }
        String now = now();
        List<ResolverEndpointIpAddress> addresses = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String subnetId = optionalText(item, "SubnetId");
            if (subnetId == null) {
                throw invalid("SubnetId is required for each IP address.");
            }
            ResolverEndpointIpAddress address = new ResolverEndpointIpAddress();
            address.setIpId(hexId("rslvr-ip-", 16));
            address.setSubnetId(subnetId);
            String ip = optionalText(item, "Ip");
            if (ip == null) {
                ip = assignIp(region, subnetId, index);
            }
            address.setIp(ip);
            address.setIpv6(optionalText(item, "Ipv6"));
            address.setStatus("ATTACHED");
            address.setCreationTime(now);
            address.setModificationTime(now);
            addresses.add(address);
            index++;
        }
        return addresses;
    }

    private String hostVpc(String region, List<ResolverEndpointIpAddress> addresses) {
        for (ResolverEndpointIpAddress address : addresses) {
            Optional<Subnet> subnet = findSubnet(region, address.getSubnetId());
            if (subnet.isPresent() && subnet.get().getVpcId() != null) {
                return subnet.get().getVpcId();
            }
        }
        return "vpc-local";
    }

    private String assignIp(String region, String subnetId, int index) {
        Optional<Subnet> subnet = findSubnet(region, subnetId);
        if (subnet.isPresent() && subnet.get().getCidrBlock() != null) {
            return ipFromCidr(subnet.get().getCidrBlock(), index);
        }
        return "10.0." + index + ".10";
    }

    private Optional<Subnet> findSubnet(String region, String subnetId) {
        try {
            return ec2Service.findSubnetById(region, subnetId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String ipFromCidr(String cidr, int index) {
        String network = cidr.split("/")[0];
        String[] oct = network.split("\\.");
        if (oct.length != 4) {
            return "10.0." + index + ".10";
        }
        int last = Integer.parseInt(oct[3]);
        int host = Math.min(254, Math.max(1, last + 10 + index));
        return oct[0] + "." + oct[1] + "." + oct[2] + "." + host;
    }

    private static List<TargetIp> readTargetIps(JsonNode node) {
        List<TargetIp> targets = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return targets;
        }
        for (JsonNode item : node) {
            String ip = optionalText(item, "Ip");
            String ipv6 = optionalText(item, "Ipv6");
            if (ip == null && ipv6 == null) {
                throw invalid("Each TargetIp must specify Ip or Ipv6.");
            }
            TargetIp target = new TargetIp();
            target.setIp(ip);
            target.setIpv6(ipv6);
            Integer port = intOrNull(item, "Port");
            target.setPort(port != null ? port : 53);
            String protocol = optionalText(item, "Protocol");
            target.setProtocol(protocol != null ? protocol : "Do53");
            target.setServerNameIndication(optionalText(item, "ServerNameIndication"));
            targets.add(target);
        }
        return targets;
    }

    private static List<String> readProtocols(JsonNode node) {
        List<String> protocols = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return protocols;
        }
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText();
            if (!PROTOCOLS.contains(value)) {
                throw invalid("Protocol must be Do53, DoH, or DoH-FIPS.");
            }
            protocols.add(value);
        }
        return protocols;
    }

    private static List<String> requireStringList(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || !node.isArray()) {
            throw invalid(field + " is required.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private boolean matchesEndpointFilters(ResolverEndpoint endpoint, JsonNode filters) {
        return matchesFilters(filters, name -> switch (name) {
            case "creatorrequestid" -> valuesOf(endpoint.getCreatorRequestId());
            case "direction" -> valuesOf(endpoint.getDirection());
            case "hostvpcid" -> valuesOf(endpoint.getHostVpcId());
            case "name" -> valuesOf(endpoint.getName());
            case "status" -> valuesOf(endpoint.getStatus());
            case "ipaddresscount" -> valuesOf(Integer.toString(endpoint.getIpAddresses().size()));
            case "securitygroupid", "securitygroupids" -> endpoint.getSecurityGroupIds();
            default -> List.of();
        });
    }

    private boolean matchesRuleFilters(ResolverRule rule, JsonNode filters) {
        return matchesFilters(filters, name -> switch (name) {
            case "creatorrequestid" -> valuesOf(rule.getCreatorRequestId());
            case "domainname" -> valuesOf(rule.getDomainName(), stripDot(rule.getDomainName()));
            case "name" -> valuesOf(rule.getName());
            case "resolverendpointid" -> valuesOf(rule.getResolverEndpointId());
            case "status" -> valuesOf(rule.getStatus());
            case "type", "ruletype" -> valuesOf(rule.getRuleType());
            default -> List.of();
        });
    }

    private boolean matchesAssociationFilters(ResolverRuleAssociation association, JsonNode filters) {
        return matchesFilters(filters, name -> switch (name) {
            case "resolverruleid" -> valuesOf(association.getResolverRuleId());
            case "vpcid" -> valuesOf(association.getVpcId());
            case "name" -> valuesOf(association.getName());
            case "status" -> valuesOf(association.getStatus());
            default -> List.of();
        });
    }

    private static List<String> valuesOf(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean matchesFilters(JsonNode filters, java.util.function.Function<String, List<String>> values) {
        if (filters == null || !filters.isArray() || filters.isEmpty()) {
            return true;
        }
        for (JsonNode filter : filters) {
            String name = optionalText(filter, "Name");
            if (name == null) {
                continue;
            }
            JsonNode wanted = filter.get("Values");
            if (wanted == null || !wanted.isArray() || wanted.isEmpty()) {
                continue;
            }
            List<String> actual = values.apply(name.toLowerCase(Locale.ROOT));
            boolean hit = false;
            for (JsonNode value : wanted) {
                String expected = value.asText();
                for (String candidate : actual) {
                    if (candidate != null && candidate.equalsIgnoreCase(expected)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode wrapEndpoint(ResolverEndpoint endpoint) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResolverEndpoint", endpointNode(endpoint));
        return response;
    }

    private ObjectNode wrapRule(ResolverRule rule) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResolverRule", ruleNode(rule));
        return response;
    }

    private ObjectNode wrapAssociation(ResolverRuleAssociation association) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResolverRuleAssociation", associationNode(association));
        return response;
    }

    private ObjectNode endpointNode(ResolverEndpoint endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", endpoint.getId());
        node.put("CreatorRequestId", endpoint.getCreatorRequestId());
        node.put("Arn", endpoint.getArn());
        if (endpoint.getName() != null) {
            node.put("Name", endpoint.getName());
        }
        ArrayNode groups = node.putArray("SecurityGroupIds");
        for (String group : endpoint.getSecurityGroupIds()) {
            groups.add(group);
        }
        node.put("Direction", endpoint.getDirection());
        node.put("IpAddressCount", endpoint.getIpAddresses().size());
        if (endpoint.getHostVpcId() != null) {
            node.put("HostVPCId", endpoint.getHostVpcId());
        }
        node.put("Status", endpoint.getStatus());
        if (endpoint.getStatusMessage() != null) {
            node.put("StatusMessage", endpoint.getStatusMessage());
        }
        node.put("CreationTime", endpoint.getCreationTime());
        node.put("ModificationTime", endpoint.getModificationTime());
        if (endpoint.getResolverEndpointType() != null) {
            node.put("ResolverEndpointType", endpoint.getResolverEndpointType());
        }
        ArrayNode protocols = node.putArray("Protocols");
        for (String protocol : endpoint.getProtocols()) {
            protocols.add(protocol);
        }
        return node;
    }

    private ObjectNode ruleNode(ResolverRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", rule.getId());
        node.put("CreatorRequestId", rule.getCreatorRequestId());
        node.put("Arn", rule.getArn());
        if (rule.getDomainName() != null) {
            node.put("DomainName", rule.getDomainName());
        }
        node.put("Status", rule.getStatus());
        if (rule.getStatusMessage() != null) {
            node.put("StatusMessage", rule.getStatusMessage());
        }
        node.put("RuleType", rule.getRuleType());
        if (rule.getName() != null) {
            node.put("Name", rule.getName());
        }
        ArrayNode targets = node.putArray("TargetIps");
        for (TargetIp target : rule.getTargetIps()) {
            ObjectNode item = targets.addObject();
            if (target.getIp() != null) {
                item.put("Ip", target.getIp());
            }
            if (target.getIpv6() != null) {
                item.put("Ipv6", target.getIpv6());
            }
            if (target.getPort() != null) {
                item.put("Port", target.getPort());
            }
            if (target.getProtocol() != null) {
                item.put("Protocol", target.getProtocol());
            }
            if (target.getServerNameIndication() != null) {
                item.put("ServerNameIndication", target.getServerNameIndication());
            }
        }
        if (rule.getResolverEndpointId() != null) {
            node.put("ResolverEndpointId", rule.getResolverEndpointId());
        }
        if (rule.getOwnerId() != null) {
            node.put("OwnerId", rule.getOwnerId());
        }
        if (rule.getShareStatus() != null) {
            node.put("ShareStatus", rule.getShareStatus());
        }
        node.put("CreationTime", rule.getCreationTime());
        node.put("ModificationTime", rule.getModificationTime());
        if (rule.getDelegationRecord() != null) {
            node.put("DelegationRecord", rule.getDelegationRecord());
        }
        return node;
    }

    private ObjectNode associationNode(ResolverRuleAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", association.getId());
        node.put("ResolverRuleId", association.getResolverRuleId());
        if (association.getName() != null) {
            node.put("Name", association.getName());
        }
        node.put("VPCId", association.getVpcId());
        node.put("Status", association.getStatus());
        if (association.getStatusMessage() != null) {
            node.put("StatusMessage", association.getStatusMessage());
        }
        return node;
    }

    private ObjectNode ipAddressNode(ResolverEndpointIpAddress address) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IpId", address.getIpId());
        node.put("SubnetId", address.getSubnetId());
        if (address.getIp() != null) {
            node.put("Ip", address.getIp());
        }
        if (address.getIpv6() != null) {
            node.put("Ipv6", address.getIpv6());
        }
        node.put("Status", address.getStatus());
        if (address.getStatusMessage() != null) {
            node.put("StatusMessage", address.getStatusMessage());
        }
        node.put("CreationTime", address.getCreationTime());
        node.put("ModificationTime", address.getModificationTime());
        return node;
    }

    private <T> List<T> page(List<T> items, JsonNode request, ObjectNode response) {
        int maxResults = maxResults(request);
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

    private static int maxResults(JsonNode request) {
        if (request != null && request.hasNonNull("MaxResults")) {
            return Math.max(1, request.get("MaxResults").asInt(100));
        }
        return 100;
    }

    private String arn(String region, String resource) {
        return "arn:aws:route53resolver:" + region + ":" + regionResolver.getAccountId() + ":" + resource;
    }

    private static String hexId(String prefix, int length) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) {
            return null;
        }
        String value = domain.toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        return value + ".";
    }

    private static String stripDot(String domain) {
        if (domain == null) {
            return null;
        }
        return domain.replaceAll("\\.+$", "");
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("Request body is required.");
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
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException notFound(String message, String resourceType) {
        return new AwsException("ResourceNotFoundException", message, 400,
                Map.of("ResourceType", resourceType));
    }

    private static AwsException exists(String message, String resourceType) {
        return new AwsException("ResourceExistsException", message, 400,
                Map.of("ResourceType", resourceType));
    }
}
