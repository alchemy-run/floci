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
import io.github.hectorvent.floci.services.route53profiles.Route53ProfilesService;
import io.github.hectorvent.floci.services.route53resolver.model.FirewallRuleGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DNS Firewall rule groups for Route 53 Resolver. Split from
 * {@link Route53ResolverService} so endpoint/rule work can land independently.
 */
@ApplicationScoped
public class Route53ResolverFirewallService implements Resettable {

    static final String SERVICE = Route53ResolverService.SERVICE;

    private final StorageBackend<String, FirewallRuleGroup> ruleGroups;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final Route53ProfilesService profilesService;

    @Inject
    public Route53ResolverFirewallService(
            StorageFactory storageFactory,
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            Route53ProfilesService profilesService) {
        this(
                storageFactory.create(SERVICE, "route53resolver-firewall-rule-groups.json",
                        new TypeReference<Map<String, FirewallRuleGroup>>() {
                        }),
                objectMapper,
                regionResolver,
                profilesService);
    }

    Route53ResolverFirewallService(
            StorageBackend<String, FirewallRuleGroup> ruleGroups,
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            Route53ProfilesService profilesService) {
        this.ruleGroups = ruleGroups;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.profilesService = profilesService;
    }

    @Override
    public void clear() {
        ruleGroups.clear();
    }

    public ObjectNode createFirewallRuleGroup(JsonNode request, String region) {
        requireObject(request);
        String creatorRequestId = requireText(request, "CreatorRequestId");
        String name = requireText(request, "Name");
        for (FirewallRuleGroup existing : ruleGroups.values()) {
            if (creatorRequestId.equals(existing.getCreatorRequestId())) {
                return wrap(existing);
            }
        }
        String now = Long.toString(Instant.now().getEpochSecond());
        String id = "rslvr-frg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        FirewallRuleGroup group = new FirewallRuleGroup();
        group.setId(id);
        group.setArn(regionResolver.buildArn(SERVICE, region, "firewall-rule-group/" + id));
        group.setName(name);
        group.setRuleCount(0);
        group.setStatus("COMPLETE");
        group.setOwnerId(regionResolver.getAccountId());
        group.setCreatorRequestId(creatorRequestId);
        group.setShareStatus("NOT_SHARED");
        group.setCreationTime(now);
        group.setModificationTime(now);
        ruleGroups.put(id, group);
        return wrap(group);
    }

    public ObjectNode listFirewallRuleGroups(JsonNode request) {
        List<FirewallRuleGroup> items = new ArrayList<>(ruleGroups.values());
        items.sort(Comparator.comparing(FirewallRuleGroup::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(FirewallRuleGroup::getId, Comparator.nullsLast(String::compareTo)));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("FirewallRuleGroups");
        for (FirewallRuleGroup group : items) {
            ObjectNode node = groups.addObject();
            node.put("Id", group.getId());
            node.put("Arn", group.getArn());
            node.put("Name", group.getName());
            if (group.getOwnerId() != null) {
                node.put("OwnerId", group.getOwnerId());
            }
            if (group.getCreatorRequestId() != null) {
                node.put("CreatorRequestId", group.getCreatorRequestId());
            }
            if (group.getShareStatus() != null) {
                node.put("ShareStatus", group.getShareStatus());
            }
        }
        return response;
    }

    public ObjectNode deleteFirewallRuleGroup(JsonNode request) {
        requireObject(request);
        String id = requireText(request, "FirewallRuleGroupId");
        FirewallRuleGroup group = ruleGroups.get(id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Firewall rule group " + id + " was not found.", 400,
                        Map.of("ResourceType", "FIREWALL_RULE_GROUP")));
        if (profilesService.hasResourceAssociation(group.getArn())) {
            throw new AwsException("ConflictException",
                    "Firewall rule group " + id + " is still associated with a Route 53 Profile.",
                    400);
        }
        ruleGroups.delete(id);
        return wrap(group);
    }

    private ObjectNode wrap(FirewallRuleGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", group.getId());
        node.put("Arn", group.getArn());
        node.put("Name", group.getName());
        node.put("RuleCount", group.getRuleCount());
        if (group.getStatus() != null) {
            node.put("Status", group.getStatus());
        }
        if (group.getStatusMessage() != null) {
            node.put("StatusMessage", group.getStatusMessage());
        }
        if (group.getOwnerId() != null) {
            node.put("OwnerId", group.getOwnerId());
        }
        if (group.getCreatorRequestId() != null) {
            node.put("CreatorRequestId", group.getCreatorRequestId());
        }
        if (group.getShareStatus() != null) {
            node.put("ShareStatus", group.getShareStatus());
        }
        if (group.getCreationTime() != null) {
            node.put("CreationTime", group.getCreationTime());
        }
        if (group.getModificationTime() != null) {
            node.put("ModificationTime", group.getModificationTime());
        }
        response.set("FirewallRuleGroup", node);
        return response;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", "Request body is required.", 400);
        }
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field) || request.get(field).asText().isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return request.get(field).asText();
    }
}
